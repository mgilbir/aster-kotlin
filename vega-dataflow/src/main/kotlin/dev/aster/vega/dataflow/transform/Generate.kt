package dev.aster.vega.dataflow.transform

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field
import kotlin.math.ceil

/**
 * `sequence`: a dataset made of numbers rather than read from anywhere.
 *
 * How a specification draws a function — a sine curve, a reference grid, an axis of its own — with
 * no data to bind to. It replaces whatever reached it rather than extending it, which is why it is
 * only ever the first transform in a pipeline.
 *
 * `stop` is **exclusive**, so `{start: 0, stop: 5}` is five rows and not six, and each row is an
 * object with one field named `data` unless `as` says otherwise. Both were probed rather than
 * assumed; an inclusive stop is the commoner convention and would put an extra point on every
 * curve.
 */
public object SequenceTransform : Transform {
  override val type: String = "sequence"

  /**
   * How many rows one `sequence` may generate.
   *
   * The same number as the expression function's `MAX_SEQUENCE`, deliberately: they are the same
   * operation and a document can reach either. A hundred thousand rows is far more than any chart
   * draws and far less than the heap.
   */
  private const val MAX_SEQUENCE: Int = 100_000

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val start = params.number("start") ?: 0.0
    val stop = params.number("stop")
    val step = params.number("step") ?: 1.0
    val name = params.string("as") ?: "data"

    if (stop == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "sequence needs a 'stop'",
        operator = type,
      )
      return input
    }
    if (step == 0.0 || !step.isFinite() || !start.isFinite() || !stop.isFinite()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "sequence needs finite bounds and a non-zero step",
        operator = type,
      )
      return input
    }

    // **Bounded**, and this is the last of the resources a pasted document could exhaust. The
    // count comes straight from three numbers a specification wrote, so
    // `{"type": "sequence", "start": 0, "stop": 1e9}` asked for a billion rows — an
    // `OutOfMemoryError` about four seconds later, which is an `Error` and therefore not something
    // `SpecCompiler`'s guard catches (an `Error` is not a failed compile) and not something
    // Kotlin/Native could catch at all. The same shape as the stack overflows, one resource over.
    //
    // The expression function of the same name has been bounded at `MAX_SEQUENCE` since it was
    // written — "a runaway step cannot spin forever; no axis has this many boundaries" — and the
    // *transform* was not, which is the asymmetry rather than the number being wrong. Same limit,
    // for the same reason, and reported rather than truncated silently: half a sequence is a wrong
    // chart where a refusal is a clear one.
    val exact = ceil((stop - start) / step)
    if (exact > MAX_SEQUENCE) {
      context.diagnostics.error(
        DiagnosticCodes.COMPILE_LIMIT_EXCEEDED,
        "sequence asks for ${exact.toLong()} rows, and the limit is $MAX_SEQUENCE. Widen the step " +
          "or narrow the bounds.",
        operator = type,
      )
      return emptyList()
    }
    val count = exact.toInt()
    if (count <= 0) return emptyList()
    // Multiplied out from the start rather than accumulated, so a fractional step does not drift:
    // ten additions of 0.1 do not reach 1.0, and d3 counts the same way for the same reason.
    return (0 until count).map { index ->
      VegaValue.Obj(mapOf(name to VegaValue.Num(start + step * index)))
    }
  }
}

/**
 * `lookup`: joins each row to a matching one in another dataset.
 *
 * Two shapes, and the difference is whether `values` is given:
 * - **with `values`**, the named fields are copied out of the matched row, and `as` renames them;
 *   with no `as` they keep their own names.
 * - **without `values`**, the whole matched row is written into the single field `as` names, which
 *   is how a specification reaches a nested object from an expression.
 *
 * A row that matches nothing gets `default`, or null. Upstream indexes the lookup table into a map,
 * so a duplicated key resolves to the **last** row carrying it, not the first.
 */
public object LookupTransform : Transform {
  override val type: String = "lookup"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val from = params.string("from")
    val key = params.string("key")
    val fields = params.stringList("fields")
    val values = params.stringList("values")
    val names = params.stringList("as")
    val fallback = params.fields["default"] ?: VegaValue.Null

    if (from.isNullOrEmpty() || key.isNullOrEmpty() || fields.isEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "lookup needs 'from', 'key' and 'fields'",
        operator = type,
      )
      return input
    }
    if (values.isEmpty() && names.size != fields.size) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "lookup without 'values' needs one 'as' name per looked-up field, to say where the " +
          "matched row goes",
        operator = type,
      )
      return input
    }
    if (values.isNotEmpty() && fields.size > 1 && names.size != fields.size * values.size) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "a lookup on several fields needs an explicit 'as' name for every field and value pair",
        operator = type,
      )
      return input
    }

    val table = context.scope.dataset(from)
    if (table.isEmpty()) {
      context.diagnostics.warn(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "lookup table '$from' is empty or unknown; every row took the default",
        operator = type,
      )
    }
    // **A null key is a key.** Upstream builds its index with `map.set(field(row), row)` and looks
    // rows up the same way, and its `fastmap` is object-backed — so a `null` key is stored under
    // `String(null)`, which is `"null"`, and a row whose key is null finds it. Probed: a lookup
    // table holding `{"k": null, "label": "NULL ROW"}` labels the null row, and only a key absent
    // from the table takes the default.
    //
    // Skipping them here meant a null could never match, so a table that deliberately provides a
    // row for "no value" — which is the ordinary way to label a missing category — silently gave
    // every such row the default instead. `asComparableKey` already spells a null the same way on
    // both sides of the join, which is why nothing else was needed.
    //
    // Last one wins, matching a map built by insertion.
    val index = LinkedHashMap<String, VegaValue>(table.size)
    for (row in table) index[row.field(key).asComparableKey()] = row

    return input.map { datum ->
      val updates = LinkedHashMap<String, VegaValue>()
      var slot = 0
      for ((index0, path) in fields.withIndex()) {
        val matched = index[datum.field(path).asComparableKey()]
        if (values.isEmpty()) {
          updates[names[index0]] = matched ?: fallback
        } else {
          for (value in values) {
            val name = names.getOrNull(slot) ?: value
            updates[name] = if (matched == null) fallback else matched.field(value)
            slot++
          }
        }
      }
      datum.withFields(updates)
    }
  }
}

/**
 * `impute`: adds the rows a series is missing, so a line does not jump the gap.
 *
 * The key domain is the union of every key in the **whole dataset**, not per group — which is the
 * point: a group is missing a key precisely when some *other* group has it. `keyvals` supplies that
 * domain explicitly instead, and can name keys nothing has, so a series can be padded out to a
 * range the data never reached.
 *
 * Two things about the output are worth knowing before reading a chart drawn from it:
 * - the new rows are **appended**, not merged into position, so anything downstream that cares
 *   about order has to sort;
 * - a new row carries only its group's fields, the key and the imputed value. Every other column is
 *   absent rather than null, which is what a mark encoding one of them will see.
 */
public object ImputeTransform : Transform {
  override val type: String = "impute"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val key = params.string("key")
    val field = params.string("field")
    if (key.isNullOrEmpty() || field.isNullOrEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "impute needs 'key' and 'field'",
        operator = type,
      )
      return input
    }
    val groupBy = params.stringList("groupby")
    val method = params.string("method") ?: "value"
    val fallback = params.fields["value"] ?: VegaValue.Num(0.0)

    val aggregate =
      when (method.lowercase()) {
        "value" -> null
        else ->
          AggregateOp.fromName(method)
            ?: run {
              context.diagnostics.error(
                DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
                "'$method' is not one of impute's methods; they are value, mean, median, " +
                  "min and max",
                operator = type,
              )
              return input
            }
      }

    // The key domain is `keyvals` **and then** the keys the data has, in first appearance order —
    // upstream's `partition` seeds the domain with `keyvals` and pushes every unseen key onto it.
    // Taking `keyvals` *instead of* the observed keys, as this did, loses a row: with
    // `keyvals: [2, 3]` over data at keys 0 and 1, a group holding only key 0 is missing 2, 3 **and
    // 1**, and upstream emits all three. Found by replaying upstream's own impute vectors.
    val explicit = (params.fields["keyvals"] as? VegaValue.Arr)?.values ?: emptyList()
    val domain =
      (explicit + input.map { it.field(key) })
        .filterNot { it is VegaValue.Null }
        .distinctBy { it.asComparableKey() }

    val groups = groupTuples(input, groupBy)
    val added = mutableListOf<VegaValue>()
    for ((groupKey, rows) in groups) {
      val present = rows.map { it.field(key).asComparableKey() }.toSet()
      val filler = if (aggregate == null) fallback else aggregateOver(aggregate, field, rows)
      for (value in domain) {
        if (value.asComparableKey() in present) continue
        val fields = LinkedHashMap<String, VegaValue>(groupBy.size + 3)
        // Upstream marks a row it invented — `t = {_impute: true}` — and puts the flag *first*. It
        // is how anything downstream tells a synthesised row from a real one: a tooltip that says
        // "no data" rather than a value, a mark that draws it hollow. Dropping the flag made the
        // two
        // indistinguishable.
        fields[IMPUTE_FLAG] = VegaValue.Bool(true)
        groupBy.forEachIndexed { index, path -> fields[path] = groupKey.values[index] }
        fields[key] = value
        fields[field] = filler
        added += VegaValue.Obj(fields)
      }
    }
    return input + added
  }

  /** Upstream's marker for a tuple `impute` invented, rather than one that was in the data. */
  private const val IMPUTE_FLAG = "_impute"
}
