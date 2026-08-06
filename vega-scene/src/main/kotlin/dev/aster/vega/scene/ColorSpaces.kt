package dev.aster.vega.scene

import kotlin.math.cbrt
import kotlin.math.pow

/**
 * Colour interpolation, in the spaces Vega's scales offer.
 *
 * RGB is the default and is what most charts get. CIE Lab exists because interpolating two
 * saturated colours in RGB passes through a muddy midpoint, and Lab does not — which is visible
 * enough that specifications ask for it explicitly.
 *
 * Platform-independent by construction: no Android colour types appear here.
 */
public object ColorSpaces {

  public enum class Interpolation {
    RGB,
    LAB;

    public companion object {
      /** Returns `null` for a space this engine does not implement, so the caller can report it. */
      public fun fromName(name: String): Interpolation? =
        when (name.lowercase()) {
          "rgb" -> RGB
          "lab" -> LAB
          else -> null
        }
    }
  }

  /** Interpolates componentwise in sRGB, which is what Vega does unless told otherwise. */
  public fun interpolateRgb(from: SceneColor, to: SceneColor, t: Double): SceneColor {
    val amount = t.coerceIn(0.0, 1.0)
    return SceneColor(
      red = from.red + (to.red - from.red) * amount,
      green = from.green + (to.green - from.green) * amount,
      blue = from.blue + (to.blue - from.blue) * amount,
      alpha = from.alpha + (to.alpha - from.alpha) * amount,
    )
  }

  /**
   * Interpolates in CIE Lab, which keeps the midpoint of two saturated colours from going muddy.
   */
  public fun interpolateLab(from: SceneColor, to: SceneColor, t: Double): SceneColor {
    val amount = t.coerceIn(0.0, 1.0)
    val a = toLab(from)
    val b = toLab(to)
    return fromLab(
      Lab(
        lightness = a.lightness + (b.lightness - a.lightness) * amount,
        a = a.a + (b.a - a.a) * amount,
        b = a.b + (b.b - a.b) * amount,
      ),
      alpha = from.alpha + (to.alpha - from.alpha) * amount,
    )
  }

  public fun interpolate(
    from: SceneColor,
    to: SceneColor,
    t: Double,
    space: Interpolation,
  ): SceneColor =
    when (space) {
      Interpolation.RGB -> interpolateRgb(from, to, t)
      Interpolation.LAB -> interpolateLab(from, to, t)
    }

  /**
   * Samples a multi-stop colour ramp at [t] in `0..1`.
   *
   * Stops are evenly spaced, as `d3.interpolateRgbBasis`-style ramps and Vega's colour ranges are.
   */
  public fun sample(
    colors: List<SceneColor>,
    t: Double,
    space: Interpolation = Interpolation.RGB,
  ): SceneColor {
    if (colors.isEmpty()) return SceneColor.Black
    if (colors.size == 1) return colors[0]
    val amount = t.coerceIn(0.0, 1.0)
    val position = amount * (colors.size - 1)
    val lower = kotlin.math.floor(position).toInt().coerceIn(0, colors.size - 1)
    val upper = (lower + 1).coerceAtMost(colors.size - 1)
    if (lower == upper) return colors[lower]
    return interpolate(colors[lower], colors[upper], position - lower, space)
  }

  // ---- CIE Lab ---------------------------------------------------------------

  public data class Lab(val lightness: Double, val a: Double, val b: Double)

  /** D65 white point, the reference d3 uses. */
  private const val XN = 0.96422
  private const val YN = 1.0
  private const val ZN = 0.82521

  private const val T0 = 4.0 / 29.0
  private const val T1 = 6.0 / 29.0
  private const val T2 = 3.0 * T1 * T1
  private const val T3 = T1 * T1 * T1

  public fun toLab(color: SceneColor): Lab {
    val r = linearize(color.red)
    val g = linearize(color.green)
    val b = linearize(color.blue)
    val x = xyz((0.2225045 * r + 0.7168786 * g + 0.0606169 * b) / YN)
    val y = xyz((0.4360747 * r + 0.3850649 * g + 0.1430804 * b) / XN)
    val z = xyz((0.0139322 * r + 0.0971045 * g + 0.7141733 * b) / ZN)
    return Lab(
      lightness = 116.0 * x - 16.0,
      a = 500.0 * (y - x),
      b = 200.0 * (x - z),
    )
  }

  public fun fromLab(lab: Lab, alpha: Double = 1.0): SceneColor {
    var y = (lab.lightness + 16.0) / 116.0
    var x = if (lab.a.isNaN()) y else y + lab.a / 500.0
    var z = if (lab.b.isNaN()) y else y - lab.b / 200.0
    y = YN * inverseXyz(y)
    x = XN * inverseXyz(x)
    z = ZN * inverseXyz(z)
    return SceneColor(
      red = delinearize(3.1338561 * x - 1.6168667 * y - 0.4906146 * z),
      green = delinearize(-0.9787684 * x + 1.9161415 * y + 0.0334540 * z),
      blue = delinearize(0.0719453 * x - 0.2289914 * y + 1.4052427 * z),
      alpha = alpha.coerceIn(0.0, 1.0),
    )
  }

  /** sRGB gamma expansion. */
  private fun linearize(channel: Double) =
    if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

  /**
   * sRGB gamma compression, clamped so an out-of-gamut Lab colour still produces a usable colour.
   */
  private fun delinearize(channel: Double): Double {
    val value =
      if (channel <= 0.0031308) 12.92 * channel else 1.055 * channel.pow(1.0 / 2.4) - 0.055
    return value.coerceIn(0.0, 1.0)
  }

  private fun xyz(value: Double) = if (value > T3) cbrt(value) else value / T2 + T0

  private fun inverseXyz(value: Double) =
    if (value > T1) value * value * value else T2 * (value - T0)
}
