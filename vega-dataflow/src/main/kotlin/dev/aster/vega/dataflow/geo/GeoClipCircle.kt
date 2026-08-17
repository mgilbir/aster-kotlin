package dev.aster.vega.dataflow.geo

import dev.aster.vega.dataflow.geo.GeoMath.EPSILON
import dev.aster.vega.dataflow.geo.GeoMath.HALF_PI
import dev.aster.vega.dataflow.geo.GeoMath.PI_
import dev.aster.vega.dataflow.geo.GeoMath.RADIANS
import dev.aster.vega.dataflow.geo.GeoMath.TAU
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Clipping to a small circle, which is how a globe hides its far side.
 *
 * `orthographic` looks at the Earth from infinitely far away and sets a clip angle of 90 degrees;
 * without this, the far hemisphere is projected onto the near one and every continent is drawn
 * twice, folded over itself. `gnomonic` and `stereographic` set their own angles for the same
 * reason — past a certain distance from the centre their formulas stop meaning anything.
 *
 * Ported from `d3-geo/src/clip/circle.js` and the circle generator it interpolates with.
 */
internal class ClipCircle(private val radius: Double) {
  private val cr = cos(radius)
  private val smallRadius = cr > 0

  /**
   * A hemisphere exactly is the easy case; anything else can cross the circle twice in one step.
   */
  private val notHemisphere = abs(cr) > EPSILON

  fun stream(sink: GeoStream): GeoStream =
    ClipStream(
      sink,
      pointVisible = ::visible,
      clipLine = { Line(it) },
      interpolate = { from, to, direction, stream ->
        circleStream(stream, radius, DELTA, direction, from, to)
      },
      start = if (smallRadius) doubleArrayOf(0.0, -radius) else doubleArrayOf(-PI_, radius - PI_),
      // The same ordering the antimeridian uses: down one side of the seam and back up the other.
      compareIntersection =
        Comparator { a, b ->
          val av = if (a[0] < 0) a[1] - HALF_PI - EPSILON else HALF_PI - a[1]
          val bv = if (b[0] < 0) b[1] - HALF_PI - EPSILON else HALF_PI - b[1]
          av.compareTo(bv)
        },
    )

  private fun visible(lambda: Double, phi: Double): Boolean = cos(lambda) * cos(phi) > cr

  /** Cuts a line into the arcs of it that fall inside the circle. */
  private inner class Line(sink: GeoStream) : ClipLineStream(sink) {
    private var point0: DoubleArray? = null
    private var c0 = 0
    private var v0 = false
    private var v00 = false
    private var clean = 0

    override fun lineStart() {
      v00 = false
      v0 = false
      clean = 1
    }

    override fun point(x: Double, y: Double) {
      val point1 = doubleArrayOf(x, y, 0.0)
      val v = visible(x, y)
      val c =
        if (smallRadius) {
          if (v) 0 else code(x, y)
        } else {
          if (v) code(x + (if (x < 0) PI_ else -PI_), y) else 0
        }
      val previous = point0
      if (previous == null) {
        v00 = v
        v0 = v
        if (v) sink.lineStart()
      }

      var next: DoubleArray? = null
      if (v != v0) {
        val crossing = intersectOne(previous, point1)
        if (
          crossing == null ||
            (previous != null && GeoMath.pointEqual(previous, crossing)) ||
            GeoMath.pointEqual(point1, crossing)
        ) {
          // A crossing that lands on a vertex is degenerate; the marker tells the rejoin so.
          point1[2] = 1.0
        }
        next = crossing
      }

      if (v != v0) {
        clean = 0
        if (v) {
          // Outside going in.
          sink.lineStart()
          val entry = intersectOne(point1, previous)
          if (entry != null) sink.point(entry[0], entry[1])
          next = entry
        } else {
          // Inside going out.
          val exit = intersectOne(previous, point1)
          if (exit != null) sink.point(exit[0], exit[1], 2.0)
          sink.lineEnd()
          next = exit
        }
        point0 = next
      } else if (notHemisphere && previous != null && (smallRadius xor v)) {
        // Both ends on the same side, but the arc between them can still cut across the circle.
        if ((c and c0) == 0) {
          val both = intersectBoth(point1, previous)
          if (both != null) {
            clean = 0
            if (smallRadius) {
              sink.lineStart()
              sink.point(both[0][0], both[0][1])
              sink.point(both[1][0], both[1][1])
              sink.lineEnd()
            } else {
              sink.point(both[1][0], both[1][1])
              sink.lineEnd()
              sink.lineStart()
              sink.point(both[0][0], both[0][1], 3.0)
            }
          }
        }
      }

      val before = point0
      if (v && (before == null || !GeoMath.pointEqual(before, point1))) {
        sink.point(point1[0], point1[1])
      }
      point0 = point1
      v0 = v
      c0 = c
    }

    override fun lineEnd() {
      if (v0) sink.lineEnd()
      point0 = null
    }

    override fun clean(): Int = clean or ((if (v00 && v0) 1 else 0) shl 1)
  }

  /**
   * Where the great-circle arc between two points crosses the clip circle.
   *
   * Two planes through the origin — one holding the arc, one holding the circle — meet in a line,
   * and the crossings are where that line meets the unit sphere. Straight from d3, algebra
   * included.
   */
  private fun intersectBoth(a: DoubleArray?, b: DoubleArray?): Array<DoubleArray>? =
    intersectPair(a, b, two = true)

  private fun intersectOne(a: DoubleArray?, b: DoubleArray?): DoubleArray? =
    intersectPair(a, b, two = false)?.firstOrNull()

  private fun intersectPair(
    a: DoubleArray?,
    b: DoubleArray?,
    two: Boolean,
  ): Array<DoubleArray>? {
    if (a == null || b == null) return null
    val pa = GeoMath.cartesian(a)
    val pb = GeoMath.cartesian(b)

    val n1 = doubleArrayOf(1.0, 0.0, 0.0)
    val n2 = GeoMath.cartesianCross(pa, pb)
    val n2n2 = dot(n2, n2)
    val n1n2 = n2[0]
    val determinant = n2n2 - n1n2 * n1n2

    // Two polar points: the arc runs through both poles and the planes coincide.
    if (determinant == 0.0) return if (two) null else arrayOf(a)

    val c1 = cr * n2n2 / determinant
    val c2 = -cr * n1n2 / determinant
    val u = GeoMath.cartesianCross(n1, n2)
    val bigA =
      doubleArrayOf(n1[0] * c1 + n2[0] * c2, n1[1] * c1 + n2[1] * c2, n1[2] * c1 + n2[2] * c2)

    val w = dot(bigA, u)
    val uu = dot(u, u)
    val t2 = w * w - uu * (dot(bigA, bigA) - 1)
    if (t2 < 0) return null

    val t = sqrt(t2)
    val q = scaleAdd(u, (-w - t) / uu, bigA)
    val first = spherical(q)
    if (!two) return arrayOf(first)

    // Both crossings, but only when the first one really lies between the two ends.
    var lambda0 = a[0]
    var lambda1 = b[0]
    var phi0 = a[1]
    var phi1 = b[1]
    if (lambda1 < lambda0) {
      val held = lambda0
      lambda0 = lambda1
      lambda1 = held
    }
    val delta = lambda1 - lambda0
    val polar = abs(delta - PI_) < EPSILON
    val meridian = polar || delta < EPSILON
    if (!polar && phi1 < phi0) {
      val held = phi0
      phi0 = phi1
      phi1 = held
    }
    val between =
      if (meridian) {
        if (polar) {
          (phi0 + phi1 > 0) xor (first[1] < (if (abs(first[0] - lambda0) < EPSILON) phi0 else phi1))
        } else {
          phi0 <= first[1] && first[1] <= phi1
        }
      } else {
        (delta > PI_) xor (lambda0 <= first[0] && first[0] <= lambda1)
      }
    if (!between) return null
    return arrayOf(first, spherical(scaleAdd(u, (-w + t) / uu, bigA)))
  }

  /** Which sides of the circle's bounding box a point falls outside of, as four bits. */
  private fun code(lambda: Double, phi: Double): Int {
    val r = if (smallRadius) radius else PI_ - radius
    var bits = 0
    if (lambda < -r) bits = bits or 1 else if (lambda > r) bits = bits or 2
    if (phi < -r) bits = bits or 4 else if (phi > r) bits = bits or 8
    return bits
  }

  private fun dot(a: DoubleArray, b: DoubleArray): Double = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

  private fun scaleAdd(v: DoubleArray, k: Double, base: DoubleArray): DoubleArray =
    doubleArrayOf(v[0] * k + base[0], v[1] * k + base[1], v[2] * k + base[2])

  private fun spherical(c: DoubleArray): DoubleArray =
    doubleArrayOf(atan2(c[1], c[0]), GeoMath.asin(c[2]))

  private companion object {
    /** d3's interpolation step along the clip circle: two degrees. */
    val DELTA = 2 * RADIANS
  }
}

/**
 * The clip circle's own outline, walked between two crossings.
 *
 * With no crossings at all — a polygon that swallows the whole circle — the walk is a full turn,
 * which is what draws the disc of a globe.
 */
internal fun circleStream(
  stream: GeoStream,
  radius: Double,
  delta: Double,
  direction: Int,
  from: DoubleArray?,
  to: DoubleArray?,
) {
  if (delta == 0.0) return
  val cosRadius = cos(radius)
  val sinRadius = sin(radius)
  val step = direction * delta
  var t0: Double
  val t1: Double
  if (from == null) {
    t0 = radius + direction * TAU
    t1 = radius - step / 2
  } else {
    t0 = circleRadius(cosRadius, from)
    t1 = circleRadius(cosRadius, to ?: from)
    if (if (direction > 0) t0 < t1 else t0 > t1) t0 += direction * TAU
  }
  var t = t0
  while (if (direction > 0) t > t1 else t < t1) {
    val point = doubleArrayOf(cosRadius, -sinRadius * cos(t), -sinRadius * sin(t))
    val s = doubleArrayOf(atan2(point[1], point[0]), GeoMath.asin(point[2]))
    stream.point(s[0], s[1])
    t -= step
  }
}

/** The signed angle of a point around the circle, measured from `[cosRadius, 0, 0]`. */
private fun circleRadius(cosRadius: Double, point: DoubleArray): Double {
  val p = GeoMath.cartesian(point)
  p[0] -= cosRadius
  GeoMath.cartesianNormalizeInPlace(p)
  val radius = GeoMath.acos(-p[1])
  return ((if (-p[2] < 0) -radius else radius) + TAU - EPSILON) % TAU
}
