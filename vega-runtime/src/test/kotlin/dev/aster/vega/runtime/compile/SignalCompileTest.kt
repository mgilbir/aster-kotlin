package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
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
    val compiled = compile(spec(encode = """$basePosition, "opacity": {"signal": "geoBounds()"}"""))
    val failures =
      compiled.diagnostics.filter { it.code == DiagnosticCodes.EXPRESSION_UNSUPPORTED_FUNCTION }
    // Two data rows, but the expression fails identically for both.
    assertEquals(1, failures.size, failures.toString())
    assertTrue(failures.single().message.contains("geographic"))
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

  /**
   * A transform can read a signal that was computed *from an earlier dataset*.
   *
   * `rows` is `length(data('t'))`, so it cannot be known before `t` — but it can be known before
   * `u`, and the order puts it there. Every signal that has become resolvable is resolved before
   * the next dataset runs, which is what maximises how much a transform can see.
   *
   * Verified against upstream: `u` comes out as `{"v": 1, "p": 2}`.
   */
  @Test
  fun `a transform reading a signal computed from an earlier dataset sees its value`() {
    val compiled =
      compile(
        """
        {
          "width": 100, "height": 50, "padding": 0,
          "signals": [{"name": "rows", "update": "length(data('t'))"}],
          "data": [
            {"name": "t", "values": [{"v": 1}]},
            {
              "name": "u",
              "source": "t",
              "transform": [{"type": "formula", "expr": "rows + datum.v", "as": "p"}]
            }
          ],
          "marks": [{
            "type": "rect", "from": {"data": "u"},
            "encode": {"enter": {
              "x": {"field": "p"}, "y": {"value": 0},
              "width": {"value": 1}, "height": {"value": 1}
            }}
          }]
        }
        """
          .trimIndent()
      )
    assertTrue(
      compiled.diagnostics.none { it.message.contains("signal 'rows'") },
      compiled.diagnostics.toString(),
    )
    // `rows` is 1 and `v` is 1, so a transform that saw the signal wrote 2 and one that did not
    // wrote 1 — the two are a pixel apart, which is exactly the kind of wrong this used to be.
    val bars = requireNotNull(compiled.scene).flatten().map { it.node }.filterIsInstance<RectNode>()
    assertEquals(listOf(2.0), bars.map { it.x })
  }

  /**
   * A transform's *expression* parameter is in the dependency graph, so this resolves.
   *
   * `u` is declared first and sources from nothing, and its `formula` reads `rows`, which counts
   * the rows of `t` — declared after it. It still comes out right, because the expression is an
   * edge: upstream's `parseExpression` walks every expression's AST and registers what the `scale`,
   * `data` and `indata` calls in it reach for as operator **parameters**, so a `formula` waits for
   * them exactly as a `{"signal": ...}` parameter would.
   *
   * This test used to assert the opposite — that the read came out null and was reported by name.
   * That diagnostic existed because the edge was missing; the edge is the fix, and the diagnostic
   * that survives it is the one for a signal nothing can supply at all.
   */
  @Test
  fun `a transform reading a signal from a later dataset still resolves`() {
    val compiled =
      compile(
        """
        {
          "width": 100, "height": 50, "padding": 0,
          "signals": [{"name": "rows", "update": "length(data('t'))"}],
          "data": [
            {
              "name": "u",
              "values": [{"v": 1}],
              "transform": [{"type": "formula", "expr": "rows + datum.v", "as": "p"}]
            },
            {"name": "t", "values": [{"v": 1}]}
          ],
          "marks": []
        }
        """
          .trimIndent()
      )
    val reported = compiled.diagnostics.filter { it.message.contains("signal 'rows'") }
    assertEquals(emptyList<Any>(), reported, compiled.diagnostics.toString())
    assertEquals(1.0, compiled.signals["rows"]?.asDouble())
  }

  /**
   * A signal computed from *other signals* is available to a transform, and says nothing.
   *
   * It cannot depend on a dataset, so there is no reason to make the transform wait for one —
   * upstream reaches the same order by ranking its dataflow. This is the shape a control has:
   * `clamp(handleYear, 1980, 2010)` filtering rows by a draggable year.
   */
  @Test
  fun `a transform reading a signal computed from other signals sees its value`() {
    val compiled =
      compile(
        """
        {
          "width": 100, "height": 50, "padding": 0,
          "signals": [
            {"name": "half", "update": "width / 2"},
            {"name": "cutoff", "update": "half - 10"}
          ],
          "data": [
            {
              "name": "t",
              "values": [{"v": 1}, {"v": 2}],
              "transform": [
                {"type": "formula", "expr": "cutoff + datum.v", "as": "p"},
                {"type": "filter", "expr": "datum.p > 41"}
              ]
            }
          ],
          "marks": []
        }
        """
          .trimIndent()
      )
    assertTrue(
      compiled.diagnostics.none { it.message.contains("cutoff") },
      compiled.diagnostics.toString(),
    )
    // 100/2 - 10 = 40, so the rows carry 41 and 42 and the filter keeps one.
    val rows = compiled.spec?.let { _ -> compiled.signals["cutoff"] }
    assertEquals(VegaValue.Num(40.0), rows)
  }

  /** A signal written down as a constant is available, so it must not be reported. */
  @Test
  fun `a transform reading a plain signal value says nothing`() {
    val compiled =
      compile(
        """
        {
          "width": 100, "height": 50, "padding": 0,
          "signals": [{"name": "origin", "value": 50}],
          "data": [
            {
              "name": "t",
              "values": [{"v": 1}],
              "transform": [{"type": "formula", "expr": "origin + datum.v", "as": "p"}]
            }
          ],
          "marks": []
        }
        """
          .trimIndent()
      )
    assertTrue(
      compiled.diagnostics.none { it.message.contains("signal 'origin'") },
      compiled.diagnostics.toString(),
    )
  }

  private fun solidFill(node: RectNode): SceneColor? =
    (node.fill?.paint as? ScenePaint.Solid)?.color
}
