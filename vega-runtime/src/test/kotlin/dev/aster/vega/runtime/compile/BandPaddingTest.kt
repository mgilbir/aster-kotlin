package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.LinearScale
import org.junit.jupiter.api.Assertions.assertEquals
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
