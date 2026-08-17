package dev.aster.vega.dataflow.geo

import dev.aster.vega.dataflow.geo.GeoMath.EPSILON
import dev.aster.vega.dataflow.geo.GeoMath.EPSILON2
import dev.aster.vega.dataflow.geo.GeoMath.HALF_PI
import dev.aster.vega.dataflow.geo.GeoMath.PI_
import dev.aster.vega.dataflow.geo.GeoMath.QUARTER_PI
import dev.aster.vega.dataflow.geo.GeoMath.TAU
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A line, cut into the segments of it that are visible.
 *
 * [clean] reports what happened, and the two bits mean different things: 1 for "no intersections",
 * 2 for "there were intersections and the first and last segments belong together". A ring that
 * crosses the antimeridian is cut at the seam and its two loose ends are the same place.
 */
internal abstract class ClipLineStream(sink: GeoStream) : DelegatingStream(sink) {
  abstract fun clean(): Int
}

/** Collects what a clip line emitted, so a whole ring can be examined before anything is drawn. */
internal class ClipBuffer : GeoStream() {
  private var lines = mutableListOf<MutableList<DoubleArray>>()
  private var line: MutableList<DoubleArray>? = null

  override fun point(x: Double, y: Double) {
    line?.add(doubleArrayOf(x, y, 0.0))
  }

  override fun point(x: Double, y: Double, m: Double) {
    line?.add(doubleArrayOf(x, y, m))
  }

  override fun lineStart() {
    line = mutableListOf()
    lines.add(line!!)
  }

  override fun lineEnd() {}

  /** Joins the last run to the first, for a ring whose seam falls in the middle of a segment. */
  fun rejoin() {
    if (lines.size > 1) {
      val last = lines.removeAt(lines.size - 1)
      val first = lines.removeAt(0)
      last.addAll(first)
      lines.add(last)
    }
  }

  fun result(): List<MutableList<DoubleArray>> {
    val result = lines
    lines = mutableListOf()
    line = null
    return result
  }
}

/**
 * Is a point inside a spherical polygon?
 *
 * The question a clip has to answer before it can rejoin anything: with every visible segment in
 * hand, does the *invisible* part of the boundary belong to the inside or the outside? Ported from
 * `d3-geo/src/polygonContains.js`, exact summation included — see [Adder] for why that matters.
 */
internal fun polygonContains(polygon: List<List<DoubleArray>>, point: DoubleArray): Boolean {
  fun longitude(p: DoubleArray): Double =
    if (abs(p[0]) <= PI_) p[0] else GeoMath.sign(p[0]) * ((abs(p[0]) + PI_) % TAU - PI_)

  val lambda = longitude(point)
  var phi = point[1]
  val sinPhi = sin(phi)
  val normal = doubleArrayOf(sin(lambda), -cos(lambda), 0.0)
  var angle = 0.0
  var winding = 0
  val sum = Adder()

  if (sinPhi == 1.0) phi = HALF_PI + EPSILON else if (sinPhi == -1.0) phi = -HALF_PI - EPSILON

  for (ring in polygon) {
    val m = ring.size
    if (m == 0) continue
    var point0 = ring[m - 1]
    var lambda0 = longitude(point0)
    var phi0 = point0[1] / 2 + QUARTER_PI
    var sinPhi0 = sin(phi0)
    var cosPhi0 = cos(phi0)

    for (j in 0 until m) {
      val point1 = ring[j]
      val lambda1 = longitude(point1)
      val phi1 = point1[1] / 2 + QUARTER_PI
      val sinPhi1 = sin(phi1)
      val cosPhi1 = cos(phi1)
      val delta = lambda1 - lambda0
      val deltaSign = if (delta >= 0) 1.0 else -1.0
      val absDelta = deltaSign * delta
      val antimeridian = absDelta > PI_
      val k = sinPhi0 * sinPhi1

      sum.add(atan2(k * deltaSign * sin(absDelta), cosPhi0 * cosPhi1 + k * cos(absDelta)))
      angle += if (antimeridian) delta + deltaSign * TAU else delta

      // Does the segment straddle the point's meridian, below the point's parallel?
      if (antimeridian xor (lambda0 >= lambda) xor (lambda1 >= lambda)) {
        val arc = GeoMath.cartesianCross(GeoMath.cartesian(point0), GeoMath.cartesian(point1))
        GeoMath.cartesianNormalizeInPlace(arc)
        val intersection = GeoMath.cartesianCross(normal, arc)
        GeoMath.cartesianNormalizeInPlace(intersection)
        val phiArc =
          (if (antimeridian xor (delta >= 0)) -1.0 else 1.0) * GeoMath.asin(intersection[2])
        if (phi > phiArc || (phi == phiArc && (arc[0] != 0.0 || arc[1] != 0.0))) {
          winding += if (antimeridian xor (delta >= 0)) 1 else -1
        }
      }

      lambda0 = lambda1
      sinPhi0 = sinPhi1
      cosPhi0 = cosPhi1
      point0 = point1
    }
  }

  val southPoleInside = angle < -EPSILON || (angle < EPSILON && sum.value() < -EPSILON2)
  return southPoleInside xor ((winding and 1) != 0)
}

/** One end of a clipped segment, linked into two rings: the subject's and the clip edge's. */
private class Intersection(
  val x: DoubleArray,
  val z: MutableList<DoubleArray>?,
  var o: Intersection?,
  var e: Boolean,
) {
  var v = false
  var n: Intersection? = null
  var p: Intersection? = null
}

/**
 * Rejoins clipped segments by walking along the clip edge between them.
 *
 * The heart of the whole thing: after cutting, a polygon is a bag of loose visible arcs, and what
 * makes it a *filled shape* again is knowing which piece of the clip boundary joins each pair. The
 * algorithm links the arcs into one ring and the boundary crossings into another, sorted along the
 * edge, then alternates between them. Ported from `d3-geo/src/clip/rejoin.js`.
 */
internal fun clipRejoin(
  segments: List<MutableList<DoubleArray>>,
  compareIntersection: Comparator<DoubleArray>,
  startInsideInitial: Boolean,
  interpolate: (DoubleArray?, DoubleArray?, Int, GeoStream) -> Unit,
  stream: GeoStream,
) {
  val subject = mutableListOf<Intersection>()
  val clip = mutableListOf<Intersection>()

  for (segment in segments) {
    val n = segment.size - 1
    if (n <= 0) continue
    var p0 = segment[0]
    val p1 = segment[n]

    if (GeoMath.pointEqual(p0, p1)) {
      if (p0[2] == 0.0 && p1[2] == 0.0) {
        stream.lineStart()
        for (i in 0 until n) {
          p0 = segment[i]
          stream.point(p0[0], p0[1])
        }
        stream.lineEnd()
        continue
      }
      // A ring that starts and ends on the clip edge is nudged apart rather than dropped.
      p1[0] += 2 * EPSILON
    }

    val a = Intersection(p0, segment, null, true)
    subject.add(a)
    val ao = Intersection(p0, null, a, false)
    a.o = ao
    clip.add(ao)
    val b = Intersection(p1, segment, null, false)
    subject.add(b)
    val bo = Intersection(p1, null, b, true)
    b.o = bo
    clip.add(bo)
  }

  if (subject.isEmpty()) return

  clip.sortWith { l, r -> compareIntersection.compare(l.x, r.x) }
  link(subject)
  link(clip)

  var startInside = startInsideInitial
  for (entry in clip) {
    startInside = !startInside
    entry.e = startInside
  }

  val start = subject[0]
  while (true) {
    var current: Intersection = start
    var isSubject = true
    while (current.v) {
      current = current.n!!
      if (current === start) return
    }
    var points = current.z
    stream.lineStart()
    do {
      current.v = true
      current.o!!.v = true
      if (current.e) {
        if (isSubject) {
          val list = points
          if (list != null) for (p in list) stream.point(p[0], p[1])
        } else {
          interpolate(current.x, current.n!!.x, 1, stream)
        }
        current = current.n!!
      } else {
        if (isSubject) {
          points = current.p!!.z
          val list = points
          if (list != null)
            for (index in list.indices.reversed()) {
              val p = list[index]
              stream.point(p[0], p[1])
            }
        } else {
          interpolate(current.x, current.p!!.x, -1, stream)
        }
        current = current.p!!
      }
      current = current.o!!
      points = current.z
      isSubject = !isSubject
    } while (!current.v)
    stream.lineEnd()
  }
}

private fun link(array: List<Intersection>) {
  if (array.isEmpty()) return
  var a = array[0]
  for (i in 1 until array.size) {
    val b = array[i]
    a.n = b
    b.p = a
    a = b
  }
  a.n = array[0]
  array[0].p = a
}

/**
 * The generic clip: cut every line, keep the visible pieces, and rejoin them along the boundary.
 *
 * `d3-geo/src/clip/index.js`, parameterised the same way — a visibility test, a line cutter, an
 * interpolation along the boundary, and the point the "is the polygon inside out?" question is
 * asked at.
 */
internal class ClipStream(
  private val sink: GeoStream,
  private val pointVisible: (Double, Double) -> Boolean,
  private val clipLine: (GeoStream) -> ClipLineStream,
  private val interpolate: (DoubleArray?, DoubleArray?, Int, GeoStream) -> Unit,
  private val start: DoubleArray,
  private val compareIntersection: Comparator<DoubleArray>,
) : GeoStream() {

  private val line = clipLine(sink)
  private val ringBuffer = ClipBuffer()
  private val ringSink = clipLine(ringBuffer)
  private var polygonStarted = false
  private var polygon = mutableListOf<List<DoubleArray>>()
  private var segments = mutableListOf<MutableList<DoubleArray>>()
  private var ring: MutableList<DoubleArray>? = null
  private var inPolygon = false
  private var inLine = false

  override fun point(x: Double, y: Double) {
    when {
      inPolygon -> {
        ring?.add(doubleArrayOf(x, y))
        ringSink.point(x, y)
      }
      inLine -> line.point(x, y)
      else -> if (pointVisible(x, y)) sink.point(x, y)
    }
  }

  override fun lineStart() {
    if (inPolygon) {
      ringSink.lineStart()
      ring = mutableListOf()
    } else {
      inLine = true
      line.lineStart()
    }
  }

  override fun lineEnd() {
    if (inPolygon) ringEnd()
    else {
      inLine = false
      line.lineEnd()
    }
  }

  override fun polygonStart() {
    inPolygon = true
    segments = mutableListOf()
    polygon = mutableListOf()
  }

  override fun polygonEnd() {
    inPolygon = false
    val merged = segments
    val startInside = polygonContains(polygon, start)
    if (merged.isNotEmpty()) {
      if (!polygonStarted) {
        sink.polygonStart()
        polygonStarted = true
      }
      clipRejoin(merged, compareIntersection, startInside, interpolate, sink)
    } else if (startInside) {
      // Nothing crossed the boundary and the polygon covers it: the boundary *is* the outline.
      if (!polygonStarted) {
        sink.polygonStart()
        polygonStarted = true
      }
      sink.lineStart()
      interpolate(null, null, 1, sink)
      sink.lineEnd()
    }
    if (polygonStarted) {
      sink.polygonEnd()
      polygonStarted = false
    }
  }

  override fun sphere() {
    sink.polygonStart()
    sink.lineStart()
    interpolate(null, null, 1, sink)
    sink.lineEnd()
    sink.polygonEnd()
  }

  private fun ringEnd() {
    val current = ring ?: return
    // The ring is closed by repeating its first point, which is what tells the cutter that the
    // last segment and the first are one.
    val first = current.firstOrNull() ?: return
    current.add(doubleArrayOf(first[0], first[1]))
    ringSink.point(first[0], first[1])
    ringSink.lineEnd()

    val clean = ringSink.clean()
    val ringSegments = ringBuffer.result().toMutableList()
    current.removeAt(current.size - 1)
    polygon.add(current)
    ring = null

    if (ringSegments.isEmpty()) return

    if ((clean and 1) != 0) {
      // No intersections: the ring is wholly visible and can be drawn as it stands.
      val segment = ringSegments[0]
      val m = segment.size - 1
      if (m > 0) {
        if (!polygonStarted) {
          sink.polygonStart()
          polygonStarted = true
        }
        sink.lineStart()
        for (i in 0 until m) sink.point(segment[i][0], segment[i][1])
        sink.lineEnd()
      }
      return
    }

    if (ringSegments.size > 1 && (clean and 2) != 0) {
      val last = ringSegments.removeAt(ringSegments.size - 1)
      val head = ringSegments.removeAt(0)
      last.addAll(head)
      ringSegments.add(last)
    }
    segments.addAll(ringSegments.filter { it.size > 1 })
  }
}

/**
 * Cutting at the antimeridian, which every projection does unless a clip angle says otherwise.
 *
 * A country that straddles longitude 180 — Russia, Fiji — is one shape on the globe and two on a
 * flat map, and this is what splits it. Without it Russia is drawn as a band across the entire
 * width of the world.
 */
internal object ClipAntimeridian {
  // Intersections are sorted along the seam, north to south down one side and back up the other.
  private val compare =
    Comparator<DoubleArray> { a, b ->
      val av = if (a[0] < 0) a[1] - HALF_PI - EPSILON else HALF_PI - a[1]
      val bv = if (b[0] < 0) b[1] - HALF_PI - EPSILON else HALF_PI - b[1]
      av.compareTo(bv)
    }

  fun stream(sink: GeoStream): GeoStream =
    ClipStream(
      sink,
      pointVisible = { _, _ -> true },
      clipLine = { Line(it) },
      interpolate = ::interpolate,
      start = doubleArrayOf(-PI_, -HALF_PI),
      compareIntersection = compare,
    )

  private class Line(sink: GeoStream) : ClipLineStream(sink) {
    private var lambda0 = Double.NaN
    private var phi0 = Double.NaN
    private var sign0 = Double.NaN
    private var clean = 1

    override fun lineStart() {
      sink.lineStart()
      clean = 1
    }

    override fun point(x: Double, y: Double) {
      var lambda1 = x
      val phi1 = y
      val sign1 = if (lambda1 > 0) PI_ else -PI_
      val delta = abs(lambda1 - lambda0)
      if (abs(delta - PI_) < EPSILON) {
        // Straight over a pole: the line is taken up to the pole, across, and back down.
        phi0 = if ((phi0 + phi1) / 2 > 0) HALF_PI else -HALF_PI
        sink.point(lambda0, phi0)
        sink.point(sign0, phi0)
        sink.lineEnd()
        sink.lineStart()
        sink.point(sign1, phi0)
        sink.point(lambda1, phi0)
        clean = 0
      } else if (sign0 != sign1 && delta >= PI_) {
        if (abs(lambda0 - sign0) < EPSILON) lambda0 -= sign0 * EPSILON
        if (abs(lambda1 - sign1) < EPSILON) lambda1 -= sign1 * EPSILON
        phi0 = intersect(lambda0, phi0, lambda1, phi1)
        sink.point(sign0, phi0)
        sink.lineEnd()
        sink.lineStart()
        sink.point(sign1, phi0)
        clean = 0
      }
      lambda0 = lambda1
      phi0 = phi1
      sink.point(lambda0, phi0)
      sign0 = sign1
    }

    override fun lineEnd() {
      sink.lineEnd()
      lambda0 = Double.NaN
      phi0 = Double.NaN
    }

    override fun clean(): Int = 2 - clean
  }

  private fun intersect(
    lambda0: Double,
    phi0: Double,
    lambda1: Double,
    phi1: Double,
  ): Double {
    val sinLambda0Lambda1 = sin(lambda0 - lambda1)
    if (abs(sinLambda0Lambda1) <= EPSILON) return (phi0 + phi1) / 2
    val cosPhi1 = cos(phi1)
    val cosPhi0 = cos(phi0)
    return atan(
      (sin(phi0) * cosPhi1 * sin(lambda1) - sin(phi1) * cosPhi0 * sin(lambda0)) /
        (cosPhi0 * cosPhi1 * sinLambda0Lambda1)
    )
  }

  private fun interpolate(
    from: DoubleArray?,
    to: DoubleArray?,
    direction: Int,
    stream: GeoStream,
  ) {
    if (from == null) {
      // The whole sphere: down one side of the seam, across, and up the other.
      val phi = direction * HALF_PI
      stream.point(-PI_, phi)
      stream.point(0.0, phi)
      stream.point(PI_, phi)
      stream.point(PI_, 0.0)
      stream.point(PI_, -phi)
      stream.point(0.0, -phi)
      stream.point(-PI_, -phi)
      stream.point(-PI_, 0.0)
      stream.point(-PI_, phi)
    } else if (to != null && abs(from[0] - to[0]) > EPSILON) {
      val lambda = if (from[0] < to[0]) PI_ else -PI_
      val phi = direction * lambda / 2
      stream.point(-lambda, phi)
      stream.point(0.0, phi)
      stream.point(lambda, phi)
    } else if (to != null) {
      stream.point(to[0], to[1])
    }
  }
}

/**
 * Cohen–Sutherland-style segment clipping against a rectangle, `d3-geo/src/clip/line.js`.
 *
 * Mutates the two endpoints to the visible span and reports whether anything survived.
 */
internal fun clipSegment(
  a: DoubleArray,
  b: DoubleArray,
  x0: Double,
  y0: Double,
  x1: Double,
  y1: Double,
): Boolean {
  val ax = a[0]
  val ay = a[1]
  val bx = b[0]
  val by = b[1]
  var t0 = 0.0
  var t1 = 1.0
  val dx = bx - ax
  val dy = by - ay

  var r = x0 - ax
  if (dx == 0.0 && r > 0) return false
  r /= dx
  if (dx < 0) {
    if (r < t0) return false
    if (r < t1) t1 = r
  } else if (dx > 0) {
    if (r > t1) return false
    if (r > t0) t0 = r
  }

  r = x1 - ax
  if (dx == 0.0 && r < 0) return false
  r /= dx
  if (dx < 0) {
    if (r > t1) return false
    if (r > t0) t0 = r
  } else if (dx > 0) {
    if (r < t0) return false
    if (r < t1) t1 = r
  }

  r = y0 - ay
  if (dy == 0.0 && r > 0) return false
  r /= dy
  if (dy < 0) {
    if (r < t0) return false
    if (r < t1) t1 = r
  } else if (dy > 0) {
    if (r > t1) return false
    if (r > t0) t0 = r
  }

  r = y1 - ay
  if (dy == 0.0 && r < 0) return false
  r /= dy
  if (dy < 0) {
    if (r > t1) return false
    if (r > t0) t0 = r
  } else if (dy > 0) {
    if (r < t0) return false
    if (r < t1) t1 = r
  }

  if (t0 > 0) {
    a[0] = ax + t0 * dx
    a[1] = ay + t0 * dy
  }
  if (t1 < 1) {
    b[0] = ax + t1 * dx
    b[1] = ay + t1 * dy
  }
  return true
}

/**
 * Clipping to a rectangle, in *projected* coordinates rather than on the sphere.
 *
 * This is the post-clip, and mercator uses it for something specific: the projection is infinite in
 * latitude, so a mercator map is clipped to the square that holds one full turn of the world.
 * Without it a polygon reaching the pole draws to infinity and takes the chart's bounds with it.
 */
internal class ClipRectangle(
  private val x0: Double,
  private val y0: Double,
  private val x1: Double,
  private val y1: Double,
) {
  private fun visible(x: Double, y: Double): Boolean = x in x0..x1 && y in y0..y1

  private fun corner(p: DoubleArray, direction: Int): Int =
    when {
      abs(p[0] - x0) < EPSILON -> if (direction > 0) 0 else 3
      abs(p[0] - x1) < EPSILON -> if (direction > 0) 2 else 1
      abs(p[1] - y0) < EPSILON -> if (direction > 0) 1 else 0
      else -> if (direction > 0) 3 else 2
    }

  private fun comparePoint(a: DoubleArray, b: DoubleArray): Double {
    val ca = corner(a, 1)
    val cb = corner(b, 1)
    return when {
      ca != cb -> (ca - cb).toDouble()
      ca == 0 -> b[1] - a[1]
      ca == 1 -> a[0] - b[0]
      ca == 2 -> a[1] - b[1]
      else -> b[0] - a[0]
    }
  }

  private fun interpolate(
    from: DoubleArray?,
    to: DoubleArray?,
    direction: Int,
    stream: GeoStream,
  ) {
    var a = 0
    var a1 = 0
    if (
      from == null ||
        to == null ||
        run {
          a = corner(from, direction)
          a1 = corner(to, direction)
          a != a1
        } ||
        ((comparePoint(from, to) < 0) != (direction > 0))
    ) {
      do {
        stream.point(if (a == 0 || a == 3) x0 else x1, if (a > 1) y1 else y0)
        a = (a + direction + 4) % 4
      } while (a != a1)
    } else {
      stream.point(to[0], to[1])
    }
  }

  fun stream(sink: GeoStream): GeoStream = Stream(sink)

  private inner class Stream(private val out: GeoStream) : GeoStream() {
    private val bufferStream = ClipBuffer()
    private var activeIsBuffer = false
    private var segments = mutableListOf<MutableList<DoubleArray>>()
    private var polygon: MutableList<List<DoubleArray>>? = null
    private var ring: MutableList<DoubleArray>? = null
    private var xx = 0.0
    private var yy = 0.0
    private var vv = false
    private var xPrev = Double.NaN
    private var yPrev = Double.NaN
    private var vPrev = false
    private var first = true
    private var clean = true
    private var inLine = false

    private fun active(): GeoStream = if (activeIsBuffer) bufferStream else out

    override fun point(x: Double, y: Double) {
      if (inLine) linePoint(x, y) else if (visible(x, y)) active().point(x, y)
    }

    override fun polygonStart() {
      activeIsBuffer = true
      segments = mutableListOf()
      polygon = mutableListOf()
      clean = true
    }

    override fun polygonEnd() {
      val startInside = polygonInside()
      val cleanInside = clean && startInside
      val merged = segments
      if (cleanInside || merged.isNotEmpty()) {
        out.polygonStart()
        if (cleanInside) {
          out.lineStart()
          interpolate(null, null, 1, out)
          out.lineEnd()
        }
        if (merged.isNotEmpty()) {
          clipRejoin(
            merged,
            { a, b -> comparePoint(a, b).compareTo(0.0) },
            startInside,
            ::interpolate,
            out,
          )
        }
        out.polygonEnd()
      }
      activeIsBuffer = false
      polygon = null
      ring = null
    }

    /** The winding number of the polygon around the rectangle's top-left corner. */
    private fun polygonInside(): Boolean {
      var winding = 0
      for (currentRing in polygon.orEmpty()) {
        if (currentRing.isEmpty()) continue
        var point = currentRing[0]
        var b0 = point[0]
        var b1 = point[1]
        for (j in 1 until currentRing.size) {
          val a0 = b0
          val a1 = b1
          point = currentRing[j]
          b0 = point[0]
          b1 = point[1]
          if (a1 <= y1) {
            if (b1 > y1 && (b0 - a0) * (y1 - a1) > (b1 - a1) * (x0 - a0)) winding++
          } else {
            if (b1 <= y1 && (b0 - a0) * (y1 - a1) < (b1 - a1) * (x0 - a0)) winding--
          }
        }
      }
      return winding != 0
    }

    override fun lineStart() {
      inLine = true
      if (polygon != null) {
        ring = mutableListOf()
        polygon!!.add(ring!!)
      }
      first = true
      vPrev = false
      xPrev = Double.NaN
      yPrev = Double.NaN
    }

    override fun lineEnd() {
      if (polygon != null) {
        linePoint(xx, yy)
        if (vv && vPrev) bufferStream.rejoin()
        segments.addAll(bufferStream.result())
      }
      inLine = false
      if (vPrev) active().lineEnd()
    }

    private fun linePoint(x: Double, y: Double) {
      var px = x
      var py = y
      val v = visible(px, py)
      ring?.add(doubleArrayOf(px, py))
      if (first) {
        xx = px
        yy = py
        vv = v
        first = false
        if (v) {
          active().lineStart()
          active().point(px, py)
        }
      } else {
        if (v && vPrev) {
          active().point(px, py)
        } else {
          val a =
            doubleArrayOf(max(CLIP_MIN, min(CLIP_MAX, xPrev)), max(CLIP_MIN, min(CLIP_MAX, yPrev)))
          px = max(CLIP_MIN, min(CLIP_MAX, px))
          py = max(CLIP_MIN, min(CLIP_MAX, py))
          val b = doubleArrayOf(px, py)
          if (clipSegment(a, b, x0, y0, x1, y1)) {
            if (!vPrev) {
              active().lineStart()
              active().point(a[0], a[1])
            }
            active().point(b[0], b[1])
            if (!v) active().lineEnd()
            clean = false
          } else if (v) {
            active().lineStart()
            active().point(px, py)
            clean = false
          }
        }
      }
      xPrev = px
      yPrev = py
      vPrev = v
    }
  }

  private companion object {
    const val CLIP_MAX = 1e9
    const val CLIP_MIN = -1e9
  }
}
