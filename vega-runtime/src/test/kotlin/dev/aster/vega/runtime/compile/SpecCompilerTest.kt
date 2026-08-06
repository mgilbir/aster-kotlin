package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Compiler behaviour, with an emphasis on the diagnostics.
 *
 * "Never silently ignore an unsupported operator" (PROJECT_BRIEF.md 3.3) is the discipline that
 * makes a partial implementation usable, so each unsupported construct has a test proving it
 * reports rather than degrades quietly.
 */
class SpecCompilerTest {

  private fun compile(json: String) = SpecCompiler().compileJson(json)

  private fun codes(json: String) = compile(json).diagnostics.map { it.code }

  private val minimalBar =
    """
    {
      "width": 100, "height": 50, "padding": 0,
      "data": [{"name": "t", "values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}]}],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
        {"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"}, "range": "height"}
      ],
      "marks": [{
        "type": "rect", "name": "bars", "from": {"data": "t"},
        "encode": {"enter": {
          "x": {"scale": "x", "field": "c"},
          "width": {"scale": "x", "band": 1},
          "y": {"scale": "y", "field": "v"},
          "y2": {"scale": "y", "value": 0},
          "fill": {"value": "steelblue"}
        }}
      }]
    }
    """
      .trimIndent()

  @Test
  fun `a minimal bar specification compiles to rect marks`() {
    val compiled = compile(minimalBar)
    assertTrue(compiled.isUsable)
    assertTrue(
      compiled.diagnostics.none { it.severity >= DiagnosticSeverity.ERROR },
      compiled.diagnostics.toString(),
    )

    val rects = compiled.scene!!.flatten().map { it.node }.filterIsInstance<RectNode>()
    assertEquals(2, rects.size)
    // Two categories across a 100-wide range with no padding: 50 each.
    assertEquals(0.0, rects[0].x, 1e-9)
    assertEquals(50.0, rects[0].width, 1e-9)
    assertEquals(50.0, rects[1].x, 1e-9)
    // Value 1 of a [0, 2] domain over a descending 50..0 range sits at 25, extending to the
    // baseline.
    assertEquals(25.0, rects[0].y, 1e-9)
    assertEquals(25.0, rects[0].height, 1e-9)
  }

  @Test
  fun `marks carry datum metadata for interaction and accessibility`() {
    val rects = compile(minimalBar).scene!!.flatten().map { it.node }.filterIsInstance<RectNode>()
    assertEquals("bars", rects[0].metadata.markName)
    assertEquals(0, rects[0].metadata.datumIndex)
    assertTrue(rects[0].metadata.interactive)
    assertNotNull(rects[0].metadata.tooltip)
    assertEquals("a", rects[0].metadata.accessibility?.label)
  }

  @Test
  fun `an axis produces ticks, labels and a domain line`() {
    val withAxis =
      minimalBar.replace(
        "\"marks\":",
        "\"axes\": [{\"orient\": \"bottom\", \"scale\": \"x\"}], \"marks\":",
      )
    val nodes = compile(withAxis).scene!!.flatten().map { it.node }
    assertEquals(2, nodes.filterIsInstance<RuleNode>().count { it.metadata.role == "axis-tick" })
    assertEquals(2, nodes.filterIsInstance<TextNode>().count { it.metadata.role == "axis-label" })
    assertEquals(1, nodes.filterIsInstance<RuleNode>().count { it.metadata.role == "axis-domain" })
  }

  @Test
  fun `grid lines are generated only when requested`() {
    val withGrid =
      minimalBar.replace(
        "\"marks\":",
        "\"axes\": [{\"orient\": \"left\", \"scale\": \"y\", \"grid\": true}], \"marks\":",
      )
    val grids =
      compile(withGrid)
        .scene!!
        .flatten()
        .map { it.node }
        .filterIsInstance<RuleNode>()
        .filter {
          it.metadata.role == "axis-grid"
        }
    assertTrue(grids.isNotEmpty())
    // A grid line spans the plot width.
    assertEquals(100.0, grids.first().x2 - grids.first().x1, 1e-9)
  }

  // ---- diagnostics ----------------------------------------------------------

  @Test
  fun `data transforms are reported, not silently skipped`() {
    val withTransform =
      minimalBar.replace(
        """"values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}]""",
        """"values": [{"c": "a", "v": 1}], "transform": [{"type": "filter", "expr": "datum.v > 0"}]""",
      )
    val diagnostic =
      compile(withTransform).diagnostics.first {
        it.code == DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED
      }
    assertTrue(diagnostic.message.contains("filter"), diagnostic.message)
  }

  @Test
  fun `a signal expression in an encoding is evaluated, not rejected`() {
    // Signals used to be rejected here; they now compile. SignalCompileTest covers the behaviour,
    // so
    // this only guards against a regression to reporting them as unsupported.
    val withSignal =
      minimalBar.replace("""{"value": "steelblue"}""", """{"signal": "'steelblue'"}""")
    val compiled = compile(withSignal)
    assertTrue(
      compiled.diagnostics.none { it.code == DiagnosticCodes.EXPRESSION_UNSUPPORTED_FUNCTION },
      compiled.diagnostics.toString(),
    )
  }

  @Test
  fun `a malformed encoding expression is reported`() {
    val broken = minimalBar.replace("""{"value": "steelblue"}""", """{"signal": "1 +"}""")
    assertTrue(codes(broken).contains(DiagnosticCodes.EXPRESSION_PARSE_ERROR))
  }

  @Test
  fun `an unsupported scale type is reported and dependent marks report too`() {
    val withLog = minimalBar.replace("\"type\": \"linear\"", "\"type\": \"log\"")
    val diagnostics = compile(withLog).diagnostics
    assertTrue(diagnostics.any { it.code == DiagnosticCodes.SCALE_UNSUPPORTED_TYPE })
    // The mark that referenced it must complain as well, rather than positioning at the origin.
    assertTrue(diagnostics.count { it.code == DiagnosticCodes.SCALE_UNSUPPORTED_TYPE } > 1)
  }

  @Test
  fun `an unimplemented mark type is reported and contributes nothing`() {
    val withArc = minimalBar.replace("\"type\": \"rect\"", "\"type\": \"arc\"")
    val compiled = compile(withArc)
    assertTrue(
      compiled.diagnostics.any {
        it.code == DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED && it.message.contains("arc")
      }
    )
    assertTrue(compiled.scene!!.flatten().none { it.node is RectNode })
  }

  @Test
  fun `an unknown mark type is reported`() {
    val bogus = minimalBar.replace("\"type\": \"rect\"", "\"type\": \"hexbin\"")
    assertTrue(codes(bogus).contains(DiagnosticCodes.PARSE_UNKNOWN_MARK))
  }

  @Test
  fun `legends and titles at the top level are reported`() {
    val extras =
      minimalBar.replace(
        "\"marks\":",
        "\"legends\": [{\"fill\": \"x\"}], \"title\": \"A chart\", \"marks\":",
      )
    val messages = compile(extras).diagnostics.map { it.message }
    assertTrue(messages.any { it.contains("legends") }, messages.toString())
    assertTrue(messages.any { it.contains("title") }, messages.toString())
    // Signals are no longer in that list; they compile.
    assertTrue(messages.none { it.contains("Signals require") }, messages.toString())
  }

  @Test
  fun `a url data source is reported and yields no data`() {
    val remote =
      minimalBar.replace(
        """"values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}]""",
        """"url": "data/table.json"""",
      )
    val compiled = compile(remote)
    assertTrue(compiled.diagnostics.any { it.code == DiagnosticCodes.PARSE_UNKNOWN_PROPERTY })
    assertTrue(compiled.scene!!.flatten().none { it.node is RectNode })
  }

  @Test
  fun `a mark referring to an unknown dataset is reported`() {
    val broken = minimalBar.replace("""{"data": "t"}""", """{"data": "nope"}""")
    assertTrue(
      compile(broken).diagnostics.any {
        it.code == DiagnosticCodes.PARSE_UNKNOWN_PROPERTY && it.message.contains("nope")
      }
    )
  }

  @Test
  fun `autosize fit falls back to pad and says so`() {
    val fit = minimalBar.replace("\"padding\": 0,", "\"padding\": 0, \"autosize\": \"fit\",")
    val compiled = compile(fit)
    assertTrue(compiled.isUsable)
    assertTrue(compiled.diagnostics.any { it.message.contains("autosize 'fit'") })
  }

  @Test
  fun `a missing width and height is reported and defaults applied`() {
    val sizeless = minimalBar.replace("\"width\": 100, \"height\": 50,", "")
    val compiled = compile(sizeless)
    assertTrue(compiled.diagnostics.any { it.code == DiagnosticCodes.PARSE_MISSING_PROPERTY })
    assertEquals(SpecCompiler.DEFAULT_WIDTH, compiled.scene!!.width, 1e-9)
  }

  @Test
  fun `invalid json produces a fatal diagnostic and no scene`() {
    val compiled = compile("{not json")
    assertFalse(compiled.isUsable)
    assertEquals(DiagnosticCodes.PARSE_INVALID_JSON, compiled.diagnostics.first().code)
  }

  @Test
  fun `an empty specification still produces a scene`() {
    val compiled = compile("""{"width": 10, "height": 10}""")
    assertTrue(compiled.isUsable)
    assertEquals(0, compiled.scene!!.flatten().count { it.node is RectNode })
  }

  @Test
  fun `padding shorthand and per-side padding both work`() {
    val uniform = compile(minimalBar.replace("\"padding\": 0", "\"padding\": 7"))
    val perSide =
      compile(
        minimalBar.replace(
          "\"padding\": 0",
          "\"padding\": {\"left\": 7, \"top\": 7, \"right\": 7, \"bottom\": 7}",
        )
      )
    val uniformScene = requireNotNull(uniform.scene)
    val perSideScene = requireNotNull(perSide.scene)
    assertEquals(uniformScene.width, perSideScene.width, 1e-9)
    assertEquals(uniformScene.height, perSideScene.height, 1e-9)
  }
}
