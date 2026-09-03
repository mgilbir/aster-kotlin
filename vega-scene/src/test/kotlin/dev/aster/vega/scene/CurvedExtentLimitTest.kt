package dev.aster.vega.scene

import kotlin.math.PI
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * An arc's extent is the extent of the **cubics that were painted**, not of an ideal circle.
 *
 * `SUPPORTED_FEATURES.md` files this as a `Known difference`. Upstream measures an arc exactly,
 * from its centre and radii, because its renderer emits a true circular arc. This scene graph has
 * only cubics — every backend it draws on takes them — so an arc's bounds come from the curve that
 * was actually drawn, and they are fractionally **wider** than the circle: a cubic's own extent is
 * taken from its control points, which sit outside the arc they bend towards.
 *
 * The size of that is the whole of the claim. An arc is split into **eighth**-turn segments rather
 * than the usual quarter, which takes the difference to roughly a millionth of the radius; at a
 * quarter turn it was large enough to fail a comparison on a 72-unit donut.
 *
 * Both bounds are asserted — small enough not to matter, and non-zero, because a zero would mean
 * the measurement had become analytic and the row would be describing something that no longer
 * happens.
 */
class CurvedExtentLimitTest {

  private fun arc(outer: Double, from: Double, to: Double, inner: Double = 0.0): RectD =
    ArcPath.build(
        centreX = 0.0,
        centreY = 0.0,
        innerRadius = inner,
        outerRadius = outer,
        startAngle = from,
        endAngle = to,
      )
      .bounds

  /**
   * A **whole** circle measures exactly, because its segment ends land on the axes.
   *
   * Eighth turns from twelve o'clock put an endpoint at every 45 degrees, so the four points that
   * decide an axis-aligned box — three, six, nine and twelve o'clock — are endpoints rather than
   * midpoints, and an endpoint is on the circle by construction. Worth pinning: it says the
   * difference below is about *where* the extreme falls, not about arcs in general.
   */
  @Test
  fun `a whole circle measures its radius exactly`() {
    for (radius in listOf(4.0, 36.0, 72.0, 400.0)) {
      val box = arc(radius, 0.0, 2 * PI)
      assertEquals(radius, box.right, 1e-9)
      assertEquals(-radius, box.top, 1e-9)
      assertEquals(radius, box.bottom, 1e-9)
      assertEquals(-radius, box.left, 1e-9)
    }
  }

  /**
   * A **partial** arc measures fractionally wide, and that is the known difference.
   *
   * Each of these spans three o'clock, so the true rightmost point is exactly the radius; the
   * measured one is a hair beyond it. Non-zero is asserted first, because a zero would mean the
   * bounds had stopped coming from the painted curve.
   */
  @Test
  fun `a partial arc measures fractionally wider than the true circle`() {
    val radius = 400.0
    var sawADifference = false
    for ((from, to) in listOf(0.05 to 1.7, 0.3 to 2.2, 0.1 to 3.0, 0.02 to 1.6)) {
      val over = arc(radius, from, to).right - radius
      assertTrue(over >= 0.0, "the arc [$from, $to] measured inside the circle, by $over")
      assertTrue(
        over < radius * 1e-5,
        "the arc [$from, $to] measured $over beyond its radius, which is worse than the " +
          "eighth-turn segmentation should allow",
      )
      if (over > 0.0) sawADifference = true
    }
    assertTrue(
      sawADifference,
      "every arc measured exactly, so the bounds are analytic now and this row is out of date",
    )
  }

  /** The difference scales with the radius, which is what makes a *relative* tolerance right. */
  @Test
  fun `the difference is proportional to the radius`() {
    val small = arc(40.0, 0.05, 1.7).right - 40.0
    val large = arc(400.0, 0.05, 1.7).right - 400.0
    assertTrue(small > 0.0 && large > 0.0)
    // Ten times the radius, ten times the error, to within the slack of double arithmetic.
    assertEquals(10.0, large / small, 0.5, "the error did not scale with the radius")
  }

  /**
   * And it is small enough that a chart's own comparison tolerance covers it.
   *
   * The number that matters in practice: on the 72-unit donut this was found on, the difference is
   * under a thousandth of a unit — far below anything a reader could see, and below the geometry
   * tolerance the differential harness allows.
   */
  @Test
  fun `on a chart-sized arc the difference is under a thousandth of a unit`() {
    val over = abs(arc(72.0, 0.05, 1.7).right - 72.0)
    assertTrue(over < 1e-3, "a 72-unit arc measured $over out")
  }
}
