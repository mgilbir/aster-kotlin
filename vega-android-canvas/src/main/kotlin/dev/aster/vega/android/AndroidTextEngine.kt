package dev.aster.vega.android

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.TextPaint
import dev.aster.vega.scene.FontStack
import dev.aster.vega.scene.FontStyle
import dev.aster.vega.scene.MeasuredTextEngine
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextStyle

/**
 * Text measurement backed by the platform's own text stack.
 *
 * This is the same engine the renderer draws with, so a label always lands where layout put it (ADR
 * 0006). Measurement happens during scene compilation, never inside `onDraw`.
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
  private val fontScale: Float = 1f,
  /**
   * A face this **host** ships, by the family name a specification asks for.
   *
   * Android resolves a family name against the *system's* families, so an app that bundles a font —
   * which most design systems do — could not get a chart to use it: `Typeface.create("Google Sans
   * Flex", …)` finds nothing and falls back to the default, silently. The Compose renderer took a
   * resolver for exactly this reason and this side did not, so the same specification drew in the
   * app's face on one renderer and not on the other.
   *
   * Answer null to leave a name to the platform, which is what the default does for every name: the
   * generic families still map onto Android's built-ins and anything else is passed through as
   * before. The result is cached per family, weight and slant, so a resolver may load a font file —
   * but it is asked on a *compile* thread as well as a drawing one, so it must be safe to call from
   * either.
   *
   * `ResourcesCompat.getFont(context, R.font.…)` is the usual answer; a `Typeface.Builder` over an
   * asset is the other.
   */
  private val typefaceResolver: (String) -> Typeface? = { null },
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

  /**
   * The font families this engine drew in something else.
   *
   * A name it cannot resolve does not fail: Android answers with the default face, which is legible
   * and is not what the specification asked for, and nothing said so. The Compose Multiplatform
   * engine has collected these since it had a resolver; this one and the Apple renderer did not, so
   * two of the three renderers fell back **silently** — which is a fair part of why they disagreed
   * about how to read a CSS stack for as long as they did (#123).
   *
   * There is no diagnostics channel through a text engine, so the facts are collected and a host
   * reads them when it wants to, exactly as `unresolvedImages` works.
   *
   * A well-formed stack is not in here: `"Helvetica Neue, Arial, sans-serif"` ends in a generic and
   * resolves on it. What lands here is a stack with nothing behind it.
   */
  public val unresolvedFontFamilies: Set<String>
    get() = unresolved.toSet()

  private val unresolved = LinkedHashSet<String>()

  private fun createTypeface(family: String, weight: Int, italic: Boolean): Typeface {
    // **Each name in the stack, in order.** `family` is what the specification wrote — a CSS list,
    // often `"Noto Sans, Chart Sans"` — and this used to hand the whole string to the resolver, so
    // a
    // host that had registered `Chart Sans` was asked for a name nothing matches and never
    // answered.
    // The Compose Multiplatform engine had always split it, so one specification drew in two faces
    // across two Kotlin hosts (#123). `FontStack` is that rule, once.
    //
    // The host's face first, and then weight and slant applied to *it* rather than to a system
    // family: an app's bold is its own face, and asking the platform for "some bold font" is how a
    // label comes out in a face nothing in the chart mentions.
    for (name in FontStack.families(family)) {
      typefaceResolver(name)?.let { supplied ->
        return styled(supplied, weight, italic)
      }
    }
    // **Then the platform, name by name, still in order.**
    //
    // This used to search the whole stack for a generic *first* — `firstOrNull { it in
    // GENERIC_ALIASES }` — and use it if one appeared anywhere. But a generic is the **last**
    // resort, in CSS and in every browser: `"Chart Sans, sans-serif"` means "Chart Sans if this
    // device has it, otherwise any sans". Preempting it meant a device that *did* have Chart Sans
    // installed system-wide never drew in it, and the fallback nobody would notice was the one
    // always taken.
    val names = FontStack.families(family)
    for (name in names) {
      builtIn(name)?.let {
        return styled(it, weight, italic)
      }
      val created = Typeface.create(name, Typeface.NORMAL)
      // `Typeface.create` never fails: an unknown name answers the default face. Comparing against
      // it is the only way to tell "this device has that font" from "it does not", so a name that
      // came back as the default is treated as absent and the next name in the stack is tried.
      if (created != Typeface.DEFAULT) return styled(created, weight, italic)
    }

    // Nothing in the stack resolved. Reported once, and then drawn in the platform default, which
    // is what a browser does with a stack it cannot satisfy either.
    //
    // The comparison above has a known false negative and it is worth naming: on an OEM build whose
    // *default* family is a named face, asking for that name by name returns an object equal to
    // `Typeface.DEFAULT`, so the font is reported unresolved while being exactly what gets drawn.
    // The report is over-eager rather than the drawing wrong, and there is no API on this platform
    // that distinguishes the two — `Typeface` exposes no family name to compare against.
    if (family.isNotBlank()) unresolved.add(family)
    return styled(Typeface.DEFAULT, weight, italic)
  }

  /**
   * Vega's generic families, and the near-generic names, as Android's built-in families.
   *
   * Wider than CSS's generics on purpose: a specification naming `Helvetica` or `Courier` on a
   * device that has neither is asking for a sans or a mono, and Android answers with the family
   * rather than with nothing.
   */
  private fun builtIn(name: String): Typeface? =
    when (name.lowercase()) {
      "sans-serif",
      "helvetica",
      "arial" -> Typeface.SANS_SERIF
      "serif",
      "times",
      "times new roman" -> Typeface.SERIF
      "monospace",
      "courier",
      "courier new" -> Typeface.MONOSPACE
      else -> null
    }

  /** A face at a weight and slant, through the widest API the device has. */
  private fun styled(base: Typeface, weight: Int, italic: Boolean): Typeface =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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

  private companion object {
    /**
     * A chart has a handful of text styles — an axis label, a title, a legend entry — so this is
     * far more than a real specification needs, and exists only so a generated one cannot grow the
     * map without bound.
     */
    const val MAX_CACHED_STYLES = 256
  }
}
