package dev.aster.vega.scene

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reads an SVG path string into [PathData].
 *
 * This is what a `path` mark's `path` channel holds, and what a `symbol`'s `shape` holds when a
 * specification wants an outline the twelve built-in shapes do not cover. Both were reported as
 * unsupported until this existed.
 *
 * The whole grammar is accepted, including the parts that are easy to forget and common in real
 * path data:
 * - a command letter may be **omitted** when it repeats, so `M0,0 10,10 20,20` is a move followed
 *   by two lines — a repeated `M` becomes `L`, which is the one exception to "the same letter
 *   again";
 * - numbers need no separator when the sign or the decimal point already ends the previous one, so
 *   `10-20` is two numbers and `.5.5` is two more;
 * - `S`, `s`, `T` and `t` reflect the previous control point, and reflect the *current point* when
 *   the previous command was not a curve of the matching kind;
 * - after `Z`, the current point returns to the start of the subpath, which is where a following
 *   relative command measures from.
 *
 * Anything malformed stops the parse and returns what was read up to that point rather than
 * throwing: a chart with a truncated glyph is more use than no chart, and the caller reports it.
 */
public object SvgPath {

  /** Returns the parsed path, and whether the whole string was understood. */
  public data class Result(val path: PathData, val complete: Boolean)

  public fun parse(source: String): Result {
    val reader = Reader(source)
    val builder = PathBuilder()
    var startX = 0.0
    var startY = 0.0
    var currentX = 0.0
    var currentY = 0.0
    // The reflected control point for a following S/s or T/t, or null when there is nothing to
    // reflect and the current point stands in for it.
    var lastCubicControl: PointD? = null
    var lastQuadControl: PointD? = null
    var command = ' '
    var started = false

    while (true) {
      reader.skipSeparators()
      if (reader.atEnd) return Result(builder.build(), complete = true)

      val next = reader.peek()
      if (next.isLetter()) {
        command = next
        reader.advance()
      } else if (command == ' ') {
        return Result(builder.build(), complete = false)
      } else if (command == 'M') {
        // A repeated implicit move is a line, which is the one place the shorthand changes meaning.
        command = 'L'
      } else if (command == 'm') {
        command = 'l'
      }

      val relative = command.isLowerCase()
      fun x(value: Double) = if (relative) currentX + value else value
      fun y(value: Double) = if (relative) currentY + value else value

      when (command.uppercaseChar()) {
        'M' -> {
          val px = x(reader.number() ?: return Result(builder.build(), false))
          val py = y(reader.number() ?: return Result(builder.build(), false))
          builder.moveTo(px, py)
          currentX = px
          currentY = py
          startX = px
          startY = py
          started = true
          lastCubicControl = null
          lastQuadControl = null
        }
        'L' -> {
          val px = x(reader.number() ?: return Result(builder.build(), false))
          val py = y(reader.number() ?: return Result(builder.build(), false))
          if (!started) return Result(builder.build(), false)
          builder.lineTo(px, py)
          currentX = px
          currentY = py
          lastCubicControl = null
          lastQuadControl = null
        }
        'H' -> {
          val px = x(reader.number() ?: return Result(builder.build(), false))
          if (!started) return Result(builder.build(), false)
          builder.lineTo(px, currentY)
          currentX = px
          lastCubicControl = null
          lastQuadControl = null
        }
        'V' -> {
          val py = y(reader.number() ?: return Result(builder.build(), false))
          if (!started) return Result(builder.build(), false)
          builder.lineTo(currentX, py)
          currentY = py
          lastCubicControl = null
          lastQuadControl = null
        }
        'C' -> {
          val c1x = x(reader.number() ?: return Result(builder.build(), false))
          val c1y = y(reader.number() ?: return Result(builder.build(), false))
          val c2x = x(reader.number() ?: return Result(builder.build(), false))
          val c2y = y(reader.number() ?: return Result(builder.build(), false))
          val px = x(reader.number() ?: return Result(builder.build(), false))
          val py = y(reader.number() ?: return Result(builder.build(), false))
          if (!started) return Result(builder.build(), false)
          builder.cubicTo(c1x, c1y, c2x, c2y, px, py)
          currentX = px
          currentY = py
          lastCubicControl = PointD(c2x, c2y)
          lastQuadControl = null
        }
        'S' -> {
          val reflected = reflect(lastCubicControl, currentX, currentY)
          val c2x = x(reader.number() ?: return Result(builder.build(), false))
          val c2y = y(reader.number() ?: return Result(builder.build(), false))
          val px = x(reader.number() ?: return Result(builder.build(), false))
          val py = y(reader.number() ?: return Result(builder.build(), false))
          if (!started) return Result(builder.build(), false)
          builder.cubicTo(reflected.x, reflected.y, c2x, c2y, px, py)
          currentX = px
          currentY = py
          lastCubicControl = PointD(c2x, c2y)
          lastQuadControl = null
        }
        'Q' -> {
          val cx = x(reader.number() ?: return Result(builder.build(), false))
          val cy = y(reader.number() ?: return Result(builder.build(), false))
          val px = x(reader.number() ?: return Result(builder.build(), false))
          val py = y(reader.number() ?: return Result(builder.build(), false))
          if (!started) return Result(builder.build(), false)
          builder.quadraticTo(cx, cy, px, py)
          currentX = px
          currentY = py
          lastQuadControl = PointD(cx, cy)
          lastCubicControl = null
        }
        'T' -> {
          val reflected = reflect(lastQuadControl, currentX, currentY)
          val px = x(reader.number() ?: return Result(builder.build(), false))
          val py = y(reader.number() ?: return Result(builder.build(), false))
          if (!started) return Result(builder.build(), false)
          builder.quadraticTo(reflected.x, reflected.y, px, py)
          currentX = px
          currentY = py
          lastQuadControl = reflected
          lastCubicControl = null
        }
        'A' -> {
          val rx = reader.number() ?: return Result(builder.build(), false)
          val ry = reader.number() ?: return Result(builder.build(), false)
          val rotation = reader.number() ?: return Result(builder.build(), false)
          val largeArc = reader.flag() ?: return Result(builder.build(), false)
          val sweep = reader.flag() ?: return Result(builder.build(), false)
          val px = x(reader.number() ?: return Result(builder.build(), false))
          val py = y(reader.number() ?: return Result(builder.build(), false))
          if (!started) return Result(builder.build(), false)
          builder.endpointArc(currentX, currentY, rx, ry, rotation, largeArc, sweep, px, py)
          currentX = px
          currentY = py
          lastCubicControl = null
          lastQuadControl = null
        }
        'Z' -> {
          if (started) builder.close()
          // A relative command after `Z` measures from the subpath's start, not from where the
          // pen last drew.
          currentX = startX
          currentY = startY
          lastCubicControl = null
          lastQuadControl = null
        }
        else -> return Result(builder.build(), complete = false)
      }
    }
  }

  /** The previous control point mirrored through the current point, or the point itself. */
  private fun reflect(control: PointD?, x: Double, y: Double): PointD =
    if (control == null) PointD(x, y) else PointD(2.0 * x - control.x, 2.0 * y - control.y)

  /**
   * SVG's endpoint-parameterised elliptical arc, converted to its centre form and then to cubics.
   *
   * The conversion is the one in the SVG specification's implementation notes, including its two
   * corrections: a radius too small to reach the endpoint is scaled up until it just does, and a
   * zero radius degenerates to a straight line rather than dividing by zero.
   */
  private fun PathBuilder.endpointArc(
    x0: Double,
    y0: Double,
    radiusX: Double,
    radiusY: Double,
    rotationDegrees: Double,
    largeArc: Boolean,
    sweep: Boolean,
    x: Double,
    y: Double,
  ) {
    var rx = abs(radiusX)
    var ry = abs(radiusY)
    if (rx == 0.0 || ry == 0.0 || (x0 == x && y0 == y)) {
      lineTo(x, y)
      return
    }
    val phi = rotationDegrees * kotlin.math.PI / 180.0
    val cosPhi = cos(phi)
    val sinPhi = sin(phi)
    val dx2 = (x0 - x) / 2.0
    val dy2 = (y0 - y) / 2.0
    val x1 = cosPhi * dx2 + sinPhi * dy2
    val y1 = -sinPhi * dx2 + cosPhi * dy2

    // Scale the radii up if they are too small to span the two endpoints.
    val lambda = (x1 * x1) / (rx * rx) + (y1 * y1) / (ry * ry)
    if (lambda > 1.0) {
      val scale = sqrt(lambda)
      rx *= scale
      ry *= scale
    }

    val numerator = rx * rx * ry * ry - rx * rx * y1 * y1 - ry * ry * x1 * x1
    val denominator = rx * rx * y1 * y1 + ry * ry * x1 * x1
    val factor = (if (largeArc == sweep) -1.0 else 1.0) * sqrt(maxOf(0.0, numerator) / denominator)
    val cx1 = factor * rx * y1 / ry
    val cy1 = -factor * ry * x1 / rx
    val cx = cosPhi * cx1 - sinPhi * cy1 + (x0 + x) / 2.0
    val cy = sinPhi * cx1 + cosPhi * cy1 + (y0 + y) / 2.0

    val startAngle = angleBetween(1.0, 0.0, (x1 - cx1) / rx, (y1 - cy1) / ry)
    var delta = angleBetween((x1 - cx1) / rx, (y1 - cy1) / ry, (-x1 - cx1) / rx, (-y1 - cy1) / ry)
    if (!sweep && delta > 0.0) delta -= 2.0 * kotlin.math.PI
    if (sweep && delta < 0.0) delta += 2.0 * kotlin.math.PI

    // An eighth of a turn per cubic, the same allowance the annular arcs use.
    val segments = ceil(abs(delta) / (kotlin.math.PI / 4.0)).toInt().coerceAtLeast(1)
    val step = delta / segments
    val handle = 4.0 / 3.0 * kotlin.math.tan(step / 4.0)
    var angle = startAngle
    for (index in 0 until segments) {
      val to = angle + step
      val cosA = cos(angle)
      val sinA = sin(angle)
      val cosB = cos(to)
      val sinB = sin(to)
      val p0x = cx + rx * cosPhi * cosA - ry * sinPhi * sinA
      val p0y = cy + rx * sinPhi * cosA + ry * cosPhi * sinA
      val p1x = cx + rx * cosPhi * cosB - ry * sinPhi * sinB
      val p1y = cy + rx * sinPhi * cosB + ry * cosPhi * sinB
      val d0x = -rx * cosPhi * sinA - ry * sinPhi * cosA
      val d0y = -rx * sinPhi * sinA + ry * cosPhi * cosA
      val d1x = -rx * cosPhi * sinB - ry * sinPhi * cosB
      val d1y = -rx * sinPhi * sinB + ry * cosPhi * cosB
      cubicTo(
        p0x + handle * d0x,
        p0y + handle * d0y,
        p1x - handle * d1x,
        p1y - handle * d1y,
        p1x,
        p1y,
      )
      angle = to
    }
  }

  private fun angleBetween(ux: Double, uy: Double, vx: Double, vy: Double): Double {
    val dot = ux * vx + uy * vy
    val lengths = sqrt(ux * ux + uy * uy) * sqrt(vx * vx + vy * vy)
    val sign = if (ux * vy - uy * vx < 0.0) -1.0 else 1.0
    return sign * acos((dot / lengths).coerceIn(-1.0, 1.0))
  }

  /** A cursor over the path string. */
  private class Reader(private val source: String) {
    private var index = 0

    val atEnd: Boolean
      get() = index >= source.length

    fun peek(): Char = source[index]

    fun advance() {
      index++
    }

    fun skipSeparators() {
      while (index < source.length && (source[index].isWhitespace() || source[index] == ',')) {
        index++
      }
    }

    /**
     * A number, in the loose form path data uses.
     *
     * A sign or a decimal point ends the previous number without any separator, so `10-20` is two
     * numbers and `.5.5` is two more — which is how minified path data is written and where a
     * split-on-whitespace parser goes wrong.
     */
    fun number(): Double? {
      skipSeparators()
      val start = index
      if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
      var sawDigit = false
      var sawDot = false
      while (index < source.length) {
        val c = source[index]
        when {
          c.isDigit() -> {
            sawDigit = true
            index++
          }
          c == '.' && !sawDot -> {
            sawDot = true
            index++
          }
          (c == 'e' || c == 'E') && sawDigit -> {
            index++
            if (index < source.length && (source[index] == '+' || source[index] == '-')) index++
          }
          else -> break
        }
      }
      if (!sawDigit) {
        index = start
        return null
      }
      return source.substring(start, index).toDoubleOrNull().also { if (it == null) index = start }
    }

    /** An arc's flag, which is a single character and may not be separated from what follows. */
    fun flag(): Boolean? {
      skipSeparators()
      if (atEnd) return null
      return when (source[index]) {
        '0' -> {
          index++
          false
        }
        '1' -> {
          index++
          true
        }
        else -> null
      }
    }
  }
}
