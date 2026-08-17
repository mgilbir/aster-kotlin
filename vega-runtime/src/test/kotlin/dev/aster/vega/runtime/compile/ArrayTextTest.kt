package dev.aster.vega.runtime.compile

import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.TextNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `text` channel whose value is an **array** is a list of lines.
 *
 * `asString()` joins an array with commas, which is right for every other channel and wrong for
 * this one: a two-element array came out as `first,second` on a single line, with bounds wrong in
 * both directions, so a chart sized around that label was laid out around the wrong rectangle. No
 * fixture used an array here, which is why it survived — the `text-array-lines` fixture does now,
 * and compares against upstream.
 *
 * These cover the rules a fixture cannot see as clearly: the single-element collapse, and
 * `lineBreak` surviving on the item while being ignored.
 */
class ArrayTextTest {

  private fun textNodes(json: String): List<TextNode> {
    val compiled =
      SpecCompiler(textEngine = MetricTextEngine(), loader = DenyLoader).compileJson(json)
    val found = mutableListOf<TextNode>()
    fun walk(node: SceneNode) {
      if (node is TextNode) found += node
      if (node is GroupNode) node.children.forEach(::walk)
    }
    compiled.scene?.let { walk(it.root) }
    return found
  }

  private fun spec(text: String, extra: String = "") =
    """
    {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 200, "height": 100, "padding": 0,
     "marks": [{"type": "text", "encode": {"enter": {
       "x": {"value": 10}, "y": {"value": 20},
       "text": {"value": $text}$extra,
       "fill": {"value": "black"}}}}]}
    """

  @Test
  fun `an array of several elements is one line each`() {
    val node = textNodes(spec("""["first", "second", "third"]""")).single()

    assertEquals(listOf("first", "second", "third"), node.layout.run.lines)
    assertEquals(3, node.layout.metrics.lineCount)
    assertEquals(listOf("first", "second", "third"), node.layout.lines.map { it.text })
    // The box is three lines tall, which is the half that reaches chart layout: a label measured as
    // one
    // line makes every chart sized around it the wrong size.
    assertTrue(node.layout.metrics.height > node.layout.metrics.lineHeight * 2)
  }

  @Test
  fun `an array of one element is a plain label`() {
    // Upstream's `lineArray` collapses it, so this is a single-line label rather than a one-line
    // multi-line one — and `lines` stays null, which is what says so.
    val node = textNodes(spec("""["only"]""")).single()

    assertNull(node.layout.run.lines)
    assertEquals("only", node.layout.run.text)
    assertEquals(1, node.layout.metrics.lineCount)
  }

  @Test
  fun `lineBreak is recorded but ignored when the text is an array`() {
    // Upstream keeps `lineBreak` on the item and does not apply it: its condition is
    // `item.lineBreak && !isArray(item.text)`. Clearing it instead rendered correctly and recorded
    // a scene
    // upstream does not produce, which the differential fixture caught.
    val node =
      textNodes(spec("""["kept", "apart"]""", """, "lineBreak": {"value": "/"}""")).single()

    assertEquals(
      "/",
      node.layout.run.lineBreak,
      "the item still carries what the specification wrote",
    )
    assertEquals(listOf("kept", "apart"), node.layout.lines.map { it.text })
  }

  @Test
  fun `lineBreak still splits an ordinary string`() {
    val node =
      textNodes(spec(""""split/on/slashes"""", """, "lineBreak": {"value": "/"}""")).single()
    assertEquals(listOf("split", "on", "slashes"), node.layout.lines.map { it.text })
  }

  @Test
  fun `each line of an array is trimmed`() {
    // `textValue` trims every line upstream; this engine used to trim only the joined whole.
    val node = textNodes(spec("""["  padded  ", " also "]""")).single()
    assertEquals(listOf("padded", "also"), node.layout.lines.map { it.text })
  }

  @Test
  fun `a number in an array is read as text`() {
    val node = textNodes(spec("""["count", 42]""")).single()
    assertEquals(listOf("count", "42"), node.layout.lines.map { it.text })
  }
}
