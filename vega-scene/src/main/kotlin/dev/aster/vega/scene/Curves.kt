package dev.aster.vega.scene

import kotlin.math.abs
import kotlin.math.min

/**
 * How a series' points are joined.
 *
 * The straight and staircase families are geometry anyone can write down; the four spline families
 * here are ports of d3-shape's own curve generators, which is what Vega draws with. They are
 * reproduced as the same incremental state machines rather than rewritten in closed form: each has
 * a first and last segment that behaves unlike the middle ones, and the shapes of those special
 * cases are not derivable from the picture.
 */
public enum class CurveKind {
  LINEAR,
  STEP,
  STEP_BEFORE,
  STEP_AFTER,
  BASIS,
  CARDINAL,
  /**
   * A smoothed line that never overshoots its data, which is why it is the one people ask for.
   *
   * Vega chooses between d3's `monotoneX` and `monotoneY` from the mark's `orient`, defaulting to
   * vertical: a horizontal series is monotone in *y*. The two are the same algorithm with the axes
   * swapped, and picking the wrong one leaves the curve overshooting on exactly the charts the
   * method exists to fix.
   */
  MONOTONE,
  NATURAL;

  public companion object {
    public fun fromName(name: String?): CurveKind? =
      when (name?.lowercase()) {
        null,
        "linear" -> LINEAR
        "step" -> STEP
        "step-before" -> STEP_BEFORE
        "step-after" -> STEP_AFTER
        "basis" -> BASIS
        "cardinal" -> CARDINAL
        "monotone" -> MONOTONE
        "natural" -> NATURAL
        else -> null
      }
  }
}

/** Draws [points] with [kind], opening a new subpath. */
public fun PathBuilder.curve(
  points: List<PointD>,
  kind: CurveKind,
  horizontal: Boolean = false,
  tension: Double = 0.0,
): PathBuilder {
  if (points.isEmpty()) return this
  when (kind) {
    CurveKind.LINEAR -> polyline(points)
    CurveKind.STEP -> steps(points, StepPosition.MIDDLE)
    CurveKind.STEP_BEFORE -> steps(points, StepPosition.BEFORE)
    CurveKind.STEP_AFTER -> steps(points, StepPosition.AFTER)
    CurveKind.BASIS -> basis(points)
    CurveKind.CARDINAL -> cardinal(points, tension)
    CurveKind.NATURAL -> natural(points)
    CurveKind.MONOTONE ->
      if (horizontal) {
        // Reflected: the same machine run with the axes swapped, and swapped back on the way out.
        monotone(points.map { PointD(it.y, it.x) }, reflect = true)
      } else {
        monotone(points, reflect = false)
      }
  }
  return this
}

// ---- basis ------------------------------------------------------------------

/**
 * A cubic B-spline, which passes near its points rather than through them.
 *
 * The first and last segments are the interesting part: the curve starts a sixth of the way along
 * the first span and finishes with a doubled final point, which is what pins an open B-spline to
 * its ends instead of letting it float short of them.
 */
private fun PathBuilder.basis(points: List<PointD>) {
  val n = points.size
  moveTo(points[0].x, points[0].y)
  if (n == 1) return
  if (n == 2) {
    lineTo(points[1].x, points[1].y)
    return
  }
  lineTo((5.0 * points[0].x + points[1].x) / 6.0, (5.0 * points[0].y + points[1].y) / 6.0)
  for (index in 2 until n) basisSegment(points[index - 2], points[index - 1], points[index])
  basisSegment(points[n - 2], points[n - 1], points[n - 1])
  lineTo(points[n - 1].x, points[n - 1].y)
}

private fun PathBuilder.basisSegment(p0: PointD, p1: PointD, next: PointD) {
  cubicTo(
    (2.0 * p0.x + p1.x) / 3.0,
    (2.0 * p0.y + p1.y) / 3.0,
    (p0.x + 2.0 * p1.x) / 3.0,
    (p0.y + 2.0 * p1.y) / 3.0,
    (p0.x + 4.0 * p1.x + next.x) / 6.0,
    (p0.y + 4.0 * p1.y + next.y) / 6.0,
  )
}

// ---- cardinal ---------------------------------------------------------------

/**
 * A Catmull-Rom-style spline through every point, with the tangent at each taken from its
 * neighbours.
 *
 * Ported as d3's state machine rather than a loop, because its first step leaves the running state
 * in an arrangement no reading of the geometry would suggest — the second point is written into the
 * slot the first occupies and then shifted over it — and the opening control point comes out of
 * exactly that.
 */
private fun PathBuilder.cardinal(points: List<PointD>, tension: Double) {
  val k = (1.0 - tension) / 6.0
  var x0 = Double.NaN
  var y0 = Double.NaN
  var x1 = Double.NaN
  var y1 = Double.NaN
  var x2 = Double.NaN
  var y2 = Double.NaN
  var stage = 0

  fun segment(x: Double, y: Double) {
    cubicTo(
      x1 + k * (x2 - x0),
      y1 + k * (y2 - y0),
      x2 + k * (x1 - x),
      y2 + k * (y1 - y),
      x2,
      y2,
    )
  }

  for (point in points) {
    when (stage) {
      0 -> {
        stage = 1
        moveTo(point.x, point.y)
      }
      1 -> {
        stage = 2
        x1 = point.x
        y1 = point.y
      }
      else -> {
        stage = 3
        segment(point.x, point.y)
      }
    }
    x0 = x1
    y0 = y1
    x1 = x2
    y1 = y2
    x2 = point.x
    y2 = point.y
  }
  when (stage) {
    2 -> lineTo(x2, y2)
    3 -> segment(x1, y1)
  }
}

// ---- natural ----------------------------------------------------------------

/**
 * A natural cubic spline: the smoothest curve through every point, solved as one system.
 *
 * Unlike the others this cannot be done incrementally — every control point depends on every data
 * point — which is why d3 collects the whole series before drawing anything.
 */
private fun PathBuilder.natural(points: List<PointD>) {
  val n = points.size
  moveTo(points[0].x, points[0].y)
  if (n == 1) return
  if (n == 2) {
    lineTo(points[1].x, points[1].y)
    return
  }
  val px = naturalControls(points.map { it.x })
  val py = naturalControls(points.map { it.y })
  for (index in 1 until n) {
    cubicTo(
      px.first[index - 1],
      py.first[index - 1],
      px.second[index - 1],
      py.second[index - 1],
      points[index].x,
      points[index].y,
    )
  }
}

/** The tridiagonal solve behind a natural spline, in d3's arrangement. */
private fun naturalControls(values: List<Double>): Pair<DoubleArray, DoubleArray> {
  val n = values.size - 1
  val a = DoubleArray(n)
  val b = DoubleArray(n)
  val r = DoubleArray(n)
  a[0] = 0.0
  b[0] = 2.0
  r[0] = values[0] + 2.0 * values[1]
  for (i in 1 until n - 1) {
    a[i] = 1.0
    b[i] = 4.0
    r[i] = 4.0 * values[i] + 2.0 * values[i + 1]
  }
  a[n - 1] = 2.0
  b[n - 1] = 7.0
  r[n - 1] = 8.0 * values[n - 1] + values[n]
  for (i in 1 until n) {
    val m = a[i] / b[i - 1]
    b[i] -= m
    r[i] -= m * r[i - 1]
  }
  a[n - 1] = r[n - 1] / b[n - 1]
  for (i in n - 2 downTo 0) a[i] = (r[i] - a[i + 1]) / b[i]
  b[n - 1] = (values[n] + a[n - 1]) / 2.0
  for (i in 0 until n - 1) b[i] = 2.0 * values[i + 1] - a[i + 1]
  return a to b
}

// ---- monotone ---------------------------------------------------------------

/**
 * Steffen's monotone interpolation: a Hermite spline whose tangents are clamped so the curve never
 * turns back on itself between two points.
 *
 * That clamp is the whole method — `min(|s0|, |s1|, |p| / 2)`, signed only when both neighbouring
 * slopes agree — and it is why a monotone line through rising data never dips, which every other
 * spline family here will happily do.
 */
private fun PathBuilder.monotone(points: List<PointD>, reflect: Boolean) {
  fun emitMove(x: Double, y: Double) = if (reflect) moveTo(y, x) else moveTo(x, y)
  fun emitLine(x: Double, y: Double) = if (reflect) lineTo(y, x) else lineTo(x, y)
  fun emitCubic(
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    x: Double,
    y: Double,
  ) = if (reflect) cubicTo(y1, x1, y2, x2, y, x) else cubicTo(x1, y1, x2, y2, x, y)

  var x0 = Double.NaN
  var y0 = Double.NaN
  var x1 = Double.NaN
  var y1 = Double.NaN
  var t0 = Double.NaN
  var stage = 0

  fun span(startSlope: Double, endSlope: Double) {
    val dx = (x1 - x0) / 3.0
    emitCubic(x0 + dx, y0 + dx * startSlope, x1 - dx, y1 - dx * endSlope, x1, y1)
  }

  /** The one-sided slope d3 uses at each end of the series. */
  fun oneSided(t: Double): Double {
    val h = x1 - x0
    return if (h != 0.0) (3.0 * (y1 - y0) / h - t) / 2.0 else t
  }

  fun threePoint(x2: Double, y2: Double): Double {
    val h0 = x1 - x0
    val h1 = x2 - x1
    val s0 = (y1 - y0) / (if (h0 != 0.0) h0 else if (h1 < 0.0) -0.0 else 0.0)
    val s1 = (y2 - y1) / (if (h1 != 0.0) h1 else if (h0 < 0.0) -0.0 else 0.0)
    val p = (s0 * h1 + s1 * h0) / (h0 + h1)
    val magnitude = min(min(abs(s0), abs(s1)), 0.5 * abs(p))
    val result = (sign(s0) + sign(s1)) * magnitude
    return if (result.isNaN()) 0.0 else result
  }

  for (point in points) {
    val x = point.x
    val y = point.y
    // Coincident points are skipped: two identical points give a zero-length span and a division
    // by zero in the slope.
    if (x == x1 && y == y1) continue
    var t1 = Double.NaN
    when (stage) {
      0 -> {
        stage = 1
        emitMove(x, y)
      }
      1 -> stage = 2
      2 -> {
        stage = 3
        t1 = threePoint(x, y)
        span(oneSided(t1), t1)
      }
      else -> {
        t1 = threePoint(x, y)
        span(t0, t1)
      }
    }
    x0 = x1
    y0 = y1
    x1 = x
    y1 = y
    t0 = t1
  }
  when (stage) {
    2 -> emitLine(x1, y1)
    3 -> span(t0, oneSided(t0))
  }
}

private fun sign(value: Double): Double = if (value < 0.0) -1.0 else 1.0
