package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.canonicalNumberString

/**
 * The `transform` block, one entry at a time.
 *
 * Most of these are a rename: Vega-Lite writes `{"joinaggregate": [{op, field, as}], "groupby":
 * []}` where Vega writes parallel `ops`/`fields`/`as` arrays, and the translation carries no
 * decisions. Two do carry decisions and are the reason this is a file rather than a `when`:
 *
 * - a `filter` may be an *expression* or a **predicate object**, and a predicate compiles to an
 *   expression whose exact shape upstream chose — `oneOf` becomes an `indexof(...) !== -1` rather
 *   than a chain of comparisons, and a `range` becomes `inrange`
 * - a predicate over a `timeUnit` compares *instants*, so the field is bucketed and cast on the way
 *   into the comparison rather than compared as text
 *
 * Anything not implemented is reported by name, with what a specification can do instead.
 */
internal class Transforms(
  private val diagnostics: DiagnosticCollector,
  /**
   * Where a `lookup`'s second dataset goes, answering with the name it was given.
   *
   * A join is the one transform that needs something *outside* the flow it is in: a whole other
   * dataset, declared beside this view's. The compilation owns the naming, so it hands in the way
   * to register one; without it — as when this class is used only to compile a predicate — a
   * `lookup` is reported instead.
   */
  private val registerLookup: ((VegaValue) -> String)? = null,
) {

  fun translate(transforms: List<VegaValue>, path: String): List<VegaValue> {
    val out = mutableListOf<VegaValue>()
    transforms.forEachIndexed { index, transform ->
      out += translateOne(transform, "$path[$index]")
    }
    return out
  }

  private fun translateOne(transform: VegaValue, path: String): List<VegaValue> =
    when {
      transform.has("calculate") ->
        listOf(
          obj {
            put("type", "formula")
            put("expr", transform.string("calculate"))
            put("as", transform.string("as"))
          }
        )

      transform.has("filter") -> filter(transform["filter"], path)

      transform.has("aggregate") ->
        listOf(
          obj {
            put("type", "aggregate")
            put("groupby", strings(fieldList(transform["groupby"])))
            putOps(transform.array("aggregate").orEmpty())
          }
        )

      transform.has("joinaggregate") ->
        listOf(
          obj {
            put("type", "joinaggregate")
            val entries = transform.array("joinaggregate").orEmpty()
            put("as", strings(entries.map { it.string("as") ?: "" }))
            put("ops", strings(entries.map { it.string("op") ?: "" }))
            put(
              "fields",
              arr(entries.map { entry -> entry.string("field")?.let(::str) ?: VegaValue.Null }),
            )
            // Written whenever the specification *stated* one, empty or not: an empty list and
            // silence are two different statements, and it is the statement that is carried
            // across. A boxplot over an ungrouped column states an empty one.
            if (transform.has("groupby")) put("groupby", strings(fieldList(transform["groupby"])))
          }
        )

      transform.has("window") ->
        listOf(
          obj {
            put("type", "window")
            val entries = transform.array("window").orEmpty()
            // `params` carries the argument of an operation that takes one — the *n* of `ntile`,
            // the offset of `lag` — and is null-filled for the ones that do not, because Vega reads
            // these four arrays in step.
            put("params", arr(entries.map { it["param"] ?: VegaValue.Null }))
            put("as", strings(entries.map { it.string("as") ?: "" }))
            put("ops", strings(entries.map { it.string("op") ?: "" }))
            put(
              "fields",
              arr(entries.map { entry -> entry.string("field")?.let(::str) ?: VegaValue.Null }),
            )
            // Always written, empty or not: `sortParams` builds the pair of lists whether or not
            // there is anything to sort by, and Vega reads a window's four arrays in step with it.
            put("sort", sortFields(transform["sort"]))
            transform["frame"]?.let { put("frame", it) }
            transform["ignorePeers"]?.let { put("ignorePeers", it) }
            if (transform.has("groupby")) {
              put("groupby", strings(fieldList(transform["groupby"])))
            }
          }
        )

      // Both of these **always** write their output names, filling in the defaults a specification
      // left out — `key`/`value` for a fold, and the field's own name for each flattened column.
      // Vega would default them the same way, but the names are what everything downstream groups
      // and scales by, so upstream settles them here rather than leaving two places to agree.
      transform.has("fold") ->
        listOf(
          obj {
            put("type", "fold")
            put("fields", strings(fieldList(transform["fold"])))
            val declared = (transform["as"] as? VegaValue.Arr)?.values.orEmpty()
            put(
              "as",
              strings(
                listOf(
                  (declared.getOrNull(0) as? VegaValue.Str)?.value ?: "key",
                  (declared.getOrNull(1) as? VegaValue.Str)?.value ?: "value",
                )
              ),
            )
          }
        )

      transform.has("flatten") ->
        listOf(
          obj {
            val fields = fieldList(transform["flatten"])
            put("type", "flatten")
            put("fields", strings(fields))
            val declared = (transform["as"] as? VegaValue.Arr)?.values.orEmpty()
            put(
              "as",
              strings(
                fields.mapIndexed { index, field ->
                  (declared.getOrNull(index) as? VegaValue.Str)?.value ?: field
                }
              ),
            )
          }
        )

      transform.has("pivot") ->
        listOf(
          obj {
            put("type", "pivot")
            put("field", transform.string("pivot"))
            put("value", transform.string("value"))
            if (transform.has("groupby")) {
              put("groupby", strings(fieldList(transform["groupby"])))
            }
            transform["limit"]?.let { put("limit", it) }
            transform["op"]?.let { put("op", it) }
          }
        )

      // An `impute` **transform**, as against an `impute` on a position channel: it names its own
      // field, its own key and its own grouping rather than taking them from the encoding, so it
      // is a translation rather than a derivation. A `keyvals` written as a sequence becomes the
      // expression that generates it, exactly as the encoding form does.
      transform.has("impute") -> {
        val field = transform.string("impute").orEmpty()
        val method = transform.string("method")
        val groupby = if (transform.has("groupby")) fieldList(transform["groupby"]) else emptyList()
        // Vega's `impute` fills a gap with a **constant**, and nothing else. A method that averages
        // its neighbours is therefore two steps: the gap is filled with null, a `window` computes
        // the average over the frame, and a formula writes it back over the nulls. That is what
        // `ImputeNode.assemble` does, and the `frame` belongs to the *window*, not the impute.
        val imputation = obj {
          put("type", "impute")
          put("field", field)
          put("key", transform.string("key"))
          imputeKeyvals(transform["keyvals"])?.let { put("keyvals", it) }
          put("method", "value")
          if (groupby.isNotEmpty()) put("groupby", strings(groupby))
          put(
            "value",
            if (method == null || method == "value") transform["value"] else VegaValue.Null,
          )
        }
        if (method == null || method == "value") {
          listOf(imputation)
        } else {
          listOf(
            imputation,
            obj {
              put("type", "window")
              put("as", strings(listOf("imputed_${field}_value")))
              put("ops", strings(listOf(method)))
              put("fields", strings(listOf(field)))
              put("frame", transform["frame"] ?: arr(listOf(VegaValue.Null, VegaValue.Null)))
              put("ignorePeers", VegaValue.Bool(false))
              if (groupby.isNotEmpty()) put("groupby", strings(groupby))
            },
            obj {
              put("type", "formula")
              put(
                "expr",
                "datum.$field === null ? datum.imputed_${field}_value : datum.$field",
              )
              put("as", field)
            },
          )
        }
      }

      transform.has("sample") ->
        listOf(
          obj {
            put("type", "sample")
            put("size", transform.number("sample"))
          }
        )

      // `isExtent` excludes the two transforms that take an `extent` **parameter** rather than
      // being one: a density and a regression both bound their sampling with it, and read as an
      // extent transform they lost everything else they said.
      transform.has("extent") && !transform.has("density") && !transform.has("regression") ->
        listOf(
          obj {
            put("type", "extent")
            put("field", transform.string("extent"))
            // The signal an `extent` publishes is named by **`param`**, not by `as`: it is a
            // parameter the rest of the specification reads, not a column written onto the rows.
            put("signal", transform.string("param") ?: transform.string("as"))
          }
        )

      transform.has("quantile") ->
        listOf(
          obj {
            put("type", "quantile")
            put("field", transform.string("quantile"))
            if (transform.has("groupby")) {
              put("groupby", strings(fieldList(transform["groupby"])))
            }
            transform["probs"]?.let { put("probs", it) }
            transform["step"]?.let { put("step", it) }
            asPair(transform["as"])?.let { put("as", it) }
          }
        )

      transform.has("density") ->
        listOf(
          obj {
            put("type", "kde")
            put("field", transform.string("density"))
            if (transform.has("groupby")) {
              put("groupby", strings(fieldList(transform["groupby"])))
            }
            for (key in
              listOf(
                "cumulative",
                "counts",
                "bandwidth",
                "extent",
                "minsteps",
                "maxsteps",
                "steps",
              )) {
              transform[key]?.let { put(key, it) }
            }
            // A density always names its two output columns, defaulting to the pair the
            // constructor supplies, because the chart that reads them was written against those
            // names — `value` for the sampled point and `density` for its estimate.
            put("as", asPair(transform["as"]) ?: strings(listOf("value", "density")))
            // `resolve` decides whether grouped densities are estimated over one shared extent or
            // each over its own; upstream states it either way rather than leaving Vega's default.
            put("resolve", transform["resolve"] ?: VegaValue.Str("shared"))
          }
        )

      transform.has("regression") || transform.has("loess") -> {
        val isLoess = transform.has("loess")
        listOf(
          obj {
            put("type", if (isLoess) "loess" else "regression")
            put("y", transform.string(if (isLoess) "loess" else "regression"))
            put("x", transform.string("on"))
            if (transform.has("groupby")) {
              put("groupby", strings(fieldList(transform["groupby"])))
            }
            for (key in listOf("bandwidth", "method", "order", "extent", "params")) {
              transform[key]?.let { put(key, it) }
            }
            // Both fits always name their output columns, defaulting to the two they were computed
            // from: the sampled `on` and the measure being fitted. The chart that reads them was
            // written against those names, so upstream settles them here rather than leaving Vega
            // to.
            val declared = (transform["as"] as? VegaValue.Arr)?.values.orEmpty()
            put(
              "as",
              strings(
                listOfNotNull(
                  (declared.getOrNull(0) as? VegaValue.Str)?.value ?: transform.string("on"),
                  (declared.getOrNull(1) as? VegaValue.Str)?.value
                    ?: transform.string(if (isLoess) "loess" else "regression"),
                )
              ),
            )
          }
        )
      }

      transform.has("lookup") -> lookup(transform, path)

      else -> {
        diagnostics.error(
          VegaLiteDiagnostics.UNSUPPORTED_TRANSFORM,
          "This transform is not implemented: " +
            "${transform.asObject?.fields?.keys?.joinToString(", ")}. Implemented are calculate, " +
            "filter, aggregate, joinaggregate, window, fold, flatten, pivot, sample, extent, " +
            "quantile, density, regression, loess and lookup.",
          jsonPath = path,
        )
        emptyList()
      }
    }

  /**
   * `lookup` joins a second dataset in, which needs that dataset declared before this one.
   *
   * Reported rather than approximated: the join itself is a Vega transform, but the *dataset* it
   * reads has to be assembled and named alongside this view's, and that is data-flow work rather
   * than a translation.
   */
  private fun lookup(transform: VegaValue, path: String): List<VegaValue> {
    val register = registerLookup
    val from = transform.obj("from")
    val data = from?.get("data")
    if (register == null || from == null || data == null) {
      diagnostics.error(
        VegaLiteDiagnostics.UNSUPPORTED_TRANSFORM,
        "A `lookup` needs a `from.data` naming the dataset to join, assembled and named beside " +
          "this view's own.",
        jsonPath = path,
      )
      return emptyList()
    }
    val key = from.string("key")
    val values = from.array("fields")
    if (key == null || values == null) {
      diagnostics.error(
        VegaLiteDiagnostics.UNSUPPORTED_TRANSFORM,
        "A `lookup` needs `from.key` — the column to match on — and `from.fields` — the columns " +
          "to bring across. A selection lookup, which has neither, is not implemented.",
        jsonPath = path,
      )
      return emptyList()
    }
    val local = transform.string("lookup")
    if (local == null) {
      diagnostics.error(
        VegaLiteDiagnostics.UNSUPPORTED_TRANSFORM,
        "A `lookup` names the *local* column to match with; this one names none.",
        jsonPath = path,
      )
      return emptyList()
    }
    // The two `fields` are different fields, which is the one thing to get right here: Vega's
    // `fields` are the rows' own columns to match on and its `values` are what to bring across,
    // while Vega-Lite's `lookup` is the first and `from.fields` the second.
    return listOf(
      obj {
        put("type", "lookup")
        put("from", register(data))
        put("key", key)
        put("fields", strings(listOf(local)))
        put("values", strings(values.mapNotNull { (it as? VegaValue.Str)?.value }))
        transform["as"]?.let { put("as", it) }
        transform["default"]?.let { put("default", it) }
      }
    )
  }

  /**
   * The parses a `filter` implies, from `getImplicitFromFilterTransform`.
   *
   * Comparing a column against `"Morris"` says that column holds text, and against `2012` that it
   * holds a number — which the data may not already know, since a CSV holds only strings. Without
   * this a numeric comparison against a CSV column silently matches nothing.
   */
  fun implicitParses(transforms: List<VegaValue>): Map<String, String> {
    val parses = LinkedHashMap<String, String>()
    for (transform in transforms) {
      if (!transform.has("filter")) continue
      collectParses(transform["filter"], parses)
    }
    return parses
  }

  /**
   * The columns the specification's own transforms write, which are never parsed.
   *
   * `ancestorParse` in `data/parse.ts` records each transform's produced fields as *derived* as it
   * walks the list, and a derived field is dropped from the implicit parse below it: a column a
   * `density` computed is already a number, and asking the loader to parse it would name a column
   * the source table never had.
   */
  fun producedFields(transforms: List<VegaValue>, source: VegaValue? = null): Set<String> {
    val produced = LinkedHashSet<String>()
    // A generated column is derived too: nothing loaded it, so nothing has to parse it.
    source?.obj("sequence")?.let { produced += it.string("as") ?: "data" }
    for (transform in transforms) {
      val stated = transform["as"]
      when (stated) {
        is VegaValue.Str -> produced += stated.value
        is VegaValue.Arr -> produced += stated.values.mapNotNull { (it as? VegaValue.Str)?.value }
        else -> Unit
      }
      // The transforms that name their output only by convention.
      when {
        transform.has("density") -> produced += listOf("value", "density")
        transform.has("quantile") -> produced += listOf("prob", "value")
        transform.has("fold") -> produced += listOf("key", "value")
        transform.has("regression") ->
          produced += listOfNotNull(transform.string("regression"), transform.string("on"))
        transform.has("loess") ->
          produced += listOfNotNull(transform.string("loess"), transform.string("on"))
      }
      // An aggregate, a window and a join-aggregate name each output in their own list.
      for (key in listOf("aggregate", "window", "joinaggregate")) {
        transform.array(key).orEmpty().forEach { entry ->
          (entry as? VegaValue.Obj)?.string("as")?.let { produced += it }
        }
      }
      transform.obj("lookup")?.let {
        produced += it.array("fields").orEmpty().mapNotNull { f -> (f as? VegaValue.Str)?.value }
      }
      transform.string("extent")?.let { produced += transform.string("param") ?: it }
    }
    return produced
  }

  /**
   * `processSequence`: a `keyvals` given as `{start, stop, step}` is a *generated* list.
   *
   * Vega has an expression for one and no transform property, so it becomes a signal. A list
   * written out passes through as it stands.
   */
  fun imputeKeyvals(stated: VegaValue?): VegaValue? =
    when (stated) {
      null -> null
      is VegaValue.Obj -> {
        val parts =
          listOfNotNull(
            Fields.expressionNumber(stated.number("start") ?: 0.0),
            stated.number("stop")?.let { Fields.expressionNumber(it) },
            stated.number("step")?.let { Fields.expressionNumber(it) },
          )
        signalRef("sequence(${parts.joinToString(",")})")
      }
      else -> stated
    }

  private fun collectParses(predicate: VegaValue?, into: MutableMap<String, String>) {
    when {
      predicate !is VegaValue.Obj -> return
      predicate.has("and") -> predicate.array("and").orEmpty().forEach { collectParses(it, into) }
      predicate.has("or") -> predicate.array("or").orEmpty().forEach { collectParses(it, into) }
      predicate.has("not") -> collectParses(predicate["not"], into)
      predicate.has("field") -> {
        val field = predicate.string("field") ?: return
        val value =
          listOf("equal", "lte", "lt", "gt", "gte").firstNotNullOfOrNull { predicate.fields[it] }
            ?: (predicate.fields["range"] as? VegaValue.Arr)?.values?.firstOrNull()
            ?: (predicate.fields["oneOf"] as? VegaValue.Arr)?.values?.firstOrNull()
        when {
          predicate.string("timeUnit") != null -> into[field] = "date"
          value is VegaValue.Obj -> into[field] = "date"
          value is VegaValue.Num -> into[field] = "number"
          value is VegaValue.Str -> into[field] = "string"
          else -> Unit
        }
      }
    }
  }

  /** A `filter`: an expression as written, or a predicate compiled into one. */
  private fun filter(filter: VegaValue?, path: String): List<VegaValue> {
    val expression = predicateExpression(filter, path) ?: return emptyList()
    return listOf(
      obj {
        put("type", "filter")
        put("expr", expression)
      }
    )
  }

  /**
   * A predicate as an expression, for anything that *tests* a row rather than filtering on it.
   *
   * A conditional encoding's `test` is written in the same grammar as a `filter` and compiles to
   * the same expression, so it goes through the same function. Two spellings of `oneOf` would agree
   * until one of them was corrected.
   */
  fun testExpression(predicate: VegaValue?, path: String): String? =
    predicateExpression(predicate, path, subject = "test")

  private fun predicateExpression(
    predicate: VegaValue?,
    path: String,
    subject: String = "filter",
  ): String? =
    when {
      predicate is VegaValue.Str -> predicate.value
      predicate is VegaValue.Obj && predicate.has("and") ->
        composite(predicate.array("and").orEmpty(), " && ", path, subject)
      predicate is VegaValue.Obj && predicate.has("or") ->
        composite(predicate.array("or").orEmpty(), " || ", path, subject)
      predicate is VegaValue.Obj && predicate.has("not") ->
        predicateExpression(predicate.fields["not"], path, subject)?.let { "!($it)" }
      predicate is VegaValue.Obj && predicate.has("field") -> fieldPredicate(predicate, path)
      else -> {
        diagnostics.error(
          VegaLiteDiagnostics.UNSUPPORTED_TRANSFORM,
          "A `$subject` must be an expression, a field predicate, or `and`/`or`/`not` over them. " +
            "A selection predicate needs parameters, which are not implemented.",
          jsonPath = path,
        )
        null
      }
    }

  private fun composite(
    parts: List<VegaValue>,
    joiner: String,
    path: String,
    subject: String,
  ): String? {
    val expressions = parts.mapNotNull { predicateExpression(it, path, subject) }
    if (expressions.isEmpty()) return null
    return expressions.joinToString(joiner) { "($it)" }
  }

  /** `fieldFilterExpression` — the shapes upstream chose, not equivalents of them. */
  private fun fieldPredicate(predicate: VegaValue.Obj, path: String): String? {
    val field = predicate.string("field") ?: return null
    val timeUnit = predicate.string("timeUnit")
    // A bucketed comparison is between *instants*, so the field is bucketed and cast to a number
    // on the way in — which is what lets `===` and `indexof` compare dates at all.
    // A bucketed comparison is between *instants*, so the field is bucketed and cast to a number
    // on the way in. A **binned** unit needs no bucketing — the column already holds the bucket —
    // so only the cast is left.
    val fieldExpr =
      when {
        timeUnit == null -> "datum[${quoted(field)}]"
        Fields.isBinnedTimeUnit(timeUnit) -> "time(datum[${quoted(field)}])"
        else -> "time(${timeUnitExpression(timeUnit, field)})"
      }

    predicate.fields["equal"]?.let {
      return "$fieldExpr===${literal(it, timeUnit)}"
    }
    predicate.fields["lt"]?.let {
      return "$fieldExpr<${literal(it, timeUnit)}"
    }
    predicate.fields["gt"]?.let {
      return "$fieldExpr>${literal(it, timeUnit)}"
    }
    predicate.fields["lte"]?.let {
      return "$fieldExpr<=${literal(it, timeUnit)}"
    }
    predicate.fields["gte"]?.let {
      return "$fieldExpr>=${literal(it, timeUnit)}"
    }
    (predicate.fields["oneOf"] as? VegaValue.Arr)?.let { values ->
      val list = values.values.joinToString(",") { literal(it, timeUnit) }
      return "indexof([$list], $fieldExpr) !== -1"
    }
    (predicate.fields["range"] as? VegaValue.Arr)?.let { range ->
      val lower = range.values.getOrNull(0)
      val upper = range.values.getOrNull(1)
      if (lower != null && lower != VegaValue.Null && upper != null && upper != VegaValue.Null) {
        return "inrange($fieldExpr, [${literal(lower, timeUnit)}, ${literal(upper, timeUnit)}])"
      }
      val parts = mutableListOf<String>()
      if (lower != null && lower != VegaValue.Null)
        parts += "$fieldExpr >= ${literal(lower, timeUnit)}"
      if (upper != null && upper != VegaValue.Null)
        parts += "$fieldExpr <= ${literal(upper, timeUnit)}"
      return if (parts.isEmpty()) "true" else parts.joinToString(" && ")
    }
    predicate.fields["valid"]?.let { valid ->
      return if (valid == VegaValue.Bool(false)) {
        "!(isValid($fieldExpr) && isFinite(+$fieldExpr))"
      } else {
        "isValid($fieldExpr) && isFinite(+$fieldExpr)"
      }
    }

    diagnostics.error(
      VegaLiteDiagnostics.UNSUPPORTED_TRANSFORM,
      "A field predicate needs one of `equal`, `lt`, `lte`, `gt`, `gte`, `range`, `oneOf` or " +
        "`valid`; this one has none of them.",
      jsonPath = path,
    )
    return null
  }

  /**
   * A bucketed instant, rebuilt from the field's own parts — `fieldExpr` in `timeunit.ts`.
   *
   * A predicate on a time unit cannot ask Vega to bucket the column and compare that: it builds the
   * bucket itself out of `year(datum.date)` and friends and hands the result to `datetime`, so the
   * comparison is between two numbers. A quarter counts from zero here and is multiplied into a
   * month, and every part the unit does not mention takes the same default a bare `datetime` does —
   * 2012 for the year, so a day-of-week bucket falls in a leap year beginning on a Sunday.
   */
  private fun timeUnitExpression(timeUnit: String, field: String): String {
    val utc = if (timeUnit.startsWith("utc")) "utc" else ""
    val present = Fields.timeUnitParts(timeUnit.removePrefix("utc")).toSet()
    val access = "datum[${quoted(field)}]"
    fun part(name: String) =
      if (name == "quarter") "($utc" + "quarter($access)-1)" else "$utc$name($access)"
    val year = if ("year" in present) part("year") else "2012"
    val month =
      when {
        "month" in present -> part("month")
        "quarter" in present -> "${part("quarter")}*3"
        else -> "0"
      }
    val date =
      when {
        "date" in present -> part("date")
        "day" in present -> "${part("day")}+1"
        else -> "1"
      }
    val rest =
      listOf("hours", "minutes", "seconds", "milliseconds").map {
        if (it in present) part(it) else "0"
      }
    val arguments = (listOf(year, month, date) + rest).joinToString(", ")
    return if (utc.isEmpty()) "datetime($arguments)" else "utc($arguments)"
  }

  /** The single local units a bare number is read *as*, rather than as a millisecond count. */
  private val SINGLE_TIME_UNITS =
    setOf(
      "year",
      "quarter",
      "month",
      "date",
      "day",
      "hours",
      "minutes",
      "seconds",
      "milliseconds",
    )

  private fun literal(value: VegaValue, timeUnit: String?): String =
    when {
      // A **number** compared against a single time unit is that unit's own value, not an instant:
      // `2006` under a `year` bucket is the year 2006, so `valueExpr` expands it through
      // `dateTimeToExpr({year: 2006})` rather than reading it as milliseconds since the epoch. The
      // cut-off is upstream's: below ten thousand it cannot plausibly be a timestamp.
      value is VegaValue.Num && timeUnit in SINGLE_TIME_UNITS && value.value < 10_000 ->
        "time(datetime(${dateTimeArguments(obj { put(timeUnit!!, value) })}))"
      value is VegaValue.Num && timeUnit != null ->
        "time(datetime(${canonicalNumberString(value.value)}))"
      value is VegaValue.Str && timeUnit in SINGLE_TIME_UNITS && !looksLikeADate(value.value) ->
        "time(datetime(${dateTimeArguments(obj { put(timeUnit!!, value) })}))"
      value is VegaValue.Str && timeUnit != null -> "time(datetime(${quoted(value.value)}))"
      // Upstream writes these with `JSON.stringify`, so they are double-quoted.
      value is VegaValue.Str -> quoted(value.value)
      value is VegaValue.Num -> canonicalNumberString(value.value)
      value is VegaValue.Bool -> value.value.toString()
      // A date-time literal is compared as a number too, so it takes the same `time()` wrapper the
      // field does — `predicateValueExpr` passes `wrapTime`.
      value is VegaValue.Obj -> "time(datetime(${dateTimeArguments(value)}))"
      else -> "null"
    }

  /**
   * A `{"year": 2012, "month": "jan"}` literal, in the argument order `datetime` takes.
   *
   * `dateTimeParts`: the missing parts are not zeroes throughout — a year defaults to **2012**, a
   * month to zero *or* to a quarter times three, and a date to one *or* to a day plus one, which is
   * the arithmetic that makes a bare `{"day": "mon"}` land on a Monday.
   */
  /** `dateTimeToExpr`: the instant a `DateTime` object names, as an expression. */
  fun dateTimeExpression(value: VegaValue.Obj): String =
    if (value.fields["utc"] == VegaValue.Bool(true)) "utc(${dateTimeArguments(value)})"
    else "datetime(${dateTimeArguments(value)})"

  private fun dateTimeArguments(value: VegaValue.Obj): String {
    fun number(key: String): String? =
      when (val part = value.fields[key]) {
        is VegaValue.Num -> canonicalNumberString(part.value)
        is VegaValue.Str -> monthOrDay(key, part.value)
        else -> null
      }

    /**
     * The same, one off — `normalizeMonth` and `normalizeQuarter`.
     *
     * A specification writes a **1-based** month and quarter where `datetime()` counts months from
     * zero, so a number is shifted. A *name* is not: `monthOrDay` has already resolved `"may"` to
     * the index Vega wants, and shifting it again reads May as April.
     */
    fun oneBased(key: String): String? =
      when (val part = value.fields[key]) {
        is VegaValue.Num -> Fields.expressionNumber(part.value - 1)
        else -> number(key)
      }
    val year = number("year") ?: "2012"
    // `normalizeMonth`/`normalizeQuarter`: a specification writes a **1-based** month and quarter,
    // where `datetime()` counts months from zero. Passing the number through gave every dated
    // comparison a month too late — January read as February.
    val month = oneBased("month") ?: oneBased("quarter")?.let { "$it*3" } ?: "0"
    val date = number("date") ?: number("day")?.let { "$it+1" } ?: "1"
    val rest = listOf("hours", "minutes", "seconds", "milliseconds").map { number(it) ?: "0" }
    return (listOf(year, month, date) + rest).joinToString(", ")
  }

  /**
   * Whether a string is an instant rather than the name of a unit's value.
   *
   * Upstream asks `Date.parse`; a leading digit is the same question asked of the strings a
   * specification actually writes — `"2006"` and `"2006-01-01"` are instants, `"jan"` is not.
   */
  private fun looksLikeADate(text: String): Boolean = text.firstOrNull()?.isDigit() == true

  /**
   * `jan` is month zero and `mon` is day one: upstream normalises the names before writing them.
   */
  private fun monthOrDay(key: String, name: String): String {
    val lower = name.lowercase().take(3)
    val index =
      when (key) {
        "month" ->
          listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
            .indexOf(lower)
        "day" -> listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat").indexOf(lower)
        else -> -1
      }
    return if (index >= 0) index.toString() else quoted(name)
  }

  private fun ObjectBuilder.putOps(entries: List<VegaValue>) {
    put("ops", strings(entries.map { it.string("op") ?: "" }))
    // A `count` counts rows and has no field, whatever a specification wrote there — `"*"` is the
    // conventional spelling of "all of them" and upstream drops it rather than passing it on.
    put(
      "fields",
      arr(
        entries.map { entry ->
          if (entry.string("op") == "count") VegaValue.Null
          else entry.string("field")?.let(::str) ?: VegaValue.Null
        }
      ),
    )
    put("as", strings(entries.map { it.string("as") ?: "" }))
  }

  private fun fieldList(value: VegaValue?): List<String> =
    when (value) {
      is VegaValue.Arr -> value.values.mapNotNull { (it as? VegaValue.Str)?.value }
      is VegaValue.Str -> listOf(value.value)
      else -> emptyList()
    }

  private fun asPair(value: VegaValue?): VegaValue? =
    when (value) {
      is VegaValue.Arr -> strings(value.values.mapNotNull { (it as? VegaValue.Str)?.value })
      is VegaValue.Str -> strings(listOf(value.value))
      else -> null
    }

  /** `sort: [{field, order}]` becomes Vega's parallel `field`/`order` arrays. */
  private fun sortFields(value: VegaValue?): VegaValue {
    val entries = (value as? VegaValue.Arr)?.values.orEmpty()
    return obj {
      put("field", strings(entries.mapNotNull { it.string("field") }))
      put("order", strings(entries.map { it.string("order") ?: "ascending" }))
    }
  }
}
