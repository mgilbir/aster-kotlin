package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field
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
    //
    // And keyed by **the JSON of the group values**, which is upstream's key verbatim:
    //
    //     k = JSON.stringify(groupby.map(get));
    //
    // rather than through the object-backed `fastmap` that `aggregate` and `window` group with. The
    // distinction that motivated writing it out was that `'' + 1001` and `'' + "1001"` are the same
    // string and `[1001]` and `["1001"]` are not, so the number and the word are two groups here
    // and one there. Keying on the raw values kept that and missed the other half: `JSON.stringify`
    // also **merges**, because JSON cannot write every double.
    //
    // A non-finite number is written `null`, so a NaN, an infinity and an actual null all key to
    // `[null]` and stack as one group — and a NaN is not exotic here, `toDate` of a word being one
    // and a `formatType: "time"` over a column of words being enough to ask for it. A negative zero
    // is written `0`, so it joins the zeroes, where a `Double`'s own `equals` holds `-0.0` apart
    // from `0.0`. Both were three groups where upstream had one, and a stack's totals are its
    // scale's domain, so the whole chart was a different height.
    //
    // [VegaJson.write] already implements `JSON.stringify`'s number rules — non-finite to `null`,
    // everything else through `Decimals.jsString` — so this is that function and not a second
    // transcription of it.
    val groups = LinkedHashMap<String, MutableList<Int>>()
    input.forEachIndexed { index, datum ->
      val key = VegaJson.write(VegaValue.Arr(groupBy.map { datum.field(it) }))
      groups.getOrPut(key) { mutableListOf() }.add(index)
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
        // Ties by creation order, upstream's `stableCompare` — see
        // [TransformContext.creationOrder]. It decides which of two equal rows is stacked nearer
        // the baseline.
        else positions.sortedWith(byCreation(input, comparator, context))
      val total = totals[key] ?: 0.0

      when (offset) {
        // `stackNormalize`: `scale = 1 / group.sum`, and one cursor over the **absolute** values.
        //
        // The reciprocal is written out rather than divided by, because that is what decides a
        // group whose values sum to nothing: `1 / 0` is Infinity, `Infinity * 0` is NaN, and a
        // mark at NaN is not drawn. Guarding the zero and answering 0 instead drew the whole group
        // flat along the baseline — a band of zero-height rectangles upstream leaves out.
        "normalize" -> {
          val scale = 1.0 / total
          var cursor = 0.0
          var running = 0.0
          for (position in ordered) {
            low[position] = cursor
            running += abs(valueAt(input, position, path))
            cursor = scale * running
            high[position] = cursor
          }
        }
        // `stackCenter`: **one** cursor, starting at `(max - sum) / 2` and advancing by the
        // absolute value.
        //
        // Not the split positive/negative cursors `zero` uses. Splitting them made a group holding
        // both signs grow in two directions from the centre line instead of one: `[3, -5]` spans
        // `[0,3]` and `[3,8]` upstream, and spanned `[0,3]` and `[0,-5]` here. The one committed
        // `center` fixture is all-positive, where the two rules agree.
        "center" -> {
          var cursor = (widest - total) / 2.0
          for (position in ordered) {
            low[position] = cursor
            cursor += abs(valueAt(input, position, path))
            high[position] = cursor
          }
        }
        // `stackZero`: two cursors, and the sign of each value picks one.
        else -> {
          var positive = 0.0
          var negative = 0.0
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
    }

    return input.mapIndexed { index, datum ->
      datum.withFields(
        mapOf(lowName to VegaValue.Num(low[index]), highName to VegaValue.Num(high[index]))
      )
    }
  }

  /**
   * `+field(t)`, and **nothing is substituted for what that comes to**.
   *
   * Upstream reads the column with a plain coercion and then adds it to a running cursor:
   * ```js
   * v = +field(t);
   * if (v < 0) { t[y0] = lastNeg; t[y1] = lastNeg += v; }
   * else       { t[y0] = lastPos; t[y1] = lastPos += v; }
   * ```
   *
   * A `NaN` is not less than zero, so it takes the positive branch and **poisons the cursor**: that
   * row gets a `y0` and no `y1`, and every row after it in the group gets neither. The totals do
   * the same, `partition` summing `Math.abs(field(g[i]))` with no guard of its own.
   *
   * This answered `0` for a value it could not read, which is a different chart rather than a
   * missing piece of one: a column of unreadable dates stacked to a flat zero, so the axis came out
   * `[0, 0]` where upstream's is `[NaN, NaN]` and draws no ticks at all. The same distinction as
   * the pie — see `Pie.valueAt` — and for the same reason, that a cursor accumulates.
   *
   * A stack with no field counts tuples, which is upstream's `field = one`.
   */
  private fun valueAt(input: List<VegaValue>, index: Int, path: String?): Double {
    if (path == null) return 1.0
    return JsSemantics.toNumber(input[index].field(path))
  }
}
