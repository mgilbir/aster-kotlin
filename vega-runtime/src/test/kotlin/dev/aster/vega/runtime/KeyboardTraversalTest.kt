package dev.aster.vega.runtime

import dev.aster.vega.scene.ChartKey
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Moving between marks with the keyboard, and — just as much — being able to leave.
 *
 * `KeyboardTraversalLimitTest` used to hold that no key moved anything, and said why that was
 * deliberate rather than unfinished: claiming a key the chart then does nothing with is a **focus
 * trap**, where TAB never leaves the chart and, on a television where the d-pad is the keyboard,
 * the four arrows let a reader in and not out. Not claiming keys was the right answer while nothing
 * moved.
 *
 * So traversal arrives with the trap designed out, and that is what most of this class is about.
 * [VegaChartController.handleKey] returns whether the key was **consumed**, and a key is consumed
 * only when it actually did something:
 *
 * - TAB is never consumed, in any state.
 * - An arrow at the end of the order is not consumed, so focus continues outward.
 * - `ENTER`/`SPACE` are consumed only over something activatable — an axis caption is not.
 * - `ESCAPE` is consumed only when there was a focus or a selection to clear.
 *
 * The order is the accessibility tree's, so a keyboard reader and a screen reader cannot disagree
 * about what is in the chart or what order it is in.
 */
class KeyboardTraversalTest {

  private val controller = VegaChartController()

  /** Four bars, each its own focusable mark, plus the axes the tree also exposes. */
  private val bars =
    """
    {
      "width": 200, "height": 100, "padding": 5, "autosize": "none",
      "data": [{"name": "t", "values": [
        {"c": "a", "v": 3}, {"c": "b", "v": 7}, {"c": "c", "v": 5}, {"c": "d", "v": 9}]}],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
         "range": "width", "padding": 0.1},
        {"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"},
         "range": "height", "nice": true}
      ],
      "marks": [{
        "type": "rect", "from": {"data": "t"},
        "encode": {"enter": {
          "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
          "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
          "fill": {"value": "#4c78a8"},
          "description": {"signal": "datum.c + ': ' + datum.v"}
        }}
      }]
    }
    """
      .trimIndent()

  private fun focused() = controller.state.value.snapshot.interactionState.focusedNodeId

  private fun press(key: ChartKey) = controller.handleKey(key)

  /** Arrows walk the marks, one at a time, in the accessibility tree's order. */
  @Test
  fun `an arrow moves focus from mark to mark`() {
    controller.setSpec(bars)
    assertNull(focused(), "something was focused before any key arrived")

    assertTrue(press(ChartKey.ARROW_RIGHT), "the first arrow was not consumed")
    val first = focused()
    assertNotNull(first, "the first arrow moved no focus")

    assertTrue(press(ChartKey.ARROW_RIGHT), "the second arrow was not consumed")
    val second = focused()
    assertNotNull(second)
    assertTrue(first != second, "the second arrow left focus where it was")

    // And back.
    assertTrue(press(ChartKey.ARROW_LEFT))
    assertEquals(first, focused(), "going back did not return to the previous element")
  }

  /** `HOME` and `END` reach the ends, which is what makes a long chart usable at all. */
  @Test
  fun `home and end reach the first and last elements`() {
    controller.setSpec(bars)
    assertTrue(press(ChartKey.END))
    val last = focused()
    assertNotNull(last)
    assertTrue(press(ChartKey.HOME))
    val first = focused()
    assertNotNull(first)
    assertTrue(first != last, "home and end reached the same element")
    // Pressing HOME again changes nothing, so it is not consumed.
    assertFalse(press(ChartKey.HOME), "HOME was consumed when focus was already at the start")
  }

  // ---- the focus trap, which is the reason this took so long to arrive -------------------------

  /**
   * TAB is never consumed. In any state, focused or not, first element or last.
   *
   * The single most important assertion here: TAB is how a reader leaves, and a chart that eats it
   * is a chart they cannot get out of.
   */
  @Test
  fun `TAB is never consumed`() {
    controller.setSpec(bars)
    assertFalse(press(ChartKey.TAB), "TAB was consumed with nothing focused")
    press(ChartKey.ARROW_RIGHT)
    assertFalse(press(ChartKey.TAB), "TAB was consumed with a mark focused")
    press(ChartKey.END)
    assertFalse(press(ChartKey.TAB), "TAB was consumed at the last element")
  }

  /**
   * An arrow at the end of the order is **not** consumed, so a d-pad carries on out of the chart.
   *
   * The television case the old row named. Pressing right past the last mark has to leave, exactly
   * as it would leave any other widget; consuming it there is the same trap as eating TAB.
   */
  @Test
  fun `an arrow past the last element is not consumed`() {
    controller.setSpec(bars)
    press(ChartKey.END)
    val last = focused()
    assertFalse(
      press(ChartKey.ARROW_RIGHT),
      "an arrow past the last element was consumed, so focus cannot leave the chart",
    )
    assertEquals(last, focused(), "the refused arrow moved focus anyway")

    press(ChartKey.HOME)
    val first = focused()
    assertFalse(
      press(ChartKey.ARROW_LEFT),
      "an arrow before the first element was consumed",
    )
    assertEquals(first, focused())
  }

  /** `ESCAPE` clears, and is not consumed when there is nothing to clear. */
  @Test
  fun `escape clears the focus once and then declines`() {
    controller.setSpec(bars)
    assertFalse(press(ChartKey.ESCAPE), "ESCAPE was consumed with nothing focused")
    press(ChartKey.ARROW_RIGHT)
    assertTrue(press(ChartKey.ESCAPE), "ESCAPE did not clear a focus")
    assertNull(focused(), "ESCAPE left the focus where it was")
    assertFalse(press(ChartKey.ESCAPE), "a second ESCAPE was consumed with nothing left to clear")
  }

  // ---- activation ------------------------------------------------------------------------------

  /**
   * `ENTER` on a focused mark leaves the chart exactly as a tap on it would.
   *
   * The same path and the same events on purpose: a specification that reacts to a click has to
   * react to both halves of its audience, and a reader who activates a bar must end up with the
   * same selection as one who taps it.
   */
  @Test
  fun `enter selects the focused mark, the same way a tap does`() {
    controller.setSpec(bars)
    press(ChartKey.ARROW_RIGHT)
    val target = focused()
    assertTrue(press(ChartKey.ENTER), "ENTER over a mark was not consumed")

    val byKey = controller.state.value.snapshot.interactionState.selection
    assertEquals(setOf(target), byKey.nodeIds, "ENTER selected something other than the focus")

    // The same node, reached by tapping its middle.
    val fresh = VegaChartController()
    fresh.setSpec(bars)
    val bounds =
      fresh.state.value.snapshot.scene
        .let { scene -> scene.flatten() }
        .first { it.node.id == target }
        .node
        .bounds
    fresh.dispatch(ChartInputEvent.Tap(PointD(bounds.centerX, bounds.centerY)))
    assertEquals(
      byKey.nodeIds,
      fresh.state.value.snapshot.interactionState.selection.nodeIds,
      "activating by keyboard and by tap left different selections",
    )
  }

  /**
   * A key moves focus and a selection, and touches nothing else — no hover, no viewport.
   *
   * Inherited from `KeyboardTraversalLimitTest`, which is gone: every limitation it held is closed,
   * traversal by this class and `keydown` handlers by `KeydownHandlerTest`. This is the part of it
   * that was a guard rather than a claim — a key that panned the chart or changed what is hovered
   * would surprise a reader who pressed an arrow.
   */
  @Test
  fun `a key leaves the hover and the viewport alone`() {
    controller.setSpec(bars)
    val before = controller.state.value.snapshot.interactionState
    for (key in ChartKey.entries) controller.dispatch(ChartInputEvent.Key(key))
    val after = controller.state.value.snapshot.interactionState
    assertEquals(before.hoveredNodeId, after.hoveredNodeId, "a key changed what is hovered")
    assertEquals(before.viewportOffset, after.viewportOffset, "a key panned the chart")
    assertEquals(before.viewportScale, after.viewportScale, "a key zoomed the chart")
    assertEquals(before.tooltip, after.tooltip, "a key changed the tooltip")
  }

  /** A chart with nothing to focus declines every key rather than pretending. */
  @Test
  fun `a chart with no focusable elements consumes nothing`() {
    controller.setSpec(
      """
      {"width": 60, "height": 40, "padding": 0, "autosize": "none",
       "data": [{"name": "t", "values": [{"v": 1}]}],
       "marks": []}
      """
        .trimIndent()
    )
    for (key in ChartKey.entries) {
      assertFalse(press(key), "$key was consumed by a chart with nothing in it")
    }
  }
}
