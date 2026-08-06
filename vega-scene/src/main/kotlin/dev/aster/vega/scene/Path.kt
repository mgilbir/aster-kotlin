package dev.aster.vega.scene

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Platform-independent path representation. Quadratic and arc segments are converted to cubics at
 * construction time so every consumer (Android `Path`, SVG, hit testing, bounds) handles exactly
 * three command kinds.
 */
public sealed interface PathCommand {
  public data class MoveTo(val x: Double, val y: Double) : PathCommand

  public data class LineTo(val x: Double, val y: Double) : PathCommand

  public data class CubicTo(
    val x1: Double,
    val y1: Double,
    val x2: Double,
    val y2: Double,
    val x: Double,
    val y: Double,
  ) : PathCommand

  public data object Close : PathCommand
}

/** An immutable command list plus its cached tight bounds. */
public class PathData(public val commands: List<PathCommand>) {

  public val isEmpty: Boolean
    get() = commands.isEmpty()

  /**
   * Tight control-point-exact bounds: cubic segments are bounded by their extrema, not their hull.
   */
  public val bounds: RectD by lazy(LazyThreadSafetyMode.NONE) { computeBounds() }

  /** Flattens to polylines, one per subpath, using [tolerance] as the maximum chord deviation. */
  public fun flatten(tolerance: Double = DEFAULT_FLATTEN_TOLERANCE): List<List<PointD>> {
    val subpaths = mutableListOf<List<PointD>>()
    var current = mutableListOf<PointD>()
    var cursor = PointD.Origin
    var subpathStart = PointD.Origin

    fun endSubpath() {
      if (current.size > 1) subpaths.add(current)
      current = mutableListOf()
    }

    for (command in commands) {
      when (command) {
        is PathCommand.MoveTo -> {
          endSubpath()
          cursor = PointD(command.x, command.y)
          subpathStart = cursor
          current.add(cursor)
        }
        is PathCommand.LineTo -> {
          cursor = PointD(command.x, command.y)
          current.add(cursor)
        }
        is PathCommand.CubicTo -> {
          val end = PointD(command.x, command.y)
          flattenCubic(
            cursor,
            PointD(command.x1, command.y1),
            PointD(command.x2, command.y2),
            end,
            tolerance,
            current,
          )
          cursor = end
        }
        PathCommand.Close -> {
          if (current.isNotEmpty()) current.add(subpathStart)
          endSubpath()
          cursor = subpathStart
          current.add(cursor)
        }
      }
    }
    endSubpath()
    return subpaths
  }

  /** Even-odd containment test against the flattened outline. */
  public fun containsEvenOdd(
    point: PointD,
    tolerance: Double = DEFAULT_FLATTEN_TOLERANCE,
  ): Boolean {
    if (!bounds.contains(point)) return false
    var inside = false
    for (ring in flatten(tolerance)) {
      var j = ring.size - 1
      for (i in ring.indices) {
        val a = ring[i]
        val b = ring[j]
        if ((a.y > point.y) != (b.y > point.y)) {
          val t = (point.y - a.y) / (b.y - a.y)
          if (point.x < a.x + t * (b.x - a.x)) inside = !inside
        }
        j = i
      }
    }
    return inside
  }

  /**
   * Shortest distance from [point] to the flattened outline; `POSITIVE_INFINITY` for an empty path.
   */
  public fun distanceToOutline(
    point: PointD,
    tolerance: Double = DEFAULT_FLATTEN_TOLERANCE,
  ): Double {
    var best = Double.POSITIVE_INFINITY
    for (ring in flatten(tolerance)) {
      for (i in 0 until ring.size - 1) {
        best = min(best, distanceToSegment(point, ring[i], ring[i + 1]))
      }
    }
    return best
  }

  private fun computeBounds(): RectD {
    var result = RectD.Empty
    var cursor = PointD.Origin
    var subpathStart = PointD.Origin
    for (command in commands) {
      when (command) {
        is PathCommand.MoveTo -> {
          cursor = PointD(command.x, command.y)
          subpathStart = cursor
          result = result.union(RectD(cursor.x, cursor.y, cursor.x, cursor.y))
        }
        is PathCommand.LineTo -> {
          cursor = PointD(command.x, command.y)
          result = result.union(RectD(cursor.x, cursor.y, cursor.x, cursor.y))
        }
        is PathCommand.CubicTo -> {
          val end = PointD(command.x, command.y)
          result =
            result.union(
              cubicBounds(
                cursor,
                PointD(command.x1, command.y1),
                PointD(command.x2, command.y2),
                end,
              )
            )
          cursor = end
        }
        PathCommand.Close -> cursor = subpathStart
      }
    }
    return result.normalized()
  }

  override fun equals(other: Any?): Boolean = other is PathData && other.commands == commands

  override fun hashCode(): Int = commands.hashCode()

  override fun toString(): String = "PathData(${commands.size} commands)"

  public companion object {
    public const val DEFAULT_FLATTEN_TOLERANCE: Double = 0.25

    public val Empty: PathData = PathData(emptyList())

    public fun build(block: PathBuilder.() -> Unit): PathData = PathBuilder().apply(block).build()
  }
}

/**
 * Accumulates path commands, converting quadratics and elliptical arcs to cubics.
 *
 * Not thread-safe; build a path once and share the resulting immutable [PathData].
 */
public class PathBuilder {
  private val commands = mutableListOf<PathCommand>()
  private var cursor = PointD.Origin
  private var subpathStart = PointD.Origin

  public fun moveTo(x: Double, y: Double): PathBuilder {
    commands.add(PathCommand.MoveTo(x, y))
    cursor = PointD(x, y)
    subpathStart = cursor
    return this
  }

  public fun lineTo(x: Double, y: Double): PathBuilder {
    commands.add(PathCommand.LineTo(x, y))
    cursor = PointD(x, y)
    return this
  }

  public fun cubicTo(
    x1: Double,
    y1: Double,
    x2: Double,
    y2: Double,
    x: Double,
    y: Double,
  ): PathBuilder {
    commands.add(PathCommand.CubicTo(x1, y1, x2, y2, x, y))
    cursor = PointD(x, y)
    return this
  }

  public fun quadraticTo(cx: Double, cy: Double, x: Double, y: Double): PathBuilder {
    // Exact degree elevation from quadratic to cubic.
    val c1x = cursor.x + 2.0 / 3.0 * (cx - cursor.x)
    val c1y = cursor.y + 2.0 / 3.0 * (cy - cursor.y)
    val c2x = x + 2.0 / 3.0 * (cx - x)
    val c2y = y + 2.0 / 3.0 * (cy - y)
    return cubicTo(c1x, c1y, c2x, c2y, x, y)
  }

  public fun close(): PathBuilder {
    commands.add(PathCommand.Close)
    cursor = subpathStart
    return this
  }

  public fun rect(x: Double, y: Double, width: Double, height: Double): PathBuilder {
    val r = RectD.fromSize(x, y, width, height)
    moveTo(r.left, r.top)
    lineTo(r.right, r.top)
    lineTo(r.right, r.bottom)
    lineTo(r.left, r.bottom)
    return close()
  }

  /** Circle approximated by four cubic segments; maximum radial error is about 0.027 %. */
  public fun circle(cx: Double, cy: Double, radius: Double): PathBuilder {
    val k = radius * KAPPA
    moveTo(cx, cy - radius)
    cubicTo(cx + k, cy - radius, cx + radius, cy - k, cx + radius, cy)
    cubicTo(cx + radius, cy + k, cx + k, cy + radius, cx, cy + radius)
    cubicTo(cx - k, cy + radius, cx - radius, cy + k, cx - radius, cy)
    cubicTo(cx - radius, cy - k, cx - k, cy - radius, cx, cy - radius)
    return close()
  }

  public fun polyline(points: List<PointD>, closePath: Boolean = false): PathBuilder {
    if (points.isEmpty()) return this
    moveTo(points[0].x, points[0].y)
    for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
    if (closePath) close()
    return this
  }

  public fun build(): PathData = PathData(commands.toList())

  public companion object {
    /** Control-point offset ratio for a circular quadrant: `4/3 * (sqrt(2) - 1)`. */
    public const val KAPPA: Double = 0.5522847498307933
  }
}

private fun flattenCubic(
  p0: PointD,
  p1: PointD,
  p2: PointD,
  p3: PointD,
  tolerance: Double,
  out: MutableList<PointD>,
) {
  // Segment count from the control polygon's deviation from the chord; cheap and always sufficient
  // because the curve lies within its control hull.
  val deviation =
    max(
      hypot(p1.x - p0.x - (p3.x - p0.x) / 3.0, p1.y - p0.y - (p3.y - p0.y) / 3.0),
      hypot(p2.x - p3.x - (p0.x - p3.x) / 3.0, p2.y - p3.y - (p0.y - p3.y) / 3.0),
    )
  val steps =
    if (deviation <= tolerance) 1
    else kotlin.math.ceil(kotlin.math.sqrt(deviation / tolerance) * 4.0).toInt().coerceIn(1, 256)

  for (i in 1..steps) {
    val t = i.toDouble() / steps
    out.add(evaluateCubic(p0, p1, p2, p3, t))
  }
}

private fun evaluateCubic(p0: PointD, p1: PointD, p2: PointD, p3: PointD, t: Double): PointD {
  val mt = 1.0 - t
  val a = mt * mt * mt
  val b = 3.0 * mt * mt * t
  val c = 3.0 * mt * t * t
  val d = t * t * t
  return PointD(
    a * p0.x + b * p1.x + c * p2.x + d * p3.x,
    a * p0.y + b * p1.y + c * p2.y + d * p3.y,
  )
}

/** Tight bounds of a cubic segment, using the derivative roots on each axis. */
private fun cubicBounds(p0: PointD, p1: PointD, p2: PointD, p3: PointD): RectD {
  var minX = min(p0.x, p3.x)
  var maxX = max(p0.x, p3.x)
  var minY = min(p0.y, p3.y)
  var maxY = max(p0.y, p3.y)

  for (t in cubicExtremaParameters(p0.x, p1.x, p2.x, p3.x)) {
    val x = evaluateCubic(p0, p1, p2, p3, t).x
    minX = min(minX, x)
    maxX = max(maxX, x)
  }
  for (t in cubicExtremaParameters(p0.y, p1.y, p2.y, p3.y)) {
    val y = evaluateCubic(p0, p1, p2, p3, t).y
    minY = min(minY, y)
    maxY = max(maxY, y)
  }
  return RectD(minX, minY, maxX, maxY)
}

/** Roots in `(0, 1)` of the derivative of a 1-D cubic Bezier. */
private fun cubicExtremaParameters(v0: Double, v1: Double, v2: Double, v3: Double): List<Double> {
  val a = -v0 + 3.0 * v1 - 3.0 * v2 + v3
  val b = 2.0 * (v0 - 2.0 * v1 + v2)
  val c = v1 - v0
  val roots = mutableListOf<Double>()

  if (abs(a) < 1e-12) {
    if (abs(b) > 1e-12) roots.add(-c / b)
  } else {
    val discriminant = b * b - 4.0 * a * c
    if (discriminant >= 0.0) {
      val sqrtD = kotlin.math.sqrt(discriminant)
      roots.add((-b + sqrtD) / (2.0 * a))
      roots.add((-b - sqrtD) / (2.0 * a))
    }
  }
  return roots.filter { it > 0.0 && it < 1.0 && it.isFinite() }
}

/** Shortest distance from [p] to the segment `a`-`b`. */
public fun distanceToSegment(p: PointD, a: PointD, b: PointD): Double {
  val dx = b.x - a.x
  val dy = b.y - a.y
  val lengthSquared = dx * dx + dy * dy
  if (lengthSquared == 0.0) return hypot(p.x - a.x, p.y - a.y)
  val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
  return hypot(p.x - (a.x + t * dx), p.y - (a.y + t * dy))
}
