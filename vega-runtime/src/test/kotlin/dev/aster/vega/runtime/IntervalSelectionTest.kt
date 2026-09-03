package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.PointD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Dragging out an interval, which is what every brush in Vega's gallery is built from.
 *
 * `SUPPORTED_FEATURES.md` said "no drag-to-select gesture yet" for long enough that the claim
 * outlived the code. It is a one-line row left from the original roadmap, sitting above the
 * detailed rows that describe the dispatch, the event-selector language and the signal handlers —
 * all three of which are what a brush is made of, and all three of which have been `Supported` for
 * some time. Nothing failed when the claim stopped being true, because nothing was checking it.
 *
 * So this is the check. Not the *shape* of a brush — there is no `interval` primitive here, and
 * upstream has none either; a brush in Vega is a rect mark whose corners are signals — but the
 * gesture that drives one, end to end through the controller: press, move, release.
 */
class IntervalSelectionTest {

  private val controller = VegaChartController()

  /**
   * Vega's own brush idiom, reduced to what it needs.
   *
   * `down` remembers where the drag started and clears on release; `xcur` follows the pointer while
   * `down` holds; `extent` is the pair a brush rect would be drawn from. Written the way a
   * specification writes it — `x()` rather than `event.x`, which is the chart's own space — because
   * that is the path being checked.
   */
  private val json =
    """
    {
      "width": 200, "height": 100, "padding": 0, "autosize": "none",
      "data": [{"name": "t", "values": [{"c": "a", "v": 3}, {"c": "b", "v": 7}]}],
      "signals": [
        {"name": "down", "value": null,
         "on": [{"events": "mousedown", "update": "x()"},
                {"events": "mouseup", "update": "null"}]},
        {"name": "xcur", "value": null,
         "on": [{"events": "mousemove", "update": "down != null ? x() : xcur"}]},
        {"name": "extent", "value": null,
         "on": [{"events": {"signal": "xcur"},
                 "update": "down != null && xcur != null ? [min(down, xcur), max(down, xcur)] : extent"}]}
      ],
      "scales": [{"name": "x", "type": "linear", "domain": [0, 10], "range": "width"}],
      "marks": [{
        "type": "rect", "from": {"data": "t"},
        "encode": {"enter": {"x": {"value": 0}, "width": {"value": 200},
                             "y": {"value": 0}, "height": {"value": 100},
                             "fill": {"value": "#cccccc"}}}
      }]
    }
    """
      .trimIndent()

  private fun signal(name: String): VegaValue? = controller.lastCompiled!!.signals[name]

  private fun press(at: Double) =
    controller.dispatch(
      ChartInputEvent.PointerDown(
        PointD(at, 50.0),
        pointerId = 1,
        device = PointerDevice.MOUSE,
        buttons = 1,
      )
    )

  private fun release(at: Double) =
    controller.dispatch(
      ChartInputEvent.PointerUp(
        PointD(at, 50.0),
        pointerId = 1,
        device = PointerDevice.MOUSE,
        buttons = 0,
      )
    )

  @Test
  fun `a drag produces an interval and releasing ends it`() {
    controller.setSpec(json)
    assertEquals(VegaValue.Null, signal("down"), "a drag was in progress before anything happened")

    press(20.0)
    assertEquals(VegaValue.Num(20.0), signal("down"), "the press did not start a drag")

    controller.dispatch(ChartInputEvent.PointerMoved(PointD(90.0, 50.0)))
    assertEquals(VegaValue.Num(90.0), signal("xcur"))
    // The pair a brush rect is drawn from, in the chart's own space and in order.
    val extent = signal("extent") as VegaValue.Arr
    assertEquals(listOf(20.0, 90.0), extent.values.map { (it as VegaValue.Num).value })

    release(90.0)
    assertEquals(VegaValue.Null, signal("down"), "releasing did not end the drag")
  }

  /**
   * Dragging **backwards** gives the same interval, which is the half a naive brush gets wrong.
   *
   * A reader may drag right-to-left, and an extent of `[90, 20]` is an empty rect rather than a
   * wide one. The `min`/`max` is the specification's own, so what is being checked is that both
   * ends reach the expression — that `x()` is read at press *and* at move.
   */
  @Test
  fun `dragging right to left gives the same interval`() {
    controller.setSpec(json)
    press(90.0)
    controller.dispatch(ChartInputEvent.PointerMoved(PointD(20.0, 50.0)))
    val extent = signal("extent") as VegaValue.Arr
    assertEquals(listOf(20.0, 90.0), extent.values.map { (it as VegaValue.Num).value })
  }

  /**
   * A move with no button down moves nothing, which is what makes it a *drag* rather than a hover.
   *
   * The guard on the two above: if `xcur` followed the pointer whatever the buttons were, they
   * would both pass while every chart brushed on hover.
   */
  @Test
  fun `moving without a press selects nothing`() {
    controller.setSpec(json)
    controller.dispatch(ChartInputEvent.PointerMoved(PointD(90.0, 50.0)))
    assertEquals(VegaValue.Null, signal("xcur"), "the pointer moved a brush with no button down")
    assertEquals(VegaValue.Null, signal("extent"))
  }

  /** No diagnostics: the specification above is ordinary Vega and nothing in it is approximated. */
  @Test
  fun `a brush specification is compiled without complaint`() {
    controller.setSpec(json)
    press(20.0)
    controller.dispatch(ChartInputEvent.PointerMoved(PointD(90.0, 50.0)))
    assertTrue(
      controller.state.value.diagnostics.isEmpty(),
      controller.state.value.diagnostics.map { it.message }.toString(),
    )
  }
}
