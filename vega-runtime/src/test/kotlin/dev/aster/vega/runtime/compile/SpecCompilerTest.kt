package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.scene.PathNode
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
  fun `an implemented transform runs`() {
    val withTransform =
      minimalBar.replace(
        """"values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}]""",
        """"values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}], """ +
          """"transform": [{"type": "filter", "expr": "datum.v > 1"}]""",
      )
    val compiled = compile(withTransform)
    assertTrue(
      compiled.diagnostics.none { it.severity >= DiagnosticSeverity.ERROR },
      compiled.diagnostics.toString(),
    )
    // One row survived the filter, so one bar.
    assertEquals(1, requireNotNull(compiled.scene).flatten().count { it.node is RectNode })
  }

  @Test
  fun `an unimplemented transform stops the pipeline and is reported`() {
    val withTransform =
      minimalBar.replace(
        """"values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}]""",
        """"values": [{"c": "a", "v": 1}], "transform": [{"type": "geojson", "field": "v"}]""",
      )
    val diagnostic =
      compile(withTransform).diagnostics.first {
        it.code == DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED
      }
    assertTrue(diagnostic.message.contains("geojson"), diagnostic.message)
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
    // `identity` is the last scale type with no implementation. Everything else the parser accepts
    // now builds — including `quantile`, which this test used to name.
    val withIdentity = minimalBar.replace("\"type\": \"linear\"", "\"type\": \"identity\"")
    val diagnostics = compile(withIdentity).diagnostics
    assertTrue(diagnostics.any { it.code == DiagnosticCodes.SCALE_UNSUPPORTED_TYPE })
    // The mark that referenced it must complain as well, rather than positioning at the origin.
    assertTrue(diagnostics.count { it.code == DiagnosticCodes.SCALE_UNSUPPORTED_TYPE } > 1)
  }

  @Test
  fun `a log scale with a domain spanning zero is reported`() {
    val withLog =
      minimalBar
        .replace("\"type\": \"linear\"", "\"type\": \"log\"")
        .replace("""{"c": "a", "v": 1}""", """{"c": "a", "v": 0}""")
    val diagnostic =
      compile(withLog).diagnostics.first { it.code == DiagnosticCodes.SCALE_INVALID_DOMAIN }
    assertTrue(diagnostic.message.contains("zero"), diagnostic.message)
  }

  @Test
  fun `every mark type has an encoder`() {
    // `shape` was the last of the twelve without one, and it was refused on the grounds that
    // projections were out of scope. They are not: a `shape` mark draws whatever outline a
    // `geoshape` transform put on it, which is the same node a `path` mark draws.
    val types =
      listOf(
        "arc",
        "area",
        "image",
        "line",
        "path",
        "rect",
        "rule",
        "shape",
        "symbol",
        "text",
        "trail",
      )
    for (type in types) {
      val compiled = compile(minimalBar.replace("\"type\": \"rect\"", "\"type\": \"$type\""))
      assertTrue(
        compiled.diagnostics.none {
          it.code == DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED && it.message.contains("encoder")
        },
        "the '$type' mark reported no encoder",
      )
    }
  }

  @Test
  fun `an arc without a radius or a sweep draws nothing rather than a degenerate shape`() {
    val compiled =
      compile(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "data": [{"name": "t", "values": [{"a": 0}]}],
          "marks": [{"type": "arc", "from": {"data": "t"}, "encode": {"enter": {
            "x": {"value": 50}, "y": {"value": 50},
            "startAngle": {"value": 0}, "endAngle": {"value": 0},
            "outerRadius": {"value": 40}}}}]
        }
        """
          .trimIndent()
      )
    assertTrue(requireNotNull(compiled.scene).flatten().none { it.node is PathNode })
  }

  /**
   * `padAngle` and `cornerRadius` used to be reported rather than drawn. They are now d3's own
   * geometry, so the arc is a different shape from the square-cornered one and nothing is reported.
   */
  @Test
  fun `arc padding and corner rounding change the shape and report nothing`() {
    fun arcPath(extra: String): PathNode {
      val compiled =
        compile(
          """
          {
            "width": 100, "height": 100, "padding": 0,
            "data": [{"name": "t", "values": [{"a": 0}]}],
            "marks": [{"type": "arc", "from": {"data": "t"}, "encode": {"enter": {
              "x": {"value": 50}, "y": {"value": 50},
              "startAngle": {"value": 0}, "endAngle": {"value": 1},
              "innerRadius": {"value": 20}, "outerRadius": {"value": 40}$extra}}}]
          }
          """
            .trimIndent()
        )
      assertTrue(compiled.diagnostics.isEmpty(), compiled.diagnostics.toString())
      return compiled.scene!!.flatten().map { it.node }.filterIsInstance<PathNode>().single()
    }

    val plain = arcPath("")
    val padded = arcPath(""", "padAngle": {"value": 0.1}""")
    val rounded = arcPath(""", "cornerRadius": {"value": 5}""")
    // A gap narrows the slice, and rounding pulls its corners in; both shrink the drawn extent.
    assertTrue(padded.bounds.width < plain.bounds.width, "padding did not narrow the slice")
    assertTrue(rounded.bounds.width < plain.bounds.width, "rounding did not pull the corners in")
  }

  @Test
  fun `an unknown mark type is reported`() {
    val bogus = minimalBar.replace("\"type\": \"rect\"", "\"type\": \"hexbin\"")
    assertTrue(codes(bogus).contains(DiagnosticCodes.PARSE_UNKNOWN_MARK))
  }

  @Test
  fun `legends, titles and signals all compile rather than being reported`() {
    val extras =
      minimalBar.replace(
        "\"marks\":",
        "\"legends\": [{\"fill\": \"x\"}], \"title\": \"A chart\", \"marks\":",
      )
    val compiled = compile(extras)
    val messages = compiled.diagnostics.map { it.message }
    assertTrue(messages.none { it.contains("Legend generation") }, messages.toString())
    assertTrue(messages.none { it.contains("Title generation") }, messages.toString())
    assertTrue(messages.none { it.contains("Signals require") }, messages.toString())
    val roles =
      requireNotNull(compiled.scene).flatten().mapNotNull { it.node.metadata.role }.toSet()
    assertTrue(roles.containsAll(setOf("legend", "title", "title-text")), roles.toString())
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

  /**
   * `fit` shrinks the plotting area so the drawing comes out no larger than the declared size,
   * where `pad` grows the surface instead. It used to fall back to `pad` with a diagnostic; the
   * diagnostic is gone with the fallback, and its absence is part of the assertion.
   */
  @Test
  fun `autosize fit shrinks the chart rather than growing the surface`() {
    // An axis is what makes the two differ: its labels hang outside the plotting area, and that
    // overhang is what `pad` adds to the surface and `fit` takes out of the chart.
    val withAxis =
      minimalBar.replace(
        "\"marks\":",
        "\"axes\": [{\"orient\": \"left\", \"scale\": \"y\"}], \"marks\":",
      )
    val padded = compile(withAxis)
    val fitted =
      compile(withAxis.replace("\"padding\": 0,", "\"padding\": 0, \"autosize\": \"fit\","))

    assertTrue(fitted.isUsable)
    assertTrue(
      fitted.diagnostics.none { it.message.contains("autosize") },
      fitted.diagnostics.toString(),
    )
    // The axis labels hang outside the plotting area, so `pad` grows past the declared 100 and
    // `fit`
    // does not.
    val padWidth = requireNotNull(padded.scene).width
    val fitWidth = requireNotNull(fitted.scene).width
    assertTrue(padWidth > 100.0, "pad should grow: $padWidth")
    assertTrue(fitWidth <= 100.0, "fit should not grow: $fitWidth")
  }

  @Test
  fun `a chart with no declared size is measured from its contents`() {
    // Upstream seeds the `width` signal with `spec.width || 0`, so a specification with no size is
    // not an incomplete one: it is a chart the size of what it draws. Probed — this specification
    // through upstream leaves a frame reaching (0, 0) to (30, 20), so 40 by 30 once the padding is
    // added. Every faceted Vega-Lite chart is written this way, and a 200-unit default stood a
    // whole phantom chart beside one.
    val sizeless =
      """
      {
        "padding": 5,
        "data": [{"name": "t", "values": [{"a": 1}]}],
        "marks": [{
          "type": "rect", "from": {"data": "t"},
          "encode": {"update": {
            "x": {"value": 0}, "y": {"value": 0},
            "width": {"value": 30}, "height": {"value": 20},
            "fill": {"value": "steelblue"}
          }}
        }]
      }
      """
        .trimIndent()
    val compiled = compile(sizeless)
    val note = compiled.diagnostics.single { it.code == DiagnosticCodes.PARSE_MISSING_PROPERTY }
    assertEquals(DiagnosticSeverity.INFO, note.severity)
    val scene = requireNotNull(compiled.scene)
    assertEquals(40.0, scene.width, 1e-9)
    assertEquals(30.0, scene.height, 1e-9)
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

  // ---- series continuity ------------------------------------------------------

  private fun seriesSpec(defined: String = "") =
    """
    {
      "width": 200, "height": 100, "padding": 0, "autosize": "none",
      "data": [{"name": "s", "values": [
        {"t": 0, "v": 10}, {"t": 1, "v": 20}, {"t": 2, "v": null},
        {"t": 3, "v": 30}, {"t": 4, "v": 15}]}],
      "scales": [
        {"name": "x", "type": "linear", "domain": [0, 4], "range": "width"},
        {"name": "y", "type": "linear", "domain": [0, 30], "range": "height"}
      ],
      "marks": [{"type": "line", "from": {"data": "s"}, "encode": {"enter": {
        "x": {"scale": "x", "field": "t"}, "y": {"scale": "y", "field": "v"}$defined,
        "stroke": {"value": "#000000"}}}}]
    }
    """
      .trimIndent()

  @Test
  fun `a null in a series does not break the line, it draws through zero`() {
    // The surprise, and upstream's actual behaviour: a Vega line reads its coordinate as `item.y ||
    // 0`
    // and draws straight to the top of the range. Breaking a series is what `defined` is for, and
    // nothing else does it — verified against upstream, which emits one unbroken path here.
    val path =
      requireNotNull(compile(seriesSpec()).scene)
        .flatten()
        .map { it.node }
        .filterIsInstance<PathNode>()
        .single()
    val moves = path.path.commands.count { it is dev.aster.vega.scene.PathCommand.MoveTo }
    assertEquals(1, moves, "the line should be one unbroken run")
    val nullPoint =
      path.path.commands.filterIsInstance<dev.aster.vega.scene.PathCommand.LineTo>().first {
        it.x == 100.0
      }
    assertEquals(0.0, nullPoint.y, 1e-9, "an unresolvable value reads as zero, not as a gap")
  }

  @Test
  fun `the defined channel is what breaks a series`() {
    val json = seriesSpec(""", "defined": {"signal": "isValid(datum.v)"}""")
    val path =
      requireNotNull(compile(json).scene)
        .flatten()
        .map { it.node }
        .filterIsInstance<PathNode>()
        .single()
    val moves = path.path.commands.count { it is dev.aster.vega.scene.PathCommand.MoveTo }
    // Three subpaths, not two: the run before the gap, the point that was not defined — which is
    // still one of the series' points, drawn as nothing — and the run after it.
    assertEquals(3, moves, "the runs either side of the gap, and the point that made it")
  }

  // ---- scale reverse ----------------------------------------------------------

  @Test
  fun `reverse flips the range and leaves the domain alone`() {
    // Reversing the domain instead maps every value to the same place, so a chart looks right and
    // everything derived from the domain is backwards — the ticks descend, so the axis labels run
    // the
    // wrong way and the domain line is drawn end to end. Verified against upstream, which keeps the
    // domain ascending and reverses the range.
    val compiled =
      compile(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "scales": [
            {"name": "y", "type": "linear", "domain": [0, 20], "range": "height", "reverse": true},
            {"name": "b", "type": "band", "domain": ["a", "b"], "range": "width", "reverse": true}
          ]
        }
        """
          .trimIndent()
      )
    val y = compiled.scales["y"] as dev.aster.vega.runtime.scale.LinearScale
    assertEquals(listOf(0.0, 20.0), y.domain)
    assertEquals(listOf(0.0, 100.0), y.range)
    // The mapping is the same either way; only what the axis reads off it differs.
    assertEquals(0.0, y.apply(0.0), 1e-9)
    assertEquals(100.0, y.apply(20.0), 1e-9)

    val band = compiled.scales["b"] as dev.aster.vega.runtime.scale.BandScale
    assertEquals(listOf("a", "b"), band.domain, "the domain keeps its order")
    assertEquals(listOf(100.0, 0.0), band.range)
  }

  // ---- time scales ------------------------------------------------------------

  @Test
  fun `time and utc scales differ only in the zone they read the calendar in`() {
    // Which is not a detail: it decides where a day starts, so the same specification ticks
    // differently in Sydney and in Reykjavik. That is also why every time fixture uses `utc` — a
    // local-time reference would depend on the machine that generated it.
    val compiled =
      compile(
        """
        {
          "width": 300, "height": 100, "padding": 0,
          "data": [{"name": "t", "values": [
            {"d": "2026-01-15T00:00:00Z"}, {"d": "2026-08-05T00:00:00Z"}],
            "format": {"parse": {"d": "date"}}}],
          "scales": [
            {"name": "local", "type": "time", "domain": {"data": "t", "field": "d"},
             "range": "width"},
            {"name": "utc", "type": "utc", "domain": {"data": "t", "field": "d"},
             "range": "width"}
          ]
        }
        """
          .trimIndent()
      )
    val local = compiled.scales["local"] as dev.aster.vega.runtime.scale.TimeScale
    val utc = compiled.scales["utc"] as dev.aster.vega.runtime.scale.TimeScale
    assertEquals(kotlinx.datetime.TimeZone.currentSystemDefault(), local.zone)
    assertEquals(kotlinx.datetime.TimeZone.UTC, utc.zone)
    // The domain is the same instants either way; only the calendar reading of them differs.
    assertEquals(local.domain, utc.domain)
  }

  @Test
  fun `a field that cannot be read as a date is reported, not silently zeroed`() {
    val compiled =
      compile(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "data": [{"name": "t", "values": [{"d": "last Tuesday"}],
            "format": {"parse": {"d": "date"}}}]
        }
        """
          .trimIndent()
      )
    assertTrue(
      compiled.diagnostics.any { it.message.contains("last Tuesday") },
      compiled.diagnostics.toString(),
    )
  }
}
