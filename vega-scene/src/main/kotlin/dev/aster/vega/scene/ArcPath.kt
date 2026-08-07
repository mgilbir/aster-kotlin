package dev.aster.vega.scene

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * An annular sector, with padding and rounded corners, exactly as d3-shape builds one.
 *
 * Ported rather than approximated, because neither `padAngle` nor `cornerRadius` is the thing its
 * name suggests:
 *
 * - **`padAngle` is not an angle subtracted from the sector.** It is a gap measured at a *pad
 *   radius* — `sqrt(r0² + r1²)` by default — and then converted back into an angle separately for
 *   each edge, so the inner edge loses more angle than the outer one and the two sides of a gap
 *   stay parallel instead of splaying. Subtracting a fixed angle from both ends, which is the
 *   obvious implementation, gives a wedge-shaped gap that widens outwards.
 * - **`cornerRadius` is clamped by the sector's own geometry**, not just by its thickness. Where
 *   the two straight edges would meet, d3 finds the intersection and derives separate limits for
 *   the inner and outer corners from it, so a thin slice rounds less than a fat one and a slice too
 *   small to round at all loses its corners rather than folding inside out.
 *
 * Angles arrive in this engine's convention — zero at twelve o'clock, increasing clockwise — and
 * are converted to the mathematical one on the way in, which is the quarter turn d3 applies itself.
 */
public object ArcPath {

  private const val EPSILON = 1e-12
  private const val HALF_PI = kotlin.math.PI / 2.0
  private const val TAU = kotlin.math.PI * 2.0

  /** @param padRadius the radius the pad gap is measured at; `null` uses d3's `sqrt(r0² + r1²)`. */
  public fun build(
    centreX: Double,
    centreY: Double,
    innerRadius: Double,
    outerRadius: Double,
    startAngle: Double,
    endAngle: Double,
    padAngle: Double = 0.0,
    cornerRadius: Double = 0.0,
    padRadius: Double? = null,
  ): PathData {
    var r0 = innerRadius
    var r1 = outerRadius
    // d3 swaps rather than rejecting, so an arc written inside-out still draws.
    if (r1 < r0) {
      val swap = r1
      r1 = r0
      r0 = swap
    }
    val a0 = startAngle - HALF_PI
    val a1 = endAngle - HALF_PI
    val da = abs(a1 - a0)
    val clockwise = a1 > a0

    return PathData.build {
      when {
        r1 <= EPSILON -> moveTo(centreX, centreY)
        da > TAU - EPSILON -> {
          ring(centreX, centreY, r1, a0, a1, !clockwise)
          if (r0 > EPSILON) ring(centreX, centreY, r0, a1, a0, clockwise)
        }
        else ->
          sector(
            centreX,
            centreY,
            r0,
            r1,
            a0,
            a1,
            da,
            clockwise,
            padAngle,
            cornerRadius,
            padRadius,
          )
      }
      close()
    }
  }

  private fun PathBuilder.ring(
    cx: Double,
    cy: Double,
    r: Double,
    a0: Double,
    a1: Double,
    anticlockwise: Boolean,
  ) {
    moveTo(cx + r * cos(a0), cy + r * sin(a0))
    sweepTo(cx, cy, r, a0, a1, anticlockwise)
  }

  @Suppress("LongParameterList")
  private fun PathBuilder.sector(
    cx: Double,
    cy: Double,
    r0: Double,
    r1: Double,
    a0: Double,
    a1: Double,
    da: Double,
    cw: Boolean,
    padAngle: Double,
    cornerRadius: Double,
    padRadius: Double?,
  ) {
    var a01 = a0
    var a11 = a1
    var a00 = a0
    var a10 = a1
    var da0 = da
    var da1 = da
    val ap = padAngle / 2.0
    val rp = if (ap > EPSILON) padRadius ?: sqrt(r0 * r0 + r1 * r1) else 0.0
    val rc = min(abs(r1 - r0) / 2.0, cornerRadius)
    var rc0 = rc
    var rc1 = rc

    // The gap is a *length* at the pad radius, turned back into an angle at each edge's own radius.
    // The inner edge is shorter, so it gives up more angle — which is what keeps the two sides of
    // the gap parallel.
    if (rp > EPSILON) {
      var p0 = asin(rp / r0 * sin(ap))
      var p1 = asin(rp / r1 * sin(ap))
      da0 -= p0 * 2.0
      if (da0 > EPSILON) {
        p0 *= if (cw) 1.0 else -1.0
        a00 += p0
        a10 -= p0
      } else {
        da0 = 0.0
        a00 = (a0 + a1) / 2.0
        a10 = a00
      }
      da1 -= p1 * 2.0
      if (da1 > EPSILON) {
        p1 *= if (cw) 1.0 else -1.0
        a01 += p1
        a11 -= p1
      } else {
        da1 = 0.0
        a01 = (a0 + a1) / 2.0
        a11 = a01
      }
    }

    val x01 = r1 * cos(a01)
    val y01 = r1 * sin(a01)
    val x10 = r0 * cos(a10)
    val y10 = r0 * sin(a10)

    // Where the two straight edges would meet decides how much rounding each corner can take. A
    // slice too small for its own corner radius loses the rounding rather than folding inside out.
    if (rc > EPSILON) {
      val x11 = r1 * cos(a11)
      val y11 = r1 * sin(a11)
      val x00 = r0 * cos(a00)
      val y00 = r0 * sin(a00)
      if (da < kotlin.math.PI) {
        val oc = intersect(x01, y01, x00, y00, x11, y11, x10, y10)
        if (oc != null) {
          val ax = x01 - oc[0]
          val ay = y01 - oc[1]
          val bx = x11 - oc[0]
          val by = y11 - oc[1]
          val cosine = (ax * bx + ay * by) / (sqrt(ax * ax + ay * ay) * sqrt(bx * bx + by * by))
          val kc = 1.0 / sin(acos(cosine.coerceIn(-1.0, 1.0)) / 2.0)
          val lc = sqrt(oc[0] * oc[0] + oc[1] * oc[1])
          rc0 = min(rc, (r0 - lc) / (kc - 1.0))
          rc1 = min(rc, (r1 - lc) / (kc + 1.0))
        } else {
          rc0 = 0.0
          rc1 = 0.0
        }
      }
    }

    // ---- the outer edge ----
    if (da1 <= EPSILON) {
      moveTo(cx + x01, cy + y01)
    } else if (rc1 > EPSILON) {
      val x00 = r0 * cos(a00)
      val y00 = r0 * sin(a00)
      val x11 = r1 * cos(a11)
      val y11 = r1 * sin(a11)
      val t0 = cornerTangents(x00, y00, x01, y01, r1, rc1, cw)
      val t1 = cornerTangents(x11, y11, x10, y10, r1, rc1, cw)
      moveTo(cx + t0.cx + t0.x01, cy + t0.cy + t0.y01)
      if (rc1 < rc) {
        // The two corners have met, so there is no straight ring left between them.
        sweepTo(
          cx + t0.cx,
          cy + t0.cy,
          rc1,
          atan2(t0.y01, t0.x01),
          atan2(t1.y01, t1.x01),
          !cw,
        )
      } else {
        sweepTo(cx + t0.cx, cy + t0.cy, rc1, atan2(t0.y01, t0.x01), atan2(t0.y11, t0.x11), !cw)
        sweepTo(
          cx,
          cy,
          r1,
          atan2(t0.cy + t0.y11, t0.cx + t0.x11),
          atan2(t1.cy + t1.y11, t1.cx + t1.x11),
          !cw,
        )
        sweepTo(cx + t1.cx, cy + t1.cy, rc1, atan2(t1.y11, t1.x11), atan2(t1.y01, t1.x01), !cw)
      }
    } else {
      moveTo(cx + x01, cy + y01)
      sweepTo(cx, cy, r1, a01, a11, !cw)
    }

    // ---- the inner edge ----
    if (r0 <= EPSILON || da0 <= EPSILON) {
      lineTo(cx + x10, cy + y10)
    } else if (rc0 > EPSILON) {
      val x00 = r0 * cos(a00)
      val y00 = r0 * sin(a00)
      val x11 = r1 * cos(a11)
      val y11 = r1 * sin(a11)
      val t0 = cornerTangents(x10, y10, x11, y11, r0, -rc0, cw)
      val t1 = cornerTangents(x01, y01, x00, y00, r0, -rc0, cw)
      lineTo(cx + t0.cx + t0.x01, cy + t0.cy + t0.y01)
      if (rc0 < rc) {
        sweepTo(
          cx + t0.cx,
          cy + t0.cy,
          rc0,
          atan2(t0.y01, t0.x01),
          atan2(t1.y01, t1.x01),
          !cw,
        )
      } else {
        sweepTo(cx + t0.cx, cy + t0.cy, rc0, atan2(t0.y01, t0.x01), atan2(t0.y11, t0.x11), !cw)
        sweepTo(
          cx,
          cy,
          r0,
          atan2(t0.cy + t0.y11, t0.cx + t0.x11),
          atan2(t1.cy + t1.y11, t1.cx + t1.x11),
          cw,
        )
        sweepTo(cx + t1.cx, cy + t1.cy, rc0, atan2(t1.y11, t1.x11), atan2(t1.y01, t1.x01), !cw)
      }
    } else {
      sweepTo(cx, cy, r0, a10, a00, cw)
    }
  }

  /**
   * A canvas-style arc, in mathematical angles, appended to the path.
   *
   * Canvas takes a direction rather than a signed sweep and normalizes the difference into it, so
   * that is done here before handing a signed sweep to the path builder. Angles are turned back a
   * quarter turn on the way out, into the twelve-o'clock convention the builder uses.
   */
  private fun PathBuilder.sweepTo(
    cx: Double,
    cy: Double,
    r: Double,
    a0: Double,
    a1: Double,
    anticlockwise: Boolean,
  ) {
    var delta = a1 - a0
    if (anticlockwise) {
      while (delta > 0.0) delta -= TAU
      if (delta < -TAU) delta = -TAU
    } else {
      while (delta < 0.0) delta += TAU
      if (delta > TAU) delta = TAU
    }
    arcTo(cx, cy, r, a0 + HALF_PI, a0 + HALF_PI + delta)
  }

  /** Where the lines (x0,y0)-(x1,y1) and (x2,y2)-(x3,y3) cross, or null if they are parallel. */
  @Suppress("LongParameterList")
  private fun intersect(
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    x3: Double,
    y3: Double,
  ): DoubleArray? {
    val x10 = x1 - x0
    val y10 = y1 - y0
    val x32 = x3 - x2
    val y32 = y3 - y2
    var t = y32 * x10 - x32 * y10
    if (t * t < EPSILON) return null
    t = (x32 * (y0 - y2) - y32 * (x0 - x2)) / t
    return doubleArrayOf(x0 + t * x10, y0 + t * y10)
  }

  /** The centre of a corner circle, and the offsets to where it meets each edge. */
  private class Tangents(
    val cx: Double,
    val cy: Double,
    val x01: Double,
    val y01: Double,
    val x11: Double,
    val y11: Double,
  )

  /** d3's `cornerTangents`: the circle of radius [rc] tangent to both the edge and the ring. */
  @Suppress("LongParameterList")
  private fun cornerTangents(
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    r1: Double,
    rc: Double,
    cw: Boolean,
  ): Tangents {
    val x01 = x0 - x1
    val y01 = y0 - y1
    val lo = (if (cw) rc else -rc) / sqrt(x01 * x01 + y01 * y01)
    val ox = lo * y01
    val oy = -lo * x01
    val x11 = x0 + ox
    val y11 = y0 + oy
    val x10 = x1 + ox
    val y10 = y1 + oy
    val x00 = (x11 + x10) / 2.0
    val y00 = (y11 + y10) / 2.0
    val dx = x10 - x11
    val dy = y10 - y11
    val d2 = dx * dx + dy * dy
    val r = r1 - rc
    val bigD = x11 * y10 - x10 * y11
    val d = (if (dy < 0.0) -1.0 else 1.0) * sqrt(maxOf(0.0, r * r * d2 - bigD * bigD))
    var cx0 = (bigD * dy - dx * d) / d2
    var cy0 = (-bigD * dx - dy * d) / d2
    val cx1 = (bigD * dy + dx * d) / d2
    val cy1 = (-bigD * dx + dy * d) / d2
    val dx0 = cx0 - x00
    val dy0 = cy0 - y00
    val dx1 = cx1 - x00
    val dy1 = cy1 - y00
    // Two circles are tangent to both lines; the nearer one is the corner.
    if (dx0 * dx0 + dy0 * dy0 > dx1 * dx1 + dy1 * dy1) {
      cx0 = cx1
      cy0 = cy1
    }
    return Tangents(cx0, cy0, -ox, -oy, cx0 * (r1 / r - 1.0), cy0 * (r1 / r - 1.0))
  }
}
