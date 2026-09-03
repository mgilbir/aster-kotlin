package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.PointD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `{"events": {"scale": "y"}}` — a handler fired by a scale being rebuilt.
 *
 * The last of the three source kinds, and the one that was refused rather than implemented. The
 * argument for refusing it was real: upstream knows which scale was rebuilt because a scale is an
 * operator in its dataflow, where a changed signal here recompiles the whole specification and
 * rebuilds *every* scale. Firing on all of them would run the handler when nothing about the scale
 * had changed, and a handler that fires spuriously is worse than one that never fires.
 *
 * What closes it is answering "rebuilt" as "resolves differently" — comparing how the scale maps a
 * fixed set of probes against how the last compile's did. So the interesting tests here are not
 * that it fires, but the two either side of that: it does **not** fire when a recompile leaves the
 * scale where it was, and it does not fire at initialization.
 */
class ScaleSourcedHandlerTest {

  private val controller = VegaChartController()

  /**
   * A chart whose `y` domain follows a signal, and whose `x` domain does not.
   *
   * Both halves are needed. `top` moving is what makes `y` resolve differently, and `picked` moving
   * is what makes a recompile happen with every scale rebuilt and none of them moved — which is the
   * case a naive implementation gets wrong.
   */
  private val json =
    """
    {
      "width": 200, "height": 100, "padding": 0,
      "data": [{"name": "t", "values": [
        {"c": "a", "v": 3}, {"c": "b", "v": 7}, {"c": "c", "v": 5}
      ]}],
      "signals": [
        {"name": "top", "value": 10,
         "on": [{"events": "rect:click", "update": "50"}]},
        {"name": "picked", "value": null,
         "on": [{"events": "mousedown", "update": "'hit'"}]},
        {"name": "rebuilds", "value": 0,
         "on": [{"events": {"scale": "y"}, "update": "rebuilds + 1"}]}
      ],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
         "range": "width", "padding": 0},
        {"name": "y", "type": "linear", "domain": [0, {"signal": "top"}],
         "range": "height"}
      ],
      "marks": [{
        "type": "rect", "from": {"data": "t"},
        "encode": {"enter": {
          "x": {"scale": "x", "field": "c"},
          "width": {"scale": "x", "band": 1},
          "y": {"value": 0},
          "height": {"value": 100},
          "fill": {"signal": "picked === null ? '#4c78a8' : '#e45756'"}
        }}
      }]
    }
    """
      .trimIndent()

  private fun signal(name: String): VegaValue? = controller.lastCompiled!!.signals[name]

  private val onABar = PointD(20.0, 50.0)

  @Test
  fun `nothing fires at initialization`() {
    controller.setSpec(json)
    // There is no previous compile to have moved away from, and upstream's initial run fires no
    // handlers either. A chart that counted one rebuild before anything happened would be wrong in
    // the direction nobody notices — the count is only ever read as a delta.
    assertEquals(VegaValue.Num(0.0), signal("rebuilds"))
  }

  @Test
  fun `a handler fires when the scale it watches resolves differently`() {
    controller.setSpec(json)
    controller.dispatch(ChartInputEvent.Tap(onABar))
    // The tap set `top` to 50, which is the `y` domain, so `y` maps everything somewhere new.
    assertEquals(VegaValue.Num(50.0), signal("top"))
    assertEquals(VegaValue.Num(1.0), signal("rebuilds"))
  }

  /**
   * The half the refusal was about: a recompile that rebuilds every scale and moves none of them.
   *
   * `mousedown` sets `picked`, which reaches a fill and nothing else. Every scale object in the new
   * compile is a *different object* from the one before it — the whole specification was rebuilt —
   * so an implementation comparing identity, or simply firing on every recompile, passes the test
   * above and fails this one.
   */
  @Test
  fun `a recompile that leaves the scale where it was fires nothing`() {
    controller.setSpec(json)
    controller.dispatch(
      ChartInputEvent.PointerDown(onABar, pointerId = 1, device = PointerDevice.TOUCH, buttons = 1)
    )
    assertEquals(VegaValue.Str("hit"), signal("picked"))
    assertEquals(VegaValue.Num(0.0), signal("rebuilds"))
  }

  /**
   * A scale that only *some* changes move, which is what makes the comparison per-scale.
   *
   * Watching `x` instead of `y` and then moving `y`'s domain: both scales are rebuilt, one of them
   * differently, and the handler is sourced on the other. An implementation that fired when *any*
   * watched-or-not scale moved would fire here.
   */
  @Test
  fun `a handler watching one scale ignores another one moving`() {
    controller.setSpec(json.replace("""{"scale": "y"}""", """{"scale": "x"}"""))
    controller.dispatch(ChartInputEvent.Tap(onABar))
    assertEquals(VegaValue.Num(50.0), signal("top"))
    assertEquals(VegaValue.Num(0.0), signal("rebuilds"))
  }

  /**
   * A scale-sourced handler that moves the scale it watches is a cycle, and is stopped.
   *
   * `rebuilds` drives `y`'s domain and fires on `y`, so every round moves the scale and fires
   * again. Upstream refuses such a specification outright; this reports it and keeps the last
   * values, which is the same choice the signal cascade makes — a chart that draws with one signal
   * stuck beats a chart that does not draw.
   */
  @Test
  fun `a scale and a signal moving each other is reported and stopped`() {
    controller.setSpec(
      json
        .replace("""[0, {"signal": "top"}]""", """[0, {"signal": "top + rebuilds"}]""")
        .replace(""""update": "rebuilds + 1"""", """"update": "rebuilds + 1"""")
    )
    controller.dispatch(ChartInputEvent.Tap(onABar))
    val reported = controller.state.value.diagnostics.map { it.message }
    assertTrue(
      reported.any { it.contains("moving each other") },
      "expected a cycle report, got $reported",
    )
    // And it drew: the run stopped, it did not abandon the chart.
    assertTrue(controller.state.value.snapshot.scene.root.children.isNotEmpty())
  }

  /**
   * A specification with no scale source pays nothing, which is checked by it still working.
   *
   * The guard that skips fingerprinting entirely is on the majority path — not one of Vega's own
   * ninety-three published examples uses a scale source — so it is the path that must not break.
   * The same handler is given an ordinary event source instead, so the counter still moves and this
   * says the guard changed nothing rather than merely that nothing happened.
   */
  @Test
  fun `a specification with no scale-sourced handler is unaffected`() {
    controller.setSpec(json.replace("""{"scale": "y"}""", """"rect:click""""))
    controller.dispatch(ChartInputEvent.Tap(onABar))
    assertEquals(VegaValue.Num(50.0), signal("top"))
    assertEquals(VegaValue.Num(1.0), signal("rebuilds"))
  }
}
