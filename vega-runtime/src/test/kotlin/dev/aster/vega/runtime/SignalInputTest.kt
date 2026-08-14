package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.SignalBind
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A specification's `bind` blocks, as controls a host can draw and drive.
 *
 * No widgets here on purpose. The engine's half of a binding is a *description* of the control and
 * a way to write the signal, and both are testable without a screen — which is also what makes the
 * same seam usable from a platform this repository has never run on.
 */
class SignalInputTest {

  private val controller = VegaChartController()

  private val json =
    """
    {
      "width": 200, "height": 60, "padding": 0,
      "signals": [
        {"name": "size", "value": 40,
         "bind": {"input": "range", "min": 10, "max": 100, "step": 5, "name": "bar size"}},
        {"name": "loose", "value": 25, "bind": {"input": "range"}},
        {"name": "shown", "value": true, "bind": {"input": "checkbox"}},
        {"name": "tint", "value": "#4c78a8",
         "bind": {"input": "select", "options": ["#4c78a8", "#e45756"], "labels": ["blue", "red"]}},
        {"name": "shape", "value": "square",
         "bind": {"input": "radio", "options": ["square", "round"]}},
        {"name": "caption", "value": "hello", "bind": {"input": "text"}},
        {"name": "doubled", "value": 0,
         "on": [{"events": {"signal": "size"}, "update": "size * 2"}]},
        {"name": "quiet", "value": 1}
      ],
      "data": [{"name": "t", "values": [{"v": 1}]}],
      "marks": [{
        "type": "rect", "from": {"data": "t"},
        "encode": {"enter": {
          "x": {"value": 0}, "y": {"value": 0},
          "width": {"signal": "size"}, "height": {"signal": "size / 2"},
          "fill": {"signal": "tint"},
          "opacity": {"signal": "shown ? 1 : 0"}
        }}
      }]
    }
    """
      .trimIndent()

  private fun rect(): RectNode {
    val out = mutableListOf<RectNode>()
    fun walk(node: SceneNode) {
      if (node is RectNode) out += node
      if (node is GroupNode) node.children.forEach { walk(it) }
    }
    walk(controller.state.value.snapshot.scene.root)
    return out.single()
  }

  private fun inputFor(signal: String): SignalInput =
    controller.inputs.value.single { it.signal == signal }

  @Test
  fun `a binding becomes a control with the signal's current value`() {
    controller.setSpec(json)

    // Declaration order, and only the signals that asked for a control.
    assertEquals(
      listOf("size", "loose", "shown", "tint", "shape", "caption"),
      controller.inputs.value.map { it.signal },
    )
    // The label is the binding's `name` when it has one, and the signal's own when it does not.
    assertEquals("bar size", inputFor("size").label)
    assertEquals("loose", inputFor("loose").label)

    assertEquals(
      SignalBind.Range(min = 10.0, max = 100.0, step = 5.0, name = "bar size"),
      inputFor("size").bind,
    )
    assertEquals(VegaValue.Num(40.0), inputFor("size").value)
    assertEquals(SignalBind.Checkbox(), inputFor("shown").bind)
    assertEquals(VegaValue.Str("#4c78a8"), inputFor("tint").value)
    assertEquals(0, inputFor("tint").selectedIndex)
    assertEquals(SignalBind.Field("text"), inputFor("caption").bind)

    val shape = inputFor("shape").bind as SignalBind.Choice
    assertTrue(shape.radio)
    // With no labels, an option is its own label.
    assertEquals("square", shape.labelAt(0))
  }

  /**
   * A slider with none of its bounds stated still has usable ones.
   *
   * Upstream reads them off the signal's own value — `max` the larger of 100 and the value, `min`
   * the smaller of 0 and that, `step` the tick step for a hundred divisions — so a bare `{"input":
   * "range"}` is a working control rather than a slider from zero to zero.
   */
  @Test
  fun `an unbounded slider takes upstream's defaults`() {
    controller.setSpec(json)
    assertEquals(SignalBind.Range(min = 0.0, max = 100.0, step = 1.0), inputFor("loose").bind)
  }

  @Test
  fun `setting a signal redraws the chart and cascades`() {
    controller.setSpec(json)
    assertEquals(40.0, rect().width)

    controller.setSignal("size", VegaValue.Num(80.0))

    assertEquals(80.0, rect().width)
    assertEquals(40.0, rect().height, "a scale of the signal moves with it")
    // The handler sourced on `size` fired, which is the whole reason a control goes through the
    // signal machinery rather than straight at the scene.
    assertEquals(VegaValue.Num(160.0), controller.lastCompiled!!.signals["doubled"])
    // And the control now shows the new value, which is what makes the binding two-way.
    assertEquals(VegaValue.Num(80.0), inputFor("size").value)
  }

  @Test
  fun `a choice and a checkbox reach the marks`() {
    controller.setSpec(json)

    controller.setSignal("tint", VegaValue.Str("#e45756"))
    assertEquals(1, inputFor("tint").selectedIndex)
    assertEquals(
      "#e45756",
      (rect().fill?.paint as? dev.aster.vega.scene.ScenePaint.Solid)?.color?.toCssHex(),
    )

    controller.setSignal("shown", VegaValue.Bool(false))
    assertEquals(0.0, rect().opacity)
  }

  /**
   * A chart loaded **asynchronously** answers a control too.
   *
   * The quietest bug the controls turned up: `setSpecAsync` recorded neither the specification's
   * text nor a fresh set of overrides, so a chart loaded that way had nothing to recompile *from* —
   * and no signal change could redraw it, whether it came from a control, a handler or a tap on a
   * mark. Every test here used the synchronous path, and the demo uses the other one, so the demo's
   * interactive specifications were inert and nothing said so.
   */
  @Test
  fun `a chart loaded asynchronously still answers a control`() {
    kotlinx.coroutines.runBlocking { controller.setSpecAsync(json) }
    assertEquals(40.0, rect().width)

    controller.setSignal("size", VegaValue.Num(64.0))

    assertEquals(64.0, rect().width)
    assertEquals(VegaValue.Num(64.0), inputFor("size").value)
  }

  /** A field writes text back, which is what a heading or a colour is. */
  @Test
  fun `a field writes its text through to the chart`() {
    controller.setSpec(
      json.replace(
        """"fill": {"signal": "tint"}""",
        """"fill": {"signal": "caption === 'red' ? '#e45756' : tint"}""",
      )
    )

    controller.setSignal("caption", VegaValue.Str("red"))

    assertEquals(VegaValue.Str("red"), inputFor("caption").value)
    assertEquals(
      "#e45756",
      (rect().fill?.paint as? dev.aster.vega.scene.ScenePaint.Solid)?.color?.toCssHex(),
    )
  }

  /** A write to a signal the specification does not declare does nothing at all. */
  @Test
  fun `a stray write is ignored`() {
    controller.setSpec(json)
    val revision = controller.state.value.snapshot.revision
    controller.setSignal("nothingNamedThis", VegaValue.Num(1.0))
    assertEquals(revision, controller.state.value.snapshot.revision)
  }
}
