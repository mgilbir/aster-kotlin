package dev.aster.vega.runtime

import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `strokeWidth` with no `stroke`: a scene node here holds no stroke at all.
 *
 * `SUPPORTED_FEATURES.md` files this as a `Deliberate difference` and the reason is structural.
 * Upstream's scene items are property bags, so a mark encoding a width and no colour carries the
 * width around and paints no outline — its own SVG renderer emits neither attribute. A node here
 * holds what a renderer *needs*, and a stroke with no colour is not something a renderer needs, so
 * there is no stroke on the node to hold a width on.
 *
 * The difference is therefore invisible in the picture and visible in the comparison, which is
 * where it has to be managed: the differential harness accepts that one equivalence — a reference
 * carrying `strokeWidth` and no `stroke` against a node with no stroke — **and nothing wider**. A
 * reference carrying a stroke *colour* still demands a stroke of that width, which is what stops
 * the allowance becoming a hole.
 *
 * Both halves are pinned here. The day a node keeps a colourless stroke, the first goes red; the
 * day the allowance widens, the second does.
 */
class StrokeWidthWithoutStrokeTest {

  private fun rects(json: String): List<RectNode> {
    val compiled = SpecCompiler().compileJson(json)
    val out = mutableListOf<RectNode>()
    fun walk(node: SceneNode) {
      if (node is RectNode) out += node
      if (node is GroupNode) node.children.forEach { walk(it) }
    }
    compiled.scene?.root?.let { walk(it) }
    return out
  }

  private fun spec(encode: String) =
    """
    {"width": 60, "height": 60, "padding": 0, "autosize": "none",
     "data": [{"name": "t", "values": [{"v": 1}]}],
     "marks": [{"type": "rect", "from": {"data": "t"},
                "encode": {"enter": {"x": {"value": 5}, "y": {"value": 5},
                                     "width": {"value": 30}, "height": {"value": 20},
                                     $encode}}}]}
    """
      .trimIndent()

  @Test
  fun `a width with no colour leaves the node with no stroke`() {
    val rect = rects(spec(""""fill": {"value": "#cccccc"}, "strokeWidth": {"value": 4}""")).single()
    assertNull(
      rect.stroke,
      "a mark encoding strokeWidth and no stroke kept a stroke, which a renderer cannot paint",
    )
  }

  /** A width **with** a colour is kept in full, which is what says the rule is about the colour. */
  @Test
  fun `a width with a colour is kept`() {
    val rect =
      rects(
          spec(
            """"fill": {"value": "#cccccc"}, "stroke": {"value": "#333333"},
               "strokeWidth": {"value": 4}"""
          )
        )
        .single()
    assertEquals(4.0, rect.stroke?.width)
  }

  /**
   * A colour with **no** width still strokes, at Vega's default of one.
   *
   * The other direction, and the one that would be wrong to drop: `stroke` alone is an ordinary
   * outline and nothing about this difference touches it.
   */
  @Test
  fun `a colour with no width strokes at the default width`() {
    val rect =
      rects(spec(""""fill": {"value": "#cccccc"}, "stroke": {"value": "#333333"}""")).single()
    assertEquals(1.0, rect.stroke?.width)
  }

  /**
   * A width of **zero** with a colour also leaves no stroke, because nothing is painted.
   *
   * Included because it is the case that could plausibly have been treated the other way, and
   * because it says the rule is "would this paint anything" rather than "was a colour written
   * down".
   */
  @Test
  fun `a zero width leaves nothing to paint`() {
    val rect =
      rects(
          spec(
            """"fill": {"value": "#cccccc"}, "stroke": {"value": "#333333"},
               "strokeWidth": {"value": 0}"""
          )
        )
        .single()
    assertNull(rect.stroke?.takeIf { it.width > 0.0 })
  }
}
