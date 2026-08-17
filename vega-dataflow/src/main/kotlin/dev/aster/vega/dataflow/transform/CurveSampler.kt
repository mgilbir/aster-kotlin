package dev.aster.vega.dataflow.transform

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Samples a smooth function into the points a line mark can be drawn from.
 *
 * The obvious way — evaluate at a hundred evenly spaced x — is not what upstream does, and the
 * difference is visible. A grid spends the same number of points on the flat parts of a curve as on
 * the bend, so either the bend is faceted or the whole thing is far denser than it needs to be.
 * Upstream instead **subdivides where the curve turns**: it starts from a coarse uniform grid and
 * splits a span in half whenever the midpoint sits more than half a degree off the straight line
 * between its ends. Flat runs stay coarse, corners get dense, and the output has no fixed length —
 * which is why a fitted curve here comes back as 48 points rather than 25 or 200.
 *
 * The angle is measured in **normalised** coordinates, x over the domain's own span and y over the
 * range the first coarse pass found. Without that, half a degree would mean something different on
 * every chart, since x and y are not in the same units and usually not within a few orders of
 * magnitude of each other.
 *
 * `minSteps == maxSteps` turns the adaptation off and samples the uniform grid directly. That is
 * how several densities are made to share their x positions so they can be stacked — adaptive
 * sampling would give each one its own points and leave nothing to stack.
 */
public object CurveSampler {

  /** Half a degree, in radians: upstream's subdivision threshold. */
  private const val MIN_RADIANS = 0.5 * PI / 180.0

  public fun sample(
    f: (Double) -> Double,
    low: Double,
    high: Double,
    minSteps: Int = 25,
    maxSteps: Int = 200,
  ): List<DoubleArray> {
    val min = if (minSteps > 0) minSteps else 25
    val max = maxOf(min, if (maxSteps > 0) maxSteps else 200)

    fun point(x: Double) = doubleArrayOf(x, f(x))

    val span = high - low
    val stop = span / max
    val out = mutableListOf(point(low))

    if (min == max) {
      // No adaptation: the caller wants sample points it can predict, not ones that follow the
      // curve. Note the loop runs to `max` while dividing by `min` — they are equal here, and
      // upstream writes it this way.
      for (i in 1 until max) out += point(low + (i.toDouble() / min) * span)
      out += point(high)
      return out
    }

    // The stack of points still to be reached, furthest first, seeded with a coarse uniform grid.
    val pending = mutableListOf(point(high))
    for (i in min - 1 downTo 1) pending += point(low + (i.toDouble() / min) * span)

    var p0 = out[0]
    val sx = 1.0 / span
    val sy = inverseRange(p0[1], pending)

    while (pending.isNotEmpty()) {
      val p1 = pending[pending.size - 1]
      val pm = point((p0[0] + p1[0]) / 2.0)
      // Two guards, and both are needed: `maxSteps` is a floor on how narrow a span may get, and
      // the angle is what decides whether a span that wide is worth splitting at all.
      if (pm[0] - p0[0] >= stop && angleDelta(p0, pm, p1, sx, sy) > MIN_RADIANS) {
        pending += pm
      } else {
        p0 = p1
        out += p1
        pending.removeAt(pending.size - 1)
      }
    }
    return out
  }

  /** `1 / (ymax - ymin)` over the coarse pass, so the angle is measured on a square. */
  private fun inverseRange(init: Double, points: List<DoubleArray>): Double {
    var ymin = init
    var ymax = init
    for (p in points) {
      if (p[1] < ymin) ymin = p[1]
      if (p[1] > ymax) ymax = p[1]
    }
    return 1.0 / (ymax - ymin)
  }

  /** How far the midpoint bends away from the chord, in normalised coordinates. */
  private fun angleDelta(
    p: DoubleArray,
    q: DoubleArray,
    r: DoubleArray,
    sx: Double,
    sy: Double,
  ): Double {
    val a0 = atan2(sy * (r[1] - p[1]), sx * (r[0] - p[0]))
    val a1 = atan2(sy * (q[1] - p[1]), sx * (q[0] - p[0]))
    return abs(a0 - a1)
  }
}
