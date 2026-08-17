package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field
import dev.aster.vega.model.isMissing
import kotlin.math.abs

/**
 * `stack`: accumulates a field within each group, writing the span to `y0` and `y1`.
 *
 * Three behaviours were verified against upstream and are the ones a naive implementation gets
 * wrong:
 * - **Negative values stack away from zero, separately from positive ones.** For `[3, -5, 2]` the
 *   spans are `[0,3]`, `[0,-5]` and `[3,5]`, not a single running total.
 * - **`center` aligns groups against the widest one**, so a group totalling 8 in a chart whose
 *   largest total is 10 starts at 1, not 0.
 * - **`sort` changes the stacking order but not the output row order**, so tuples come back in
 *   input order carrying different spans.
 *
 * Spans are tracked by input position rather than by tuple value, so two structurally identical
 * rows still stack as two separate segments.
 */
public object StackTransform : Transform {
  override val type: String = "stack"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val path = params.string("field")
    val groupBy = params.stringList("groupby")
    val offset = params.string("offset") ?: "zero"
    val names = params.stringList("as")
    val lowName = names.getOrNull(0) ?: "y0"
    val highName = names.getOrNull(1) ?: "y1"

    // Group positions, not tuples, so duplicates stay distinct.
    val groups = LinkedHashMap<List<VegaValue>, MutableList<Int>>()
    input.forEachIndexed { index, datum ->
      groups.getOrPut(groupKey(datum, groupBy)) { mutableListOf() }.add(index)
    }

    val comparator = sortComparator(params.fields["sort"])
    val totals = groups.mapValues { (_, positions) ->
      positions.sumOf { abs(valueAt(input, it, path)) }
    }
    val widest = totals.values.maxOrNull() ?: 0.0

    val low = DoubleArray(input.size)
    val high = DoubleArray(input.size)

    for ((key, positions) in groups) {
      val ordered =
        if (comparator == null) positions
        else positions.sortedWith { a, b -> comparator.compare(input[a], input[b]) }
      val total = totals[key] ?: 0.0

      if (offset == "normalize") {
        var cursor = 0.0
        for (position in ordered) {
          val value = abs(valueAt(input, position, path))
          val next = cursor + value
          // Divide by the group total so every group spans 0..1.
          low[position] = if (total == 0.0) 0.0 else cursor / total
          high[position] = if (total == 0.0) 0.0 else next / total
          cursor = next
        }
      } else {
        // `center` shifts the whole group so it is centred against the widest group.
        val base = if (offset == "center") (widest - total) / 2.0 else 0.0
        var positive = base
        var negative = base
        for (position in ordered) {
          val value = valueAt(input, position, path)
          if (value < 0) {
            low[position] = negative
            negative += value
            high[position] = negative
          } else {
            low[position] = positive
            positive += value
            high[position] = positive
          }
        }
      }
    }

    return input.mapIndexed { index, datum ->
      datum.withFields(
        mapOf(lowName to VegaValue.Num(low[index]), highName to VegaValue.Num(high[index]))
      )
    }
  }

  /** A missing or non-finite value contributes nothing; a stack with no field counts tuples. */
  private fun valueAt(input: List<VegaValue>, index: Int, path: String?): Double {
    if (path == null) return 1.0
    val value = input[index].field(path)
    if (value.isMissing) return 0.0
    val number = JsSemantics.toNumber(value)
    return if (number.isFinite()) number else 0.0
  }
}
