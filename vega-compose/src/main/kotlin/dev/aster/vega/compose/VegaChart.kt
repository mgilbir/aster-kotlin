package dev.aster.vega.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.aster.vega.android.VegaChartView
import dev.aster.vega.runtime.ChartEvent
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.Scene

/**
 * Public Compose API for a chart.
 *
 * This hosts the canonical [VegaChartView] through `AndroidView` rather than reimplementing drawing
 * on a Compose `DrawScope`. That is deliberate (PROJECT_BRIEF.md 6.1, `vega-compose`): both APIs
 * then share identical text metrics, rendering, hit testing and accessibility, so a chart cannot
 * look or behave differently depending on which API the app happens to use. A direct `DrawScope`
 * backend may be added later, once the Canvas backend is proven.
 *
 * Recomposition does not reparse or recompile anything: the [controller] owns the scene, and the
 * view is only told to re-check its revision.
 */
@Composable
public fun VegaChart(
  controller: VegaChartController,
  modifier: Modifier = Modifier,
  onEvent: ((ChartEvent) -> Unit)? = null,
) {
  AndroidView(
    modifier = modifier,
    factory = { context -> VegaChartView(context).apply { this.controller = controller } },
    update = { view ->
      // Assigning the same controller would reset the view's state, so only swap when it changed.
      if (view.controller !== controller) view.controller = controller
      view.invalidateIfStale()
    },
  )

  if (onEvent != null) {
    // Keyed on the controller only. `onEvent` is usually a new lambda instance on every
    // recomposition, so keying on it would restart the subscription constantly and drop events;
    // rememberUpdatedState keeps the latest callback without resubscribing.
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(controller) { controller.events.collect { currentOnEvent(it) } }
  }
}

/**
 * Convenience overload for a hand-authored scene.
 *
 * The controller is remembered against [scene], so recomposition with an unchanged scene does not
 * rebuild anything.
 */
@Composable
public fun VegaChart(
  scene: Scene,
  modifier: Modifier = Modifier,
  onEvent: ((ChartEvent) -> Unit)? = null,
) {
  val controller = remember { VegaChartController.fromScene(scene) }
  DisposableEffect(scene) {
    if (controller.snapshot.scene !== scene) controller.setScene(scene)
    onDispose {}
  }
  VegaChart(controller = controller, modifier = modifier, onEvent = onEvent)
}

/** Creates and remembers a controller across recompositions. */
@Composable
public fun rememberVegaChartController(scene: Scene = Scene.empty()): VegaChartController =
  remember {
    VegaChartController.fromScene(scene)
  }
