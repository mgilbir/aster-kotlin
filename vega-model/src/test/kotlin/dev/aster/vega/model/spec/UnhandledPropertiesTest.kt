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
   * Two of upstream's 23 scale properties remain. `domainMin`, `domainMax` and `domainMid` were the
   * ones in wide use, and `bins` was the last one a real example needed; all four are implemented
   * rather than reported.
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
    // `bins` was on this list and is implemented; the two left are genuinely unread.
    assertEquals(listOf("domainImplicit", "domainRaw").sorted(), reported.sorted())
  }

  /**
   * `labelColor`, `labelFont`, `symbolDash` and the legend's own background — `fillColor`,
   * `strokeColor`, `cornerRadius` — are implemented and so do not appear. What remains is
   * `symbolFillColor`, which upstream applies only where the scale supplies no fill of its own, and
   * two layout properties.
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
    assertEquals(
      listOf("symbolFillColor", "clipHeight", "gridAlign").sorted(),
      reported.sorted(),
    )
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
    // `fontWeight`, `font` and `fontStyle` are read now — a theme setting the heading in
    // `config.title` is ordinary, and both the weight and the slant change the *measurement*, not
    // only the look.
    assertEquals(
      listOf("color", "lineHeight", "subtitleColor", "baseline").sorted(),
      reported.sorted(),
    )
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

    val tailored =
      diagnostics(
          spec(
            """"scales": [{"name": "s", "type": "linear", "domain": [0, 1],
                "range": "width", "domainRaw": [1, 2]}]"""
          )
        )
        .single { it.jsonPath?.endsWith("domainRaw") == true }
    assertTrue("resolved domain" in tailored.message, tailored.message)
  }
}
