package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.LinearScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Three things a scale takes from upstream's own arithmetic rather than from d3's.
 *
 * ```js
 * scale.paddingOuter = function(_) { paddingOuter = Math.max(0, Math.min(1, _)); … };
 * ```
 *
 * **Vega's band scale is its own**, not d3's, and it clamps all three paddings to `[0, 1]` where d3
 * clamps only the top of `paddingInner` and leaves `paddingOuter` alone. A chart that writes a
 * padding of 3 gets one whole step of it, not three.
 *
 * ```js
 * if (_.rangeStep != null) { range = configureRangeStep(type, _, count); }
 * ```
 *
 * **`rangeStep`** is `{"range": {"step": …}}` written at the top level, and is still read. Unread,
 * a scale that wrote it had no range at all and was not built.
 *
 * ```js
 * scale.invert = function(y) { … piecewise(range, domain.map(transform), interpolateNumber) … };
 * ```
 *
 * And **invert** is the forward walk with the two swapped, so a scale of more than two stops
 * inverts as readily as it maps — which is what a brush over a diverging axis needs. This answered
 * `NaN`.
 *
 * Every expectation was read off a live upstream view.
 */
class BandPaddingTest {

  private fun scales(json: String) =
    SpecCompiler(VegaHeadlessTextEngine())
      .compileJson(
        """{"width": 100, "height": 20, "padding": 0, "autosize": "none", "scales": [$json]}"""
      )
      .scales

  private fun placed(scale: BandScale) =
    "${scale.scale(VegaValue.Str("a")).asString()} bw=${scale.bandwidth}"

  @Test
  fun `an outer padding above one is one`() {
    val built =
      scales(
        """{"name": "s", "type": "band", "domain": ["a", "b"], "range": [0, 100],
            "paddingOuter": 3}"""
      )
    assertEquals("25 bw=25.0", placed(built.getValue("s") as BandScale))
  }

  @Test
  fun `an inner padding below zero is zero`() {
    val built =
      scales(
        """{"name": "s", "type": "band", "domain": ["a", "b"], "range": [0, 100],
            "paddingInner": -1}"""
      )
    assertEquals("0 bw=50.0", placed(built.getValue("s") as BandScale))
  }

  @Test
  fun `an inner padding above one leaves no band at all`() {
    val built =
      scales(
        """{"name": "s", "type": "band", "domain": ["a", "b"], "range": [0, 100],
            "paddingInner": 2}"""
      )
    assertEquals("0 bw=0.0", placed(built.getValue("s") as BandScale))
  }

  /**
   * **A space between zero and one is divided by, not rounded up to one.**
   *
   * `bandSpace` is `count ? (space > 0 ? space : 1) : 0`, and the only thing it replaces is a space
   * that is *not* above zero. A single band carrying half its width in inner padding has a space of
   * `0.5`, which upstream divides a 200-wide range by to reach a step of 400 and a band that fills
   * the range; `maxOf(1.0, space)` rounds the divisor up instead and draws it half as wide. Read
   * off a live upstream view at both paddings below.
   */
  @Test
  fun `a space below one still divides the range`() {
    val half =
      scales(
        """{"name": "s", "type": "band", "domain": ["a"], "range": [0, 200],
            "paddingInner": 0.5}"""
      )
    assertEquals("0 bw=200.0", placed(half.getValue("s") as BandScale))
    assertEquals(400.0, (half.getValue("s") as BandScale).step, 1e-9)

    val most =
      scales(
        """{"name": "s", "type": "band", "domain": ["a"], "range": [0, 200],
            "paddingInner": 0.8}"""
      )
    assertEquals("0 bw=200.0", placed(most.getValue("s") as BandScale))
    assertEquals(1000.0, (most.getValue("s") as BandScale).step, 1e-9)
  }

  /**
   * **A padding nothing can read is NaN, and only the step survives it.**
   *
   * Upstream clamps with `Math.max(0, Math.min(1, _))`, which answers NaN for a word, and then
   * `bandSpace`'s `space > 0` is false so the step divides by one and becomes the whole range.
   * Bandwidth and every position go NaN. This engine discarded the NaN and drew an ordinary chart.
   * Probed: `padding: "wide"` over four bands in a 200-wide range gives step 200, bandwidth NaN.
   */
  @Test
  fun `a padding that is not a number leaves the bands unplaceable`() {
    val built =
      SpecCompiler(VegaHeadlessTextEngine())
        .compileJson(
          """
          {"width": 200, "height": 20, "padding": 0, "autosize": "none",
           "signals": [{"name": "gap", "value": "wide"}],
           "scales": [{"name": "s", "type": "band", "domain": ["a", "b", "c", "d"],
             "range": [0, 200], "padding": {"signal": "gap"}}]}
          """
            .trimIndent()
        )
        .scales
    val scale = built.getValue("s") as BandScale
    assertEquals(200.0, scale.step, 1e-9)
    assertTrue(scale.bandwidth.isNaN(), "bandwidth should be NaN, was ${scale.bandwidth}")
    assertEquals("undefined", scale.scale(VegaValue.Str("a")).asString())
  }

  /** `rangeStep` is the older spelling of `range: {step: …}`, and means the same thing. */
  @Test
  fun `a top-level rangeStep sizes the range`() {
    val built =
      scales(
        """{"name": "a", "type": "band", "domain": ["a", "b", "c"], "rangeStep": 20},
           {"name": "b", "type": "band", "domain": ["a", "b", "c"], "range": {"step": 20}},
           {"name": "c", "type": "point", "domain": ["a", "b", "c"], "rangeStep": 20}"""
      )
    for (name in listOf("a", "b", "c")) {
      assertEquals(
        listOf("0", "20", "40"),
        listOf("a", "b", "c").map { built.getValue(name).scale(VegaValue.Str(it)).asString() },
        "scale $name",
      )
    }
  }

  /** And a three-stop scale inverts, the repeated stop handing its position to the later piece. */
  @Test
  fun `a piecewise scale inverts`() {
    val built =
      scales("""{"name": "m", "type": "linear", "domain": [0, 0, 100], "range": [0, 50, 100]}""")
    val scale = built.getValue("m") as LinearScale
    assertEquals(
      listOf(0.0, 0.0, 0.0, 50.0, 100.0),
      listOf(0.0, 25.0, 50.0, 75.0, 100.0).map { scale.invert(it) },
    )
  }
}
