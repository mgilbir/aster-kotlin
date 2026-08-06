package dev.aster.vega.scene

import kotlin.math.roundToInt

/** Non-premultiplied sRGB colour with components in `0..1`. */
public data class SceneColor(
  val red: Double,
  val green: Double,
  val blue: Double,
  val alpha: Double = 1.0,
) {
  init {
    require(red.isFinite() && green.isFinite() && blue.isFinite() && alpha.isFinite()) {
      "SceneColor components must be finite: ($red, $green, $blue, $alpha)"
    }
  }

  public val isTransparent: Boolean
    get() = alpha <= 0.0

  public fun withAlpha(newAlpha: Double): SceneColor = copy(alpha = newAlpha.coerceIn(0.0, 1.0))

  /** `0xAARRGGBB`, the packed form Android's `Paint.setColor` expects. */
  public fun toArgb(): Int {
    fun channel(v: Double): Int = (v.coerceIn(0.0, 1.0) * 255.0).roundToInt()
    return (channel(alpha) shl 24) or
      (channel(red) shl 16) or
      (channel(green) shl 8) or
      channel(blue)
  }

  /** Lowercase `#rrggbb`, or `#rrggbbaa` when not fully opaque. */
  public fun toCssHex(): String {
    fun hex(v: Double): String =
      ((v.coerceIn(0.0, 1.0) * 255.0).roundToInt()).toString(16).padStart(2, '0')
    val base = "#${hex(red)}${hex(green)}${hex(blue)}"
    return if (alpha >= 1.0) base else base + hex(alpha)
  }

  public companion object {
    public val Black: SceneColor = SceneColor(0.0, 0.0, 0.0)
    public val White: SceneColor = SceneColor(1.0, 1.0, 1.0)
    public val Transparent: SceneColor = SceneColor(0.0, 0.0, 0.0, 0.0)

    public fun fromArgb(argb: Int): SceneColor =
      SceneColor(
        red = ((argb shr 16) and 0xFF) / 255.0,
        green = ((argb shr 8) and 0xFF) / 255.0,
        blue = (argb and 0xFF) / 255.0,
        alpha = ((argb shr 24) and 0xFF) / 255.0,
      )

    /**
     * Parses `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa` and the CSS named colours Vega's default
     * schemes use. Returns `null` for anything else so the caller can emit a diagnostic instead of
     * guessing a colour.
     */
    public fun parse(text: String): SceneColor? {
      val value = text.trim()
      if (
        value.equals("none", ignoreCase = true) || value.equals("transparent", ignoreCase = true)
      ) {
        return Transparent
      }
      if (value.startsWith("#")) return parseHex(value.substring(1))
      if (value.startsWith("rgb", ignoreCase = true)) return parseRgbFunction(value)
      return NAMED_COLORS[value.lowercase()]
    }

    private fun parseHex(hex: String): SceneColor? {
      fun nibble(c: Char): Int? = c.digitToIntOrNull(16)
      return when (hex.length) {
        3,
        4 -> {
          val parts = hex.map { nibble(it) ?: return null }
          SceneColor(
            parts[0] / 15.0,
            parts[1] / 15.0,
            parts[2] / 15.0,
            if (parts.size == 4) parts[3] / 15.0 else 1.0,
          )
        }
        6,
        8 -> {
          val bytes =
            (hex.indices step 2).map { i ->
              val hi = nibble(hex[i]) ?: return null
              val lo = nibble(hex[i + 1]) ?: return null
              (hi shl 4) or lo
            }
          SceneColor(
            bytes[0] / 255.0,
            bytes[1] / 255.0,
            bytes[2] / 255.0,
            if (bytes.size == 4) bytes[3] / 255.0 else 1.0,
          )
        }
        else -> null
      }
    }

    private fun parseRgbFunction(value: String): SceneColor? {
      val open = value.indexOf('(')
      val close = value.indexOf(')')
      if (open < 0 || close < open) return null
      // CSS allows both `rgb(r, g, b)` and the space-separated `rgb(r g b / a)` form.
      val parts =
        value
          .substring(open + 1, close)
          .split(',', '/', ' ', '\t')
          .map { it.trim() }
          .filter { it.isNotEmpty() }
      if (parts.size < 3) return null

      fun component(text: String): Double? =
        if (text.endsWith("%")) text.dropLast(1).toDoubleOrNull()?.div(100.0)
        else text.toDoubleOrNull()?.div(255.0)

      val r = component(parts[0]) ?: return null
      val g = component(parts[1]) ?: return null
      val b = component(parts[2]) ?: return null
      val a =
        if (parts.size >= 4) {
          val text = parts[3]
          if (text.endsWith("%")) text.dropLast(1).toDoubleOrNull()?.div(100.0) ?: return null
          else text.toDoubleOrNull() ?: return null
        } else 1.0
      return SceneColor(r, g, b, a)
    }

    /**
     * The subset of CSS named colours that appear in Vega's default configuration and colour
     * schemes. Unknown names deliberately fail rather than falling back to black.
     */
    private val NAMED_COLORS: Map<String, SceneColor> =
      mapOf(
        "black" to Black,
        "white" to White,
        "red" to SceneColor(1.0, 0.0, 0.0),
        "green" to SceneColor(0.0, 128 / 255.0, 0.0),
        "blue" to SceneColor(0.0, 0.0, 1.0),
        "gray" to SceneColor(128 / 255.0, 128 / 255.0, 128 / 255.0),
        "grey" to SceneColor(128 / 255.0, 128 / 255.0, 128 / 255.0),
        "lightgray" to SceneColor(211 / 255.0, 211 / 255.0, 211 / 255.0),
        "lightgrey" to SceneColor(211 / 255.0, 211 / 255.0, 211 / 255.0),
        "darkgray" to SceneColor(169 / 255.0, 169 / 255.0, 169 / 255.0),
        "darkgrey" to SceneColor(169 / 255.0, 169 / 255.0, 169 / 255.0),
        "silver" to SceneColor(192 / 255.0, 192 / 255.0, 192 / 255.0),
        "steelblue" to SceneColor(70 / 255.0, 130 / 255.0, 180 / 255.0),
        "orange" to SceneColor(1.0, 165 / 255.0, 0.0),
        "purple" to SceneColor(128 / 255.0, 0.0, 128 / 255.0),
        "brown" to SceneColor(165 / 255.0, 42 / 255.0, 42 / 255.0),
        "pink" to SceneColor(1.0, 192 / 255.0, 203 / 255.0),
        "yellow" to SceneColor(1.0, 1.0, 0.0),
        "cyan" to SceneColor(0.0, 1.0, 1.0),
        "magenta" to SceneColor(1.0, 0.0, 1.0),
        "navy" to SceneColor(0.0, 0.0, 128 / 255.0),
        "teal" to SceneColor(0.0, 128 / 255.0, 128 / 255.0),
        "olive" to SceneColor(128 / 255.0, 128 / 255.0, 0.0),
        "maroon" to SceneColor(128 / 255.0, 0.0, 0.0),
        "lime" to SceneColor(0.0, 1.0, 0.0),
        "aqua" to SceneColor(0.0, 1.0, 1.0),
        "fuchsia" to SceneColor(1.0, 0.0, 1.0),
      )
  }
}

public data class GradientStop(val offset: Double, val color: SceneColor)

/**
 * What fills or strokes a node.
 *
 * Gradient coordinates are in object space (`0..1` across the node's bounds), matching Vega's
 * `gradient` definition, so a paint can be shared between nodes of different sizes.
 */
public sealed interface ScenePaint {
  @JvmInline public value class Solid(public val color: SceneColor) : ScenePaint

  public data class LinearGradient(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val stops: List<GradientStop>,
  ) : ScenePaint

  public data class RadialGradient(
    val cx: Double,
    val cy: Double,
    val radius: Double,
    val focusX: Double = cx,
    val focusY: Double = cy,
    val stops: List<GradientStop>,
  ) : ScenePaint

  public companion object {
    public val Black: ScenePaint = Solid(SceneColor.Black)

    public fun solid(color: SceneColor): ScenePaint = Solid(color)
  }
}

public enum class StrokeCap {
  BUTT,
  ROUND,
  SQUARE,
}

public enum class StrokeJoin {
  MITER,
  ROUND,
  BEVEL,
}

/**
 * Stroke description. [dashArray] is a list of on/off lengths as in SVG; an empty list means solid.
 */
public data class Stroke(
  val paint: ScenePaint,
  val width: Double = 1.0,
  val cap: StrokeCap = StrokeCap.BUTT,
  val join: StrokeJoin = StrokeJoin.MITER,
  val miterLimit: Double = 10.0,
  val dashArray: List<Double> = emptyList(),
  val dashOffset: Double = 0.0,
  val opacity: Double = 1.0,
) {
  public val isVisible: Boolean
    get() = width > 0.0 && opacity > 0.0

  /** Half the stroke width, i.e. how far a stroke extends beyond the geometry it outlines. */
  public val halfWidth: Double
    get() = width / 2.0
}

/** Fill description, split from [Stroke] so a node can carry one, both or neither. */
public data class Fill(val paint: ScenePaint, val opacity: Double = 1.0) {
  public val isVisible: Boolean
    get() = opacity > 0.0

  public companion object {
    public fun of(color: SceneColor): Fill = Fill(ScenePaint.Solid(color))
  }
}

/**
 * Blend modes the engine can express on every backend. Anything else in a specification produces
 * `VEGA_RENDER_UNSUPPORTED_BLEND_MODE` rather than a silent substitution.
 */
public enum class SceneBlendMode {
  NORMAL,
  MULTIPLY,
  SCREEN,
  OVERLAY,
  DARKEN,
  LIGHTEN,
}
