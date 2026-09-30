package dev.aster.vega.runtime

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.SizeD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `container:resize`, Vega 6.4.0's event source (vega/vega#4318).
 *
 * Upstream's container is the element a view is embedded in, watched by a `ResizeObserver`; here it
 * is the surface a host draws in, which it reports with [ChartInputEvent.Resized] on every layout.
 * The rules are the observer's: the first size is a baseline that fires nothing, an unchanged size
 * fires nothing, a container collapsed in both directions fires nothing, and `resize` is the only
 * type a container has. `config.events.container` can refuse the listener.
 *
 * Delivered from `dispatch`, on the host's own thread, and not from the container-size setters:
 * `setContainerSizeAsync` finishes on whatever thread its compile resumed on, and a handler reads
 * the host's clock, which the iOS host binds to the main actor. Firing from there trapped the Swift
 * suite.
 */
class ContainerResizeEventTest {

  private fun spec(config: String = "{}") =
    """
    {
      "width": 100, "height": 50, "config": $config,
      "signals": [
        {"name": "resizes", "value": 0,
         "on": [{"events": "container:resize", "update": "resizes + 1"}]},
        {"name": "measured", "value": null,
         "on": [{"events": "container:resize", "update": "containerSize()[0]"}]}
      ]
    }
    """
      .trimIndent()

  private fun VegaChartController.signal(name: String): VegaValue? = lastCompiled!!.signals[name]

  private fun VegaChartController.resize(width: Double, height: Double) =
    dispatch(ChartInputEvent.Resized(width, height, pixelScale = 1.0))

  @Test
  fun `a changed surface fires the stream, and the first one is only a baseline`() {
    val controller = VegaChartController()
    controller.setSpec(spec())
    controller.resize(300.0, 200.0)
    assertEquals(VegaValue.Num(0.0), controller.signal("resizes"), "the baseline fired")

    controller.containerSize = SizeD(320.0, 200.0)
    controller.resize(320.0, 200.0)
    assertEquals(VegaValue.Num(1.0), controller.signal("resizes"))
    // The handler reads `containerSize()`, and reads what the host said rather than `[null, null]`.
    assertEquals(VegaValue.Num(320.0), controller.signal("measured"))

    controller.containerSize = SizeD(480.0, 200.0)
    controller.resize(480.0, 200.0)
    assertEquals(VegaValue.Num(2.0), controller.signal("resizes"))
    assertEquals(VegaValue.Num(480.0), controller.signal("measured"))
  }

  @Test
  fun `the same size, or no size at all, fires nothing`() {
    val controller = VegaChartController()
    controller.setSpec(spec())
    controller.resize(300.0, 200.0)
    controller.resize(320.0, 200.0)
    controller.resize(320.0, 200.0)
    assertEquals(VegaValue.Num(1.0), controller.signal("resizes"))
    // Upstream's observer returns early for a container with no width and no height.
    controller.resize(0.0, 0.0)
    assertEquals(VegaValue.Num(1.0), controller.signal("resizes"))
  }

  @Test
  fun `config events container can refuse the listener`() {
    val controller = VegaChartController()
    controller.setSpec(spec("""{"events": {"container": false}}"""))
    controller.resize(300.0, 200.0)
    controller.resize(320.0, 200.0)
    assertEquals(VegaValue.Num(0.0), controller.signal("resizes"))
    // The dispatcher's reports reach the controller's diagnostics, not the compile's.
    val diagnostics = controller.diagnostics.value
    assertTrue(
      diagnostics.any {
        it.code == DiagnosticCodes.INTERACTION_UNSUPPORTED && "container resize" in it.message
      },
      "the refusal was not reported: $diagnostics",
    )
  }

  @Test
  fun `a container has no event but resize`() {
    val controller = VegaChartController()
    controller.setSpec(
      """
      {"width": 100, "height": 50,
       "signals": [{"name": "s", "value": 0,
                    "on": [{"events": "container:click", "update": "s + 1"}]}]}
      """
        .trimIndent()
    )
    val diagnostics = controller.diagnostics.value
    assertTrue(
      diagnostics.any { "Unsupported container event type: click" in it.message },
      "the unknown type was not reported: $diagnostics",
    )
  }
}
