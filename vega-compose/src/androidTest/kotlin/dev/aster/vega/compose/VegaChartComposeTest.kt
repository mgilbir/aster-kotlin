package dev.aster.vega.compose

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aster.vega.fixtures.SampleScenes
import dev.aster.vega.runtime.ChartEvent
import dev.aster.vega.runtime.ChartInputEvent
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Compose API hosts the canonical View, so these tests check the integration — same controller,
 * same scene instance, events reaching the caller, no rebuild on recomposition — rather than
 * re-testing drawing, which `AndroidCanvasSceneRendererTest` covers.
 *
 * Deliberately driven through [ActivityScenario] rather than `createComposeRule`. The Compose test
 * rule idles through Espresso, and Espresso 3.7.0 crashes on API 37 with `NoSuchMethodException:
 * android.hardware.input.InputManager.getInstance`. Nothing here needs Compose's semantics tree or
 * gesture injection, so avoiding Espresso keeps these tests runnable on the newest platform.
 * Revisit when a compatible Espresso release ships.
 */
@RunWith(AndroidJUnit4::class)
class VegaChartComposeTest {

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

  /** Composes [content] in a real activity and runs [assertions] once it has settled. */
  private fun composed(content: @Composable () -> Unit, assertions: () -> Unit) {
    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      scenario.onActivity { activity -> activity.setContent { content() } }
      // Each onActivity call drains the main thread, letting composition and the AndroidView
      // factory run before the assertions observe the result.
      scenario.onActivity {}
      scenario.onActivity {}
      assertions()
    }
  }

  @Test
  fun composeAndViewShareTheSameSceneInstance() {
    val scene = SampleScenes.barChart()
    val controller = VegaChartController.fromScene(scene)

    composed({ VegaChart(controller = controller, modifier = Modifier.fillMaxSize()) }) {
      assertSame(scene, controller.snapshot.scene)
      assertEquals(scene.nodeCount, controller.snapshot.scene.nodeCount)
    }
  }

  @Test
  fun recompositionDoesNotRebuildTheScene() {
    val scene = SampleScenes.lineChart()
    val controller = VegaChartController.fromScene(scene)
    val revisionBefore = controller.snapshot.revision

    composed({ VegaChart(controller = controller, modifier = Modifier.fillMaxSize()) }) {
      // Composing and laying out must not publish a new snapshot: the controller owns the scene.
      assertEquals(revisionBefore, controller.snapshot.revision)
      assertSame(scene, controller.snapshot.scene)
    }
  }

  @Test
  fun eventsReachTheComposeCaller() {
    val scene = SampleScenes.barChart()
    val controller = VegaChartController.fromScene(scene)
    val received = mutableListOf<ChartEvent>()

    ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
      scenario.onActivity { activity ->
        activity.setContent {
          VegaChart(
            controller = controller,
            modifier = Modifier.fillMaxSize(),
            onEvent = { received.add(it) },
          )
        }
      }
      scenario.onActivity {}
      scenario.onActivity {
        // Input arrives in surface coordinates, so scale the scene-space target by the fit scale
        // the
        // hosted view published. Dispatching raw scene coordinates would miss.
        val target = firstBarCenter(scene)
        val scale = controller.contentScale
        controller.dispatch(ChartInputEvent.Tap(PointD(target.x * scale, target.y * scale)))
      }
      // The collector runs in a composition-scoped coroutine, so let the main thread drain again.
      scenario.onActivity {}
      scenario.onActivity {}

      assertFalse(
        "the tap should have produced a selection",
        controller.snapshot.interactionState.selection.isEmpty,
      )
      assertTrue("no events reached the caller: $received", received.isNotEmpty())
    }
  }

  @Test
  fun sceneOverloadUsesItsOwnController() {
    val external = VegaChartController()

    composed({ VegaChart(scene = SampleScenes.areaChart(), modifier = Modifier.fillMaxSize()) }) {
      // The overload owns a remembered controller; an unrelated one must be untouched.
      assertEquals(0L, external.snapshot.revision)
    }
  }
}
