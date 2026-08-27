package dev.aster.vega.compose.mp

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.aster.vega.model.locale.VegaCaptions
import dev.aster.vega.scene.AccessibilityTree
import dev.aster.vega.scene.AccessibleElement
import dev.aster.vega.scene.HitTestOptions
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneHitIndex
import dev.aster.vega.scene.SceneNodeId
import dev.aster.vega.scene.VectorD
import kotlin.math.roundToInt

/**
 * Draws a compiled [Scene], on Android, iOS or the desktop.
 *
 * The scene is what the engine produced; this composable only paints it. Nothing here recompiles a
 * specification, so a chart redraws at the cost of a traversal.
 *
 * A chart takes the scene's **own** size unless [modifier] says otherwise — a specification
 * declares a width and a height, so that is the size it wants, and a caller who wants something
 * else says so and gets [fit] applied. The alternative, a `Canvas` with no intrinsic size, is worse
 * than it sounds: a zero-sized layer still put solid fills on the screen here while resolving every
 * *gradient* to black, which is a bug report about colours rather than about layout.
 *
 * **It is also the accessibility tree.** The engine computes one in common code and this exposes
 * it: one zero-alpha element per focusable mark, axis, legend and title, positioned over the thing
 * it describes so a reader can *touch* it rather than only swipe through it. Without that the chart
 * was one silent drawing on this renderer while the Android View and the Swift one both spoke.
 *
 * @param fit how a scene that is not the size of its slot is placed in it. It only has something to
 *   do where the slot and the scene differ — see [sizing], which is what decides whether they can.
 * @param sizing where the chart's size comes from where [modifier] leaves a dimension free.
 *   [SceneSizing.Scene] is the scene's own, which is the default and what this always did;
 *   [SceneSizing.Fill] takes the slot instead and leaves [fit] to place the scene inside it.
 * @param selectedNodeIds the marks currently selected, so a reader is told which. From a
 *   controller's interaction state where there is one.
 * @param captions the language the one sentence the tree writes itself is in — the dense-chart
 *   summary. Everything else is already in the chart's own locale, having come from the compiler.
 * @param accessibilityMaxExposedMarks how many **data marks** a reader may explore one by one
 *   before a summary stands in for them. [AccessibilityTree.MAX_EXPOSED_MARKS] by default, which is
 *   the engine's judgement rather than a fact — a host knows the size of the screen, whether the
 *   chart is the page or a thumbnail on it, and what its own users have said. Only marks count
 *   toward it and the guides are exposed either way, so a chart of many small points does not
 *   collapse on the strength of its axis labels.
 * @param resolveImage turns an `image` mark's URL into something drawable. Null draws no URL
 *   images, which is the default and is deliberate: a URL is not an image, and fetching one is a
 *   decision about following an address the *specification* chose — the same argument `DataLoader`
 *   makes for data. `data:` URLs and engine-produced rasters need no resolver and are drawn without
 *   one. An image this cannot answer leaves a hole in the chart rather than aborting the draw; the
 *   lower-level `DrawScopeTarget` collects those URLs, and this composable has nowhere to hand
 *   them.
 * @param imageCache where decoded images are kept **across frames**. A draw target is built once
 *   per frame, so a cache inside one lives for a single draw: a heatmap's raster was PNG-decoded on
 *   every frame, and a resolver would be called on every frame. The default is remembered against
 *   the composition, which is what a caller wants; pass one to share it between charts, or to clear
 *   it when the image behind a URL has changed.
 * @param onUnresolvedImage told the first time an `image` mark's URL cannot be resolved, and not
 *   again for that URL. An unresolved image leaves a hole in the chart and the draw carries on,
 *   which is right — a chart is better with one mark missing than not drawn at all — and until now
 *   the hole was all a host got, since the target that collects these is built per frame and
 *   discarded with it.
 *
 *   **Called from the draw**, so treat it as a report and not as a place to set state a
 *   recomposition would read: launch, log, enqueue. It fires once per URL per [imageCache] rather
 *   than once per frame, which is what makes it safe to have at all; [ImageCache.unresolvedImages]
 *   is the same facts without a callback, for a host that would rather poll.
 *
 * @param onActivate what to do when a **reader** activates a mark through the accessibility tree.
 *   Null leaves the chart inert, which is right for a chart that is only being looked at.
 * @param onTap a tap, in **scene** coordinates, with the mark under it or null where it hit
 *   nothing.
 * @param onLongPress the same for a long press, which most specifications bind a tooltip to.
 * @param onPan a drag, as the increment since the last report, and whether the gesture has ended.
 * @param onZoom a pinch, as the factor since the last report, about an anchor in scene coordinates.
 * @param onHover a pointer moving over the chart, and null when it leaves. Mouse and stylus only.
 * @param hitTestOptions how generously a tap picks a mark; the default is the touch tolerance.
 * @param viewportOffset the pan a controller has accumulated, in **pixels**, from
 *   `InteractionState.viewportOffset`. Without it a pan updates state that nothing shows.
 * @param viewportScale the zoom a controller has accumulated, from
 *   `InteractionState.viewportScale`.
 * @param onPlaced the fit scale and centring offset, whenever the slot's size changes — a host sets
 *   `controller.contentScale` from it, which is what lets the controller invert a point correctly.
 *
 * A callback rather than a controller because this module depends on `vega-scene` alone: a scene is
 * all a renderer needs, and taking `vega-runtime` here to dispatch a tap would make every host that
 * only draws pay for a dataflow. A host that has a controller passes `onActivate = {
 * controller.dispatch(ChartInputEventTap(...)) }`, which is also where a tooltip comes from.
 */
@Composable
public fun VegaChart(
  scene: Scene,
  modifier: Modifier = Modifier,
  fit: SceneFit = SceneFit.Contain,
  sizing: SceneSizing = SceneSizing.Scene,
  textEngine: ComposeTextEngine = rememberVegaTextEngine(),
  selectedNodeIds: Set<SceneNodeId> = emptySet(),
  captions: VegaCaptions = VegaCaptions.English,
  accessibilityMaxExposedMarks: Int = AccessibilityTree.MAX_EXPOSED_MARKS,
  resolveImage: ((String) -> ImageBitmap?)? = null,
  imageCache: ImageCache = rememberVegaImageCache(),
  onUnresolvedImage: ((String) -> Unit)? = null,
  onActivate: ((SceneNodeId) -> Unit)? = null,
  onTap: ((PointD, SceneNodeId?) -> Unit)? = null,
  onLongPress: ((PointD, SceneNodeId?) -> Unit)? = null,
  onPan: ((VectorD, Boolean) -> Unit)? = null,
  onZoom: ((Double, PointD, Boolean) -> Unit)? = null,
  onHover: ((PointD?, SceneNodeId?) -> Unit)? = null,
  hitTestOptions: HitTestOptions = HitTestOptions.Touch,
  viewportOffset: VectorD = VectorD.Zero,
  viewportScale: Double = 1.0,
  onPlaced: ((ChartPlacement) -> Unit)? = null,
) {
  val walk = remember { SceneWalk() }
  val density = LocalDensity.current.density
  // Built once per scene, not per tap: `SceneHitIndex` walks the whole tree and, past its
  // threshold,
  // builds a grid. A chart of five hundred marks would otherwise pay for that on every finger down.
  val hitIndex = remember(scene, hitTestOptions) { SceneHitIndex(scene, hitTestOptions) }
  // Cached against the scene's identity and the selection, which is what the engine's own
  // documentation asks a host to do: `elements` flattens the scene, so recomputing it on every
  // recomposition would walk the tree for a pointer that moved.
  val elements =
    remember(scene, selectedNodeIds, captions, accessibilityMaxExposedMarks) {
      AccessibilityTree.elements(scene, selectedNodeIds, captions, accessibilityMaxExposedMarks)
    }

  // **The caller's modifier first, then a size.** The other order looks equivalent and is not: a
  // `size` modifier fixes the constraints its child is measured with, so
  // `Modifier.size(sceneSize).then(caller)` clamped every caller to the scene's own size — a chart
  // could not be made bigger or filled to a slot, which quietly made `SceneFit.Contain`, the
  // default, mean nothing outside a test that sizes the canvas itself.
  //
  // What the size *is* comes from [sizing], and this is the order that makes both answers work.
  // `Modifier.size` coerces the size it wants into the constraints it is handed, so a caller that
  // bounds a dimension already wins; what it decides is the dimension a caller left free, and there
  // it used to be the scene's own — about 300 units plus axes for a `width: "container"` chart,
  // whatever room was going, with nothing to say `fit` had done nothing. `fillMaxSize` in front of
  // it
  // fills a **bounded** dimension and passes an unbounded one through untouched, so `Fill` takes
  // the
  // slot where there is one and falls back to the scene's size where there is not — which is the
  // only
  // thing it can do inside a scrolling column.
  //
  // Scene units are CSS pixels, which is what a dp is on the platforms this runs on, so the
  // `Scene` answer is the chart at its natural size.
  val sized =
    when (sizing) {
      SceneSizing.Scene -> Modifier
      SceneSizing.Fill -> Modifier.fillMaxSize()
    }
  // The placement last handed to [onPlaced]; see the note beside the call. A plain holder rather
  // than a `MutableState`, because writing it must not invalidate anything — it is a record of what
  // was reported, not an input to the drawing.
  val lastPlacement = remember { Ref<ChartPlacement>() }

  Box(modifier = modifier.then(sized).then(Modifier.size(scene.width.dp, scene.height.dp))) {
    Canvas(
      modifier =
        Modifier.matchParentSize()
          // The drawing says nothing on its own: every announcement belongs to an element in the
          // overlay, and a canvas that also carried a description would be read out twice.
          .clearAndSetSemantics {}
          .chartPointerInput(
            scene = scene,
            fit = fit,
            density = density,
            hitIndex = hitIndex,
            onTap = onTap,
            onLongPress = onLongPress,
            onPan = onPan,
            onZoom = onZoom,
            onHover = onHover,
            // **Read, not keyed.** See `chartPointerInput`: a `pointerInput` restarts when a key
            // changes, and the viewport changes on the first pixel of every pan.
            viewport = rememberUpdatedState(Viewport(viewportOffset, viewportScale)),
          )
    ) {
      // Reported before drawing, so a host that sets `contentScale` from it has done so before the
      // first gesture can arrive. The **fit** alone: the controller applies the pan and the zoom
      // itself, and handing it a scale that already carried them would apply each twice.
      //
      // **Only when it changed**, which the Android View's `reportPlacement` has always done and
      // this did not: `DrawScope` runs per frame, so a host was called from the draw phase sixty
      // times a second with the same numbers. A host doing the documented thing with them — setting
      // `controller.contentScale` — was writing to a `StateFlow` from inside a draw, which
      // schedules the next frame, which draws, which writes. The same seam on two renderers fired
      // at two different cadences and neither said so.
      val placed = fitPlacement(scene, size.width, size.height, fit, density)
      if (placed != lastPlacement.value) {
        lastPlacement.value = placed
        onPlaced?.invoke(placed)
      }
      val placement =
        placement(scene, size.width, size.height, fit, density, viewportOffset, viewportScale)
      translate(left = placement.left, top = placement.top) {
        scale(scale = placement.scale, pivot = Offset.Zero) {
          walk.draw(
            scene,
            DrawScopeTarget(
              scope = this,
              textMeasurer = textEngine.measurer,
              fontFamilyResolver = textEngine.fontFamilyResolver,
              resolveImage = resolveImage,
              imageCache = imageCache,
              onUnresolvedImage = onUnresolvedImage,
            ),
          )
        }
      }
    }

    AccessibilityOverlay(
      elements = elements,
      scene = scene,
      fit = fit,
      density = density,
      onActivate = onActivate,
      // The same pan and zoom the drawing used: a reader exploring by touch has to land on the mark
      // where it *is* now, not where it was before the chart was moved.
      viewportOffset = viewportOffset,
      viewportScale = viewportScale,
      modifier = Modifier.matchParentSize(),
    )
  }
}

/**
 * The accessibility elements, laid out over the parts of the chart they describe.
 *
 * Positioned rather than merely listed, and that distinction is the whole of it: a reader exploring
 * by touch lands on a *rectangle*, and a set of elements without frames can be swiped through but
 * not touched. The Swift renderer says the same thing in `SceneCanvas.accessibilityOverlay`, and
 * its header records why — `accessibilityChildren` there yields frames of `(inf, inf, 0, 0)` that a
 * reader cannot reach.
 *
 * One `Layout` rather than a modifier per child, because the frames have to be measured against the
 * **placed** chart: the fit scale and the centring offset come from the slot's own size, which only
 * a layout can see. It is also what keeps one copy of that arithmetic — two copies is how a
 * reader's finger lands beside the mark it looked like it hit, a defect this project has had on
 * Android and in the Swift renderer, both of which now share one placement between drawing and
 * touching.
 */
@Composable
private fun AccessibilityOverlay(
  elements: List<AccessibleElement>,
  scene: Scene,
  fit: SceneFit,
  density: Float,
  onActivate: ((SceneNodeId) -> Unit)?,
  viewportOffset: VectorD,
  viewportScale: Double,
  modifier: Modifier = Modifier,
) {
  if (elements.isEmpty()) return
  Layout(
    modifier = modifier,
    content = {
      for (element in elements) {
        val nodeId = element.nodeId
        val activate = if (element.activatable && nodeId != null) onActivate else null
        Box(
          Modifier.semantics {
            contentDescription = element.label
            selected = element.selected
            // A **button only where activating it does something.** Both existing hosts announce
            // every element as a button — `className = "android.widget.Button"` on Android,
            // `.isButton` on iOS — so a reader is told they can activate an axis caption and then
            // nothing happens when they try. `AccessibleElement.activatable` is the engine's own
            // answer to which elements are marks.
            if (activate != null) {
              role = Role.Button
              onClick {
                activate(nodeId!!)
                true
              }
            }
          }
        )
      }
    },
  ) { measurables, constraints ->
    val placement =
      placement(
        scene,
        constraints.maxWidth.toFloat(),
        constraints.maxHeight.toFloat(),
        fit,
        density,
        viewportOffset,
        viewportScale,
      )
    // At least a pixel in each direction: a rule, an axis domain line or a zero-height bar has no
    // extent on one axis, and a reader cannot land on a frame of no size.
    val placeables = measurables.mapIndexed { index, measurable ->
      val bounds = elements[index].bounds
      measurable.measure(
        Constraints.fixed(
          width = (bounds.width * placement.scale).roundToInt().coerceAtLeast(1),
          height = (bounds.height * placement.scale).roundToInt().coerceAtLeast(1),
        )
      )
    }
    layout(constraints.maxWidth, constraints.maxHeight) {
      placeables.forEachIndexed { index, placeable ->
        val bounds = elements[index].bounds
        placeable.place(
          x = (bounds.left * placement.scale + placement.left).roundToInt(),
          y = (bounds.top * placement.scale + placement.top).roundToInt(),
        )
      }
    }
  }
}

/**
 * Where a chart's size comes from where its `modifier` leaves a dimension free.
 *
 * The two are not alternatives to [SceneFit] but the question in front of it: `fit` decides how a
 * scene is placed in a slot of a different size, and it has nothing to do until something decides
 * that the slot may *be* a different size.
 */
public enum class SceneSizing {
  /**
   * The scene's own size — one scene unit per dp.
   *
   * A specification declares a width and a height, so that is the size it wants, and this is the
   * default for that reason. The trap it comes with is worth stating: a caller that bounds neither
   * dimension gets this whatever `fit` says, and for a `width: "container"` chart that is
   * `config.view.continuousWidth` — 300 — plus its axes, however much room was available.
   */
  Scene,

  /**
   * Whatever the slot allows, leaving `fit` to place the scene inside it.
   *
   * A bounded dimension is filled; an unbounded one falls back to the scene's own size, because
   * there is nothing else it could be — a chart inside a scrolling column has as much height as it
   * asks for. So this is safe to pass anywhere and does something wherever there is room to do it
   * in.
   */
  Fill,
}

/** How a scene is placed in a slot that is not its own size. */
public enum class SceneFit {
  /** Scaled to fit, keeping its aspect ratio, and centred. */
  Contain,

  /** Drawn at its own size — one scene unit per dp — in the slot's top-left corner. */
  None,
}

/** The fit scale and the centring offset, in pixels, shared by the drawing and the overlay. */
private data class Placement(val scale: Float, val left: Float, val top: Float)

/**
 * Where a chart ended up in its slot: the **fit** scale, and the offset it was centred by.
 *
 * Reported through `onPlaced` for the one thing a host has to do itself — tell its controller what
 * the fit factor is, `controller.contentScale = placement.scale`, so that the controller can invert
 * a point the way it documents: subtract the pan offset, then divide by `contentScale *
 * viewportScale`.
 *
 * The pan and zoom **state** goes the other way, from the controller into `VegaChart`, so the same
 * numbers move the drawing, the touch target and the accessibility frames. One placement for all
 * three is the whole point; the Swift renderer's `ChartPlacement` exists for the same reason and
 * says so.
 *
 * **An alias now.** The type moved to `vega-scene`, the module every renderer already depends on,
 * because every renderer needs it: declaring it here left the two `View`-based Android surfaces
 * unable to report a placement at all, since they cannot depend on a Compose module to say where
 * they drew something. The alias keeps `dev.aster.vega.compose.mp.ChartPlacement` compiling.
 */
public typealias ChartPlacement = dev.aster.vega.scene.ScenePlacement

/**
 * Where a scene sits inside a slot, computed once and used by everything that has to agree about
 * it.
 *
 * Both the drawing and the accessibility frames go through this. A second copy of the arithmetic is
 * how a reader's finger lands next to the mark it looked like it hit — a defect this project has
 * had twice, once on Android and once in the Swift renderer, which is why both of those share one
 * placement too.
 */
/**
 * Gestures, turned into **scene** coordinates and the mark under them.
 *
 * This renderer had no pointer input at all: a tap did nothing, and the only way to activate a mark
 * was through the accessibility tree — so a screen-reader user could select one and a sighted user
 * could not. Every other host answers a finger, and a specification saying `"tooltip": true`
 * expects one to.
 *
 * What it deliberately does **not** do is dispatch. This module depends on `vega-scene` alone, so
 * there is no dataflow here to send an event into; a host with a controller forwards these to it,
 * and a host that only draws passes nothing and pays for nothing. What the module *does* own is the
 * part a host must not have to repeat: inverting the **same placement** the drawing used, and hit
 * testing through `SceneHitIndex`. Two copies of that arithmetic is how a finger lands beside the
 * mark it looked like it hit — a defect this project has had on Android and in the Swift renderer,
 * which is why `ChartPlacement` exists there.
 *
 * The phases a host will need: `onPan` and `onZoom` report an **increment** and whether the gesture
 * is over, which maps onto `ChartInputEvent` as `GesturePhase.CHANGED` while `ended` is false and
 * `GesturePhase.ENDED` when it is true. That is the same pairing the Swift session sends.
 */
/**
 * The pan and the zoom together, so `chartPointerInput` reads one state rather than two.
 *
 * A value class in all but name: it exists because `rememberUpdatedState` holds one object, and the
 * point of holding one is to keep both out of the `pointerInput` keys. See [chartPointerInput].
 */
private data class Viewport(val offset: VectorD, val scale: Double)

/** A mutable box that is not Compose state: writing it invalidates nothing. See `onPlaced`. */
private class Ref<T> {
  var value: T? = null
}

private fun Modifier.chartPointerInput(
  scene: Scene,
  fit: SceneFit,
  density: Float,
  hitIndex: SceneHitIndex,
  onTap: ((PointD, SceneNodeId?) -> Unit)?,
  onLongPress: ((PointD, SceneNodeId?) -> Unit)?,
  onPan: ((VectorD, Boolean) -> Unit)?,
  onZoom: ((Double, PointD, Boolean) -> Unit)?,
  onHover: ((PointD?, SceneNodeId?) -> Unit)?,
  /**
   * The pan and the zoom the host has accumulated, as a **state to read** rather than a value.
   *
   * `Modifier.pointerInput` restarts its coroutine whenever a key changes, cancelling whatever
   * gesture is in flight. The viewport was among the keys, and the viewport is precisely what a pan
   * changes: the documented wiring feeds `InteractionState.viewportOffset` back into this
   * composable, so the first pan increment cancelled the detector that produced it. A continuous
   * pan or pinch was a sequence of one-increment gestures, each starting from a fresh centroid — a
   * chart that stutters and never really follows the finger. It is read through a `State` and kept
   * out of the keys, so the detector sees the current viewport and keeps running.
   */
  viewport: State<Viewport>,
): Modifier {
  if (onTap == null && onLongPress == null && onPan == null && onZoom == null && onHover == null) {
    return this
  }
  return this.then(
      if (onTap == null && onLongPress == null) Modifier
      else
        Modifier.pointerInput(scene, fit, density, hitIndex) {
          fun reported(offset: Offset): Pair<PointD, SceneNodeId?> =
            controllerPoint(offset, scene, fit, density, size.width, size.height) to
              hitIndex
                .hitTest(
                  scenePoint(
                    offset,
                    scene,
                    fit,
                    density,
                    size.width,
                    size.height,
                    viewport.value.offset,
                    viewport.value.scale,
                  )
                )
                ?.node
                ?.id

          detectTapGestures(
            onTap =
              onTap?.let { report ->
                { offset ->
                  val (point, nodeId) = reported(offset)
                  report(point, nodeId)
                }
              },
            onLongPress =
              onLongPress?.let { report ->
                { offset ->
                  val (point, nodeId) = reported(offset)
                  report(point, nodeId)
                }
              },
          )
        }
    )
    .then(
      if (onPan == null && onZoom == null) Modifier
      else
        Modifier.pointerInput(scene, fit, density) {
          // One detector for both, because a pinch is a two-finger drag: separate detectors would
          // each claim the pointers and a two-finger gesture would arrive as one of the two at
          // random. `detectTransformGestures` reports pan, zoom and rotation from the same stream,
          // and `panZoomLock = false` lets a gesture be both — the Android View runs its pan and
          // scale detectors over one stream and lets both fire, and a chart being explored is
          // usually
          // being moved and scaled at once.
          detectTransformGestures(panZoomLock = false) { centroid, pan, zoom, _ ->
            if (onPan != null && (pan.x != 0f || pan.y != 0f)) {
              // **Pixels, undivided.** A pan is a distance, so no centring comes off it; and a
              // controller accumulates it in pixels — `InteractionState.viewportOffset` is what
              // `visibleViewport` divides by `contentScale * viewportScale` — so dividing here
              // would
              // scale it twice and pan by a fraction of the finger. The Android View dispatches the
              // raw pixel distance for the same reason.
              onPan(VectorD(pan.x.toDouble(), pan.y.toDouble()), false)
            }
            if (onZoom != null && zoom != 1f) {
              onZoom(
                zoom.toDouble(),
                controllerPoint(centroid, scene, fit, density, size.width, size.height),
                false,
              )
            }
          }
        }
    )
    .then(
      if (onPan == null && onZoom == null) Modifier
      else
      // **The end of the gesture, which the detector above never reports.**
      //
      // `detectTransformGestures` reports increments and nothing else, so both callbacks were
      // always called with `ended = false` and nothing ever passed true — a parameter documented
      // on both of them and dead on both. `VegaChartController` emits `ChartEvent.ViewportChanged`
      // only on `GesturePhase.ENDED`, which is the whole point of the phase: a host persists or
      // announces a viewport once rather than sixty times a second. On this renderer that event
      // never fired at all.
      //
      // A **second** `pointerInput` rather than a wrapper around the first, because
      // `detectTransformGestures` is a `PointerInputScope` extension and `awaitEachGesture` hands
      // out a restricted scope that cannot call one. Two of them is not a conflict: this one
      // consumes nothing, so it observes the same stream the detector does.
      Modifier.pointerInput(scene, fit, density) {
          awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var moved = false
            var pinched = false
            var centroid = Offset.Zero
            var mostFingers = 0
            while (true) {
              // The **final** pass, so this sees the event after the detector above has had it —
              // which means every position change has already been *consumed* by the detector, and
              // `positionChanged()` reports false for a consumed one. The ignore-consumed spelling
              // is the whole point of a watcher that runs behind another handler.
              val event = awaitPointerEvent(PointerEventPass.Final)
              val down = event.changes.filter { it.pressed }
              if (down.size > 1) pinched = true
              if (event.changes.any { it.positionChangedIgnoreConsumed() }) moved = true
              // The centroid while the gesture was at its widest, so a pinch closes about the point
              // it was actually about. Taken from the *last* event instead, a two-finger pinch
              // closes on wherever the second finger happened to be when the first came up.
              if (down.size >= mostFingers && down.isNotEmpty()) {
                mostFingers = down.size
                centroid =
                  Offset(
                    down.sumOf { it.position.x.toDouble() }.toFloat() / down.size,
                    down.sumOf { it.position.y.toDouble() }.toFloat() / down.size,
                  )
              }
              if (event.changes.none { it.pressed }) break
            }
            // A zero increment and a unit factor: the movement has already been dispatched, and
            // what is being reported is that there will be no more of it. A gesture that never
            // moved was a tap, and closing a pan that never happened would publish a viewport
            // change for it.
            if (!moved) return@awaitEachGesture
            onPan?.invoke(VectorD(0.0, 0.0), true)
            // Only where two fingers were down: a one-finger drag is not a pinch, and reporting one
            // would publish the viewport twice for the same gesture.
            if (pinched) {
              onZoom?.invoke(
                1.0,
                controllerPoint(centroid, scene, fit, density, size.width, size.height),
                true,
              )
            }
          }
        }
    )
    .then(
      if (onHover == null) Modifier
      else
        Modifier.pointerInput(scene, fit, density, hitIndex) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent()
              // **Mouse and stylus only**, which is what this callback documents and what "hover"
              // means: a finger on the glass is a press, not a pointer resting somewhere. Every
              // `Move` was reported, so a touch drag churned the hover state — and with it the
              // tooltip — sixty times a second through the whole of a pan, on a gesture whose
              // pointer is under the reader's own finger and cannot be seen anyway.
              if (event.changes.any { it.type == PointerType.Touch }) continue
              when (event.type) {
                PointerEventType.Move,
                PointerEventType.Enter -> {
                  val offset = event.changes.firstOrNull()?.position ?: continue
                  val reported =
                    controllerPoint(offset, scene, fit, density, size.width, size.height)
                  val hit =
                    hitIndex.hitTest(
                      scenePoint(
                        offset,
                        scene,
                        fit,
                        density,
                        size.width,
                        size.height,
                        viewport.value.offset,
                        viewport.value.scale,
                      )
                    )
                  onHover(reported, hit?.node?.id)
                }
                // A pointer that left says so with a null, which is what clears a hover state. The
                // engine's own `PointerExited` means the same thing.
                PointerEventType.Exit -> onHover(null, null)
                else -> Unit
              }
            }
          }
        }
    )
}

/**
 * A point in the slot, in the space **a controller expects**: pixels with the centring taken off.
 *
 * Not scene coordinates, and the difference is the whole reason this function has a long comment.
 * `VegaChartController.toSceneSpace` documents its own inverse — *subtract the pan offset, then
 * divide by `contentScale * viewportScale`* — so a host that hands it a point which has **already**
 * been divided by the fit scale has it divided twice, and every tap lands at a fraction of where
 * the finger was. What a host must remove is the part the controller cannot know about: the offset
 * a fitted chart is *centred* by. The scale it does know, because the host sets `contentScale` from
 * `onPlaced`.
 *
 * `ChartPlacement.scenePoint` in the Swift renderer is this same function and its header makes the
 * same point; the Android View has no centring to remove, which is why it dispatches raw pixels.
 */
private fun controllerPoint(
  offset: Offset,
  scene: Scene,
  fit: SceneFit,
  density: Float,
  width: Int,
  height: Int,
): PointD {
  // The **fit** placement, with no pan and no zoom in it. The controller subtracts its own
  // `viewportOffset` and divides by its own `viewportScale`, so removing either here would remove
  // it
  // twice — which is a tap that drifts further from the finger the further the chart has been
  // panned.
  val placement = placement(scene, width.toFloat(), height.toFloat(), fit, density)
  return PointD((offset.x - placement.left).toDouble(), (offset.y - placement.top).toDouble())
}

/**
 * The same point in **scene** coordinates, for the hit test this module runs itself.
 *
 * The full inverse, including the pan and the zoom, because the mark reported beside a gesture has
 * to be the mark that was under the finger on screen — not the one that would have been there
 * before the chart was moved.
 */
private fun scenePoint(
  offset: Offset,
  scene: Scene,
  fit: SceneFit,
  density: Float,
  width: Int,
  height: Int,
  viewportOffset: VectorD,
  viewportScale: Double,
): PointD {
  val placement =
    placement(scene, width.toFloat(), height.toFloat(), fit, density, viewportOffset, viewportScale)
  val scale = if (placement.scale == 0f) 1f else placement.scale
  return PointD(
    ((offset.x - placement.left) / scale).toDouble(),
    ((offset.y - placement.top) / scale).toDouble(),
  )
}

private fun placement(
  scene: Scene,
  width: Float,
  height: Float,
  fit: SceneFit,
  density: Float,
  viewportOffset: VectorD = VectorD.Zero,
  viewportScale: Double = 1.0,
): Placement {
  val scale =
    when (fit) {
      // **The density, not one.** A scene unit is a dp, so "its own size" means one scene unit per
      // dp — which is `density` pixels. Drawing at a scale of 1 made the chart come out at a third
      // of
      // its size on a 3x screen while every glyph was drawn at the right size, because text went
      // through `sp` and everything else did not. Both halves of that are fixed here and in
      // `DrawScopeTarget.text`.
      SceneFit.None -> density
      SceneFit.Contain ->
        if (scene.width <= 0.0 || scene.height <= 0.0) {
          density
        } else {
          // Already in pixels on both sides — the slot's size is in pixels and the scene's own size
          // is in dp — so this factor carries the density with it.
          minOf(width / scene.width.toFloat(), height / scene.height.toFloat())
        }
    }
  // Centred in whatever is left over, which is what makes a chart in a slot of the wrong aspect
  // ratio
  // look placed rather than stuck to a corner. `None` means what it says and does not move.
  val fitLeft = if (fit == SceneFit.None) 0f else (width - scene.width.toFloat() * scale) / 2f
  val fitTop = if (fit == SceneFit.None) 0f else (height - scene.height.toFloat() * scale) / 2f
  // Then the **viewport** on top of the fit, in the order the controller documents its own inverse:
  // translate by the pan, and scale by `contentScale * viewportScale`. A pan is accumulated in
  // pixels
  // — `InteractionState.viewportOffset` is what `visibleViewport` divides by that product to get
  // scene
  // units — so it is added to the offset here and not multiplied into it. Without this the state
  // moved
  // and the drawing did not: a pan that made `canReset` true and left the chart where it was.
  return Placement(
    scale = scale * viewportScale.toFloat(),
    left = fitLeft + viewportOffset.dx.toFloat(),
    top = fitTop + viewportOffset.dy.toFloat(),
  )
}

/** The fit alone, for `onPlaced`: what a host sets as `contentScale`, with no pan or zoom in it. */
private fun fitPlacement(
  scene: Scene,
  width: Float,
  height: Float,
  fit: SceneFit,
  density: Float,
): ChartPlacement {
  val placed = placement(scene, width, height, fit, density)
  return ChartPlacement(
    scale = placed.scale.toDouble(),
    left = placed.left.toDouble(),
    top = placed.top.toDouble(),
  )
}

/**
 * An [ImageCache] that outlives a frame.
 *
 * Remembered against nothing, so it survives every recomposition and every redraw of the composable
 * that owns it — which is the point. A draw target is built once per frame, so a cache inside one
 * caches nothing across frames: an engine-produced raster was PNG-decoded on every draw, and a
 * host-supplied resolver would be called on every draw.
 *
 * [VegaChart] calls this for its caller. Hoist it where two charts should share one, or where the
 * image behind a URL can change and the cache has to be cleared.
 */
@Composable
public fun rememberVegaImageCache(maxEntries: Int = 64): ImageCache =
  remember(maxEntries) {
    ImageCache(maxEntries)
  }
