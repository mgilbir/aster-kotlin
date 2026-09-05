package dev.aster.vega.compose.mp

import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.SizeD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A corner radius reaches a target **clamped**, because the clamping is upstream's and not a
 * platform's.
 *
 * `Corners.of` clamps the four radii as one group against `min(width, height) / 2`, which is the
 * rule `vega-scenegraph` applies. This walk used to hand the target the raw `cornerRadius` instead
 * and leave the clamping to whatever received it, and the two answers are not the same one:
 *
 * - the scene's rule gives a 100×20 bar with a 40-unit top-left radius a **10**-unit corner, the
 *   same as any other corner of that bar could have;
 * - Skia's `RoundRect`, handed the raw 40, scales all four radii by one factor until they fit — the
 *   CSS rule — and gives **20**, because only the two radii on the short edge constrain it and the
 *   other one there is zero.
 *
 * So a rounded bar was drawn with twice the corner on Compose Multiplatform that the Android canvas
 * and an exported SVG of the same chart drew, both of which read the clamped `RectNode.corners`.
 *
 * **Why nothing caught it.** `test-fixtures/scene-walk` compares this walk with the Apple one call
 * for call, and both were emitting the same wrong number — the Apple *target* then clamped again
 * for itself, so its picture was right for a reason the comparison could not see. The goldens
 * record `corners=[…]`, the instruction rather than the curve, which is exactly the blind spot
 * issue #245 was filed about.
 */
class CornerClampTest {

  private val ids = SceneNodeIdAllocator()

  private fun sceneOf(vararg children: SceneNode): Scene =
    Scene(
      width = 200.0,
      height = 100.0,
      background = null,
      root = GroupNode(id = ids.allocate(), children = children.toList()),
    )

  private fun recorded(scene: Scene): String {
    val calls = CanonicalCalls()
    SceneWalk().draw(scene, calls)
    return calls.text
  }

  /** The corners of the one rect in a recording, as written. */
  private fun cornersOf(text: String): String =
    text
      .lines()
      .first { it.trimStart().startsWith("rect ") }
      .substringAfter("corners=[")
      .substringBefore(']')

  /**
   * The case the two rules disagree about: one large radius on a squat bar.
   *
   * Unequal radii are what separates them. With all four equal the two rules agree — Skia's common
   * factor works out to `min(w, h) / 2r`, which is the clamp — so a test using a uniform
   * `cornerRadius` would have passed against the unclamped walk and proved nothing.
   */
  @Test
  fun `one large radius on a squat bar is clamped by the scene, not by the target`() {
    val bar =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 100.0,
        height = 20.0,
        cornerRadiusTopLeft = 40.0,
        fill = Fill(ScenePaint.Black),
      )
    assertEquals(10.0, bar.corners.topLeft, "the scene's own answer changed")
    assertEquals(
      "10.000,0.000,0.000,0.000",
      cornersOf(recorded(sceneOf(bar))),
      "the walk passed on the raw radius",
    )
  }

  /** Equal radii too, so the ordinary rounded bar is pinned as well as the awkward one. */
  @Test
  fun `a uniform radius larger than the bar can hold is clamped to half its short side`() {
    val bar =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 100.0,
        height = 20.0,
        cornerRadius = 40.0,
        fill = Fill(ScenePaint.Black),
      )
    assertEquals("10.000,10.000,10.000,10.000", cornersOf(recorded(sceneOf(bar))))
  }

  /** A radius that already fits is passed through untouched, so the clamp is not a cap on style. */
  @Test
  fun `a radius the bar can hold is unchanged`() {
    val bar =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 100.0,
        height = 20.0,
        cornerRadiusTopLeft = 4.0,
        cornerRadiusBottomRight = 2.5,
        fill = Fill(ScenePaint.Black),
      )
    assertEquals("4.000,0.000,2.500,0.000", cornersOf(recorded(sceneOf(bar))))
  }

  /**
   * A **group's** panel is clamped against the panel, which is a different rectangle from the
   * group's own extent once it has been clipped.
   */
  @Test
  fun `a group panel's corners are clamped against the panel`() {
    val group =
      GroupNode(
        id = ids.allocate(),
        size = SizeD(100.0, 20.0),
        cornerRadiusTopLeft = 40.0,
        fill = Fill(ScenePaint.Black),
      )
    assertEquals(10.0, group.paintCorners.topLeft, "the scene's own answer changed")
    val text = recorded(sceneOf(group))
    assertTrue(
      text.contains("corners=[10.000,0.000,0.000,0.000]"),
      "the group panel kept a raw radius:\n$text",
    )
  }
}
