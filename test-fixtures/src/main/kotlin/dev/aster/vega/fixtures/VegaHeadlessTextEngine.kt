package dev.aster.vega.fixtures

import dev.aster.vega.scene.SizeD
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.TextLayout
import dev.aster.vega.scene.TextLine
import dev.aster.vega.scene.TextMetrics
import dev.aster.vega.scene.TextRun
import dev.aster.vega.scene.textBounds

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
    val lines = text.text.split('\n')
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
      bounds = textBounds(text, metrics),
    )
  }

  /** `~~` in JavaScript truncates toward zero, so this floors rather than rounds. */
  private fun estimateWidth(text: String, fontSize: Double): Double =
    (0.8 * text.length * fontSize).toInt().toDouble()
}
