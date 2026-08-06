package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.TextBaseline
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Legends: entry generation, layout and placement.
 *
 * Every number here was read off upstream Vega compiling the same specification, using the text
 * engine that reproduces upstream's canvas-free measurement so the widths are comparable. Legend
 * layout is pure arithmetic on [LegendDefaults], which means these are the tests that pin those
 * constants: get `symbolSize` or `rowPadding` wrong and every entry moves.
 */
class LegendTest {

  /** The headless engine, because a legend's size depends on how wide its labels measure. */
  private fun compile(json: String) = SpecCompiler(VegaHeadlessTextEngine()).compileJson(json)

  private fun nodes(json: String): List<SceneNode> {
    val compiled = compile(json)
    assertTrue(
      compiled.diagnostics.none { it.severity >= DiagnosticSeverity.ERROR },
      "expected a clean compile; got ${compiled.diagnostics}",
    )
    return requireNotNull(compiled.scene).flatten().map { it.node }
  }

  private fun legends(json: String): List<GroupNode> =
    nodes(json).filterIsInstance<GroupNode>().filter { it.metadata.role == "legend" }

  private fun role(json: String, role: String): List<SceneNode> =
    nodes(json).filter { it.metadata.role == role }

  /**
   * Where nodes of [role] actually land, in scene coordinates.
   *
   * Entries are positioned by a group transform, so a symbol's own `x` is cell-local and says
   * nothing about where it is drawn. This asks the flattened scene instead.
   */
  private fun placed(json: String, role: String): List<RectD> {
    val compiled = compile(json)
    return requireNotNull(compiled.scene)
      .flatten()
      .filter { it.node.metadata.role == role }
      .map { it.worldBounds }
  }

  /** Two categories whose labels measure 16 units wide, which makes the arithmetic checkable. */
  private fun spec(
    legends: String,
    width: Int = 200,
    height: Int = 100,
    scales: String =
      """
      {"name": "s1", "type": "ordinal", "domain": {"data": "t", "field": "c"},
       "range": {"scheme": "category10"}}
      """,
  ) =
    """
    {
      "width": $width, "height": $height, "padding": 0, "autosize": "none",
      "data": [{"name": "t", "values": [{"c": "aa", "v": 0}, {"c": "bb", "v": 19}]}],
      "scales": [$scales],
      "legends": [$legends]
    }
    """
      .trimIndent()

  // ---- symbol entries ---------------------------------------------------------

  @Test
  fun `a symbol legend draws one swatch and one label per domain entry`() {
    val symbols = role(spec("""{"fill": "s1"}"""), "legend-symbol")
    val labels = role(spec("""{"fill": "s1"}"""), "legend-label")
    assertEquals(2, symbols.size)
    assertEquals(listOf("aa", "bb"), labels.map { (it as TextNode).text })
  }

  @Test
  fun `symbol and label sit where upstream puts them`() {
    // Upstream's row box is max(ceil(sqrt(100) + 1.5), 10) = 12, so the swatch centres at 6 and the
    // label starts at 12 + labelOffset 4 = 16. The second row is 13 lower: the previous cell
    // reaches
    // 11 and rowPadding is 2, with nothing overhanging backwards to add.
    val json = spec("""{"fill": "s1"}""")
    val symbols = placed(json, "legend-symbol")
    val labels = placed(json, "legend-label")
    val legendX = legends(json).single().transform.e
    assertEquals(6.0, symbols[0].centerX - legendX, 1e-9)
    assertEquals(6.0, symbols[0].centerY, 1e-9)
    assertEquals(6.0 + 13.0, symbols[1].centerY, 1e-9)
    assertEquals(16.0, labels[0].left - legendX, 1e-9)
    assertEquals(6.0, labels[0].centerY, 1e-9)
    assertEquals(19.0, labels[1].centerY, 1e-9)
  }

  @Test
  fun `a swatch is filled from the scale it describes`() {
    val symbols = role(spec("""{"fill": "s1"}"""), "legend-symbol").map { it as SymbolNode }
    val fills = symbols.map { ((it.fill!!.paint as ScenePaint.Solid).color).toCssHex() }
    // category10's first two entries.
    assertEquals(listOf("#1f77b4", "#ff7f0e"), fills)
    assertEquals(LegendDefaults.SYMBOL_SIZE, symbols[0].size)
  }

  @Test
  fun `a legend that maps no colour outlines its swatches in grey instead of inventing a fill`() {
    val json =
      spec(
        """{"size": "sizes"}""",
        scales =
          """
          {"name": "sizes", "type": "linear", "domain": {"data": "t", "field": "v"},
           "range": [20, 200]}
          """,
      )
    val symbols = role(json, "legend-symbol").map { it as SymbolNode }
    assertTrue(symbols.isNotEmpty())
    symbols.forEach {
      // Upstream fills these transparently rather than not at all, which says "no colour here"
      // instead of leaving it to whatever default a renderer has.
      assertEquals(
        0.0,
        requireNotNull(it.fill).paint.let { p -> (p as ScenePaint.Solid).color.alpha },
      )
      val stroke = requireNotNull(it.stroke)
      assertEquals(
        LegendDefaults.symbolBaseStrokeColor.toCssHex(),
        (stroke.paint as ScenePaint.Solid).color.toCssHex(),
      )
    }
    // The swatches vary in size, which is the whole point of a size legend.
    assertTrue(symbols.map { it.size }.distinct().size > 1)
  }

  @Test
  fun `a legend runs down the page whatever edge it hangs off`() {
    // Upstream's symbolDirection and gradientDirection are both vertical and neither depends on
    // orient, so a legend along the bottom still stacks its entries rather than spreading them.
    for (orient in listOf("right", "bottom", "top-left")) {
      val symbols = placed(spec("""{"fill": "s1", "orient": "$orient"}"""), "legend-symbol")
      assertEquals(symbols[0].centerX, symbols[1].centerX, 1e-9, "$orient should stack, not spread")
      assertTrue(symbols[1].centerY > symbols[0].centerY, "$orient: $symbols")
    }
  }

  @Test
  fun `a horizontal legend runs its entries along a row`() {
    val json = spec("""{"fill": "s1", "direction": "horizontal"}""")
    val symbols = placed(json, "legend-symbol")
    val legendX = legends(json).single().transform.e
    // The first cell reaches 32 and columnPadding is 10, so the second starts at 42.
    assertEquals(6.0, symbols[0].centerX - legendX, 1e-9)
    assertEquals(6.0 + 42.0, symbols[1].centerX - legendX, 1e-9)
    assertEquals(symbols[0].centerY, symbols[1].centerY, 1e-9)
  }

  // ---- titles and sizing ------------------------------------------------------

  @Test
  fun `a title sits above the entries and pushes them down`() {
    val json = spec("""{"fill": "s1", "title": "One"}""")
    val title = role(json, "legend-title").single() as TextNode
    val symbols = role(json, "legend-symbol").map { it as SymbolNode }
    assertEquals("One", title.text)
    assertEquals(TextBaseline.TOP, title.layout.run.baseline)
    // Title height 11 plus titlePadding 5, so the first swatch centre moves from 6 to 22. The
    // offset
    // is on the entry group, so it shows up in world coordinates rather than on the symbol itself.
    val body =
      nodes(json).filterIsInstance<GroupNode>().single { it.metadata.role == "legend-entry" }
    assertEquals(16.0, body.transform.f, 1e-9)
    assertEquals(6.0, symbols[0].y, 1e-9)
  }

  @Test
  fun `a legend is exactly as large as upstream makes it`() {
    // Upstream: 32 x 24 without a title, 32 x 40 with one — the title adds its own height plus
    // titlePadding, and the width is the wider of the title and the entries.
    val plain = legends(spec("""{"fill": "s1"}""")).single()
    assertEquals(32.0, plain.size?.width)
    assertEquals(24.0, plain.size?.height)

    val titled = legends(spec("""{"fill": "s1", "title": "One"}""")).single()
    assertEquals(32.0, titled.size?.width)
    assertEquals(40.0, titled.size?.height)
  }

  @Test
  fun `a wide title widens the legend`() {
    // "Segment" measures 61 against entries that reach 32, so the title decides the width.
    val legend = legends(spec("""{"fill": "s1", "title": "Segment"}""")).single()
    assertEquals(61.0, legend.size?.width)
  }

  // ---- placement --------------------------------------------------------------

  /**
   * Where each orientation puts a 32x24 legend on a 200x100 chart with no axes.
   *
   * Read off upstream. The offset is 18 in every direction; the difference between the cases is
   * which edge the legend is anchored by, and a far-edge anchor subtracts the legend's own size.
   */
  @ParameterizedTest
  @CsvSource(
    "right,218,0",
    "left,-50,0",
    "top,0,-42",
    "bottom,0,118",
    "top-left,18,18",
    "top-right,150,18",
    "bottom-left,18,58",
    "bottom-right,150,58",
  )
  fun `each orientation places the legend where upstream does`(
    orient: String,
    x: Double,
    y: Double,
  ) {
    val legend = legends(spec("""{"fill": "s1", "orient": "$orient"}""")).single()
    assertEquals(x, legend.transform.e, 1e-9, "$orient x")
    assertEquals(y, legend.transform.f, 1e-9, "$orient y")
  }

  @Test
  fun `orient none places the legend by hand`() {
    val legend =
      legends(spec("""{"fill": "s1", "orient": "none", "legendX": 33, "legendY": 44}""")).single()
    assertEquals(33.0, legend.transform.e, 1e-9)
    assertEquals(44.0, legend.transform.f, 1e-9)
  }

  @Test
  fun `legends sharing an orientation stack with a margin between them`() {
    val two =
      legends(
        spec(
          """{"fill": "s1", "title": "One"}, {"fill": "s2", "title": "Two"}""",
          scales =
            """
            {"name": "s1", "type": "ordinal", "domain": {"data": "t", "field": "c"},
             "range": {"scheme": "category10"}},
            {"name": "s2", "type": "ordinal", "domain": {"data": "t", "field": "c"},
             "range": {"scheme": "set1"}}
            """,
        )
      )
    assertEquals(2, two.size)
    assertEquals(0.0, two[0].transform.f, 1e-9)
    // Height 40 plus the 8-unit layout margin.
    assertEquals(48.0, two[1].transform.f, 1e-9)
    assertEquals(two[0].transform.e, two[1].transform.e, 1e-9, "both hang off the same edge")
  }

  @Test
  fun `a vertical axis pushes a right-hand legend out, and a horizontal one does not`() {
    val withAxis =
      """
      {
        "width": 200, "height": 100, "padding": 0, "autosize": "none",
        "data": [{"name": "t", "values": [{"c": "aa", "v": 0}, {"c": "bb", "v": 19}]}],
        "scales": [
          {"name": "s1", "type": "ordinal", "domain": {"data": "t", "field": "c"},
           "range": {"scheme": "category10"}},
          {"name": "yr", "type": "linear", "domain": [0, 10], "range": [200, 260]}
        ],
        "axes": [{"orient": "right", "scale": "yr"}],
        "legends": [{"fill": "s1"}]
      }
      """
        .trimIndent()
    val legend = legends(withAxis).single()
    // The right axis reaches past the plotting area, so the legend clears the axis rather than the
    // plot: further right than the 218 it would sit at with no axis.
    assertTrue(
      legend.transform.e > 218.0,
      "legend should clear the axis, was ${legend.transform.e}",
    )
  }

  // ---- gradient legends -------------------------------------------------------

  private val heatSpec =
    spec(
      """{"fill": "heat", "title": "Score"}""",
      scales =
        """
        {"name": "heat", "type": "linear", "domain": {"data": "t", "field": "v"},
         "range": ["#eeeeff", "#003366"]}
        """,
    )

  @Test
  fun `a continuous colour scale gets a gradient legend without being asked`() {
    assertEquals(1, role(heatSpec, "legend-gradient").size)
    assertTrue(role(heatSpec, "legend-symbol").isEmpty())
  }

  @Test
  fun `a gradient swatch has upstream's default proportions`() {
    val swatch = role(heatSpec, "legend-gradient").single() as RectNode
    assertEquals(LegendDefaults.GRADIENT_THICKNESS, swatch.width)
    assertEquals(LegendDefaults.GRADIENT_LENGTH, swatch.height)
  }

  @Test
  fun `a gradient is sampled at the scale's own ticks, not just at its ends`() {
    // Upstream samples scale.ticks(15) plus the domain ends, so a [0, 19] domain yields 20 stops
    // with
    // offsets at multiples of 1/19 — a two-stop gradient would bend in the wrong places on a
    // multi-colour ramp.
    val swatch = role(heatSpec, "legend-gradient").single() as RectNode
    val gradient = swatch.fill!!.paint as ScenePaint.LinearGradient
    assertEquals(20, gradient.stops.size)
    assertEquals(0.0, gradient.stops.first().offset, 1e-9)
    assertEquals(1.0, gradient.stops.last().offset, 1e-9)
    assertEquals(1.0 / 19.0, gradient.stops[1].offset, 1e-9)
    // Vertical ramps run bottom to top, so the low end of the domain is at the bottom.
    assertEquals(1.0, gradient.y1, 1e-9)
    assertEquals(0.0, gradient.y2, 1e-9)
  }

  @Test
  fun `gradient labels are ticked along the ramp and baselined so they do not overhang it`() {
    // A 200-unit ramp asks for max(2, 2 * floor(200/100)) = 4 ticks, so a [0, 19] domain reads
    // 0, 5, 10, 15. Read off upstream, including the positions: a label sits at (1 - perc) *
    // length,
    // so 5 of 19 lands at 147.37 rather than at a quarter of the way up.
    val labels = role(heatSpec, "legend-label").map { it as TextNode }
    assertEquals(listOf("0", "5", "10", "15"), labels.map { it.text })
    // All of them sit beside the swatch: thickness 16 plus the gradient label offset of 2.
    assertTrue(labels.all { it.x == 18.0 }, labels.map { it.x }.toString())
    assertEquals(200.0, labels[0].y, 1e-9)
    assertEquals(200.0 * (1.0 - 5.0 / 19.0), labels[1].y, 1e-6)
    // Only the end labels change baseline, so a ramp's extremes stay inside it.
    assertEquals(TextBaseline.BOTTOM, labels[0].layout.run.baseline)
    assertEquals(TextBaseline.MIDDLE, labels[1].layout.run.baseline)
  }

  @Test
  fun `a short ramp labels the domain's own ends instead of too few ticks`() {
    // 120 units asks for 2 ticks, which upstream considers uninformative and replaces with the
    // domain endpoints — so this reads "0" and "19" rather than "0" and "10".
    val json =
      spec(
        """{"fill": "heat", "gradientLength": 120}""",
        scales =
          """
          {"name": "heat", "type": "linear", "domain": {"data": "t", "field": "v"},
           "range": ["#eeeeff", "#003366"]}
          """,
      )
    val labels = role(json, "legend-label").map { it as TextNode }
    assertEquals(listOf("0", "19"), labels.map { it.text })
    assertEquals(TextBaseline.BOTTOM, labels[0].layout.run.baseline)
    assertEquals(TextBaseline.TOP, labels[1].layout.run.baseline)
  }

  @Test
  fun `a horizontal gradient runs left to right with labels below`() {
    val json =
      spec(
        """{"fill": "heat", "direction": "horizontal"}""",
        scales =
          """
          {"name": "heat", "type": "linear", "domain": {"data": "t", "field": "v"},
           "range": ["#eeeeff", "#003366"]}
          """,
      )
    val swatch = role(json, "legend-gradient").single() as RectNode
    assertEquals(LegendDefaults.GRADIENT_LENGTH, swatch.width)
    assertEquals(LegendDefaults.GRADIENT_THICKNESS, swatch.height)
    val gradient = swatch.fill!!.paint as ScenePaint.LinearGradient
    assertEquals(0.0, gradient.y1, 1e-9)
    assertEquals(1.0, gradient.x2, 1e-9)
    val labels = role(json, "legend-label").map { it as TextNode }
    assertTrue(labels.all { it.y == 18.0 }, labels.map { it.y }.toString())
    assertEquals(TextBaseline.TOP, labels[0].layout.run.baseline)
  }

  @Test
  fun `gradientLength resizes the ramp`() {
    val json =
      spec(
        """{"fill": "heat", "gradientLength": 60, "gradientThickness": 8}""",
        scales =
          """
          {"name": "heat", "type": "linear", "domain": {"data": "t", "field": "v"},
           "range": ["#eeeeff", "#003366"]}
          """,
      )
    val swatch = role(json, "legend-gradient").single() as RectNode
    assertEquals(8.0, swatch.width)
    assertEquals(60.0, swatch.height)
  }

  @Test
  fun `an ordinal colour scale gets symbols, not a gradient`() {
    assertEquals(2, role(spec("""{"fill": "s1"}"""), "legend-symbol").size)
    assertTrue(role(spec("""{"fill": "s1"}"""), "legend-gradient").isEmpty())
  }

  @Test
  fun `a legend that also maps size stays symbolic even over a colour ramp`() {
    // A gradient cannot show varying sizes, so upstream only derives the gradient form for a legend
    // that maps colour alone.
    val json =
      spec(
        """{"fill": "heat", "size": "sizes"}""",
        scales =
          """
          {"name": "heat", "type": "linear", "domain": {"data": "t", "field": "v"},
           "range": ["#eeeeff", "#003366"]},
          {"name": "sizes", "type": "linear", "domain": {"data": "t", "field": "v"},
           "range": [20, 200]}
          """,
      )
    assertTrue(role(json, "legend-gradient").isEmpty())
    assertTrue(role(json, "legend-symbol").isNotEmpty())
  }

  // ---- what is reported -------------------------------------------------------

  @Test
  fun `explicit values override the entries the scale would generate`() {
    val labels = role(spec("""{"fill": "s1", "values": ["bb"]}"""), "legend-label")
    assertEquals(listOf("bb"), labels.map { (it as TextNode).text })
  }

  @Test
  fun `a legend with no channel is rejected`() {
    val compiled = compile(spec("""{"title": "Nothing"}"""))
    assertTrue(
      compiled.diagnostics.any {
        it.severity >= DiagnosticSeverity.ERROR && it.message.contains("which scale it describes")
      },
      compiled.diagnostics.toString(),
    )
  }

  @Test
  fun `a legend over an unbuilt scale is reported and skipped`() {
    val compiled = compile(spec("""{"fill": "nope"}"""))
    assertTrue(
      compiled.diagnostics.any {
        it.severity >= DiagnosticSeverity.ERROR && it.message.contains("nope")
      },
      compiled.diagnostics.toString(),
    )
    assertTrue(requireNotNull(compiled.scene).flatten().none { it.node.metadata.role == "legend" })
  }

  @Test
  fun `a gradient legend over a discrete scale is reported`() {
    val compiled = compile(spec("""{"fill": "s1", "type": "gradient"}"""))
    assertTrue(
      compiled.diagnostics.any {
        it.severity >= DiagnosticSeverity.ERROR && it.message.contains("continuous colour scale")
      },
      compiled.diagnostics.toString(),
    )
  }

  @Test
  fun `banded gradients are reported rather than approximated`() {
    val compiled = compile(spec("""{"fill": "s1", "type": "discrete"}"""))
    assertTrue(
      compiled.diagnostics.any { it.message.contains("Discrete (banded)") },
      compiled.diagnostics.toString(),
    )
  }

  @Test
  fun `unimplemented legend properties are reported by name`() {
    val compiled =
      compile(
        spec(
          """{"fill": "s1", "columns": 2, "labelOverlap": "parity", "symbolLimit": 4,
             "format": ".2f", "titleOrient": "left"}"""
        )
      )
    val messages = compiled.diagnostics.map { it.message }
    for (name in listOf("columns", "labelOverlap", "symbolLimit", "format", "titleOrient")) {
      assertTrue(messages.any { it.contains("'$name'") }, "$name not reported in $messages")
    }
  }

  @Test
  fun `an unknown orientation is reported instead of quietly defaulting`() {
    val compiled = compile(spec("""{"fill": "s1", "orient": "sideways"}"""))
    assertTrue(
      compiled.diagnostics.any { it.message.contains("legend orientation 'sideways'") },
      compiled.diagnostics.toString(),
    )
  }
}
