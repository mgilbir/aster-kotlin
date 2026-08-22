package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticSeverity
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

  /**
   * A specification that asks its container how big it is, and nobody answers, says so.
   *
   * The answer itself is upstream's and does not change: `[null, null]` is what a browser gives
   * outside a container, verified in a `renderer: 'none'` view, and the `container-size` fixture
   * depends on it. What changed is the silence around it. A document branching on a breakpoint took
   * its "no container" arm with nothing in the diagnostics channel to explain the layout.
   *
   * INFO, because the chart is exactly the one upstream draws.
   */
  @Test
  fun `an unanswered containerSize is reported, per dimension`() {
    val vega = requireNotNull(VegaLiteInput.toVega(responsive).vegaJson)

    fun report(containerSize: SizeD?) =
      SpecCompiler(VegaHeadlessTextEngine(), containerSize = containerSize)
        .compileJson(vega)
        .diagnostics
        .singleOrNull { it.code == DiagnosticCodes.EXPRESSION_CONTAINER_SIZE_UNANSWERED }

    val none = requireNotNull(report(null)) { "no diagnostic for an unanswered containerSize()" }
    assertEquals(DiagnosticSeverity.INFO, none.severity)
    assertTrue("no host width or height was supplied" in none.message, none.message)

    // A host that knows only its width supplies only its width, and the other dimension is still
    // unanswered — which is the case that would read as "supplied" if this were one boolean.
    val widthOnly = requireNotNull(report(SizeD(412.0, 0.0)))
    assertTrue("no host height was supplied" in widthOnly.message, widthOnly.message)

    assertEquals(null, report(SizeD(412.0, 900.0)), "answered, so nothing to say")
  }

  /**
   * `readsContainerSize` is exact, not a search for the word.
   *
   * A host uses it to decide whether a resize is worth a recompile at all, so a false positive
   * costs a compile per layout change on a chart that declares its own size — which is most charts.
   * The probe therefore rejects on the text and then **confirms against the tree**: a field named
   * `containerSize` and a label containing the word are not calls.
   */
  @Test
  fun `readsContainerSize is true only where the function is called`() {
    val vega = requireNotNull(VegaLiteInput.toVega(responsive).vegaJson)
    assertTrue(SpecCompiler(VegaHeadlessTextEngine()).compileJson(vega).readsContainerSize)

    val fixed =
      """
      {"width": 100, "height": 50,
       "data": [{"name": "t", "values": [{"containerSize": 3}]}],
       "marks": [{"type": "text", "from": {"data": "t"}, "encode": {"update": {
         "text": {"signal": "'containerSize is a field here: ' + datum.containerSize"}}}}]}
      """
        .trimIndent()
    val compiled = SpecCompiler(VegaHeadlessTextEngine()).compileJson(fixed)
    assertTrue(compiled.isUsable, compiled.diagnostics.toString())
    assertEquals(
      false,
      compiled.readsContainerSize,
      "a field and a string are not calls: ${compiled.diagnostics}",
    )
    assertEquals(
      emptyList<String>(),
      compiled.diagnostics
        .filter { it.code == DiagnosticCodes.EXPRESSION_CONTAINER_SIZE_UNANSWERED }
        .map { it.message },
    )
  }
}
