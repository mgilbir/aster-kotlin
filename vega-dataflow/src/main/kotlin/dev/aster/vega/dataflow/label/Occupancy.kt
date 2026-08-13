package dev.aster.vega.dataflow.label

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Which pixels a mark covers, computed from its geometry.
 *
 * **This is the one substitution in the port.** Upstream draws the marks to be avoided into a real
 * canvas and reads the alpha channel back, so a pixel counts as occupied when the rasteriser gave
 * it any coverage at all. There is no canvas under Node — upstream's own `label` transform throws
 * there — so the same question is answered geometrically instead: a pixel is occupied when the
 * shape's outline or interior overlaps the pixel's square.
 *
 * The two answers agree except on pixels a shape barely grazes, where a rasteriser's antialiasing
 * and an exact overlap test can disagree by one pixel. One pixel is enough to change which anchor a
 * label takes, or to drop a label a crowded chart would otherwise fit, so the transform reports
 * this rather than leaving it to be discovered.
 */
internal class Occupancy(private val scaler: Scaler, private val bitmap: Bitmap) {

  /** A filled disc, which is what a `circle` symbol is and what most labelled marks are. */
  fun disc(cx: Double, cy: Double, radius: Double) {
    if (radius <= 0) {
      point(cx, cy)
      return
    }
    val top = scaler.scale(cy - radius)
    val bottom = scaler.scale(cy + radius)
    for (row in top..bottom) {
      // The row of pixels spans a band of the disc; its widest point decides the span to fill.
      val yLow = scaler.invert(row)
      val yHigh = yLow + scaler.ratio
      val nearest = if (cy < yLow) yLow else if (cy > yHigh) yHigh else cy
      val dy = nearest - cy
      val half = radius * radius - dy * dy
      if (half < 0) continue
      val dx = sqrt(half)
      fillRow(row, cx - dx, cx + dx)
    }
  }

  /** An axis-aligned box: a rect, an image, or any shape reduced to its bounds. */
  fun box(x1: Double, y1: Double, x2: Double, y2: Double) {
    val top = scaler.scale(min(y1, y2))
    val bottom = scaler.scale(max(y1, y2))
    for (row in top..bottom) fillRow(row, min(x1, x2), max(x1, x2))
  }

  /** A stroked segment, thickened by half its width, which is what a line mark covers. */
  fun segment(x1: Double, y1: Double, x2: Double, y2: Double, width: Double) {
    val half = max(width, 1.0) / 2
    val top = scaler.scale(min(y1, y2) - half)
    val bottom = scaler.scale(max(y1, y2) + half)
    for (row in top..bottom) {
      val yLow = scaler.invert(row)
      val yHigh = yLow + scaler.ratio
      // The span of x where the segment comes within `half` of this row of pixels.
      var lo = Double.POSITIVE_INFINITY
      var hi = Double.NEGATIVE_INFINITY
      // Sampled at the row's two edges and at the segment's own ends, which bounds a straight
      // segment exactly: within one row the swept region is a trapezium.
      for (y in doubleArrayOf(yLow, yHigh, y1, y2)) {
        if (y < min(y1, y2) - half || y > max(y1, y2) + half) continue
        val t = if (y2 == y1) 0.5 else ((y - y1) / (y2 - y1)).coerceIn(0.0, 1.0)
        val x = x1 + (x2 - x1) * t
        lo = min(lo, x - half)
        hi = max(hi, x + half)
      }
      if (lo > hi) continue
      fillRow(row, lo, hi)
    }
  }

  /** One point, for a mark with no extent of its own. */
  fun point(x: Double, y: Double) {
    val px = scaler.scale(x)
    val py = scaler.scale(y)
    if (!bitmap.outOfBounds(px, py, px, py)) bitmap.set(px, py)
  }

  private fun fillRow(row: Int, xLow: Double, xHigh: Double) {
    if (!xLow.isFinite() || !xHigh.isFinite()) return
    val left = scaler.scale(xLow)
    val right = scaler.scale(xHigh)
    if (bitmap.outOfBounds(left, row, right, row)) {
      // Clamp rather than drop: a mark half off the surface still occupies the half that is on it.
      val cl = left.coerceIn(0, scaler.pixelWidth - 1)
      val cr = right.coerceIn(0, scaler.pixelWidth - 1)
      if (row < 0 || row >= scaler.pixelHeight || cl > cr) return
      bitmap.setRange(cl, row, cr, row)
      return
    }
    bitmap.setRange(left, row, right, row)
  }
}
