package dev.aster.vega.runtime.scale

import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A scale whose **transform** runs the domain backwards, which a negative `pow` exponent does.
 *
 * ```js
 * if (d1 < d0) d0 = normalize(d1, d0), r0 = interpolate(r1, r0);
 * else        d0 = normalize(d0, d1), r0 = interpolate(r0, r1);
 * ```
 *
 * d3 orders the *transformed* ends before normalizing and swaps the range ends to match. For finite
 * ends the two orders are the same number — `(x - d1) / (d0 - d1)` is `1 - (x - d0) / (d1 - d0)`,
 * and swapping the range undoes the `1 -`. They stop being the same once an end is infinite,
 * because the algebra that makes them equal divides infinity by infinity.
 *
 * `transformPow` is `x < 0 ? -pow(-x, e) : pow(x, e)`, so `pow(0, -4)` is `Infinity` and a domain
 * starting at zero transforms to a downward one. Taken in the written order every value answers
 * `NaN`; ordered as d3 orders it, only the value whose own transform is the infinity does.
 *
 * Every expectation here was read off d3 directly at `domain([0, 95]).range([120, 0])`.
 */
class TransformedDomainOrderTest {

  private fun pow(exponent: Double) =
    PowScale("s", listOf(0.0, 95.0), listOf(120.0, 0.0), clamp = false, exponent = exponent)

  private fun at(exponent: Double, x: Double) = pow(exponent).position(VegaValue.Num(x))

  @Test
  fun `a negative exponent places every value but the one whose transform is infinite`() {
    assertTrue(at(-4.0, 0.0).isNaN(), "pow(0, -4) is Infinity, and only that value is lost")
    assertEquals(0.0, at(-4.0, 8.0), 1e-9)
    assertEquals(0.0, at(-4.0, 95.0), 1e-9)
  }

  /** The ordinary exponents are untouched, which is what says the ordering costs nothing. */
  @Test
  fun `an ordinary exponent is unchanged`() {
    assertEquals(120.0, at(0.5, 0.0), 1e-9)
    assertEquals(85.17713999471944, at(0.5, 8.0), 1e-9)
    assertEquals(0.0, at(0.5, 95.0), 1e-9)

    assertEquals(120.0, at(8.0, 0.0), 1e-9)
    assertEquals(119.99999969653241, at(8.0, 8.0), 1e-6)
    assertEquals(0.0, at(8.0, 95.0), 1e-9)
  }

  /**
   * An exponent of zero transforms every value to 1, so the span is zero and `normalize` answers
   * `constant(0.5)` — the midpoint of the range, for every input.
   */
  @Test
  fun `a zero exponent puts everything at the midpoint`() {
    assertEquals(60.0, at(0.0, 0.0), 1e-9)
    assertEquals(60.0, at(0.0, 8.0), 1e-9)
    assertEquals(60.0, at(0.0, 95.0), 1e-9)
  }

  /**
   * `normalize` answers **two** different things for a span it cannot divide by: the midpoint when
   * the span is zero, and `NaN` when the span is not a number. Testing `d0 == d1` catches only the
   * first, so a domain of two infinite transformed ends took the midpoint and produced a real
   * number where d3 produces none.
   */
  @Test
  fun `a span that is not a number is not the midpoint`() {
    assertEquals(60.0, bimap(1.0, 1.0, 120.0, 0.0, 1.0), 1e-9)
    assertTrue(
      bimap(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, 120.0, 0.0, 1.0).isNaN(),
      "two infinite ends leave no span to divide by",
    )
  }
}
