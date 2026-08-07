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

    val count = ceil((stop - start) / step).toInt()
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
    // Last one wins, matching a map built by insertion.
    val index = LinkedHashMap<String, VegaValue>(table.size)
    for (row in table) {
      val id = row.field(key)
      if (!id.isMissing()) index[id.asComparableKey()] = row
    }

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

  private fun VegaValue.isMissing(): Boolean = this is VegaValue.Null
}
