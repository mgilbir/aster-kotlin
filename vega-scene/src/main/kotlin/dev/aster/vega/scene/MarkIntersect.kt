package dev.aster.vega.scene

import kotlin.math.abs

/**
 * Whether a mark meets a rectangle — the geometry under `intersect()` and a brush selection.
 *
 * These are upstream's `intersectPoint`, `intersectRect`, `intersectRule` and `intersectBoxLine`,
 * which decide which marks a dragged region has caught. They are pure geometry: given an item's
 * numbers and a box, no scene and no renderer.
 *
 * The **expression** `intersect(box)` still answers an empty array here, and correctly — it asks
 * the live scenegraph what a pointer is over, and a signal resolves before a scene exists, which is
 * upstream's answer in the same position. What these give is the layer beneath it, verified against
 * upstream's own vectors, so that anything wanting hit-testing later starts from arithmetic that is
 * already right rather than from a second guess at it.
 *
 * The fourth primitive, `intersectPath`, is deliberately absent: upstream rasterises the path into
 * an offscreen canvas and walks the pixels of the overlap with `isPointInPath`. That is a
 * renderer's answer, not a geometry one, and it says so itself — with no context it returns `true`
 * and lets the bounds stand in.
 */
public object MarkIntersect {

  /**
   * A **point** mark: whether the box contains its centre.
   *
   * Not its drawn circle — upstream tests the centre alone, so a symbol whose edge overlaps the
   * brush but whose middle does not is *not* caught. Reproduced rather than improved, because a
   * selection that disagrees with the one a reader dragged is worse than a strict one.
   */
  public fun point(x: Double, y: Double, box: RectD): Boolean =
    box.left <= x && x <= box.right && box.top <= y && y <= box.bottom

  /** A **rect** mark: whether the two rectangles overlap at all. */
  public fun rect(x: Double, y: Double, width: Double, height: Double, box: RectD): Boolean =
    x + width >= box.left && x <= box.right && y + height >= box.top && y <= box.bottom

  /**
   * A **rule**: whether the segment from `(x, y)` to `(x2, y2)` crosses the box.
   *
   * A rule with no second point is a point, which is what upstream's `x2 != null ? x2 : x` means.
   */
  public fun rule(x: Double, y: Double, x2: Double, y2: Double, box: RectD): Boolean =
    boxLine(box, x, y, x2, y2)

  /**
   * Liang–Barsky: whether a segment meets an axis-aligned rectangle.
   *
   * The segment is walked as a parameter from 0 to 1 and clipped against each of the four edges in
   * turn, narrowing the interval that could still be inside. It survives if the interval does. The
   * `1e-10` is upstream's and is doing real work: a segment exactly parallel to an edge has a zero
   * denominator, and the test says it misses only when it also lies outside that edge.
   */
  public fun boxLine(box: RectD, x: Double, y: Double, u: Double, v: Double): Boolean {
    val dx = u - x
    val dy = v - y
    var enter = 0.0
    var leave = 1.0

    for (edge in 0 until 4) {
      val p: Double
      val q: Double
      when (edge) {
        0 -> {
          p = -dx
          q = -(box.left - x)
        }
        1 -> {
          p = dx
          q = box.right - x
        }
        2 -> {
          p = -dy
          q = -(box.top - y)
        }
        else -> {
          p = dy
          q = box.bottom - y
        }
      }
      if (abs(p) < 1e-10 && q < 0) return false
      val at = q / p
      if (p < 0) {
        if (at > leave) return false else if (at > enter) enter = at
      } else if (p > 0) {
        if (at < enter) return false else if (at < leave) leave = at
      }
    }
    return true
  }
}
