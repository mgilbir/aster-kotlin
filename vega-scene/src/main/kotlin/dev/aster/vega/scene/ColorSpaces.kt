package dev.aster.vega.scene

import dev.aster.vega.model.roundHalfUp
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
    LAB,
    /**
     * Lab in polar form: the same lightness, with the chroma and the **hue angle** interpolated
     * separately.
     *
     * A hue is a circle, so there are two ways round it and they give different colours. [HCL]
     * takes the shorter arc and [HCL_LONG] the longer one, which is the whole difference between a
     * ramp that stays in one family and one that visits the rest of the spectrum on the way.
     */
    HCL,
    HCL_LONG,
    HSL,
    HSL_LONG,
    /**
     * Green's cubehelix: a helix through RGB whose *perceived* lightness rises monotonically.
     *
     * Chosen for exactly that — a ramp nobody can read the wrong way round, and one that survives
     * being printed in grey.
     */
    CUBEHELIX,
    CUBEHELIX_LONG;

    /** True for the variants that take the long way round the hue circle. */
    internal val isLong: Boolean
      get() = this == HCL_LONG || this == HSL_LONG || this == CUBEHELIX_LONG

    public companion object {
      /** Returns `null` for a space this engine does not implement, so the caller can report it. */
      public fun fromName(name: String): Interpolation? =
        when (name.lowercase()) {
          "rgb" -> RGB
          "lab" -> LAB
          "hcl" -> HCL
          "hcl-long" -> HCL_LONG
          "hsl" -> HSL
          "hsl-long" -> HSL_LONG
          "cubehelix" -> CUBEHELIX
          "cubehelix-long" -> CUBEHELIX_LONG
          else -> null
        }
    }
  }

  /**
   * One channel, interpolated the way `d3-interpolate`'s `nogamma` does.
   *
   * The `NaN` rule is upstream's and it is load-bearing: a grey has no hue, and `NaN - x` is `NaN`,
   * which d3 reads as "no difference" and so holds the channel **constant at the end that has a
   * value**. A ramp from grey to red therefore keeps red's hue throughout and only its chroma
   * moves, where averaging a NaN would give a colour nobody can name.
   */
  private fun channel(from: Double, to: Double, t: Double): Double {
    val delta = to - from
    if (delta.isNaN() || delta == 0.0) return if (from.isNaN()) to else from
    return from + delta * t
  }

  /**
   * A hue angle, taken the short way round unless [long] says otherwise.
   *
   * `d - 360 * round(d / 360)` is d3's, and it is not the same as a modulo: it maps a difference of
   * exactly 180 to 180 rather than to -180, so a pair of complementary colours turns consistently
   * rather than depending on which was written first.
   */
  private fun hue(from: Double, to: Double, t: Double, long: Boolean): Double {
    if (long) return channel(from, to, t)
    val delta = to - from
    if (delta.isNaN() || delta == 0.0) return if (from.isNaN()) to else from
    val shortest =
      if (delta > 180.0 || delta < -180.0) delta - 360.0 * kotlin.math.round(delta / 360.0)
      else delta
    return from + shortest * t
  }

  /**
   * Interpolates componentwise in sRGB, which is what Vega does unless told otherwise.
   *
   * The arithmetic happens on the **8-bit channel values**, not on the 0..1 fractions, because that
   * is where upstream's halves are halves. Viridis's stops 22 and 23 have blue 104 and 93, and a
   * midpoint between them is exactly 98.5, which rounds up to 99. Averaging `104/255` and `93/255`
   * and scaling back gives 98.49999999999999, which rounds down to 98 — a colour off by one, on
   * every ramp, wherever a value happens to land halfway between two stops. `n / 255.0 * 255.0` is
   * exactly `n` for every byte, so working in 8-bit space costs nothing and keeps the halves whole.
   *
   * Rounding here rather than when the colour is written out matches d3, which rounds as it
   * stringifies: an interpolated colour is never interpolated again, so the two are the same.
   */
  public fun interpolateRgb(
    from: SceneColor,
    to: SceneColor,
    t: Double,
    /**
     * `interpolate: {"type": "rgb", "gamma": y}` — the only interpolator d3 gives a gamma.
     *
     * It bends the ramp's **middle** and leaves both ends exactly where they were, which is what
     * makes it easy to get wrong and hard to notice: `1.0` is the plain linear ramp, above that the
     * ramp brightens through the middle and below it darkens. d3 raises each channel to the power,
     * interpolates there, and takes the root on the way out.
     */
    gamma: Double = 1.0,
  ): SceneColor {
    val amount = t.coerceIn(0.0, 1.0)
    fun channel(a: Double, b: Double): Double {
      val start = a * 255.0
      val end = b * 255.0
      if (gamma != 1.0) {
        val lo = start.pow(gamma)
        val span = end.pow(gamma) - lo
        return roundHalfUp((lo + amount * span).pow(1.0 / gamma)) / 255.0
      }
      return roundHalfUp(start + (end - start) * amount) / 255.0
    }
    return SceneColor(
      red = channel(from.red, to.red),
      green = channel(from.green, to.green),
      blue = channel(from.blue, to.blue),
      // Opacity is not an 8-bit channel upstream and d3 does not round it.
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
    /** Only `rgb` has one; every other space ignores it, as d3 does. */
    gamma: Double = 1.0,
  ): SceneColor {
    // **A fully transparent endpoint contributes no colour, only its opacity.** d3 blanks a
    // transparent colour's channels to NaN, and its interpolators are built to notice: a NaN
    // endpoint holds the *other* end's channel constant, so `red -> transparent` stays red and
    // fades away. This engine keeps the zeros it parsed, so the same ramp faded through black —
    // visibly wrong, and reachable from `range: ["red", "transparent"]`.
    //
    // Matching the effect rather than importing NaN channels: the alpha still interpolates, so the
    // colour disappears exactly as it should, and nothing downstream has to defend against a NaN.
    // Substituted rather than recursed into: handing the same pair back to this function meant the
    // replacement still had a zero alpha, so the branch fired again and the stack ran out. A
    // fixture with a `range: ["red", "transparent"]` is what found that, which is the argument for
    // having one.
    val start = if (from.alpha == 0.0 && to.alpha != 0.0) to.withAlpha(from.alpha) else from
    val end = if (to.alpha == 0.0 && from.alpha != 0.0) from.withAlpha(to.alpha) else to
    return when (space) {
      Interpolation.RGB -> interpolateRgb(start, end, t, gamma)
      Interpolation.LAB -> interpolateLab(start, end, t)
      Interpolation.HCL,
      Interpolation.HCL_LONG -> interpolateHcl(start, end, t, space.isLong)
      Interpolation.HSL,
      Interpolation.HSL_LONG -> interpolateHsl(start, end, t, space.isLong)
      Interpolation.CUBEHELIX,
      Interpolation.CUBEHELIX_LONG -> interpolateCubehelix(start, end, t, space.isLong)
    }
  }

  // ---- HCL --------------------------------------------------------------------

  /** Lab in polar coordinates: lightness, chroma and a hue angle in degrees. */
  public data class Hcl(val hue: Double, val chroma: Double, val lightness: Double)

  /**
   * `d3.hcl`: Lab read as an angle and a radius.
   *
   * A colour with no chroma at all has **no hue** — `NaN`, not zero — because an angle at the
   * origin means nothing, and interpolating a zero there would drag the ramp through red. Its
   * chroma is `NaN` too at pure black and pure white, where even the *radius* is undefined.
   */
  public fun toHcl(color: SceneColor): Hcl {
    val lab = toLab(color)
    if (lab.a == 0.0 && lab.b == 0.0) {
      val chroma = if (lab.lightness > 0.0 && lab.lightness < 100.0) 0.0 else Double.NaN
      return Hcl(Double.NaN, chroma, lab.lightness)
    }
    val angle = kotlin.math.atan2(lab.b, lab.a) * 180.0 / kotlin.math.PI
    return Hcl(
      hue = if (angle < 0.0) angle + 360.0 else angle,
      chroma = kotlin.math.sqrt(lab.a * lab.a + lab.b * lab.b),
      lightness = lab.lightness,
    )
  }

  public fun fromHcl(hcl: Hcl, alpha: Double = 1.0): SceneColor {
    if (hcl.hue.isNaN()) return fromLab(Lab(hcl.lightness, 0.0, 0.0), alpha)
    val radians = hcl.hue * kotlin.math.PI / 180.0
    return fromLab(
      Lab(
        lightness = hcl.lightness,
        a = kotlin.math.cos(radians) * hcl.chroma,
        b = kotlin.math.sin(radians) * hcl.chroma,
      ),
      alpha,
    )
  }

  public fun interpolateHcl(
    from: SceneColor,
    to: SceneColor,
    t: Double,
    long: Boolean = false,
  ): SceneColor {
    val amount = t.coerceIn(0.0, 1.0)
    val a = toHcl(from)
    val b = toHcl(to)
    return fromHcl(
      Hcl(
        hue = hue(a.hue, b.hue, amount, long),
        chroma = channel(a.chroma, b.chroma, amount),
        lightness = channel(a.lightness, b.lightness, amount),
      ),
      alpha = channel(from.alpha, to.alpha, amount),
    )
  }

  // ---- HSL --------------------------------------------------------------------

  public data class Hsl(val hue: Double, val saturation: Double, val lightness: Double)

  /**
   * `d3.hsl`, including its two `NaN`s: a grey has no hue, and pure black or white has no
   * saturation either.
   */
  public fun toHsl(color: SceneColor): Hsl {
    val r = color.red
    val g = color.green
    val b = color.blue
    val min = minOf(r, g, b)
    val max = maxOf(r, g, b)
    var span = max - min
    val lightness = (max + min) / 2.0
    var hue = Double.NaN
    var saturation: Double
    if (span != 0.0) {
      hue =
        when {
          r == max -> (g - b) / span + (if (g < b) 6.0 else 0.0)
          g == max -> (b - r) / span + 2.0
          else -> (r - g) / span + 4.0
        }
      saturation = span / (if (lightness < 0.5) max + min else 2.0 - max - min)
      hue *= 60.0
    } else {
      saturation = if (lightness > 0.0 && lightness < 1.0) 0.0 else Double.NaN
      span = 0.0
    }
    return Hsl(hue, saturation, lightness)
  }

  public fun fromHsl(hsl: Hsl, alpha: Double = 1.0): SceneColor {
    val h = hsl.hue % 360.0 + (if (hsl.hue < 0.0) 360.0 else 0.0)
    val s = if (h.isNaN() || hsl.saturation.isNaN()) 0.0 else hsl.saturation
    val l = hsl.lightness
    val m2 = l + (if (l < 0.5) l else 1.0 - l) * s
    val m1 = 2.0 * l - m2
    return SceneColor(
      red = hslChannel(if (h >= 240.0) h - 240.0 else h + 120.0, m1, m2),
      green = hslChannel(h, m1, m2),
      blue = hslChannel(if (h < 120.0) h + 240.0 else h - 120.0, m1, m2),
      alpha = alpha.coerceIn(0.0, 1.0),
    )
  }

  private fun hslChannel(hue: Double, m1: Double, m2: Double): Double =
    (when {
        hue < 60.0 -> m1 + (m2 - m1) * hue / 60.0
        hue < 180.0 -> m2
        hue < 240.0 -> m1 + (m2 - m1) * (240.0 - hue) / 60.0
        else -> m1
      })
      .coerceIn(0.0, 1.0)

  public fun interpolateHsl(
    from: SceneColor,
    to: SceneColor,
    t: Double,
    long: Boolean = false,
  ): SceneColor {
    val amount = t.coerceIn(0.0, 1.0)
    val a = toHsl(from)
    val b = toHsl(to)
    return fromHsl(
      Hsl(
        hue = hue(a.hue, b.hue, amount, long),
        saturation = channel(a.saturation, b.saturation, amount),
        lightness = channel(a.lightness, b.lightness, amount),
      ),
      alpha = channel(from.alpha, to.alpha, amount),
    )
  }

  // ---- cubehelix --------------------------------------------------------------

  public data class Cubehelix(val hue: Double, val saturation: Double, val lightness: Double)

  private const val CH_A = -0.14861
  private const val CH_B = 1.78277
  private const val CH_C = -0.29227
  private const val CH_D = -0.90649
  private const val CH_E = 1.97294

  /**
   * `d3.cubehelix`.
   *
   * The `-120` on the hue is Green's own convention and not a normalisation: the helix is defined
   * with its zero at blue rather than at red, and dropping it turns every ramp a third of the way
   * round the circle.
   */
  public fun toCubehelix(color: SceneColor): Cubehelix {
    val ed = CH_E * CH_D
    val eb = CH_E * CH_B
    val bcDa = CH_B * CH_C - CH_D * CH_A
    val r = color.red
    val g = color.green
    val b = color.blue
    val l = (bcDa * b + ed * r - eb * g) / (bcDa + ed - eb)
    val bl = b - l
    val k = (CH_E * (g - l) - CH_C * bl) / CH_D
    val s = kotlin.math.sqrt(k * k + bl * bl) / (CH_E * l * (1.0 - l))
    val h =
      if (s.isNaN() || s == 0.0) Double.NaN
      else kotlin.math.atan2(k, bl) * 180.0 / kotlin.math.PI - 120.0
    return Cubehelix(if (h < 0.0) h + 360.0 else h, s, l)
  }

  public fun fromCubehelix(value: Cubehelix, alpha: Double = 1.0): SceneColor {
    val h = if (value.hue.isNaN()) 0.0 else (value.hue + 120.0) * kotlin.math.PI / 180.0
    val l = value.lightness
    val a = if (value.saturation.isNaN()) 0.0 else value.saturation * l * (1.0 - l)
    val cos = kotlin.math.cos(h)
    val sin = kotlin.math.sin(h)
    return SceneColor(
      red = (l + a * (CH_A * cos + CH_B * sin)).coerceIn(0.0, 1.0),
      green = (l + a * (CH_C * cos + CH_D * sin)).coerceIn(0.0, 1.0),
      blue = (l + a * (CH_E * cos)).coerceIn(0.0, 1.0),
      alpha = alpha.coerceIn(0.0, 1.0),
    )
  }

  public fun interpolateCubehelix(
    from: SceneColor,
    to: SceneColor,
    t: Double,
    long: Boolean = false,
  ): SceneColor {
    val amount = t.coerceIn(0.0, 1.0)
    val a = toCubehelix(from)
    val b = toCubehelix(to)
    return fromCubehelix(
      Cubehelix(
        hue = hue(a.hue, b.hue, amount, long),
        saturation = channel(a.saturation, b.saturation, amount),
        lightness = channel(a.lightness, b.lightness, amount),
      ),
      alpha = channel(from.alpha, to.alpha, amount),
    )
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
    gamma: Double = 1.0,
  ): SceneColor {
    if (colors.isEmpty()) return SceneColor.Black
    if (colors.size == 1) return colors[0]
    val amount = t.coerceIn(0.0, 1.0)
    val position = amount * (colors.size - 1)
    val lower = kotlin.math.floor(position).toInt().coerceIn(0, colors.size - 1)
    val upper = (lower + 1).coerceAtMost(colors.size - 1)
    // **An endpoint still goes through the interpolator**, which is what d3 does: its ramp is a
    // function evaluated at `t`, not a lookup, so at `t = 1` the substitution above still applies.
    // For an ordinary colour this is exact — interpolating to a stop at 1 returns that stop — and
    // for one carrying no colour of its own it is the difference between `range: ["red",
    // "transparent"]` ending at transparent *red* and ending at transparent *black*.
    if (lower == upper) {
      return when {
        colors.size == 1 -> colors[0]
        lower == 0 -> interpolate(colors[0], colors[1], 0.0, space, gamma)
        else -> interpolate(colors[lower - 1], colors[lower], 1.0, space, gamma)
      }
    }
    return interpolate(colors[lower], colors[upper], position - lower, space, gamma)
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
    // **A grey is exactly neutral**, and d3 makes sure of it by reusing the luminance for the
    // other two axes instead of computing them. Without that, the three sums differ in their last
    // bits and `a` and `b` come out as floating-point dust rather than zero — which matters one
    // step later: `toHcl` reads a hue of *undefined* only when both are exactly zero, so `#ccc`
    // was given a hue of 158 degrees and an HCL ramp from red to grey swung through green instead
    // of quietly desaturating.
    val neutral = color.red == color.green && color.green == color.blue
    val y = if (neutral) x else xyz((0.4360747 * r + 0.3850649 * g + 0.1430804 * b) / XN)
    val z = if (neutral) x else xyz((0.0139322 * r + 0.0971045 * g + 0.7141733 * b) / ZN)
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
