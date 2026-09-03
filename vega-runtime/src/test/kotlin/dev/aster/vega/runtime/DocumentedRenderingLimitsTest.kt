package dev.aster.vega.runtime

import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.AccessibilityTree
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.TextNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Three documented limits of what is drawn, each held up by what the engine actually does.
 *
 * They sit together because they are the same kind of claim — "this is not interpreted", "this is
 * not offered" — and each is the sort that quietly stops being true.
 */
class DocumentedRenderingLimitsTest {

  private fun compile(json: String) = SpecCompiler().compileJson(json)

  private fun texts(root: SceneNode): List<TextNode> {
    val out = mutableListOf<TextNode>()
    fun walk(node: SceneNode) {
      if (node is TextNode) out += node
      if (node is GroupNode) node.children.forEach { walk(it) }
    }
    walk(root)
    return out
  }

  private fun textSpec(text: String) =
    """
    {"width": 200, "height": 60, "padding": 0, "autosize": "none",
     "data": [{"name": "t", "values": [{"s": ${dev.aster.vega.model.VegaJson.write(
       dev.aster.vega.model.VegaValue.Str(text))}}]}],
     "marks": [{"type": "text", "from": {"data": "t"},
                "encode": {"enter": {"x": {"value": 5}, "y": {"value": 20},
                                     "text": {"field": "s"}}}}]}
    """
      .trimIndent()

  /**
   * **HTML labels, CSS parsing — not planned.** A label's text is the characters it was given.
   *
   * Nothing parses markup out of a text mark, so a specification whose data happens to contain
   * `<b>` gets those five characters drawn. That is the right answer — a label comes from *data*,
   * and interpreting data as markup is how an injection works — and it is worth pinning, because
   * "we render labels as rich text" is the kind of feature that arrives without anybody rereading
   * this row.
   */
  @Test
  fun `a label containing markup is drawn as characters`() {
    for (raw in
      listOf(
        "<b>bold</b>",
        "a &amp; b",
        "<script>alert(1)</script>",
        "line<br/>break",
        "<span style=\"color:red\">x</span>",
      )) {
      val drawn = texts(requireNotNull(compile(textSpec(raw)).scene).root).single()
      assertEquals(
        raw,
        drawn.text,
        "markup in a label was interpreted rather than drawn, which is both a feature this row " +
          "says is not planned and a way for data to become markup",
      )
    }
  }

  /**
   * **Domain adjustment and reset-zoom actions — planned.** No such action is offered to assistive
   * technology.
   *
   * A chart that pans and zooms has no accessible way to do either yet: the accessibility tree
   * offers activation and nothing else. Pinned because the row says `Planned`, and a planned thing
   * that quietly arrives leaves the row telling a reader it is still missing.
   */
  @Test
  fun `no accessibility action offers to adjust a domain or reset a zoom`() {
    val compiled =
      compile(
        """
        {"width": 200, "height": 100, "padding": 0, "autosize": "none",
         "data": [{"name": "t", "values": [{"c": "a", "v": 3}, {"c": "b", "v": 7}]}],
         "signals": [{"name": "zoom", "value": 1,
                      "on": [{"events": "wheel", "update": "zoom * 1.1"}]}],
         "scales": [{"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
                     "range": "width"},
                    {"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"},
                     "range": "height"}],
         "axes": [{"scale": "y", "orient": "left"}],
         "marks": [{"type": "rect", "from": {"data": "t"},
                    "encode": {"enter": {"x": {"scale": "x", "field": "c"},
                                         "width": {"scale": "x", "band": 1},
                                         "y": {"scale": "y", "field": "v"},
                                         "y2": {"value": 0}}}}]}
        """
          .trimIndent()
      )
    val elements = AccessibilityTree.elements(requireNotNull(compiled.scene))
    assertTrue(elements.isNotEmpty(), "the chart exposed nothing at all, so this proves nothing")
    val offered = elements.filter { element ->
      val said = (element.label + " " + (element.roleDescription ?: "")).lowercase()
      "reset" in said || "zoom" in said || "adjust" in said || "domain" in said
    }
    assertEquals(
      emptyList<String>(),
      offered.map { it.label },
      "an accessibility element offers a domain or zoom action, so this row is no longer `Planned`",
    )
  }
}
