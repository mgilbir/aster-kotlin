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

  /**
   * `labelFlush` hangs the labels at the ends of the range from those ends.
   *
   * Reference vectors from upstream, on a linear scale from 0 to 100 across 100 units: the label at
   * the origin aligns left, the one at the far end aligns right, and the one in the middle is left
   * centred. `true` is a threshold of one unit, so nothing but the two extremes qualifies.
   */
  @Test
  fun `labelFlush hangs the first and last label from the range ends`() {
    val labels =
      SpecCompiler()
        .compileJson(
          """
          {
            "width": 100, "height": 60, "padding": 0,
            "data": [{"name": "t", "values": [{"v": 0}, {"v": 100}]}],
            "scales": [
              {"name": "x", "type": "linear", "domain": {"data": "t", "field": "v"},
               "range": "width"}
            ],
            "axes": [{"orient": "bottom", "scale": "x", "labelFlush": true}]
          }
          """
            .trimIndent()
        )
        .scene!!
        .flatten()
        .map { it.node }
        .filterIsInstance<TextNode>()
        .filter { it.metadata.role == "axis-label" }

    assertTrue(labels.size >= 3, "expected several labels, got ${labels.size}")
    assertEquals(dev.aster.vega.scene.TextAlign.LEFT, labels.first().layout.run.align)
    assertEquals(dev.aster.vega.scene.TextAlign.RIGHT, labels.last().layout.run.align)
    assertEquals(
      dev.aster.vega.scene.TextAlign.CENTER,
      labels[labels.size / 2].layout.run.align,
      "a label away from either end keeps the orientation's own alignment",
    )
  }

  /**
   * A `gridScale` gives the gridlines the *other* scale's range, in that range's direction.
   *
   * Upstream draws a bottom axis's gridlines from the far side of the plot back to the axis when
   * the grid scale is a vertical one, because a vertical scale's range starts at the bottom. It is
   * the same line drawn the other way round, which is exactly the kind of difference that survives
   * unnoticed until a dashed gridline starts its pattern at the wrong end.
   */
  @Test
  fun `gridScale takes its direction from the scale it names`() {
    fun gridlines(gridScale: String) =
      SpecCompiler()
        .compileJson(
          """
          {
            "width": 100, "height": 60, "padding": 0,
            "data": [{"name": "t", "values": [{"v": 0}, {"v": 10}]}],
            "scales": [
              {"name": "x", "type": "linear", "domain": {"data": "t", "field": "v"},
               "range": "width"},
              {"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"},
               "range": "height"}
            ],
            "axes": [{"orient": "bottom", "scale": "x", "grid": true$gridScale}]
          }
          """
            .trimIndent()
        )
        .scene!!
        .flatten()
        .map { it.node }
        .filterIsInstance<RuleNode>()
        .filter { it.metadata.role == "axis-grid" }

    // `range: "height"` descends — from the bottom of the plot to the top — so a gridline that
    // follows it starts at the top of the plot in absolute terms and ends on the axis.
    val directed = gridlines(""", "gridScale": "y"""").first()
    assertTrue(
      directed.y1 < directed.y2,
      "a gridline over a descending scale runs back towards its axis: $directed",
    )
    val plain = gridlines("").first()
    assertTrue(plain.y1 > plain.y2, "without a gridScale it runs away from its axis: $plain")
  }

  // ---- what gets reported ---------------------------------------------------

  /**
   * Nothing is reported: every one of upstream's 79 axis properties is read.
   *
   * Kept as the place the next gap goes, and as the assertion that the last one has not come back.
   * They came off this list in turn — the line caps and dash offsets first, then `tickRound`,
   * `tickBand`, `position`, `translate`, `labelOffset`, `aria`, `description`, `tickMinStep`, and
   * finally `labelBound`, which turned out to cull nothing upstream either.
   */
  @Test
  fun `an axis reports nothing, because every property is read`() {
    val styled =
      compile(
          """{"orient": "bottom", "scale": "x", "labelBound": true, "tickMinStep": 5,
              "tickRound": false, "tickBand": "extent", "position": 10, "translate": 0,
              "labelOffset": 3, "aria": false, "description": "d", "tickCap": "round",
              "gridCap": "round", "domainCap": "round", "tickDashOffset": 2}"""
        )
        .diagnostics
    assertTrue(
      styled.none { it.code == DiagnosticCodes.PARSE_UNKNOWN_PROPERTY },
      "an axis reported something: $styled",
    )
  }

  @Test
  fun `an axis encode block folds into the properties it duplicates`() {
    // Reported by name rather than dropped: a part nobody implements, a channel that part has no
    // property for, and a channel a *plain string* property could not carry a signal into.
    val diagnostics =
      compile(
          """{"orient": "bottom", "scale": "x", "grid": true, "encode": {
              "grid": {"enter": {"strokeDash": {"value": [3, 3]},
                                 "x": {"value": 4},
                                 "strokeWidth": {"signal": "1 + 1"}}},
              "labels": {"update": {"text": {"signal": "'E'"}}},
              "axis": {"enter": {"fill": {"value": "red"}}}}}"""
        )
        .diagnostics
    assertTrue(diagnostics.any { it.jsonPath?.endsWith("encode.axis") == true }, "$diagnostics")
    assertTrue(diagnostics.any { it.jsonPath?.endsWith("grid.enter.x") == true }, "$diagnostics")
    // Both of these are honoured and say nothing: a constant dash pattern folds onto `gridDash`,
    // and
    // a **signal**-valued width folds onto `gridWidth`, which carries one as far as the builder.
    assertTrue(diagnostics.none { it.jsonPath?.endsWith("strokeDash") == true }, "$diagnostics")
    assertTrue(diagnostics.none { it.jsonPath?.endsWith("strokeWidth") == true }, "$diagnostics")
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
