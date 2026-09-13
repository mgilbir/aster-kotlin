package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A stroke that is **not a colour** is still a stroke, as far as the measuring goes.
 *
 * ```js
 * if (item.stroke && item.opacity !== 0 && item.strokeOpacity !== 0) {
 *   const sw = item.strokeWidth != null ? +item.strokeWidth : 1;
 *   …
 *   bounds.expand(e);
 * }
 * ```
 *
 * `boundStroke` asks whether the item **has** a stroke, not whether that stroke is a colour. So
 * upstream measures a mark stroked `"banana"` exactly as it measures one stroked `"#ffffff"`, and
 * carries the word through to its scenegraph unchanged.
 *
 * A templated dashboard reaches this by a route nobody designs: `{"name": "strokeColor", "value":
 * "'#FFFFFF'"}` — quotes and all, because the template's author was writing an expression and Deneb
 * stores it as a value. This engine read that as a broken colour, said so, and then drew the mark
 * with no stroke at all. Under `autosize: "fit"` a stroke-width of difference is not cosmetic: it
 * moves the measured overhang, which moves the plotting area, which moves every scale range and
 * every mark in the chart. Six of the sixty-three Deneb templates were out by exactly that.
 *
 * The colour is **transparent** here, because that is what the drawing comes to: a renderer handed
 * a colour it cannot read paints nothing with it. The warning is still reported.
 *
 * All six expectations were read off a live upstream view of the same specification.
 */
class UnreadableStrokeTest {

  private fun compile(stroke: String?) =
    SpecCompiler(VegaHeadlessTextEngine())
      .compileJson(
        """
        {
          "width": 100, "height": 100, "padding": 0, "autosize": "pad",
          "data": [{"name": "t", "values": [{"a": 1}]}],
          "marks": [{"type": "symbol", "from": {"data": "t"}, "encode": {"update": {
            "x": {"value": 50}, "y": {"value": 50}, "size": {"value": 50},
            ${stroke?.let { """"stroke": $it,""" } ?: ""}
            "strokeWidth": {"value": 0.5}
          }}}]
        }
        """
          .trimIndent()
      )

  /** The symbol's own bounds, which are what a `fit` or a `pad` is measured from. */
  private fun bounds(stroke: String?): String {
    val node =
      requireNotNull(compile(stroke).scene)
        .flatten()
        .map { it.node }
        .filterIsInstance<SymbolNode>()
        .single()
    return "${node.bounds.left} .. ${node.bounds.right}"
  }

  /** Half a unit of stroke either side of the 7.07-wide circle, which is 2 × the stroke width. */
  private val stroked = "45.46446609406726 .. 54.53553390593274"

  /** And without one, the circle alone. */
  private val bare = "46.46446609406726 .. 53.53553390593274"

  @Test
  fun `a colour that reads widens the mark`() {
    assertEquals(stroked, bounds("""{"value": "#ffffff"}"""))
  }

  @Test
  fun `a colour that does not read widens it the same`() {
    assertEquals(stroked, bounds("""{"value": "banana"}"""))
    assertEquals(stroked, bounds("""{"value": "'#FFFFFF'"}"""))
  }

  /** An **empty** string is falsy upstream, so it is no stroke rather than an unreadable one. */
  @Test
  fun `an empty stroke is no stroke`() {
    assertEquals(bare, bounds("""{"value": ""}"""))
  }

  @Test
  fun `an explicit null and an absent channel are no stroke either`() {
    assertEquals(bare, bounds("""{"value": null}"""))
    assertEquals(bare, bounds(null))
  }

  /** Unreadable is not silent: the chart is measured upstream's way and the reader is told why. */
  @Test
  fun `the unreadable colour is still reported`() {
    val warnings =
      compile("""{"value": "banana"}""")
        .diagnostics
        .filter { it.severity == DiagnosticSeverity.WARNING }
        .map { it.message }
    assertEquals(listOf("Could not parse colour 'banana' for channel 'stroke'"), warnings)
  }

  /** And it paints nothing, which is what a renderer does with a colour it cannot read. */
  @Test
  fun `the unreadable stroke paints nothing`() {
    val node =
      requireNotNull(compile("""{"value": "banana"}""").scene)
        .flatten()
        .map { it.node }
        .filterIsInstance<SymbolNode>()
        .single()
    val stroke = requireNotNull(node.stroke) { "the stroke should still be there" }
    assertEquals(0.5, stroke.width)
    assertTrue(stroke.isVisible, "it still measures as a stroke")
    assertEquals(
      0.0,
      (stroke.paint as dev.aster.vega.scene.ScenePaint.Solid).color.alpha,
      "nothing is painted with it",
    )
  }
}
