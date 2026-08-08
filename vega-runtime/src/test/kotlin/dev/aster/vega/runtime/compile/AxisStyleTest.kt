package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.scene.FontStyle
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Axis appearance, and what an axis says about the properties it cannot honour.
 *
 * Upstream's axis takes 74 properties. This engine honours 20-odd, and the rest used to be dropped
 * without a word — which is the failure this project is built to avoid: an axis that draws ten
 * ticks where four were asked for, or draws its labels flat where the specification turned them,
 * still looks like a chart. Every one of them is now named in a diagnostic.
 */
class AxisStyleTest {

  private fun compile(axis: String) =
    SpecCompiler()
      .compileJson(
        """
        {
          "width": 100, "height": 60, "padding": 0,
          "data": [{"name": "t", "values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}]}],
          "scales": [
            {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
             "range": "width"}
          ],
          "axes": [$axis]
        }
        """
          .trimIndent()
      )

  private fun nodes(axis: String) = compile(axis).scene!!.flatten().map { it.node }

  private fun rules(axis: String, role: String) =
    nodes(axis).filterIsInstance<RuleNode>().filter { it.metadata.role == role }

  private fun texts(axis: String, role: String) =
    nodes(axis).filterIsInstance<TextNode>().filter { it.metadata.role == role }

  // ---- styling --------------------------------------------------------------

  @Test
  fun `tick colour, width, dash and opacity reach the scene`() {
    val ticks =
      rules(
        """{"orient": "bottom", "scale": "x", "tickColor": "#003399", "tickWidth": 3,
            "tickDash": [2, 2], "tickOpacity": 0.5}""",
        "axis-tick",
      )
    assertEquals(2, ticks.size)
    val stroke = ticks.first().stroke
    assertEquals("#003399", (stroke.paint as ScenePaint.Solid).color.toCssHex())
    assertEquals(3.0, stroke.width)
    assertEquals(listOf(2.0, 2.0), stroke.dashArray)
    assertEquals(0.5, stroke.opacity)
  }

  @Test
  fun `the grid and the domain line are styled independently of the ticks`() {
    val axis =
      """{"orient": "bottom", "scale": "x", "grid": true,
          "gridColor": "#00aa00", "gridDash": [4, 2],
          "domainColor": "#990099", "domainWidth": 4}"""
    val grid = rules(axis, "axis-grid").first().stroke
    assertEquals("#00aa00", (grid.paint as ScenePaint.Solid).color.toCssHex())
    assertEquals(listOf(4.0, 2.0), grid.dashArray)

    val domain = rules(axis, "axis-domain").first().stroke
    assertEquals("#990099", (domain.paint as ScenePaint.Solid).color.toCssHex())
    assertEquals(4.0, domain.width)
    // Untouched properties keep Vega's own defaults rather than inheriting from a sibling part.
    assertEquals(1.0, rules(axis, "axis-tick").first().stroke.width)
  }

  /**
   * A label's colour is a *fill* and every other part's is a stroke, so `labelOpacity` lands on a
   * fill opacity where `gridOpacity` lands on a stroke opacity. Upstream draws them that way and a
   * renderer cannot substitute one for the other.
   */
  @Test
  fun `a label is filled, not stroked, and takes its own font`() {
    val labels =
      texts(
        """{"orient": "bottom", "scale": "x", "labelColor": "#cc3333", "labelOpacity": 0.7,
            "labelFont": "serif", "labelFontStyle": "italic", "labelFontWeight": "bold"}""",
        "axis-label",
      )
    val fill = labels.first().fill!!
    assertEquals("#cc3333", (fill.paint as ScenePaint.Solid).color.toCssHex())
    assertEquals(0.7, fill.opacity)
    val style = labels.first().layout.run.style
    assertEquals("serif", style.fontFamily)
    assertEquals(FontStyle.ITALIC, style.fontStyle)
    assertEquals(700, style.fontWeight)
  }

  /** A weight arrives as a keyword or a number; the scene graph wants the number either way. */
  @Test
  fun `a numeric font weight is accepted alongside the keyword`() {
    val numeric =
      texts("""{"orient": "bottom", "scale": "x", "labelFontWeight": 300}""", "axis-label")
    assertEquals(300, numeric.first().layout.run.style.fontWeight)
    val keyword =
      texts("""{"orient": "bottom", "scale": "x", "labelFontWeight": "normal"}""", "axis-label")
    assertEquals(400, keyword.first().layout.run.style.fontWeight)
  }

  @Test
  fun `a title takes its own colour and weight, and stays bold by default`() {
    val plain = texts("""{"orient": "bottom", "scale": "x", "title": "T"}""", "axis-title")
    assertEquals(700, plain.first().layout.run.style.fontWeight)

    val styled =
      texts(
        """{"orient": "bottom", "scale": "x", "title": "T",
            "titleColor": "#333333", "titleFontWeight": "normal"}""",
        "axis-title",
      )
    assertEquals(400, styled.first().layout.run.style.fontWeight)
    assertEquals(
      "#333333",
      (styled.first().fill!!.paint as ScenePaint.Solid).color.toCssHex(),
    )
  }

  // ---- what gets reported ---------------------------------------------------

  @Test
  fun `every unhonoured axis property is reported by name`() {
    val unhonoured =
      listOf(
        "\"labelBound\": true",
        "\"labelOffset\": 3",
        "\"tickMinStep\": 5",
        "\"tickRound\": false",
        "\"tickBand\": \"extent\"",
        "\"tickCap\": \"round\"",
        "\"gridCap\": \"round\"",
        "\"gridDashOffset\": 2",
        "\"domainCap\": \"round\"",
        "\"position\": 10",
        "\"translate\": 0",
        "\"titleLimit\": 40",
        "\"aria\": false",
        "\"description\": \"d\"",
      )
    for (property in unhonoured) {
      val name = property.substringAfter('"').substringBefore('"')
      val diagnostics = compile("""{"orient": "bottom", "scale": "x", $property}""").diagnostics
      val reported = diagnostics.filter {
        it.code == DiagnosticCodes.PARSE_UNKNOWN_PROPERTY && it.jsonPath?.endsWith(name) == true
      }
      assertTrue(reported.isNotEmpty(), "'$name' was not reported: $diagnostics")
    }
  }

  @Test
  fun `an axis encode block folds into the properties it duplicates`() {
    // Reported by name rather than dropped: a part nobody implements, a channel that part has no
    // property for, and a channel that would need the axis's own datum to resolve.
    val diagnostics =
      compile(
          """{"orient": "bottom", "scale": "x", "grid": true, "encode": {
              "grid": {"enter": {"strokeDash": {"value": [3, 3]},
                                 "x": {"value": 4},
                                 "strokeWidth": {"signal": "1 + 1"}}},
              "axis": {"enter": {"fill": {"value": "red"}}}}}"""
        )
        .diagnostics
    assertTrue(diagnostics.any { it.jsonPath?.endsWith("encode.axis") == true }, "$diagnostics")
    assertTrue(diagnostics.any { it.jsonPath?.endsWith("grid.enter.x") == true }, "$diagnostics")
    assertTrue(
      diagnostics.any { it.jsonPath?.endsWith("grid.enter.strokeWidth") == true },
      "$diagnostics",
    )
    // The one it can honour says nothing at all.
    assertTrue(diagnostics.none { it.jsonPath?.endsWith("strokeDash") == true }, "$diagnostics")
  }

  @Test
  fun `a styled axis reports nothing`() {
    val diagnostics =
      compile(
          """{"orient": "bottom", "scale": "x", "title": "T", "grid": true,
              "tickColor": "#003399", "tickWidth": 3, "tickDash": [2, 2], "tickOpacity": 0.5,
              "gridColor": "#00aa00", "gridWidth": 2, "gridDash": [4, 2], "gridOpacity": 0.3,
              "domainColor": "#990099", "domainWidth": 4, "domainDash": [6, 3],
              "domainOpacity": 0.6, "labelColor": "#cc3333", "labelFontStyle": "italic",
              "labelOpacity": 0.7, "labelFont": "serif", "labelFontWeight": "bold",
              "titleColor": "#333333", "titleFontWeight": "normal", "titleFont": "serif"}"""
        )
        .diagnostics
    assertTrue(
      diagnostics.none { it.severity >= DiagnosticSeverity.WARNING },
      diagnostics.toString(),
    )
  }
}
