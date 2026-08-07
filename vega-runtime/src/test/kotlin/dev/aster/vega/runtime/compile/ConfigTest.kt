package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A specification's `config` block.
 *
 * This is where a Vega-Lite-compiled specification puts everything it does not say inline, so a
 * chart that ignores `config` is not one with a few options missing — it is one drawn in somebody
 * else's theme.
 *
 * The precedence was read out of upstream's `axis-config.js` and then confirmed by setting the same
 * property at every level and seeing which one drew:
 * ```
 * style["guide-label"] / style["guide-title"]   weakest, and where Vega's own defaults live
 * config.axis
 * config.axisX | config.axisY
 * config.axisTop | axisBottom | axisLeft | axisRight
 * config.axisBand                               band scales only
 * the axis's own properties                     strongest
 * ```
 */
class ConfigTest {

  private fun compile(config: String, axis: String = "", scaleType: String = "band") =
    SpecCompiler()
      .compileJson(
        """
        {
          "width": 150, "height": 80, "padding": 5,
          "config": $config,
          "data": [{"name": "t", "values": [{"c": "a"}, {"c": "b"}]}],
          "scales": [{"name": "s", "type": "$scaleType",
            "domain": ${if (scaleType == "band") """{"data": "t", "field": "c"}""" else "[0, 10]"},
            "range": "width"}],
          "axes": [{"orient": "bottom", "scale": "s", "title": "T"$axis}]
        }
        """
          .trimIndent()
      )

  private fun ticks(config: String, axis: String = "", scaleType: String = "band") =
    compile(config, axis, scaleType)
      .scene!!
      .flatten()
      .map { it.node }
      .filterIsInstance<RuleNode>()
      .filter { it.metadata.role == "axis-tick" }

  private fun text(config: String, role: String, axis: String = "") =
    compile(config, axis)
      .scene!!
      .flatten()
      .map { it.node }
      .filterIsInstance<TextNode>()
      .first { it.metadata.role == role }

  /** A tick's length, which is the easiest property to watch move down the chain. */
  private fun tickSize(config: String, axis: String = "", scaleType: String = "band"): Double =
    ticks(config, axis, scaleType).first().let { kotlin.math.abs(it.y2 - it.y1) }

  private fun colourOf(paint: ScenePaint): String = (paint as ScenePaint.Solid).color.toCssHex()

  // ---- the chain ------------------------------------------------------------

  @Test
  fun `each block in turn beats the one before it`() {
    val axis = """"axis": {"tickSize": 3}"""
    val axisX = """"axisX": {"tickSize": 6}"""
    val axisBottom = """"axisBottom": {"tickSize": 9}"""
    val axisBand = """"axisBand": {"tickSize": 12}"""
    assertEquals(5.0, tickSize("{}"), "Vega's own default")
    assertEquals(3.0, tickSize("{$axis}"))
    assertEquals(6.0, tickSize("{$axis, $axisX}"))
    assertEquals(9.0, tickSize("{$axis, $axisX, $axisBottom}"))
    assertEquals(12.0, tickSize("{$axis, $axisX, $axisBottom, $axisBand}"))
    assertEquals(7.0, tickSize("{$axis, $axisX, $axisBottom, $axisBand}", """, "tickSize": 7"""))
  }

  /** `axisBand` is the one block that keys off the scale rather than the axis. */
  @Test
  fun `axisBand applies only to a band scale`() {
    val config = """{"axis": {"tickSize": 3}, "axisBand": {"tickSize": 12}}"""
    assertEquals(12.0, tickSize(config, scaleType = "band"))
    assertEquals(3.0, tickSize(config, scaleType = "linear"))
  }

  @Test
  fun `a dimension block applies only to its own dimension`() {
    assertEquals(5.0, tickSize("""{"axisY": {"tickSize": 20}}"""), "a bottom axis is not axisY")
    assertEquals(20.0, tickSize("""{"axisX": {"tickSize": 20}}"""))
  }

  // ---- guide styles ---------------------------------------------------------

  /**
   * A `style` block names its properties the way a *mark* does and a guide names them the way a
   * guide does, so `fill` has to become `labelColor` on the way through. Without that translation
   * the form every Vega-Lite theme uses would set nothing at all.
   */
  @Test
  fun `guide-label and guide-title carry the text defaults`() {
    val config =
      """{"style": {"guide-label": {"fill": "#00cc00", "fontSize": 14},
                    "guide-title": {"fill": "#0000cc", "fontSize": 20, "fontWeight": "normal"}}}"""
    val label = text(config, "axis-label")
    assertEquals("#00cc00", colourOf(label.fill!!.paint))
    assertEquals(14.0, label.layout.run.style.fontSize)

    val title = text(config, "axis-title")
    assertEquals("#0000cc", colourOf(title.fill!!.paint))
    assertEquals(20.0, title.layout.run.style.fontSize)
    assertEquals(400, title.layout.run.style.fontWeight)
  }

  @Test
  fun `an axis block beats the guide style below it`() {
    val config =
      """{"axis": {"labelColor": "#cc0000"}, "style": {"guide-label": {"fill": "#00cc00"}}}"""
    assertEquals("#cc0000", colourOf(text(config, "axis-label").fill!!.paint))
  }

  // ---- legends and chart-level values ---------------------------------------

  @Test
  fun `a legend reads its own config block`() {
    val compiled =
      SpecCompiler()
        .compileJson(
          """
          {
            "width": 120, "height": 60, "padding": 5,
            "config": {"legend": {"labelColor": "#775566", "labelFontSize": 11},
                       "style": {"guide-title": {"fill": "#332211"}}},
            "data": [{"name": "t", "values": [{"c": "a"}, {"c": "b"}]}],
            "scales": [{"name": "s", "type": "ordinal",
              "domain": {"data": "t", "field": "c"}, "range": {"scheme": "tableau10"}}],
            "legends": [{"fill": "s", "title": "Series"}]
          }
          """
            .trimIndent()
        )
    val nodes = compiled.scene!!.flatten().map { it.node }.filterIsInstance<TextNode>()
    val label = nodes.first { it.metadata.role == "legend-label" }
    assertEquals("#775566", colourOf(label.fill!!.paint))
    assertEquals(11.0, label.layout.run.style.fontSize)
    assertEquals(
      "#332211",
      colourOf(nodes.first { it.metadata.role == "legend-title" }.fill!!.paint),
    )
  }

  @Test
  fun `background and padding fall back to config, and the top level beats them`() {
    fun background(spec: String) = SpecCompiler().compileJson(spec).scene!!.background
    assertEquals(
      "#eeeeff",
      background("""{"width": 10, "height": 10, "config": {"background": "#eeeeff"}}""")
        ?.toCssHex(),
    )
    assertEquals(
      "#ff0000",
      background(
          """{"width": 10, "height": 10, "background": "#ff0000",
              "config": {"background": "#eeeeff"}}"""
        )
        ?.toCssHex(),
    )
  }

  // ---- what is still reported -----------------------------------------------

  /**
   * A theme that reaches the axes and not the bars looks more broken than one that reaches neither,
   * so the blocks nothing consumes say so by name.
   */
  @Test
  fun `config blocks that do not reach the chart are reported`() {
    val diagnostics =
      compile("""{"mark": {"fill": "#123456"}, "rect": {"stroke": "#000"}, "range": {}}""")
        .diagnostics
        .filter { it.code == DiagnosticCodes.PARSE_UNKNOWN_PROPERTY }
    for (name in listOf("mark", "rect", "range")) {
      assertTrue(
        diagnostics.any { it.jsonPath == "$.config.$name" },
        "$name not reported in $diagnostics",
      )
    }
  }

  @Test
  fun `a style nothing reads is reported by name`() {
    val diagnostics = compile("""{"style": {"point": {"size": 30}}}""").diagnostics
    assertTrue(
      diagnostics.any { it.jsonPath == "$.config.style.point" },
      diagnostics.toString(),
    )
  }

  @Test
  fun `an honoured config reports nothing`() {
    val diagnostics =
      compile(
          """{"axis": {"tickSize": 3}, "axisX": {"tickColor": "#123456"},
              "axisBand": {"domainColor": "#654321"}, "legend": {"labelColor": "#abcdef"},
              "style": {"guide-label": {"fill": "#000000"}}}"""
        )
        .diagnostics
    assertTrue(diagnostics.isEmpty(), diagnostics.toString())
  }
}
