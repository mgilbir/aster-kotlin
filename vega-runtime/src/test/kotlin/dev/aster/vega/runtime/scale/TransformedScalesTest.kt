package dev.aster.vega.runtime.scale

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Log, power, sqrt and symlog scales.
 *
 * Every expected value was read off upstream Vega running the same scale definition, so these are
 * reference vectors rather than restatements of the implementation.
 */
class TransformedScalesTest {

  private val tolerance = 1e-6

  // ---- log ------------------------------------------------------------------

  @Test
  fun `log scale spaces powers evenly`() {
    val scale = LogScale("s", listOf(1.0, 1000.0), listOf(0.0, 100.0))
    assertEquals(0.0, scale.apply(1.0), tolerance)
    assertEquals(33.333333, scale.apply(10.0), tolerance)
    assertEquals(66.666667, scale.apply(100.0), tolerance)
    assertEquals(100.0, scale.apply(1000.0), tolerance)
  }

  @Test
  fun `log base is configurable`() {
    val scale = LogScale("s", listOf(1.0, 8.0), listOf(0.0, 100.0), base = 2.0)
    assertEquals(0.0, scale.apply(1.0), tolerance)
    assertEquals(33.333333, scale.apply(2.0), tolerance)
    assertEquals(66.666667, scale.apply(4.0), tolerance)
    assertEquals(100.0, scale.apply(8.0), tolerance)
  }

  @Test
  fun `log ticks are the powers and their multiples`() {
    val scale = LogScale("s", listOf(1.0, 1000.0), listOf(0.0, 100.0))
    val ticks = scale.ticks()
    assertEquals(
      listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 20.0, 30.0),
      ticks.take(12),
    )
  }

  @Test
  fun `sparse log ticks fall back to linear spacing, as d3 does`() {
    // Base 2 over [1, 8] would give only 1, 2, 4, 8 — fewer than half the requested count — so d3
    // abandons log spacing entirely. Verified against upstream.
    val scale = LogScale("s", listOf(1.0, 8.0), listOf(0.0, 100.0), base = 2.0)
    assertEquals(listOf(1.0, 1.5, 2.0, 2.5), scale.ticks().take(4))
  }

  @Test
  fun `log nice snaps to enclosing powers`() {
    assertEquals(listOf(1.0, 1000.0), Ticks.niceLog(listOf(3.0, 700.0), 10.0))
    assertEquals(listOf(1.0, 8.0), Ticks.niceLog(listOf(1.5, 7.0), 2.0))
  }

  @Test
  fun `a log domain spanning zero is invalid rather than silently adjusted`() {
    assertFalse(LogScale("s", listOf(-10.0, 10.0), listOf(0.0, 1.0)).isValid)
    assertFalse(LogScale("s", listOf(0.0, 10.0), listOf(0.0, 1.0)).isValid)
    assertTrue(LogScale("s", listOf(1.0, 10.0), listOf(0.0, 1.0)).isValid)
    assertTrue(LogScale("s", listOf(-100.0, -1.0), listOf(0.0, 1.0)).isValid)
  }

  @Test
  fun `an invalid log domain maps everything to NaN`() {
    val scale = LogScale("s", listOf(-10.0, 10.0), listOf(0.0, 1.0))
    assertTrue(scale.apply(5.0).isNaN())
  }

  @Test
  fun `a negative log domain preserves ordering`() {
    val scale = LogScale("s", listOf(-100.0, -1.0), listOf(0.0, 100.0))
    assertEquals(0.0, scale.apply(-100.0), tolerance)
    assertEquals(100.0, scale.apply(-1.0), tolerance)
    assertEquals(50.0, scale.apply(-10.0), tolerance)
  }

  @Test
  fun `log invert round-trips`() {
    val scale = LogScale("s", listOf(1.0, 1000.0), listOf(0.0, 100.0))
    assertEquals(42.0, scale.invert(scale.apply(42.0)), 1e-9)
  }

  // ---- pow and sqrt ---------------------------------------------------------

  @Test
  fun `pow squares the domain`() {
    val scale = PowScale("s", listOf(0.0, 10.0), listOf(0.0, 100.0), exponent = 2.0)
    assertEquals(0.0, scale.apply(0.0), tolerance)
    assertEquals(25.0, scale.apply(5.0), tolerance)
    assertEquals(100.0, scale.apply(10.0), tolerance)
  }

  @Test
  fun `a pow scale with no exponent is linear`() {
    // Upstream's default exponent is 1, so an unparameterized pow scale does nothing.
    val scale = PowScale("s", listOf(0.0, 10.0), listOf(0.0, 100.0))
    assertEquals(50.0, scale.apply(5.0), tolerance)
  }

  @Test
  fun `sqrt is a pow scale with exponent one half`() {
    val scale = PowScale("s", listOf(0.0, 100.0), listOf(0.0, 100.0), exponent = 0.5)
    assertEquals(0.0, scale.apply(0.0), tolerance)
    assertEquals(50.0, scale.apply(25.0), tolerance)
    assertEquals(100.0, scale.apply(100.0), tolerance)
  }

  @Test
  fun `pow handles a negative domain by sign`() {
    val scale = PowScale("s", listOf(-10.0, 10.0), listOf(0.0, 100.0), exponent = 2.0)
    assertEquals(0.0, scale.apply(-10.0), tolerance)
    assertEquals(50.0, scale.apply(0.0), tolerance)
    assertEquals(100.0, scale.apply(10.0), tolerance)
  }

  @Test
  fun `pow ticks are linear on the domain`() {
    val scale = PowScale("s", listOf(0.0, 10.0), listOf(0.0, 100.0), exponent = 2.0)
    assertEquals((0..10).map { it.toDouble() }, scale.ticks())
  }

  @Test
  fun `pow invert round-trips`() {
    val scale = PowScale("s", listOf(0.0, 100.0), listOf(0.0, 100.0), exponent = 0.5)
    assertEquals(37.0, scale.invert(scale.apply(37.0)), 1e-9)
  }

  // ---- symlog ---------------------------------------------------------------

  @Test
  fun `symlog handles zero and both signs`() {
    // Upstream over [-100, 100]: -1 lands at 42.490476 and 0 exactly at the midpoint.
    val scale = SymlogScale("s", listOf(-100.0, 100.0), listOf(0.0, 100.0))
    assertEquals(0.0, scale.apply(-100.0), tolerance)
    assertEquals(42.490476, scale.apply(-1.0), tolerance)
    assertEquals(50.0, scale.apply(0.0), tolerance)
    assertEquals(57.509524, scale.apply(1.0), tolerance)
    assertEquals(100.0, scale.apply(100.0), tolerance)
  }

  @Test
  fun `the symlog constant flattens the curve near zero`() {
    val scale = SymlogScale("s", listOf(-100.0, 100.0), listOf(0.0, 100.0), constant = 10.0)
    assertEquals(0.0, scale.apply(-100.0), tolerance)
    assertEquals(50.0, scale.apply(0.0), tolerance)
    assertEquals(100.0, scale.apply(100.0), tolerance)
    // A larger constant pulls small magnitudes closer to the midpoint than the default would.
    val default = SymlogScale("s", listOf(-100.0, 100.0), listOf(0.0, 100.0))
    assertTrue(scale.apply(1.0) < default.apply(1.0))
  }

  @Test
  fun `symlog invert round-trips across zero`() {
    val scale = SymlogScale("s", listOf(-100.0, 100.0), listOf(0.0, 100.0))
    assertEquals(-7.0, scale.invert(scale.apply(-7.0)), 1e-9)
    assertEquals(0.0, scale.invert(scale.apply(0.0)), 1e-9)
    assertEquals(7.0, scale.invert(scale.apply(7.0)), 1e-9)
  }

  // ---- shared behaviour -----------------------------------------------------

  @Test
  fun `clamping applies in domain space, not transformed space`() {
    val scale = LogScale("s", listOf(1.0, 100.0), listOf(0.0, 100.0), clamp = true)
    assertEquals(0.0, scale.apply(0.5), tolerance)
    assertEquals(100.0, scale.apply(1000.0), tolerance)
  }

  @Test
  fun `a zero-extent domain returns the range midpoint`() {
    val scale = PowScale("s", listOf(5.0, 5.0), listOf(0.0, 100.0), exponent = 2.0)
    assertEquals(50.0, scale.apply(5.0), tolerance)
  }

  @Test
  fun `too few domain or range values is rejected`() {
    org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
      PowScale("s", listOf(1.0), listOf(0.0, 1.0))
    }
    org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
      SymlogScale("s", listOf(0.0, 1.0), listOf(0.0))
    }
  }

  @Test
  fun `a reversed range flips the output`() {
    val scale = LogScale("s", listOf(1.0, 100.0), listOf(100.0, 0.0))
    assertEquals(100.0, scale.apply(1.0), tolerance)
    assertEquals(0.0, scale.apply(100.0), tolerance)
  }
}
