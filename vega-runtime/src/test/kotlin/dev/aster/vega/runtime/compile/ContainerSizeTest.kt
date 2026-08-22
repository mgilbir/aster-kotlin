package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaValue
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.SizeD
import dev.aster.vegalite.VegaLiteInput
import kotlinx.coroutines.runBlocking
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

  /** A chart that declares its own width and height, which is most charts. */
  private val fixedSize =
    """
    {"width": 120, "height": 60, "padding": 5,
     "data": [{"name": "t", "values": [{"a": 1, "b": 2}, {"a": 3, "b": 4}]}],
     "scales": [{"name": "x", "type": "linear", "domain": {"data": "t", "field": "a"},
                 "range": "width"}],
     "marks": [{"type": "symbol", "from": {"data": "t"}, "encode": {"update": {
       "x": {"scale": "x", "field": "a"}, "y": {"value": 30}}}}]}
    """
      .trimIndent()

  /**
   * A resize costs nothing on a chart that never asks how big its container is.
   *
   * The setter recompiled **unconditionally**. Nothing in it looked at whether the loaded document
   * reads `containerSize()`, so a host that reports its layout size on every resize — which is what
   * a host that has any responsive chart at all will do — paid a full compile per step of a
   * split-view drag for every chart on the page, including every chart that states its own width
   * and height.
   *
   * The revision is the evidence rather than a timing: it advances once per publish, so an
   * unchanged revision is proof that nothing was compiled and nothing republished.
   */
  @Test
  fun `a resize does not recompile a chart that never asks its container`() {
    val controller = VegaChartController(textEngine = VegaHeadlessTextEngine())
    controller.setSpec(fixedSize)
    assertEquals(false, requireNotNull(controller.lastCompiled).readsContainerSize)
    val before = controller.snapshot.revision

    controller.containerSize = SizeD(412.0, 900.0)
    assertEquals(before, controller.snapshot.revision, "a compile nobody asked for")

    controller.containerSize = SizeD(800.0, 900.0)
    assertEquals(before, controller.snapshot.revision)

    // And a chart that *does* ask still moves, which is the half that must not be lost.
    val responsiveController =
      VegaChartController(
        textEngine = VegaHeadlessTextEngine(),
        containerSize = SizeD(200.0, 400.0),
      )
    responsiveController.setSpec(requireNotNull(VegaLiteInput.toVega(responsive).vegaJson))
    val responsiveBefore = responsiveController.snapshot.revision
    responsiveController.containerSize = SizeD(600.0, 400.0)
    assertTrue(
      responsiveController.snapshot.revision > responsiveBefore,
      "a responsive chart still re-lays out",
    )
  }

  /**
   * The size is still recorded when the recompile is skipped.
   *
   * Otherwise the saving would be a bug: a specification loaded *after* the resize would compile
   * against a container size the host had already stated and this had quietly dropped.
   */
  @Test
  fun `a skipped recompile still records the size for the next specification`() {
    val controller = VegaChartController(textEngine = VegaHeadlessTextEngine())
    controller.setSpec(fixedSize)
    controller.containerSize = SizeD(412.0, 900.0)
    assertEquals(SizeD(412.0, 900.0), controller.containerSize)

    controller.setSpec(requireNotNull(VegaLiteInput.toVega(responsive).vegaJson))
    assertEquals(412.0, (controller.lastCompiled!!.signals.signal("width") as VegaValue.Num).value)
  }

  /**
   * `setContainerSizeAsync` is the same work off the calling thread, and awaitable.
   *
   * A resize arrives on whatever thread runs a host's layout, which on both hosts is the main one,
   * and the property assignment recompiles inline — so a chart sized to its container paid a
   * compile on the main thread for every step of a drag. Null where nothing was recompiled, which
   * is not a failure: it means the chart on screen is still the right one.
   */
  @Test
  fun `the asynchronous form compiles off the caller's thread and says when it did nothing`() =
    runBlocking {
      val controller =
        VegaChartController(
          textEngine = VegaHeadlessTextEngine(),
          containerSize = SizeD(200.0, 400.0),
        )
      controller.setSpec(requireNotNull(VegaLiteInput.toVega(responsive).vegaJson))
      val narrow = requireNotNull(controller.snapshot.scene).width

      val compiled = requireNotNull(controller.setContainerSizeAsync(SizeD(600.0, 400.0)))
      assertTrue(compiled.readsContainerSize)
      assertTrue(
        requireNotNull(controller.snapshot.scene).width > narrow + 300.0,
        "the awaited compile is published before it returns",
      )

      // The same size again, so nothing to do.
      assertEquals(null, controller.setContainerSizeAsync(SizeD(600.0, 400.0)))

      // And a chart that never asks: recorded, not recompiled.
      val fixed = VegaChartController(textEngine = VegaHeadlessTextEngine())
      fixed.setSpec(fixedSize)
      val before = fixed.snapshot.revision
      assertEquals(null, fixed.setContainerSizeAsync(SizeD(412.0, 900.0)))
      assertEquals(before, fixed.snapshot.revision)
      assertEquals(SizeD(412.0, 900.0), fixed.containerSize)
    }
}
