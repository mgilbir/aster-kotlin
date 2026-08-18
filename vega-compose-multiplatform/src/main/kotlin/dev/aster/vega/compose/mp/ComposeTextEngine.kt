package dev.aster.vega.compose.mp

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle as ComposeTextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle as ComposeFontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.aster.vega.scene.FontStyle as SceneFontStyle
import dev.aster.vega.scene.MeasuredTextEngine
import dev.aster.vega.scene.TextStyle

/**
 * Measures text with **Compose**, so a chart drawn by Compose is laid out by the font that draws
 * it.
 *
 * Without this the module fell back to `MetricTextEngine`, whose advance is `length * fontSize *
 * 0.6` and whose own documentation says it matches no real font. Every reserved box was then the
 * wrong width while Compose drew real glyphs into it: right-aligned axis labels over the domain
 * line, a long label overhanging the space kept for it. `CoreTextTextEngine` in
 * `swift/AsterVegaRender` exists for exactly this reason on iOS, and its header records the same
 * symptom.
 *
 * A subclass rather than a reimplementation. [MeasuredTextEngine] owns the layout — newlines,
 * `limit` and its ellipsis, wrapping, baselines, bounds from the alignment — and asks only how wide
 * a string is in a style, so this file is three measurements and the arithmetic that carries them
 * between two coordinate systems.
 *
 * **Units, which is the part that is easy to get wrong.** The engine works in scene units, which
 * are CSS pixels and are what a `dp` is on every platform this runs on; Compose measures in
 * *pixels*. So every number Compose returns is divided by [Density.density] on the way out. The
 * **font scale** is deliberately *not* divided out: a run is measured at `fontSize.sp`, so a reader
 * who has asked for larger text gets a larger measurement, and the axis therefore reserves the
 * larger box. That is the whole point of feeding the scale in here rather than applying it when
 * drawing — text drawn bigger inside a box measured smaller is what makes labels overlap.
 *
 * [DrawScopeTarget] undoes the same conversion when it draws, and both of them resolve a font
 * family through the same [fontFamilyResolver]; measuring with one font and drawing with another is
 * the original bug in a different disguise. [rememberVegaTextEngine] wires a matched pair from the
 * composition, which is the way to get one.
 *
 * Not thread-safe: the metric cache is a plain map, and a `TextEngine` is documented as belonging
 * to one compile at a time.
 *
 * @param measurer the platform's text measurement, which must have been built with [density].
 * @param density the composition's density **and font scale**; see above.
 * @param fontFamilyResolver turns a specification's family name into a Compose family. The default
 *   maps the generic CSS names and answers `null` for anything else, which means the platform's
 *   default face.
 */
public class ComposeTextEngine(
  internal val measurer: TextMeasurer,
  private val density: Density,
  internal val fontFamilyResolver: (String) -> FontFamily? = ::genericFontFamily,
) : MeasuredTextEngine() {

  /** Ascent, descent and line height for one style, which cost a measurement each to find out. */
  private class LineMetrics(val ascent: Double, val descent: Double, val lineHeight: Double)

  private val metrics = LinkedHashMap<TextStyle, LineMetrics>()

  override fun advanceOf(line: String, style: TextStyle): Double {
    if (line.isEmpty()) return 0.0
    // `getLineRight` is a Float where `size.width` is an `IntSize`, and the difference is not
    // academic: a rounded advance per label accumulates into a visibly wrong axis, and the layout
    // arithmetic above this is all in doubles.
    return measurer
      .measure(text = line, style = composeTextStyle(style))
      .getLineRight(0)
      .toDouble() / density.density
  }

  override fun ascentOf(style: TextStyle): Double = lineMetrics(style).ascent

  override fun descentOf(style: TextStyle): Double = lineMetrics(style).descent

  /**
   * The font's own line height rather than upstream's `fontSize + 2`.
   *
   * The base class invites a platform engine to prefer this and it is the right answer for the same
   * reason the advances are: leading is part of what a font says about itself, and a stack of lines
   * drawn by Compose sits where Compose's metrics put it.
   */
  override fun defaultLineHeightOf(style: TextStyle): Double =
    lineMetrics(style).lineHeight.takeIf { it > 0.0 } ?: (style.fontSize + 2.0)

  private fun lineMetrics(style: TextStyle): LineMetrics {
    metrics.remove(style)?.let {
      // Re-inserted so the eviction below takes the least recently used, not the oldest.
      metrics[style] = it
      return it
    }
    // A probe with an ascender and a descender in it. The metrics come from the *font*, so the
    // string only has to be non-empty; these two letters make a failure obvious in a debugger.
    val probe = measurer.measure(text = "Hg", style = composeTextStyle(style))
    val scale = density.density
    val computed =
      LineMetrics(
        ascent = (probe.getLineBaseline(0) - probe.getLineTop(0)).toDouble() / scale,
        descent = (probe.getLineBottom(0) - probe.getLineBaseline(0)).toDouble() / scale,
        lineHeight = (probe.getLineBottom(0) - probe.getLineTop(0)).toDouble() / scale,
      )
    metrics[style] = computed
    if (metrics.size > MAX_CACHED_STYLES) metrics.remove(metrics.keys.first())
    return computed
  }

  /** A scene unit is an `sp` here: the measurement is meant to carry the reader's font scale. */
  private fun composeTextStyle(style: TextStyle) =
    composeStyleOf(style, spPerSceneUnit = 1f, fontFamilyResolver = fontFamilyResolver)

  private companion object {
    /**
     * A chart has a handful of text styles — an axis label, a title, a legend entry — so this is
     * three orders of magnitude more than a real specification needs and exists only so a generated
     * one cannot grow the map without bound.
     */
    const val MAX_CACHED_STYLES = 256
  }
}

/**
 * A [ComposeTextEngine] and the measurer behind it, built from the composition's own density.
 *
 * The pair matters more than either half: the engine measures with the composition's density and
 * font scale, and [VegaChart] draws with the same ones. A caller who builds the engine by hand has
 * to keep them in step; this does it for them.
 *
 * The scene has to be compiled **with this engine** for any of it to help — a chart compiled with
 * `MetricTextEngine` and drawn here is laid out from advances that match no font:
 * ```
 * val engine = rememberVegaTextEngine()
 * val compiled = remember(engine, json) { SpecCompiler(engine).compileJson(json) }
 * compiled.scene?.let { VegaChart(it, textEngine = engine) }
 * ```
 *
 * Remembered against the density, the font scale, the layout direction and the resolver, so a
 * reader changing their text size rebuilds the engine — and, because the scene is remembered
 * against the engine, recompiles the chart. That is the only way a larger font reaches the *layout*
 * rather than only the drawing.
 */
@Composable
public fun rememberVegaTextEngine(
  fontFamilyResolver: (String) -> FontFamily? = ::genericFontFamily
): ComposeTextEngine {
  val density = LocalDensity.current
  val direction = LocalLayoutDirection.current
  val measurer = rememberTextMeasurer()
  return remember(measurer, density.density, density.fontScale, direction, fontFamilyResolver) {
    ComposeTextEngine(measurer, density, fontFamilyResolver)
  }
}

/**
 * The generic CSS families, and `null` for anything else.
 *
 * A specification names a font as a string, and resolving that string to a face installed on the
 * device is the platform's business — `AndroidTextEngine.createTypeface` makes the same mapping. A
 * host that ships its own face passes its own resolver: that is the one seam between "the chart
 * uses the app's font" and "the chart uses whatever the platform defaults to".
 *
 * A comma-separated stack is read left to right, as CSS reads it, and the first name that is a
 * generic family answers. Anything else is a face this function cannot know about, so it declines
 * rather than guessing — `null` means [FontFamily.Default].
 */
public fun genericFontFamily(family: String): FontFamily? {
  for (name in family.split(',')) {
    val trimmed = name.trim().trim('"', '\'').lowercase()
    when (trimmed) {
      "sans-serif",
      "system-ui",
      "ui-sans-serif" -> return FontFamily.SansSerif
      "serif",
      "ui-serif" -> return FontFamily.Serif
      "monospace",
      "ui-monospace" -> return FontFamily.Monospace
      "cursive" -> return FontFamily.Cursive
    }
  }
  return null
}

/**
 * One scene text style as Compose's.
 *
 * Shared by [ComposeTextEngine] and [DrawScopeTarget] so the two cannot disagree about a font, and
 * parameterised by the one thing they do differ about: how many **sp** a scene unit is.
 *
 * A scene unit is a CSS pixel, which is a `dp`, which is an `sp` before the reader's font scale is
 * applied. So the engine measures with `spPerSceneUnit = 1` and gets pixels that already carry the
 * font scale — the larger box a larger text setting needs. [DrawScopeTarget] draws inside a scope
 * that has already been scaled by the density, where a Compose pixel *is* a scene unit, so it
 * passes `1 / density` and the glyphs come out the size the layout reserved. Handing either of them
 * the other's factor is the double-density defect this parameter exists to make impossible to
 * write.
 */
internal fun composeStyleOf(
  style: TextStyle,
  spPerSceneUnit: Float,
  fontFamilyResolver: (String) -> FontFamily?,
): ComposeTextStyle =
  ComposeTextStyle(
    fontSize = (style.fontSize.toFloat() * spPerSceneUnit).sp,
    fontWeight = FontWeight(style.fontWeight.coerceIn(1, 1000)),
    fontStyle =
      if (style.fontStyle == SceneFontStyle.ITALIC) ComposeFontStyle.Italic
      else ComposeFontStyle.Normal,
    fontFamily = fontFamilyResolver(style.fontFamily) ?: FontFamily.Default,
    // CSS adds letter spacing after every character, the last one included, and so does Compose; a
    // browser measures the same string the same way. Left in the measurement rather than corrected
    // out of it, because the drawing applies it too.
    letterSpacing = (style.letterSpacing.toFloat() * spPerSceneUnit).sp,
  )
