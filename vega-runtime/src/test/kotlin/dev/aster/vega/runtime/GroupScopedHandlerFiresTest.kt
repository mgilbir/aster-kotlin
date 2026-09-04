@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.runtime

import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.PointD
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A signal handler declared inside a group mark fires, which it never did.
 *
 * The second half of the change begun by `GroupScopeIsRecordedTest`. Two things stopped it: nothing
 * built a binding for a handler outside the top level, and a group's signals were resolved with no
 * pinned values, so a value a handler set had nowhere to live across the recompile that firing
 * triggers. Both are closed here.
 *
 * The specification under test is Vega's own `overview-plus-detail` wherever it can be, because a
 * fixture in the corpus is worth more than a shape invented to pass.
 */
class GroupScopedHandlerFiresTest {

  private val controller = VegaChartController()

  private fun overviewPlusDetail() =
    File("../test-fixtures/specs/overview-plus-detail.vg.json").readText()

  private fun press(x: Double, y: Double) =
    controller.dispatch(
      ChartInputEvent.PointerDown(
        PointD(x, y),
        pointerId = 1,
        device = PointerDevice.MOUSE,
        buttons = 1,
      )
    )

  private fun groupSignal(path: String, name: String): VegaValue? =
    controller.lastCompiled?.groupScopes?.get(path)?.values?.get(name)

  /**
   * The overview group's `brush` moves when the overview is pressed.
   *
   * `{"events": "@overview:pointerdown", "update": "[x(), x()]"}` — the first of the five, and the
   * one everything else in that group hangs off. It stayed at its declared `0` however hard the
   * chart was pressed.
   */
  @Test
  fun `pressing the overview sets the group's own brush`() {
    controller.setSpec(overviewPlusDetail())
    assertEquals(
      VegaValue.Num(0.0),
      groupSignal("overview", "brush"),
      "brush did not start at its declared value",
    )

    // Inside the overview group: it is declared at y 430 with a height of 70, under the
    // 390-tall detail plot, and the chart carries a padding of 5.
    val overview = controller.lastCompiled!!.groupScopes["overview"]
    assertNotNull(overview, "the overview scope was not recorded")
    press(120.0, 460.0)

    val brush = groupSignal("overview", "brush")
    assertTrue(
      brush is VegaValue.Arr,
      "pressing the overview left brush as $brush; a handler declared in a group still does not fire",
    )
  }

  /**
   * And the value **survives the recompile**, which is the half that pinning fixes.
   *
   * Every change recompiles the whole specification here, so a value with nowhere to be pinned
   * lasts exactly until the next compile. A second press is what makes that visible: without
   * pinning the first one is already gone.
   */
  @Test
  fun `a group signal keeps its value across the recompile a later event triggers`() {
    controller.setSpec(overviewPlusDetail())
    press(120.0, 460.0)
    val first = groupSignal("overview", "brush")
    assertTrue(first is VegaValue.Arr, "the first press set nothing")

    // `xdown` is set by a *different* stream, so this recompiles without touching `brush`.
    controller.dispatch(ChartInputEvent.PointerMoved(PointD(150.0, 460.0)))
    assertEquals(
      first,
      groupSignal("overview", "brush"),
      "brush was recomputed from its declared value by the next compile, so nothing is pinned",
    )
  }

  /**
   * A group's signal and a same-named top-level one stay separate.
   *
   * The failure a single flat override map would produce, and the reason the two stores are
   * separate rather than one keyed by a qualified name: upstream gives each scope its own.
   */
  @Test
  fun `a group's signal does not write the chart's signal of the same name`() {
    controller.setSpec(
      """
      {
        "width": 200, "height": 100, "padding": 0, "autosize": "none",
        "signals": [{"name": "picked", "value": "outer"}],
        "data": [{"name": "t", "values": [{"v": 1}]}],
        "marks": [{
          "type": "group", "name": "inner",
          "signals": [{"name": "picked", "value": "inner",
                       "on": [{"events": "@box:pointerdown", "update": "'changed'"}]}],
          "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                               "width": {"value": 200}, "height": {"value": 100}}},
          "marks": [{"type": "rect", "name": "box", "from": {"data": "t"},
                     "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                          "width": {"value": 50}, "height": {"value": 50},
                                          "fill": {"value": "#cccccc"}}}}]
        }]
      }
      """
        .trimIndent()
    )
    press(20.0, 20.0)
    assertEquals(
      VegaValue.Str("changed"),
      groupSignal("inner", "picked"),
      "the group's own handler did not fire",
    )
    assertEquals(
      VegaValue.Str("outer"),
      controller.lastCompiled!!.signals.values["picked"],
      "a handler inside a group wrote the chart's signal of the same name",
    )
  }

  /**
   * A handler in a group reads the group's **own** signals, not the chart's.
   *
   * The other direction of the same separation, and the one that decides whether the update
   * expression is evaluated in the right place at all.
   */
  @Test
  fun `a group handler's update reads the group's own scope`() {
    controller.setSpec(
      """
      {
        "width": 200, "height": 100, "padding": 0, "autosize": "none",
        "signals": [{"name": "base", "value": 100}],
        "data": [{"name": "t", "values": [{"v": 1}]}],
        "marks": [{
          "type": "group", "name": "inner",
          "signals": [
            {"name": "base", "value": 5},
            {"name": "seen", "value": 0, "on": [{"events": "@box:pointerdown", "update": "base"}]}
          ],
          "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                               "width": {"value": 200}, "height": {"value": 100}}},
          "marks": [{"type": "rect", "name": "box", "from": {"data": "t"},
                     "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                          "width": {"value": 50}, "height": {"value": 50}}}}]
        }]
      }
      """
        .trimIndent()
    )
    press(20.0, 20.0)
    assertEquals(
      VegaValue.Num(5.0),
      groupSignal("inner", "seen"),
      "the handler read the chart's 'base' of 100 instead of its group's 5",
    )
  }

  /**
   * A top-level handler is untouched, which is what says nothing was traded for this.
   *
   * The guard on all of the above: every one would pass equally well for a controller that had
   * started routing *every* handler into a group store.
   */
  @Test
  fun `a top-level handler still fires and still writes the top-level signal`() {
    controller.setSpec(
      """
      {
        "width": 120, "height": 60, "padding": 0, "autosize": "none",
        "data": [{"name": "t", "values": [{"v": 1}]}],
        "signals": [{"name": "taps", "value": 0,
                     "on": [{"events": "pointerdown", "update": "taps + 1"}]}],
        "marks": [{"type": "rect", "from": {"data": "t"},
                   "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                        "width": {"value": 50}, "height": {"value": 50}}}}]
      }
      """
        .trimIndent()
    )
    press(20.0, 20.0)
    assertEquals(VegaValue.Num(1.0), controller.lastCompiled!!.signals.values["taps"])
    press(20.0, 20.0)
    assertEquals(VegaValue.Num(2.0), controller.lastCompiled!!.signals.values["taps"])
  }
}
