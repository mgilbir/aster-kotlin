package dev.aster.vega.fixtures

import dev.aster.vega.model.roundHalfUp
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.SizeD
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextBaseline
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.TextLayout
import dev.aster.vega.scene.TextLine
import dev.aster.vega.scene.TextMetrics
import dev.aster.vega.scene.TextRun
import dev.aster.vega.scene.displayText

/**
 * Reproduces upstream Vega's text measurement when it runs without a canvas.
 *
 * Vega's `vega-scenegraph` falls back to `~~(0.8 * text.length * fontSize)` — truncated, not
 * rounded — with a line height of `fontSize` when no canvas is available, which is the situation in
 * the Node oracle. Matching it exactly is what lets differential tests compare **layout** as well
 * as geometry: under `autosize: pad` the surface size depends on how wide the axis labels are, so
 * without this the two engines would disagree on every chart's overall size for reasons that have
 * nothing to do with the port's correctness.
 *
 * This is a comparison engine, not a rendering one. It deliberately matches a crude approximation
 * and must never be used to lay out a chart for display — real text metrics come from
 * `AndroidTextEngine` on a device, or `MetricTextEngine` for platform-independent goldens
 * (docs/adr/0006).
 */
public class VegaHeadlessTextEngine : TextEngine {

  override fun measure(text: TextRun, constraint: SizeD?): TextMetrics =
    layout(text, constraint).metrics

  override fun layout(text: TextRun, constraint: SizeD?): TextLayout {
    val fontSize = text.style.fontSize
    // Vega's fallback ignores wrapping entirely; only explicit newlines break a line.
    val lines = text.displayText { estimateWidth(it, fontSize) }.split('\n')
    val lineHeight = text.style.lineHeight ?: fontSize

    val measured = lines.map { line -> TextLine(line, estimateWidth(line, fontSize), 0.0) }
    val positioned = measured.mapIndexed { index, line ->
      line.copy(baselineY = index * lineHeight)
    }

    // Vega reports a line box exactly `fontSize` tall, split evenly, so a middle-baselined label
    // centres on its anchor and a top-baselined one starts at it.
    val half = fontSize / 2.0
    val metrics =
      TextMetrics(
        width = positioned.maxOfOrNull { it.width } ?: 0.0,
        height = if (positioned.isEmpty()) 0.0 else (positioned.size - 1) * lineHeight + fontSize,
        ascent = half,
        descent = half,
        lineCount = positioned.size,
        lineHeight = lineHeight,
      )
    return TextLayout(
      run = text,
      metrics = metrics,
      lines = positioned,
      bounds = bounds(text, metrics),
    )
  }

  /** `~~` in JavaScript truncates toward zero, so this floors rather than rounds. */
  private fun estimateWidth(text: String, fontSize: Double): Double =
    (0.8 * text.length * fontSize).toInt().toDouble()

  /**
   * Upstream's own text bounds, which are not quite the ideal ones [textBounds] computes.
   *
   * Vega does its own baseline arithmetic rather than trusting a renderer's, and it rounds twice:
   * once for the baseline offset and once for the `4/5` correction it subtracts back off. The two
   * roundings usually cancel, but not always — at a 12px font a top-baselined box starts one unit
   * *above* its anchor, where at 10, 11 and 13 it starts exactly on it. That single unit is enough
   * to move a chart title, because the title is placed against the height of the box below it.
   *
   * This belongs here and not in [textBounds]: it is an artefact of Vega's canvas-free fallback,
   * and baking it into the scene model would make a real device inherit it.
   */
  private fun bounds(run: TextRun, metrics: TextMetrics): RectD {
    val fontSize = run.style.fontSize
    val baselineOffset =
      when (run.baseline) {
        TextBaseline.TOP -> 0.79 * fontSize
        TextBaseline.MIDDLE -> 0.30 * fontSize
        TextBaseline.BOTTOM -> -0.21 * fontSize
        TextBaseline.LINE_TOP -> 0.29 * fontSize + 0.5 * metrics.lineHeight
        TextBaseline.LINE_BOTTOM -> 0.29 * fontSize - 0.5 * metrics.lineHeight
        TextBaseline.ALPHABETIC -> 0.0
      }
    val top = roundHalfUp(baselineOffset) - roundHalfUp(0.8 * fontSize)
    val left =
      when (run.align) {
        TextAlign.LEFT -> 0.0
        TextAlign.CENTER -> -metrics.width / 2.0
        TextAlign.RIGHT -> -metrics.width
      }
    return RectD(left, top, left + metrics.width, top + metrics.height)
  }
}
