package dev.aster.vega.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A stroked `path` mark's bounds under `scaleX` and `scaleY`.
 *
 * **The stroke is measured in the space the pen draws in, not the space the outline is written
 * in.** Upstream renders the path through the transform and only then widens for the stroke —
 * `pathRender(context, cache, x, y, sx, sy)` fills the bounds and `boundStroke` expands what came
 * out — so `scaleX` stretches the shape and not the pen.
 *
 * These bounds are local and are mapped afterwards, so the allowance has to be divided by the scale
 * here to survive the multiplication later. Getting it wrong is invisible until something is both
 * scaled *and* stroked, which **no fixture in the corpus was**: `path-scaled-stroke` had to be
 * written to catch it, and it found a second bug in the comparison while it was there.
 *
 * The expected numbers are upstream's, read off `vega@6.3.1` running the same specification.
 */
class PathStrokeBoundsTest {

  /** The square `path-scaled-stroke` opens with: 1.25 to 6.75 in its own coordinates. */
  private val square = SvgPath.parse("M1.25,1.25L6.75,1.25L6.75,6.75L1.25,6.75Z").path

  private fun node(
    transform: Transform2D,
    width: Double = 2.0,
    join: StrokeJoin = StrokeJoin.MITER,
    cap: StrokeCap = StrokeCap.BUTT,
    miterLimit: Double = Stroke.DEFAULT_MITER_LIMIT,
  ) =
    PathNode(
      id = SceneNodeId(1),
      path = square,
      stroke =
        Stroke(
          paint = ScenePaint.Solid(SceneColor.parse("black")!!),
          width = width,
          join = join,
          cap = cap,
          miterLimit = miterLimit,
        ),
      transform = transform,
    )

  /**
   * The case the corpus was missing, with upstream's own numbers.
   *
   * A two-unit miter-joined stroke has an allowance of `max(w/2, miterLimit * w / 2)` = 4. Scaled
   * by 8 and translated by 10, upstream reports the item's bounds as 16 to 68 — 52 wide. Expanding
   * locally by the full 4 instead would hand 32 units to the transform and give 108.
   */
  @Test
  fun `a scaled stroke keeps upstream's allowance rather than a scaled one`() {
    val transform = Transform2D.translate(10.0, 10.0).concat(Transform2D.scale(8.0, 8.0))
    val bounds = transform.mapBounds(node(transform).bounds)
    assertEquals(16.0, bounds.left, 1e-9)
    assertEquals(68.0, bounds.right, 1e-9)
    assertEquals(52.0, bounds.right - bounds.left, 1e-9)
    assertEquals(52.0, bounds.bottom - bounds.top, 1e-9)
  }

  /**
   * A non-uniform scale needs a different allowance on each axis, which a single expand cannot
   * give.
   *
   * The reason `RectD.expand` gained a two-argument form. Stretched twelve times horizontally and
   * three times vertically, the same four-unit allowance is four units on **both** axes after the
   * transform — so locally it must be a third of a unit one way and a third of that the other.
   */
  @Test
  fun `a non-uniform scale gets an even margin on every side`() {
    val transform = Transform2D.scale(12.0, 3.0)
    val plain = transform.mapBounds(node(transform, width = 0.0).bounds)
    val stroked = transform.mapBounds(node(transform).bounds)
    assertEquals(4.0, plain.left - stroked.left, 1e-9, "left margin")
    assertEquals(4.0, stroked.right - plain.right, 1e-9, "right margin")
    assertEquals(4.0, plain.top - stroked.top, 1e-9, "top margin")
    assertEquals(4.0, stroked.bottom - plain.bottom, 1e-9, "bottom margin")
  }

  /**
   * An unscaled path is untouched, which is every path in the corpus.
   *
   * The guard that says the fix is a fix rather than a change: 196 fixtures compare mark for mark
   * against upstream and none of them moved.
   */
  @Test
  fun `an unscaled path measures exactly as it did`() {
    val bounds = node(Transform2D.translate(10.0, 10.0)).bounds
    // 1.25 - 4 to 6.75 + 4, in the path's own coordinates.
    assertEquals(-2.75, bounds.left, 1e-9)
    assertEquals(10.75, bounds.right, 1e-9)
  }

  /**
   * The allowance is the **stroke's**, not a constant that happens to be four.
   *
   * A miter limit of 1 gives `max(w/2, 1 * w / 2)` = 1 rather than 4, so dividing by the scale must
   * divide the right number. Read off upstream: the same square at `scaleX: 8` with `strokeWidth:
   * 2` and `strokeMiterLimit: 1` comes out 46 wide where the default limit gives 52.
   */
  @Test
  fun `a lower miter limit narrows the allowance before it is divided`() {
    val transform = Transform2D.translate(10.0, 10.0).concat(Transform2D.scale(8.0, 8.0))
    val bounds = transform.mapBounds(node(transform, miterLimit = 1.0).bounds)
    assertEquals(46.0, bounds.right - bounds.left, 1e-9)
  }

  /** A rotation stretches nothing, so it must not change the allowance either. */
  @Test
  fun `a rotation leaves the allowance alone`() {
    val transform = Transform2D.rotateDegrees(30.0)
    val plain = node(transform, width = 0.0).bounds
    val stroked = node(transform).bounds
    assertEquals(4.0, plain.left - stroked.left, 1e-9)
    assertEquals(4.0, plain.top - stroked.top, 1e-9)
  }

  /** A collapsed scale has no margin to divide, and dividing would be by zero. */
  @Test
  fun `a zero scale does not divide by it`() {
    val bounds = node(Transform2D.scale(0.0, 0.0)).bounds
    assertEquals(true, bounds.left.isFinite() && bounds.right.isFinite(), "bounds went non-finite")
  }
}
