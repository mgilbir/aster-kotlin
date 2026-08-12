package dev.aster.vega.runtime.compile

import dev.aster.vega.model.spec.GuideStroke
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.FontStyle
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.Stroke
import dev.aster.vega.scene.StrokeCap
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextBaseline
import dev.aster.vega.scene.TextStyle

/**
 * Turns a guide's `{part}Color`/`Width`/`Dash`/`Opacity`/`Font…` properties into scene paint.
 *
 * All of it is pass-through — upstream puts these on the mark unchanged, verified by reading the
 * scenegraph of a fully styled axis — with one thing worth stating: a *label's* colour is a fill
 * and everything else's is a stroke, so `labelOpacity` lands on `fillOpacity` while `gridOpacity`
 * lands on `strokeOpacity`. They are not interchangeable to a renderer.
 *
 * A width participates in measurement wherever the part it belongs to does. A 9-unit tick therefore
 * makes the chart a unit wider and a 9-unit gridline does not, because upstream measures an axis by
 * its ticks and labels and leaves the gridlines out.
 */
internal object GuideStyle {

  fun stroke(style: GuideStroke, defaultColor: SceneColor): Stroke =
    Stroke(
      paint = ScenePaint.Solid(style.color?.let { SceneColor.parse(it) } ?: defaultColor),
      width = style.width ?: AxisDefaults.TICK_WIDTH,
      cap = capOf(style.cap),
      dashArray = style.dash ?: emptyList(),
      dashOffset = style.dashOffset ?: 0.0,
      opacity = style.opacity ?: 1.0,
    )

  /** A CSS line cap. Anything unrecognised leaves the default, which is what upstream draws. */
  fun capOf(name: String?): StrokeCap =
    when (name?.lowercase()) {
      "round" -> StrokeCap.ROUND
      "square" -> StrokeCap.SQUARE
      else -> StrokeCap.BUTT
    }

  /** An explicit alignment on a guide's text part, or null to keep the derived one. */
  fun alignOf(name: String?): TextAlign? =
    when (name?.lowercase()) {
      "left" -> TextAlign.LEFT
      "center" -> TextAlign.CENTER
      "right" -> TextAlign.RIGHT
      else -> null
    }

  fun baselineOf(name: String?): TextBaseline? =
    when (name?.lowercase()) {
      "top" -> TextBaseline.TOP
      "middle" -> TextBaseline.MIDDLE
      "bottom" -> TextBaseline.BOTTOM
      "alphabetic" -> TextBaseline.ALPHABETIC
      "line-top" -> TextBaseline.LINE_TOP
      "line-bottom" -> TextBaseline.LINE_BOTTOM
      else -> null
    }

  fun fill(style: GuideStroke, defaultColor: SceneColor): Fill =
    Fill(
      paint = ScenePaint.Solid(style.color?.let { SceneColor.parse(it) } ?: defaultColor),
      opacity = style.opacity ?: 1.0,
    )

  fun text(style: GuideStroke, fontSize: Double, defaultWeight: Int): TextStyle =
    TextStyle(
      fontFamily = style.font ?: AxisDefaults.LABEL_FONT_FAMILY,
      fontSize = fontSize,
      lineHeight = style.lineHeight,
      fontWeight = style.fontWeight?.let(::weightOf) ?: defaultWeight,
      fontStyle =
        if (style.fontStyle.equals("italic", ignoreCase = true)) {
          FontStyle.ITALIC
        } else {
          FontStyle.NORMAL
        },
    )

  /**
   * CSS font weights: a keyword or a number, and the scene graph wants the number.
   *
   * Anything unrecognized falls back to normal rather than throwing, since a weight is a rendering
   * detail and a chart that refuses to draw over one is the worse outcome.
   */
  private fun weightOf(weight: String): Int =
    when (weight.lowercase()) {
      "normal" -> 400
      "bold" -> 700
      "lighter" -> 300
      "bolder" -> 700
      else -> weight.toIntOrNull()?.coerceIn(1, 1000) ?: 400
    }
}
