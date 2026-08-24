@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field

/**
 * The column a [CrossFilterTransform] writes each row's verdict into.
 *
 * Upstream keeps the verdicts in a bitmap beside the data, indexed by a `_index` it stamps on every
 * tuple. This engine's transforms are pure functions over copied rows, so there is no stable index
 * to key a side table by; the verdict rides on the row instead, which survives being sourced into
 * another dataset and reordered exactly as the row does.
 */
internal fun crossFilterField(signal: String): String = "__crossfilter_$signal"

/**
 * `crossfilter`: which of several range queries each row fails.
 *
 * This is the indexing half of an interactive cross-filter — three histograms of the same 200,000
 * flights, each showing the rows the *other two* brushes let through. The transform itself changes
 * nothing about the data: it computes, per row, a bit for every dimension whose query the row falls
 * outside of, and publishes them for [ResolveFilterTransform] to consult. Bit *i* set means
 * dimension *i* rejected the row, which is upstream's polarity and the reason `resolvefilter`'s
 * test is for **zero**.
 *
 * A row passes dimension *i* when its value is in `[lo, hi)`. That half-open interval is not a
 * choice made here: upstream bisects a sorted index at both ends with `bisectLeft` and sets the bit
 * on everything below the first position and from the second position on, which admits `lo` and
 * excludes `hi`. A value that is not a finite number fails every comparison and so fails the query,
 * as it does upstream.
 *
 * The incremental machinery upstream carries — sorted indices, previous and current bitmaps, the
 * `mask` of which dimensions moved — exists to avoid re-scanning 200,000 rows when one brush moves
 * by a pixel. A compile evaluates the whole specification once, so none of it is observable here
 * and the straightforward scan gives the same answer.
 */
public object CrossFilterTransform : Transform {
  override val type: String = "crossfilter"

  /** `{field, dimensions}` — where the verdicts are, and how many bits of them there are. */
  override val publishesSignal: Boolean = true

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val signal = params.string("signal")
    if (signal.isNullOrEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "crossfilter needs a 'signal' to publish its verdicts under; nothing can read them " +
          "otherwise",
        operator = type,
      )
      return input
    }
    val fields = params.stringList("fields")
    val queries = (params.fields["query"] as? VegaValue.Arr)?.values.orEmpty()
    if (fields.isEmpty() || fields.size != queries.size) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "crossfilter needs one 'query' range per 'fields' entry; got ${fields.size} field(s) " +
          "and ${queries.size} quer${if (queries.size == 1) "y" else "ies"}",
        operator = type,
      )
      return input
    }
    if (fields.size > MAX_DIMENSIONS) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "crossfilter is limited to $MAX_DIMENSIONS dimensions, because the verdict is a bit per " +
          "dimension and `resolvefilter`'s 'ignore' is the matching mask; got ${fields.size}",
        operator = type,
      )
      return input
    }

    val ranges = queries.map { query ->
      val bounds = (query as? VegaValue.Arr)?.values.orEmpty()
      if (bounds.size < 2) {
        context.diagnostics.error(
          DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          "crossfilter's 'query' entries are two-element ranges",
          operator = type,
        )
        return input
      }
      JsSemantics.toNumber(bounds[0]) to JsSemantics.toNumber(bounds[1])
    }

    val verdicts = IntArray(input.size)
    for ((dimension, path) in fields.withIndex()) {
      val bit = 1 shl dimension
      val (low, high) = ranges[dimension]
      for (row in input.indices) {
        val value = JsSemantics.toNumber(input[row].field(path))
        if (!(value >= low && value < high)) verdicts[row] = verdicts[row] or bit
      }
    }

    val column = crossFilterField(signal)
    context.setSignal(
      signal,
      VegaValue.Obj(
        linkedMapOf(
          "field" to VegaValue.Str(column),
          "dimensions" to VegaValue.Num(fields.size.toDouble()),
        )
      ),
    )
    return input.mapIndexed { row, datum ->
      datum.withFields(mapOf(column to VegaValue.Num(verdicts[row].toDouble())))
    }
  }

  /** One bit per dimension in an `Int`, and `ignore` is written as a decimal mask beside it. */
  private const val MAX_DIMENSIONS = 31
}

/**
 * `resolvefilter`: keeps the rows a cross-filter's *other* dimensions let through.
 *
 * `ignore` is a bit mask of the dimensions to disregard, so a histogram of arrival delays passes
 * `ignore: 1` and shows every row the time and distance brushes admit — including the ones its own
 * brush excludes, which is what keeps the bars outside the selection visible instead of erasing the
 * chart you are dragging on. Upstream complements the mask and tests for zero; so does this.
 */
public object ResolveFilterTransform : Transform {
  override val type: String = "resolvefilter"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val filter = params.fields["filter"] as? VegaValue.Obj
    val column = filter?.field("field")?.let { it as? VegaValue.Str }?.value
    if (column == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "resolvefilter's 'filter' must be the signal a 'crossfilter' transform publishes",
        operator = type,
      )
      return input
    }
    // Everything *not* ignored has to be clear. `ignore` defaults to zero, which ignores nothing
    // and so applies every dimension.
    val keep = (params.number("ignore")?.toInt() ?: 0).inv()
    return input.filter { datum ->
      val verdict = JsSemantics.toNumber(datum.field(column))
      verdict.isFinite() && (verdict.toInt() and keep) == 0
    }
  }
}
