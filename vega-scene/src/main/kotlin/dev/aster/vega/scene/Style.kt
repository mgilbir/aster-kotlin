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

    /** Alpha bits for a fully opaque packed colour. */
    private const val ALPHA_OPAQUE: Int = 0xFF shl 24

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
      return NAMED_COLORS[value.lowercase()]?.let { fromArgb(it or ALPHA_OPAQUE) }
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
     * The CSS named colours, which is the set Vega accepts.
     *
     * The full table rather than a convenient subset: a chart that asks for `firebrick` and
     * silently gets no fill is worse than one that fails loudly, and every partial list eventually
     * omits the colour someone used. Values are packed RGB; alpha is always opaque.
     */
    private val NAMED_COLORS: Map<String, Int> =
      mapOf(
        "aliceblue" to 0xf0f8ff,
        "antiquewhite" to 0xfaebd7,
        "aqua" to 0x00ffff,
        "aquamarine" to 0x7fffd4,
        "azure" to 0xf0ffff,
        "beige" to 0xf5f5dc,
        "bisque" to 0xffe4c4,
        "black" to 0x000000,
        "blanchedalmond" to 0xffebcd,
        "blue" to 0x0000ff,
        "blueviolet" to 0x8a2be2,
        "brown" to 0xa52a2a,
        "burlywood" to 0xdeb887,
        "cadetblue" to 0x5f9ea0,
        "chartreuse" to 0x7fff00,
        "chocolate" to 0xd2691e,
        "coral" to 0xff7f50,
        "cornflowerblue" to 0x6495ed,
        "cornsilk" to 0xfff8dc,
        "crimson" to 0xdc143c,
        "cyan" to 0x00ffff,
        "darkblue" to 0x00008b,
        "darkcyan" to 0x008b8b,
        "darkgoldenrod" to 0xb8860b,
        "darkgray" to 0xa9a9a9,
        "darkgreen" to 0x006400,
        "darkgrey" to 0xa9a9a9,
        "darkkhaki" to 0xbdb76b,
        "darkmagenta" to 0x8b008b,
        "darkolivegreen" to 0x556b2f,
        "darkorange" to 0xff8c00,
        "darkorchid" to 0x9932cc,
        "darkred" to 0x8b0000,
        "darksalmon" to 0xe9967a,
        "darkseagreen" to 0x8fbc8f,
        "darkslateblue" to 0x483d8b,
        "darkslategray" to 0x2f4f4f,
        "darkslategrey" to 0x2f4f4f,
        "darkturquoise" to 0x00ced1,
        "darkviolet" to 0x9400d3,
        "deeppink" to 0xff1493,
        "deepskyblue" to 0x00bfff,
        "dimgray" to 0x696969,
        "dimgrey" to 0x696969,
        "dodgerblue" to 0x1e90ff,
        "firebrick" to 0xb22222,
        "floralwhite" to 0xfffaf0,
        "forestgreen" to 0x228b22,
        "fuchsia" to 0xff00ff,
        "gainsboro" to 0xdcdcdc,
        "ghostwhite" to 0xf8f8ff,
        "gold" to 0xffd700,
        "goldenrod" to 0xdaa520,
        "gray" to 0x808080,
        "green" to 0x008000,
        "greenyellow" to 0xadff2f,
        "grey" to 0x808080,
        "honeydew" to 0xf0fff0,
        "hotpink" to 0xff69b4,
        "indianred" to 0xcd5c5c,
        "indigo" to 0x4b0082,
        "ivory" to 0xfffff0,
        "khaki" to 0xf0e68c,
        "lavender" to 0xe6e6fa,
        "lavenderblush" to 0xfff0f5,
        "lawngreen" to 0x7cfc00,
        "lemonchiffon" to 0xfffacd,
        "lightblue" to 0xadd8e6,
        "lightcoral" to 0xf08080,
        "lightcyan" to 0xe0ffff,
        "lightgoldenrodyellow" to 0xfafad2,
        "lightgray" to 0xd3d3d3,
        "lightgreen" to 0x90ee90,
        "lightgrey" to 0xd3d3d3,
        "lightpink" to 0xffb6c1,
        "lightsalmon" to 0xffa07a,
        "lightseagreen" to 0x20b2aa,
        "lightskyblue" to 0x87cefa,
        "lightslategray" to 0x778899,
        "lightslategrey" to 0x778899,
        "lightsteelblue" to 0xb0c4de,
        "lightyellow" to 0xffffe0,
        "lime" to 0x00ff00,
        "limegreen" to 0x32cd32,
        "linen" to 0xfaf0e6,
        "magenta" to 0xff00ff,
        "maroon" to 0x800000,
        "mediumaquamarine" to 0x66cdaa,
        "mediumblue" to 0x0000cd,
        "mediumorchid" to 0xba55d3,
        "mediumpurple" to 0x9370db,
        "mediumseagreen" to 0x3cb371,
        "mediumslateblue" to 0x7b68ee,
        "mediumspringgreen" to 0x00fa9a,
        "mediumturquoise" to 0x48d1cc,
        "mediumvioletred" to 0xc71585,
        "midnightblue" to 0x191970,
        "mintcream" to 0xf5fffa,
        "mistyrose" to 0xffe4e1,
        "moccasin" to 0xffe4b5,
        "navajowhite" to 0xffdead,
        "navy" to 0x000080,
        "oldlace" to 0xfdf5e6,
        "olive" to 0x808000,
        "olivedrab" to 0x6b8e23,
        "orange" to 0xffa500,
        "orangered" to 0xff4500,
        "orchid" to 0xda70d6,
        "palegoldenrod" to 0xeee8aa,
        "palegreen" to 0x98fb98,
        "paleturquoise" to 0xafeeee,
        "palevioletred" to 0xdb7093,
        "papayawhip" to 0xffefd5,
        "peachpuff" to 0xffdab9,
        "peru" to 0xcd853f,
        "pink" to 0xffc0cb,
        "plum" to 0xdda0dd,
        "powderblue" to 0xb0e0e6,
        "purple" to 0x800080,
        "rebeccapurple" to 0x663399,
        "red" to 0xff0000,
        "rosybrown" to 0xbc8f8f,
        "royalblue" to 0x4169e1,
        "saddlebrown" to 0x8b4513,
        "salmon" to 0xfa8072,
        "sandybrown" to 0xf4a460,
        "seagreen" to 0x2e8b57,
        "seashell" to 0xfff5ee,
        "sienna" to 0xa0522d,
        "silver" to 0xc0c0c0,
        "skyblue" to 0x87ceeb,
        "slateblue" to 0x6a5acd,
        "slategray" to 0x708090,
        "slategrey" to 0x708090,
        "snow" to 0xfffafa,
        "springgreen" to 0x00ff7f,
        "steelblue" to 0x4682b4,
        "tan" to 0xd2b48c,
        "teal" to 0x008080,
        "thistle" to 0xd8bfd8,
        "tomato" to 0xff6347,
        "turquoise" to 0x40e0d0,
        "violet" to 0xee82ee,
        "wheat" to 0xf5deb3,
        "white" to 0xffffff,
        "whitesmoke" to 0xf5f5f5,
        "yellow" to 0xffff00,
        "yellowgreen" to 0x9acd32,
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
