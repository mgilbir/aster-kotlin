package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Signals and expression-valued encodings, end to end through the compiler.
 *
 * Resolution rules were verified against upstream Vega and are stated where they are not obvious.
 */
class SignalCompileTest {

  private fun compile(json: String) = SpecCompiler().compileJson(json)

  private fun rects(json: String): List<RectNode> {
    val compiled = compile(json)
    assertTrue(
      compiled.diagnostics.none { it.severity >= DiagnosticSeverity.ERROR },
      compiled.diagnostics.toString(),
    )
    return requireNotNull(compiled.scene).flatten().map { it.node }.filterIsInstance<RectNode>()
  }

  private fun spec(signals: String = "", encode: String): String =
    """
    {
      "width": 100, "height": 50, "padding": 0,
      ${if (signals.isEmpty()) "" else "\"signals\": [$signals],"}
      "data": [{"name": "t", "values": [{"c": "a", "v": 1}, {"c": "b", "v": 4}]}],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
        {"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"}, "range": "height"}
      ],
      "marks": [{
        "type": "rect", "name": "bars", "from": {"data": "t"},
        "encode": {"enter": {$encode}}
      }]
    }
    """
      .trimIndent()

  private val basePosition =
    """
    "x": {"scale": "x", "field": "c"},
    "width": {"scale": "x", "band": 1},
    "y": {"scale": "y", "field": "v"},
    "y2": {"scale": "y", "value": 0}
    """
      .trimIndent()

  // ---- signals --------------------------------------------------------------

  @Test
  fun `a signal value can drive an encoding channel`() {
    val bars =
      rects(
        spec(
          signals = """{"name": "barOpacity", "value": 0.25}""",
          encode = """$basePosition, "opacity": {"signal": "barOpacity"}""",
        )
      )
    assertEquals(0.25, bars[0].opacity, 1e-9)
  }

  @Test
  fun `update wins over init, and init over value`() {
    // Verified against upstream: {value: 5, update: "99"} resolves to 99.
    val both =
      rects(
        spec(
          signals = """{"name": "o", "value": 0.5, "update": "0.125"}""",
          encode = """$basePosition, "opacity": {"signal": "o"}""",
        )
      )
    assertEquals(0.125, both[0].opacity, 1e-9)

    val initOnly =
      rects(
        spec(
          signals = """{"name": "o", "value": 0.5, "init": "0.25"}""",
          encode = """$basePosition, "opacity": {"signal": "o"}""",
        )
      )
    assertEquals(0.25, initOnly[0].opacity, 1e-9)
  }

  @Test
  fun `signals resolve in dependency order regardless of declaration order`() {
    val bars =
      rects(
        spec(
          // `b` is declared before the `a` it depends on.
          signals = """{"name": "b", "update": "a / 8"}, {"name": "a", "value": 2}""",
          encode = """$basePosition, "opacity": {"signal": "b"}""",
        )
      )
    assertEquals(0.25, bars[0].opacity, 1e-9)
  }

  @Test
  fun `width and height are implicit signals`() {
    val bars =
      rects(
        spec(
          signals = """{"name": "half", "update": "width / 200"}""",
          encode = """$basePosition, "opacity": {"signal": "half"}""",
        )
      )
    assertEquals(0.5, bars[0].opacity, 1e-9)
  }

  @Test
  fun `an expression can read the datum`() {
    val bars = rects(spec(encode = """$basePosition, "opacity": {"signal": "datum.v / 4"}"""))
    assertEquals(0.25, bars[0].opacity, 1e-9)
    assertEquals(1.0, bars[1].opacity, 1e-9)
  }

  @Test
  fun `an expression can position a mark`() {
    val bars =
      rects(
        spec(
          encode =
            """
            "x": {"signal": "datum.v * 10"},
            "width": {"value": 5},
            "y": {"value": 0},
            "height": {"value": 10}
            """
              .trimIndent()
        )
      )
    assertEquals(10.0, bars[0].x, 1e-9)
    assertEquals(40.0, bars[1].x, 1e-9)
  }

  @Test
  fun `a signal cycle is reported by name and does not hang`() {
    val compiled =
      compile(
        spec(
          signals = """{"name": "a", "update": "b"}, {"name": "b", "update": "a"}""",
          encode = basePosition,
        )
      )
    val cycle = compiled.diagnostics.first { it.code == DiagnosticCodes.SIGNAL_CYCLE }
    assertTrue(cycle.message.contains("a"), cycle.message)
    assertTrue(cycle.message.contains("b"), cycle.message)
    // The chart still compiles; only the cyclic signals are unusable.
    assertTrue(compiled.isUsable)
  }

  @Test
  fun `a malformed signal expression is reported once with its signal name`() {
    val compiled =
      compile(spec(signals = """{"name": "bad", "update": "1 +"}""", encode = basePosition))
    val diagnostic =
      compiled.diagnostics.first { it.code == DiagnosticCodes.EXPRESSION_PARSE_ERROR }
    assertEquals("bad", diagnostic.operator)
  }

  /**
   * A handler that parses is not a limitation and no longer warns.
   *
   * It did while nothing dispatched them. `VegaChartController` does now, and the parser cannot
   * know whether a host wired one up — so warning unconditionally cried wolf on every working
   * chart, which is the fastest way to teach a reader to ignore diagnostics.
   */
  @Test
  fun `a handler that parses cleanly says nothing`() {
    val compiled =
      compile(
        spec(
          signals =
            """{"name": "hovered", "value": null,
                "on": [{"events": "rect:mouseover[event.shiftKey]{100}", "update": "1"}]}""",
          encode = basePosition,
        )
      )
    assertEquals(emptyList<String>(), compiled.diagnostics.map { it.message })
  }

  /** A selector that cannot be read is an error, not a handler that silently never fires. */
  @Test
  fun `an unreadable event selector is reported`() {
    val compiled =
      compile(
        spec(
          signals = """{"name": "s", "value": 0, "on": [{"events": "click[", "update": "1"}]}""",
          encode = basePosition,
        )
      )
    assertTrue(
      compiled.diagnostics.any { it.message.contains("Unmatched left bracket") },
      compiled.diagnostics.toString(),
    )
  }

  @Test
  fun `an unsupported function inside an encoding is reported once, not per datum`() {
    val compiled = compile(spec(encode = """$basePosition, "opacity": {"signal": "random()"}"""))
    val failures =
      compiled.diagnostics.filter { it.code == DiagnosticCodes.EXPRESSION_UNSUPPORTED_FUNCTION }
    // Two data rows, but the expression fails identically for both.
    assertEquals(1, failures.size, failures.toString())
    assertTrue(failures.single().message.contains("reproducible"))
  }

  // ---- conditional production rules -----------------------------------------

  @Test
  fun `a conditional rule picks the first passing test`() {
    val bars =
      rects(
        spec(
          encode =
            """
            $basePosition,
            "fill": [
              {"test": "datum.v > 2", "value": "red"},
              {"value": "blue"}
            ]
            """
              .trimIndent()
        )
      )
    assertEquals(SceneColor.parse("blue"), solidFill(bars[0]))
    assertEquals(SceneColor.parse("red"), solidFill(bars[1]))
  }

  @Test
  fun `a conditional rule with no passing test and no default leaves the channel unset`() {
    val bars =
      rects(
        spec(
          encode =
            """
            $basePosition,
            "fill": [{"test": "datum.v > 100", "value": "red"}]
            """
              .trimIndent()
        )
      )
    assertNull(bars[0].fill)
  }

  @Test
  fun `a conditional rule can reference a signal`() {
    val bars =
      rects(
        spec(
          signals = """{"name": "threshold", "value": 3}""",
          encode =
            """
            $basePosition,
            "fill": [
              {"test": "datum.v > threshold", "value": "red"},
              {"value": "blue"}
            ]
            """
              .trimIndent(),
        )
      )
    assertEquals(SceneColor.parse("blue"), solidFill(bars[0]))
    assertEquals(SceneColor.parse("red"), solidFill(bars[1]))
  }

  @Test
  fun `a conditional rule can select a scaled production`() {
    val bars =
      rects(
        spec(
          encode =
            """
            "x": {"scale": "x", "field": "c"},
            "width": {"scale": "x", "band": 1},
            "y": [
              {"test": "datum.v > 2", "scale": "y", "field": "v"},
              {"value": 0}
            ],
            "y2": {"scale": "y", "value": 0}
            """
              .trimIndent()
        )
      )
    // The first bar takes the default y of 0; the second takes the scaled value.
    assertEquals(0.0, bars[0].y, 1e-9)
    assertEquals(0.0, bars[1].y, 1e-9)
    assertEquals(50.0, bars[0].height, 1e-9)
  }

  @Test
  fun `signals are exposed to the compiled result for inspection`() {
    val compiled = compile(spec(signals = """{"name": "a", "value": 7}""", encode = basePosition))
    assertTrue(compiled.isUsable)
    assertEquals(VegaValue.Num(7.0), compiled.signals["a"])
    assertEquals(VegaValue.Num(100.0), compiled.signals["width"])
  }

  private fun solidFill(node: RectNode): SceneColor? =
    (node.fill?.paint as? ScenePaint.Solid)?.color
}
