@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.runtime

import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.PointD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A handler declared in a group and listening to its **whole group** fires, which it did not.
 *
 * `{"events": "mousedown"}` inside a group mark is not the same selector as at the top level: the
 * parser gives it source `scope`, because upstream attaches the listener to that group's own item.
 * It means "a mousedown anywhere in this group".
 *
 * Answering that needs the group's rectangle, and the dispatcher has neither the scene nor the
 * point in world space — so these were refused by name, with a diagnostic telling the author to
 * write `@markname:mousedown` instead. The controller answers it now and hands the groups the event
 * landed in over on the event itself.
 *
 * **The lookup is by node id, not by name.** `ScopeCompiler` records the node each cell was drawn
 * as, beside the path it recorded the scope under. Walking the scene and matching mark names would
 * have to tell a group mark from the group nodes an axis and a legend also produce, and pairing
 * those by shape is the inference that has been wrong every time it has been tried in this
 * codebase.
 *
 * **And "in" is the hit item's ancestry**, which is upstream's `inScope(event.item)` rather than
 * containment of the group's rectangle. Every spec here fills its groups with a mark, so the two
 * readings agree throughout this class; `FacetedScopeHandlerTest` is where they come apart.
 */
class ScopeSourcedHandlerTest {

  private val controller = VegaChartController()

  /** Two side-by-side groups, each with a bare `scope` handler of its own. */
  private val twoGroups =
    """
    {
      "width": 200, "height": 100, "padding": 0, "autosize": "none",
      "data": [{"name": "t", "values": [{"v": 1}]}],
      "marks": [
        {
          "type": "group", "name": "left",
          "signals": [{"name": "hits", "value": 0,
                       "on": [{"events": "mousedown", "update": "hits + 1"}]}],
          "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                               "width": {"value": 100}, "height": {"value": 100}}},
          "marks": [{"type": "rect", "from": {"data": "t"},
                     "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                          "width": {"value": 100}, "height": {"value": 100},
                                          "fill": {"value": "#cccccc"}}}}]
        },
        {
          "type": "group", "name": "right",
          "signals": [{"name": "hits", "value": 0,
                       "on": [{"events": "mousedown", "update": "hits + 1"}]}],
          "encode": {"enter": {"x": {"value": 100}, "y": {"value": 0},
                               "width": {"value": 100}, "height": {"value": 100}}},
          "marks": [{"type": "rect", "from": {"data": "t"},
                     "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                          "width": {"value": 100}, "height": {"value": 100},
                                          "fill": {"value": "#999999"}}}}]
        }
      ]
    }
    """
      .trimIndent()

  private fun press(x: Double, y: Double) =
    controller.dispatch(
      ChartInputEvent.PointerDown(
        PointD(x, y),
        pointerId = 1,
        device = PointerDevice.MOUSE,
        buttons = 1,
      )
    )

  private fun hits(group: String) =
    controller.lastCompiled!!.groupScopes[group]?.values?.get("hits")

  @Test
  fun `a bare scope handler fires for an event inside its own group`() {
    controller.setSpec(twoGroups)
    assertEquals(VegaValue.Num(0.0), hits("left"))

    press(50.0, 50.0)
    assertEquals(VegaValue.Num(1.0), hits("left"), "a bare scope handler did not fire in its group")
  }

  /**
   * And **not** for an event in the group next to it, which is the whole difficulty.
   *
   * Widening a bare scope stream to the view would have made this pass the test above and fail here
   * — a group's handler firing on every event in the chart. That is why they were refused rather
   * than approximated.
   */
  @Test
  fun `it does not fire for an event in a sibling group`() {
    controller.setSpec(twoGroups)
    press(150.0, 50.0)
    assertEquals(
      VegaValue.Num(0.0),
      hits("left"),
      "the left group's handler fired for a press in the right group",
    )
    assertEquals(VegaValue.Num(1.0), hits("right"), "the right group's own handler did not fire")
  }

  /** Nor for an event outside every group. */
  @Test
  fun `it does not fire for an event outside every group`() {
    controller.setSpec(
      """
      {
        "width": 200, "height": 100, "padding": 0, "autosize": "none",
        "data": [{"name": "t", "values": [{"v": 1}]}],
        "marks": [{
          "type": "group", "name": "corner",
          "signals": [{"name": "hits", "value": 0,
                       "on": [{"events": "mousedown", "update": "hits + 1"}]}],
          "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                               "width": {"value": 40}, "height": {"value": 40}}},
          "marks": [{"type": "rect", "from": {"data": "t"},
                     "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                          "width": {"value": 40}, "height": {"value": 40}}}}]
        }]
      }
      """
        .trimIndent()
    )
    press(180.0, 90.0)
    assertEquals(
      VegaValue.Num(0.0),
      hits("corner"),
      "a handler fired for a press outside its group entirely",
    )
    press(20.0, 20.0)
    assertEquals(VegaValue.Num(1.0), hits("corner"), "and then did not fire for one inside it")
  }

  /**
   * A nested group counts its ancestors too: an event in a cell is also in the group holding it.
   *
   * Upstream's listener sits on each group's item and an event bubbles through both, so a handler
   * on the outer group hears what happens in the inner one.
   */
  @Test
  fun `an event inside a nested group is inside its parent as well`() {
    controller.setSpec(
      """
      {
        "width": 200, "height": 100, "padding": 0, "autosize": "none",
        "data": [{"name": "t", "values": [{"v": 1}]}],
        "marks": [{
          "type": "group", "name": "outer",
          "signals": [{"name": "hits", "value": 0,
                       "on": [{"events": "mousedown", "update": "hits + 1"}]}],
          "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                               "width": {"value": 200}, "height": {"value": 100}}},
          "marks": [{
            "type": "group", "name": "inner",
            "signals": [{"name": "hits", "value": 0,
                         "on": [{"events": "mousedown", "update": "hits + 1"}]}],
            "encode": {"enter": {"x": {"value": 10}, "y": {"value": 10},
                                 "width": {"value": 50}, "height": {"value": 50}}},
            "marks": [{"type": "rect", "from": {"data": "t"},
                       "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                            "width": {"value": 50}, "height": {"value": 50}}}}]
          }]
        }]
      }
      """
        .trimIndent()
    )
    press(30.0, 30.0)
    assertEquals(VegaValue.Num(1.0), hits("outer/inner"), "the inner group's handler did not fire")
    assertEquals(
      VegaValue.Num(1.0),
      hits("outer"),
      "the outer group's handler did not hear an event inside the group it contains",
    )
  }

  /** No diagnostic: a bare scope selector is ordinary Vega and is dispatched now. */
  @Test
  fun `a bare scope selector is no longer reported`() {
    controller.setSpec(twoGroups)
    press(50.0, 50.0)
    val reported = controller.state.value.diagnostics.map { it.message }
    assertTrue(
      reported.none { "@markname:" in it },
      "a bare scope selector is still being refused: $reported",
    )
  }

  /** A top-level handler is unaffected, so nothing was traded for this. */
  @Test
  fun `a top-level handler still fires wherever the event lands`() {
    controller.setSpec(
      """
      {
        "width": 200, "height": 100, "padding": 0, "autosize": "none",
        "data": [{"name": "t", "values": [{"v": 1}]}],
        "signals": [{"name": "taps", "value": 0,
                     "on": [{"events": "mousedown", "update": "taps + 1"}]}],
        "marks": [{"type": "rect", "from": {"data": "t"},
                   "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                        "width": {"value": 20}, "height": {"value": 20}}}}]
      }
      """
        .trimIndent()
    )
    press(180.0, 90.0)
    assertEquals(
      VegaValue.Num(1.0),
      controller.lastCompiled!!.signals.values["taps"],
      "a top-level handler stopped firing for an event outside every mark",
    )
  }
}
