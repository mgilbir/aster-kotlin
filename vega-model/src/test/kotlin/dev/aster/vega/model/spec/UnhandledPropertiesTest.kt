package dev.aster.vega.model.spec

import dev.aster.vega.model.DiagnosticCodes
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

  private fun spec(body: String) =
    """
    {
      "width": 100, "height": 60,
      "data": [{"name": "t", "values": [{"c": "a", "v": 1}]}],
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
}
