package dev.aster.vega.scene

import dev.aster.vega.model.normalizeZero
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

public data class PointD(val x: Double, val y: Double) {
  public companion object {
    public val Origin: PointD = PointD(0.0, 0.0)
  }
}

public data class VectorD(val dx: Double, val dy: Double) {
  public companion object {
    public val Zero: VectorD = VectorD(0.0, 0.0)
  }
}

public data class SizeD(val width: Double, val height: Double) {
  public companion object {
    public val Zero: SizeD = SizeD(0.0, 0.0)
  }
}

/**
 * An axis-aligned rectangle in scene coordinates, with `top` above `bottom` in the y-down
 * coordinate system Vega and Android both use.
 *
 * [Empty] is a deliberately inverted rectangle so that `union` over an empty node list yields an
 * empty result rather than a rectangle anchored at the origin.
 */
public data class RectD(val left: Double, val top: Double, val right: Double, val bottom: Double) {

  public val width: Double
    get() = right - left

  public val height: Double
    get() = bottom - top

  public val isEmpty: Boolean
    get() = right < left || bottom < top

  public val centerX: Double
    get() = (left + right) / 2.0

  public val centerY: Double
    get() = (top + bottom) / 2.0

  public fun contains(x: Double, y: Double): Boolean =
    !isEmpty && x >= left && x <= right && y >= top && y <= bottom

  public fun contains(point: PointD): Boolean = contains(point.x, point.y)

  public fun intersects(other: RectD): Boolean =
    !isEmpty &&
      !other.isEmpty &&
      left <= other.right &&
      other.left <= right &&
      top <= other.bottom &&
      other.top <= bottom

  public fun union(other: RectD): RectD =
    when {
      other.isEmpty -> this
      isEmpty -> other
      else ->
        RectD(
          min(left, other.left),
          min(top, other.top),
          max(right, other.right),
          max(bottom, other.bottom),
        )
    }

  public fun expand(amount: Double): RectD =
    if (isEmpty) this else RectD(left - amount, top - amount, right + amount, bottom + amount)

  public fun translate(dx: Double, dy: Double): RectD =
    if (isEmpty) this else RectD(left + dx, top + dy, right + dx, bottom + dy)

  /** Normalizes `-0.0` components so equal geometry always compares and serializes equal. */
  public fun normalized(): RectD =
    RectD(normalizeZero(left), normalizeZero(top), normalizeZero(right), normalizeZero(bottom))

  public companion object {
    public val Empty: RectD =
      RectD(
        Double.POSITIVE_INFINITY,
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
      )

    public fun fromSize(x: Double, y: Double, width: Double, height: Double): RectD {
      // Negative extents are legal in Vega encodings (a bar drawn upward); normalize so that
      // `left <= right` always holds.
      val l = if (width >= 0) x else x + width
      val t = if (height >= 0) y else y + height
      return RectD(l, t, l + abs(width), t + abs(height))
    }

    public fun fromPoints(points: Iterable<PointD>): RectD {
      var result = Empty
      for (p in points) result = result.union(RectD(p.x, p.y, p.x, p.y))
      return result
    }
  }
}

/**
 * A 2x3 affine transform laid out as in SVG's `matrix(a b c d e f)`:
 * ```
 * | a c e |
 * | b d f |
 * | 0 0 1 |
 * ```
 */
public data class Transform2D(
  val a: Double,
  val b: Double,
  val c: Double,
  val d: Double,
  val e: Double,
  val f: Double,
) {

  public val isIdentity: Boolean
    get() = a == 1.0 && b == 0.0 && c == 0.0 && d == 1.0 && e == 0.0 && f == 0.0

  public fun apply(x: Double, y: Double): PointD = PointD(a * x + c * y + e, b * x + d * y + f)

  public fun apply(point: PointD): PointD = apply(point.x, point.y)

  /** Returns `this * other`, i.e. [other] applied first and `this` second. */
  public fun concat(other: Transform2D): Transform2D =
    Transform2D(
      a = a * other.a + c * other.b,
      b = b * other.a + d * other.b,
      c = a * other.c + c * other.d,
      d = b * other.c + d * other.d,
      e = a * other.e + c * other.f + e,
      f = b * other.e + d * other.f + f,
    )

  /** Axis-aligned bounds of [rect] after this transform. Skew and rotation widen the result. */
  public fun mapBounds(rect: RectD): RectD {
    if (rect.isEmpty) return rect
    if (isIdentity) return rect
    return RectD.fromPoints(
      listOf(
        apply(rect.left, rect.top),
        apply(rect.right, rect.top),
        apply(rect.right, rect.bottom),
        apply(rect.left, rect.bottom),
      )
    )
  }

  public val determinant: Double
    get() = a * d - b * c

  /** Returns `null` for a singular transform; callers must not silently treat that as identity. */
  public fun invert(): Transform2D? {
    val det = determinant
    if (det == 0.0 || !det.isFinite()) return null
    val ia = d / det
    val ib = -b / det
    val ic = -c / det
    val id = a / det
    return Transform2D(
      a = ia,
      b = ib,
      c = ic,
      d = id,
      e = -(ia * e + ic * f),
      f = -(ib * e + id * f),
    )
  }

  public companion object {
    public val Identity: Transform2D = Transform2D(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

    public fun translate(dx: Double, dy: Double): Transform2D =
      Transform2D(1.0, 0.0, 0.0, 1.0, dx, dy)

    public fun scale(sx: Double, sy: Double = sx): Transform2D =
      Transform2D(sx, 0.0, 0.0, sy, 0.0, 0.0)

    public fun rotateRadians(radians: Double): Transform2D {
      val cos = kotlin.math.cos(radians)
      val sin = kotlin.math.sin(radians)
      return Transform2D(cos, sin, -sin, cos, 0.0, 0.0)
    }

    public fun rotateDegrees(degrees: Double): Transform2D =
      rotateRadians(degrees * kotlin.math.PI / 180.0)
  }
}
