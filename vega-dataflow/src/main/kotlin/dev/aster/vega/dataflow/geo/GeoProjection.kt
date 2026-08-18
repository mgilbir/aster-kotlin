package dev.aster.vega.dataflow.geo

import dev.aster.vega.dataflow.geo.GeoMath.DEGREES
import dev.aster.vega.dataflow.geo.GeoMath.EPSILON
import dev.aster.vega.dataflow.geo.GeoMath.HALF_PI
import dev.aster.vega.dataflow.geo.GeoMath.PI_
import dev.aster.vega.dataflow.geo.GeoMath.RADIANS
import dev.aster.vega.dataflow.geo.GeoMath.TAU
import dev.aster.vega.model.VegaValue
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A point transform: longitude and latitude in radians to plane coordinates. */
internal fun interface RawProjection {
  fun project(lambda: Double, phi: Double): DoubleArray
}

/**
 * A projection that can be read backwards: a point on the page to a place on the globe.
 *
 * Separate from [RawProjection] because not every formula has a closed form inverse, and one that
 * does not should say so rather than answer something plausible.
 */
internal interface InvertibleRaw : RawProjection {
  fun invert(x: Double, y: Double): DoubleArray
}

/** A raw projection with both directions, which is how d3 writes the invertible ones. */
internal fun invertible(
  forward: (Double, Double) -> DoubleArray,
  backward: (Double, Double) -> DoubleArray,
): InvertibleRaw =
  object : InvertibleRaw {
    override fun project(lambda: Double, phi: Double): DoubleArray = forward(lambda, phi)

    override fun invert(x: Double, y: Double): DoubleArray = backward(x, y)
  }

/**
 * Anything a `geoshape` can draw through.
 *
 * Two implementations, and the second is why this exists at all: `albersUsa` is not a projection
 * but *three*, each with its own clip rectangle, and geometry is pushed through all three at once.
 */
internal interface GeoProjector {
  fun stream(sink: GeoStream): GeoStream

  /** One point, for a `geopoint` transform; null when the point falls outside every piece. */
  fun apply(lambda: Double, phi: Double): DoubleArray?
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
/**
 * The scale a `fit` is measured against, which is d3's default for every projection family.
 *
 * A fitted projection's scale comes out as a multiple of this, so it has to be the number d3 uses
 * rather than any convenient one.
 */
private const val FIT_REFERENCE_SCALE = 150.0

internal class Projection(private var raw: RawProjection) : GeoProjector {
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

  /**
   * `geoIdentity`: coordinates already on the page rather than on a globe.
   *
   * Two differences, and both matter. There is **no spherical clipping** — a ring that would
   * enclose a pole on a globe is just a ring here, and putting a five-sided polygon through the
   * antimeridian clip made it measure as the whole world. And the vertical axis is **not flipped**:
   * a map projection negates `y` so that north is up, where an identity projection leaves the
   * coordinates as the data wrote them.
   *
   * Everything else — the scale, the translation, the reflections, the plane rotation, `clipExtent`
   * and `fitExtent` — is shared, which is why this is a flag rather than a second class.
   */
  var planar: Boolean = false
    set(value) {
      field = value
      if (value) preclip = { it }
      recenter()
    }

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
  private var placeInvert: ((Double, Double) -> DoubleArray)? = null

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

  /**
   * `transverseMercator`, which is the same formula read down the page rather than across.
   *
   * Upstream expresses that by replacing the `center` and `rotate` accessors: a centre given as
   * `[x, y]` is stored as `[-y, x]`, and a rotation gains a quarter turn. A specification that
   * writes a longitude means a longitude whichever way the projection runs.
   */
  var swapsAxes: Boolean = false

  fun center(lambda: Double, phi: Double): Projection {
    val x = if (swapsAxes) -phi else lambda
    val y = if (swapsAxes) lambda else phi
    centreLambda = x % 360 * RADIANS
    centrePhi = y % 360 * RADIANS
    return recenter()
  }

  fun rotate(values: DoubleArray): Projection {
    deltaLambda = values.getOrElse(0) { 0.0 } % 360 * RADIANS
    deltaPhi = values.getOrElse(1) { 0.0 } % 360 * RADIANS
    val gamma = if (values.size > 2) values[2] else 0.0
    deltaGamma = (if (swapsAxes) gamma + 90 else gamma) % 360 * RADIANS
    return recenter()
  }

  fun angle(value: Double): Projection {
    alpha = value % 360 * RADIANS
    return recenter()
  }

  /**
   * The two standard parallels of a conic projection, which change the formula rather than tune it.
   *
   * A cone touching the globe at 29.5 and 45.5 degrees is a different surface from one touching at
   * 0 and 60, so upstream rebuilds the raw projection rather than parameterising it.
   */
  fun parallels(y0: Double, y1: Double): Projection {
    val builder = conic ?: return this
    raw = builder(y0 * RADIANS, y1 * RADIANS)
    return recenter()
  }

  /** Set for the conic family, which needs its raw projection rebuilt when the parallels move. */
  var conic: ((Double, Double) -> RawProjection)? = null

  fun reflect(x: Boolean, y: Boolean): Projection {
    reflectX = if (x) -1.0 else 1.0
    reflectY = if (y) -1.0 else 1.0
    return recenter()
  }

  /**
   * A clip angle in degrees: the circle beyond which the projection stops meaning anything.
   *
   * Zero restores antimeridian cutting, which is what a projection that covers the whole world uses
   * instead.
   */
  fun clipAngle(value: Double): Projection {
    preclip =
      if (value != 0.0) {
        val circle = ClipCircle(value * RADIANS)
        ({ sink: GeoStream -> circle.stream(sink) })
      } else {
        ClipAntimeridian::stream
      }
    return this
  }

  fun precision(value: Double): Projection {
    delta2 = value * value
    return this
  }

  fun clipExtent(x0: Double, y0: Double, x1: Double, y1: Double): Projection {
    userClip = doubleArrayOf(x0, y0, x1, y1)
    return reclip()
  }

  fun clearClipExtent(): Projection {
    userClip = null
    return reclip()
  }

  /**
   * The rectangle the specification asked to clip to, as distinct from the one a projection chooses
   * for itself.
   *
   * The distinction is d3's and it is load-bearing twice over. A mercator clips to the square its
   * own scale makes one full turn of the world, and that square has to be **recomputed** whenever
   * the scale or the translation moves — so a user extent cannot simply replace it, it has to be
   * intersected with it and re-intersected afterwards. And a `fit` measures the geometry with the
   * user's clip removed: the automatic one must stay, because it is what keeps a mercator from
   * drawing the world several times over, while the user's is in screen coordinates the fit has not
   * chosen yet.
   */
  private var userClip: DoubleArray? = null

  /** Rebuilds [postclip] from the automatic extent and the user's, in d3's `reclip`. */
  private fun reclip(): Projection {
    val user = userClip
    if (!clipsToOneTurn) {
      postclip = user?.let { r ->
        { sink: GeoStream -> ClipRectangle(r[0], r[1], r[2], r[3]).stream(sink) }
      }
      return this
    }
    val k = PI_ * scale
    // The projected origin, which is where the square is centred. Upstream writes it as
    // `m(rotation(m.rotate()).invert([0, 0]))` — rotating a point straight back through the
    // rotation
    // that produced it leaves the untransformed origin, so this is the same thing without the round
    // trip.
    val t = transform(0.0, 0.0)
    val rect =
      when {
        user == null -> doubleArrayOf(t[0] - k, t[1] - k, t[0] + k, t[1] + k)
        // A transverse mercator wraps down the page rather than across, so the turn bounds *y*.
        swapsAxes ->
          doubleArrayOf(user[0], maxOf(t[1] - k, user[1]), user[2], minOf(t[1] + k, user[3]))
        else -> doubleArrayOf(maxOf(t[0] - k, user[0]), user[1], minOf(t[0] + k, user[2]), user[3])
      }
    postclip = { sink -> ClipRectangle(rect[0], rect[1], rect[2], rect[3]).stream(sink) }
    return this
  }

  /**
   * Scales and centres the projection so that [geojson] exactly fills the rectangle given,
   * `d3-geo/src/projection/fit.js`.
   *
   * The sequence is upstream's and none of it is arbitrary. The projection is first reset to scale
   * **150** with no translation, because a fit is measured against d3's reference scale and the
   * result is a multiple of it. Any `clipExtent` is *removed* for the measurement and restored
   * afterwards, since a clip in screen coordinates would cut the geometry to a rectangle the fit
   * has not chosen yet and the two would chase each other. And the scale factor is the **smaller**
   * of the two ratios, so the geometry fits inside the box rather than filling it and spilling
   * over.
   */
  fun fitExtent(
    x0: Double,
    y0: Double,
    x1: Double,
    y1: Double,
    geojson: VegaValue,
  ): Projection {
    // The *user's* clip is removed for the measurement and put back afterwards; the automatic one
    // is left alone, because it moves with the scale and the fit is about to move the scale.
    val clip = userClip
    userClip = null
    scale(FIT_REFERENCE_SCALE).translate(0.0, 0.0)
    val sink = PathBoundsSink()
    GeoJsonStream.stream(geojson, stream(sink))
    val bounds = sink.result()
    userClip = clip
    reclip()
    if (bounds == null) return this
    val width = x1 - x0
    val height = y1 - y0
    val spanX = bounds[2] - bounds[0]
    val spanY = bounds[3] - bounds[1]
    if (spanX <= 0.0 || spanY <= 0.0) return this
    val k = kotlin.math.min(width / spanX, height / spanY)
    val cx = x0 + (width - k * (bounds[2] + bounds[0])) / 2.0
    val cy = y0 + (height - k * (bounds[3] + bounds[1])) / 2.0
    return scale(FIT_REFERENCE_SCALE * k).translate(cx, cy)
  }

  /** The full pipeline, wrapped around whatever will finally draw. */
  override fun stream(sink: GeoStream): GeoStream {
    val clipped = postclip?.invoke(sink) ?: sink
    val resampled: GeoStream =
      if (delta2 != 0.0) ResampleStream(clipped, transform, delta2)
      else NoResampleStream(clipped, transform)
    val preclipped = preclip(resampled)
    return RotateStream(preclipped, rotation)
  }

  /**
   * A point on the page read back to longitude and latitude, or null for a projection with no
   * closed form inverse.
   *
   * The pipeline undone in reverse: the placement, then the formula, then the rotation. Only the
   * point transforms are undone — clipping and resampling are not functions and have no inverse —
   * so this is exact for a point and meaningless for a shape.
   */
  fun invert(x: Double, y: Double): DoubleArray? {
    val formula = raw as? InvertibleRaw ?: return null
    val plane = placeInvert?.invoke(x, y) ?: return null
    val place = formula.invert(plane[0], plane[1])
    val turned = rotation.invert(place[0], place[1])
    return doubleArrayOf(turned[0] * DEGREES, turned[1] * DEGREES)
  }

  /** One point, projected — what a `geopoint` transform needs and a path does not. */
  override fun apply(lambda: Double, phi: Double): DoubleArray {
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
    // The placement read backwards, for `invert`. A *rotated* plane has none here — d3 has one and
    // nothing in the corpus uses it, so it is refused rather than guessed at.
    val dx = translateX - at[0]
    val dy = translateY - at[1]
    placeInvert =
      if (alpha != 0.0) null
      else {
        { x, y -> doubleArrayOf((x - dx) / scale * reflectX, (dy - y) / scale * reflectY) }
      }
    if (clipsToOneTurn) reclip()
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
    // A planar projection adds where a spherical one subtracts: `geoIdentity` is `k * y + ty` and
    // `geoProjection` is `ty - k * y`, because only the second is trying to put north at the top.
    val flip = if (planar) -1.0 else 1.0
    if (alpha == 0.0) {
      return { x, y -> doubleArrayOf(dx + k * (x * sx), dy - flip * k * (y * sy)) }
    }
    val cosAlpha = cos(alpha)
    val sinAlpha = sin(alpha)
    val a = cosAlpha * k
    val b = sinAlpha * k
    return { x, y ->
      val px = x * sx
      val py = y * sy
      doubleArrayOf(a * px - b * py + dx, dy - flip * (b * px + a * py))
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

/**
 * The raw projections: longitude and latitude in radians to a point on an abstract plane.
 *
 * Each is transcribed from d3-geo's own `projection` sources rather than from a textbook. The
 * formulas agree with the textbooks; the *constants* often do not, and a projection is judged by
 * whether it lands the same pixel as upstream.
 */
internal object RawProjections {

  val mercator =
    invertible(
      { lambda, phi -> doubleArrayOf(lambda, ln(tan((HALF_PI + phi) / 2))) },
      { x, y -> doubleArrayOf(x, 2 * atan(exp(y)) - HALF_PI) },
    )

  val equirectangular =
    invertible({ lambda, phi -> doubleArrayOf(lambda, phi) }, { x, y -> doubleArrayOf(x, y) })

  /**
   * The **planar** identity, for `geoIdentity`: coordinates that are already on the page.
   *
   * Divides by `RADIANS` because the wrapper multiplied by it. The two cancel, so a point reaches
   * the scale-and-translate stage exactly as the data wrote it — which is what makes this the
   * identity rather than the equirectangular projection, whose raw *is* the identity in radian
   * space.
   */
  val planar =
    invertible(
      { lambda, phi -> doubleArrayOf(lambda / RADIANS, phi / RADIANS) },
      { x, y -> doubleArrayOf(x * RADIANS, y * RADIANS) },
    )

  val orthographic =
    invertible(
      { lambda, phi -> doubleArrayOf(cos(phi) * sin(lambda), sin(phi)) },
      azimuthalInvert { GeoMath.asin(it) },
    )

  val gnomonic =
    invertible(
      { lambda, phi ->
        val cy = cos(phi)
        val k = cos(lambda) * cy
        doubleArrayOf(cy * sin(lambda) / k, sin(phi) / k)
      },
      azimuthalInvert { atan(it) },
    )

  val stereographic =
    invertible(
      { lambda, phi ->
        val cy = cos(phi)
        val k = 1 + cos(lambda) * cy
        doubleArrayOf(cy * sin(lambda) / k, sin(phi) / k)
      },
      azimuthalInvert { 2 * atan(it) },
    )

  val azimuthalEqualArea =
    invertible(
      azimuthal { cxcy -> sqrt(2 / (1 + cxcy)) },
      azimuthalInvert { 2 * GeoMath.asin(it / 2) },
    )

  val azimuthalEquidistant =
    invertible(
      azimuthal { cxcy ->
        val c = GeoMath.acos(cxcy)
        if (c == 0.0) c else c / sin(c)
      },
      azimuthalInvert { it },
    )

  val transverseMercator =
    invertible(
      { lambda, phi -> doubleArrayOf(ln(tan((HALF_PI + phi) / 2)), -lambda) },
      { x, y -> doubleArrayOf(-y, 2 * atan(exp(x)) - HALF_PI) },
    )

  /**
   * `mollweide`: the one extended projection Vega itself ships.
   *
   * An equal-area ellipse of the world, and the only projection here whose forward direction is
   * **iterative** — the auxiliary angle solves `theta + sin theta = pi sin phi` by Newton's method,
   * capped at thirty steps the way upstream caps it. A different cap or a different tolerance gives
   * a slightly different ellipse.
   */
  val mollweide =
    invertible(
      { lambda, phi ->
        val theta = mollweideTheta(PI_, phi)
        doubleArrayOf(SQRT2 / HALF_PI * lambda * cos(theta), SQRT2 * sin(theta))
      },
      { x, y ->
        val theta = GeoMath.asin(y / SQRT2)
        doubleArrayOf(
          x / (SQRT2 / HALF_PI * cos(theta)),
          GeoMath.asin((2 * theta + sin(2 * theta)) / PI_),
        )
      },
    )

  private fun mollweideTheta(cp: Double, phi0: Double): Double {
    val target = cp * sin(phi0)
    var phi = phi0
    var steps = 30
    while (true) {
      val delta = (phi + sin(phi) - target) / (1 + cos(phi))
      phi -= delta
      steps--
      if (abs(delta) <= EPSILON || steps <= 0) break
    }
    return phi / 2
  }

  private val SQRT2 = sqrt(2.0)

  /**
   * The azimuthal family's shared inverse: a distance from the centre back to an angle.
   *
   * They differ only in how that distance maps to an angle, which is the function passed in.
   */
  private fun azimuthalInvert(angle: (Double) -> Double): (Double, Double) -> DoubleArray =
    { x, y ->
      val z = sqrt(x * x + y * y)
      val c = angle(z)
      val sc = sin(c)
      val cc = cos(c)
      doubleArrayOf(atan2(x * sc, z * cc), GeoMath.asin(if (z == 0.0) 0.0 else y * sc / z))
    }

  val naturalEarth1 = RawProjection { lambda, phi ->
    val phi2 = phi * phi
    val phi4 = phi2 * phi2
    doubleArrayOf(
      lambda *
        (0.8707 - 0.131979 * phi2 +
          phi4 * (-0.013791 + phi4 * (0.003971 * phi2 - 0.001529 * phi4))),
      phi * (1.007226 + phi2 * (0.015085 + phi4 * (-0.044475 + 0.028874 * phi2 - 0.005916 * phi4))),
    )
  }

  val equalEarth = RawProjection { lambda, phi ->
    val l = GeoMath.asin(EQUAL_EARTH_M * sin(phi))
    val l2 = l * l
    val l6 = l2 * l2 * l2
    doubleArrayOf(
      lambda * cos(l) / (EQUAL_EARTH_M * (A1 + 3 * A2 * l2 + l6 * (7 * A3 + 9 * A4 * l2))),
      l * (A1 + A2 * l2 + l6 * (A3 + A4 * l2)),
    )
  }

  /**
   * The azimuthal family, which differ only in how far out a given angle is drawn.
   *
   * The `Infinity` guard is upstream's: `gnomonic` at ninety degrees divides by zero, and d3
   * answers `[2, 0]` rather than a NaN that would poison every bound downstream.
   */
  private fun azimuthal(scale: (Double) -> Double): (Double, Double) -> DoubleArray =
    { lambda, phi ->
      val cx = cos(lambda)
      val cy = cos(phi)
      val k = scale(cx * cy)
      if (k == Double.POSITIVE_INFINITY) doubleArrayOf(2.0, 0.0)
      else doubleArrayOf(k * cy * sin(lambda), k * sin(phi))
    }

  /**
   * Invertible, which matters out of proportion to the formula: `albers` is this projection pointed
   * at the United States, and `albersUsa` is three of them. Without the inverse nothing could read
   * a point on any map of the United States back to a place — the family Vega-Lite reaches for by
   * default there — and a brush that asks where the middle of the plot *is* got no answer at all.
   */
  fun conicEqualArea(y0: Double, y1: Double): RawProjection {
    val sy0 = sin(y0)
    val n = (sy0 + sin(y1)) / 2
    // Parallels symmetric about the equator make the cone a cylinder, and the formula degenerates.
    if (abs(n) < EPSILON) return cylindricalEqualArea(y0)
    val c = 1 + sy0 * (2 * n - sy0)
    val r0 = sqrt(c) / n
    return invertible(
      { lambda, phi ->
        val r = sqrt(c - 2 * n * sin(phi)) / n
        doubleArrayOf(r * sin(lambda * n), r0 - r * cos(lambda * n))
      },
      { x, y ->
        val r0y = r0 - y
        // `atan2(x, |r0y|)` and then the sign put back: the cone's apex may be above or below the
        // plane, and taking the angle to a signed radius would fold the southern half onto the
        // northern one.
        val longitude = atan2(x, abs(r0y)) / n * sign(r0y)
        doubleArrayOf(longitude, asin((c - (x * x + r0y * r0y) * n * n) / (2 * n)))
      },
    )
  }

  private fun cylindricalEqualArea(phi0: Double): RawProjection {
    val cosPhi0 = cos(phi0)
    return invertible(
      { lambda, phi -> doubleArrayOf(lambda * cosPhi0, sin(phi) / cosPhi0) },
      { x, y -> doubleArrayOf(x / cosPhi0, asin(y * cosPhi0)) },
    )
  }

  fun conicEquidistant(y0: Double, y1: Double): RawProjection {
    val cy0 = cos(y0)
    val n = if (y0 == y1) sin(y0) else (cy0 - cos(y1)) / (y1 - y0)
    if (abs(n) < EPSILON) return equirectangular
    val g = cy0 / n + y0
    return RawProjection { lambda, phi ->
      val gy = g - phi
      val nl = n * lambda
      doubleArrayOf(gy * sin(nl), g - gy * cos(nl))
    }
  }

  fun conicConformal(y0: Double, y1: Double): RawProjection {
    val cy0 = cos(y0)
    val n = if (y0 == y1) sin(y0) else ln(cy0 / cos(y1)) / ln(tany(y1) / tany(y0))
    val f = cy0 * pow(tany(y0), n) / n
    // Parallels that make the cone a cylinder: the projection *is* mercator, not an approximation.
    if (n == 0.0) return mercator
    return RawProjection { lambda, phi ->
      // The pole the cone opens away from is infinitely far, so it is clamped rather than refused.
      var y = phi
      if (f > 0) {
        if (y < -HALF_PI + EPSILON) y = -HALF_PI + EPSILON
      } else {
        if (y > HALF_PI - EPSILON) y = HALF_PI - EPSILON
      }
      val r = f / pow(tany(y), n)
      doubleArrayOf(r * sin(n * lambda), f - r * cos(n * lambda))
    }
  }

  private fun tany(y: Double): Double = tan((HALF_PI + y) / 2)

  private fun pow(base: Double, exponent: Double): Double = base.pow(exponent)

  private const val A1 = 1.340264
  private const val A2 = -0.081106
  private const val A3 = 0.000893
  private const val A4 = 0.003796
  private val EQUAL_EARTH_M = sqrt(3.0) / 2
}

/**
 * The projections a specification can name, each built with d3's own defaults for its type.
 *
 * The defaults matter as much as the formulas: `orthographic` is unusable without its 90-degree
 * clip angle, `albers` is a conic whose standard parallels and centring make it a map of the United
 * States rather than of a cone, and `transverseMercator` is a mercator turned on its side by a
 * default rotation. A specification that names a type and nothing else expects all of it.
 */
internal object Projections {

  /** d3's `90 + epsilon` clip angle. The epsilon is a plain 1e-6 and the angle is in degrees. */
  private const val EPSILON_DEGREES = 1e-6

  fun byName(name: String): Projection? =
    when (name.lowercase()) {
      "mercator" -> Projection(RawProjections.mercator).apply { clipsToOneTurn = true }
      "equirectangular" -> Projection(RawProjections.equirectangular).scale(152.63)
      // `geoIdentity`: no globe at all. The coordinates are already on the page and only the scale,
      // the translation and a reflection touch them, which is what a chart drawing a map it has
      // projected elsewhere — or one whose "geometry" is a floor plan — asks for.
      //
      // Built out of the ordinary machinery rather than beside it, by undoing the degree-to-radian
      // step the wrapper applies: the raw formula divides by `RADIANS` again, so the pair arrives
      // at
      // the affine stage unchanged. That gives `clipExtent`, `fitExtent`, `reflect` and the
      // resampler
      // for nothing, all of which upstream's identity projection also has. Its defaults are its own
      // —
      // scale **1** and translate **[0, 0]**, where every spherical projection here starts at 150
      // and
      // the middle of a 960 by 500 page.
      "identity" ->
        Projection(RawProjections.planar).apply { planar = true }.scale(1.0).translate(0.0, 0.0)
      "orthographic" ->
        Projection(RawProjections.orthographic).scale(249.5).clipAngle(90 + EPSILON_DEGREES)
      "gnomonic" -> Projection(RawProjections.gnomonic).scale(144.049).clipAngle(60.0)
      "stereographic" -> Projection(RawProjections.stereographic).scale(250.0).clipAngle(142.0)
      "azimuthalequalarea" ->
        Projection(RawProjections.azimuthalEqualArea).scale(124.75).clipAngle(180 - 1e-3)
      "azimuthalequidistant" ->
        Projection(RawProjections.azimuthalEquidistant).scale(79.4188).clipAngle(180 - 1e-3)
      "naturalearth1" -> Projection(RawProjections.naturalEarth1).scale(175.295)
      "mollweide" -> Projection(RawProjections.mollweide).scale(169.529)
      "equalearth" -> Projection(RawProjections.equalEarth).scale(177.158)
      // A mercator turned on its side. The default rotation is applied **before** the axis swap
      // is switched on, because upstream sets it through the accessor the swap replaces.
      "transversemercator" ->
        Projection(RawProjections.transverseMercator).apply {
          clipsToOneTurn = true
          rotate(doubleArrayOf(0.0, 0.0, 90.0))
          scale(159.155)
          swapsAxes = true
        }
      "conicequalarea" -> conic(RawProjections::conicEqualArea).scale(155.424).center(0.0, 33.6442)
      "conicequidistant" ->
        conic(RawProjections::conicEquidistant).scale(131.154).center(0.0, 13.9389)
      "conicconformal" -> conic(RawProjections::conicConformal).scale(109.5).parallels(30.0, 30.0)
      // `albers` is not a projection of its own: it is `conicEqualArea` pointed at the United
      // States, and every one of these five numbers is part of what the name means.
      "albers" ->
        conic(RawProjections::conicEqualArea)
          .parallels(29.5, 45.5)
          .scale(1070.0)
          .translate(480.0, 250.0)
          .rotate(doubleArrayOf(96.0, 0.0))
          .center(-0.6, 38.7)
      else -> null
    }

  private fun conic(builder: (Double, Double) -> RawProjection): Projection {
    // d3's conic default: parallels at 0 and 60 degrees, which the caller usually replaces.
    val projection = Projection(builder(0.0, PI_ / 3))
    projection.conic = builder
    return projection
  }

  /** The composite ones, which are not a [Projection] and so cannot be built like one. */
  fun compositeByName(name: String): GeoProjector? =
    when (name.lowercase()) {
      "albersusa" -> AlbersUsa()
      else -> null
    }

  val names: Set<String> =
    setOf(
      "albers",
      "albersUsa",
      "azimuthalEqualArea",
      "identity",
      "azimuthalEquidistant",
      "conicConformal",
      "conicEqualArea",
      "conicEquidistant",
      "equalEarth",
      "equirectangular",
      "gnomonic",
      "mercator",
      "mollweide",
      "naturalEarth1",
      "orthographic",
      "stereographic",
      "transverseMercator",
    )
}

/**
 * `albersUsa`: the United States drawn as one map, with Alaska and Hawaii moved into the corner.
 *
 * Three projections, not one — an Albers for the lower forty-eight and a conic equal-area apiece
 * for Alaska and Hawaii — each clipped to its own rectangle so that only one of them ever draws a
 * given point. Geometry is pushed through all three at once and the clipping decides which answers.
 *
 * Every constant here is upstream's, and they are not adjustable: the map is laid out for a 960 by
 * 500 surface, and Alaska is drawn at 35% of the main scale, which is why it looks plausible rather
 * than the size of the rest of the country put together.
 */
internal class AlbersUsa : GeoProjector {
  private val lower48 = Projections.byName("albers")!!
  private val alaska =
    Projections.byName("conicEqualArea")!!.rotate(doubleArrayOf(154.0, 0.0))
      .center(-2.0, 58.5)
      .parallels(55.0, 65.0)
  private val hawaii =
    Projections.byName("conicEqualArea")!!.rotate(doubleArrayOf(157.0, 0.0))
      .center(-3.0, 19.9)
      .parallels(8.0, 18.0)

  private var k = 1070.0
  private var tx = 480.0
  private var ty = 250.0

  init {
    scale(1070.0)
  }

  fun scale(value: Double): AlbersUsa {
    k = value
    lower48.scale(value)
    alaska.scale(value * 0.35)
    hawaii.scale(value)
    return translate(tx, ty)
  }

  fun translate(x: Double, y: Double): AlbersUsa {
    tx = x
    ty = y
    lower48.translate(x, y).clipExtent(x - 0.455 * k, y - 0.238 * k, x + 0.455 * k, y + 0.238 * k)
    alaska
      .translate(x - 0.307 * k, y + 0.201 * k)
      .clipExtent(
        x - 0.425 * k + GeoMath.EPSILON,
        y + 0.120 * k + GeoMath.EPSILON,
        x - 0.214 * k - GeoMath.EPSILON,
        y + 0.234 * k - GeoMath.EPSILON,
      )
    hawaii
      .translate(x - 0.205 * k, y + 0.212 * k)
      .clipExtent(
        x - 0.214 * k + GeoMath.EPSILON,
        y + 0.166 * k + GeoMath.EPSILON,
        x - 0.115 * k - GeoMath.EPSILON,
        y + 0.234 * k - GeoMath.EPSILON,
      )
    return this
  }

  fun precision(value: Double): AlbersUsa {
    lower48.precision(value)
    alaska.precision(value)
    hawaii.precision(value)
    return this
  }

  /**
   * `fitExtent` for a composite, which is the same arithmetic as a plain projection's.
   *
   * A composite has no `rotate` or `center` to speak of, so this used to be left off it entirely —
   * and a fitted `albersUsa` therefore drew at the family's unfitted default with nothing said.
   * That is the projection **Vega-Lite reaches for by default** on any United States chart, so the
   * gap was not exotic.
   *
   * Nothing about the fit needs the pieces: measure the geometry at a reference scale with the
   * origin at zero, then scale and translate so the measured box lands in the requested one. This
   * class has `scale`, `translate` and `stream`, which is all of it — the three sub-projections
   * move together because they are driven from the same `k`, `tx` and `ty`.
   */
  fun fitExtent(x0: Double, y0: Double, x1: Double, y1: Double, geojson: VegaValue): AlbersUsa {
    scale(FIT_REFERENCE_SCALE).translate(0.0, 0.0)
    val sink = PathBoundsSink()
    GeoJsonStream.stream(geojson, stream(sink))
    val bounds = sink.result() ?: return this
    val width = x1 - x0
    val height = y1 - y0
    val spanX = bounds[2] - bounds[0]
    val spanY = bounds[3] - bounds[1]
    if (spanX <= 0.0 || spanY <= 0.0) return this
    val k = kotlin.math.min(width / spanX, height / spanY)
    val cx = x0 + (width - k * (bounds[2] + bounds[0])) / 2.0
    val cy = y0 + (height - k * (bounds[3] + bounds[1])) / 2.0
    return scale(FIT_REFERENCE_SCALE * k).translate(cx, cy)
  }

  override fun stream(sink: GeoStream): GeoStream =
    Multiplex(listOf(lower48.stream(sink), alaska.stream(sink), hawaii.stream(sink)))

  override fun apply(lambda: Double, phi: Double): DoubleArray? {
    // Whichever piece admits the point; the clip rectangles make at most one of them do.
    for (projection in listOf(lower48, alaska, hawaii)) {
      val capture = Capture()
      projection.stream(capture).point(lambda, phi)
      capture.point?.let {
        return it
      }
    }
    return null
  }

  /**
   * A point on the page read back to a place — d3's `albersUsa.invert`.
   *
   * A composite has no single inverse in the way one projection does, and on that reasoning this
   * class had none at all. d3 answers it differently and the answer is simple: the inset boxes sit
   * at **known offsets from the middle**, so normalising the point by the scale and translation
   * says which piece drew it, and that piece is asked. The point is handed on unnormalised, the
   * piece's own translation already accounting for where its box is.
   *
   * Without it nothing could read a place back off a map of the United States — which is what a
   * brush along one channel asks for when it borrows the centre of the plot.
   */
  fun invert(x: Double, y: Double): DoubleArray? {
    val nx = (x - tx) / k
    val ny = (y - ty) / k
    val piece =
      when {
        ny >= 0.120 && ny < 0.234 && nx >= -0.425 && nx < -0.214 -> alaska
        ny >= 0.166 && ny < 0.234 && nx >= -0.214 && nx < -0.115 -> hawaii
        else -> lower48
      }
    return piece.invert(x, y)
  }

  /** Pushes everything into every stream at once. */
  private class Multiplex(private val streams: List<GeoStream>) : GeoStream() {
    override fun point(x: Double, y: Double) {
      for (s in streams) s.point(x, y)
    }

    override fun point(x: Double, y: Double, m: Double) {
      for (s in streams) s.point(x, y, m)
    }

    override fun lineStart() {
      for (s in streams) s.lineStart()
    }

    override fun lineEnd() {
      for (s in streams) s.lineEnd()
    }

    override fun polygonStart() {
      for (s in streams) s.polygonStart()
    }

    override fun polygonEnd() {
      for (s in streams) s.polygonEnd()
    }

    override fun sphere() {
      for (s in streams) s.sphere()
    }
  }

  /** Catches the one point a piece emitted, if it emitted one. */
  private class Capture : GeoStream() {
    var point: DoubleArray? = null

    override fun point(x: Double, y: Double) {
      point = doubleArrayOf(x, y)
    }
  }
}
