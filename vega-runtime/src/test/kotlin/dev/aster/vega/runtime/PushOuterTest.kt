@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.runtime

import dev.aster.vega.loader.FileDataLoader
import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.PointD
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `push: "outer"` — a signal declared in a group that writes the **enclosing** scope's signal.
 *
 * The last of three, and the one that makes Vega's `overview-plus-detail` work end to end: brushing
 * the overview moves the detail panel. The two before it gave a group's scope somewhere to live and
 * a way to fire; this is how a value gets back out of one.
 *
 * The definition is not a signal of the group's at all — it names the outer one — so it is excluded
 * from the group's own resolution, and a handler on it reads the group's scope while writing
 * outward. Both halves matter and both are asserted: reading outward would evaluate
 * `invert('xOverview', brush)` against a scale and a signal that are not there, and writing inward
 * would leave the outer signal untouched, which is exactly what happened before.
 */
class PushOuterTest {

  private fun controller(): VegaChartController {
    val root = File(System.getProperty("user.dir")).parentFile
    return VegaChartController(loader = FileDataLoader(File(root, "test-fixtures")))
  }

  private fun overviewPlusDetail(): String {
    val root = File(System.getProperty("user.dir")).parentFile
    return File(root, "test-fixtures/specs/overview-plus-detail.vg.json").readText()
  }

  private fun VegaChartController.press(x: Double, y: Double) =
    dispatch(
      ChartInputEvent.PointerDown(
        PointD(x, y),
        pointerId = 1,
        device = PointerDevice.MOUSE,
        buttons = 1,
      )
    )

  /**
   * The full chain, in one press: a group handler fires, the cascade crosses the scope boundary,
   * and the value lands on the chart's signal.
   *
   * Shaped after `overview-plus-detail` — a group brush, a scale of the group's own, a pushed
   * domain sourced on `{"signal": ...}` — but driven by a **view** event, for the reason the next
   * test records. What it exercises is all three pieces at once: the handler fires in the group,
   * `brushed` changing puts `outerDomain` on the cascade's frontier under its qualified name, and
   * the push writes outward instead of into the group.
   */
  @Test
  fun `a group brush cascades across the boundary onto the chart's own signal`() {
    val controller = controller()
    controller.setSpec(
      """
      {
        "width": 200, "height": 100, "padding": 0, "autosize": "none",
        "signals": [{"name": "outerDomain", "value": null}],
        "data": [{"name": "t", "values": [{"v": 0}, {"v": 10}]}],
        "marks": [{
          "type": "group", "name": "ov",
          "scales": [{"name": "xOv", "type": "linear", "domain": [0, 10], "range": [0, 200]}],
          "signals": [
            {"name": "brushed", "value": null,
             "on": [{"events": "@band:pointerdown", "update": "[20, 120]"}]},
            {"name": "outerDomain", "push": "outer",
             "on": [{"events": {"signal": "brushed"},
                     "update": "brushed ? invert('xOv', brushed) : null"}]}
          ],
          "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                               "width": {"value": 200}, "height": {"value": 100}}},
          "marks": [{"type": "rect", "name": "band", "from": {"data": "t"},
                     "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                          "width": {"value": 200}, "height": {"value": 100}}}}]
        }]
      }
      """
        .trimIndent()
    )
    assertEquals(VegaValue.Null, controller.lastCompiled!!.signals.values["outerDomain"])

    controller.press(20.0, 20.0)

    val pushed = controller.lastCompiled!!.signals.values["outerDomain"]
    assertTrue(
      pushed is VegaValue.Arr && pushed.values.size == 2,
      "the chart's outerDomain is $pushed; either the group handler did not fire, the cascade did " +
        "not cross the scope boundary, or the push did not write outward",
    )
    // `invert` on the group's own scale: 20 and 120 over a [0,10] domain across 200 units.
    val ends = (pushed as VegaValue.Arr).values.map { (it as VegaValue.Num).value }
    assertEquals(1.0, ends[0], 1e-9)
    assertEquals(6.0, ends[1], 1e-9)
  }

  /**
   * On Vega's own fixture the push is wired and the **drag** is what stops, for an older reason.
   *
   * Worth pinning exactly, because it would otherwise read as this change not working. Pressing the
   * overview sets `brush` to a degenerate pair, `[x(), x()]`, and every handler that widens it —
   * the drag and the pan alike — is sourced on `window:pointermove`. This engine draws on a canvas
   * rather than in a page, so nothing dispatches a window event and it says so. `span(brush)` is
   * therefore zero and `detailDomain` is correctly null.
   *
   * So `overview-plus-detail` brushes as far as this engine can take it, and the remaining step is
   * the window stream rather than anything about group scopes.
   */
  @Test
  fun `the overview fixture brushes as far as the window-stream limit allows`() {
    val controller = controller()
    controller.setSpec(overviewPlusDetail())
    controller.press(120.0, 460.0)

    // The press reached the group's own signal, which is what the two changes before this bought.
    val brush = controller.lastCompiled!!.groupScopes["overview"]?.values?.get("brush")
    assertTrue(
      brush is VegaValue.Arr && brush.values.size == 2,
      "pressing the overview did not set the group's brush: $brush",
    )
    // Degenerate, because widening it needs a drag.
    val pair = (brush as VegaValue.Arr).values.map { (it as VegaValue.Num).value }
    assertEquals(
      pair[0],
      pair[1],
      "the brush spans, so a drag arrived and this test is out of date",
    )

    // And the engine says why the drag cannot.
    val reported = controller.state.value.diagnostics.map { it.message }
    assertTrue(
      reported.any { "window:" in it && "nothing dispatches" in it },
      "the window streams that widen the brush were dropped without a word: $reported",
    )
  }

  /**
   * And the group does **not** get a shadow copy of the pushed signal.
   *
   * The failure the exclusion prevents: resolving the definition inside the group would make a
   * fresh local `detailDomain`, the group's own marks would read the shadow, and the outer signal
   * would sit at its declared value however hard the overview was brushed — which is what used to
   * happen, and what the old parse-time warning described.
   */
  @Test
  fun `a pushed signal is not declared inside the group`() {
    val controller = controller()
    controller.setSpec(overviewPlusDetail())
    val overview = controller.lastCompiled!!.groupScopes["overview"]
    assertNotNull(overview, "the overview scope was not recorded")
    // The group's four own signals are there.
    for (name in listOf("brush", "anchor", "xdown", "delta")) {
      assertTrue(name in overview!!.values, "'$name' is missing from the overview scope")
    }
    // `detailDomain` reads through to the outer one rather than being declared here. It resolves to
    // the *same value*, which is what "reads through" means; what must not happen is a separate
    // entry that a later push leaves stale.
    assertEquals(
      controller.lastCompiled!!.signals.values["detailDomain"],
      overview!!.values["detailDomain"],
      "the group holds a detailDomain of its own, so the outer one is shadowed",
    )
  }

  /** The reading half, in isolation: a pushed handler sees the group's scope, not the chart's. */
  @Test
  fun `a pushed handler reads the group's own scope and writes the chart's`() {
    val controller = controller()
    controller.setSpec(
      """
      {
        "width": 200, "height": 100, "padding": 0, "autosize": "none",
        "signals": [{"name": "answer", "value": 0}, {"name": "base", "value": 100}],
        "data": [{"name": "t", "values": [{"v": 1}]}],
        "marks": [{
          "type": "group", "name": "inner",
          "signals": [
            {"name": "base", "value": 7},
            {"name": "answer", "push": "outer",
             "on": [{"events": "@box:pointerdown", "update": "base"}]}
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
    controller.press(20.0, 20.0)
    // 7, the group's own `base` — not 100, the chart's. That is the reading half.
    assertEquals(
      VegaValue.Num(7.0),
      controller.lastCompiled!!.signals.values["answer"],
      "a pushed handler either read the chart's scope or wrote the group's",
    )
    // And nothing was left behind in the group.
    assertEquals(
      VegaValue.Num(7.0),
      controller.lastCompiled!!.groupScopes["inner"]?.values?.get("answer"),
      "the group's view of the pushed signal disagrees with the chart's",
    )
  }

  /**
   * Without `push`, the same specification writes the group's signal and leaves the chart's alone.
   *
   * The guard: every assertion above would pass equally well for an engine that had started writing
   * outward from *every* group handler, which would be a worse bug than the one being fixed.
   */
  @Test
  fun `the same handler without push writes the group's signal only`() {
    val controller = controller()
    controller.setSpec(
      """
      {
        "width": 200, "height": 100, "padding": 0, "autosize": "none",
        "signals": [{"name": "answer", "value": 0}, {"name": "base", "value": 100}],
        "data": [{"name": "t", "values": [{"v": 1}]}],
        "marks": [{
          "type": "group", "name": "inner",
          "signals": [
            {"name": "base", "value": 7},
            {"name": "answer", "value": 0,
             "on": [{"events": "@box:pointerdown", "update": "base"}]}
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
    controller.press(20.0, 20.0)
    assertEquals(
      VegaValue.Num(7.0),
      controller.lastCompiled!!.groupScopes["inner"]?.values?.get("answer"),
      "the group's own handler did not fire",
    )
    assertEquals(
      VegaValue.Num(0.0),
      controller.lastCompiled!!.signals.values["answer"],
      "a handler with no 'push' wrote the chart's signal anyway",
    )
  }

  /** A `push` value that is not `outer` is reported and treated as an ordinary declaration. */
  @Test
  fun `a push value nobody defines is reported rather than guessed at`() {
    val controller = controller()
    controller.setSpec(
      """
      {
        "width": 100, "height": 60, "padding": 0, "autosize": "none",
        "signals": [{"name": "answer", "value": 0}],
        "data": [{"name": "t", "values": [{"v": 1}]}],
        "marks": [{
          "type": "group", "name": "inner",
          "signals": [{"name": "answer", "push": "sideways", "value": 3}],
          "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                               "width": {"value": 100}, "height": {"value": 60}}},
          "marks": []
        }]
      }
      """
        .trimIndent()
    )
    val reported = controller.state.value.diagnostics.map { it.message }
    assertTrue(
      reported.any { "sideways" in it && "outer" in it },
      "an undefined push value was taken as outer without a word: $reported",
    )
  }
}
