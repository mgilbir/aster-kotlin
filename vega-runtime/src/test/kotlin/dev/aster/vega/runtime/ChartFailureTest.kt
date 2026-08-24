package dev.aster.vega.runtime

import dev.aster.vega.fixtures.SampleScenes
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.scene.Scene
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `ChartState.failure` over its whole life, because the interesting part is when it goes *away*.
 *
 * A compile that draws nothing keeps the previous snapshot on purpose, so the state alone said
 * nothing about what had happened and a host had to infer it from the diagnostics. That inference
 * is wrong in both directions — `PARSE_NOTHING_TO_DRAW` is INFO by choice, and a chart can draw
 * with errors in it — which is why this is stated rather than derived. Both of those directions are
 * asserted below, since they are the reason the property exists.
 *
 * The Swift `ChartSession` carries the same value under the same rules; a host writing "this chart
 * cannot be drawn" should be writing the same logic on both platforms.
 */
class ChartFailureTest {

  private val scene: Scene = SampleScenes.barChart()

  private val bar =
    """
    {
      "width": 120, "height": 60, "padding": 0,
      "data": [{"name": "t", "values": [{"c": "a", "v": 1}, {"c": "b", "v": 3}]}],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
        {"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"}, "range": "height"}
      ],
      "marks": [{
        "type": "rect", "from": {"data": "t"},
        "encode": {"enter": {
          "x": {"scale": "x", "field": "c"},
          "width": {"scale": "x", "band": 1},
          "y": {"scale": "y", "field": "v"},
          "y2": {"value": 0}
        }}
      }]
    }
    """
      .trimIndent()

  @Test
  fun `a compile that draws nothing sets the failure, and keeps the chart`() {
    val controller = VegaChartController.fromScene(scene)
    val before = controller.snapshot.scene

    controller.setSpec("not json at all")

    val state = controller.state.value
    assertNotNull(state.failure, "a compile that produced no scene should say so")
    assertEquals(before, state.snapshot.scene, "the chart on screen should be kept")
  }

  @Test
  fun `the failure is the first error's own message, not a generic sentence`() {
    val controller = VegaChartController.fromScene(scene)
    controller.setSpec("not json at all")

    val state = controller.state.value
    val firstError = state.diagnostics.firstOrNull { it.severity >= DiagnosticSeverity.ERROR }
    assertNotNull(firstError, "this specification should produce an error")
    assertEquals(firstError!!.message, state.failure)
  }

  @Test
  fun `the next compile that draws clears it`() {
    val controller = VegaChartController.fromScene(scene)
    controller.setSpec("not json at all")
    assertNotNull(controller.state.value.failure)

    controller.setSpec(bar)

    assertNull(controller.state.value.failure, "a chart was drawn, so the failure is over")
  }

  @Test
  fun `setScene clears it too`() {
    // The other way a chart arrives. A host that recovers by handing over a scene it built itself
    // has a chart on screen, and a message saying it has none would be describing nothing.
    val controller = VegaChartController()
    controller.setSpec("not json at all")
    assertNotNull(controller.state.value.failure)

    controller.setScene(scene)

    assertNull(controller.state.value.failure)
  }

  @Test
  fun `a chart that draws with errors in it is not a failure`() {
    // The direction a severity filter gets wrong. This specification names a mark type that does
    // not
    // exist, which is reported at ERROR and drops that mark — and the rest of the chart still
    // draws,
    // so a host showing "cannot be drawn" here would be lying about a chart the reader can see.
    val controller = VegaChartController()
    val compiled = controller.setSpec(bar.replace("\"type\": \"rect\"", "\"type\": \"nonsense\""))

    val state = controller.state.value
    assertTrue(
      state.diagnostics.any { it.severity >= DiagnosticSeverity.ERROR },
      "expected this to report an error: ${state.diagnostics}",
    )
    assertTrue(compiled.isUsable, "expected a scene despite the error")
    assertNull(state.failure, "a chart that drew is not a failed compile")
  }

  @Test
  fun `a clean compile never sets it`() {
    val controller = VegaChartController()
    controller.setSpec(bar)
    assertNull(controller.state.value.failure)
  }
}
