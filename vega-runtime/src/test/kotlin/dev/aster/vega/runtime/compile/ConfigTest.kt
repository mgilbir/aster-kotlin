package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.spec.EventConfig
import dev.aster.vega.model.spec.EventPermit
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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

  // ---- marks ----------------------------------------------------------------

  private fun rectStyle(config: String, mark: String = "", encode: String = "") =
    SpecCompiler()
      .compileJson(
        """
        {
          "width": 100, "height": 60, "padding": 0,
          "config": $config,
          "data": [{"name": "t", "values": [{"v": 1}]}],
          "marks": [{"type": "rect"$mark, "from": {"data": "t"}, "encode": {"enter": {
            "x": {"value": 0}, "width": {"value": 10},
            "y": {"value": 0}, "height": {"value": 10}$encode}}}]
        }
        """
          .trimIndent()
      )
      .scene!!
      .flatten()
      .map { it.node }
      .filterIsInstance<dev.aster.vega.scene.RectNode>()
      .first { it.metadata.role == "mark" }

  /**
   * `config.mark` sits **below** the engine's built-in per-type defaults and `config.{marktype}`
   * sits above them, which is not what the names suggest. Upstream's own default configuration
   * fills `config.rect` in with the blue, so `config.mark.fill` never reaches a rect and
   * `config.rect.fill` does.
   */
  @Test
  fun `config mark loses to the built-in type default and config rect beats it`() {
    assertEquals("#4c78a8", colourOf(rectStyle("{}").fill!!.paint))
    assertEquals("#4c78a8", colourOf(rectStyle("""{"mark": {"fill": "#123456"}}""").fill!!.paint))
    assertEquals(
      "#654321",
      colourOf(
        rectStyle("""{"mark": {"fill": "#123456"}, "rect": {"fill": "#654321"}}""").fill!!.paint
      ),
    )
  }

  /** Where there is no built-in in between, `config.mark` applies straight through. */
  @Test
  fun `config mark applies where the type has no built-in of its own`() {
    val styled = rectStyle("""{"mark": {"stroke": "#334455", "strokeWidth": 3}}""")
    assertEquals("#334455", colourOf(styled.stroke!!.paint))
    assertEquals(3.0, styled.stroke!!.width)
  }

  /**
   * Upstream's pairing rule: a mark that encodes *either* paint channel gets **neither** default. A
   * rect outlined with a stroke and no fill is an outline — checking only `fill`, as this engine
   * did, filled it with the built-in blue instead.
   */
  @Test
  fun `encoding one paint channel suppresses the default for both`() {
    val outlined = rectStyle("{}", encode = """, "stroke": {"value": "#224466"}""")
    assertNull(outlined.fill, "a stroked rect must not pick up the built-in fill")
    assertEquals("#224466", colourOf(outlined.stroke!!.paint))

    val filled = rectStyle("{}", encode = """, "fill": {"value": "#00ff00"}""")
    assertEquals("#00ff00", colourOf(filled.fill!!.paint))
  }

  /** A named style beats the type block, and a later style beats an earlier one. */
  @Test
  fun `a mark opts into a config style by name`() {
    val config =
      """{"rect": {"fill": "#111111"},
          "style": {"a": {"fill": "#aaaaaa"}, "b": {"fill": "#bbbbbb", "cornerRadius": 5}}}"""
    assertEquals("#aaaaaa", colourOf(rectStyle(config, mark = """, "style": "a"""").fill!!.paint))
    val both = rectStyle(config, mark = """, "style": ["a", "b"]""")
    assertEquals("#bbbbbb", colourOf(both.fill!!.paint))
    assertEquals(5.0, both.cornerRadius)
  }

  // ---- nothing is left to report ---------------------------------------------

  /**
   * A theme that reaches the axes and not the bars looks more broken than one that reaches neither,
   * which is why every block used to say so by name. None is left to.
   */
  @Test
  fun `every config block reaches the chart`() {
    // The list emptied one block at a time. `range` came off first — its entries are what a *named*
    // range stands for, and the parser substitutes one for the name as upstream does. Then `group`,
    // which paints the view's own frame rather than its group marks, and `projection`, which merges
    // under each projection as `config.title` merges under the title.
    //
    // `events` was last, and it is the one that is not a drawing instruction: it decides which
    // listeners a view may attach and which browser defaults it suppresses. Being unread meant an
    // embedder's `{"window": false}` was ignored in silence — the opposite of what a policy is for.
    val diagnostics =
      compile("""{"events": {"defaults": {"allow": ["wheel"]}, "window": false, "bind": false}}""")
        .diagnostics
        .filter { it.code == DiagnosticCodes.PARSE_UNKNOWN_PROPERTY }
    assertTrue(diagnostics.isEmpty(), diagnostics.toString())
    // And the four that are chart-level *values* rather than blocks are read, not reported: saying
    // they had been ignored sent a reader looking for a bug that was not there.
    val scalars =
      compile("""{"background": "#eee", "padding": 7, "autosize": "fit", "description": "d"}""")
        .diagnostics
        .filter { it.code == DiagnosticCodes.PARSE_UNKNOWN_PROPERTY }
    assertTrue(scalars.isEmpty(), scalars.toString())
  }

  /**
   * The event policy is *read*, not merely unreported.
   *
   * Each shape is upstream's `initializeEventConfig`, which was probed rather than assumed: a list
   * becomes an allow-list, an object naming types is the same list written out, and `timer` is the
   * one key upstream leaves un-unpacked — so an array there permits nothing, which is carried
   * through as upstream carries it rather than corrected.
   */
  @Test
  fun `the event policy is read from config`() {
    val events =
      compile(
          """{"events": {"window": false, "view": ["click"], "selector": {"wheel": true},
              "timer": [500], "defaults": {"prevent": ["mousedown"], "allow": false},
              "bind": false, "globalCursor": true}}"""
        )
        .spec!!
        .events
    assertEquals(EventPermit.All(false), events.window)
    assertEquals(EventPermit.Types(setOf("click")), events.view)
    assertEquals(EventPermit.Types(setOf("wheel")), events.selector)
    assertEquals(EventPermit.Types(emptySet()), events.timer)
    assertEquals(EventPermit.Types(setOf("mousedown")), events.preventDefault)
    assertEquals(EventPermit.All(false), events.allowDefault)
    assertEquals(false, events.bind)
    assertEquals(true, events.globalCursor)
    // No policy at all permits everything and prevents nothing.
    assertEquals(EventConfig(), compile("""{}""").spec!!.events)
  }

  /**
   * A style block reaches a mark through the mark's own `style` property, so an unused one is not a
   * gap — it is a style nothing opted into, exactly as upstream leaves it.
   */
  @Test
  fun `a style nothing names is simply unused`() {
    assertTrue(compile("""{"style": {"point": {"size": 30}}}""").diagnostics.isEmpty())
  }

  /**
   * A top-level `style` paints the chart's own group, from Vega's built-in blocks.
   *
   * `"style": "cell"` is on every specification Vega-Lite compiles and nothing else writes it, so
   * this went missing for as long as nothing had compiled one: the plotting area drew without its
   * grey border, and — a border being half a unit of surface on each side — every such chart came
   * out a unit smaller than upstream's.
   */
  @Test
  fun `a top-level style paints the chart's own frame`() {
    val scene =
      SpecCompiler()
        .compileJson(
          """
          {
            "width": 100, "height": 60, "padding": 0, "style": "cell",
            "data": [{"name": "t", "values": [{"v": 1}]}],
            "marks": []
          }
          """
            .trimIndent()
        )
        .scene!!
    val frame = scene.root.children.first() as dev.aster.vega.scene.GroupNode
    assertEquals("#dddddd", colourOf(frame.stroke!!.paint))
    assertEquals(0.0, (frame.fill!!.paint as ScenePaint.Solid).color.alpha, 1e-9)
    // The stroke straddles the edge, so the surface reaches half a unit past the plotting area.
    assertEquals(101.0, scene.width, 1e-9)
    assertEquals(61.0, scene.height, 1e-9)
  }

  /** `config.mark` is for marks. The chart's frame is not one, and must not take its paint. */
  @Test
  fun `a config mark fill does not paint the chart's frame`() {
    val scene =
      SpecCompiler()
        .compileJson(
          """
          {
            "width": 100, "height": 60, "padding": 0,
            "config": {"mark": {"fill": "#ff0000"}},
            "data": [{"name": "t", "values": [{"v": 1}]}],
            "marks": []
          }
          """
            .trimIndent()
        )
        .scene!!
    val frame = scene.root.children.first() as dev.aster.vega.scene.GroupNode
    assertNull(frame.fill, "the frame took config.mark's fill")
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

  /**
   * The same block, supplied by the **host** instead of by the specification.
   *
   * A chart arriving from a server carries the colours that server chose, and an app drawing it on
   * a dark surface has to be able to say otherwise without rewriting the payload. The
   * specification's own configuration still wins wherever both name a property, which is what makes
   * a host's one a *theme* rather than an override.
   */
  @Test
  fun `a host configuration reaches the guides, and a specification's own beats it`() {
    fun labelColour(json: String, host: String?): String =
      colourOf(
        SpecCompiler(hostConfig = host?.let { VegaJson.parse(it) })
          .compileJson(json)
          .scene!!
          .flatten()
          .map { it.node }
          .filterIsInstance<TextNode>()
          .first { it.metadata.role == "axis-label" }
          .fill!!
          .paint
      )

    val plain =
      """
      {
        "width": 150, "height": 80, "padding": 5,
        "data": [{"name": "t", "values": [{"c": "a"}]}],
        "scales": [{"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
                    "range": "width"}],
        "axes": [{"orient": "bottom", "scale": "x"}]
      }
      """
        .trimIndent()
    val themed =
      plain.replace(
        """"width": 150""",
        """"config": {"axis": {"labelColor": "#111111"}}, "width": 150""",
      )

    val hostTheme = """{"axis": {"labelColor": "#eeeeee"}}"""
    val untouched = labelColour(plain, null)
    val hosted = labelColour(plain, hostTheme)
    val stated = labelColour(themed, hostTheme)

    assertTrue(hosted != untouched, "the host's colour did not reach the axis: $hosted")
    assertTrue(stated != hosted, "the specification's own colour should have won: $stated")
  }
}
