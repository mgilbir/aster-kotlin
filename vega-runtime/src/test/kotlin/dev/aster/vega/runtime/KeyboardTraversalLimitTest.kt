package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.PointD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * What a key still does **not** do, now that traversal exists.
 *
 * This class used to hold that no key moved anything at all. `KeyboardTraversalTest` closes that,
 * and what is left is narrower and easy to miss: a key moves focus, and it still fires **no signal
 * handler**. `keydown` is a selector a specification may perfectly well write, so such a chart
 * compiles, draws, traverses — and never updates.
 *
 * The old assertions are gone rather than kept, because they had stopped meaning anything. They
 * used a chart whose rects carry no `description`, and a mark with no description is not focusable,
 * so there was nothing for a key to move focus *between*: the test would have gone on passing after
 * traversal arrived, while claiming traversal did not exist. Every test here now uses a chart with
 * described marks, and the first one below exists only to prove that.
 */
class KeyboardTraversalLimitTest {

  private val controller = VegaChartController()

  /**
   * Described marks, so the accessibility tree has something in it and a key has somewhere to go.
   */
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
                             "fill": {"value": "#4c78a8"},
                             "description": {"signal": "'bar ' + datum.v"}}}
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

  /**
   * The guard on everything below: this chart really does have somewhere for focus to go.
   *
   * Without it the rest would pass for a chart with nothing focusable in it, which is precisely how
   * the previous version of this class came to assert something false and stay green.
   */
  @Test
  fun `the chart under test has focusable marks`() {
    controller.setSpec(json)
    controller.handleKey(ChartKey.ARROW_RIGHT)
    assertNotNull(
      controller.state.value.snapshot.interactionState.focusedNodeId,
      "no mark could be focused, so nothing else here is testing what it says",
    )
  }

  /**
   * No key fires a signal handler, because no Vega event is produced for one.
   *
   * `fireSignalHandlers` maps only the pointer family. A specification writing `{"events":
   * "keydown"}` gets a chart that compiles and draws and never updates from the keyboard, and that
   * is worth a test of its own now that keys visibly *do* something else.
   */
  @Test
  fun `a keydown handler does not fire`() {
    controller.setSpec(json)
    for (key in everyKey) controller.dispatch(ChartInputEvent.Key(key))
    assertEquals(
      VegaValue.Num(0.0),
      controller.lastCompiled!!.signals.values["keys"],
      "a key fired a keydown handler, so key events now reach the event dispatch",
    )
  }

  /**
   * The pointer family *does* fire, which is what keeps the above from being vacuous.
   *
   * Without it the test would pass equally well for a controller whose dispatch was broken
   * outright.
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
    assertEquals(VegaValue.Num(1.0), controller.lastCompiled!!.signals.values["taps"])
  }

  /**
   * A key moves focus and a selection, and touches **nothing else** — no hover, no viewport.
   *
   * The narrowed form of what this class used to claim. Traversal is allowed to move focus and, on
   * activation, the selection; a key that panned the chart or changed what is hovered would be a
   * surprise to a reader who pressed an arrow.
   */
  @Test
  fun `a key leaves the hover and the viewport alone`() {
    controller.setSpec(json)
    val before = controller.state.value.snapshot.interactionState
    for (key in everyKey) controller.dispatch(ChartInputEvent.Key(key))
    val after = controller.state.value.snapshot.interactionState
    assertEquals(before.hoveredNodeId, after.hoveredNodeId, "a key changed what is hovered")
    assertEquals(before.viewportOffset, after.viewportOffset, "a key panned the chart")
    assertEquals(before.viewportScale, after.viewportScale, "a key zoomed the chart")
    assertEquals(before.tooltip, after.tooltip, "a key changed the tooltip")

    // Focus is checked mid-sequence rather than at the end, because the run finishes on ESCAPE and
    // ESCAPE clears it — comparing the ends would be comparing null with null and would pass for a
    // controller in which no key did anything, which is the mistake this class made before.
    val fresh = VegaChartController()
    fresh.setSpec(json)
    fresh.dispatch(ChartInputEvent.Key(ChartKey.ARROW_RIGHT))
    assertNotNull(
      fresh.state.value.snapshot.interactionState.focusedNodeId,
      "no key moved focus, so traversal is not running and this test proves nothing",
    )
  }
}
