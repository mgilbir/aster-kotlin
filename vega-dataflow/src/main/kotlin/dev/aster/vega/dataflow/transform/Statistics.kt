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
          groupBy.forEachIndexed { index, path -> fields[path] = groupKey.values[index] }
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
 * `regression`: a fitted trend through a scatter.
 *
 * The seven methods divide by how their output is produced, not by how they are fitted. A `linear`
 * or `constant` fit comes back as its **two endpoints**, because a straight line between them is
 * the line and a hundred points would draw the same picture. Everything curved — `log`, `exp`,
 * `pow`, `quad`, `poly` — is instead handed to [CurveSampler], which places points where the curve
 * bends rather than on a grid, so the number of rows depends on the data and not on a parameter.
 *
 * `params` swaps the fitted points for the fit itself: `coef` in the form each method reports (an
 * intercept and a slope; for `pow`, a multiplier and an exponent; for `quad` and `poly`, the
 * polynomial's terms from the constant upward) and the `rSquared` that says how much of the
 * variance it accounts for.
 *
 * A group with no more points than the fit has parameters is **skipped with a warning** rather than
 * fitted, since such a fit passes exactly through its data and means nothing.
 */
public object RegressionTransform : Transform {
  override val type: String = "regression"

  private val METHODS = setOf("constant", "linear", "log", "exp", "pow", "quad", "poly")

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
    if (method !in METHODS) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "regression method '$method' is not one of ${METHODS.sorted().joinToString(", ")}",
        operator = type,
      )
      return input
    }

    val groupBy = params.stringList("groupby")
    val wantParams = params.boolean("params") ?: false
    val order = params.number("order")?.toInt() ?: 3
    val names = params.stringList("as")
    val xName = names.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: xField
    val yName = names.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: yField
    val dof = RegressionFits.degreesOfFreedom(method, order)

    var extent = params.numberList("extent").takeIf { it.size >= 2 }
    if (extent != null && method == "log" && extent[0] <= 0.0) {
      // A logarithm has nothing to say below zero, so the extent is dropped rather than producing
      // a run of NaN the chart would silently omit.
      context.diagnostics.warn(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "ignoring an extent starting at or below zero for a log regression",
        operator = type,
      )
      extent = null
    }

    return groupTuples(input, groupBy).flatMap { (groupKey, rows) ->
      val points = numericPairs(rows, xField, yField)
      if (points.size <= dof) {
        context.diagnostics.warn(
          DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          "skipping a regression with more parameters than data points",
          operator = type,
        )
        return@flatMap emptyList()
      }
      val model = RegressionFits.fit(method, points, order) ?: return@flatMap emptyList()
      val prefix = LinkedHashMap<String, VegaValue>(groupBy.size)
      groupBy.forEachIndexed { index, path -> prefix[path] = groupKey.values[index] }

      if (wantParams) {
        listOf(
          VegaValue.Obj(
            prefix +
              mapOf(
                "coef" to VegaValue.Arr(model.coef.map { VegaValue.Num(it) }),
                "rSquared" to VegaValue.Num(model.rSquared),
              )
          )
        )
      } else {
        val low = extent?.get(0) ?: points.minOf { it[0] }
        val high = extent?.get(1) ?: points.maxOf { it[0] }
        val sampled =
          if (method == "linear" || method == "constant") {
            listOf(doubleArrayOf(low, model.predict(low)), doubleArrayOf(high, model.predict(high)))
          } else {
            CurveSampler.sample(model.predict, low, high, minSteps = 25, maxSteps = 200)
          }
        sampled.map { p ->
          VegaValue.Obj(prefix + mapOf(xName to VegaValue.Num(p[0]), yName to VegaValue.Num(p[1])))
        }
      }
    }
  }
}

/**
 * `loess`: a smooth trend with no equation behind it.
 *
 * Where `regression` fits one curve to everything, this fits a **separate weighted straight line at
 * every point** over its nearest neighbours, and joins the answers up. That is what lets it follow
 * a shape no formula describes — and equally what stops the result from having coefficients to
 * report, which is why there is no `params` here.
 *
 * `bandwidth` is the fraction of the data each local fit sees, 0.3 by default. Small values chase
 * noise; large ones approach the straight line `regression` would have drawn.
 */
public object LoessTransform : Transform {
  override val type: String = "loess"

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
        "loess needs 'x' and 'y'",
        operator = type,
      )
      return input
    }
    val groupBy = params.stringList("groupby")
    val bandwidth = params.number("bandwidth")?.takeIf { it > 0.0 } ?: 0.3
    val names = params.stringList("as")
    val xName = names.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: xField
    val yName = names.getOrNull(1)?.takeIf { it.isNotEmpty() } ?: yField

    return groupTuples(input, groupBy).flatMap { (groupKey, rows) ->
      val points = numericPairs(rows, xField, yField)
      if (points.isEmpty()) return@flatMap emptyList()
      val prefix = LinkedHashMap<String, VegaValue>(groupBy.size)
      groupBy.forEachIndexed { index, path -> prefix[path] = groupKey.values[index] }
      RegressionFits.loess(points, bandwidth).map { p ->
        VegaValue.Obj(prefix + mapOf(xName to VegaValue.Num(p[0]), yName to VegaValue.Num(p[1])))
      }
    }
  }
}

/**
 * The rows a fit will see, as `[x, y]` pairs.
 *
 * A row is dropped when either value is **not a number** — which upstream writes as `(u = +u) >= u`
 * and is false only for NaN. An infinity survives that test and reaches the fit, so a column
 * holding one produces an unusable model rather than a plausible one fitted to the rest.
 */
internal fun numericPairs(
  rows: List<VegaValue>,
  xField: String,
  yField: String,
): List<DoubleArray> =
  rows
    .map { doubleArrayOf(it.field(xField).asDouble(), it.field(yField).asDouble()) }
    .filterNot { it[0].isNaN() || it[1].isNaN() }
