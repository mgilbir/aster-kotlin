package dev.aster.vega.runtime.compile

import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.PathNode
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.transformedBounds
import dev.aster.vega.svg.SvgRenderer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `clip: true` on a mark that is **not** a group.
 *
 * It was read for bounds and never applied when drawing: `Marks.kt` emitted it, `ScopeCompiler`
 * intersected the mark's reach with the plotting area, and then every renderer drew the mark
 * unclipped — because a clip rectangle exists only on a group node and only `encodeGroup` ever set
 * one. So a value past the scale's domain was painted over the axis instead of hidden, which on a
 * measured score is a wrong picture rather than a cosmetic defect: the chart shows a number the
 * specification asked to have cut off.
 *
 * Upstream's rule is in `vega-scenegraph`: `CanvasRenderer.draw` clips the context before drawing a
 * mark that declares `clip`, `util/canvas/clip.js` clips it to `(0, 0, group.width, group.height)`
 * — the *enclosing* group's extent, not the mark's own bounds — and `bound/boundClip.js` intersects
 * the mark's bounds with the same rectangle. `test-fixtures/specs/mark-clip.vg.json` is the
 * fixture, and its differential reference is upstream's own answer for the geometry; what cannot be
 * seen in a scene comparison is whether anything clips at all, which is what this test is for.
 */
class MarkClipTest {

  private val spec =
    """
    {
      "width": 200, "height": 120, "padding": 5,
      "data": [{"name": "t", "values": [
        {"x": 0, "y": 90}, {"x": 2, "y": 130}, {"x": 4, "y": 25}
      ]}],
      "scales": [
        {"name": "x", "type": "linear", "domain": [0, 4], "range": "width", "zero": false},
        {"name": "y", "type": "linear", "domain": [0, 100], "range": "height"}
      ],
      "marks": [
        {"type": "line", "clip": true, "from": {"data": "t"}, "encode": {"enter": {
          "x": {"scale": "x", "field": "x"}, "y": {"scale": "y", "field": "y"},
          "stroke": {"value": "#4c78a8"}
        }}},
        {"type": "symbol", "clip": true, "from": {"data": "t"}, "encode": {"enter": {
          "x": {"scale": "x", "field": "x"}, "y": {"scale": "y", "field": "y"}
        }}},
        {"type": "text", "from": {"data": "t"}, "encode": {"enter": {
          "x": {"scale": "x", "field": "x"}, "y": {"scale": "y", "field": "y"},
          "text": {"field": "y"}
        }}}
      ]
    }
    """
      .trimIndent()

  private fun scene() = requireNotNull(SpecCompiler().compileJson(spec).scene) { "no scene" }

  private fun descendants(node: SceneNode): List<SceneNode> =
    listOf(node) + ((node as? GroupNode)?.children?.flatMap { descendants(it) } ?: emptyList())

  private fun clipGroups(root: SceneNode) =
    descendants(root).filterIsInstance<GroupNode>().filter { it.clip != null }

  @Test
  fun `a clipped mark is drawn under the enclosing group's extent`() {
    val groups = clipGroups(scene().root)

    assertEquals(2, groups.size, "one clip for the line and one for the symbols")
    for (group in groups) {
      assertEquals(
        RectD(0.0, 0.0, 200.0, 120.0),
        group.clip,
        "upstream clips to (0, 0, group.width, group.height), not to the mark's own bounds",
      )
      // It is the clip and nothing else: a container that painted a fill or announced itself would
      // be a mark of its own, and a reader must meet the items inside it unchanged.
      assertEquals(null, group.fill)
      assertEquals(null, group.stroke)
      assertEquals(null, group.paintRect)
      assertEquals(null, group.metadata.role)
    }

    assertEquals(
      listOf(1, 3),
      groups.map { it.children.size }.sorted(),
      "the line is one path node for the series; the symbols are one node per datum",
    )
    assertTrue(
      groups.any { group -> group.children.all { it is PathNode } },
      "the line's path is inside a clip",
    )
    assertTrue(
      groups.any { group -> group.children.all { it is SymbolNode } },
      "the symbols are inside a clip",
    )
  }

  @Test
  fun `a mark that does not ask to be clipped is not`() {
    val clipped = clipGroups(scene().root).flatMap { descendants(it) }
    val texts = descendants(scene().root).filterIsInstance<TextNode>()

    assertEquals(3, texts.size, "the text mark draws a label per row")
    assertTrue(
      clipped.filterIsInstance<TextNode>().isEmpty(),
      "only a mark declaring `clip` is clipped; the labels reach past the plotting area",
    )
  }

  /**
   * Clipping is a paint-time cut, not a filter: the item outside the domain is still in the scene,
   * still hit-testable and still announced, exactly as it is upstream. Cutting it from the data
   * would change what a reader hears as well as what they see.
   */
  @Test
  fun `an item outside the clip is still in the scene`() {
    val symbols = descendants(scene().root).filterIsInstance<SymbolNode>()
    assertEquals(3, symbols.size, "three rows, three symbols, one of them off the top")
    assertTrue(
      symbols.any { it.transformedBounds.top < 0.0 },
      "the y=130 row is above a domain that stops at 100, so its symbol sits outside the clip",
    )
  }

  /** The clip reaches the output: the SVG renderer writes it as a `clipPath` over the mark. */
  @Test
  fun `the clip is written into the SVG output`() {
    val svg = SvgRenderer().render(scene()).svg

    assertTrue(
      svg.contains(
        """<clipPath id="vc0"><rect x="0" y="0" width="200" height="120"/></clipPath>"""
      ),
      "the clip rectangle is the plotting area's:\n$svg",
    )
    assertEquals(
      2,
      Regex("""clip-path="url\(#vc0\)"""").findAll(svg).count(),
      "one reference per clipped mark, sharing the one definition:\n$svg",
    )
  }
}
