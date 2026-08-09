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
            put("groupby", strings(fieldList(transform["groupby"])))
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
            sortFields(transform["sort"])?.let { put("sort", it) }
            transform["frame"]?.let { put("frame", it) }
            transform["ignorePeers"]?.let { put("ignorePeers", it) }
            if (transform.has("groupby")) {
              put("groupby", strings(fieldList(transform["groupby"])))
            }
          }
        )

      transform.has("fold") ->
        listOf(
          obj {
            put("type", "fold")
            put("fields", strings(fieldList(transform["fold"])))
            asPair(transform["as"])?.let { put("as", it) }
          }
        )

      transform.has("flatten") ->
        listOf(
          obj {
            put("type", "flatten")
            put("fields", strings(fieldList(transform["flatten"])))
            transform["as"]?.let { put("as", it) }
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

      transform.has("sample") ->
        listOf(
          obj {
            put("type", "sample")
            put("size", transform.number("sample"))
          }
        )

      transform.has("extent") ->
        listOf(
          obj {
            put("type", "extent")
            put("field", transform.string("extent"))
            put("signal", transform.string("as"))
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
            asPair(transform["as"])?.let { put("as", it) }
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
            asPair(transform["as"])?.let { put("as", it) }
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
    val fieldExpr =
      if (timeUnit != null) "time(${timeUnitExpression(timeUnit, field)})"
      else "datum[${quoted(field)}]"

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

  private fun timeUnitExpression(timeUnit: String, field: String): String {
    val parts = Fields.timeUnitParts(timeUnit).joinToString(",") { "'$it'" }
    return "timeUnit([$parts], datum[${quoted(field)}])"
  }

  private fun literal(value: VegaValue, timeUnit: String?): String =
    when {
      value is VegaValue.Str && timeUnit != null -> "time(datetime(${quoted(value.value)}))"
      // Upstream writes these with `JSON.stringify`, so they are double-quoted.
      value is VegaValue.Str -> quoted(value.value)
      value is VegaValue.Num -> canonicalNumberString(value.value)
      value is VegaValue.Bool -> value.value.toString()
      value is VegaValue.Obj -> "datetime(${dateTimeArguments(value)})"
      else -> "null"
    }

  /** A `{"year": 2012, "month": "jan"}` literal, in the argument order `datetime` takes. */
  private fun dateTimeArguments(value: VegaValue.Obj): String {
    val order = listOf("year", "month", "date", "hours", "minutes", "seconds", "milliseconds")
    return order.joinToString(", ") { key ->
      when (val part = value.fields[key]) {
        null -> "0"
        is VegaValue.Num -> canonicalNumberString(part.value)
        is VegaValue.Str -> quoted(part.value)
        else -> "0"
      }
    }
  }

  private fun ObjectBuilder.putOps(entries: List<VegaValue>) {
    put("ops", strings(entries.map { it.string("op") ?: "" }))
    put("fields", arr(entries.map { entry -> entry.string("field")?.let(::str) ?: VegaValue.Null }))
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
  private fun sortFields(value: VegaValue?): VegaValue? {
    val entries = (value as? VegaValue.Arr)?.values ?: return null
    if (entries.isEmpty()) return null
    return obj {
      put("field", strings(entries.mapNotNull { it.string("field") }))
      put("order", strings(entries.map { it.string("order") ?: "ascending" }))
    }
  }
}
