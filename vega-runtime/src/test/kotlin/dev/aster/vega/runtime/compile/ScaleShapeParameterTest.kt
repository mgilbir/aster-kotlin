package dev.aster.vega.runtime.compile

import dev.aster.vega.model.VegaValue
import dev.aster.vega.runtime.scale.LogScale
import dev.aster.vega.runtime.scale.PowScale
import dev.aster.vega.runtime.scale.SymlogScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `base`, `exponent` and `constant` are **coerced, not validated**.
 *
 * Upstream's scale transform walks its parameters and calls a setter per key — `scale[key](_[key])`
 * — and d3's setters for these three are `base = +_`, `exponent = +_` and `constant = +_`. A word
 * becomes NaN and stays on the scale. This engine read all three with the reader that *discards* a
 * NaN and fell back on the default, so a chart upstream cannot draw was drawn as though nothing
 * were wrong.
 *
 * Two of the three are pinned here rather than only in `a-scale-parameter-that-is-not-a-number`,
 * and deliberately so: their only scene-visible effect is a mark position of NaN, and until the
 * differential's non-finite gap is closed a reference holding `"NaN"` agrees with any number this
 * engine produces. Reverted, the fixture caught `base` and neither of the other two. These catch
 * them. See the STATUS entry on the box that cannot be placed.
 *
 * Every expectation was read off a live upstream view.
 */
class ScaleShapeParameterTest {

  private fun scales(scale: String) =
    SpecCompiler()
      .compileJson(
        """
        {"width": 120, "height": 40, "padding": 0, "autosize": "none",
         "signals": [{"name": "word", "value": "wide"}],
         "scales": [$scale]}
        """
          .trimIndent()
      )
      .scales

  private fun at(name: String, scale: String, v: Double): Double =
    scales(scale).getValue(name).scale(VegaValue.Num(v)).asDoubleOrNull() ?: Double.NaN

  private fun VegaValue.asDoubleOrNull(): Double? = (this as? VegaValue.Num)?.value

  /** `pow.exponent = "wide"` poisons the mapping: upstream's `scale(4)` is NaN, not 4.8. */
  @Test
  fun `an exponent that is not a number leaves the scale unable to place anything`() {
    val s =
      scales(
          """{"name": "p", "type": "pow", "domain": [1, 100], "range": [0, 120],
              "exponent": {"signal": "word"}}"""
        )
        .getValue("p") as PowScale
    assertTrue(s.exponent.isNaN(), "exponent should be NaN, was ${s.exponent}")
    assertTrue(
      at(
          "p",
          """{"name": "p", "type": "pow", "domain": [1, 100], "range": [0, 120],
        "exponent": {"signal": "word"}}""",
          4.0,
        )
        .isNaN()
    )
    // The control: a real exponent still maps, and upstream puts 4 at 0.192 with exponent 2.
    assertEquals(
      0.192,
      at(
        "p",
        """{"name": "p", "type": "pow", "domain": [1, 100], "range": [0, 120],
          "exponent": 2}""",
        4.0,
      ),
      1e-9,
    )
  }

  /**
   * The same for `symlog.constant`; upstream's `scale(4)` is NaN where a constant of 2 gives 23.59.
   */
  @Test
  fun `a constant that is not a number leaves the scale unable to place anything`() {
    val s =
      scales(
          """{"name": "s", "type": "symlog", "domain": [1, 100], "range": [0, 120],
              "constant": {"signal": "word"}}"""
        )
        .getValue("s") as SymlogScale
    assertTrue(s.constant.isNaN(), "constant should be NaN, was ${s.constant}")
    assertTrue(
      at(
          "s",
          """{"name": "s", "type": "symlog", "domain": [1, 100], "range": [0, 120],
        "constant": {"signal": "word"}}""",
          4.0,
        )
        .isNaN()
    )
    assertEquals(
      23.5873959,
      at(
        "s",
        """{"name": "s", "type": "symlog", "domain": [1, 100], "range": [0, 120],
          "constant": 2}""",
        4.0,
      ),
      1e-6,
    )
  }

  /**
   * `log.base` is the odd one: it never touches the mapping, only the **ticks**.
   *
   * `scale(4)` is 36.12 with a base of 10, of 2 or of NaN, because d3 normalises `log(x)` and the
   * base reaches only the tick generator. With a NaN base upstream produces **no ticks at all**,
   * where base 10 gives nineteen over `[1, 100]` and base 2 gives seven.
   */
  @Test
  fun `a base that is not a number leaves the scale with no ticks`() {
    val bad =
      scales(
          """{"name": "l", "type": "log", "domain": [1, 100], "range": [0, 120],
              "base": {"signal": "word"}}"""
        )
        .getValue("l") as LogScale
    assertTrue(bad.base.isNaN(), "base should be NaN, was ${bad.base}")
    assertEquals(emptyList<Double>(), bad.ticks(5), "an unreadable base generates no ticks")
    // The mapping is untouched, which is what makes the ticks the only place this shows.
    assertEquals(
      36.1235994,
      at(
        "l",
        """{"name": "l", "type": "log", "domain": [1, 100],
        "range": [0, 120], "base": {"signal": "word"}}""",
        4.0,
      ),
      1e-6,
    )

    val good =
      scales("""{"name": "l", "type": "log", "domain": [1, 100], "range": [0, 120], "base": 2}""")
        .getValue("l") as LogScale
    assertEquals(listOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0), good.ticks(5))
  }
}
