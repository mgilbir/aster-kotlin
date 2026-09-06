package dev.aster.vega.runtime

import dev.aster.vega.fixtures.SampleScenes
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNodeId
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a chart keeps when it is replaced, and what a **tap** draws.
 *
 * Both reported from using the demo. "Click on stacked bar, click on one of the bars, tooltip
 * appears, move to line and the tooltip is still there" — interaction state that names marks
 * outlived the marks. And: "Do we need to highlight always an item on click? It looks bad" — every
 * tap drew the focus ring, which exists so a reader arrowing through a chart can see where they
 * are.
 */
class FocusRingAndChartChangeTest {

  private fun firstBarCentre(scene: Scene): PointD {
    val bar = scene.flatten().map { it.node }.filterIsInstance<RectNode>().first()
    return PointD(bar.rect.centerX, bar.rect.centerY)
  }

  private fun ring(controller: VegaChartController): RectNode? =
    controller.snapshot.scene
      .flatten()
      .map { it.node }
      .filterIsInstance<RectNode>()
      .firstOrNull {
        it.id == SceneNodeId.Overlay
      }

  private fun tapAMark(controller: VegaChartController) {
    controller.dispatch(ChartInputEvent.Tap(firstBarCentre(controller.snapshot.scene)))
  }

  // MARK: what a replaced chart keeps

  /** The reported one, in the order it was reported. */
  @Test
  fun `a tooltip does not outlive the chart it came from`() {
    val controller = VegaChartController.fromScene(SampleScenes.stackedBarChart())
    tapAMark(controller)
    assertNotNull(
      controller.snapshot.interactionState.tooltip,
      "the tap produced no tooltip, so this test would pass for the wrong reason",
    )

    controller.setScene(SampleScenes.lineChart())

    val after = controller.snapshot.interactionState
    assertNull(after.tooltip, "the tooltip outlived its chart")
    assertNull(after.tooltipAnchor, "the tooltip's anchor outlived its chart")
    assertTrue(after.selection.isEmpty, "the selection outlived its chart")
    assertNull(after.hoveredNodeId, "the hover outlived its chart")
    assertNull(after.focusedNodeId, "the focus outlived its chart")
  }

  /**
   * The **viewport** is the exception, and stays.
   *
   * A pan and a zoom are a statement about the surface rather than about any mark, which is the
   * line between the two halves of `InteractionState`. A host that wants them dropped calls
   * `resetViewport`.
   */
  @Test
  fun `a pan survives the chart it was made on`() {
    val controller = VegaChartController.fromScene(SampleScenes.barChart())
    controller.dispatch(ChartInputEvent.Zoom(2.0, PointD(10.0, 10.0), GesturePhase.ENDED))
    val zoomed = controller.snapshot.interactionState.viewportScale
    assertTrue(zoomed != 1.0, "the zoom did not take, so this test would prove nothing")

    controller.setScene(SampleScenes.lineChart())

    assertEquals(zoomed, controller.snapshot.interactionState.viewportScale)
  }

  // MARK: what a tap draws

  /** A tap moves focus and draws **no** ring, which is the `:focus-visible` rule. */
  @Test
  fun `a tap moves focus without drawing a ring`() {
    val controller = VegaChartController.fromScene(SampleScenes.barChart())
    tapAMark(controller)

    val state = controller.snapshot.interactionState
    assertNotNull(
      state.focusedNodeId,
      "a tap must still move focus, or arrowing on from it restarts",
    )
    assertFalse(state.focusVisible, "a tap must not make the ring visible")
    assertNull(ring(controller), "a tap drew a focus ring")
  }

  /** The keyboard does draw one — that is what it is for. */
  @Test
  fun `arrowing to a mark draws the ring`() {
    val controller = VegaChartController.fromScene(SampleScenes.barChart())
    controller.dispatch(ChartInputEvent.Key(dev.aster.vega.scene.ChartKey.ARROW_RIGHT))

    assertTrue(controller.snapshot.interactionState.focusVisible, "the keyboard must show the ring")
    assertNotNull(ring(controller), "arrowing to a mark drew no focus ring")
  }

  /** And a tap after arrowing takes the ring away again, rather than leaving it behind. */
  @Test
  fun `a tap after the keyboard hides the ring again`() {
    val controller = VegaChartController.fromScene(SampleScenes.barChart())
    controller.dispatch(ChartInputEvent.Key(dev.aster.vega.scene.ChartKey.ARROW_RIGHT))
    assertNotNull(ring(controller), "the keyboard drew no ring, so this proves nothing")

    tapAMark(controller)

    assertNull(ring(controller), "the ring survived a pointer interaction")
  }

  /** A host that wants a ring on touch asks for one. */
  @Test
  fun `a host can ask for the ring on a pointer`() {
    val controller = VegaChartController.fromScene(SampleScenes.barChart())
    controller.focusRing = FocusRing(showsOnPointer = true)

    tapAMark(controller)

    assertTrue(controller.snapshot.interactionState.focusVisible)
    assertNotNull(ring(controller), "showsOnPointer did not draw a ring on a tap")
  }

  /**
   * A **dash** reaches the ring, which is the lever a quieter one actually needs.
   *
   * Thinner or paler loses the contrast that makes a focus indicator findable; a dash keeps it and
   * reads as less of a border. The demo offers both so the difference can be seen.
   */
  @Test
  fun `a dashed ring reaches the scene`() {
    val controller = VegaChartController.fromScene(SampleScenes.barChart())
    controller.focusRing = FocusRing(dash = listOf(3.0, 3.0))
    controller.dispatch(ChartInputEvent.Key(dev.aster.vega.scene.ChartKey.ARROW_RIGHT))

    val drawn = ring(controller) ?: error("the keyboard drew no ring")
    assertEquals(listOf(3.0, 3.0), drawn.stroke?.dashArray, "the dash did not reach the ring")
  }

  /** And can restyle it, live, while one is on screen. */
  @Test
  fun `a host can restyle the ring and see it change`() {
    val controller = VegaChartController.fromScene(SampleScenes.barChart())
    controller.dispatch(ChartInputEvent.Key(dev.aster.vega.scene.ChartKey.ARROW_RIGHT))
    val before = ring(controller) ?: error("the keyboard drew no ring, so this proves nothing")

    controller.focusRing = FocusRing(inset = 8.0, width = 5.0, colour = SceneColor(1.0, 0.0, 0.0))

    val after = ring(controller) ?: error("restyling the ring removed it")
    assertEquals(5.0, after.stroke?.width, "the ring kept the old width")
    assertTrue(after.width > before.width, "a larger inset must make a larger ring")
  }
}
