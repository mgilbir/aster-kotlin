package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.SceneNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A changed signal recompiles the **whole** specification: there is no incremental dataflow.
 *
 * `SUPPORTED_FEATURES.md` files this as a `Deliberate difference`, and the difference is measured
 * rather than merely chosen. Compiling each of 55 fixtures 200 times on a warm JIT put the heaviest
 * at 366 microseconds and the median at 112, against a 16,600 microsecond frame at 60fps. Even
 * allowing an order of magnitude for ART, a cold cache and a phone's CPU, a full recompile fits
 * inside a frame — so an incremental engine buys nothing an interaction can feel, and the
 * `DataflowOperator` contract sits there unused in case that stops being true.
 *
 * Two things are pinned. The **structure**, because that is the claim: a signal change rebuilds
 * every node rather than patching the ones that moved. And a **ceiling** on the time, deliberately
 * far looser than the measurement, because the argument only holds while a recompile is cheap and a
 * hundredfold regression would quietly turn a documented decision into a wrong one.
 */
class RecompileNotPulseTest {

  private val json =
    """
    {
      "width": 200, "height": 100, "padding": 0, "autosize": "none",
      "data": [{"name": "t", "values": [{"c": "a", "v": 3}, {"c": "b", "v": 7}, {"c": "c", "v": 5}]}],
      "signals": [{"name": "top", "value": 10,
                   "on": [{"events": "rect:click", "update": "50"}]}],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
         "range": "width", "padding": 0},
        {"name": "y", "type": "linear", "domain": [0, {"signal": "top"}], "range": "height"}
      ],
      "axes": [{"scale": "y", "orient": "left"}],
      "marks": [{
        "type": "rect", "from": {"data": "t"},
        "encode": {"enter": {"x": {"scale": "x", "field": "c"},
                             "width": {"scale": "x", "band": 1},
                             "y": {"scale": "y", "field": "v"},
                             "y2": {"value": 0}}}
      }]
    }
    """
      .trimIndent()

  /**
   * Every node in the scene, by **reference**.
   *
   * Not by `id`: identity is allocated per compile and restarts at one, so two unrelated compiles
   * share ids by construction and comparing them would say nothing. What says a scene was rebuilt
   * rather than patched is that no node *object* survived.
   */
  private fun nodes(root: SceneNode): List<SceneNode> {
    val out = mutableListOf<SceneNode>()
    fun walk(node: SceneNode) {
      out += node
      if (node is GroupNode) node.children.forEach { walk(it) }
    }
    walk(root)
    return out
  }

  /**
   * Every node is new after a signal changes, which is what "recompiles the whole specification"
   * means.
   *
   * Node identity is allocated per compile, so a patched scene would keep the ids of the marks that
   * did not move. Sharing even one would mean an incremental path had appeared, and the row would
   * be describing an engine that no longer exists.
   */
  @Test
  fun `a changed signal rebuilds every node rather than patching`() {
    val controller = VegaChartController()
    controller.setSpec(json)
    val before = nodes(controller.state.value.snapshot.scene.root)
    assertTrue(before.size > 5, "the chart is too small for this to say anything")

    controller.dispatch(ChartInputEvent.Tap(dev.aster.vega.scene.PointD(20.0, 50.0)))
    assertEquals(VegaValue.Num(50.0), controller.lastCompiled!!.signals["top"])

    val after = nodes(controller.state.value.snapshot.scene.root)
    val survivors = after.filter { node -> before.any { it === node } }
    assertEquals(
      emptyList<SceneNode>(),
      survivors,
      "${survivors.size} node object(s) survived the recompile, so the scene is being patched " +
        "rather than rebuilt and an incremental path has appeared",
    )
  }

  /**
   * And a recompile stays far inside a frame.
   *
   * The ceiling is **50 milliseconds** against a measurement of 366 microseconds — deliberately
   * more than a hundred times the observed figure, because a timing assertion tight enough to be
   * interesting is an assertion that flakes on a loaded machine. What it catches is the change that
   * would invalidate the decision: an accidental quadratic, a per-compile re-parse, a cache that
   * stopped working. The real numbers live in STATUS.md, Performance observations, where they can
   * be stated with their caveats.
   */
  @Test
  fun `a full recompile stays far inside a frame`() {
    val controller = VegaChartController()
    controller.setSpec(json)
    // Warm the JIT and the expression cache; the claim is about a running chart, not a cold start.
    repeat(20) { controller.setSpec(json) }

    val started = System.nanoTime()
    repeat(20) { controller.setSpec(json) }
    val perCompileMillis = (System.nanoTime() - started) / 20.0 / 1_000_000.0

    assertTrue(
      perCompileMillis < 50.0,
      "a full recompile took ${perCompileMillis}ms, which is past the point where recompiling " +
        "instead of propagating a pulse is still the right decision",
    )
  }
}
