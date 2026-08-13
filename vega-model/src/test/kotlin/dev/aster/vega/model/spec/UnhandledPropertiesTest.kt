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
   * explanation for, including one upstream adds later. A tailored explanation wins where it
   * exists, because "what will be drawn instead" is the useful half.
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

    // A tailored explanation, from the one property in the whole inventory that still has one: a
    // title's `encode`, of which only `dx` and `dy` are read.
    val tailored =
      diagnostics(spec(""""title": {"text": "T", "encode": {"title": {"update": {}}}}""")).single {
        it.jsonPath?.endsWith("encode") == true
      }
    assertTrue("dx" in tailored.message, tailored.message)
  }
}
