package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A gesture reaching a specification's `on` handlers, through the controller.
 *
 * This is the layer that was missing: the dispatcher and the updater were both tested in isolation,
 * but nothing turned a tap into the Vega events a selector is written against. The interesting part
 * is that one gesture produces several event names, because that is what a browser does on a touch
 * screen and what almost every specification in the wild is written against.
 */
class ControllerInteractionTest {

  private var now = 0L

  private val controller = VegaChartController(clock = { now })

  private val json =
    """
    {
      "width": 200, "height": 100, "padding": 0,
      "data": [{"name": "t", "values": [
        {"c": "a", "v": 3}, {"c": "b", "v": 7}, {"c": "c", "v": 5}
      ]}],
      "signals": [
        {"name": "picked", "value": null,
         "on": [{"events": "rect:click", "update": "datum.c"}]},
        {"name": "taps", "value": 0,
         "on": [{"events": "mousedown", "update": "taps + 1"}]}
      ],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
         "range": "width", "padding": 0},
        {"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"},
         "range": "height", "zero": true}
      ],
      "marks": [{
        "type": "rect", "from": {"data": "t"},
        "encode": {"enter": {
          "x": {"scale": "x", "field": "c"},
          "width": {"scale": "x", "band": 1},
          "y": {"value": 0},
          "height": {"value": 100},
          "fill": {"signal": "datum.c === picked ? '#e45756' : '#4c78a8'"}
        }}
      }]
    }
    """
      .trimIndent()

  private fun fills(): List<String> {
    val out = mutableListOf<String>()
    fun walk(node: SceneNode) {
      when (node) {
        is RectNode -> out += node.fill.toString()
        is GroupNode -> node.children.forEach { walk(it) }
        else -> Unit
      }
    }
    walk(controller.state.value.snapshot.scene.root)
    return out
  }

  /** The middle band of three across 200px: x from 66.7 to 133.3. */
  private val onSecondBar = PointD(100.0, 50.0)

  @Test
  fun `a tap on a mark fires its handler and republishes the scene`() {
    controller.setSpec(json)
    val before = fills()
    val revisionBefore = controller.state.value.snapshot.revision

    controller.dispatch(ChartInputEvent.Tap(onSecondBar))

    assertEquals(VegaValue.Str("b"), controller.lastCompiled!!.signals["picked"])
    val after = fills()
    assertNotEquals(before, after)
    assertEquals(1, after.indices.count { after[it] != before[it] }, after.toString())
    assertTrue(controller.state.value.snapshot.revision > revisionBefore)
  }

  /**
   * A press produces `pointerdown`, `touchstart` **and** `mousedown`, because a browser on a touch
   * screen fires the touch family and synthesises the mouse family from it. A specification written
   * against `mousedown` — which most are — would otherwise be inert on Android.
   */
  @Test
  fun `one press reaches a handler written against mousedown`() {
    controller.setSpec(json)
    controller.dispatch(
      ChartInputEvent.PointerDown(
        onSecondBar,
        pointerId = 1,
        device = PointerDevice.TOUCH,
        buttons = 1,
      )
    )
    assertEquals(VegaValue.Num(1.0), controller.lastCompiled!!.signals["taps"])
  }

  /** Each press counts once, not once per synthesised name. */
  @Test
  fun `a repeated press accumulates one at a time`() {
    controller.setSpec(json)
    repeat(3) { tick ->
      now = tick.toLong()
      controller.dispatch(
        ChartInputEvent.PointerDown(
          onSecondBar,
          pointerId = 1,
          device = PointerDevice.TOUCH,
          buttons = 1,
        )
      )
    }
    assertEquals(VegaValue.Num(3.0), controller.lastCompiled!!.signals["taps"])
  }

  /** A tap that lands on nothing still fires a view-level handler, but no mark-level one. */
  @Test
  fun `a tap off any mark leaves a mark handler alone`() {
    controller.setSpec(json)
    controller.dispatch(ChartInputEvent.Tap(onSecondBar))
    assertEquals(VegaValue.Str("b"), controller.lastCompiled!!.signals["picked"])

    // Below the bars, which stop at y = 100.
    controller.dispatch(ChartInputEvent.Tap(PointD(100.0, 500.0)))
    // Still "b": nothing set it to anything else, and the handler never fired.
    assertEquals(VegaValue.Str("b"), controller.lastCompiled!!.signals["picked"])
  }

  /** A specification with no handlers must not recompile on every gesture. */
  @Test
  fun `a chart with no handlers does not recompile`() {
    controller.setSpec(json.replace(Regex(""""on": \[[^\]]*\],?"""), ""))
    val revision = controller.state.value.snapshot.revision
    controller.dispatch(ChartInputEvent.Tap(onSecondBar))
    controller.dispatch(ChartInputEvent.PointerMoved(onSecondBar))
    assertEquals(revision, controller.state.value.snapshot.revision)
  }

  /**
   * `config.events` refuses a listener, and the refusal is *told*.
   *
   * The dispatcher has always reported as it registered — a blocked stream, a debounce nothing can
   * schedule — into a collector this controller threw away, so a host asking for the diagnostics
   * saw only the compiler's. A policy whose enforcement is invisible is indistinguishable from one
   * that was ignored, which is the failure this whole block exists to prevent.
   */
  @Test
  fun `a refused listener is reported to the host`() {
    controller.setSpec(
      json.replace(
        """"signals": [""",
        """"config": {"events": {"view": ["click"]}},
           "signals": [""",
      )
    )
    val reported = controller.state.value.diagnostics.map { it.message }
    assertTrue(
      reported.any { it.startsWith("Blocked view mousedown event listener") },
      reported.toString(),
    )
    // And the refusal is narrow: the click handler on the same chart still fires.
    controller.dispatch(ChartInputEvent.Tap(onSecondBar))
    assertEquals(VegaValue.Str("b"), controller.lastCompiled!!.signals["picked"])
    assertEquals(VegaValue.Num(0.0), controller.lastCompiled!!.signals["taps"])
  }

  /** Loading a new specification forgets what the old one's handlers had set. */
  @Test
  fun `setSpec clears the accumulated signal values`() {
    controller.setSpec(json)
    controller.dispatch(ChartInputEvent.Tap(onSecondBar))
    assertEquals(VegaValue.Str("b"), controller.lastCompiled!!.signals["picked"])

    controller.setSpec(json)
    assertEquals(VegaValue.Null, controller.lastCompiled!!.signals["picked"])
  }
}
