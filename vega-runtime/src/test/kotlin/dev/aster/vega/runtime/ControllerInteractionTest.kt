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

  /** Every rect's fill as a hex string, in scene order. */
  private fun rectFills(): List<String> {
    val out = mutableListOf<String>()
    fun walk(node: SceneNode) {
      when (node) {
        is RectNode ->
          out +=
            ((node.fill?.paint as? dev.aster.vega.scene.ScenePaint.Solid)?.color?.toCssHex()
              ?: "none")
        is GroupNode -> node.children.forEach { walk(it) }
        else -> Unit
      }
    }
    walk(controller.state.value.snapshot.scene.root)
    return out
  }

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

  /**
   * A handler whose source is another **signal**, which is how one control drives another.
   *
   * Upstream makes it a dataflow edge: it fires when the source changes and cascades to whatever is
   * sourced on *it*. Probed rather than assumed, in both directions — setting `a` to 5 in a chain
   * two deep left `b` at 10 and `c` at 11, and at **initialization** nothing fires at all, so both
   * keep their declared values. That second half is why no differential fixture can cover this: the
   * scene the harness compares is the one before anything has changed.
   */
  @Test
  fun `a signal-sourced handler fires when its source changes, and cascades`() {
    controller.setSpec(
      json.replace(
        """{"name": "taps", "value": 0,""",
        """{"name": "doubled", "value": 0,
          "on": [{"events": {"signal": "picked"}, "update": "picked + picked"}]},
         {"name": "labelled", "value": "none",
          "on": [{"events": {"signal": "doubled"}, "update": "'is ' + doubled"}]},
         {"name": "taps", "value": 0,""",
      )
    )
    // Nothing has changed yet, so nothing has fired: upstream's initial run leaves both alone.
    assertEquals(VegaValue.Num(0.0), controller.lastCompiled!!.signals["doubled"])
    assertEquals(VegaValue.Str("none"), controller.lastCompiled!!.signals["labelled"])

    controller.dispatch(ChartInputEvent.Tap(onSecondBar))

    // The tap set `picked` to "b"; the chain ran from there in one batch.
    assertEquals(VegaValue.Str("b"), controller.lastCompiled!!.signals["picked"])
    assertEquals(VegaValue.Str("bb"), controller.lastCompiled!!.signals["doubled"])
    assertEquals(VegaValue.Str("is bb"), controller.lastCompiled!!.signals["labelled"])
  }

  /** A cycle would never settle. Upstream refuses the specification; this reports and stops. */
  @Test
  fun `a cycle among signal-driven handlers is reported`() {
    controller.setSpec(
      json.replace(
        """{"name": "taps", "value": 0,""",
        """{"name": "ping", "value": 0,
          "on": [{"events": {"signal": "pong"}, "update": "pong + 1"}]},
         {"name": "pong", "value": 0,
          "on": [{"events": {"signal": "picked"}, "update": "1"},
                 {"events": {"signal": "ping"}, "update": "ping + 1"}]},
         {"name": "taps", "value": 0,""",
      )
    )
    controller.dispatch(ChartInputEvent.Tap(onSecondBar))
    val reported = controller.state.value.diagnostics.map { it.message }
    assertTrue(reported.any { it.contains("are on a cycle") }, reported.toString())
  }

  /**
   * The event functions — `x()`, `y()`, `xy()` and `item()` — which every brush and pan is built
   * on.
   *
   * `x()` is the commonest expression in an interactive specification after `datum`: forty of the
   * uses across Vega's own examples. It is **not** `event.x`: upstream takes the chart's padding
   * and autosize origin off first (`offset(view)`, which is exactly what the root group carries as
   * its translation), so the answer is in the space the marks are placed in. With a padding of ten,
   * a tap at 30 has to read 20 — reading the raw pointer position instead would put every brush out
   * by the padding, and a chart with no padding would have hidden it.
   */
  @Test
  fun `the event functions read the point in the root frame's space`() {
    controller.setSpec(
      """
      {
        "width": 100, "height": 50, "padding": 10, "autosize": "none",
        "signals": [
          {"name": "px", "value": -1, "on": [{"events": "click", "update": "x()"}]},
          {"name": "py", "value": -1, "on": [{"events": "click", "update": "y()"}]},
          {"name": "pxy", "value": null, "on": [{"events": "click", "update": "xy()"}]},
          {"name": "kind", "value": "none",
           "on": [{"events": "*:click", "update": "item().mark.marktype"}]}
        ],
        "data": [{"name": "t", "values": [{"c": "a"}]}],
        "marks": [{
          "type": "rect", "from": {"data": "t"},
          "encode": {"enter": {
            "x": {"value": 0}, "width": {"value": 100},
            "y": {"value": 0}, "height": {"value": 50}, "fill": {"value": "#4c78a8"}}}
        }]
      }
      """
        .trimIndent()
    )
    controller.dispatch(ChartInputEvent.Tap(PointD(30.0, 25.0)))

    val signals = controller.lastCompiled!!.signals
    assertEquals(VegaValue.Num(20.0), signals["px"])
    assertEquals(VegaValue.Num(15.0), signals["py"])
    assertEquals(
      VegaValue.Arr(listOf(VegaValue.Num(20.0), VegaValue.Num(15.0))),
      signals["pxy"],
    )
    // The tap landed on the rect, so `item()` is that item rather than upstream's empty object.
    assertEquals(VegaValue.Str("rect"), signals["kind"])
  }

  /**
   * `x(item)` and `group()` need the chain of groups above the event's item, which this event does
   * not carry. Refused with a message that says so rather than answered from the wrong space.
   */
  @Test
  fun `the argument forms of the event functions are refused`() {
    controller.setSpec(
      json.replace(
        """{"name": "taps", "value": 0,""",
        """{"name": "measured", "value": -1,
          "on": [{"events": "click", "update": "x(item())"}]},
         {"name": "taps", "value": 0,""",
      )
    )
    controller.dispatch(ChartInputEvent.Tap(onSecondBar))
    val reported = controller.state.value.diagnostics.map { it.message }
    assertTrue(reported.any { it.contains("only the argument-less form") }, reported.toString())
  }

  /**
   * `{"encode": "select"}` — a handler whose whole effect is on the event's own item.
   *
   * Upstream rewrites it into `encode(item(), 'select')` and pulses that into the dataflow, so a
   * named block is overlaid on one item and nothing else moves. Two halves were probed rather than
   * assumed. The pass that applies it puts the block **after** the mark's `update`, so a `select`
   * setting `fill` red beats an `update` setting it green; every pass after that re-runs `update`,
   * which takes back the channels it sets and leaves the rest — a second signal change returned the
   * item to green upstream, and returns it to green here.
   *
   * Also the case where nothing else says to redraw: the update expression returns the item it was
   * handed, so no signal value changes, and testing only the signals meant a press styled nothing.
   */
  @Test
  fun `an encode handler overlays a named block on the event's own item`() {
    val spec =
      """
      {
        "width": 90, "height": 30, "padding": 0,
        "signals": [
          {"name": "nudge", "value": 0,
           "on": [{"events": "click", "update": "nudge + 1"}]},
          {"name": "picked", "value": 0, "on": [
            {"events": "rect:mousedown", "encode": "select"},
            {"events": "rect:mouseup", "encode": "release"}
          ]}
        ],
        "data": [{"name": "t", "values": [{"i": 0}, {"i": 1}, {"i": 2}]}],
        "marks": [{
          "type": "rect", "from": {"data": "t"},
          "encode": {
            "enter": {"y": {"value": 0}, "width": {"value": 30}, "height": {"value": 30},
                      "stroke": {"value": "#000000"}},
            "update": {"x": {"signal": "datum.i * 30 + nudge"}, "fill": {"value": "#00ff00"}},
            "select": {"fill": {"value": "#ff0000"}, "strokeWidth": {"value": 3}},
            "release": {"fill": {"value": "#0000ff"}}
          }
        }]
      }
      """
        .trimIndent()
    controller.setSpec(spec)
    assertEquals(listOf("#00ff00", "#00ff00", "#00ff00"), rectFills())

    // A press on the middle rect: only that one takes the `select` block, and the block wins over
    // `update`, which sets the same channel.
    controller.dispatch(
      ChartInputEvent.PointerDown(
        PointD(45.0, 15.0),
        pointerId = 1,
        device = PointerDevice.MOUSE,
        buttons = 1,
      )
    )
    assertEquals(listOf("#00ff00", "#ff0000", "#00ff00"), rectFills())

    // A **signal change** re-runs `update`, which owns `fill` and takes it back — upstream's
    // behaviour, probed both ways. A click that changed nothing would leave the overlay alone there
    // too, because upstream reverts on the encoder running again and not on the clock.
    controller.dispatch(ChartInputEvent.Tap(PointD(1.0, 1.0)))
    assertEquals(listOf("#00ff00", "#00ff00", "#00ff00"), rectFills())

    // And the opposite block replaces the overlay rather than adding to it.
    controller.dispatch(
      ChartInputEvent.PointerUp(
        PointD(45.0, 15.0),
        pointerId = 1,
        device = PointerDevice.MOUSE,
        buttons = 0,
      )
    )
    assertEquals(listOf("#00ff00", "#0000ff", "#00ff00"), rectFills())
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
