package dev.aster.vega.scene

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

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
  NATURAL,

  /**
   * The B-spline without the end conditions that pin [BASIS] to its first and last points.
   *
   * "Open" here means the *control polygon* is open, not the outline: the curve simply starts and
   * ends where the spline naturally does, a sixth of the way in, so the first and last data points
   * are not on it at all. Vega offers the same distinction for the cardinal family.
   */
  BASIS_OPEN,

  /**
   * A B-spline over points pulled some of the way towards the straight line between the ends.
   *
   * `tension` is a *blend*, not a stiffness: beta 1 leaves the points alone and draws [BASIS], beta
   * 0 collapses them onto the chord and draws a straight line. Used for edge bundling, which is
   * where the name comes from.
   */
  BUNDLE,

  /** The cardinal spline without end conditions, as [BASIS_OPEN] is to [BASIS]. */
  CARDINAL_OPEN,

  /**
   * The centripetal Catmull-Rom family: a spline through every point that cannot form a cusp.
   *
   * The difference from [CARDINAL] is the parameterisation. A cardinal spline takes each tangent
   * from the neighbours' separation alone, so two points close together next to one far away pull
   * the curve into a loop; this one weights each by the distance to the power `alpha`, which is
   * what `tension` means here — 0 reproduces [CARDINAL] exactly, 0.5 (the default) is centripetal,
   * 1 is chordal.
   */
  CATMULL_ROM,

  /** The Catmull-Rom spline without end conditions, as [CARDINAL_OPEN] is to [CARDINAL]. */
  CATMULL_ROM_OPEN,

  /**
   * The closed variants, which join the last point back to the first.
   *
   * Not a decoration on the open forms. Only [LINEAR_CLOSED] is the open curve plus a `Z`; the two
   * splines wrap their control-point window right round the series, so the segment either side of
   * the join is computed from points at the other end of the list. Drawing the open spline and
   * closing it would leave a visible corner exactly where the method exists to remove one.
   */
  LINEAR_CLOSED,
  BASIS_CLOSED,
  CARDINAL_CLOSED,
  CATMULL_ROM_CLOSED;

  /** True when this curve joins its last point back to its first. */
  public val isClosed: Boolean
    get() =
      this == LINEAR_CLOSED ||
        this == BASIS_CLOSED ||
        this == CARDINAL_CLOSED ||
        this == CATMULL_ROM_CLOSED

  /** True when the first and last data points are not on the curve. */
  public val isOpen: Boolean
    get() = this == BASIS_OPEN || this == CARDINAL_OPEN || this == CATMULL_ROM_OPEN

  /**
   * What `tension` means to this family when the specification does not say.
   *
   * Three different quantities share one channel name — a cardinal stiffness, a Catmull-Rom
   * exponent and a bundle blend — and each has its own neutral value. Taking 0 for all three would
   * turn an unspecified Catmull-Rom into a cardinal spline and an unspecified bundle into a
   * straight line.
   */
  public val defaultTension: Double
    get() =
      when (this) {
        BUNDLE -> 0.85
        CATMULL_ROM,
        CATMULL_ROM_OPEN,
        CATMULL_ROM_CLOSED -> 0.5
        else -> 0.0
      }

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
        "basis-open" -> BASIS_OPEN
        "bundle" -> BUNDLE
        "cardinal-open" -> CARDINAL_OPEN
        "catmull-rom" -> CATMULL_ROM
        "catmull-rom-open" -> CATMULL_ROM_OPEN
        "linear-closed" -> LINEAR_CLOSED
        "basis-closed" -> BASIS_CLOSED
        "cardinal-closed" -> CARDINAL_CLOSED
        "catmull-rom-closed" -> CATMULL_ROM_CLOSED
        else -> null
      }
  }
}

/**
 * Draws [points] with [kind], opening a new subpath.
 *
 * A null [tension] takes the family's own neutral value; see [CurveKind.defaultTension].
 *
 * [partOfArea] exists for one quirk of the open families. Given exactly three points there is no
 * segment left to draw — the curve is a single position — and d3 closes the subpath, which for a
 * line renders nothing and for an area would cut the outline off before its baseline. It is the
 * caller, not the geometry, that knows which of the two this is.
 */
public fun PathBuilder.curve(
  points: List<PointD>,
  kind: CurveKind,
  horizontal: Boolean = false,
  tension: Double? = null,
  partOfArea: Boolean = false,
): PathBuilder {
  if (points.isEmpty()) return this
  @Suppress("NAME_SHADOWING") val tension = tension ?: kind.defaultTension
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
    CurveKind.BASIS_OPEN -> basisOpen(points, partOfArea)
    CurveKind.BUNDLE -> bundled(points, tension).takeIf { it.isNotEmpty() }?.let { basis(it) }
    CurveKind.CARDINAL_OPEN -> cardinalOpen(points, tension, partOfArea)
    // An alpha of zero is not a degenerate Catmull-Rom spline — d3 hands the whole series to the
    // cardinal curve instead. The two are the same shape, but only the substitution reproduces its
    // end conditions: a Catmull-Rom run at alpha 0 still applies the span correction, because a
    // zero span raised to the power zero is one, not zero.
    CurveKind.CATMULL_ROM ->
      if (tension == 0.0) cardinal(points, 0.0) else catmullRom(points, tension)
    CurveKind.CATMULL_ROM_OPEN ->
      if (tension == 0.0) cardinalOpen(points, 0.0, partOfArea)
      else catmullRomOpen(points, tension, partOfArea)
    CurveKind.LINEAR_CLOSED -> polyline(points, closePath = true)
    CurveKind.BASIS_CLOSED -> basisClosed(points)
    CurveKind.CARDINAL_CLOSED -> cardinalClosed(points, tension)
    CurveKind.CATMULL_ROM_CLOSED ->
      if (tension == 0.0) cardinalClosed(points, 0.0) else catmullRomClosed(points, tension)
  }
  // A line of one point closes its subpath. Nothing is drawn either way, but the `Z` is in every
  // reference path upstream produces, so it is in ours. The closed families do it above, where they
  // also have to place the point; the open ones drew nothing to close.
  // `bundle` is excluded with the open families: having no chord to pull a single point towards, it
  // feeds the B-spline nothing and so has no subpath to close.
  if (
    points.size == 1 && !partOfArea && !kind.isOpen && !kind.isClosed && kind != CurveKind.BUNDLE
  ) {
    close()
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

/**
 * A cubic B-spline wrapped right round the series, so there is no first or last segment.
 *
 * d3 achieves that by holding the opening three points back and replaying them after the rest: the
 * window that produces each segment therefore straddles the join, and the curve closes onto itself
 * smoothly. Note what it does *not* do — for three points or more it never emits a `Z`, because the
 * wrap already brings the outline back to where it started. Only the degenerate one- and two-point
 * cases close explicitly, and those are the ones a reading of the picture would never predict.
 */
private fun PathBuilder.basisClosed(points: List<PointD>) {
  var x0 = Double.NaN
  var y0 = Double.NaN
  var x1 = Double.NaN
  var y1 = Double.NaN
  var x2 = Double.NaN
  var y2 = Double.NaN
  var x3 = Double.NaN
  var y3 = Double.NaN
  var x4 = Double.NaN
  var y4 = Double.NaN
  var stage = 0

  fun feed(x: Double, y: Double) {
    when (stage) {
      0 -> {
        stage = 1
        x2 = x
        y2 = y
      }
      1 -> {
        stage = 2
        x3 = x
        y3 = y
      }
      2 -> {
        stage = 3
        x4 = x
        y4 = y
        moveTo((x0 + 4.0 * x1 + x) / 6.0, (y0 + 4.0 * y1 + y) / 6.0)
      }
      else -> basisSegment(PointD(x0, y0), PointD(x1, y1), PointD(x, y))
    }
    x0 = x1
    x1 = x
    y0 = y1
    y1 = y
  }

  for (point in points) feed(point.x, point.y)
  when (stage) {
    1 -> {
      moveTo(x2, y2)
      close()
    }
    2 -> {
      moveTo((x2 + 2.0 * x3) / 3.0, (y2 + 2.0 * y3) / 3.0)
      lineTo((x3 + 2.0 * x2) / 3.0, (y3 + 2.0 * y2) / 3.0)
      close()
    }
    3 -> {
      feed(x2, y2)
      feed(x3, y3)
      feed(x4, y4)
    }
  }
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

/**
 * The cardinal spline wrapped round the series, the same way [basisClosed] wraps the B-spline.
 *
 * Three points are held back and replayed at the end, so each tangent near the join is taken from
 * neighbours on the other side of it. As with the closed B-spline, a series of three points or more
 * emits no `Z`: the wrap lands the outline back on its own start.
 */
private fun PathBuilder.cardinalClosed(points: List<PointD>, tension: Double) {
  val k = (1.0 - tension) / 6.0
  var x0 = Double.NaN
  var y0 = Double.NaN
  var x1 = Double.NaN
  var y1 = Double.NaN
  var x2 = Double.NaN
  var y2 = Double.NaN
  var x3 = Double.NaN
  var y3 = Double.NaN
  var x4 = Double.NaN
  var y4 = Double.NaN
  var x5 = Double.NaN
  var y5 = Double.NaN
  var stage = 0

  fun feed(x: Double, y: Double) {
    when (stage) {
      0 -> {
        stage = 1
        x3 = x
        y3 = y
      }
      1 -> {
        stage = 2
        x4 = x
        y4 = y
        moveTo(x, y)
      }
      2 -> {
        stage = 3
        x5 = x
        y5 = y
      }
      else ->
        cubicTo(
          x1 + k * (x2 - x0),
          y1 + k * (y2 - y0),
          x2 + k * (x1 - x),
          y2 + k * (y1 - y),
          x2,
          y2,
        )
    }
    x0 = x1
    x1 = x2
    x2 = x
    y0 = y1
    y1 = y2
    y2 = y
  }

  for (point in points) feed(point.x, point.y)
  when (stage) {
    1 -> {
      moveTo(x3, y3)
      close()
    }
    2 -> {
      lineTo(x3, y3)
      close()
    }
    3 -> {
      feed(x3, y3)
      feed(x4, y4)
      feed(x5, y5)
    }
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

// ---- the open families ------------------------------------------------------

/**
 * The B-spline without end conditions: the curve runs only between the interior control points.
 *
 * Two points or fewer therefore produce **nothing at all** — not a straight line, as the closed and
 * end-pinned families do, but an empty path. Three produce a single position and a `Z`, which draws
 * nothing either. That is upstream's behaviour and it is the reason a chart that switches from
 * `basis` to `basis-open` can lose a short series entirely.
 */
private fun PathBuilder.basisOpen(points: List<PointD>, partOfArea: Boolean) {
  var x0 = Double.NaN
  var y0 = Double.NaN
  var x1 = Double.NaN
  var y1 = Double.NaN
  var stage = 0
  for (point in points) {
    when (stage) {
      0 -> stage = 1
      1 -> stage = 2
      2 -> {
        stage = 3
        moveTo((x0 + 4.0 * x1 + point.x) / 6.0, (y0 + 4.0 * y1 + point.y) / 6.0)
      }
      else -> {
        stage = 4
        basisSegment(PointD(x0, y0), PointD(x1, y1), point)
      }
    }
    x0 = x1
    x1 = point.x
    y0 = y1
    y1 = point.y
  }
  if (stage == 3 && !partOfArea) close()
}

/** The cardinal spline without end conditions; see [basisOpen] for the short-series behaviour. */
private fun PathBuilder.cardinalOpen(points: List<PointD>, tension: Double, partOfArea: Boolean) {
  val k = (1.0 - tension) / 6.0
  var x0 = Double.NaN
  var y0 = Double.NaN
  var x1 = Double.NaN
  var y1 = Double.NaN
  var x2 = Double.NaN
  var y2 = Double.NaN
  var stage = 0
  for (point in points) {
    when (stage) {
      0 -> stage = 1
      1 -> stage = 2
      2 -> {
        stage = 3
        moveTo(x2, y2)
      }
      else -> {
        stage = 4
        cubicTo(
          x1 + k * (x2 - x0),
          y1 + k * (y2 - y0),
          x2 + k * (x1 - point.x),
          y2 + k * (y1 - point.y),
          x2,
          y2,
        )
      }
    }
    x0 = x1
    x1 = x2
    x2 = point.x
    y0 = y1
    y1 = y2
    y2 = point.y
  }
  if (stage == 3 && !partOfArea) close()
}

// ---- bundle -----------------------------------------------------------------

/**
 * Pulls a series `beta` of the way from its own shape towards the straight chord across it, which
 * is all `bundle` is: the result is then drawn as an ordinary [basis] spline.
 *
 * The parameter `t` is the *index* fraction along the series, not arc length, so unevenly spaced
 * points are pulled towards unevenly spaced positions on the chord.
 */
private fun bundled(points: List<PointD>, beta: Double): List<PointD> {
  val last = points.size - 1
  if (last <= 0) return emptyList()
  val first = points[0]
  val dx = points[last].x - first.x
  val dy = points[last].y - first.y
  return points.mapIndexed { index, point ->
    val t = index.toDouble() / last
    PointD(
      beta * point.x + (1.0 - beta) * (first.x + t * dx),
      beta * point.y + (1.0 - beta) * (first.y + t * dy),
    )
  }
}

// ---- catmull-rom ------------------------------------------------------------

/** d3's epsilon, below which a span counts as no span and the tangent is left alone. */
private const val CURVE_EPSILON = 1e-12

/**
 * The running state of a Catmull-Rom spline: three points and the two spans between them, each
 * raised to `alpha` and to `alpha/2`.
 *
 * Held in an object because d3 shares one `point` routine between the open, closed and end-pinned
 * variants, and reproducing that sharing is what keeps the three consistent with each other.
 */
private class CatmullRomState(val alpha: Double) {
  var x0: Double = Double.NaN
  var y0: Double = Double.NaN
  var x1: Double = Double.NaN
  var y1: Double = Double.NaN
  var x2: Double = Double.NaN
  var y2: Double = Double.NaN
  var l01a: Double = 0.0
  var l12a: Double = 0.0
  var l23a: Double = 0.0
  var l012a: Double = 0.0
  var l122a: Double = 0.0
  var l232a: Double = 0.0
  var stage: Int = 0

  /** Records the span to the incoming point, before it becomes part of the window. */
  fun measure(x: Double, y: Double) {
    if (stage == 0) return
    val dx = x2 - x
    val dy = y2 - y
    l232a = (dx * dx + dy * dy).pow(alpha)
    l23a = sqrt(l232a)
  }

  /** Slides the window along by one point. */
  fun advance(x: Double, y: Double) {
    l01a = l12a
    l12a = l23a
    l012a = l122a
    l122a = l232a
    x0 = x1
    x1 = x2
    x2 = x
    y0 = y1
    y1 = y2
    y2 = y
  }
}

/**
 * One Catmull-Rom segment, ending at the middle point of the window.
 *
 * The two control points are the cardinal ones corrected by the ratio of the neighbouring spans —
 * that correction is the whole difference between this family and [cardinal], and it is what stops
 * the curve looping when one span is much shorter than the next.
 */
private fun PathBuilder.catmullRomSegment(s: CatmullRomState, x: Double, y: Double) {
  var x1 = s.x1
  var y1 = s.y1
  var x2 = s.x2
  var y2 = s.y2
  if (s.l01a > CURVE_EPSILON) {
    val a = 2.0 * s.l012a + 3.0 * s.l01a * s.l12a + s.l122a
    val n = 3.0 * s.l01a * (s.l01a + s.l12a)
    x1 = (x1 * a - s.x0 * s.l122a + s.x2 * s.l012a) / n
    y1 = (y1 * a - s.y0 * s.l122a + s.y2 * s.l012a) / n
  }
  if (s.l23a > CURVE_EPSILON) {
    val b = 2.0 * s.l232a + 3.0 * s.l23a * s.l12a + s.l122a
    val m = 3.0 * s.l23a * (s.l23a + s.l12a)
    x2 = (x2 * b + s.x1 * s.l232a - x * s.l122a) / m
    y2 = (y2 * b + s.y1 * s.l232a - y * s.l122a) / m
  }
  cubicTo(x1, y1, x2, y2, s.x2, s.y2)
}

private fun PathBuilder.catmullRom(points: List<PointD>, alpha: Double) {
  val s = CatmullRomState(alpha)
  fun feed(x: Double, y: Double) {
    s.measure(x, y)
    when (s.stage) {
      0 -> {
        s.stage = 1
        moveTo(x, y)
      }
      1 -> s.stage = 2
      else -> {
        s.stage = 3
        catmullRomSegment(s, x, y)
      }
    }
    s.advance(x, y)
  }
  for (point in points) feed(point.x, point.y)
  // The closing segment comes from replaying the last point: with a zero span to itself the
  // correction vanishes and the curve lands on it, which is how the end gets pinned.
  when (s.stage) {
    2 -> lineTo(s.x2, s.y2)
    3 -> feed(s.x2, s.y2)
  }
}

/**
 * The Catmull-Rom spline without end conditions; see [basisOpen] for the short-series behaviour.
 */
private fun PathBuilder.catmullRomOpen(points: List<PointD>, alpha: Double, partOfArea: Boolean) {
  val s = CatmullRomState(alpha)
  for (point in points) {
    s.measure(point.x, point.y)
    when (s.stage) {
      0 -> s.stage = 1
      1 -> s.stage = 2
      2 -> {
        s.stage = 3
        moveTo(s.x2, s.y2)
      }
      else -> {
        s.stage = 4
        catmullRomSegment(s, point.x, point.y)
      }
    }
    s.advance(point.x, point.y)
  }
  if (s.stage == 3 && !partOfArea) close()
}

/**
 * The Catmull-Rom spline wrapped round the series, as [cardinalClosed] wraps the cardinal one.
 *
 * Three points are held back and replayed, so the segments either side of the join are computed
 * from points at the far end of the list.
 */
private fun PathBuilder.catmullRomClosed(points: List<PointD>, alpha: Double) {
  val s = CatmullRomState(alpha)
  var x3 = Double.NaN
  var y3 = Double.NaN
  var x4 = Double.NaN
  var y4 = Double.NaN
  var x5 = Double.NaN
  var y5 = Double.NaN
  fun feed(x: Double, y: Double) {
    s.measure(x, y)
    when (s.stage) {
      0 -> {
        s.stage = 1
        x3 = x
        y3 = y
      }
      1 -> {
        s.stage = 2
        x4 = x
        y4 = y
        moveTo(x, y)
      }
      2 -> {
        s.stage = 3
        x5 = x
        y5 = y
      }
      else -> catmullRomSegment(s, x, y)
    }
    s.advance(x, y)
  }
  for (point in points) feed(point.x, point.y)
  when (s.stage) {
    1 -> {
      moveTo(x3, y3)
      close()
    }
    2 -> {
      lineTo(x3, y3)
      close()
    }
    3 -> {
      feed(x3, y3)
      feed(x4, y4)
      feed(x5, y5)
    }
  }
}
