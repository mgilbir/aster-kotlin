package dev.aster.vega.dataflow.geo

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos as kacos
import kotlin.math.asin as kasin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The constants d3-geo works in, kept together because the tolerances are load-bearing.
 *
 * `epsilon` is not a rounding convenience: the clipping code decides whether a point sits *on* a
 * meridian with it, and a different value changes which side of the antimeridian a country's
 * easternmost island comes out on.
 */
internal object GeoMath {
  const val EPSILON = 1e-6
  const val EPSILON2 = 1e-12
  const val PI_ = PI
  const val HALF_PI = PI / 2
  const val QUARTER_PI = PI / 4
  const val TAU = PI * 2
  const val DEGREES = 180 / PI
  const val RADIANS = PI / 180

  /** Clamped, because a dot product that rounds to 1.0000000000000002 is not an error. */
  fun asin(x: Double): Double = if (x > 1) HALF_PI else if (x < -1) -HALF_PI else kasin(x)

  fun acos(x: Double): Double = if (x > 1) 0.0 else if (x < -1) PI else kacos(x)

  fun sign(x: Double): Double = if (x > 0) 1.0 else if (x < 0) -1.0 else 0.0

  fun cartesian(spherical: DoubleArray): DoubleArray {
    val lambda = spherical[0]
    val phi = spherical[1]
    val cosPhi = cos(phi)
    return doubleArrayOf(cosPhi * cos(lambda), cosPhi * sin(lambda), sin(phi))
  }

  fun cartesianCross(a: DoubleArray, b: DoubleArray): DoubleArray =
    doubleArrayOf(
      a[1] * b[2] - a[2] * b[1],
      a[2] * b[0] - a[0] * b[2],
      a[0] * b[1] - a[1] * b[0],
    )

  fun cartesianNormalizeInPlace(d: DoubleArray) {
    val l = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2])
    d[0] /= l
    d[1] /= l
    d[2] /= l
  }

  fun pointEqual(a: DoubleArray, b: DoubleArray): Boolean =
    abs(a[0] - b[0]) < EPSILON && abs(a[1] - b[1]) < EPSILON
}

/**
 * Shewchuk's exact summation, which `polygonContains` needs and an ordinary `+=` cannot give.
 *
 * The test at the end is `sum < -epsilon2` on a total of thousands of `atan2` terms that very
 * nearly cancel: the sign of a number around 1e-12 decides whether the South Pole is inside the
 * polygon, and therefore whether a whole continent is filled or left as a hole. A naive sum loses
 * exactly the bits that decide it. Ported from `d3-array`'s `Adder`.
 */
internal class Adder {
  private val partials = DoubleArray(32)
  private var n = 0

  fun add(value: Double): Adder {
    var x = value
    var i = 0
    var j = 0
    while (j < n && j < 32) {
      val y = partials[j]
      val hi = x + y
      val lo = if (abs(x) < abs(y)) x - (hi - y) else y - (hi - x)
      if (lo != 0.0) partials[i++] = lo
      x = hi
      j++
    }
    partials[i] = x
    n = i + 1
    return this
  }

  fun value(): Double {
    var index = n
    var hi = 0.0
    var lo = 0.0
    if (index > 0) {
      hi = partials[--index]
      while (index > 0) {
        val x = hi
        val y = partials[--index]
        hi = x + y
        lo = y - (hi - x)
        if (lo != 0.0) break
      }
      if (
        index > 0 && ((lo < 0 && partials[index - 1] < 0) || (lo > 0 && partials[index - 1] > 0))
      ) {
        val y = lo * 2
        val x = hi + y
        if (y == x - hi) hi = x
      }
    }
    return hi
  }
}

/**
 * A sink for geometry, in the shape d3-geo passes everything through.
 *
 * Every stage of a projection — rotating, clipping, resampling, drawing — is one of these wrapping
 * the next, and geometry is *pushed* through rather than transformed and returned. That shape is
 * not incidental: clipping emits a different number of points than it consumes, and sometimes a
 * different number of rings, so there is nothing for a coordinate-in-coordinate-out design to
 * return.
 */
internal abstract class GeoStream {
  abstract fun point(x: Double, y: Double)

  /**
   * A point carrying a **crossing marker**, which only circle clipping emits.
   *
   * `m` is 0 for an ordinary vertex, and 2 or 3 for one the clip put on the circle's edge. It
   * survives into the buffered segments, where `clipRejoin` reads it to tell a ring that genuinely
   * closes on itself from one whose two ends merely landed on the same place.
   */
  open fun point(x: Double, y: Double, m: Double) {
    point(x, y)
  }

  open fun lineStart() {}

  open fun lineEnd() {}

  open fun polygonStart() {}

  open fun polygonEnd() {}

  /** The whole globe, which only a `{"type": "Sphere"}` geometry and a clip interpolation emit. */
  open fun sphere() {}
}

/** A stream that forwards everything to another; the base every stage here is built on. */
internal open class DelegatingStream(protected val sink: GeoStream) : GeoStream() {
  override fun point(x: Double, y: Double) = sink.point(x, y)

  override fun point(x: Double, y: Double, m: Double) = sink.point(x, y, m)

  override fun lineStart() = sink.lineStart()

  override fun lineEnd() = sink.lineEnd()

  override fun polygonStart() = sink.polygonStart()

  override fun polygonEnd() = sink.polygonEnd()

  override fun sphere() = sink.sphere()
}

/**
 * Pushes a GeoJSON object through a stream, `d3-geo/src/stream.js`.
 *
 * The one detail worth knowing: a polygon's rings are streamed with their **last point dropped**,
 * because GeoJSON closes a ring by repeating its first point and the stream closes it with
 * `lineEnd`. Streaming it would put a zero-length segment on every ring and change what the
 * clipping code sees.
 */
internal object GeoJsonStream {

  fun stream(value: VegaValue, sink: GeoStream) {
    when (value.field("type").asString()) {
      "Feature" -> geometry(value.field("geometry"), sink)
      "FeatureCollection" ->
        for (feature in list(value.field("features"))) geometry(feature.field("geometry"), sink)
      else -> geometry(value, sink)
    }
  }

  private fun geometry(value: VegaValue, sink: GeoStream) {
    when (value.field("type").asString()) {
      "Sphere" -> sink.sphere()
      "Point" -> point(value.field("coordinates"), sink)
      "MultiPoint" -> for (p in list(value.field("coordinates"))) point(p, sink)
      "LineString" -> line(list(value.field("coordinates")), sink, closed = false)
      "MultiLineString" ->
        for (l in list(value.field("coordinates"))) line(list(l), sink, closed = false)
      "Polygon" -> polygon(list(value.field("coordinates")), sink)
      "MultiPolygon" -> for (p in list(value.field("coordinates"))) polygon(list(p), sink)
      "GeometryCollection" -> for (g in list(value.field("geometries"))) geometry(g, sink)
      else -> Unit
    }
  }

  private fun polygon(rings: List<VegaValue>, sink: GeoStream) {
    sink.polygonStart()
    for (ring in rings) line(list(ring), sink, closed = true)
    sink.polygonEnd()
  }

  private fun line(coordinates: List<VegaValue>, sink: GeoStream, closed: Boolean) {
    sink.lineStart()
    val n = coordinates.size - if (closed) 1 else 0
    for (index in 0 until n) point(coordinates[index], sink)
    sink.lineEnd()
  }

  private fun point(value: VegaValue, sink: GeoStream) {
    val values = list(value)
    if (values.size < 2) return
    sink.point(values[0].asDouble(), values[1].asDouble())
  }

  private fun list(value: VegaValue): List<VegaValue> =
    (value as? VegaValue.Arr)?.values ?: emptyList()
}
