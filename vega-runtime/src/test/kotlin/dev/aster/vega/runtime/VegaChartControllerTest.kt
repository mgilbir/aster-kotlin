package dev.aster.vega.runtime

import dev.aster.vega.fixtures.SampleScenes
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.VectorD
import dev.aster.vega.scene.flatten
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VegaChartControllerTest {

  private val scene: Scene = SampleScenes.barChart()

  private fun firstBarCenter(scene: Scene): PointD {
    val bar =
      scene
        .flatten()
        .map { it.node }
        .filterIsInstance<RectNode>()
        .first {
          it.metadata.markName == "bars"
        }
    return PointD(bar.rect.centerX, bar.rect.centerY)
  }

  @Test
  fun `revision increases when the scene changes`() {
    val controller = VegaChartController.fromScene(scene)
    val first = controller.snapshot.revision

    controller.setScene(SampleScenes.lineChart())
    val second = controller.snapshot.revision

    assertTrue(second > first, "revision must advance so the view knows to invalidate")
    assertEquals(second, controller.snapshot.scene.revision)
  }

  @Test
  fun `hover updates interaction state without replacing the scene`() {
    val controller = VegaChartController.fromScene(scene)
    val sceneBefore = controller.snapshot.scene

    controller.dispatch(ChartInputEvent.PointerMoved(firstBarCenter(scene)))

    assertTrue(
      sceneBefore === controller.snapshot.scene,
      "a hover must not rebuild or replace the scene",
    )
    assertNotNull(controller.snapshot.interactionState.hoveredNodeId)
    assertNotNull(controller.snapshot.interactionState.tooltip)
  }

  @Test
  fun `hover over empty space clears the hover state`() {
    val controller = VegaChartController.fromScene(scene)
    controller.dispatch(ChartInputEvent.PointerMoved(firstBarCenter(scene)))
    assertNotNull(controller.snapshot.interactionState.hoveredNodeId)

    controller.dispatch(ChartInputEvent.PointerExited(null))
    assertNull(controller.snapshot.interactionState.hoveredNodeId)
    assertNull(controller.snapshot.interactionState.tooltip)
  }

  @Test
  fun `repeated hover over the same mark does not bump the revision`() {
    val controller = VegaChartController.fromScene(scene)
    val point = firstBarCenter(scene)

    controller.dispatch(ChartInputEvent.PointerMoved(point))
    val revision = controller.snapshot.revision
    controller.dispatch(ChartInputEvent.PointerMoved(point))

    assertEquals(revision, controller.snapshot.revision)
  }

  @Test
  fun `tap selects a mark and emits both events`() = runTest {
    val controller = VegaChartController.fromScene(scene)
    val collected = mutableListOf<ChartEvent>()
    // Unconfined so the collector is subscribed before `dispatch` emits.
    val job =
      backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        controller.events.collect { collected.add(it) }
      }

    controller.dispatch(ChartInputEvent.Tap(firstBarCenter(scene)))
    testScheduler.advanceUntilIdle()

    assertTrue(collected.any { it is ChartEvent.MarkClicked }, "collected: $collected")
    assertTrue(collected.any { it is ChartEvent.SelectionChanged }, "collected: $collected")
    assertFalse(controller.snapshot.interactionState.selection.isEmpty)
    job.cancel()
  }

  @Test
  fun `tapping empty space clears an existing selection`() {
    val controller = VegaChartController.fromScene(scene)
    controller.dispatch(ChartInputEvent.Tap(firstBarCenter(scene)))
    assertFalse(controller.snapshot.interactionState.selection.isEmpty)

    controller.dispatch(ChartInputEvent.Tap(PointD(1.0, scene.height - 1.0)))
    assertTrue(controller.snapshot.interactionState.selection.isEmpty)
  }

  @Test
  fun `hit testing inverts the host content scale`() {
    val controller = VegaChartController.fromScene(scene)
    val point = firstBarCenter(scene)

    // The host draws the scene at 2x, so the same mark sits at twice the coordinates on screen.
    controller.contentScale = 2.0

    controller.dispatch(ChartInputEvent.PointerMoved(PointD(point.x * 2.0, point.y * 2.0)))
    assertNotNull(
      controller.snapshot.interactionState.hoveredNodeId,
      "a scaled surface must still hit the mark under the finger",
    )

    controller.dispatch(ChartInputEvent.PointerExited(null))
    // Scene-space coordinates must now miss, because the surface is scaled.
    controller.dispatch(ChartInputEvent.PointerMoved(point))
    assertNull(controller.snapshot.interactionState.hoveredNodeId)
  }

  @Test
  fun `content scale ignores non-positive and non-finite values`() {
    val controller = VegaChartController.fromScene(scene)
    controller.contentScale = 3.0
    controller.contentScale = 0.0
    controller.contentScale = -1.0
    controller.contentScale = Double.NaN
    controller.contentScale = Double.POSITIVE_INFINITY
    assertEquals(3.0, controller.contentScale)
  }

  @Test
  fun `content scale composes with zoom`() {
    val controller = VegaChartController.fromScene(scene)
    val point = firstBarCenter(scene)
    controller.contentScale = 2.0
    controller.dispatch(ChartInputEvent.Zoom(2.0, PointD.Origin, GesturePhase.ENDED))

    // contentScale 2 and zoom 2 put the mark at 4x its scene position, with no pan offset.
    controller.dispatch(ChartInputEvent.PointerMoved(PointD(point.x * 4.0, point.y * 4.0)))
    assertNotNull(controller.snapshot.interactionState.hoveredNodeId)
  }

  @Test
  fun `pan accumulates and hit testing follows the panned viewport`() {
    val controller = VegaChartController.fromScene(scene)
    val point = firstBarCenter(scene)

    controller.dispatch(ChartInputEvent.Pan(VectorD(30.0, 0.0), GesturePhase.CHANGED))
    controller.dispatch(ChartInputEvent.Pan(VectorD(10.0, 0.0), GesturePhase.ENDED))
    assertEquals(40.0, controller.snapshot.interactionState.viewportOffset.dx)

    // The original screen position no longer covers the mark, but the shifted one does.
    controller.dispatch(ChartInputEvent.PointerMoved(PointD(point.x + 40.0, point.y)))
    assertNotNull(controller.snapshot.interactionState.hoveredNodeId)
  }

  @Test
  fun `zoom keeps the anchor point stationary`() {
    val controller = VegaChartController.fromScene(scene)
    val anchor = PointD(200.0, 130.0)

    controller.dispatch(ChartInputEvent.Zoom(2.0, anchor, GesturePhase.ENDED))
    val interaction = controller.snapshot.interactionState
    assertEquals(2.0, interaction.viewportScale)

    // Forward-mapping the anchor's scene position must land back on the anchor.
    val sceneX = (anchor.x - interaction.viewportOffset.dx) / interaction.viewportScale
    assertEquals(anchor.x, sceneX * interaction.viewportScale + interaction.viewportOffset.dx, 1e-9)
  }

  @Test
  fun `zoom is clamped and a non-positive factor is reported`() {
    val controller = VegaChartController.fromScene(scene)
    repeat(20) {
      controller.dispatch(ChartInputEvent.Zoom(3.0, PointD.Origin, GesturePhase.CHANGED))
    }
    assertEquals(VegaChartController.MAX_ZOOM, controller.snapshot.interactionState.viewportScale)

    controller.dispatch(ChartInputEvent.Zoom(0.0, PointD.Origin, GesturePhase.CHANGED))
    assertEquals(
      DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
      controller.diagnostics.value.last().code,
    )
  }

  @Test
  fun `resetViewport returns to the identity transform`() {
    val controller = VegaChartController.fromScene(scene)
    controller.dispatch(ChartInputEvent.Zoom(2.0, PointD(10.0, 10.0), GesturePhase.ENDED))
    controller.dispatch(ChartInputEvent.Pan(VectorD(25.0, 25.0), GesturePhase.ENDED))

    controller.resetViewport()
    assertEquals(1.0, controller.snapshot.interactionState.viewportScale)
    assertEquals(VectorD.Zero, controller.snapshot.interactionState.viewportOffset)
  }

  @Test
  fun `setSpec reports an explicit diagnostic instead of rendering nothing`() {
    val controller = VegaChartController.fromScene(scene)
    controller.setSpec("{\"\$schema\": \"https://vega.github.io/schema/vega/v5.json\"}")

    val diagnostic = controller.diagnostics.value.single()
    assertEquals(DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED, diagnostic.code)
    assertTrue(diagnostic.message.contains("Milestone 3"))

    controller.clearDiagnostics()
    assertTrue(controller.diagnostics.value.isEmpty())
  }

  @Test
  fun `interaction updates are deterministic`() {
    fun run(): InteractionState {
      val controller = VegaChartController.fromScene(SampleScenes.barChart())
      controller.dispatch(ChartInputEvent.PointerMoved(firstBarCenter(scene)))
      controller.dispatch(ChartInputEvent.Tap(firstBarCenter(scene)))
      controller.dispatch(ChartInputEvent.Zoom(1.5, PointD(50.0, 50.0), GesturePhase.ENDED))
      controller.dispatch(ChartInputEvent.Pan(VectorD(5.0, -5.0), GesturePhase.ENDED))
      return controller.snapshot.interactionState
    }
    assertEquals(run(), run())
  }
}
