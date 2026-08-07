package dev.aster.vega.dataflow.transform

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.field
import kotlin.math.floor

/**
 * `quantile`: the distribution of a column, as a list of probability and value pairs.
 *
 * The probabilities sit at the **middle** of each step rather than at its edges — `(i + 0.5) ×
 * step` — so the default hundred steps run from 0.005 to 0.995 and never ask for the minimum or the
 * maximum. That is what makes a quantile plot's ends behave; asking for probability 0 and 1 would
 * pin them to the two extreme observations.
 */
public object QuantileTransform : Transform {
  override val type: String = "quantile"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val field = params.string("field")
    if (field.isNullOrEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "quantile needs a 'field'",
        operator = type,
      )
      return input
    }
    val groupBy = params.stringList("groupby")
    val names = params.stringList("as")
    val probName = names.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: "prob"
    val valueName = names.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: "value"

    val explicit = params.numberList("probs").takeIf { it.isNotEmpty() }
    val step = params.number("step") ?: 0.01
    val probabilities =
      explicit
        ?: run {
          val count = floor(1.0 / step).toInt()
          (0 until count).map { (it + 0.5) * step }
        }

    return groupTuples(input, groupBy).flatMap { (groupKey, rows) ->
      val values = rows.map { it.field(field).asDouble() }.filter { it.isFinite() }.sorted()
      if (values.isEmpty()) {
        emptyList()
      } else {
        probabilities.map { probability ->
          val fields = LinkedHashMap<String, VegaValue>(groupBy.size + 2)
          groupBy.forEachIndexed { index, path -> fields[path] = groupKey[index] }
          fields[probName] = VegaValue.Num(probability)
          fields[valueName] = VegaValue.Num(quantileAt(values, probability))
          VegaValue.Obj(fields)
        }
      }
    }
  }

  /** d3's quantile: linear interpolation between the two straddling observations. */
  private fun quantileAt(sorted: List<Double>, probability: Double): Double {
    if (sorted.size == 1) return sorted[0]
    val position = (sorted.size - 1) * probability.coerceIn(0.0, 1.0)
    val lower = floor(position).toInt()
    val upper = kotlin.math.ceil(position).toInt()
    if (lower == upper) return sorted[lower]
    val weight = position - lower
    return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
  }
}

/**
 * `regression`: a fitted line through a scatter, which is what a trend line is.
 *
 * A linear fit needs only its two endpoints, because a line between them is the line — which is why
 * the output is two rows and not a sampled curve. Every other method upstream offers is a curve,
 * and upstream samples those **adaptively**, subdividing wherever the direction turns by more than
 * half a degree. That sampler is a separate algorithm and its output is not the evenly-spaced grid
 * a reader would assume, so the curved methods are reported by name rather than approximated with a
 * grid that would be visibly coarser in exactly the places curvature matters.
 *
 * `params` swaps the fitted points for the fit itself: `coef` as `[intercept, slope]` and the
 * `rSquared` that says how much of the variance it explains.
 */
public object RegressionTransform : Transform {
  override val type: String = "regression"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val xField = params.string("x")
    val yField = params.string("y")
    if (xField.isNullOrEmpty() || yField.isNullOrEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "regression needs 'x' and 'y'",
        operator = type,
      )
      return input
    }
    val method = params.string("method") ?: "linear"
    if (!method.equals("linear", ignoreCase = true)) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
        "regression method '$method' is not implemented; it is a curve, and upstream samples one " +
          "adaptively rather than on a grid, so no fitted points were produced",
        operator = type,
      )
      return input
    }

    val groupBy = params.stringList("groupby")
    val wantParams = params.boolean("params") ?: false
    val extent = params.numberList("extent").takeIf { it.size >= 2 }
    val names = params.stringList("as")
    val xName = names.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: xField
    val yName = names.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: yField

    return groupTuples(input, groupBy).flatMap { (groupKey, rows) ->
      val points =
        rows
          .map { it.field(xField).asDouble() to it.field(yField).asDouble() }
          .filter { it.first.isFinite() && it.second.isFinite() }
      if (points.size < 2) {
        emptyList()
      } else {
        val fit = leastSquares(points)
        val prefix = LinkedHashMap<String, VegaValue>(groupBy.size)
        groupBy.forEachIndexed { index, path -> prefix[path] = groupKey[index] }
        if (wantParams) {
          listOf(
            VegaValue.Obj(
              prefix +
                mapOf(
                  "coef" to
                    VegaValue.Arr(listOf(VegaValue.Num(fit.intercept), VegaValue.Num(fit.slope))),
                  "rSquared" to VegaValue.Num(fit.rSquared),
                )
            )
          )
        } else {
          val low = extent?.get(0) ?: points.minOf { it.first }
          val high = extent?.get(1) ?: points.maxOf { it.first }
          listOf(low, high).map { x ->
            VegaValue.Obj(
              prefix +
                mapOf(
                  xName to VegaValue.Num(x),
                  yName to VegaValue.Num(fit.intercept + fit.slope * x),
                )
            )
          }
        }
      }
    }
  }

  private class Fit(val intercept: Double, val slope: Double, val rSquared: Double)

  /**
   * Ordinary least squares in upstream's own arithmetic.
   *
   * The four moments are accumulated as **running means** — `X += (x - X) / n` — rather than as
   * sums divided at the end, and the slope comes from those rather than from mean-centred
   * deviations. The two are algebraically the same and differ in the last bits of a double, which
   * is enough to miss a reference vector by 1e-14; the same lesson d3's interpolation taught.
   */
  private fun leastSquares(points: List<Pair<Double, Double>>): Fit {
    var meanX = 0.0
    var meanY = 0.0
    var meanXY = 0.0
    var meanX2 = 0.0
    var n = 0
    for ((x, y) in points) {
      n++
      meanX += (x - meanX) / n
      meanY += (y - meanY) / n
      meanXY += (x * y - meanXY) / n
      meanX2 += (x * x - meanX2) / n
    }

    val delta = meanX2 - meanX * meanX
    // Upstream's own guard: a vertical scatter has no slope rather than an infinite one.
    val slope = if (kotlin.math.abs(delta) < 1e-24) 0.0 else (meanXY - meanX * meanY) / delta
    val intercept = meanY - slope * meanX

    var sse = 0.0
    var sst = 0.0
    for ((x, y) in points) {
      val residual = y - (intercept + slope * x)
      val deviation = y - meanY
      sse += residual * residual
      sst += deviation * deviation
    }
    return Fit(intercept, slope, 1.0 - sse / sst)
  }
}
