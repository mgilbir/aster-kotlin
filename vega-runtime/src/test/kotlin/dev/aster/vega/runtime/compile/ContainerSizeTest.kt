package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.VegaValue
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.SizeD
import dev.aster.vegalite.VegaLiteInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `width: "container"`, and the one question a host has to be able to answer.
 *
 * A responsive width cannot come from the specification — `"container"` means "ask the page" and
 * there is no page — so it comes from the host, which is the only party that knows how much room a
 * chart has. An adopter asked how to pass it in; this is the answer, and it is a **compile** input
 * rather than a draw-time one because Vega-Lite turns `"container"` into a signal, and every scale
 * range, axis extent and mark position downstream is resolved from that signal.
 *
 * With nothing supplied a chart takes `config.view.continuousWidth`, which is 300 and is exactly
 * what upstream falls back to outside a browser. That default is load-bearing: the `container-size`
 * fixture compares against upstream in a `renderer: 'none'` view, where `containerSize()` is
 * `[null, null]`.
 */
class ContainerSizeTest {

  /** The Vega-Lite shape an adopter's server emits: a chart that sizes itself to its surface. */
  private val responsive =
    """
    {
      "${'$'}schema": "https://vega.github.io/schema/vega-lite/v6.json",
      "width": "container",
      "data": {"values": [{"a": 1, "b": 2}, {"a": 3, "b": 4}]},
      "mark": "line",
      "encoding": {
        "x": {"field": "a", "type": "quantitative"},
        "y": {"field": "b", "type": "quantitative"}
      }
    }
    """
      .trimIndent()

  private fun widthOf(containerSize: SizeD?): Double {
    val vega = requireNotNull(VegaLiteInput.toVega(responsive).vegaJson) { "no Vega" }
    val compiled =
      SpecCompiler(VegaHeadlessTextEngine(), containerSize = containerSize).compileJson(vega)
    return (compiled.signals.signal("width") as VegaValue.Num).value
  }

  @Test
  fun `without a container the chart takes upstream's own fallback`() {
    // `config.view.continuousWidth`, which is 300. Not zero and not the surface's size: this is
    // what
    // upstream draws with no page to ask, and the differential fixtures depend on it.
    assertEquals(300.0, widthOf(null))
  }

  @Test
  fun `a host's width is the chart's width`() {
    assertEquals(412.0, widthOf(SizeD(412.0, 900.0)))
  }

  @Test
  fun `the width reaches the drawing, not only the signal`() {
    val vega = requireNotNull(VegaLiteInput.toVega(responsive).vegaJson)
    val wide =
      SpecCompiler(VegaHeadlessTextEngine(), containerSize = SizeD(600.0, 400.0)).compileJson(vega)
    val narrow =
      SpecCompiler(VegaHeadlessTextEngine(), containerSize = SizeD(200.0, 400.0)).compileJson(vega)

    val wideScene = requireNotNull(wide.scene) { "no scene" }
    val narrowScene = requireNotNull(narrow.scene) { "no scene" }
    assertTrue(
      wideScene.width > narrowScene.width + 300.0,
      "the scene did not follow the container: ${wideScene.width} vs ${narrowScene.width}",
    )
  }

  @Test
  fun `a dimension a host does not know is left to the fallback`() {
    // A chart in a scrolling list has a width and as much height as it asks for, so a host answers
    // the half it knows and zero means "not this one".
    assertEquals(412.0, widthOf(SizeD(412.0, 0.0)))
  }

  @Test
  fun `a controller re-lays the chart out when its surface changes`() {
    val controller =
      VegaChartController(
        textEngine = VegaHeadlessTextEngine(),
        containerSize = SizeD(200.0, 400.0),
      )
    val vega = requireNotNull(VegaLiteInput.toVega(responsive).vegaJson)
    controller.setSpec(vega)
    val narrow = requireNotNull(controller.snapshot.scene) { "no scene" }.width

    controller.containerSize = SizeD(600.0, 400.0)
    val wide = requireNotNull(controller.snapshot.scene) { "no scene" }.width

    assertTrue(wide > narrow + 300.0, "resize did not recompile: $narrow then $wide")
  }
}
