package dev.aster.vega.android

import android.view.KeyEvent
import android.view.View
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aster.vega.runtime.VegaChartController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The chart's **own** accessibility actions, on a real node, reached the way TalkBack reaches them.
 *
 * `VegaChartController.accessibilityActions` has offered zooming and resetting since it was
 * written, and **no host wired them** — the feature was built, tested and documented against
 * `AccessibilityNodeInfo.addAction` and the call was never made, so a reader could reach every bar
 * in a chart and never the view they were drawn in (#226).
 *
 * Instrumented because that is the only place the claim can be checked: what matters is that a real
 * `AccessibilityNodeInfo` carries the action and that performing it by id does something. A JVM
 * test could assert the controller's list, which was already true and was never the problem.
 */
@RunWith(AndroidJUnit4::class)
class ChartActionsInstrumentedTest {

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  private val spec =
    """
    {"width": 200, "height": 100, "padding": 0, "autosize": "none",
     "data": [{"name": "t", "values": [{"c": "a", "v": 3}, {"c": "b", "v": 7}]}],
     "scales": [{"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
                 "range": "width"},
                {"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"},
                 "range": "height"}],
     "axes": [{"orient": "left", "scale": "y"}],
     "marks": [{"type": "rect", "from": {"data": "t"},
                "encode": {"enter": {"x": {"scale": "x", "field": "c"},
                                     "width": {"scale": "x", "band": 1},
                                     "y": {"value": 0}, "y2": {"value": 100},
                                     "description": {"signal": "'bar ' + datum.c"}}}}]}
    """
      .trimIndent()

  private fun laidOut(): Pair<VegaChartView, VegaChartController> {
    var pair: Pair<VegaChartView, VegaChartController>? = null
    InstrumentationRegistryHelper.onMain {
      val controller = VegaChartController(textEngine = AndroidTextEngine())
      controller.setSpec(spec)
      val view = VegaChartView(context)
      view.controller = controller
      view.measure(
        View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
      )
      view.layout(0, 0, 200, 100)
      pair = view to controller
    }
    return pair!!
  }

  /** The node a reader lands on carries the chart's actions, with the engine's own labels. */
  private fun hostNode(view: VegaChartView): AccessibilityNodeInfoCompat {
    // Through the **View's** own method rather than `ViewCompat`'s deprecated static: this is the
    // route the framework itself takes when it builds a node for a11y, so it exercises the delegate
    // exactly as TalkBack would.
    val info = AccessibilityNodeInfoCompat.obtain()
    InstrumentationRegistryHelper.onMain { view.onInitializeAccessibilityNodeInfo(info.unwrap()) }
    return info
  }

  private fun actionIds(info: AccessibilityNodeInfoCompat): List<Int> =
    info.actionList.map { it.id }

  @Test
  fun theChartsOwnNodeOffersTheZoomActions() {
    val (view, _) = laidOut()
    val ids = actionIds(hostNode(view))
    assertTrue(
      "the chart's node offers no zoom-in action, so a reader cannot zoom: $ids",
      R.id.aster_vega_action_zoom_in in ids,
    )
    assertTrue(
      "the chart's node offers no zoom-out action: $ids",
      R.id.aster_vega_action_zoom_out in ids,
    )
    // Nothing has moved yet, so there is nothing to reset and the action is not offered.
    assertTrue(
      "a chart at rest offers a reset that would do nothing: $ids",
      R.id.aster_vega_action_reset_zoom !in ids,
    )
  }

  @Test
  fun performingZoomInChangesTheViewAndOffersAResetAfterwards() {
    val (view, controller) = laidOut()
    var performed = false
    InstrumentationRegistryHelper.onMain {
      performed = view.performAccessibilityAction(R.id.aster_vega_action_zoom_in, null)
    }
    assertTrue("performing zoom-in through the node reported no change", performed)
    assertTrue(
      "the viewport did not zoom",
      controller.snapshot.interactionState.viewportScale > 1.0,
    )
    val ids = actionIds(hostNode(view))
    assertTrue("a zoomed chart offers no way back: $ids", R.id.aster_vega_action_reset_zoom in ids)
  }

  /** An action the controller does not offer is refused rather than performed silently. */
  @Test
  fun anActionThatIsNotOfferedIsRefused() {
    val (view, controller) = laidOut()
    var performed = true
    InstrumentationRegistryHelper.onMain {
      performed = view.performAccessibilityAction(R.id.aster_vega_action_reset_zoom, null)
    }
    assertTrue("a reset was performed on a chart already at rest", !performed)
    assertEquals(1.0, controller.snapshot.interactionState.viewportScale, 1e-9)
  }

  /** And the axis reset appears only once an axis has actually been adjusted. */
  @Test
  fun theAxisResetAppearsOnlyAfterAnAdjustment() {
    val (view, controller) = laidOut()
    assertTrue(
      "an unadjusted chart offers an axis reset",
      R.id.aster_vega_action_reset_domains !in actionIds(hostNode(view)),
    )
    var adjusted = false
    InstrumentationRegistryHelper.onMain { adjusted = controller.adjustScaleDomain("y", true) }
    assertTrue("the axis could not be adjusted, so this decides nothing", adjusted)
    assertTrue(
      "an adjusted chart offers no way back",
      R.id.aster_vega_action_reset_domains in actionIds(hostNode(view)),
    )
  }

  /**
   * The **engine's** focus follows this host's traversal, so the focus ring follows the reader.
   *
   * Two notions of focus existed and never met. `ExploreByTouchHelper.dispatchKeyEvent` claims the
   * arrow keys to walk its own virtual views, so `VegaChartController.handleKey`'s traversal never
   * runs here — the platform's implementation wins before the engine's is asked, which is the right
   * outcome because the helper's traversal is what a TalkBack reader already knows. What was
   * missing is the engine being told, so the focus ring drawn into the scene follows them (#227).
   *
   * The direction is the whole finding, and the first attempt had it backwards: pushing the
   * engine's focus into the helper could never have worked, because the engine's focus never moves
   * on this host.
   */
  @Test
  fun theHostsTraversalMovesTheEnginesFocus() {
    val (view, controller) = laidOut()
    assertNull(
      "a fresh chart already has a focused node",
      controller.snapshot.interactionState.focusedNodeId,
    )
    InstrumentationRegistryHelper.onMain {
      view.isFocusableInTouchMode = true
      view.requestFocus()
      view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
    }
    assertNotNull(
      "walking the chart with an arrow key did not move the engine's focus, so the focus ring " +
        "cannot follow the reader",
      controller.snapshot.interactionState.focusedNodeId,
    )
  }
}
