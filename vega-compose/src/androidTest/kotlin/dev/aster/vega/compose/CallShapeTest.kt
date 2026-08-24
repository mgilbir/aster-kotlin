package dev.aster.vega.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.aster.vega.android.AndroidImageResolver
import dev.aster.vega.runtime.ChartEvent
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.Scene

/**
 * The call shapes a host writes, asserted by compiling.
 *
 * Nothing here runs, and that is the point: a parameter list is not something a running test can
 * check, because the damage is done at the call site of code that is not in this repository. This
 * fails to *compile* if the shape moves, which is three seconds rather than a bug report.
 *
 * The shape that matters is the **trailing lambda**. Kotlin binds it to the final parameter, so
 * `VegaChart(controller) { event -> … }` means `onEvent` only while `onEvent` is last. Append a
 * parameter after it and the idiom silently rebinds if the new parameter is function-typed, or
 * stops compiling if it is not — and either way every host writing it is broken by a change that
 * looks purely additive, since every parameter has a default and no signature snapshot records the
 * difference.
 *
 * The Swift package has the same guard for the mirror-image reason: a trailing closure binds to the
 * *first* eligible parameter there, so `onPlaced` has to come **before** anything closure-typed
 * rather than after. Both rules exist because a release shipped with one of them broken.
 */
@Suppress("unused")
private object CallShapes {

  /**
   * The lambda's parameter is **used as a [ChartEvent]**, and that is the whole assertion.
   *
   * Writing `println(event)` instead proves nothing, which I found out by trying it: append another
   * closure-typed parameter after `onEvent`, the trailing lambda rebinds to it, its parameter is
   * inferred as whatever that one takes, and `println` accepts it happily. The call compiles, the
   * host's callback is now wired to the wrong seam, and the guard says nothing. Forcing the type
   * makes the rebinding a compile error instead of a silent one.
   */
  private fun onlyAChartEvent(event: ChartEvent): ChartEvent = event

  @Composable
  fun TrailingLambdaIsTheEventCallback(controller: VegaChartController) {
    // If this stops compiling, `onEvent` is no longer the last parameter.
    VegaChart(controller) { event -> onlyAChartEvent(event) }
  }

  @Composable
  fun TrailingLambdaOnTheSceneOverload(scene: Scene) {
    VegaChart(scene) { event -> onlyAChartEvent(event) }
  }

  @Composable
  fun TheShortestCall(controller: VegaChartController) {
    VegaChart(controller)
  }

  @Composable
  fun PositionalControllerAndModifier(controller: VegaChartController) {
    // Two positional arguments, which is as far as a caller can reasonably go before naming things.
    VegaChart(controller, Modifier)
  }

  @Composable
  fun EverySeamIsReachable(controller: VegaChartController) {
    // The whole point of the change this guards: a host on this artifact can reach what the view
    // underneath already had. If one of these disappears, it went missing from one API and not the
    // other, which is the defect being fixed.
    VegaChart(
      controller = controller,
      modifier = Modifier,
      fontResolver = { null },
      accessibilityMaxExposedMarks = 40,
      tooltipsEnabled = false,
      imageResolver = AndroidImageResolver { null },
      onUnresolvedImage = {},
      onPlaced = {},
      onEvent = {},
    )
  }

  @Composable
  fun EverySeamIsReachableFromTheSceneOverload(scene: Scene) {
    VegaChart(
      scene = scene,
      modifier = Modifier,
      fontResolver = { null },
      accessibilityMaxExposedMarks = 40,
      tooltipsEnabled = false,
      imageResolver = AndroidImageResolver { null },
      onUnresolvedImage = {},
      onPlaced = {},
      onEvent = {},
    )
  }
}
