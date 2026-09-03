package dev.aster.vega.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `path` mark that is **rotated and scaled**: this engine measures what it painted.
 *
 * `SUPPORTED_FEATURES.md` files this as a `Deliberate difference`, and it is one of the few places
 * the engine knowingly disagrees with upstream rather than reproducing it. Upstream's *renderer*
 * rotates the outline about the item's own `(x, y)`; its *bounds* code rotates the already-placed
 * points about the **origin**, because the context it measures with defines no `translate` and no
 * `rotate`. For a square at `(250, 40)` scaled by `(2, 0.5)` and turned 20 degrees, upstream
 * reports a top-left some seventy units above the shape it drew.
 *
 * Reproducing the quirk would make chart size agree under `autosize: pad` and would put every hit
 * target for such a mark in the wrong place — a reader would tap the shape and miss. So the
 * difference is kept, and this is what holds it: the bounds must contain the geometry as drawn.
 *
 * If somebody ever decides to match upstream instead, these go red, and the row has to change from
 * a deliberate difference into whatever the new answer is.
 */
class RotatedScaledPathBoundsTest {

  /** Upstream's own example: a unit square, placed far from the origin so the quirk is visible. */
  private val square = SvgPath.parse("M0,0L10,0L10,10L0,10Z").path

  private fun node(transform: Transform2D) =
    PathNode(
      id = SceneNodeId(1),
      path = square,
      fill = Fill(ScenePaint.Solid(SceneColor.parse("black")!!)),
      transform = transform,
    )

  private fun placed(transform: Transform2D): RectD = transform.mapBounds(node(transform).bounds)

  /**
   * The corners as the renderer draws them, computed independently of the bounds path.
   *
   * This is what "the box it painted" means, and it is worked from the transform rather than read
   * off `bounds`, so the assertion compares two different routes to the same answer.
   */
  private fun paintedCorners(transform: Transform2D): List<PointD> =
    listOf(
      transform.apply(0.0, 0.0),
      transform.apply(10.0, 0.0),
      transform.apply(10.0, 10.0),
      transform.apply(0.0, 10.0),
    )

  private val rotatedAndScaled: Transform2D =
    Transform2D.translate(250.0, 40.0)
      .concat(Transform2D.rotateDegrees(20.0))
      .concat(Transform2D.scale(2.0, 0.5))

  @Test
  fun `the bounds contain the shape as it is drawn`() {
    val box = placed(rotatedAndScaled)
    for (corner in paintedCorners(rotatedAndScaled)) {
      assertTrue(
        corner.x >= box.left - 1e-9 && corner.x <= box.right + 1e-9,
        "a painted corner at ${corner.x} is outside the bounds ${box.left}..${box.right}",
      )
      assertTrue(
        corner.y >= box.top - 1e-9 && corner.y <= box.bottom + 1e-9,
        "a painted corner at ${corner.y} is outside the bounds ${box.top}..${box.bottom}",
      )
    }
  }

  /**
   * And they are **tight**: every edge is touched by a corner.
   *
   * Containment on its own would be satisfied by any box large enough, upstream's included. What
   * says this engine measures what it painted is that the box is exactly the corners' extent.
   */
  @Test
  fun `the bounds are the painted extent and no larger`() {
    val box = placed(rotatedAndScaled)
    val corners = paintedCorners(rotatedAndScaled)
    assertEquals(corners.minOf { it.x }, box.left, 1e-9)
    assertEquals(corners.maxOf { it.x }, box.right, 1e-9)
    assertEquals(corners.minOf { it.y }, box.top, 1e-9)
    assertEquals(corners.maxOf { it.y }, box.bottom, 1e-9)
  }

  /**
   * The quirk, stated as a number, so the row's example is checked rather than retold.
   *
   * Upstream rotates the *placed* points about the origin, which for a shape 250 units to the right
   * swings it a long way up. Working that out here and asserting this engine does **not** produce
   * it is what makes the difference deliberate rather than accidental: the two answers are far
   * apart, and it is this one that matches the pixels.
   */
  @Test
  fun `upstream's origin-rotated box is far from the painted one and is not what is reported`() {
    val painted = placed(rotatedAndScaled)

    // Upstream: scale, translate, and only then rotate the already-placed points about (0, 0).
    val aboutOrigin = Transform2D.rotateDegrees(20.0)
    val upstreamCorners =
      listOf(0.0 to 0.0, 10.0 to 0.0, 10.0 to 10.0, 0.0 to 10.0).map { (x, y) ->
        aboutOrigin.apply(250.0 + 2.0 * x, 40.0 + 0.5 * y)
      }
    val upstreamTop = upstreamCorners.minOf { it.y }

    assertTrue(
      kotlin.math.abs(upstreamTop - painted.top) > 50.0,
      "the two readings are only ${kotlin.math.abs(upstreamTop - painted.top)} apart, so this " +
        "fixture no longer exercises the difference the row describes",
    )
  }

  /**
   * With **no rotation** the two agree, which is why this is the only shape that differs.
   *
   * Upstream's bounds context has no `rotate`, so a scale alone goes through it unharmed. Pinning
   * that keeps the difference as narrow as the row says it is.
   */
  @Test
  fun `a scaled but unrotated path agrees with upstream's reading`() {
    val scaledOnly = Transform2D.translate(250.0, 40.0).concat(Transform2D.scale(2.0, 0.5))
    val box = placed(scaledOnly)
    assertEquals(250.0, box.left, 1e-9)
    assertEquals(270.0, box.right, 1e-9)
    assertEquals(40.0, box.top, 1e-9)
    assertEquals(45.0, box.bottom, 1e-9)
  }
}
