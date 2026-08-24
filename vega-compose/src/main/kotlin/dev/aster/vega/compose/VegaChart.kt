package dev.aster.vega.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.aster.vega.android.AndroidImageResolver
import dev.aster.vega.android.VegaChartView
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.runtime.ChartEvent
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.AccessibilityTree
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
 *
 * Every seam the view has is a parameter here. Hosting the view is what makes the two APIs agree,
 * and a seam reachable from one and not the other undoes that: a host on this artifact could not
 * register a font or raise the accessibility threshold at all, though the view underneath had both.
 *
 * **[onEvent] stays last, and anything added later goes before it.** Kotlin binds a trailing lambda
 * to the final parameter, so `VegaChart(controller) { event -> … }` — the idiom — means [onEvent]
 * only while [onEvent] is last. A parameter appended after it silently captures that lambda if it
 * is function-typed and rejects it if it is not, and either way every host writing the idiom is
 * broken by a change that looks additive. The Swift package learned this from the other side, where
 * a trailing closure binds to the *first* eligible parameter rather than the last; the rule there
 * is the mirror image and is written beside `onPlaced` for the same reason.
 */
@Composable
public fun VegaChart(
  controller: VegaChartController,
  modifier: Modifier = Modifier,
  /**
   * Resolves a font family named by a specification to a typeface, or null to leave it to the
   * platform. See [VegaChartView.fontResolver] — the same seam, and the same caveat: text metrics
   * are decided when a specification is compiled, so a controller compiling with a different engine
   * measures with different faces.
   */
  fontResolver: ((String) -> android.graphics.Typeface?)? = null,
  /** How many marks are exposed individually to a screen reader; see [VegaChartView]. */
  accessibilityMaxExposedMarks: Int = AccessibilityTree.MAX_EXPOSED_MARKS,
  /** Whether the view draws the tooltip itself, or leaves it to a host that renders its own. */
  tooltipsEnabled: Boolean = true,
  /**
   * Turns an image mark's URL into a bitmap. See [VegaChartView.imageResolver]: a URL is asked once
   * rather than once per frame, and a refusal is remembered, so a resolver that fetches should
   * answer from a cache and start the fetch elsewhere.
   *
   * **Passing a different instance is how a Compose host clears that cache.** There is no reference
   * to the view from here, so [VegaChartView.clearImageCache] cannot be called directly; assigning
   * a resolver that is not identically the previous one rebuilds the renderer, and a new renderer
   * starts with nothing remembered. That is what to do when the image behind a URL has changed, or
   * to give a fetch that failed once another go.
   */
  imageResolver: AndroidImageResolver = AndroidImageResolver.None,
  /** Told the first time an image mark's URL cannot be resolved, and not again for that URL. */
  onUnresolvedImage: ((String) -> Unit)? = null,
  onEvent: ((ChartEvent) -> Unit)? = null,
) {
  AndroidView(
    modifier = modifier,
    factory = { context ->
      VegaChartView(context).apply {
        // Before the controller, because assigning it compiles, and `fontResolver` is documented as
        // wanting to be set before the first compile — the view rebuilds its text engine when it
        // changes, and a chart already measured was measured with whatever this was then.
        this.fontResolver = fontResolver
        this.accessibilityMaxExposedMarks = accessibilityMaxExposedMarks
        this.tooltipsEnabled = tooltipsEnabled
        this.imageResolver = imageResolver
        this.onUnresolvedImage = onUnresolvedImage
        this.controller = controller
      }
    },
    update = { view ->
      // Assigned unconditionally: every one of these setters returns early when the value has not
      // changed, so recomposition costs a comparison and does not churn the view.
      view.fontResolver = fontResolver
      view.accessibilityMaxExposedMarks = accessibilityMaxExposedMarks
      view.tooltipsEnabled = tooltipsEnabled
      view.imageResolver = imageResolver
      view.onUnresolvedImage = onUnresolvedImage
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
  fontResolver: ((String) -> android.graphics.Typeface?)? = null,
  accessibilityMaxExposedMarks: Int = AccessibilityTree.MAX_EXPOSED_MARKS,
  tooltipsEnabled: Boolean = true,
  imageResolver: AndroidImageResolver = AndroidImageResolver.None,
  onUnresolvedImage: ((String) -> Unit)? = null,
  onEvent: ((ChartEvent) -> Unit)? = null,
) {
  val controller = remember { VegaChartController.fromScene(scene) }
  DisposableEffect(scene) {
    if (controller.snapshot.scene !== scene) controller.setScene(scene)
    onDispose {}
  }
  // The same list, forwarded rather than a subset: the two overloads differ in where the scene
  // comes
  // from and in nothing else, and a seam on one and not the other is the defect this change is
  // about.
  VegaChart(
    controller = controller,
    modifier = modifier,
    fontResolver = fontResolver,
    accessibilityMaxExposedMarks = accessibilityMaxExposedMarks,
    tooltipsEnabled = tooltipsEnabled,
    imageResolver = imageResolver,
    onUnresolvedImage = onUnresolvedImage,
    onEvent = onEvent,
  )
}

/**
 * Creates and remembers a controller across recompositions.
 *
 * @param locale the language every generated name and number is written in — a month on a time
 *   axis, a thousands separator, a spoken caption. Defaults to d3's `en-US`. Remembered against it,
 *   so a host that negotiates its user's language rebuilds the controller when that changes.
 */
@Composable
public fun rememberVegaChartController(
  scene: Scene = Scene.empty(),
  locale: VegaLocale = VegaLocale.EnglishUS,
): VegaChartController =
  remember(locale) {
    VegaChartController(initialScene = scene, locale = locale)
  }
