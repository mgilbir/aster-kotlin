package dev.aster.vega.dataflow.geo

import dev.aster.vega.dataflow.geo.GeoMath.EPSILON
import dev.aster.vega.dataflow.geo.GeoMath.HALF_PI
import dev.aster.vega.dataflow.geo.GeoMath.PI_
import dev.aster.vega.dataflow.geo.GeoMath.RADIANS
import dev.aster.vega.dataflow.geo.GeoMath.TAU
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A point transform: longitude and latitude in radians to plane coordinates, or the reverse. */
internal fun interface RawProjection {
  fun project(lambda: Double, phi: Double): DoubleArray
}

/**
 * The three-angle rotation every projection applies before projecting anything.
 *
 * `rotate: [-100, 40]` on a map of the United States is this: the globe is turned so that the point
 * of interest sits where the projection is least distorted. Ported from `d3-geo/src/rotation.js`.
 */
internal class Rotation(deltaLambda: Double, deltaPhi: Double, deltaGamma: Double) {
  private val dl = deltaLambda % TAU
  private val cosDeltaPhi = cos(deltaPhi)
  private val sinDeltaPhi = sin(deltaPhi)
  private val cosDeltaGamma = cos(deltaGamma)
  private val sinDeltaGamma = sin(deltaGamma)
  private val hasPhiGamma = deltaPhi != 0.0 || deltaGamma != 0.0

  fun forward(lambda: Double, phi: Double): DoubleArray {
    if (dl == 0.0 && !hasPhiGamma) return identity(lambda, phi)
    var l = lambda
    var p = phi
    if (dl != 0.0) {
      l += dl
      if (abs(l) > PI_) l -= (l / TAU).roundToLong() * TAU
    }
    if (!hasPhiGamma) return doubleArrayOf(l, p)
    val cosPhi = cos(p)
    val x = cos(l) * cosPhi
    val y = sin(l) * cosPhi
    val z = sin(p)
    val k = z * cosDeltaPhi + x * sinDeltaPhi
    l = atan2(y * cosDeltaGamma - k * sinDeltaGamma, x * cosDeltaPhi - z * sinDeltaPhi)
    p = GeoMath.asin(k * cosDeltaGamma + y * sinDeltaGamma)
    return doubleArrayOf(l, p)
  }

  fun invert(lambda: Double, phi: Double): DoubleArray {
    if (dl == 0.0 && !hasPhiGamma) return identity(lambda, phi)
    var l = lambda
    var p = phi
    if (hasPhiGamma) {
      val cosPhi = cos(p)
      val x = cos(l) * cosPhi
      val y = sin(l) * cosPhi
      val z = sin(p)
      val k = z * cosDeltaGamma - y * sinDeltaGamma
      l = atan2(y * cosDeltaGamma + z * sinDeltaGamma, x * cosDeltaPhi + k * sinDeltaPhi)
      p = GeoMath.asin(k * cosDeltaPhi - x * sinDeltaPhi)
    }
    if (dl != 0.0) {
      l += -dl
      if (abs(l) > PI_) l -= (l / TAU).roundToLong() * TAU
    }
    return doubleArrayOf(l, p)
  }

  private fun identity(lambda: Double, phi: Double): DoubleArray {
    var l = lambda
    if (abs(l) > PI_) l -= (l / TAU).roundToLong() * TAU
    return doubleArrayOf(l, phi)
  }
}

/**
 * Adaptive resampling: a straight line on the globe is a curve on the map.
 *
 * Every segment is bisected *along the great circle* and kept subdividing while the midpoint is
 * further than the precision from the straight line between the ends. This is what makes a
 * graticule curve and a long border follow the projection instead of cutting across it — and it is
 * also why the number of points in the output is not the number of points in the input, so nothing
 * here can be written as a per-point map. Ported from `d3-geo/src/projection/resample.js`.
 */
internal class ResampleStream(
  private val sink: GeoStream,
  private val project: (Double, Double) -> DoubleArray,
  private val delta2: Double,
) : GeoStream() {

  private var lambda00 = 0.0
  private var x00 = 0.0
  private var y00 = 0.0
  private var a00 = 0.0
  private var b00 = 0.0
  private var c00 = 0.0
  private var lambda0 = 0.0
  private var x0 = Double.NaN
  private var y0 = 0.0
  private var a0 = 0.0
  private var b0 = 0.0
  private var c0 = 0.0

  private var inLine = false
  private var inRing = false
  private var ringFirst = false

  override fun point(x: Double, y: Double) {
    if (!inLine) {
      val p = project(x, y)
      sink.point(p[0], p[1])
      return
    }
    if (ringFirst) {
      linePoint(x, y)
      lambda00 = x
      x00 = x0
      y00 = y0
      a00 = a0
      b00 = b0
      c00 = c0
      ringFirst = false
      return
    }
    linePoint(x, y)
  }

  override fun lineStart() {
    x0 = Double.NaN
    inLine = true
    if (inRing) ringFirst = true
    sink.lineStart()
  }

  override fun lineEnd() {
    if (inRing) {
      resampleLineTo(x0, y0, lambda0, a0, b0, c0, x00, y00, lambda00, a00, b00, c00, MAX_DEPTH)
    }
    inLine = false
    sink.lineEnd()
  }

  override fun polygonStart() {
    sink.polygonStart()
    inRing = true
  }

  override fun polygonEnd() {
    sink.polygonEnd()
    inRing = false
  }

  override fun sphere() = sink.sphere()

  private fun linePoint(lambda: Double, phi: Double) {
    val c = GeoMath.cartesian(doubleArrayOf(lambda, phi))
    val p = project(lambda, phi)
    resampleLineTo(
      x0,
      y0,
      lambda0,
      a0,
      b0,
      c0,
      p[0].also { x0 = it },
      p[1].also { y0 = it },
      lambda.also { lambda0 = it },
      c[0].also { a0 = it },
      c[1].also { b0 = it },
      c[2].also { c0 = it },
      MAX_DEPTH,
    )
    sink.point(x0, y0)
  }

  @Suppress("LongParameterList")
  private fun resampleLineTo(
    x0: Double,
    y0: Double,
    lambda0: Double,
    a0: Double,
    b0: Double,
    c0: Double,
    x1: Double,
    y1: Double,
    lambda1: Double,
    a1: Double,
    b1: Double,
    c1: Double,
    depth: Int,
  ) {
    val dx = x1 - x0
    val dy = y1 - y0
    val d2 = dx * dx + dy * dy
    // Negated rather than written the other way round, because `d2` is **NaN** for the first point
    // of every line — there is no previous point — and `NaN > x` is false where `NaN <= x` is also
    // false. Getting this backwards emits the first vertex sixteen times and draws nothing wrong.
    if (!(d2 > 4 * delta2) || depth <= 0) return

    var a = a0 + a1
    var b = b0 + b1
    var c = c0 + c1
    val m = sqrt(a * a + b * b + c * c)
    c /= m
    val phi2 = GeoMath.asin(c)
    val lambda2 =
      if (abs(abs(c) - 1) < EPSILON || abs(lambda0 - lambda1) < EPSILON) (lambda0 + lambda1) / 2
      else atan2(b, a)
    val p = project(lambda2, phi2)
    val x2 = p[0]
    val y2 = p[1]
    val dx2 = x2 - x0
    val dy2 = y2 - y0
    val dz = dy * dx2 - dx * dy2
    // Three tests, and all three are needed: the midpoint's distance from the chord, whether it
    // lands anywhere near the middle of it, and how far apart the ends are on the sphere.
    if (
      dz * dz / d2 > delta2 ||
        abs((dx * dx2 + dy * dy2) / d2 - 0.5) > 0.3 ||
        a0 * a1 + b0 * b1 + c0 * c1 < COS_MIN_DISTANCE
    ) {
      a /= m
      b /= m
      resampleLineTo(x0, y0, lambda0, a0, b0, c0, x2, y2, lambda2, a, b, c, depth - 1)
      sink.point(x2, y2)
      resampleLineTo(x2, y2, lambda2, a, b, c, x1, y1, lambda1, a1, b1, c1, depth - 1)
    }
  }

  private companion object {
    const val MAX_DEPTH = 16
    val COS_MIN_DISTANCE = cos(30 * RADIANS)
  }
}

/** Resampling turned off, which is what `precision(0)` means. */
internal class NoResampleStream(
  target: GeoStream,
  private val project: (Double, Double) -> DoubleArray,
) : DelegatingStream(target) {
  override fun point(x: Double, y: Double) {
    val p = project(x, y)
    sink.point(p[0], p[1])
  }
}

/**
 * A d3-geo projection: rotate, clip, project, resample, clip again.
 *
 * The order is not negotiable and it is not obvious. Clipping happens **twice** and in different
 * spaces: the pre-clip cuts on the sphere, in radians, before anything is projected — which is the
 * only place the antimeridian exists — and the post-clip cuts the flat result, which is how a
 * mercator map stops at the poles instead of running to infinity.
 *
 * Ported from `d3-geo/src/projection/index.js`.
 */
internal class Projection(private val raw: RawProjection) {
  var scale: Double = 150.0
    private set

  private var translateX = 480.0
  private var translateY = 250.0
  private var centreLambda = 0.0
  private var centrePhi = 0.0
  private var deltaLambda = 0.0
  private var deltaPhi = 0.0
  private var deltaGamma = 0.0
  private var alpha = 0.0
  private var reflectX = 1.0
  private var reflectY = 1.0
  private var delta2 = 0.5

  private var preclip: (GeoStream) -> GeoStream = ClipAntimeridian::stream
  private var postclip: ((GeoStream) -> GeoStream)? = null

  /**
   * `mercator`'s extra rule, which every projection in its family inherits.
   *
   * The formula is infinite in latitude, so upstream clips the *output* to a square of side `pi *
   * scale` around the projected origin — one full turn of the world. Without it a polygon reaching
   * a pole runs to infinity and takes the chart's bounds with it, and it has to be re-applied after
   * anything that moves the origin.
   */
  var clipsToOneTurn: Boolean = false
    set(value) {
      field = value
      recenter()
    }

  private lateinit var rotation: Rotation
  private lateinit var transform: (Double, Double) -> DoubleArray

  init {
    recenter()
  }

  fun scale(value: Double): Projection {
    scale = value
    return recenter()
  }

  fun translate(x: Double, y: Double): Projection {
    translateX = x
    translateY = y
    return recenter()
  }

  fun center(lambda: Double, phi: Double): Projection {
    centreLambda = lambda % 360 * RADIANS
    centrePhi = phi % 360 * RADIANS
    return recenter()
  }

  fun rotate(values: DoubleArray): Projection {
    deltaLambda = values.getOrElse(0) { 0.0 } % 360 * RADIANS
    deltaPhi = values.getOrElse(1) { 0.0 } % 360 * RADIANS
    deltaGamma = if (values.size > 2) values[2] % 360 * RADIANS else 0.0
    return recenter()
  }

  fun angle(value: Double): Projection {
    alpha = value % 360 * RADIANS
    return recenter()
  }

  fun reflect(x: Boolean, y: Boolean): Projection {
    reflectX = if (x) -1.0 else 1.0
    reflectY = if (y) -1.0 else 1.0
    return recenter()
  }

  fun precision(value: Double): Projection {
    delta2 = value * value
    return this
  }

  fun clipExtent(x0: Double, y0: Double, x1: Double, y1: Double): Projection {
    postclip = { sink -> ClipRectangle(x0, y0, x1, y1).stream(sink) }
    return this
  }

  fun clearClipExtent(): Projection {
    postclip = null
    return this
  }

  /** The full pipeline, wrapped around whatever will finally draw. */
  fun stream(sink: GeoStream): GeoStream {
    val clipped = postclip?.invoke(sink) ?: sink
    val resampled: GeoStream =
      if (delta2 != 0.0) ResampleStream(clipped, transform, delta2)
      else NoResampleStream(clipped, transform)
    val preclipped = preclip(resampled)
    return RotateStream(preclipped, rotation)
  }

  /** One point, projected — what a `geopoint` transform needs and a path does not. */
  fun apply(lambda: Double, phi: Double): DoubleArray {
    val rotated = rotation.forward(lambda * RADIANS, phi * RADIANS)
    return transform(rotated[0], rotated[1])
  }

  private fun recenter(): Projection {
    val centre = scaleTranslateRotate(scale, 0.0, 0.0, reflectX, reflectY, alpha)
    val projected = raw.project(centreLambda, centrePhi)
    val at = centre(projected[0], projected[1])
    val place =
      scaleTranslateRotate(
        scale,
        translateX - at[0],
        translateY - at[1],
        reflectX,
        reflectY,
        alpha,
      )
    rotation = Rotation(deltaLambda, deltaPhi, deltaGamma)
    transform = { lambda, phi ->
      val p = raw.project(lambda, phi)
      place(p[0], p[1])
    }
    if (clipsToOneTurn) {
      // The projected origin, which is where the square is centred. Upstream writes it as
      // `m(rotation(m.rotate()).invert([0, 0]))` — rotating a point straight back through the
      // rotation that produced it leaves the untransformed origin, so this is the same thing
      // without the round trip.
      val k = PI_ * scale
      val t = transform(0.0, 0.0)
      postclip = { sink -> ClipRectangle(t[0] - k, t[1] - k, t[0] + k, t[1] + k).stream(sink) }
    }
    return this
  }

  private fun scaleTranslateRotate(
    k: Double,
    dx: Double,
    dy: Double,
    sx: Double,
    sy: Double,
    alpha: Double,
  ): (Double, Double) -> DoubleArray {
    if (alpha == 0.0) {
      return { x, y -> doubleArrayOf(dx + k * (x * sx), dy - k * (y * sy)) }
    }
    val cosAlpha = cos(alpha)
    val sinAlpha = sin(alpha)
    val a = cosAlpha * k
    val b = sinAlpha * k
    return { x, y ->
      val px = x * sx
      val py = y * sy
      doubleArrayOf(a * px - b * py + dx, dy - b * px - a * py)
    }
  }

  /** Degrees in, radians out, rotated: the first stage of the pipeline. */
  private class RotateStream(target: GeoStream, private val rotation: Rotation) :
    DelegatingStream(target) {
    override fun point(x: Double, y: Double) {
      val r = rotation.forward(x * RADIANS, y * RADIANS)
      sink.point(r[0], r[1])
    }
  }
}

/** The raw projections the corpus names, each one a formula and its inverse. */
internal object RawProjections {

  val mercator = RawProjection { lambda, phi ->
    doubleArrayOf(lambda, ln(tan((HALF_PI + phi) / 2)))
  }

  val equirectangular = RawProjection { lambda, phi -> doubleArrayOf(lambda, phi) }
}

/** The projections a specification can name, each built with whatever extra rule it carries. */
internal object Projections {

  fun mercator(): Projection = Projection(RawProjections.mercator).also { it.clipsToOneTurn = true }

  fun equirectangular(): Projection = Projection(RawProjections.equirectangular)

  /** Null for a name this engine does not have, so a caller can say so rather than guess. */
  fun byName(name: String): Projection? =
    when (name.lowercase()) {
      "mercator" -> mercator()
      "equirectangular" -> equirectangular()
      else -> null
    }

  val names: Set<String> = setOf("mercator", "equirectangular")
}
