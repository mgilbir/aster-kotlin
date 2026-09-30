package dev.aster.vega.expression

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * d3-ease's easing functions, which Vega 6.4.0 exposes to the expression language under their d3
 * names (vega/vega#4316).
 *
 * Each maps a normalized time in `[0, 1]` to an eased position, and is transcribed from `d3-ease`
 * 3.0.1 **operation for operation**: `b0 * (t -= b2) * t` is `(b0 * t) * t` and not `b0 * (t * t)`,
 * because the two differ in the last bit and d3's own test suite, replayed by
 * `UpstreamEaseVectorsTest`, is the judge of that.
 *
 * The parametric families — `easePoly`, `easeBack`, `easeElastic` — are at their default parameters
 * only, as upstream exposes them: d3's `.exponent()`, `.overshoot()` and `.amplitude()` have no
 * expression-language equivalent.
 */
internal object Ease {

  // ---- back: an overshoot of 1.70158 ---------------------------------------------------------

  private const val OVERSHOOT = 1.70158

  private fun backIn(t: Double): Double = t * t * (OVERSHOOT * (t - 1) + t)

  private fun backOut(t: Double): Double {
    val u = t - 1
    return u * u * ((u + 1) * OVERSHOOT + u) + 1
  }

  private fun backInOut(t: Double): Double {
    val u = t * 2
    return (if (u < 1) {
      u * u * ((OVERSHOOT + 1) * u - OVERSHOOT)
    } else {
      val v = u - 2
      v * v * ((OVERSHOOT + 1) * v + OVERSHOOT) + 2
    }) / 2
  }

  // ---- bounce --------------------------------------------------------------------------------

  private const val B1 = 4.0 / 11
  private const val B2 = 6.0 / 11
  private const val B3 = 8.0 / 11
  private const val B4 = 3.0 / 4
  private const val B5 = 9.0 / 11
  private const val B6 = 10.0 / 11
  private const val B7 = 15.0 / 16
  private const val B8 = 21.0 / 22
  private const val B9 = 63.0 / 64
  private const val B0 = 1 / B1 / B1

  private fun bounceOut(t: Double): Double =
    when {
      t < B1 -> B0 * t * t
      t < B3 -> (t - B2).let { u -> B0 * u * u + B4 }
      t < B6 -> (t - B5).let { u -> B0 * u * u + B7 }
      else -> (t - B8).let { u -> B0 * u * u + B9 }
    }

  private fun bounceIn(t: Double): Double = 1 - bounceOut(1 - t)

  private fun bounceInOut(t: Double): Double {
    val u = t * 2
    return (if (u <= 1) 1 - bounceOut(1 - u) else bounceOut(u - 1) + 1) / 2
  }

  // ---- circle --------------------------------------------------------------------------------

  private fun circleIn(t: Double): Double = 1 - sqrt(1 - t * t)

  private fun circleOut(t: Double): Double {
    val u = t - 1
    return sqrt(1 - u * u)
  }

  private fun circleInOut(t: Double): Double {
    val u = t * 2
    return (if (u <= 1) {
      1 - sqrt(1 - u * u)
    } else {
      val v = u - 2
      sqrt(1 - v * v) + 1
    }) / 2
  }

  // ---- cubic ---------------------------------------------------------------------------------

  private fun cubicIn(t: Double): Double = t * t * t

  private fun cubicOut(t: Double): Double {
    val u = t - 1
    return u * u * u + 1
  }

  private fun cubicInOut(t: Double): Double {
    val u = t * 2
    return (if (u <= 1) {
      u * u * u
    } else {
      val v = u - 2
      v * v * v + 2
    }) / 2
  }

  // ---- elastic: an amplitude of 1 and a period of 0.3 ----------------------------------------

  private const val TAU = 2 * PI

  /** `p /= tau`, which d3 does once when the function is built and every call then reads. */
  private const val PERIOD = 0.3 / TAU

  /** `Math.asin(1 / (a = Math.max(1, a))) * p`, at the default amplitude of 1. */
  private val SHIFT = asin(1 / max(1.0, 1.0)) * PERIOD

  private const val AMPLITUDE = 1.0

  private fun elasticIn(t: Double): Double {
    val u = t - 1
    return AMPLITUDE * tpmt(-u) * sin((SHIFT - u) / PERIOD)
  }

  private fun elasticOut(t: Double): Double = 1 - AMPLITUDE * tpmt(t) * sin((t + SHIFT) / PERIOD)

  private fun elasticInOut(t: Double): Double {
    val u = t * 2 - 1
    return (if (u < 0) AMPLITUDE * tpmt(-u) * sin((SHIFT - u) / PERIOD)
    else 2 - AMPLITUDE * tpmt(u) * sin((SHIFT + u) / PERIOD)) / 2
  }

  // ---- exp -----------------------------------------------------------------------------------

  /** Two to the power of minus ten times [x], rescaled so the curve meets `[0, 1]` exactly. */
  private fun tpmt(x: Double): Double = (2.0.pow(-10 * x) - 0.0009765625) * 1.0009775171065494

  private fun expIn(t: Double): Double = tpmt(1 - t)

  private fun expOut(t: Double): Double = 1 - tpmt(t)

  private fun expInOut(t: Double): Double {
    val u = t * 2
    return (if (u <= 1) tpmt(1 - u) else 2 - tpmt(u - 1)) / 2
  }

  // ---- poly: an exponent of 3 ----------------------------------------------------------------

  private const val EXPONENT = 3.0

  private fun polyIn(t: Double): Double = t.pow(EXPONENT)

  private fun polyOut(t: Double): Double = 1 - (1 - t).pow(EXPONENT)

  private fun polyInOut(t: Double): Double {
    val u = t * 2
    return (if (u <= 1) u.pow(EXPONENT) else 2 - (2 - u).pow(EXPONENT)) / 2
  }

  // ---- quad ----------------------------------------------------------------------------------

  private fun quadIn(t: Double): Double = t * t

  private fun quadOut(t: Double): Double = t * (2 - t)

  private fun quadInOut(t: Double): Double {
    val u = t * 2
    return (if (u <= 1) {
      u * u
    } else {
      val v = u - 1
      v * (2 - v) + 1
    }) / 2
  }

  // ---- sin -----------------------------------------------------------------------------------

  private const val HALF_PI = PI / 2

  /** Exactly 1 at 1, where `1 - cos(pi / 2)` is a hair short of it. */
  private fun sinIn(t: Double): Double = if (t == 1.0) 1.0 else 1 - cos(t * HALF_PI)

  private fun sinOut(t: Double): Double = sin(t * HALF_PI)

  private fun sinInOut(t: Double): Double = (1 - cos(PI * t)) / 2

  /**
   * Every name upstream registers, with the d3 function it is bound to.
   *
   * The unsuffixed name of each family is d3's choice of default and is not always the symmetric
   * one: `easeBounce` and `easeElastic` are the **out** forms, the rest are in-out.
   */
  val functions: Map<String, (Double) -> Double> =
    linkedMapOf(
      "easeLinear" to { t -> t },
      "easeQuad" to ::quadInOut,
      "easeQuadIn" to ::quadIn,
      "easeQuadOut" to ::quadOut,
      "easeQuadInOut" to ::quadInOut,
      "easeCubic" to ::cubicInOut,
      "easeCubicIn" to ::cubicIn,
      "easeCubicOut" to ::cubicOut,
      "easeCubicInOut" to ::cubicInOut,
      "easePoly" to ::polyInOut,
      "easePolyIn" to ::polyIn,
      "easePolyOut" to ::polyOut,
      "easePolyInOut" to ::polyInOut,
      "easeSin" to ::sinInOut,
      "easeSinIn" to ::sinIn,
      "easeSinOut" to ::sinOut,
      "easeSinInOut" to ::sinInOut,
      "easeExp" to ::expInOut,
      "easeExpIn" to ::expIn,
      "easeExpOut" to ::expOut,
      "easeExpInOut" to ::expInOut,
      "easeCircle" to ::circleInOut,
      "easeCircleIn" to ::circleIn,
      "easeCircleOut" to ::circleOut,
      "easeCircleInOut" to ::circleInOut,
      "easeBounce" to ::bounceOut,
      "easeBounceIn" to ::bounceIn,
      "easeBounceOut" to ::bounceOut,
      "easeBounceInOut" to ::bounceInOut,
      "easeBack" to ::backInOut,
      "easeBackIn" to ::backIn,
      "easeBackOut" to ::backOut,
      "easeBackInOut" to ::backInOut,
      "easeElastic" to ::elasticOut,
      "easeElasticIn" to ::elasticIn,
      "easeElasticOut" to ::elasticOut,
      "easeElasticInOut" to ::elasticInOut,
    )
}
