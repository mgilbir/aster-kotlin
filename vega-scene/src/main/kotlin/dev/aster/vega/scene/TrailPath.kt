package dev.aster.vega.scene

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * A `trail`: a line whose thickness varies from point to point.
 *
 * Not a stroked path — it is a **filled** one, built as a separate closed shape per segment. Each
 * pair of neighbouring points becomes the outline of the two circles around them plus the two
 * tangent lines between: a capsule with a different radius at each end. Consecutive capsules
 * overlap at their shared point, and the overlap is what makes the join look continuous without any
 * of them knowing about the others.
 *
 * The other thing to know is the units. A trail's `size` is a **width**, halved to a radius — where
 * a symbol's `size` is a squared extent. Reading it as a symbol's would make every trail far too
 * thick, and neither name says which it is.
 */
public object TrailPath {

  private const val HALF_PI = kotlin.math.PI / 2.0

  /** @param points each position with the width the trail has there. */
  public fun build(points: List<Pair<PointD, Double>>): PathData = PathData.build {
    for (index in 1 until points.size) {
      val (p1, w1) = points[index - 1]
      val (p2, w2) = points[index]
      val r1 = w1 / 2.0
      val r2 = w2 / 2.0

      // The normal to the segment, before normalising: perpendicular to (p2 - p1).
      var ux = p1.y - p2.y
      var uy = p2.x - p1.x
      if (ux == 0.0 && uy == 0.0) {
        // Two points in the same place leave no direction to be perpendicular to, so upstream
        // draws the end cap alone rather than dividing by a zero length.
        circle(p2.x, p2.y, r2)
        close()
        continue
      }
      val length = hypot(ux, uy)
      ux /= length
      uy /= length
      val rx = ux * r1
      val ry = uy * r1
      val t = atan2(uy, ux)

      moveTo(p1.x - rx, p1.y - ry)
      lineTo(p2.x - ux * r2, p2.y - uy * r2)
      // Half a turn round each end, clockwise, which is the direction a canvas arc takes by
      // default. Angles are a quarter turn from the builder's own convention.
      arcTo(p2.x, p2.y, r2, t - kotlin.math.PI + HALF_PI, t + HALF_PI)
      lineTo(p1.x + rx, p1.y + ry)
      arcTo(p1.x, p1.y, r1, t + HALF_PI, t + kotlin.math.PI + HALF_PI)
      close()
    }
  }
}
