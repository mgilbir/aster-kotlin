package dev.aster.vega.runtime.scale

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * d3's tick algorithm, run on every target.
 *
 * Where an axis puts its labels is decided by `log10`, `pow`, a `round` and a division, and the
 * answer is expected to be *exactly* d3's — `TicksTest` on the JVM compares against upstream's own
 * output for a hundred domains. That arithmetic is common Kotlin, but `kotlin.math` is backed by
 * the platform's own library on each target, so "identical on the JVM" does not by itself mean
 * identical on Kotlin/Native. This runs the cases that would move a label if anything drifted.
 *
 * The expectations are upstream's, not this implementation's: they are the same values `TicksTest`
 * asserts, which were generated from d3 with `oracle-js/src/scaleprobe.mjs`.
 */
class TicksCommonTest {

  @Test
  fun `the tick increment is d3's`() {
    assertEquals(2.0, Ticks.tickIncrement(0.0, 10.0, 5))
    assertEquals(1.0, Ticks.tickIncrement(0.0, 10.0, 10))
    // A step below one is reported as a **negative divisor**, not as the step: d3 returns -2 for a
    // step of a half and -5 for a step of a fifth, so that the caller divides instead of
    // multiplying
    // by a number it cannot represent. Read out of d3-array rather than assumed — the first draft
    // of
    // this test asserted 0.5 and the native run is what caught it.
    assertEquals(-2.0, Ticks.tickIncrement(0.0, 1.0, 2))
    assertEquals(-5.0, Ticks.tickIncrement(0.0, 1.0, 5))
    assertEquals(Double.NEGATIVE_INFINITY, Ticks.tickIncrement(1.0, 1.0, 10))
    assertEquals(Double.NEGATIVE_INFINITY, Ticks.tickIncrement(0.0, Double.NaN, 10))
  }

  /**
   * The reason ticks are computed rather than accumulated: adding a step repeatedly drifts, and a
   * label reading `0.30000000000000004` is a rendering bug with an arithmetic cause.
   */
  @Test
  fun `a tenth-step tick is exact rather than accumulated`() {
    val values = Ticks.ticks(0.0, 1.0, 10)
    assertEquals(11, values.size)
    assertEquals(0.3, values[3], "computed, not summed")
    assertTrue(0.1 + 0.2 != values[3], "the naive sum drifts and this does not")
    assertEquals(1.0, values.last())
  }

  @Test
  fun `ticks over a decade and a negative domain match d3`() {
    assertEquals(listOf(0.0, 2.0, 4.0, 6.0, 8.0, 10.0), Ticks.ticks(0.0, 10.0, 5))
    assertEquals(listOf(-10.0, -5.0, 0.0, 5.0, 10.0), Ticks.ticks(-10.0, 10.0, 4))
    assertEquals(emptyList(), Ticks.ticks(1.0, 1.0, 0), "no ticks were asked for")
  }

  @Test
  fun `nice rounds a domain outwards as an axis needs`() {
    assertEquals(listOf(0.0, 10.0), Ticks.nice(listOf(0.3, 9.7), 10))
    assertEquals(listOf(-10.0, 10.0), Ticks.nice(listOf(-9.1, 9.1), 5))
    assertEquals(listOf(1.0, 1.0), Ticks.nice(listOf(1.0, 1.0), 10), "a point stays a point")
  }

  /** The step a slider or a label format is derived from, and the digits it implies. */
  @Test
  fun `a step and its precision follow the increment`() {
    assertEquals(0.2, Ticks.stepFrom(-5.0), "a negative increment is a divisor")
    assertEquals(5.0, Ticks.stepFrom(5.0))
    assertEquals(1, Ticks.precisionForStep(0.2))
    assertEquals(0, Ticks.precisionForStep(5.0))
  }
}
