package dev.aster.vega.runtime.scale

import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The scale arithmetic an interaction reads, against d3's own answers.
 *
 * Every expectation here was taken from `d3-scale` at the pinned version rather than derived: these
 * are the numbers a `brush` turns a pointer into, and being close is not the same as agreeing.
 */
class ScaleAndTickFidelityTest {

  /**
   * `invert` is the inverse of `apply` on a domain of more than two stops.
   *
   * `apply` has been piecewise since a three-stop power scale was found interpolating across both
   * segments; `invert` was not, so the two were not each other's inverse and the gap grew with how
   * unevenly the stops were spread. This is what `invert('s', x())` reads — a brush on a multi-stop
   * log or power axis was selecting a range with the wrong numbers in it, silently.
   */
  @Test
  fun `invert is piecewise on a multi-stop pow or log scale`() {
    val pow =
      PowScale(
        name = "p",
        domain = listOf(4.0, 2.0, 1.0),
        range = listOf(1.0, 2.0, 4.0),
        exponent = 2.0,
      )
    // d3: scalePow().exponent(2).domain([4,2,1]).range([1,2,4])
    assertEquals(3.1666666666666665, pow.apply(1.5), 1e-12)
    assertEquals(1.5811388300841898, pow.invert(3.0), 1e-9)
    assertEquals(3.1622776601683795, pow.invert(1.5), 1e-9)

    val log =
      LogScale(name = "l", domain = listOf(1.0, 10.0, 1000.0), range = listOf(0.0, 50.0, 100.0))
    assertEquals(50.0, log.apply(10.0), 1e-9)
    assertEquals(75.0, log.apply(100.0), 1e-9)
    assertEquals(100.0, log.invert(75.0), 1e-6)
    assertEquals(3.16227766016838, log.invert(25.0), 1e-9)

    // And the round trip, which is the property the two halves owe each other.
    for (value in listOf(1.1, 2.0, 9.9, 10.0, 50.0, 500.0, 999.0)) {
      val back = log.invert(log.apply(value))
      assertTrue(
        abs(back - value) < 1e-6,
        "log.invert(log.apply($value)) was $back",
      )
    }
  }

  /**
   * `nice` returns the domain it was given when it cannot converge, not a half-niced one.
   *
   * d3's `nice` has no iteration cap — its step converges, so `while (true)` terminates — and the
   * cap here is a safety net for an input d3 never sees. It used to write back whatever the loop
   * had reached, which is a domain that is neither what was asked for nor a rounded one, with
   * nothing to say which had happened.
   */
  @Test
  fun `nice answers the input when it cannot converge`() {
    // The ordinary case is unchanged, which is most of what this guards.
    assertEquals(listOf(0.0, 10.0), Ticks.nice(listOf(0.7, 9.3), 10))
    assertEquals(listOf(0.0, 1.0), Ticks.nice(listOf(0.0, 0.96), 10))
    // A degenerate or unusable domain comes back untouched rather than partly rounded.
    val degenerate = listOf(5.0, 5.0)
    assertEquals(degenerate, Ticks.nice(degenerate, 10))
    val infinite = listOf(Double.NEGATIVE_INFINITY, 1.0)
    assertEquals(infinite, Ticks.nice(infinite, 10))
    assertEquals(listOf(1.0, 2.0), Ticks.nice(listOf(1.0, 2.0), 0))
  }

  /**
   * A tick label past 2^63 is the number, not `Long.MAX_VALUE`.
   *
   * `Double.toLong()` **saturates** rather than overflowing, so every value above about 9.2e18
   * printed the identical `9223372036854775807`. A linear axis over a domain that size is unusual
   * and entirely legal, and every one of its labels was the same wrong number with nothing said.
   */
  @Test
  fun `a label past what a Long holds is still the number`() {
    // JavaScript's `toFixed(0)`, which is what `Decimals.fixed` reproduces and what d3-format's
    // integer types use: the *exact* value of the double, not its shortest round-trip spelling.
    assertEquals("9223372036854775808", formatNumber(9.223372036854776E18, 0))
    assertTrue(
      formatNumber(1e19, 0) != "9223372036854775807",
      "1e19 must not print as Long.MAX_VALUE: got ${formatNumber(1e19, 0)}",
    )
    assertEquals("10000000000000000000", formatNumber(1e19, 0))
    assertEquals("-10000000000000000000", formatNumber(-1e19, 0))
    // And the ordinary range is untouched.
    assertEquals("3", formatNumber(2.5, 0))
    assertEquals("0", formatNumber(-0.0, 0))
    assertEquals("-7", formatNumber(-7.0, 0))
  }
}
