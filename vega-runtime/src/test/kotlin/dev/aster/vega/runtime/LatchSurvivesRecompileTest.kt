@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.runtime

import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.PointD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/**
 * A `between` latch survives the recompile the drag's own first event causes.
 *
 * **The first drag of every brush was lost.** `[mousedown, mouseup] > mousemove` is a latch —
 * opened by the first stream, closed by the second — and the dispatcher that holds it is rebuilt on
 * every recompile, with every `Gate` starting closed. A recompile is what a fired handler causes.
 * So in the standard brush idiom, an `anchor` set on `mousedown` beside a `brush` on the gated
 * `mousemove`, the `mousedown` opened the latch, changed `anchor`, recompiled, and threw the latch
 * away before the `mousemove` it was meant to gate ever arrived.
 *
 * **Why nobody saw it.** The failure depends on whether the opening event *changed* anything. A
 * second drag from the same point sets `anchor` to the value it already had, changes no signal,
 * rebuilds nothing, and works perfectly — so a brush that had been dragged once looked fine forever
 * after, and only the first drag from each new point was silently dropped.
 *
 * Upstream never rebuilds its streams at all: a `View`'s dataflow outlives every signal update, so
 * its latches do too. Carrying them across is what matches it — the same lesson `startTimers`
 * already recorded for timers, which had been cancelled mid-flight for exactly this reason and
 * three lines from exactly this code.
 *
 * Found by verifying an audit question about *faceted* interval selections. Those work, and so does
 * `push: "outer"` from a cell; what did not work was every brush in the corpus.
 */
class LatchSurvivesRecompileTest {

  private val controller = VegaChartController()

  /** The plainest brush idiom there is: an anchor on the press, an interval on the gated move. */
  private val brushable =
    """
    {
      "width": 200, "height": 100, "padding": 0, "autosize": "none",
      "data": [{"name": "t", "values": [{"v": 1}]}],
      "signals": [
        {"name": "anchor", "value": 0, "on": [{"events": "mousedown", "update": "x()"}]},
        {"name": "brush", "value": [0, 0],
         "on": [{"events": "[mousedown, mouseup] > mousemove",
                 "update": "[min(anchor, x()), max(anchor, x())]"}]}
      ],
      "marks": [{"type": "rect", "from": {"data": "t"},
                 "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                      "width": {"value": 200}, "height": {"value": 100},
                                      "fill": {"value": "#eeeeee"}}}}]
    }
    """
      .trimIndent()

  private fun down(x: Double) =
    controller.dispatch(
      ChartInputEvent.PointerDown(
        PointD(x, 50.0),
        pointerId = 1,
        device = PointerDevice.MOUSE,
        buttons = 1,
      )
    )

  private fun move(x: Double) = controller.dispatch(ChartInputEvent.PointerMoved(PointD(x, 50.0)))

  private fun up(x: Double) =
    controller.dispatch(
      ChartInputEvent.PointerUp(
        PointD(x, 50.0),
        pointerId = 1,
        device = PointerDevice.MOUSE,
        buttons = 0,
      )
    )

  private fun brush() = controller.lastCompiled!!.signals.values["brush"]

  private fun interval(from: Double, to: Double) =
    VegaValue.Arr(listOf(VegaValue.Num(from), VegaValue.Num(to)))

  /** The case that was broken: the very first drag, whose press moves `anchor`. */
  @Test
  fun `the first drag of a brush is not lost`() {
    controller.setSpec(brushable)
    down(20.0)
    move(60.0)
    assertEquals(
      interval(20.0, 60.0),
      brush(),
      "the press opened the latch, changed a signal, recompiled, and the recompile threw the " +
        "latch away before the move it gates arrived",
    )
  }

  /**
   * And a drag starting somewhere **new**, which is the same failure a second time.
   *
   * Worth its own case because the first one can be made to pass by seeding `anchor`: what breaks
   * is any press that *changes* it, not the first press in particular.
   */
  @Test
  fun `a later drag from a new point is not lost either`() {
    controller.setSpec(brushable)
    down(20.0)
    move(60.0)
    up(60.0)
    down(150.0)
    move(170.0)
    assertEquals(interval(150.0, 170.0), brush(), "a drag from a new anchor was dropped")
  }

  /**
   * The drag from the **same** point still works, which is what hid this.
   *
   * Kept as a case of its own so the shape of the old failure stays visible: this one passed
   * throughout, and it is the one a person exploring by hand would try second.
   */
  @Test
  fun `a repeated drag from the same point still works`() {
    controller.setSpec(brushable)
    down(20.0)
    move(60.0)
    up(60.0)
    down(20.0)
    move(80.0)
    assertEquals(interval(20.0, 80.0), brush())
  }

  /** The latch still **closes**, so a drag ends where it is released rather than running on. */
  @Test
  fun `a released drag stops tracking`() {
    controller.setSpec(brushable)
    down(20.0)
    move(60.0)
    up(60.0)
    move(190.0)
    assertEquals(
      interval(20.0, 60.0),
      brush(),
      "the brush kept following the pointer after the release closed the latch",
    )
  }

  /** And an ungated move never fires it, so the latch is doing the gating and not the mark. */
  @Test
  fun `a move with no press does nothing`() {
    controller.setSpec(brushable)
    move(60.0)
    assertEquals(interval(0.0, 0.0), brush(), "a move fired the brush with no press to open it")
  }

  /**
   * A drag does **not** span two documents.
   *
   * The one time carrying a latch would be wrong: the streams it belonged to no longer exist, so a
   * chart replaced mid-drag would come up already brushing.
   */
  @Test
  fun `a new specification does not inherit an open latch`() {
    controller.setSpec(brushable)
    down(20.0)
    controller.setSpec(brushable)
    move(90.0)
    assertEquals(
      interval(0.0, 0.0),
      brush(),
      "a chart loaded mid-drag came up with the previous document's latch open",
    )
  }

  /**
   * A **faceted** brush, per cell, which is the audit question this was found under.
   *
   * Each cell has its own latch, its own anchor and its own brush, and a drag in one leaves the
   * other alone. `push: "outer"` carries the cell's own key back out to the chart, so the two
   * halves of that question are both answered here.
   */
  @Test
  fun `a faceted brush selects in its own cell and pushes outward`() {
    controller.setSpec(
      """
      {
        "width": 200, "height": 100, "padding": 0, "autosize": "none",
        "data": [{"name": "t", "values": [{"c": "a"}, {"c": "b"}]}],
        "scales": [{"name": "cells", "type": "band", "domain": {"data": "t", "field": "c"},
                    "range": "width"}],
        "signals": [{"name": "picked", "value": "none"}],
        "marks": [{
          "type": "group", "name": "cell",
          "from": {"facet": {"name": "rows", "data": "t", "groupby": "c"}},
          "encode": {"enter": {"x": {"scale": "cells", "field": "c"}, "y": {"value": 0},
                               "width": {"scale": "cells", "band": 1}, "height": {"value": 100}}},
          "signals": [
            {"name": "anchor", "value": 0, "on": [{"events": "mousedown", "update": "x()"}]},
            {"name": "brush", "value": [0, 0],
             "on": [{"events": "[mousedown, mouseup] > mousemove",
                     "update": "[min(anchor, x()), max(anchor, x())]"}]},
            {"name": "picked", "push": "outer",
             "on": [{"events": "[mousedown, mouseup] > mousemove", "update": "parent.c"}]}
          ],
          "marks": [{"type": "rect", "from": {"data": "rows"},
                     "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                          "width": {"value": 100}, "height": {"value": 100},
                                          "fill": {"value": "#eeeeee"}}}}]
        }]
      }
      """
        .trimIndent()
    )
    fun cellBrush(cell: Int) =
      controller.lastCompiled!!.groupScopes["cell/cells[$cell]"]?.values?.get("brush")

    down(20.0)
    move(60.0)
    up(60.0)
    assertEquals(interval(20.0, 60.0), cellBrush(0), "the first cell's brush did not follow")
    assertEquals(interval(0.0, 0.0), cellBrush(1), "the drag reached the cell it was not in")
    assertEquals(VegaValue.Str("a"), controller.lastCompiled!!.signals.values["picked"])

    down(120.0)
    move(160.0)
    assertEquals(interval(20.0, 60.0), cellBrush(0), "a drag in the second cell moved the first")
    assertEquals(interval(120.0, 160.0), cellBrush(1), "the second cell's brush did not follow")
    assertNotEquals(
      VegaValue.Str("a"),
      controller.lastCompiled!!.signals.values["picked"],
      "the pushed signal kept the first cell's value",
    )
  }
}
