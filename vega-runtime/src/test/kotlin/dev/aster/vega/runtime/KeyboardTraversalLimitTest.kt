package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.PointD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A key press reaches the controller and moves nothing, which is what the row means by *partial*.
 *
 * `SUPPORTED_FEATURES.md`: "Key events are translated; traversal semantics come with accessibility
 * work." Two separate facts, and both are pinned here, because the second is the kind of gap a
 * reader has to be able to trust.
 *
 * **Translated**: a host turns a platform key into a [ChartInputEvent.Key] and hands it over.
 * `VegaChartView.onKeyDown` does exactly that on Android and `ChartSession.press(_:)` on Apple.
 *
 * **And moves nothing**: `fireSignalHandlers` maps only the pointer family, so a key produces no
 * Vega event, fires no `keydown` handler, and moves no focus between marks. That is deliberate and
 * the Android view says why in its own comment — claiming a key the chart then does nothing with is
 * a **focus trap**: TAB would never move focus off the chart, ESC would never dismiss the sheet it
 * sits in, and on a television, where the d-pad is the keyboard, the four arrows would let a reader
 * enter the chart and not leave.
 *
 * So the day traversal arrives, these go red — which is correct, because the row will then be
 * describing something else.
 */
class KeyboardTraversalLimitTest {

  private val controller = VegaChartController()

  private val json =
    """
    {
      "width": 120, "height": 60, "padding": 0, "autosize": "none",
      "data": [{"name": "t", "values": [{"v": 10}, {"v": 40}, {"v": 70}]}],
      "signals": [
        {"name": "keys", "value": 0, "on": [{"events": "keydown", "update": "keys + 1"}]},
        {"name": "taps", "value": 0, "on": [{"events": "mousedown", "update": "taps + 1"}]}
      ],
      "marks": [{
        "type": "rect", "from": {"data": "t"},
        "encode": {"enter": {"x": {"field": "v"}, "y": {"value": 10},
                             "width": {"value": 20}, "height": {"value": 20},
                             "fill": {"value": "#4c78a8"}}}
      }]
    }
    """
      .trimIndent()

  private val everyKey =
    listOf(
      ChartKey.TAB,
      ChartKey.ARROW_LEFT,
      ChartKey.ARROW_RIGHT,
      ChartKey.ARROW_UP,
      ChartKey.ARROW_DOWN,
      ChartKey.HOME,
      ChartKey.END,
      ChartKey.ENTER,
      ChartKey.SPACE,
      ChartKey.ESCAPE,
    )

  @Test
  fun `no key moves focus between marks`() {
    controller.setSpec(json)
    assertNull(controller.state.value.snapshot.interactionState.focusedNodeId)
    for (key in everyKey) {
      controller.dispatch(ChartInputEvent.Key(key))
      assertNull(
        controller.state.value.snapshot.interactionState.focusedNodeId,
        "$key moved focus, so traversal has arrived and this row is out of date",
      )
    }
  }

  /**
   * And no key fires a signal handler, because no Vega event is produced for one.
   *
   * `keydown` is a selector a specification may perfectly well write, so this is the sharper half:
   * such a specification compiles, draws, and never fires. The row has to say so.
   */
  @Test
  fun `a keydown handler does not fire`() {
    controller.setSpec(json)
    for (key in everyKey) controller.dispatch(ChartInputEvent.Key(key))
    assertEquals(
      VegaValue.Num(0.0),
      controller.lastCompiled!!.signals["keys"],
      "a key fired a keydown handler, so key events now reach the dispatch",
    )
  }

  /**
   * The pointer family *does* fire, which is what keeps the two above from being vacuous.
   *
   * Without it they would pass equally well for a controller whose dispatch was broken outright,
   * and the row would be recording the wrong limitation.
   */
  @Test
  fun `the pointer family still fires, so the dispatch itself works`() {
    controller.setSpec(json)
    controller.dispatch(
      ChartInputEvent.PointerDown(
        PointD(15.0, 15.0),
        pointerId = 1,
        device = PointerDevice.MOUSE,
        buttons = 1,
      )
    )
    assertEquals(VegaValue.Num(1.0), controller.lastCompiled!!.signals["taps"])
  }

  /**
   * A key changes nothing else either — no selection, no hover, no viewport.
   *
   * Stated because "moves no focus" alone would leave room for a key that quietly panned the chart
   * or cleared a selection, which a reader pressing ESC in a dialog would find surprising.
   */
  @Test
  fun `a key leaves the rest of the interaction state alone`() {
    controller.setSpec(json)
    val before = controller.state.value.snapshot.interactionState
    for (key in everyKey) controller.dispatch(ChartInputEvent.Key(key))
    assertEquals(before, controller.state.value.snapshot.interactionState)
  }
}
