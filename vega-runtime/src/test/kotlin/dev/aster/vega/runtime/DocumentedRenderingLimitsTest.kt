package dev.aster.vega.runtime

import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.TextNode
import org.junit.jupiter.api.Assertions.assertEquals
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
}
