package dev.aster.vega.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The rounded-rectangle outline, against the path strings `vega-scenegraph`'s own generator
 * produces.
 *
 * Worth pinning to the command level rather than to a picture. The corner is a cubic Bézier with a
 * control-point offset of `1 - 0.448084975506` of the radius — Mortensen's circle approximation,
 * not the more familiar `4/3·(√2-1)` — and the clamp that keeps a radius inside the rectangle is
 * applied to all four corners as a group against `min(width, height) / 2`, so the radii interact.
 */
class RectPathTest {

  private fun path(
    x: Double,
    y: Double,
    width: Double,
    height: Double,
    cornerRadius: Double = 0.0,
    topLeft: Double? = null,
    topRight: Double? = null,
    bottomRight: Double? = null,
    bottomLeft: Double? = null,
  ): PathData? =
    RectNode(
        id = SceneNodeId(0),
        x = x,
        y = y,
        width = width,
        height = height,
        cornerRadius = cornerRadius,
        cornerRadiusTopLeft = topLeft,
        cornerRadiusTopRight = topRight,
        cornerRadiusBottomRight = bottomRight,
        cornerRadiusBottomLeft = bottomLeft,
      )
      .roundedPath

  /** d3-path's own text, rounded to six decimals so the vectors stay readable. */
  private fun svg(path: PathData): String =
    path.commands.joinToString("") { command ->
      when (command) {
        is PathCommand.MoveTo -> "M${n(command.x)},${n(command.y)}"
        is PathCommand.LineTo -> "L${n(command.x)},${n(command.y)}"
        is PathCommand.CubicTo ->
          "C${n(command.x1)},${n(command.y1)},${n(command.x2)},${n(command.y2)}," +
            "${n(command.x)},${n(command.y)}"
        PathCommand.Close -> "Z"
      }
    }

  private fun n(value: Double): String {
    val rounded = kotlin.math.round(value * 1_000_000.0) / 1_000_000.0
    return if (rounded == kotlin.math.truncate(rounded)) rounded.toLong().toString()
    else rounded.toString()
  }

  @Test
  fun `one radius on all four corners`() {
    assertEquals(
      "M18,20L102,20C106.41532,20,110,23.58468,110,28L110,72C110,76.41532,106.41532,80,102,80" +
        "L18,80C13.58468,80,10,76.41532,10,72L10,28C10,23.58468,13.58468,20,18,20Z",
      svg(path(10.0, 20.0, 100.0, 60.0, cornerRadius = 8.0)!!),
    )
  }

  @Test
  fun `a radius larger than the rectangle is clamped to half its shorter side`() {
    // 40 on a 20px-tall bar becomes 10, which is why the two `L100,10 L100,10` legs are empty.
    assertEquals(
      "M10,0L90,0C95.51915,0,100,4.48085,100,10L100,10C100,15.51915,95.51915,20,90,20" +
        "L10,20C4.48085,20,0,15.51915,0,10L0,10C0,4.48085,4.48085,0,10,0Z",
      svg(path(0.0, 0.0, 100.0, 20.0, cornerRadius = 40.0)!!),
    )
  }

  @Test
  fun `a per-corner radius of zero squares that corner off`() {
    // The reason the overrides are nullable: `cornerRadius: 6` with `cornerRadiusTopLeft: 0` is a
    // bar rounded on three corners, and reading the override's absence as zero would flatten all
    // four.
    assertEquals(
      "M0,0L74,0C77.31149,0,80,2.68851,80,6L80,34C80,37.31149,77.31149,40,74,40" +
        "L6,40C2.68851,40,0,37.31149,0,34L0,0C0,0,0,0,0,0Z",
      svg(path(0.0, 0.0, 80.0, 40.0, cornerRadius = 6.0, topLeft = 0.0)!!),
    )
  }

  @Test
  fun `four different radii`() {
    assertEquals(
      "M2,0L76,0C78.20766,0,80,1.79234,80,4L80,34C80,37.31149,77.31149,40,74,40" +
        "L8,40C3.58468,40,0,36.41532,0,32L0,2C0,0.89617,0.89617,0,2,0Z",
      svg(
        path(
          0.0,
          0.0,
          80.0,
          40.0,
          topLeft = 2.0,
          topRight = 4.0,
          bottomRight = 6.0,
          bottomLeft = 8.0,
        )!!
      ),
    )
  }

  @Test
  fun `the clamp is one limit for all four corners, not one per corner`() {
    // Only the top-left asks for more than the rectangle can hold, and only it is cut back — but
    // the
    // limit it is cut back to comes from the rectangle's shorter side, so the two corners that were
    // never set stay square rather than following the clamped value.
    assertEquals(
      "M10,0L97,0C98.655745,0,100,1.344255,100,3L100,20C100,20,100,20,100,20" +
        "L0,20C0,20,0,20,0,20L0,10C0,4.48085,4.48085,0,10,0Z",
      svg(path(0.0, 0.0, 100.0, 20.0, topLeft = 40.0, topRight = 3.0)!!),
    )
  }

  @Test
  fun `a square rectangle has no outline of its own`() {
    assertNull(path(0.0, 0.0, 50.0, 30.0))
  }

  @Test
  fun `a negative extent squares every corner`() {
    // The clamp's upper bound is `min(width, height) / 2` — negative here — so every radius clamps
    // to zero. Upstream draws `M100,50h-40v-20h40Z`: a plain rectangle.
    assertNull(path(100.0, 50.0, -40.0, -20.0, cornerRadius = 4.0))
  }
}
