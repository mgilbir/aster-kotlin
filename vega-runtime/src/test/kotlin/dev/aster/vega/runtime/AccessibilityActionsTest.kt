package dev.aster.vega.runtime

import dev.aster.vega.model.locale.VegaCaptions
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.VectorD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A chart that pans and zooms offers an accessible way to do both, which it did not.
 *
 * The row read `Planned`: "the accessibility tree exposes activation and nothing else", so a reader
 * could reach every bar in a chart and never the view they were drawn in. Panning and zooming were
 * gestures and only gestures.
 *
 * These actions belong to the **chart** rather than to any one mark, which is why they are not on
 * `AccessibleElement`: the scene builds that tree and the scene does not know it has been panned. A
 * host attaches them to the chart's own node — `AccessibilityNodeInfo.addAction` on Android,
 * `UIAccessibilityCustomAction` on Apple.
 *
 * The rule throughout is that **an action is offered only when invoking it would do something**. An
 * action a reader triggers to no effect is worse than one that was never there, because they have
 * no way to tell which they met.
 */
class AccessibilityActionsTest {

  private val controller = VegaChartController()

  private val json =
    """
    {"width": 200, "height": 100, "padding": 0, "autosize": "none",
     "data": [{"name": "t", "values": [{"c": "a", "v": 3}, {"c": "b", "v": 7}]}],
     "scales": [{"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
                 "range": "width"}],
     "marks": [{"type": "rect", "from": {"data": "t"},
                "encode": {"enter": {"x": {"scale": "x", "field": "c"},
                                     "width": {"scale": "x", "band": 1},
                                     "y": {"value": 0}, "y2": {"value": 100}}}}]}
    """
      .trimIndent()

  private fun kinds() = controller.accessibilityActions.map { it.kind }

  private fun viewport() = controller.state.value.snapshot.interactionState

  /**
   * At rest, a chart offers the two zooms and **not** a reset, because there is nothing to undo.
   */
  @Test
  fun `a chart at rest offers zooming but not a reset`() {
    controller.setSpec(json)
    assertEquals(listOf(ChartActionKind.ZOOM_IN, ChartActionKind.ZOOM_OUT), kinds())
  }

  /** Once zoomed, the reset appears — and it is the action that says the view has moved. */
  @Test
  fun `a zoomed chart offers a reset`() {
    controller.setSpec(json)
    assertTrue(controller.perform(ChartActionKind.ZOOM_IN), "zooming in did nothing")
    assertTrue(
      ChartActionKind.RESET_ZOOM in kinds(),
      "a zoomed chart still offers no way back: ${kinds()}",
    )
    assertTrue(viewport().viewportScale > 1.0, "the zoom action did not change the scale")
  }

  /** And the reset puts it back exactly, then stops being offered. */
  @Test
  fun `resetting returns the view to rest and withdraws the action`() {
    controller.setSpec(json)
    controller.perform(ChartActionKind.ZOOM_IN)
    controller.perform(ChartActionKind.ZOOM_IN)
    assertTrue(controller.perform(ChartActionKind.RESET_ZOOM), "the reset did nothing")
    assertEquals(1.0, viewport().viewportScale, "the reset left the chart zoomed")
    assertEquals(VectorD.Zero, viewport().viewportOffset, "the reset left the chart panned")
    assertFalse(
      ChartActionKind.RESET_ZOOM in kinds(),
      "a chart back at rest still offers a reset that would do nothing",
    )
  }

  /**
   * A pan alone offers the reset too, without any zoom.
   *
   * The case a scale-only test would miss: a reader who has panned is just as lost as one who has
   * zoomed, and `viewportScale` is still exactly 1.
   */
  @Test
  fun `a panned chart offers a reset even at the default scale`() {
    controller.setSpec(json)
    controller.dispatch(ChartInputEvent.Pan(delta = VectorD(20.0, 0.0), phase = GesturePhase.ENDED))
    assertEquals(1.0, viewport().viewportScale)
    assertTrue(
      ChartActionKind.RESET_ZOOM in kinds(),
      "a panned chart at the default scale offers no way back: ${kinds()}",
    )
  }

  /**
   * At the zoom limit the action is withdrawn rather than offered and ignored.
   *
   * The half that makes `perform` honest: an action list that never shrinks would have a reader
   * pressing "zoom in" forever at maximum with nothing to tell them why nothing happens.
   */
  @Test
  fun `the zoom actions are withdrawn at the limits`() {
    controller.setSpec(json)
    repeat(80) { controller.perform(ChartActionKind.ZOOM_IN) }
    assertTrue(
      viewport().viewportScale >= VegaChartController.MAX_ZOOM - 1e-9,
      "eighty steps did not reach the zoom limit; the test needs more",
    )
    assertFalse(
      ChartActionKind.ZOOM_IN in kinds(),
      "zoom-in is still offered at the maximum: ${kinds()}",
    )
    assertFalse(
      controller.perform(ChartActionKind.ZOOM_IN),
      "an action that is not offered was performed anyway",
    )
    assertTrue(ChartActionKind.ZOOM_OUT in kinds(), "there is no way back from the limit")
  }

  /**
   * Performing an action that is not offered returns false and changes nothing.
   *
   * The contract a host needs: `false` means nothing happened, so it knows not to announce a
   * change. Announcing one that did not happen is how a reader loses track of where they are.
   */
  @Test
  fun `an action that is not offered does nothing and says so`() {
    controller.setSpec(json)
    val before = viewport()
    assertFalse(
      controller.perform(ChartActionKind.RESET_ZOOM),
      "a reset was performed on a chart that was already at rest",
    )
    assertEquals(before, viewport(), "the refused action changed the viewport anyway")
  }

  /**
   * A zoom action is anchored at the middle of the surface, since a reader has no pointer.
   *
   * Checked by comparing against the gesture path with the same anchor: the two must not drift,
   * because a chart that zooms differently for a reader than for a pointer is two charts.
   */
  @Test
  fun `an action zooms about the middle, exactly as the gesture would`() {
    controller.setSpec(json)
    controller.perform(ChartActionKind.ZOOM_IN)
    val byAction = viewport()

    val gesture = VegaChartController()
    gesture.setSpec(json)
    val scene = gesture.state.value.snapshot.scene
    gesture.dispatch(
      ChartInputEvent.Zoom(
        scaleFactor = VegaChartController.ZOOM_STEP,
        anchor = PointD(scene.width / 2.0, scene.height / 2.0),
        phase = GesturePhase.ENDED,
      )
    )
    assertEquals(
      gesture.state.value.snapshot.interactionState.viewportScale,
      byAction.viewportScale,
    )
    assertEquals(
      gesture.state.value.snapshot.interactionState.viewportOffset,
      byAction.viewportOffset,
      "the action and the gesture zoomed about different points",
    )
  }

  /**
   * These actions move the **viewport** and change no scale, which is still worth pinning.
   *
   * "Zoom" and "adjust the domain" are easy to conflate and are not the same thing. A zoom is the
   * visual transform a pinch applies and leaves every scale exactly as the specification built it,
   * so the ticks a reader hears never change however far in they go.
   *
   * A domain **is** adjustable now — `AdjustableAxisTest` — and deliberately not from here. It
   * belongs to the axis, as the increment and decrement of an adjustable element, because a pair of
   * actions per axis would grow this list with the number of axes: eight entries on a two-axis
   * chart, against the three below. The one thing that did land here is the way *back*, since a
   * reader who has adjusted two axes is not standing on either of them.
   *
   * So no action here narrows or widens anything, and this is what keeps it that way.
   */
  @Test
  fun `no action adjusts a scale's domain`() {
    controller.setSpec(json)
    val before = controller.lastCompiled!!.scales["x"]
    for (action in ChartActionKind.entries) controller.perform(action)
    assertEquals(
      before,
      controller.lastCompiled!!.scales["x"],
      "an accessibility action changed a scale; adjusting a domain belongs to the axis element",
    )
    assertTrue(
      controller.accessibilityActions.none {
        "narrow" in it.label.lowercase() || "widen" in it.label.lowercase()
      },
      "an action narrows or widens a scale, so this list now grows with the number of axes: " +
        "${controller.accessibilityActions.map { it.label }}",
    )
  }

  /** The labels come from the chart's locale, so a host has nowhere to invent its own wording. */
  @Test
  fun `the action labels are the locale's`() {
    val dutch =
      object : VegaCaptions by VegaCaptions.English {
        override fun zoomInAction(): String = "Inzoomen"

        override fun resetZoomAction(): String = "Zoom herstellen"
      }
    val localised = VegaChartController(locale = VegaLocale.EnglishUS.copy(captions = dutch))
    localised.setSpec(json)
    assertEquals(
      "Inzoomen",
      localised.accessibilityActions.first { it.kind == ChartActionKind.ZOOM_IN }.label,
    )
    localised.perform(ChartActionKind.ZOOM_IN)
    assertEquals(
      "Zoom herstellen",
      localised.accessibilityActions.first { it.kind == ChartActionKind.RESET_ZOOM }.label,
    )
  }
}
