package dev.aster.vega.runtime.compile

import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.SceneNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The accessibility channels a specification can set on a mark.
 *
 * These are Vega's own mechanism and were missed the first time round: the caption wording was
 * ported from upstream's `aria.js` but only its two guide functions were read, and the rest of that
 * file is the per-mark model — `description`, `aria`, `ariaRole`, `ariaRoleDescription`.
 *
 * One difference from upstream is deliberate and is pinned below. Upstream labels an individual
 * mark **only** when `description` is given; with none, an item carries no label at all. That suits
 * a pointer, where a reader meets the chart as a whole. It does not suit exploring by touch, where
 * landing on an unlabelled mark announces nothing — so a label is derived from the mark's scaled
 * fields when the specification offers none.
 */
class AriaChannelsTest {

  private fun compile(markProps: String = "", enter: String) =
    SpecCompiler()
      .compileJson(
        """
        {
          "width": 100, "height": 50, "padding": 0,
          "data": [{"name": "t", "values": [{"c": "a", "v": 3}]}],
          "scales": [
            {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
             "range": "width"},
            {"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"},
             "range": "height"}
          ],
          "marks": [{
            "type": "rect", "from": {"data": "t"}$markProps,
            "encode": {"enter": {
              "x": {"scale": "x", "field": "c"},
              "width": {"scale": "x", "band": 1},
              "y": {"scale": "y", "field": "v"},
              "y2": {"scale": "y", "value": 0}$enter
            }}
          }]
        }
        """
          .trimIndent()
      )

  private fun markDescriptors(scene: SceneNode): List<Pair<String, String?>> {
    val out = mutableListOf<Pair<String, String?>>()
    fun walk(node: SceneNode) {
      if (node.metadata.role == "mark") {
        node.metadata.accessibility?.let { out += it.label to it.role }
      }
      if (node is GroupNode) node.children.forEach { walk(it) }
    }
    walk(scene)
    return out
  }

  /** The specification's own words beat anything derived from the channels. */
  @Test
  fun `a description channel becomes the label`() {
    val compiled =
      compile(enter = ""","description": {"signal": "datum.c + ' had ' + datum.v + ' sales'"}""")
    assertEquals(emptyList<String>(), compiled.diagnostics.map { it.message })
    assertEquals(
      listOf("a had 3 sales" to "graphics-symbol"),
      markDescriptors(compiled.scene!!.root),
    )
  }

  /** The deliberate divergence: something useful when the specification says nothing. */
  @Test
  fun `with no description a label is derived from the scaled fields`() {
    assertEquals(
      listOf("a" to "graphics-symbol"),
      markDescriptors(compile(enter = "").scene!!.root),
    )
  }

  /** `aria: false` on the mark hides every one of its items from a screen reader. */
  @Test
  fun `aria false on the mark suppresses the descriptor`() {
    val compiled = compile(markProps = ""","aria": false""", enter = "")
    assertEquals(emptyList<Pair<String, String?>>(), markDescriptors(compiled.scene!!.root))
  }

  /** And per row, so one series of a mark can be hidden while the rest is read. */
  @Test
  fun `aria false on a row suppresses just that row`() {
    val compiled = compile(enter = ""","aria": {"value": false}""")
    assertEquals(emptyList<Pair<String, String?>>(), markDescriptors(compiled.scene!!.root))
  }

  @Test
  fun `ariaRole overrides the default role`() {
    val compiled =
      compile(enter = ""","description": {"value": "a bar"}, "ariaRole": {"value": "img"}""")
    assertEquals(listOf("a bar" to "img"), markDescriptors(compiled.scene!!.root))
  }

  /** A mark with no scaled field and no description has nothing to say, and says nothing. */
  @Test
  fun `a mark with nothing to describe gets no descriptor`() {
    val compiled =
      SpecCompiler()
        .compileJson(
          """
          {
            "width": 100, "height": 50, "padding": 0,
            "data": [{"name": "t", "values": [{"c": "a"}]}],
            "marks": [{"type": "rect", "from": {"data": "t"},
              "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                   "width": {"value": 9}, "height": {"value": 9}}}}]
          }
          """
            .trimIndent()
        )
    assertNull(markDescriptors(compiled.scene!!.root).firstOrNull())
  }
}
