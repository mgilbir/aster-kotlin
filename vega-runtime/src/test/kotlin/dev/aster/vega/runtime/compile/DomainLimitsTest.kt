package dev.aster.vega.runtime.compile

import dev.aster.vega.runtime.scale.LinearScale
import dev.aster.vega.runtime.scale.PowScale
import dev.aster.vega.runtime.scale.SymlogScale
import dev.aster.vega.runtime.scale.TransformedScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `zero`, `domainMin`, `domainMax` and `domainMid` on a continuous scale.
 *
 * Every expected domain was read out of upstream — `view.scale('s').domain()` over the data below —
 * and then checked against `configureDomain` in `vega-encode/src/Scale.js`, because two of these
 * are not what the property names suggest:
 *
 * - `zero` keys off the **scale type**, through upstream's `includeZero`, which lists linear, pow
 *   and sqrt and nothing else. It is not about whether the domain came from the data: a linear
 *   scale handed `[10, 20]` still starts at 0. This engine skipped `zero` entirely for a
 *   written-out domain, so a chart with an explicit `[10, 100]` drew its bars from the wrong
 *   baseline.
 * - `domainMin`/`domainMax` **replace** an end rather than clamping it, and they run after `zero`.
 *   So `domainMin: 30` beats the zero that would otherwise have pulled the domain down, and
 *   `domainMin: 200` against data reaching 91 leaves the domain running backwards — upstream does
 *   not correct that, and neither does this.
 */
class DomainLimitsTest {

  private fun compile(scale: String) =
    SpecCompiler()
      .compileJson(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "data": [{"name": "t", "values": [{"v": 19}, {"v": 91}]}],
          "scales": [{"name": "s", "range": "width", $scale}]
        }
        """
          .trimIndent()
      )

  private fun domainOf(scale: String): List<Double> =
    when (val built = compile(scale).scales["s"]) {
      is LinearScale -> built.domain
      is PowScale -> built.domain
      is SymlogScale -> built.domain
      is TransformedScale -> built.domain
      else -> error("not a continuous scale: $built")
    }

  private val fromData = """"domain": {"data": "t", "field": "v"}"""

  // ---- zero -----------------------------------------------------------------

  @Test
  fun `zero applies to a linear scale whether or not the domain was written out`() {
    assertEquals(listOf(0.0, 91.0), domainOf(""""type": "linear", $fromData"""))
    assertEquals(listOf(19.0, 91.0), domainOf(""""type": "linear", "zero": false, $fromData"""))
    // The one this engine had wrong: an explicit domain is not exempt.
    assertEquals(listOf(0.0, 20.0), domainOf(""""type": "linear", "domain": [10, 20]"""))
    assertEquals(
      listOf(10.0, 20.0),
      domainOf(""""type": "linear", "domain": [10, 20], "zero": false"""),
    )
  }

  /**
   * Upstream's `includeZero` lists linear, pow and sqrt. Symlog is not on it, and log cannot be.
   */
  @Test
  fun `zero follows the scale type`() {
    assertEquals(listOf(0.0, 91.0), domainOf(""""type": "pow", "exponent": 2, $fromData"""))
    assertEquals(listOf(0.0, 91.0), domainOf(""""type": "sqrt", $fromData"""))
    assertEquals(listOf(19.0, 91.0), domainOf(""""type": "symlog", $fromData"""))
    assertEquals(listOf(19.0, 91.0), domainOf(""""type": "log", $fromData"""))
  }

  /** A linear scale with a colour range becomes `sequential-linear` upstream, which zero skips. */
  @Test
  fun `a colour scale does not include zero`() {
    val scale = compile(""""type": "linear", "range": ["#eeeeee", "#333333"], $fromData""")
    assertEquals(
      listOf(19.0, 91.0),
      (scale.scales["s"] as dev.aster.vega.runtime.scale.SequentialColorScale).domain,
    )
  }

  // ---- the explicit limits --------------------------------------------------

  @Test
  fun `domainMin and domainMax replace an end rather than clamping it`() {
    assertEquals(listOf(0.0, 91.0), domainOf(""""type": "linear", "domainMin": 0, $fromData"""))
    assertEquals(listOf(0.0, 100.0), domainOf(""""type": "linear", "domainMax": 100, $fromData"""))
    assertEquals(listOf(0.0, 50.0), domainOf(""""type": "linear", "domainMax": 50, $fromData"""))
    assertEquals(
      listOf(0.0, 100.0),
      domainOf(""""type": "linear", "domainMin": 0, "domainMax": 100, $fromData"""),
    )
  }

  /** They run after `zero`, so a minimum above zero wins rather than being pulled back down. */
  @Test
  fun `an explicit minimum beats the zero that would otherwise apply`() {
    assertEquals(listOf(30.0, 91.0), domainOf(""""type": "linear", "domainMin": 30, $fromData"""))
    assertEquals(
      listOf(30.0, 91.0),
      domainOf(""""type": "linear", "domainMin": 30, "zero": false, $fromData"""),
    )
  }

  /** Upstream leaves a backwards domain backwards, and a chart drawn from one is upside down. */
  @Test
  fun `a minimum above the maximum is left alone`() {
    assertEquals(listOf(200.0, 91.0), domainOf(""""type": "linear", "domainMin": 200, $fromData"""))
    assertEquals(listOf(0.0, 5.0), domainOf(""""type": "linear", "domainMax": 5, $fromData"""))
  }

  /** `nice` runs last, so it widens whatever the limits left behind. */
  @Test
  fun `nice rounds the domain the limits produced`() {
    assertEquals(
      listOf(30.0, 95.0),
      domainOf(""""type": "linear", "domainMin": 30, "nice": true, $fromData"""),
    )
    assertEquals(
      listOf(0.0, 100.0),
      domainOf(""""type": "linear", "domainMin": 3, "domainMax": 97, "nice": true, $fromData"""),
    )
    assertEquals(
      listOf(30.0, 60.0),
      domainOf(""""type": "linear", "domainMin": 30, "domainMax": 60, "nice": true, $fromData"""),
    )
  }

  @Test
  fun `an explicit limit may come from a signal`() {
    val compiled =
      SpecCompiler()
        .compileJson(
          """
          {
            "width": 100, "height": 100, "padding": 0,
            "signals": [{"name": "cap", "value": 120}],
            "data": [{"name": "t", "values": [{"v": 19}, {"v": 91}]}],
            "scales": [{"name": "s", "type": "linear", "range": "width",
              "domain": {"data": "t", "field": "v"}, "domainMax": {"signal": "cap"}}]
          }
          """
            .trimIndent()
        )
    assertEquals(listOf(0.0, 120.0), (compiled.scales["s"] as LinearScale).domain)
  }

  /** A three-point domain for a diverging range; upstream inserts it before the last value. */
  @Test
  fun `domainMid inserts a midpoint`() {
    assertEquals(
      listOf(0.0, 50.0, 91.0),
      domainOf(""""type": "linear", "domainMid": 50, "range": [0, 50, 100], $fromData"""),
    )
  }
}
