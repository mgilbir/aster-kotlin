package dev.aster.vega.android

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import dev.aster.vega.scene.FontStyle
import dev.aster.vega.scene.MeasuredTextEngine
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextStyle

/**
 * Text measurement backed by the platform's own text stack.
 *
 * This is the same engine the renderer draws with, so a label always lands where layout put it
 * (PROJECT_BRIEF.md 9). Measurement happens during scene compilation, never inside `onDraw`.
 *
 * A [MeasuredTextEngine], which is the point of this class being short. The layout — splitting on
 * newlines, honouring `limit` and its ellipsis, wrapping to a constraint, stacking baselines,
 * deriving bounds from the alignment — belongs to the base class and is shared with
 * `MetricTextEngine` and with `CoreTextTextEngine` in `swift/AsterVegaRender`. This answers the one
 * question the platform is the authority on: how wide is this string, and how far does this font
 * rise and fall.
 *
 * It was the last engine still carrying its own copy of that layout, and the copy differed: it
 * wrapped through `StaticLayout` where the other two wrap greedily on spaces. Two answers to "where
 * do the lines break" is two answers to where every label sits, and the *host* decided which a
 * chart got. Upstream Vega does not wrap at all — it breaks on `\n` and truncates with `limit` — so
 * a constraint is this engine's own addition, and an addition that behaves differently per platform
 * is worse than one that behaves simply. What the platform still decides is every number: advances,
 * ascent, descent and the font's own line height.
 *
 * Not thread-safe: it owns mutable [TextPaint]s. Create one per renderer or per compile pass, or
 * call [VegaChartView.newCompatibleTextEngine].
 */
public class AndroidTextEngine(
  /**
   * The reader's text scale, which enlarges the box a label is laid out in as well as the glyphs.
   *
   * A scale reaches the **layout** through this, which is the whole reason it is a constructor
   * parameter and not something applied when drawing: text drawn larger inside a box measured
   * smaller is what makes axis labels overlap. `VegaChartView` passes the device's own `fontScale`;
   * a caller building an engine by hand passes whatever its host uses, and 1 means "the size the
   * specification asked for".
   */
  private val fontScale: Float = 1f
) : MeasuredTextEngine() {

  /**
   * Two paints, and the separation is deliberate.
   *
   * [measurePaint] is never handed out, so a measurement cannot be perturbed by a caller that
   * configured the paint for drawing — the renderer sets a colour, a shader and an alignment on the
   * paint it is given. One shared paint made every advance depend on what had last been drawn with
   * it, which is the kind of coupling that produces a chart whose labels move when a mark's fill
   * changes.
   *
   * Both are configured by [configure], so they cannot disagree about a font.
   */
  private val measurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
  private val drawPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

  private val typefaceCache = HashMap<TypefaceKey, Typeface>()
  private val metricsCache = LinkedHashMap<TextStyle, LineMetrics>()

  /** The style [measurePaint] currently carries, so an unchanged style costs no reconfiguration. */
  private var measuring: TextStyle? = null

  private data class TypefaceKey(val family: String, val weight: Int, val italic: Boolean)

  private class LineMetrics(val ascent: Double, val descent: Double, val lineHeight: Double)

  override fun advanceOf(line: String, style: TextStyle): Double {
    if (line.isEmpty()) return 0.0
    return measure(style).measureText(line).toDouble()
  }

  override fun ascentOf(style: TextStyle): Double = lineMetrics(style).ascent

  override fun descentOf(style: TextStyle): Double = lineMetrics(style).descent

  /**
   * The font's own line height rather than upstream's `fontSize + 2`.
   *
   * What this engine has always used — `descent - ascent` off the platform's font metrics — and
   * what the base class invites a platform engine to prefer, for the same reason the advances are
   * platform numbers: leading is part of what a font says about itself, and a stack of lines drawn
   * by Android sits where Android's metrics put it. A degenerate font size would collapse every
   * baseline onto one, so that falls back to upstream's rule.
   */
  override fun defaultLineHeightOf(style: TextStyle): Double =
    lineMetrics(style).lineHeight.takeIf { it > 0.0 } ?: (style.fontSize + 2.0)

  private fun lineMetrics(style: TextStyle): LineMetrics {
    metricsCache.remove(style)?.let {
      // Re-inserted so the eviction below drops the least recently used rather than the oldest.
      metricsCache[style] = it
      return it
    }
    val metrics = measure(style).fontMetrics
    val computed =
      LineMetrics(
        ascent = -metrics.ascent.toDouble(),
        descent = metrics.descent.toDouble(),
        lineHeight = (metrics.descent - metrics.ascent).toDouble(),
      )
    metricsCache[style] = computed
    if (metricsCache.size > MAX_CACHED_STYLES) metricsCache.remove(metricsCache.keys.first())
    return computed
  }

  /** [measurePaint], configured for [style] — and reconfigured only when the style has changed. */
  private fun measure(style: TextStyle): TextPaint {
    if (measuring != style) {
      configure(measurePaint, style)
      measuring = style
    }
    return measurePaint
  }

  /** Configures a paint for [style]. The one place a font is chosen, for measuring and drawing. */
  private fun configure(paint: TextPaint, style: TextStyle) {
    paint.reset()
    paint.isAntiAlias = true
    paint.textSize = (style.fontSize * fontScale).toFloat()
    paint.typeface = resolveTypeface(style)
    // Android's letter spacing is in **ems**, and a specification's is in the same units as the
    // font size. Applied to the measurement as well as the drawing, which is what CSS does: the
    // spacing follows every character, the last one included.
    paint.letterSpacing =
      if (style.fontSize > 0.0) (style.letterSpacing / style.fontSize).toFloat() else 0f
    paint.textAlign = Paint.Align.LEFT
  }

  /** The configured paint, for the renderer to draw with. Do not retain it across style changes. */
  internal fun paintFor(style: TextStyle): TextPaint {
    configure(drawPaint, style)
    return drawPaint
  }

  internal fun androidAlign(align: TextAlign): Paint.Align =
    when (align) {
      TextAlign.LEFT -> Paint.Align.LEFT
      TextAlign.CENTER -> Paint.Align.CENTER
      TextAlign.RIGHT -> Paint.Align.RIGHT
    }

  private fun resolveTypeface(style: TextStyle): Typeface {
    val italic = style.fontStyle == FontStyle.ITALIC
    val key = TypefaceKey(style.fontFamily, style.fontWeight, italic)
    return typefaceCache.getOrPut(key) {
      createTypeface(style.fontFamily, style.fontWeight, italic)
    }
  }

  private fun createTypeface(family: String, weight: Int, italic: Boolean): Typeface {
    // Vega's generic families map onto Android's built-in families; anything else is passed through
    // to the platform, which falls back rather than failing.
    val base =
      when (family.lowercase()) {
        "sans-serif",
        "helvetica",
        "arial" -> Typeface.SANS_SERIF
        "serif",
        "times",
        "times new roman" -> Typeface.SERIF
        "monospace",
        "courier",
        "courier new" -> Typeface.MONOSPACE
        else -> Typeface.create(family, Typeface.NORMAL)
      }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      Typeface.create(base, weight.coerceIn(1, 1000), italic)
    } else {
      val legacyStyle =
        when {
          weight >= 600 && italic -> Typeface.BOLD_ITALIC
          weight >= 600 -> Typeface.BOLD
          italic -> Typeface.ITALIC
          else -> Typeface.NORMAL
        }
      Typeface.create(base, legacyStyle)
    }
  }

  private companion object {
    /**
     * A chart has a handful of text styles — an axis label, a title, a legend entry — so this is
     * far more than a real specification needs, and exists only so a generated one cannot grow the
     * map without bound.
     */
    const val MAX_CACHED_STYLES = 256
  }
}
