package dev.aster.vega.runtime

import dev.aster.vega.scene.ChartKey
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneNodeId
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Keyboard focus is **visible**, which it was not on any host.
 *
 * `InteractionState.focusedNodeId` was written by `handleKey` and read by **no renderer** — not the
 * Android canvas, not the SVG one, not Compose Multiplatform — and no host announced it either. So
 * arrow-key traversal moved an invisible cursor: a reader learned where they were by pressing Enter
 * and watching the selection change (#227).
 *
 * Drawn **into the scene** rather than by each host, for the same reason the accessibility tree is
 * built once. Three renderers drawing their own idea of a focus ring is three chances to disagree
 * about a thing a reader needs to be identical, and every renderer already draws whatever the scene
 * holds — so this reaches all of them at once, the SVG export included.
 */
class FocusRingTest {

  private val spec =
    """
    {"width": 200, "height": 100, "padding": 0, "autosize": "none",
     "data": [{"name": "t", "values": [{"c": "a", "v": 3}, {"c": "b", "v": 7}]}],
     "scales": [{"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
                 "range": "width"}],
     "marks": [{"type": "rect", "from": {"data": "t"},
                "encode": {"enter": {"x": {"scale": "x", "field": "c"},
                                     "width": {"scale": "x", "band": 1},
                                     "y": {"value": 0}, "y2": {"value": 100},
                                     "description": {"signal": "'bar ' + datum.c"}}}}]}
    """
      .trimIndent()

  private val controller = VegaChartController()

  private fun ring(): RectNode? =
    controller.state.value.snapshot.scene
      .flatten()
      .map { it.node }
      .filterIsInstance<RectNode>()
      .firstOrNull { it.id == SceneNodeId.Overlay }

  @Test
  fun `nothing focused draws no ring`() {
    controller.setSpec(spec)
    assertNull(ring(), "a chart nobody has focused is drawing a focus ring")
  }

  @Test
  fun `an arrow key draws a ring around the mark it focused`() {
    controller.setSpec(spec)
    assertTrue(controller.handleKey(ChartKey.ARROW_RIGHT), "the arrow key moved no focus")
    val focused = controller.state.value.snapshot.interactionState.focusedNodeId
    assertNotNull(focused, "nothing was focused, so this decides nothing")

    val drawn = ring()
    assertNotNull(
      drawn,
      "the focused mark has no ring, so a keyboard reader cannot see where it is",
    )

    // Around the mark, not on it: the ring is inset outwards so a thin mark still gets a ring
    // rather than a thicker version of itself.
    val mark =
      controller.state.value.snapshot.scene.flatten().first { it.node.id == focused }.worldBounds
    assertEquals(mark.left - FocusRing().inset, drawn!!.x, 1e-9)
    assertEquals(mark.top - FocusRing().inset, drawn.y, 1e-9)
    assertEquals(mark.width + FocusRing().inset * 2, drawn.width, 1e-9)
    assertEquals(mark.height + FocusRing().inset * 2, drawn.height, 1e-9)
    // An outline, not a fill: the mark's own paint has to stay visible underneath.
    assertNull(drawn.fill, "the focus ring is filled, so it hides the mark it surrounds")
    assertNotNull(drawn.stroke, "the focus ring has no outline")
  }

  /** The ring **moves** with the focus rather than accumulating. */
  @Test
  fun `moving the focus moves the one ring`() {
    controller.setSpec(spec)
    controller.handleKey(ChartKey.ARROW_RIGHT)
    val first = ring()!!.x
    controller.handleKey(ChartKey.ARROW_RIGHT)
    val rings =
      controller.state.value.snapshot.scene
        .flatten()
        .map { it.node }
        .count { it.id == SceneNodeId.Overlay }
    assertEquals(1, rings, "the rings are stacking up as the focus moves")
    assertTrue(ring()!!.x != first, "the ring did not follow the focus")
  }

  /** And clearing the focus takes it away. */
  @Test
  fun `escape clears the focus and the ring with it`() {
    controller.setSpec(spec)
    controller.handleKey(ChartKey.ARROW_RIGHT)
    assertNotNull(ring())
    controller.handleKey(ChartKey.ESCAPE)
    assertNull(
      controller.state.value.snapshot.interactionState.focusedNodeId,
      "escape did not clear the focus, so this decides nothing",
    )
    assertNull(ring(), "the ring outlived the focus it was drawing")
  }

  /**
   * The ring is **not** a mark: it cannot be hit, and it is not announced.
   *
   * A reader is already standing on the thing it surrounds, so announcing the ring would be a
   * second element for one mark; and a ring that can be tapped is a target over every focused mark.
   */
  @Test
  fun `the ring is neither hit-testable nor announced`() {
    controller.setSpec(spec)
    controller.handleKey(ChartKey.ARROW_RIGHT)
    val drawn = ring()!!
    assertFalse(drawn.metadata.interactive, "the focus ring is hit-testable")
    assertNull(drawn.metadata.accessibility, "the focus ring is announced as an element of its own")
  }

  /**
   * Its id is **reserved**, which took a bug to get right.
   *
   * The ring is appended to a finished scene and stripped again when the focus moves, so it needs
   * an identity no mark can share. Taking one from a fresh `SceneNodeIdAllocator` gives
   * `SceneNodeId(1)` — the first mark of every scene — and stripping the ring then deleted a bar.
   */
  @Test
  fun `the ring's id cannot collide with a mark's`() {
    controller.setSpec(spec)
    val marks =
      controller.state.value.snapshot.scene
        .flatten()
        .map { it.node.id }
        .filter { it != SceneNodeId.Overlay }
    assertFalse(
      SceneNodeId.Overlay in marks,
      "the overlay id is one a scene build can hand out, so stripping the ring can delete a mark",
    )
    controller.handleKey(ChartKey.ARROW_RIGHT)
    controller.handleKey(ChartKey.ESCAPE)
    assertEquals(
      marks.size,
      controller.state.value.snapshot.scene.flatten().count(),
      "focusing and unfocusing changed how many nodes the chart has",
    )
  }
}
