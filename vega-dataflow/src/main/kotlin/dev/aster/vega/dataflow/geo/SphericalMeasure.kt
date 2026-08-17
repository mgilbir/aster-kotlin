package dev.aster.vega.dataflow.geo

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * `geoArea`, `geoCentroid` and `geoLength` measured **on the globe**, not on the page.
 *
 * These are what `geoArea(null, feature)` means, and the null is not an edge case: upstream's
 * `geoMethod` branches on the projection, and with none it calls d3's spherical function rather
 * than a path generator. This engine ran the *planar* path sinks over raw longitude and latitude
 * instead, which is not a small difference — a one-degree box is `1` square degree planar and
 * `0.000304` steradians spherical, a factor of about three thousand, and the centroid of anything
 * larger than a city is in the wrong place.
 *
 * The arithmetic is spherical trigonometry rather than anything cartographic:
 * - **Area** sums the spherical excess of the triangles from the south pole to each edge, by
 *   Cagnoli's theorem. A ring that comes out negative is the complement of the one that was meant,
 *   so it is folded back by `2π` — which is how a polygon that encloses a pole is measured as the
 *   larger piece rather than the smaller.
 * - **Centroid** accumulates in three dimensions and reports the highest dimension present: the
 *   area-weighted centroid of the polygons, else the length-weighted centroid of the lines, else
 *   the mean of the bare points. A `MultiPolygon` with one degenerate part therefore still balances
 *   on the part that has area.
 * - **Length** sums the great-circle distance along each line, in radians.
 *
 * Sums go through [Adder] rather than a `Double` because these are long runs of small terms with
 * cancellation, and the fifteenth digit is what the vectors compare.
 */
internal object SphericalMeasure {

  /** The area a geometry covers on the unit sphere, in steradians. */
  fun area(geojson: dev.aster.vega.model.VegaValue): Double {
    val sink = AreaSink()
    GeoJsonStream.stream(geojson, sink)
    return sink.result()
  }

  /** The great-circle length of a geometry's lines, in radians. */
  fun length(geojson: dev.aster.vega.model.VegaValue): Double {
    val sink = LengthSink()
    GeoJsonStream.stream(geojson, sink)
    return sink.result()
  }

  /**
   * The smallest box on the globe holding a geometry, as `[west, south, east, north]`.
   *
   * `[NaN, NaN, NaN, NaN]` for a geometry with no extent, which is what d3 answers.
   */
  fun bounds(geojson: dev.aster.vega.model.VegaValue): DoubleArray {
    val sink = BoundsSink()
    GeoJsonStream.stream(geojson, sink)
    return sink.result()
  }

  /**
   * Where a geometry balances on the globe, as `[longitude, latitude]`.
   *
   * A geometry with no centroid — an empty one, or a pair of antipodal points, where every
   * direction is equally central — is `[NaN, NaN]` rather than absent, which is what d3 answers.
   */
  fun centroid(geojson: dev.aster.vega.model.VegaValue): DoubleArray {
    val sink = CentroidSink()
    GeoJsonStream.stream(geojson, sink)
    return sink.result()
  }

  /**
   * The spherical excess of a ring, by Cagnoli's theorem.
   *
   * Extracted because **bounds needs it too**: d3 decides whether a polygon encloses a pole from
   * the sign of the same sum, and does it by having its bounds stream drive the *global* area
   * stream. Two stateful sinks sharing a global is the part not worth reproducing.
   */
  private class RingExcess {
    private var sum = Adder()
    private var first = true
    private var lambda00 = 0.0
    private var phi00 = 0.0
    private var lambda0 = 0.0
    private var cosPhi0 = 0.0
    private var sinPhi0 = 0.0

    fun polygonStart() {
      sum = Adder()
    }

    fun lineStart() {
      first = true
    }

    fun point(x: Double, y: Double) {
      // Half the angular distance from the south pole, which is what makes the excess a single
      // `atan2` rather than a case analysis.
      val phi = y * GeoMath.RADIANS / 2 + GeoMath.QUARTER_PI
      if (first) {
        first = false
        lambda00 = x
        phi00 = y
        lambda0 = x * GeoMath.RADIANS
        cosPhi0 = cos(phi)
        sinPhi0 = sin(phi)
        return
      }
      val lambda = x * GeoMath.RADIANS
      val dLambda = lambda - lambda0
      val sdLambda = if (dLambda >= 0) 1.0 else -1.0
      val adLambda = sdLambda * dLambda
      val cosPhi = cos(phi)
      val sinPhi = sin(phi)
      val k = sinPhi0 * sinPhi
      val u = cosPhi0 * cosPhi + k * cos(adLambda)
      val v = k * sdLambda * sin(adLambda)
      sum.add(atan2(v, u))
      lambda0 = lambda
      cosPhi0 = cosPhi
      sinPhi0 = sinPhi
    }

    /** The stream drops a ring's repeated closing point, so the closing edge is added here. */
    fun lineEnd() {
      if (!first) point(lambda00, phi00)
    }

    fun value(): Double = sum.value()
  }

  /** d3's `areaStream`: spherical excess, accumulated ring by ring. */
  private class AreaSink : GeoStream() {
    private val total = Adder()
    private val excess = RingExcess()
    private var inPolygon = false

    override fun point(x: Double, y: Double) {
      if (inPolygon) excess.point(x, y)
    }

    override fun polygonStart() {
      inPolygon = true
      excess.polygonStart()
    }

    override fun lineStart() {
      if (inPolygon) excess.lineStart()
    }

    override fun lineEnd() {
      if (inPolygon) excess.lineEnd()
    }

    override fun polygonEnd() {
      val value = excess.value()
      // A ring wound the other way describes the complement of the region that was meant, which is
      // how a polygon enclosing a pole is measured as the larger piece rather than the smaller.
      total.add(if (value < 0) GeoMath.TAU + value else value)
      inPolygon = false
    }

    override fun sphere() {
      total.add(GeoMath.TAU)
    }

    fun result(): Double = total.value() * 2
  }

  /**
   * d3's `boundsStream`: the smallest box on the globe that holds a geometry.
   *
   * The hard part is that longitude **wraps**, so "smallest" is not a matter of taking minima. Two
   * islands at ±179° are two degrees apart across the antimeridian, not 358° apart the other way,
   * and a box that spans the Pacific the long way round is wrong rather than merely loose. d3
   * handles it by collecting a *range* per line, merging the ones that overlap, and then taking the
   * inverse of the **largest gap** between them — the widest stretch of longitude the geometry does
   * not occupy is the part to leave out.
   *
   * Two more cases only a sphere has. A polygon whose ring sum is negative encloses everything, so
   * the box is the whole world; and a ring whose longitudes wind a full turn contains a pole, so it
   * spans every longitude and reaches to 90° on the side it wound towards.
   */
  private class BoundsSink : GeoStream() {
    private var lambda0 = Double.POSITIVE_INFINITY
    private var phi0 = Double.POSITIVE_INFINITY
    private var lambda1 = Double.NEGATIVE_INFINITY
    private var phi1 = Double.NEGATIVE_INFINITY
    private var lambda2 = 0.0
    private var lambda00 = 0.0
    private var phi00 = 0.0
    private var previous: DoubleArray? = null
    private var deltaSum = Adder()
    private val ranges = mutableListOf<DoubleArray>()
    private var range: DoubleArray? = null
    private val excess = RingExcess()
    private var mode = Mode.POINT

    private enum class Mode {
      POINT,
      LINE,
      RING,
    }

    override fun point(x: Double, y: Double) {
      when (mode) {
        Mode.POINT -> {
          range = doubleArrayOf(x, x).also { ranges.add(it) }
          lambda0 = x
          lambda1 = x
          if (y < phi0) phi0 = y
          if (y > phi1) phi1 = y
        }
        Mode.LINE -> linePoint(x, y)
        Mode.RING -> {
          if (previous != null) {
            val delta = x - lambda2
            // d3's own sign, which is not the one that normalises a wrap: a step across the
            // antimeridian is pushed *further* the way it went, so the sum counts turns rather than
            // measuring displacement — which is what makes a full turn round a pole detectable.
            deltaSum.add(
              if (abs(delta) > 180) delta + (if (delta > 0) 360.0 else -360.0) else delta
            )
          } else {
            lambda00 = x
            phi00 = y
          }
          excess.point(x, y)
          linePoint(x, y)
        }
      }
    }

    private fun linePoint(lambda: Double, phi: Double) {
      val p = GeoMath.cartesian(doubleArrayOf(lambda * GeoMath.RADIANS, phi * GeoMath.RADIANS))
      val p0 = previous
      if (p0 != null) {
        val normal = GeoMath.cartesianCross(p0, p)
        val equatorial = doubleArrayOf(normal[1], -normal[0], 0.0)
        val inflection = GeoMath.cartesianCross(equatorial, normal)
        GeoMath.cartesianNormalizeInPlace(inflection)
        val spherical = spherical(inflection)
        val delta = lambda - lambda2
        val sign = if (delta > 0) 1.0 else -1.0
        var lambdai = spherical[0] * GeoMath.DEGREES * sign
        // A step of more than half the globe went the short way across the antimeridian, so the
        // test for "is the inflection between the two points" runs inverted.
        val antimeridian = abs(delta) > 180
        if (antimeridian xor (sign * lambda2 < lambdai && lambdai < sign * lambda)) {
          val phii = spherical[1] * GeoMath.DEGREES
          if (phii > phi1) phi1 = phii
        } else {
          lambdai = (lambdai + 360) % 360 - 180
          if (antimeridian xor (sign * lambda2 < lambdai && lambdai < sign * lambda)) {
            val phii = -spherical[1] * GeoMath.DEGREES
            if (phii < phi0) phi0 = phii
          } else {
            if (phi < phi0) phi0 = phi
            if (phi > phi1) phi1 = phi
          }
        }
        if (antimeridian) {
          if (lambda < lambda2) {
            if (angle(lambda0, lambda) > angle(lambda0, lambda1)) lambda1 = lambda
          } else {
            if (angle(lambda, lambda1) > angle(lambda0, lambda1)) lambda0 = lambda
          }
        } else {
          if (lambda1 >= lambda0) {
            if (lambda < lambda0) lambda0 = lambda
            if (lambda > lambda1) lambda1 = lambda
          } else {
            if (lambda > lambda2) {
              if (angle(lambda0, lambda) > angle(lambda0, lambda1)) lambda1 = lambda
            } else {
              if (angle(lambda, lambda1) > angle(lambda0, lambda1)) lambda0 = lambda
            }
          }
        }
      } else {
        range = doubleArrayOf(lambda, lambda).also { ranges.add(it) }
        lambda0 = lambda
        lambda1 = lambda
      }
      if (phi < phi0) phi0 = phi
      if (phi > phi1) phi1 = phi
      previous = p
      lambda2 = lambda
    }

    override fun lineStart() {
      if (mode == Mode.RING) excess.lineStart() else mode = Mode.LINE
    }

    override fun lineEnd() {
      if (mode == Mode.RING) {
        point(lambda00, phi00)
        excess.lineEnd()
        // A full turn of longitude means the ring went round a pole.
        if (abs(deltaSum.value()) > GeoMath.EPSILON) {
          lambda1 = 180.0
          lambda0 = -180.0
        }
      } else {
        mode = Mode.POINT
      }
      range?.let {
        it[0] = lambda0
        it[1] = lambda1
      }
      previous = null
    }

    override fun polygonStart() {
      mode = Mode.RING
      deltaSum = Adder()
      excess.polygonStart()
    }

    override fun polygonEnd() {
      mode = Mode.POINT
      val sum = deltaSum.value()
      when {
        // A ring wound the other way encloses everything outside it, so the box is the world.
        excess.value() < 0 -> {
          lambda1 = 180.0
          lambda0 = -180.0
          phi1 = 90.0
          phi0 = -90.0
        }
        sum > GeoMath.EPSILON -> phi1 = 90.0
        sum < -GeoMath.EPSILON -> phi0 = -90.0
      }
      range?.let {
        it[0] = lambda0
        it[1] = lambda1
      }
    }

    override fun sphere() {
      lambda1 = 180.0
      lambda0 = -180.0
      phi1 = 90.0
      phi0 = -90.0
    }

    /** The Cartesian point back to longitude and latitude, in radians. */
    private fun spherical(p: DoubleArray): DoubleArray =
      doubleArrayOf(atan2(p[1], p[0]), GeoMath.asin(p[2]))

    /**
     * The distance rightwards from one longitude to another.
     *
     * Almost `(b - a + 360) % 360`, except that the distance from -180° to 180° is a full turn
     * rather than nothing — the two are the same place, but a range spanning them is the world.
     */
    private fun angle(from: Double, to: Double): Double {
      val d = to - from
      return if (d < 0) d + 360 else d
    }

    private fun contains(r: DoubleArray, x: Double): Boolean =
      if (r[0] <= r[1]) r[0] <= x && x <= r[1] else x < r[0] || r[1] < x

    fun result(): DoubleArray {
      if (ranges.isNotEmpty()) {
        ranges.sortBy { it[0] }
        val merged = mutableListOf(ranges[0])
        var a = ranges[0]
        for (index in 1 until ranges.size) {
          val b = ranges[index]
          if (contains(a, b[0]) || contains(a, b[1])) {
            if (angle(a[0], b[1]) > angle(a[0], a[1])) a[1] = b[1]
            if (angle(b[0], a[1]) > angle(a[0], a[1])) a[0] = b[0]
          } else {
            a = b
            merged.add(a)
          }
        }
        // The box is the inverse of the widest stretch of longitude the geometry does not occupy.
        var widest = Double.NEGATIVE_INFINITY
        val last = merged.size - 1
        var previousRange = merged[last]
        for (index in 0..last) {
          val b = merged[index]
          val gap = angle(previousRange[1], b[0])
          if (gap > widest) {
            widest = gap
            lambda0 = b[0]
            lambda1 = previousRange[1]
          }
          previousRange = b
        }
      }
      ranges.clear()
      range = null
      return if (lambda0 == Double.POSITIVE_INFINITY || phi0 == Double.POSITIVE_INFINITY)
        doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN)
      else doubleArrayOf(lambda0, phi0, lambda1, phi1)
    }
  }

  /** d3's `lengthStream`: great-circle distance summed along each line. */
  private class LengthSink : GeoStream() {
    private var total = Adder()
    private var inLine = false
    private var first = true
    private var lambda0 = 0.0
    private var sinPhi0 = 0.0
    private var cosPhi0 = 0.0

    override fun lineStart() {
      inLine = true
      first = true
    }

    override fun lineEnd() {
      inLine = false
    }

    override fun point(x: Double, y: Double) {
      if (!inLine) return
      val lambda = x * GeoMath.RADIANS
      val phi = y * GeoMath.RADIANS
      if (first) {
        first = false
        lambda0 = lambda
        sinPhi0 = sin(phi)
        cosPhi0 = cos(phi)
        return
      }
      val sinPhi = sin(phi)
      val cosPhi = cos(phi)
      val delta = kotlin.math.abs(lambda - lambda0)
      val cosDelta = cos(delta)
      val sinDelta = sin(delta)
      val x1 = cosPhi * sinDelta
      val y1 = cosPhi0 * sinPhi - sinPhi0 * cosPhi * cosDelta
      val z = sinPhi0 * sinPhi + cosPhi0 * cosPhi * cosDelta
      total.add(atan2(sqrt(x1 * x1 + y1 * y1), z))
      lambda0 = lambda
      sinPhi0 = sinPhi
      cosPhi0 = cosPhi
    }

    fun result(): Double = total.value()
  }

  /**
   * d3's `centroidStream`: three accumulators, and the highest-dimensional one wins.
   *
   * `W` weights, `X`/`Y`/`Z` the weighted Cartesian sums. The zero-dimensional sums are kept even
   * while measuring a polygon, because a geometry collection may hold both and the answer has to
   * fall back cleanly when the polygons turn out to be degenerate.
   */
  private class CentroidSink : GeoStream() {
    private var w0 = 0.0
    private var w1 = 0.0
    private var x0 = 0.0
    private var y0 = 0.0
    private var z0 = 0.0
    private var x1 = 0.0
    private var y1 = 0.0
    private var z1 = 0.0
    private var x2 = Adder()
    private var y2 = Adder()
    private var z2 = Adder()

    private var mode = Mode.POINT
    private var first = true
    private var lambda00 = 0.0
    private var phi00 = 0.0
    private var x00 = 0.0
    private var y00 = 0.0
    private var z00 = 0.0

    private enum class Mode {
      POINT,
      LINE,
      RING,
    }

    override fun point(x: Double, y: Double) {
      when (mode) {
        Mode.POINT -> {
          val lambda = x * GeoMath.RADIANS
          val phi = y * GeoMath.RADIANS
          val cosPhi = cos(phi)
          addPoint(cosPhi * cos(lambda), cosPhi * sin(lambda), sin(phi))
        }
        Mode.LINE -> line(x, y)
        Mode.RING -> ring(x, y)
      }
    }

    private fun addPoint(cx: Double, cy: Double, cz: Double) {
      w0 += 1
      x0 += (cx - x0) / w0
      y0 += (cy - y0) / w0
      z0 += (cz - z0) / w0
    }

    private var px = 0.0
    private var py = 0.0
    private var pz = 0.0

    private fun line(x: Double, y: Double) {
      val lambda = x * GeoMath.RADIANS
      val phi = y * GeoMath.RADIANS
      val cosPhi = cos(phi)
      val cx = cosPhi * cos(lambda)
      val cy = cosPhi * sin(lambda)
      val cz = sin(phi)
      if (first) {
        first = false
        px = cx
        py = cy
        pz = cz
        addPoint(cx, cy, cz)
        return
      }
      // The weight is the chord between the two points, so a line contributes by its own length.
      val w = hypot(hypot(py * cz - pz * cy, pz * cx - px * cz), px * cy - py * cx)
      val angle = kotlin.math.atan2(w, px * cx + py * cy + pz * cz)
      w1 += angle
      x1 += angle * (px + cx)
      y1 += angle * (py + cy)
      z1 += angle * (pz + cz)
      px = cx
      py = cy
      pz = cz
      addPoint(cx, cy, cz)
    }

    private fun ring(x: Double, y: Double) {
      if (first) {
        first = false
        lambda00 = x
        phi00 = y
        val lambda = x * GeoMath.RADIANS
        val phi = y * GeoMath.RADIANS
        val cosPhi = cos(phi)
        x00 = cosPhi * cos(lambda)
        y00 = cosPhi * sin(lambda)
        z00 = sin(phi)
        px = x00
        py = y00
        pz = z00
        addPoint(x00, y00, z00)
        return
      }
      val lambda = x * GeoMath.RADIANS
      val phi = y * GeoMath.RADIANS
      val cosPhi = cos(phi)
      val cx = cosPhi * cos(lambda)
      val cy = cosPhi * sin(lambda)
      val cz = sin(phi)
      // The cross product is twice the triangle's area and points along its normal, so summing it
      // over the fan from the ring's first vertex weights each vertex by the area it carries.
      val nx = py * cz - pz * cy
      val ny = pz * cx - px * cz
      val nz = px * cy - py * cx
      val m = hypot(hypot(nx, ny), nz)
      val w = GeoMath.asin(m)
      val v = if (m != 0.0) -w / m else 0.0
      x2.add(v * nx)
      y2.add(v * ny)
      z2.add(v * nz)
      w1 += w
      x1 += w * (px + cx)
      y1 += w * (py + cy)
      z1 += w * (pz + cz)
      px = cx
      py = cy
      pz = cz
      addPoint(cx, cy, cz)
    }

    override fun lineStart() {
      if (mode != Mode.RING) mode = Mode.LINE
      first = true
    }

    override fun lineEnd() {
      if (mode == Mode.RING && !first) ring(lambda00, phi00)
      if (mode == Mode.LINE) mode = Mode.POINT
      first = true
    }

    override fun polygonStart() {
      mode = Mode.RING
    }

    override fun polygonEnd() {
      mode = Mode.POINT
    }

    fun result(): DoubleArray {
      var cx = x2.value()
      var cy = y2.value()
      var cz = z2.value()
      var m = hypot(hypot(cx, cy), cz)

      // Fall through the dimensions: area, then length, then the bare points.
      if (m < GeoMath.EPSILON2) {
        cx = x1
        cy = y1
        cz = z1
        // If the area-weighted centroid is degenerate *and* there were no lines either, the
        // remaining answer is the mean of the points.
        if (w1 < GeoMath.EPSILON) {
          cx = x0
          cy = y0
          cz = z0
        }
        m = hypot(hypot(cx, cy), cz)
        // Every direction is equally central, so there is no answer rather than an arbitrary one.
        if (m < GeoMath.EPSILON2) return doubleArrayOf(Double.NaN, Double.NaN)
      }
      return doubleArrayOf(atan2(cy, cx) * GeoMath.DEGREES, GeoMath.asin(cz / m) * GeoMath.DEGREES)
    }
  }
}
