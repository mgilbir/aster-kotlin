package dev.aster.vega.dataflow.transform

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The probability distributions `density` and `kde` draw, ported from `vega-statistics`.
 *
 * Only `pdf` and `cdf` are here. Upstream's distributions also sample and invert, but a chart never
 * asks for either: sampling needs a random source, which would make a specification render
 * differently on every frame, and the inverse is used only by a quantile plot built from a
 * distribution rather than from data.
 */
internal object Distributions {

  private val SQRT2PI = sqrt(2 * PI)

  internal interface Distribution {
    fun pdf(x: Double): Double

    fun cdf(x: Double): Double
  }

  internal fun normal(mean: Double = 0.0, stdev: Double = 1.0): Distribution =
    object : Distribution {
      override fun pdf(x: Double): Double = densityNormal(x, mean, stdev)

      override fun cdf(x: Double): Double = cumulativeNormal(x, mean, stdev)
    }

  internal fun logNormal(mean: Double = 0.0, stdev: Double = 1.0): Distribution =
    object : Distribution {
      override fun pdf(x: Double): Double {
        if (x <= 0) return 0.0
        val z = (ln(x) - mean) / stdev
        return exp(-0.5 * z * z) / (stdev * SQRT2PI * x)
      }

      override fun cdf(x: Double): Double = cumulativeNormal(ln(x), mean, stdev)
    }

  internal fun uniform(min: Double = 0.0, max: Double = 1.0): Distribution =
    object : Distribution {
      override fun pdf(x: Double): Double = if (x in min..max) 1.0 / (max - min) else 0.0

      override fun cdf(x: Double): Double =
        when {
          x < min -> 0.0
          x > max -> 1.0
          else -> (x - min) / (max - min)
        }
    }

  /**
   * A kernel density estimate: a Gaussian bump over every observation, added up.
   *
   * The support is taken **unfiltered**. Upstream partitions the field values into groups without
   * dropping the missing ones, so a null in the column turns the whole density into NaN rather than
   * being quietly skipped — which is visible, where a silently narrower estimate would not be.
   */
  internal fun kde(support: List<Double>, bandwidth: Double): Distribution {
    val n = support.size
    val h = if (bandwidth > 0.0) bandwidth else estimateBandwidth(support)
    return object : Distribution {
      override fun pdf(x: Double): Double {
        var y = 0.0
        for (s in support) y += densityNormal((x - s) / h, 0.0, 1.0)
        return y / h / n
      }

      override fun cdf(x: Double): Double {
        var y = 0.0
        for (s in support) y += cumulativeNormal((x - s) / h, 0.0, 1.0)
        return y / n
      }
    }
  }

  /** Several distributions blended by weight; the weights are normalised to sum to one. */
  internal fun mixture(parts: List<Distribution>, weights: List<Double>): Distribution {
    val w = DoubleArray(parts.size) { weights.getOrNull(it) ?: 1.0 }
    val total = w.sum()
    for (i in w.indices) w[i] /= total
    return object : Distribution {
      override fun pdf(x: Double): Double = parts.indices.sumOf { w[it] * parts[it].pdf(x) }

      override fun cdf(x: Double): Double = parts.indices.sumOf { w[it] * parts[it].cdf(x) }
    }
  }

  private fun densityNormal(value: Double, mean: Double, stdev: Double): Double {
    val z = (value - mean) / stdev
    return exp(-0.5 * z * z) / (stdev * SQRT2PI)
  }

  /**
   * The normal CDF, by West's (2009) rational approximation.
   *
   * There is no closed form for it, so this is a fitted one: two polynomials in Horner form below
   * 7.07 standard deviations, a continued fraction above, and a flat zero past 37 where a double
   * has no room left to hold the answer. The constants are transcribed, not derived, and the
   * splitting points are part of the fit — moving either changes the result in the fifth digit.
   */
  private fun cumulativeNormal(value: Double, mean: Double, stdev: Double): Double {
    val z = (value - mean) / stdev
    val magnitude = abs(z)
    val cd: Double
    if (magnitude > 37) {
      cd = 0.0
    } else {
      val e = exp(-magnitude * magnitude / 2)
      if (magnitude < 7.07106781186547) {
        var sum = 3.52624965998911e-02 * magnitude + 0.700383064443688
        sum = sum * magnitude + 6.37396220353165
        sum = sum * magnitude + 33.912866078383
        sum = sum * magnitude + 112.079291497871
        sum = sum * magnitude + 221.213596169931
        sum = sum * magnitude + 220.206867912376
        var numerator = e * sum
        sum = 8.83883476483184e-02 * magnitude + 1.75566716318264
        sum = sum * magnitude + 16.064177579207
        sum = sum * magnitude + 86.7807322029461
        sum = sum * magnitude + 296.564248779674
        sum = sum * magnitude + 637.333633378831
        sum = sum * magnitude + 793.826512519948
        sum = sum * magnitude + 440.413735824752
        numerator /= sum
        cd = numerator
      } else {
        var sum = magnitude + 0.65
        sum = magnitude + 4 / sum
        sum = magnitude + 3 / sum
        sum = magnitude + 2 / sum
        sum = magnitude + 1 / sum
        cd = e / sum / 2.506628274631
      }
    }
    return if (z > 0) 1 - cd else cd
  }

  /**
   * Scott's rule for a kernel bandwidth: `1.06 · v · n^-0.2`.
   *
   * `v` is the smaller of the standard deviation and the interquartile range over 1.34, which is
   * what stops a long tail from smoothing away the shape of the bulk. The chain of fallbacks after
   * it is upstream's, and each rung catches a case where the one before is zero or undefined: fewer
   * than two values leave no deviation, identical values leave no spread at all, and a dataset
   * sitting exactly on zero leaves nothing to scale by — hence the final literal 1.
   */
  internal fun estimateBandwidth(values: List<Double>): Double {
    val clean = values.filterNot { it.isNaN() }
    val n = values.size
    val d = deviation(clean)
    val q = quantiles(clean, listOf(0.25, 0.5, 0.75))
    val h = (q[2] - q[0]) / 1.34
    val v = firstUsable(minOf(d, h), d, abs(q[0])) ?: 1.0
    return 1.06 * v * n.toDouble().pow(-0.2)
  }

  /** JavaScript's `a || b || c` over doubles: zero and NaN both fall through. */
  private fun firstUsable(vararg candidates: Double): Double? = candidates.firstOrNull {
    !it.isNaN() && it != 0.0
  }

  /** d3's `deviation`: the *sample* standard deviation, and NaN when there is only one value. */
  private fun deviation(values: List<Double>): Double {
    var count = 0
    var mean = 0.0
    var sum = 0.0
    for (value in values) {
      val delta = value - mean
      mean += delta / ++count
      sum += delta * (value - mean)
    }
    return if (count > 1) sqrt(sum / (count - 1)) else Double.NaN
  }

  internal fun quantiles(values: List<Double>, probabilities: List<Double>): List<Double> {
    val sorted = values.sorted()
    return probabilities.map { quantileSorted(sorted, it) }
  }

  /** d3's `quantileSorted`: linear interpolation between the two straddling order statistics. */
  internal fun quantileSorted(sorted: List<Double>, probability: Double): Double {
    if (sorted.isEmpty()) return Double.NaN
    if (sorted.size == 1) return sorted[0]
    val position = (sorted.size - 1) * probability.coerceIn(0.0, 1.0)
    val lower = kotlin.math.floor(position).toInt()
    val upper = kotlin.math.ceil(position).toInt()
    if (lower == upper) return sorted[lower]
    val weight = position - lower
    return sorted[lower] * (1.0 - weight) + sorted[upper] * weight
  }
}
