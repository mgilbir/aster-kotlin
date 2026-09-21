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

/**
 * `symlogForward` and `symlogBackward` on their own, below the scale that uses them.
 *
 * Two of d3's three decisions in that one line are observable through a chart and pinned by
 * `a-symlog-is-log1p-of-x-over-c`: `log1p` rather than `ln(1 + t)`, which moves the last two
 * digits, and `abs(x / c)` rather than `abs(x) / c`, which decides a negative constant. The third —
 * `Math.sign`, which is **zero at zero** and so carries the sign of a negative zero through — is
 * not. A scale normalizes the transform's answer against its domain immediately, and that
 * arithmetic absorbs a `-0` before anything can look at it, so a mutant that branches on the sign
 * instead of multiplying by it survives every fixture. Searched for a chart that could see it and
 * there is none, which makes this the honest place to pin it rather than a gap to leave open.
 */
class SymlogTransformTest {

  @Test
  fun `the sign is multiplied in, so a negative zero keeps its sign`() {
    // `Math.sign(-0) * Math.log1p(0)` is `-0 * 0`, which is `-0`. A branch on `value < 0` answers
    // `+0`, because `-0 < 0` is false.
    assertTrue(1.0 / symlogForward(-0.0, 1.0) < 0.0, "symlog(-0) should be -0")
    assertTrue(1.0 / symlogForward(0.0, 1.0) > 0.0, "symlog(0) should be +0")
    assertTrue(1.0 / symlogBackward(-0.0, 1.0) < 0.0, "symexp(-0) should be -0")
  }

  @Test
  fun `log1p and not ln of one plus`() {
    // The two differ in the last bits for a small argument, which is the whole of the defect this
    // replaced. Read off `node`: `Math.sign(0.2) * Math.log1p(Math.abs(0.2 / 1))`.
    assertEquals(0.18232155679395462, symlogForward(0.2, 1.0))
    assertEquals(0.09531017980432487, symlogForward(0.1, 1.0))
  }

  @Test
  fun `the constant divides before the absolute, so a negative one still has a logarithm`() {
    // `Math.sign(8) * Math.log1p(Math.abs(8 / -2))` is `log1p(4)`; `ln1p(abs(8) / -2)` is NaN.
    assertEquals(kotlin.math.ln(5.0), symlogForward(8.0, -2.0), 1e-12)
    assertEquals(-kotlin.math.ln(5.0), symlogForward(-8.0, -2.0), 1e-12)
  }

  @Test
  fun `the inverse undoes the transform`() {
    for (x in listOf(-40.0, -1.0, 0.0, 0.25, 3.0, 1e6)) {
      assertEquals(x, symlogBackward(symlogForward(x, 7.5), 7.5), 1e-9)
    }
  }
}
