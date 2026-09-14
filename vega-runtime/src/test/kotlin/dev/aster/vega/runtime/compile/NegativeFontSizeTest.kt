package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.TextNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A **negative** font size, which is a number a specification may write and upstream draws.
 *
 * ```js
 * export function fontSize(item) {
 *   return item.fontSize != null ? (+item.fontSize || 0) : 11;
 * }
 * function _estimateWidth(text, currentFontHeight) {
 *   return ~~(0.8 * text.length * currentFontHeight);
 * }
 * ```
 *
 * Nothing there rejects it: the width comes out **negative**, so the text reaches backwards from
 * its anchor and its line box mirrors about it — `fontSize: -4` at `x = 10` bounds `[-6, 10]` where
 * `+4` bounds `[10, 26]`, probed on a live view. Upstream draws the chart.
 *
 * This engine required a non-negative one on `TextStyle`, an invariant of its own imposed on a
 * number that arrives from a document. The exception escaped the encoder and the whole compile was
 * reported as *a defect in this engine* — so a chart upstream draws became no chart at all, and the
 * diagnostic pointed the reader at the port rather than at their own specification.
 *
 * What is asserted here is that the chart is drawn and the number is carried. The guide *layout*
 * around a negative font size is not yet exact — `scripts/property-sweep.sh` has four cases a few
 * units apart, in the arithmetic that places an axis title and a legend title — and a test that
 * pinned this engine's current answer there would be pinning a difference rather than an agreement.
 */
class NegativeFontSizeTest {

  private val spec =
    """
    {
      "width": 200, "height": 120, "padding": 5,
      "data": [{"name": "t", "values": [{"c": "alpha", "v": 28}, {"c": "beta", "v": 55}]}],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
        {"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"}, "range": "height"}
      ],
      "axes": [{"orient": "bottom", "scale": "x", "labelFontSize": -4, "title": "category"}],
      "marks": [
        {"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
          "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
          "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0}
        }}}
      ]
    }
    """
      .trimIndent()

  private fun descendants(node: SceneNode): List<SceneNode> =
    listOf(node) + ((node as? GroupNode)?.children?.flatMap { descendants(it) } ?: emptyList())

  @Test
  fun `a negative font size draws a chart rather than taking it down`() {
    val compiled = SpecCompiler().compileJson(spec)

    val scene = requireNotNull(compiled.scene) { "upstream draws this chart; so must this engine" }
    assertTrue(
      compiled.diagnostics.none { it.severity >= DiagnosticSeverity.ERROR },
      "no error was called for: ${compiled.diagnostics.map { it.message }}",
    )
    val labels =
      descendants(scene.root).filterIsInstance<TextNode>().filter {
        it.metadata.role == "axis-label"
      }
    assertEquals(2, labels.size, "one label per band")
    // The number is **carried**, not clamped: upstream puts `fontSize: -4` on the item, and a
    // reader inspecting the scene or a renderer writing an attribute sees what was asked for.
    assertEquals(listOf(-4.0, -4.0), labels.map { it.layout.run.style.fontSize })
  }
}
