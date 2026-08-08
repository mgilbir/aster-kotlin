package dev.aster.vega.expression

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * The three probability distributions Vega's expression language exposes, and the maths behind
 * them.
 *
 * They live here rather than beside the `density` transform because the expression language reaches
 * for them too — a quantile-quantile plot is `quantileNormal(datum.prob)` and nothing else — and
 * `vega-expression` is the lower module. `Distributions` in `vega-dataflow` builds on the same
 * functions, so the density a chart draws and the quantile it asks for cannot disagree.
 *
 * Every constant is transcribed from `vega-statistics`, not derived. The approximations are fits:
 * moving a coefficient or a branch point changes the answer in a digit somebody's chart depends on.
 */
public object Statistics {

  private val SQRT2PI = sqrt(2 * PI)

  /** `densityUniform(x, min, max)` — flat inside the range, zero outside. */
  public fun densityUniform(x: Double, min: Double = 0.0, max: Double = 1.0): Double =
    if (x in min..max) 1.0 / (max - min) else 0.0

  public fun cumulativeUniform(x: Double, min: Double = 0.0, max: Double = 1.0): Double =
    when {
      x < min -> 0.0
      x > max -> 1.0
      else -> (x - min) / (max - min)
    }

  public fun quantileUniform(p: Double, min: Double = 0.0, max: Double = 1.0): Double =
    if (p in 0.0..1.0) min + p * (max - min) else Double.NaN

  /** The log-normal trio, each the normal one composed with a logarithm. */
  public fun densityLogNormal(x: Double, mean: Double = 0.0, stdev: Double = 1.0): Double {
    if (x <= 0) return 0.0
    val z = (ln(x) - mean) / stdev
    return exp(-0.5 * z * z) / (stdev * SQRT2PI * x)
  }

  public fun cumulativeLogNormal(x: Double, mean: Double = 0.0, stdev: Double = 1.0): Double =
    cumulativeNormal(ln(x), mean, stdev)

  public fun quantileLogNormal(p: Double, mean: Double = 0.0, stdev: Double = 1.0): Double =
    exp(quantileNormal(p, mean, stdev))

  /**
   * `quantileNormal(p, mean, stdev)` — the inverse normal CDF, upstream's own arrangement.
   *
   * `mean + stdev * sqrt(2) * erfinv(2p - 1)`. A quantile-quantile plot is built entirely from
   * this: it asks what value each rank *would* have under a normal distribution, and plots the
   * data's own quantiles against it.
   */
  public fun quantileNormal(p: Double, mean: Double = 0.0, stdev: Double = 1.0): Double =
    if (p < 0 || p > 1) Double.NaN else mean + stdev * SQRT2 * erfInverse(2 * p - 1)

  private val SQRT2 = sqrt(2.0)

  /**
   * The inverse error function, to Giles' approximation as ported by Apache Commons Math — which is
   * what `vega-statistics` uses, so the coefficients are theirs and not a different approximation
   * of the same curve.
   *
   * Three branches by how far into the tail the input is. The logarithm's argument is written as
   * `(1 - x) * (1 + x)` and **must not** be simplified to `1 - x * x`: near the boundaries the two
   * differ by enough to matter, which is upstream's own warning and worth keeping.
   */
  private fun erfInverse(x: Double): Double {
    var w = -ln((1 - x) * (1 + x))
    val coefficients =
      when {
        w < 6.25 -> {
          w -= 3.125
          ERFINV_NEAR
        }
        w < 16.0 -> {
          w = sqrt(w) - 3.25
          ERFINV_MID
        }
        w.isFinite() -> {
          w = sqrt(w) - 5.0
          ERFINV_FAR
        }
        else -> return Double.POSITIVE_INFINITY
      }
    // Horner's method, in the order upstream evaluates it: the first coefficient is the highest
    // power and each step multiplies by w before adding the next.
    var p = coefficients[0]
    for (index in 1 until coefficients.size) p = coefficients[index] + p * w
    return p * x
  }

  /** w - 3.125 branch: 23 coefficients, most significant last. */
  private val ERFINV_NEAR =
    doubleArrayOf(
      -3.6444120640178196996e-21,
      -1.685059138182016589e-19,
      1.2858480715256400167e-18,
      1.115787767802518096e-17,
      -1.333171662854620906e-16,
      2.0972767875968561637e-17,
      6.6376381343583238325e-15,
      -4.0545662729752068639e-14,
      -8.1519341976054721522e-14,
      2.6335093153082322977e-12,
      -1.2975133253453532498e-11,
      -5.4154120542946279317e-11,
      1.051212273321532285e-09,
      -4.1126339803469836976e-09,
      -2.9070369957882005086e-08,
      4.2347877827932403518e-07,
      -1.3654692000834678645e-06,
      -1.3882523362786468719e-05,
      0.0001867342080340571352,
      -0.00074070253416626697512,
      -0.0060336708714301490533,
      0.24015818242558961693,
      1.6536545626831027356,
    )

  /** sqrt(w) - 3.25 branch: 19 coefficients, most significant last. */
  private val ERFINV_MID =
    doubleArrayOf(
      2.2137376921775787049e-09,
      9.0756561938885390979e-08,
      -2.7517406297064545428e-07,
      1.8239629214389227755e-08,
      1.5027403968909827627e-06,
      -4.013867526981545969e-06,
      2.9234449089955446044e-06,
      1.2475304481671778723e-05,
      -4.7318229009055733981e-05,
      6.8284851459573175448e-05,
      2.4031110387097893999e-05,
      -0.0003550375203628474796,
      0.00095328937973738049703,
      -0.0016882755560235047313,
      0.0024914420961078508066,
      -0.0037512085075692412107,
      0.005370914553590063617,
      1.0052589676941592334,
      3.0838856104922207635,
    )

  /** sqrt(w) - 5 branch: 17 coefficients, most significant last. */
  private val ERFINV_FAR =
    doubleArrayOf(
      -2.7109920616438573243e-11,
      -2.5556418169965252055e-10,
      1.5076572693500548083e-09,
      -3.7894654401267369937e-09,
      7.6157012080783393804e-09,
      -1.4960026627149240478e-08,
      2.9147953450901080826e-08,
      -6.7711997758452339498e-08,
      2.2900482228026654717e-07,
      -9.9298272942317002539e-07,
      4.5260625972231537039e-06,
      -1.9681778105531670567e-05,
      7.5995277030017761139e-05,
      -0.00021503011930044477347,
      -0.00013871931833623122026,
      1.0103004648645343977,
      4.8499064014085844221,
    )

  public fun densityNormal(value: Double, mean: Double = 0.0, stdev: Double = 1.0): Double {
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
  public fun cumulativeNormal(value: Double, mean: Double = 0.0, stdev: Double = 1.0): Double {
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
}
