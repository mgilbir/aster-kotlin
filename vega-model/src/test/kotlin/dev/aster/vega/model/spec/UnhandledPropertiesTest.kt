package dev.aster.vega.model.spec

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Nothing a specification asks for is dropped without saying so.
 *
 * This is PROJECT_BRIEF.md 3.3 taken literally, and the axis is why it needs a test rather than a
 * convention: it honoured fifteen of upstream's 74 properties and ignored the other fifty-nine in
 * silence, each one added at a moment when nobody was looking at the whole list. The parsers now
 * name what they *consume* and report the remainder, so a property nobody thought about — including
 * one upstream adds after this was written — becomes a diagnostic instead of a silence.
 *
 * The property lists below come from `oracle-js/node_modules/vega/build/vega-schema.json`, which is
 * upstream's own schema for the pinned version:
 * ```
 * node -e "const s=require('./oracle-js/node_modules/vega/build/vega-schema.json');
 *          console.log(Object.keys(s.definitions.axis.properties).sort().join(' '))"
 * ```
 */
class UnhandledPropertiesTest {

  private fun diagnostics(json: String) = SpecParser().parseJson(json).diagnostics

  private fun ignored(json: String): List<String> =
    diagnostics(json)
      .filter { it.code == DiagnosticCodes.PARSE_UNKNOWN_PROPERTY }
      .mapNotNull { it.jsonPath?.substringAfterLast('.') }

  /**
   * A chart, with the fragment under test in it.
   *
   * The mark is not decoration. A document that declares nothing that draws now reports
   * `PARSE_NOTHING_TO_DRAW`, and several assertions here are that a fragment produces **no**
   * diagnostic at all — which is a much weaker claim if the surrounding document is not a chart.
   */
  private fun spec(body: String) =
    """
    {
      "width": 100, "height": 60,
      "data": [{"name": "t", "values": [{"c": "a", "v": 1}]}],
      "marks": [{"type": "rect", "from": {"data": "t"}}],
      $body
    }
    """
      .trimIndent()

  /**
   * None of upstream's 23 scale properties is reported any more.
   *
   * Kept, and kept naming the six that were the last to arrive — `domainMin`, `domainMax`,
   * `domainMid`, `bins`, `domainRaw` and `domainImplicit`. One that stopped being honoured would go
   * back to being reported here, which is the failure this test exists to produce; the empty list
   * only says that today none of them is.
   */
  @Test
  fun `a scale reports the domain overrides it cannot honour`() {
    val reported =
      ignored(
        spec(
          """"scales": [{"name": "s", "type": "linear", "range": "width",
              "domain": {"data": "t", "field": "v"},
              "domainMin": 0, "domainMax": 100, "domainMid": 50,
              "domainRaw": [1, 2], "domainImplicit": true, "bins": [0, 5, 10]}]"""
        )
      )
    assertEquals(emptyList<String>(), reported.sorted())
  }

  /**
   * Nothing remains: all 72 of upstream's legend properties are read.
   *
   * `symbolFillColor` came off this list once it was understood to be a *fallback* rather than an
   * override — upstream sets the channel from it and then overwrites it from the scale, so only a
   * `size` or `shape` legend ever takes the colour — and `clipHeight` came off because it had been
   * implemented all along and was simply missing from `LEGEND_CONSUMED`, so it reported a gap that
   * was not there. `gridAlign` was the last, and it is not the dead letter it looks like: upstream
   * defaults it to `each` in `config.legend`, and the entry grid's row centring is conditional on
   * being aligned, so a legend that set `none` was quietly centred anyway.
   */
  @Test
  fun `a legend reports the styling it cannot honour`() {
    val reported =
      ignored(
        spec(
          """"scales": [{"name": "s", "type": "ordinal", "domain": ["a"], "range": ["#000"]}],
             "legends": [{"fill": "s", "labelColor": "#333", "labelFont": "serif",
              "symbolFillColor": "#eee", "symbolDash": [2, 2], "strokeColor": "#999",
              "cornerRadius": 4, "clipHeight": 10, "gridAlign": "each"}]"""
        )
      )
    assertEquals(emptyList<String>(), reported.sorted())
  }

  /**
   * A signal in a guide's styling is **read**, and a value of an unreadable shape is reported.
   *
   * This was the quietest gap in the parser and it is now closed. `labelFontSize: {"signal": "n"}`
   * always worked, because that property is read through `numberOrSignal`; `labelColor: {"signal":
   * "c"}` did not, because the styling block took only a literal — and it said nothing, so a chart
   * colouring its axis from a control drew black labels and looked finished. Both work now. What is
   * still reported is a value that is neither: an array where a colour belongs is nothing anything
   * can read, and saying so is the difference between a gap and a wrong chart.
   */
  @Test
  fun `a guide's styling reads a signal and reports what it cannot`() {
    val honoured =
      ignored(
        spec(
          """"signals": [{"name": "c", "value": "#c00"}],
             "scales": [{"name": "s", "type": "linear", "domain": [0, 1], "range": "width"}],
             "axes": [{"scale": "s", "orient": "bottom", "labelColor": {"signal": "c"},
              "tickWidth": {"signal": "2"}, "gridDash": {"signal": "[2,2]"},
              "titleFontWeight": {"signal": "'bold'"}, "labelFontSize": {"signal": "12"}}],
             "legends": [{"fill": "s", "labelColor": {"signal": "c"},
              "symbolSize": {"signal": "40"}}]"""
        )
      )
    assertEquals(emptyList<String>(), honoured.sorted())

    val unreadable =
      ignored(
        spec(
          """"scales": [{"name": "s", "type": "linear", "domain": [0, 1], "range": "width"}],
             "axes": [{"scale": "s", "orient": "bottom", "labelColor": [1, 2],
              "tickWidth": "wide"}]"""
        )
      )
    assertEquals(listOf("labelColor", "tickWidth"), unreadable.sorted())
  }

  @Test
  fun `a title reports the styling it cannot honour`() {
    val reported =
      ignored(
        spec(
          """"title": {"text": "T", "color": "#333", "font": "serif", "fontWeight": "bold",
              "fontStyle": "italic", "lineHeight": 14, "subtitleColor": "#666",
              "baseline": "top"}"""
        )
      )
    // Every one of these is read now. `color` and `subtitleColor` paint the two lines separately,
    // `lineHeight` sets the gap between a heading's lines, and `baseline` overrides what `orient`
    // implies — as `align` and `angle` override what `anchor` and `orient` imply.
    assertEquals(emptyList<String>(), reported.sorted())
  }

  /**
   * Every channel in Vega's encoding vocabulary now reaches the scene, so this asserts silence.
   *
   * Kept rather than deleted, and kept naming the channels that were the last to arrive — the
   * per-corner radii, `limit`/`ellipsis`, `tension`, polar `radius`/`theta`, `blend` and `clip`. A
   * channel that stopped being honoured would go back to being reported here, which is the failure
   * this test exists to produce; the empty list only says that today none of them is.
   */
  @Test
  fun `an encode entry reports the channels no encoder reads`() {
    val reported =
      ignored(
        spec(
          """"marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
              "xc": {"value": 10}, "yc": {"value": 10},
              "width": {"value": 5}, "height": {"value": 5},
              "strokeDash": {"value": [2, 2]}, "strokeCap": {"value": "round"},
              "limit": {"value": 40}, "ellipsis": {"value": "~"},
              "tooltip": {"value": "t"}, "zindex": {"value": 1},
              "cornerRadiusTopLeft": {"value": 2}, "cornerRadiusBottomRight": {"value": 2},
              "tension": {"value": 0.5}, "radius": {"value": 4}, "theta": {"value": 1},
              "blend": {"value": "multiply"}, "clip": {"value": true},
              "cursor": {"value": "pointer"}}}}]"""
        )
      )
    assertEquals(emptyList<String>(), reported.sorted())
  }

  /**
   * A `layout` block reports nothing: all ten of upstream's properties are read.
   *
   * Kept because inverting this block from a table of exceptions into a table of what it *consumes*
   * is what found the last gap — `titleAnchor` had neither an entry nor a reader, so a trellis that
   * anchored its cell titles was told nothing at all.
   */
  @Test
  fun `a layout reports the properties it cannot honour`() {
    val reported =
      ignored(
        spec(
          """"marks": [{"type": "group", "from": {"facet": {"data": "t", "name": "cell",
              "groupby": "c"}},
             "layout": {"columns": 2, "padding": 5, "align": "each", "bounds": "flush",
              "center": true, "offset": 4, "headerBand": 0.5, "footerBand": 0.5,
              "titleBand": 0.5, "titleAnchor": "end"},
             "marks": []}]"""
        )
      )
    assertEquals(emptyList<String>(), reported.sorted())
  }

  /** A channel the engine does read is not reported, or the diagnostics would be noise. */
  @Test
  fun `an ordinary mark reports nothing`() {
    val reported =
      ignored(
        spec(
          """"scales": [{"name": "s", "type": "linear", "domain": [0, 1], "range": "height"}],
             "marks": [{"type": "rect", "name": "bars", "from": {"data": "t"},
              "encode": {"enter": {
                "x": {"value": 0}, "width": {"value": 5},
                "y": {"scale": "s", "field": "v"}, "y2": {"scale": "s", "value": 0},
                "fill": {"value": "steelblue"}, "fillOpacity": {"value": 0.8},
                "stroke": {"value": "black"}, "strokeWidth": {"value": 1},
                "cornerRadius": {"value": 2}}}}]"""
        )
      )
    assertTrue(reported.isEmpty(), reported.toString())
  }

  /**
   * The generic message is the safety net: it fires for a property nobody has written a tailored
   * explanation for, including one upstream adds later.
   *
   * Every block's table of tailored explanations is now empty — there is no property left in the
   * whole inventory to point at — so the second half of this test looks one level *down* instead,
   * at a **channel** a guide's `encode` block cannot express. That is where the remaining gaps are,
   * and they are named one at a time rather than being reported as a whole block nobody reads.
   */
  @Test
  fun `a property nobody anticipated is still reported`() {
    val invented =
      diagnostics(
          spec(
            """"scales": [{"name": "s", "type": "linear", "domain": [0, 1], "range": "width",
                  "somethingUpstreamAddedLater": 3}]"""
          )
        )
        .single { it.jsonPath?.endsWith("somethingUpstreamAddedLater") == true }
    assertTrue("not implemented" in invented.message, invented.message)

    val channel =
      diagnostics(
          spec(
            """"title": {"text": "T", "encode": {"title": {"update": {"x": {"value": 4},
                  "fill": {"value": "#333"}}}}}"""
          )
        )
        .single { it.jsonPath?.endsWith("x") == true }
    assertTrue("is not read" in channel.message, channel.message)
  }

  /**
   * `bind` is grammar, not a widget, and the two forms that cannot work say why.
   *
   * The description of a control parses like anything else — see `SignalBind` — so a chart that
   * asks for a slider no longer reports that bindings have no equivalent here. Two forms still
   * cannot: a binding that takes its value from an **element already on the page**, which there is
   * no page for, and a `radio` or `select` with nothing to choose between, which upstream's own
   * schema requires.
   */
  @Test
  fun `a binding is read, and the two forms that cannot work are reported`() {
    val bound =
      SpecParser()
        .parseJson(
          spec(
            """"signals": [{"name": "size", "value": 40,
                 "bind": {"input": "range", "min": 10, "max": 100, "name": "bar size"}}]"""
          )
        )
    assertTrue(bound.diagnostics.isEmpty(), bound.diagnostics.toString())
    assertEquals(
      SignalBind.Range(min = 10.0, max = 100.0, name = "bar size"),
      bound.spec!!.signals.single().bind,
    )

    val element =
      diagnostics(spec(""""signals": [{"name": "s", "bind": {"element": "#slider"}}]""")).single {
        it.code == DiagnosticCodes.PARSE_MISSING_PROPERTY
      }
    assertTrue("already on the page" in element.message, element.message)

    val empty =
      diagnostics(spec(""""signals": [{"name": "s", "bind": {"input": "radio"}}]""")).single {
        it.code == DiagnosticCodes.PARSE_MISSING_PROPERTY
      }
    assertTrue("needs 'options'" in empty.message, empty.message)
  }

  /**
   * A generic input carries every extra property; a structured one reports it.
   *
   * The asymmetry is upstream's, in both halves of it. Its generic generator copies all remaining
   * properties onto the element and its schema marks that variant `additionalProperties: true`, so
   * `placeholder` is grammar and a diagnostic on it would report a gap that is really a decision
   * not to carry the hint. The four structured kinds close the door — `additionalProperties:
   * false`, and a generator that builds a fixed control — so `placeholder` on a checkbox means
   * nothing anywhere and is worded as the mistake it is, not as something unimplemented here.
   */
  @Test
  fun `a text binding carries its extra attributes and a checkbox reports them`() {
    val field =
      SpecParser()
        .parseJson(
          spec(
            """"signals": [{"name": "q", "value": "",
                 "bind": {"input": "text", "placeholder": "search jobs", "autocomplete": "off",
                          "maxlength": 40}}]"""
          )
        )
    assertTrue(field.diagnostics.isEmpty(), field.diagnostics.toString())
    val bind = field.spec!!.signals.single().bind as SignalBind.Field
    assertEquals(
      mapOf(
        "placeholder" to VegaValue.Str("search jobs"),
        "autocomplete" to VegaValue.Str("off"),
        "maxlength" to VegaValue.Num(40.0),
      ),
      bind.attributes,
    )
    assertEquals("search jobs", bind.attributeText("placeholder"))
    assertEquals(null, bind.attributeText("title"))

    val checkbox =
      diagnostics(
          spec(""""signals": [{"name": "s", "bind": {"input": "checkbox", "placeholder": "x"}}]""")
        )
        .single()
    assertTrue("neither has upstream's" in checkbox.message, checkbox.message)
  }

  /**
   * A handler fired by a **scale** being rebuilt says so, and one fired by a signal says nothing.
   *
   * The pair matters more than either half. A recompile rebuilds every scale, so nothing here knows
   * which one *moved* and the scale form cannot be honoured — it is reported. The signal form is
   * honoured, and a diagnostic on it would send a reader looking for a gap that was closed.
   */
  @Test
  fun `a handler sourced on a scale is reported and one sourced on a signal is not`() {
    val scaled =
      diagnostics(
          spec(
            """"scales": [{"name": "s", "type": "linear", "domain": [0, 1], "range": "width"}],
               "signals": [{"name": "k", "value": 0,
                 "on": [{"events": {"scale": "s"}, "update": "1"}]}]"""
          )
        )
        .single { it.code == DiagnosticCodes.PARSE_UNKNOWN_PROPERTY }
    assertTrue("does not track" in scaled.message, scaled.message)

    assertEquals(
      emptyList<String>(),
      diagnostics(
          spec(
            """"signals": [{"name": "a", "value": 1},
               {"name": "b", "value": 0,
                 "on": [{"events": {"signal": "a"}, "update": "a * 2"}]}]"""
          )
        )
        .map { it.message },
    )
  }

  /**
   * An interval tick count is read, and warned about by nothing.
   *
   * `tickCount` used to be read **twice**: once through `numberOrSignal`, which sees a string or an
   * `{"interval": …}` object and has nothing to make a number of, so it warned; and then again,
   * correctly, into `tickInterval` and `tickStep`. So a document was laid out exactly right and
   * complained about at the same time. Measured on a device, every chart carrying an interval tick
   * count produced one warning per axis, four on one page, all false — and a false warning is worse
   * than no warning, because it is indistinguishable from a property the engine really did drop.
   *
   * The four spellings are asserted together deliberately: the two interval forms have to stay
   * silent and the number and the signal have to keep reaching `tickCount`, which is the reading
   * the interval branch could have eaten.
   */
  @Test
  fun `an interval tick count is read without a diagnostic`() {
    val json =
      spec(
        """"scales": [{"name": "t", "type": "time",
             "domain": [{"signal": "datetime(2026, 0, 1)"}, {"signal": "datetime(2026, 11, 1)"}],
             "range": "width"},
            {"name": "s", "type": "linear", "domain": [0, 1], "range": "width"}],
           "axes": [{"scale": "t", "orient": "bottom", "tickCount": {"interval": "day", "step": 20}},
            {"scale": "t", "orient": "top", "tickCount": "month"},
            {"scale": "s", "orient": "left", "tickCount": 5},
            {"scale": "s", "orient": "right", "tickCount": {"signal": "3"}}],
           "legends": [{"fill": "t", "tickCount": {"interval": "month"}}]"""
      )
    assertEquals(emptyList<String>(), ignored(json).sorted())

    val axes = SpecParser().parseJson(json).spec!!.axes
    assertEquals("day", axes[0].tickInterval)
    assertEquals(20, axes[0].tickStep)
    assertEquals("month", axes[1].tickInterval)
    assertEquals(null, axes[1].tickStep)
    // The number and the signal still land on `tickCount`, and neither is mistaken for an interval.
    assertEquals(NumberValue.Constant(5.0), axes[2].tickCount)
    assertEquals(NumberValue.Signal("3"), axes[3].tickCount)
    assertEquals(null, axes[2].tickInterval)
    assertEquals(null, axes[3].tickInterval)
    assertEquals("month", SpecParser().parseJson(json).spec!!.legends.single().tickInterval)
  }

  /**
   * The warning moves to where a reading really does fail.
   *
   * Both of these used to fall through to a count **in silence**, which is the opposite failure
   * from the one above and the more expensive one: the specification asked for tick marks on
   * calendar boundaries and got whatever round number the algorithm liked. `"fortnight"` is not a
   * unit `TimeInterval.forUnit` knows, and an interval named by a signal cannot be resolved at the
   * point the axis is built.
   */
  @Test
  fun `a tick interval nothing can read is reported`() {
    val reported =
      diagnostics(
          spec(
            """"signals": [{"name": "grain", "value": "day"}],
               "scales": [{"name": "t", "type": "time",
                 "domain": [{"signal": "datetime(2026, 0, 1)"}, {"signal": "now()"}],
                 "range": "width"}],
               "axes": [{"scale": "t", "orient": "bottom", "tickCount": "fortnight"},
                {"scale": "t", "orient": "top", "tickCount": {"interval": {"signal": "grain"}}}]"""
          )
        )
        .filter { it.code == DiagnosticCodes.PARSE_UNKNOWN_PROPERTY }
        .map { it.message }
    assertEquals(2, reported.size, reported.toString())
    assertTrue(reported.any { "'fortnight'" in it }, reported.toString())
    assertTrue(reported.any { "supplied by a signal" in it }, reported.toString())
  }

  /**
   * A document that declares nothing that draws says so, and a chart does not.
   *
   * `{}` parses. The root is an object, `marks` is absent so it reads as an empty list, and a
   * non-null `VegaSpec` with no marks compiles to a non-null, empty `Scene` — with, until now, no
   * diagnostic anywhere. A host that reads "no diagnostics" as "there is a chart" could not tell an
   * empty placeholder object from a server apart from a chart that drew.
   *
   * Informational, not an error: `{}` is valid Vega and upstream renders it as an empty surface, so
   * anything louder would be this engine disagreeing with the grammar.
   *
   * The negative half is the part that keeps it usable. A guide draws on its own, so two committed
   * fixtures — `log-axis-labels.vg.json`, which carries `"marks": []` and a pair of axes, and
   * `legend-columns.vg.json`, which draws only a legend — must stay silent.
   */
  @Test
  fun `a document that draws nothing says so, and one that draws does not`() {
    val bare = SpecParser().parseJson("{}").diagnostics.single()
    assertEquals(DiagnosticCodes.PARSE_NOTHING_TO_DRAW, bare.code)
    assertEquals(DiagnosticSeverity.INFO, bare.severity)
    assertTrue("the root object is empty" in bare.message, bare.message)

    // A Vega-Lite document handed to the Vega parser: its own keys name the mistake, which is why
    // the message carries them. This case used to produce no diagnostic whatsoever.
    val vegaLite =
      SpecParser()
        .parseJson("""{"mark": "bar", "encoding": {"x": {"field": "a", "type": "nominal"}}}""")
        .diagnostics
        .single { it.code == DiagnosticCodes.PARSE_NOTHING_TO_DRAW }
    assertTrue("mark, encoding" in vegaLite.message, vegaLite.message)

    val drawn =
      listOf(
        // A chart of marks.
        """{"width": 10, "height": 10,
           "marks": [{"type": "rect", "encode": {"update": {"x": {"value": 1}}}}]}""",
        // `log-axis-labels.vg.json`: an empty mark list and a pair of axes.
        """{"width": 10, "height": 10, "marks": [],
           "scales": [{"name": "s", "type": "linear", "domain": [0, 1], "range": "width"}],
           "axes": [{"scale": "s", "orient": "bottom"}]}""",
        // `legend-columns.vg.json`: no marks at all, and a legend.
        """{"width": 10, "height": 10,
           "scales": [{"name": "s", "type": "ordinal", "domain": ["a"], "range": ["#000"]}],
           "legends": [{"fill": "s"}]}""",
        // A title is ink too.
        """{"width": 10, "height": 10, "title": {"text": "T"}}""",
      )
    for (json in drawn) {
      assertEquals(
        emptyList<String>(),
        SpecParser()
          .parseJson(json)
          .diagnostics
          .filter { it.code == DiagnosticCodes.PARSE_NOTHING_TO_DRAW }
          .map { it.message },
        json,
      )
    }
  }

  /**
   * `usermeta` reaches the host instead of being reported at it.
   *
   * It used to be the sole entry in a table of unsupported top-level sections: one `usermeta is
   * ignored` warning per compile, whatever the block held, and no field on `VegaSpec` to hold it.
   * So a document carrying supplementary data for the host — the case upstream's schema describes,
   * "optional metadata that will be passed to Vega" — lost it unconditionally, and all a host
   * learned was that something had gone.
   *
   * The three states are asserted together because they are three different statements: absent is
   * null, `{}` is an empty map, and content is content. A host reading absent and empty as one
   * cannot tell a document that carries no metadata from one whose metadata was filtered to
   * nothing.
   */
  @Test
  fun `usermeta is carried to the host, and a non-object one is reported`() {
    val absent = SpecParser().parseJson(spec(""""width": 100"""))
    assertEquals(null, absent.spec!!.usermeta)
    assertEquals(emptyList<String>(), ignored(spec(""""width": 100""")))

    val empty = SpecParser().parseJson(spec(""""usermeta": {}"""))
    assertEquals(emptyMap<String, VegaValue>(), empty.spec!!.usermeta)
    assertEquals(emptyList<String>(), ignored(spec(""""usermeta": {}""")))

    val carried =
      SpecParser()
        .parseJson(spec(""""usermeta": {"table": [{"c": "a", "v": 1}], "source": "diary"}"""))
    assertEquals(emptyList<String>(), ignored(spec(""""usermeta": {"source": "diary"}""")))
    val meta = carried.spec!!.usermeta!!
    assertEquals(setOf("table", "source"), meta.keys)
    assertEquals(VegaValue.Str("diary"), meta["source"])
    // Nested content arrives whole, which is the point of carrying the value rather than a summary.
    assertEquals(1, (meta["table"] as VegaValue.Arr).values.size)

    // Upstream's type is an object. Anything else cannot be read back by key, so it is reported
    // rather than coerced into something a host would look in and find nothing.
    val wrong = diagnostics(spec(""""usermeta": [1, 2]""")).single()
    assertEquals(DiagnosticCodes.PARSE_UNKNOWN_PROPERTY, wrong.code)
    assertTrue("must be an object" in wrong.message, wrong.message)
    assertEquals(null, SpecParser().parseJson(spec(""""usermeta": [1, 2]""")).spec!!.usermeta)
  }
}
