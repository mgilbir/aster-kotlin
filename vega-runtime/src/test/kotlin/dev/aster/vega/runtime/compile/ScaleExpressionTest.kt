package dev.aster.vega.runtime.compile

import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.TextNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `scale()` and `invert()` inside an expression.
 *
 * The pair is how a specification positions something *between* two scaled values — a label at the
 * midpoint of a bin, a rule at the middle of a band — which no encoding channel can express, since
 * a channel scales one field and stops.
 */
class ScaleExpressionTest {

  private fun compile(expression: String, extra: String = "") =
    SpecCompiler()
      .compileJson(
        """
        {
          "width": 200, "height": 100,
          "data": [{"name": "t", "values": [{"v": 25}]}],
          "scales": [
            {"name": "x", "type": "linear", "domain": [0, 100], "range": [0, 200]},
            {"name": "c", "type": "ordinal", "domain": ["a", "b"], "range": ["red", "blue"]}
          ],
          "marks": [{
            "type": "text", "from": {"data": "t"},
            "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                 "text": {"signal": "$expression"}$extra}}
          }]
        }
        """
          .trimIndent()
      )

  private fun textOf(node: SceneNode): String {
    val out = mutableListOf<String>()
    fun walk(n: SceneNode) {
      when (n) {
        is TextNode -> out += n.layout.lines.joinToString("") { it.text }
        is GroupNode -> n.children.forEach { walk(it) }
        else -> Unit
      }
    }
    walk(node)
    return out.joinToString(",")
  }

  @Test
  fun `scale applies a named scale to a value`() {
    val compiled = compile("scale('x', datum.v)")
    assertEquals(emptyList<String>(), compiled.diagnostics.map { it.message })
    assertEquals("50", textOf(compiled.scene!!.root))
  }

  /** The reason it exists: a position no single channel can name. */
  @Test
  fun `scale can position something between two scaled values`() {
    assertEquals("110", textOf(compile("(scale('x', 30) + scale('x', 80)) / 2").scene!!.root))
  }

  @Test
  fun `invert reads a continuous scale backwards`() {
    assertEquals("25", textOf(compile("invert('x', 50)").scene!!.root))
  }

  /** A discrete scale maps many positions to one value, so an inverse would have to pick. */
  @Test
  fun `invert on a discrete scale is reported`() {
    val compiled = compile("invert('c', 1)")
    assertTrue(
      compiled.diagnostics.any { it.message.contains("cannot be inverted") },
      compiled.diagnostics.toString(),
    )
  }

  @Test
  fun `a scale that does not exist is reported rather than yielding null`() {
    val compiled = compile("scale('nope', 1)")
    assertTrue(
      compiled.diagnostics.any { it.message.contains("does not define") },
      compiled.diagnostics.toString(),
    )
  }

  /**
   * Upstream's third argument selects a scale from another group's scope. There is no equivalent
   * here, and guessing which scope was meant would put marks somewhere plausible and wrong.
   */
  @Test
  fun `a group argument is reported`() {
    val compiled = compile("scale('x', datum.v, 'other')")
    assertTrue(
      compiled.diagnostics.any { it.message.contains("another scope") },
      compiled.diagnostics.toString(),
    )
  }

  /**
   * A signal's own `update` cannot call `scale()`, because scales are built *from* signals — a
   * scale's domain or range may be signal-valued, so they cannot exist yet. Upstream orders it the
   * same way; this reports rather than resolving to null.
   */
  @Test
  fun `a signal can reach a scale that waits on nothing`() {
    val compiled =
      SpecCompiler()
        .compileJson(
          """
          {
            "width": 200, "height": 100,
            "signals": [{"name": "mid", "update": "scale('x', 50)"}],
            "data": [{"name": "t", "values": [{"v": 1}]}],
            "scales": [{"name": "x", "type": "linear", "domain": [0, 100], "range": [0, 200]}],
            "marks": [{"type": "text", "from": {"data": "t"},
                       "encode": {"enter": {"text": {"signal": "mid"}}}}]
          }
          """
            .trimIndent()
        )
    // `x` has a literal domain and a literal range, so it is built before the signals and there is
    // nothing circular about reading it. Vega's dot plot does exactly this to turn a step in data
    // units into a size in pixels.
    assertEquals(VegaValue.Num(100.0), compiled.signals["mid"])
    assertTrue(
      compiled.diagnostics.none { it.message.contains("defines but has not built yet") },
      compiled.diagnostics.toString(),
    )
  }

  /**
   * A signal may read a scale that itself waits on a *different* signal.
   *
   * There is an order that works — `top`, then `x`, then `mid` — and finding it is what ordering
   * the three kinds together buys. This was refused while scales were built in one phase after the
   * signals: the whole phase came too late for `mid` and too early for nothing.
   *
   * Verified against upstream, which reports `mid` as 100 and draws that as the text.
   */
  @Test
  fun `a signal can reach a scale that waits on another signal`() {
    val compiled =
      SpecCompiler()
        .compileJson(
          """
          {
            "width": 200, "height": 100,
            "signals": [
              {"name": "top", "value": 100},
              {"name": "mid", "update": "scale('x', 50)"}
            ],
            "data": [{"name": "t", "values": [{"v": 1}]}],
            "scales": [{"name": "x", "type": "linear",
                        "domain": {"signal": "[0, top]"}, "range": [0, 200]}],
            "marks": [{"type": "text", "from": {"data": "t"},
                       "encode": {"enter": {"text": {"signal": "mid"}}}}]
          }
          """
            .trimIndent()
        )
    assertEquals(VegaValue.Num(100.0), compiled.signals["mid"])
    assertTrue(
      compiled.diagnostics.none { it.message.contains("has not built yet") },
      compiled.diagnostics.toString(),
    )
  }

  /**
   * The other half of that rule, and the half that matters to real charts: a *group's* signals are
   * resolved long after the chart's scales are built, so one of them may read those.
   *
   * This is how a trellis cell takes its height from one band of the outer scale, which two of
   * Vega's own examples do. Refusing it made the cells collapse to nothing.
   */
  @Test
  fun `a group's signal can reach an enclosing scale`() {
    val compiled =
      SpecCompiler()
        .compileJson(
          """
          {
            "width": 200, "height": 120,
            "data": [{"name": "t", "values": [{"cat": "a"}, {"cat": "b"}, {"cat": "c"}]}],
            "scales": [{"name": "yscale", "type": "band",
                        "domain": {"data": "t", "field": "cat"}, "range": "height"}],
            "marks": [{
              "type": "group", "from": {"data": "t"},
              "signals": [{"name": "height", "update": "bandwidth('yscale')"}],
              "encode": {"enter": {"y": {"scale": "yscale", "field": "cat"}}},
              "marks": []
            }]
          }
          """
            .trimIndent()
        )
    assertTrue(
      compiled.diagnostics.none { it.message.contains("bandwidth()") },
      compiled.diagnostics.toString(),
    )
  }

  /** A group's own scales are built from its signals, so those are still out of reach. */
  @Test
  fun `a group's signal cannot reach a scale the group itself declares`() {
    val compiled =
      SpecCompiler()
        .compileJson(
          """
          {
            "width": 200, "height": 120,
            "data": [{"name": "t", "values": [{"cat": "a"}, {"cat": "b"}]}],
            "marks": [{
              "type": "group", "from": {"data": "t"},
              "signals": [{"name": "step", "update": "bandwidth('inner')"}],
              "scales": [{"name": "inner", "type": "band",
                          "domain": [1, 2], "range": [0, 50]}],
              "marks": []
            }]
          }
          """
            .trimIndent()
        )
    assertTrue(
      compiled.diagnostics.any { it.message.contains("defines but has not built yet") },
      compiled.diagnostics.toString(),
    )
  }
}
