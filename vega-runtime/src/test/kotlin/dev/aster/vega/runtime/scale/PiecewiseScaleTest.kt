package dev.aster.vega.runtime.scale

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A continuous scale with **more than two stops**, and which segment a value on a stop belongs to.
 *
 * ```js
 * const i = bisect(domain, x, 1, j) - 1;
 * return r[i](d[i](x));
 * ```
 *
 * `bisect` is a **right** bisection, so a value sitting exactly on an interior stop belongs to the
 * segment that *starts* there rather than to the one that ends there. The two readings agree
 * everywhere except where a stop is repeated — and that is not a curiosity: it is what `domainMid`
 * builds whenever the middle lands on an end of the data. A diverging bar chart over values that
 * happen to be all positive has the domain `[0, 0, 95.4]`, and zero has to land on the middle of
 * the range, which is where the chart's baseline is drawn. Reading it as the *first* segment made
 * the zero the midpoint of a degenerate piece, and every bar started a quarter of the way across.
 *
 * Every expectation is `d3.scaleLinear()` on the same domain and range.
 */
class PiecewiseScaleTest {

  private fun scaled(domain: List<Double>, range: List<Double>, inputs: List<Double>): String =
    LinearScale("s", domain, range).let { scale ->
      inputs.joinToString(" ") { "$it:${trim(scale.apply(it))}" }
    }

  /** Four decimals, which is where d3's own printing was compared. */
  private fun trim(value: Double): String {
    val rounded = kotlin.math.round(value * 10000.0) / 10000.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString()
    else rounded.toString()
  }

  /** The shape `domainMid` builds over all-positive data: the middle stop repeats the first. */
  @Test
  fun `a value on a repeated stop takes the segment that starts there`() {
    assertEquals(
      "-10.0:21 0.0:42 10.0:46.4025 95.4:84 100.0:86.0252",
      scaled(
        listOf(0.0, 0.0, 95.4),
        listOf(0.0, 42.0, 84.0),
        listOf(-10.0, 0.0, 10.0, 95.4, 100.0),
      ),
    )
  }

  /** An ordinary diverging domain, where the reading makes no difference and must not move. */
  @Test
  fun `a three-stop domain interpolates each half separately`() {
    assertEquals(
      "-50.0:0 -25.0:21 0.0:42 50.0:63 100.0:84",
      scaled(
        listOf(-50.0, 0.0, 100.0),
        listOf(0.0, 42.0, 84.0),
        listOf(-50.0, -25.0, 0.0, 50.0, 100.0),
      ),
    )
  }

  /** And the same domain written backwards, which upstream handles by reversing both arrays. */
  @Test
  fun `a descending three-stop domain reads the same way`() {
    assertEquals(
      "100.0:0 50.0:21 0.0:42 -25.0:63 -50.0:84",
      scaled(
        listOf(100.0, 0.0, -50.0),
        listOf(0.0, 42.0, 84.0),
        listOf(100.0, 50.0, 0.0, -25.0, -50.0),
      ),
    )
  }

  /** A repeated stop in the middle of four, which is the general case of the first test. */
  @Test
  fun `a repeated interior stop hands the value to the later segment`() {
    assertEquals(
      "0.0:0 25.0:5 50.0:20 75.0:25 100.0:30",
      scaled(
        listOf(0.0, 50.0, 50.0, 100.0),
        listOf(0.0, 10.0, 20.0, 30.0),
        listOf(0.0, 25.0, 50.0, 75.0, 100.0),
      ),
    )
  }
}
