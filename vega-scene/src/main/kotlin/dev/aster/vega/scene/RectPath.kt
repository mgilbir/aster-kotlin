package dev.aster.vega.scene

import kotlin.math.max
import kotlin.math.min

/**
 * The four corner radii of a rectangle, clamped to what the rectangle can hold.
 *
 * Clamped as one group against `min(width, height) / 2` — not each against its own two edges —
 * which is upstream's rule and matters when the radii differ: a 100×20 bar with a 40px top-left
 * radius gets 10, the same as every other corner would, rather than the 20 its longer edge could
 * allow.
 */
public data class Corners(
  val topLeft: Double,
  val topRight: Double,
  val bottomRight: Double,
  val bottomLeft: Double,
) {
  public val isSquare: Boolean
    get() = topLeft <= 0.0 && topRight <= 0.0 && bottomRight <= 0.0 && bottomLeft <= 0.0

  public companion object {
    public fun of(
      width: Double,
      height: Double,
      topLeft: Double,
      topRight: Double,
      bottomRight: Double,
      bottomLeft: Double,
    ): Corners {
      // `min(width, height)`, not of their magnitudes. A rectangle given a negative extent has a
      // negative limit, every radius clamps to zero and the corners come out square — which is what
      // upstream draws, and taking the magnitude here would round the corners of a bar drawn
      // upwards
      // from its baseline.
      val limit = min(width, height) / 2.0
      fun clamp(value: Double) = max(0.0, min(value, limit))
      return Corners(clamp(topLeft), clamp(topRight), clamp(bottomRight), clamp(bottomLeft))
    }
  }
}

/**
 * A rectangle with independently rounded corners, as a path.
 *
 * The corners are cubic Béziers with a control-point offset of `1 - 0.448084975506` of the radius,
 * which is Mortensen's four-segment circle approximation and *not* the more familiar `4/3 ·
 * (√2 - 1) ≈ 0.5523`. The two differ by about a thousandth of the radius — invisible on screen, but
 * this is the constant Vega draws with, so it is the constant here.
 *
 * A path rather than an SVG `rx`/`ry` for two reasons: `rx` cannot express four different radii at
 * all, and even for one radius it is a true elliptical arc where upstream's is that approximation.
 */
public object RectPath {

  /** `1 - C`, the fraction of the radius the control points sit in from the corner. */
  private const val C = 0.448084975506

  public fun of(x: Double, y: Double, width: Double, height: Double, corners: Corners): PathData {
    val x2 = x + width
    val y2 = y + height
    val (tl, tr, br, bl) = corners
    return PathData.build {
      moveTo(x + tl, y)
      lineTo(x2 - tr, y)
      cubicTo(x2 - C * tr, y, x2, y + C * tr, x2, y + tr)
      lineTo(x2, y2 - br)
      cubicTo(x2, y2 - C * br, x2 - C * br, y2, x2 - br, y2)
      lineTo(x + bl, y2)
      cubicTo(x + C * bl, y2, x, y2 - C * bl, x, y2 - bl)
      lineTo(x, y + tl)
      cubicTo(x, y + C * tl, x + C * tl, y, x + tl, y)
      close()
    }
  }
}
