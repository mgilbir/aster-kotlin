package dev.aster.vega.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.customview.widget.ExploreByTouchHelper
import dev.aster.vega.model.asString
import dev.aster.vega.runtime.ChartInputEvent
import dev.aster.vega.runtime.ChartKey
import dev.aster.vega.runtime.GesturePhase
import dev.aster.vega.runtime.Modifiers
import dev.aster.vega.runtime.PointerDevice
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.AccessibilityTree
import dev.aster.vega.scene.HitTestOptions
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.ScenePlacement
import dev.aster.vega.scene.VectorD
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The canonical Android host for a chart: one custom [View] drawing one scene through one canvas.
 *
 * There is deliberately no view, drawable, render node or accessibility view per mark (ADR 0009).
 * `onDraw` consumes an already compiled snapshot and does no scene compilation, JSON parsing,
 * transform evaluation or text layout.
 *
 * The Compose API wraps this class rather than reimplementing drawing, so both APIs share identical
 * text metrics, hit testing and accessibility (, `vega-compose`).
 */
public open class VegaChartView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
  View(context, attrs, defStyleAttr) {

  /**
   * Measured with the **reader's** text scale, not the specification's.
   *
   * `fontScale` is what Android's font-size setting moves, and it has to reach the *layout* rather
   * than only the drawing: an axis reserves its label box from a measurement, so text drawn larger
   * inside a box measured smaller is what makes labels overlap. The parameter existed and nothing
   * ever set it, which an external review pointed out — for a reader-facing chart that is not
   * chrome.
   *
   * A change to the setting restarts the activity unless an app declares
   * `android:configChanges="fontScale"`, and a restart rebuilds this view and this engine. An app
   * that *does* declare it keeps the old scale until it builds a new engine and recompiles, which
   * is the note on [newCompatibleTextEngine].
   */
  private var textEngine = AndroidTextEngine(context.resources.configuration.fontScale)

  /**
   * Every seam the renderer takes, applied in one place.
   *
   * It was constructed at a field initialiser and again in [fontResolver]'s setter, and a seam
   * added to one and not the other is silently dropped for whichever host set the other first.
   * There are three of them now.
   */
  private fun newRenderer(): AndroidCanvasSceneRenderer =
    AndroidCanvasSceneRenderer(
      textEngine = textEngine,
      imageResolver = imageResolver,
      onUnresolvedImage = { url -> onUnresolvedImage?.invoke(url) },
    )

  /**
   * Turns an image mark's URL into a bitmap, or null where there is nothing to show.
   *
   * The renderer has taken one of these since it could draw an `image` mark and **nothing outside
   * this module could set it**, so every image mark on a View or Compose host resolved to nothing
   * and reported `EXPORT_IMAGE_UNRESOLVED`. The Compose Multiplatform renderer grew the same seam
   * for 0.2.0; this is the other half of it, and the gap was reported from outside (#99).
   *
   * A URL is asked **once**, not once per frame, and a refusal is remembered too — see
   * [clearImageCache], which is how a host says an address now holds something different.
   *
   * Called from the draw, on the main thread. A resolver that fetches should answer from a cache
   * and start the fetch elsewhere, then call [clearImageCache] when it lands.
   */
  public var imageResolver: AndroidImageResolver = AndroidImageResolver.None
    set(value) {
      if (field === value) return
      field = value
      renderer = newRenderer()
      invalidate()
    }

  /**
   * Told the first time an image mark's URL cannot be resolved, and not again for that URL.
   *
   * The hole in the chart is otherwise all a host gets, since a diagnostic has to be read out of
   * the controller and correlated by hand. Matches `onUnresolvedImage` on the Compose Multiplatform
   * chart, so the same host code works on either renderer.
   *
   * Set freely: unlike [imageResolver] this does not rebuild the renderer, because the renderer
   * calls back through this property rather than capturing it.
   */
  public var onUnresolvedImage: ((String) -> Unit)? = null

  /**
   * Built **after** every property it reads, and that placement is load-bearing.
   *
   * Kotlin runs property initialisers in declaration order, so a renderer constructed above
   * [imageResolver] would be handed that field before it had been assigned — a null arriving where
   * the type says it cannot be. Nothing in the compiler catches it, because the read happens inside
   * [newRenderer] rather than in the initialiser itself.
   */
  private var renderer = newRenderer()

  /**
   * Forgets every resolved and refused address, so the next draw asks [imageResolver] again.
   *
   * For an image that has changed behind its URL, and for a fetch that failed once and may not fail
   * twice.
   */
  public fun clearImageCache() {
    renderer.clearImageCache()
    invalidate()
  }

  /**
   * A face this app ships, by the family name a specification asks for; null leaves it to Android.
   *
   * Android resolves a family name against the *system's* families, so a bundled font — which most
   * design systems ship — could not reach a chart at all: the name finds nothing and the default is
   * used, silently. The Compose renderer has taken a resolver for this since it got a text engine;
   * this is the same seam, and until now the same specification drew in the app's face on one
   * renderer and not the other.
   *
   * Set it **before** the first compile. Text metrics are decided when a specification is compiled,
   * so a chart already on screen was measured with whatever this was then; setting it rebuilds this
   * view's engine and repaints, and a host that changes it afterwards recompiles.
   * [newCompatibleTextEngine] carries it, so a controller compiling off the main thread measures
   * with the same faces this view draws with.
   */
  public var fontResolver: ((String) -> android.graphics.Typeface?)? = null
    set(value) {
      if (field === value) return
      field = value
      textEngine = newCompatibleTextEngine()
      renderer = newRenderer()
      invalidate()
    }

  private val viewport = RectF()

  /** Revision last drawn; `invalidate()` is only called when this falls behind. */
  private var drawnRevision = Long.MIN_VALUE

  /** Preferred size last reported; `requestLayout()` is only called when it actually changes. */
  private var reportedSize: Pair<Int, Int>? = null

  private val accessibilityHelper = VegaAccessibilityHelper(this)

  /**
   * How many **data marks** a screen reader may explore one by one before a summary stands in for
   * them.
   *
   * [AccessibilityTree.MAX_EXPOSED_MARKS] by default, which is the engine's judgement rather than a
   * fact: a host knows things it does not — the size of the screen, whether the chart is the page
   * or a thumbnail on it, what its own users have said. The guides are exposed either way, so
   * raising this trades a longer swipe list for per-mark exploration and lowering it does the
   * reverse.
   *
   * Only marks count, so a chart of many small points does not cross it on the strength of its axis
   * labels.
   */
  public var accessibilityMaxExposedMarks: Int = AccessibilityTree.MAX_EXPOSED_MARKS
    set(value) {
      if (field == value) return
      field = value
      accessibilityHelper.invalidateSemanticTree()
    }

  /** Watches the current controller's snapshot while the view is attached. */
  private var snapshotObserver: Job? = null

  public var controller: VegaChartController = VegaChartController()
    set(value) {
      field = value
      drawnRevision = Long.MIN_VALUE
      accessibilityHelper.invalidateSemanticTree()
      syncContentScale()
      reportPlacement()
      observeController()
      updatePreferredSize()
      applyChartDescription()
      invalidate()
    }

  /**
   * Announces what the chart *is*, before a screen reader starts reading its marks.
   *
   * Taken from the specification's own `description`, which every fixture in this repository
   * already carries and nothing read until TalkBack was pointed at the demo. Without it a reader
   * hears a list of labelled values and is never told they are a chart, let alone of what — the
   * difference between "Jan 28, Feb 55" and "Monthly rainfall. Jan 28, Feb 55".
   *
   * Only set when the specification supplies one; a host that set its own `contentDescription` for
   * a hand-built scene keeps it.
   */
  private fun applyChartDescription() {
    val description = controller.lastCompiled?.spec?.description ?: return
    contentDescription = description
  }

  /**
   * The engine this view measures and draws with. Use it to build a scene by hand.
   *
   * Do **not** hand it to a [VegaChartController] that compiles specifications off the main thread:
   * this instance is in use whenever the view paints, and it keeps one shared `TextPaint`. Call
   * [newCompatibleTextEngine] for that instead.
   */
  public val chartTextEngine: AndroidTextEngine
    get() = textEngine

  /**
   * A second engine measuring exactly as this view's does, safe to use on another thread.
   *
   * Measurement depends only on the text style and the font scale, so two engines configured alike
   * are interchangeable — which is what makes compiling on a background thread safe without either
   * side locking. Two *threads* sharing one engine is what is not safe.
   */
  /**
   * An exporter that draws with **this view's** seams: its text engine, its faces, its images.
   *
   * `SceneExporter()` builds a fresh renderer, and a fresh one measures at font scale 1, knows none
   * of the host's faces and resolves no image — so an export taken that way is not the chart on
   * screen, which is the one thing the exporter promises. Every host was reaching for the default
   * because reaching for this needed a renderer nothing exposed.
   */
  public fun exporter(): SceneExporter = SceneExporter(newRenderer())

  public fun newCompatibleTextEngine(): AndroidTextEngine =
    // The **same font scale and the same faces**, or the claim above is false: an engine measuring
    // at
    // 1 while this view draws at 1.3 lays every label out in a box that is too small, and one
    // resolving a family the platform's way while this view draws the app's own face measures the
    // wrong advances. Both are the defect the seams exist to avoid, arriving through the seam meant
    // to prevent it.
    AndroidTextEngine(
      context.resources.configuration.fontScale,
      fontResolver ?: { null },
    )

  init {
    isFocusable = true
    isFocusableInTouchMode = true
    // One virtual-node provider for the whole chart; see VegaAccessibilityHelper.
    androidx.core.view.ViewCompat.setAccessibilityDelegate(this, accessibilityHelper)
  }

  private val gestureDetector =
    GestureDetector(
      context,
      object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
          dispatchChartEvent(ChartInputEvent.Tap(e.toPointD()))
          return true
        }

        override fun onLongPress(e: MotionEvent) {
          dispatchChartEvent(ChartInputEvent.LongPress(e.toPointD()))
        }

        override fun onScroll(
          e1: MotionEvent?,
          e2: MotionEvent,
          distanceX: Float,
          distanceY: Float,
        ): Boolean {
          // A chart whose specification brushes wants the drag for itself; see [panEnabled].
          if (!panEnabled) return false
          // GestureDetector reports the distance travelled, which is the negation of the pan.
          panning = true
          dispatchChartEvent(
            ChartInputEvent.Pan(
              VectorD(-distanceX.toDouble(), -distanceY.toDouble()),
              GesturePhase.CHANGED,
            )
          )
          return true
        }
      },
    )

  private val scaleDetector =
    ScaleGestureDetector(
      context,
      object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
          dispatchChartEvent(
            ChartInputEvent.Zoom(
              scaleFactor = detector.scaleFactor.toDouble(),
              // **Placement-relative**, like every other point this view dispatches. The detector
              // reports the focus in raw view coordinates, and those were passed straight through
              // while `toPointD` takes the placement's origin off — so on any chart that is padded
              // or centred in its slot, a pinch zoomed about a point offset from the reader's
              // fingers by exactly that origin.
              anchor = placedPoint(detector.focusX, detector.focusY),
              phase = GesturePhase.CHANGED,
            )
          )
          return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
          dispatchChartEvent(
            ChartInputEvent.Zoom(
              scaleFactor = 1.0,
              anchor = placedPoint(detector.focusX, detector.focusY),
              phase = GesturePhase.ENDED,
            )
          )
        }
      },
    )

  /** Replaces the scene. Convenience for `controller.setScene`. */
  public fun setScene(scene: Scene) {
    controller.setScene(scene)
    syncContentScale()
    accessibilityHelper.invalidateSemanticTree()
    updatePreferredSize()
    invalidate()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    observeController()
  }

  override fun onDetachedFromWindow() {
    snapshotObserver?.cancel()
    snapshotObserver = null
    super.onDetachedFromWindow()
  }

  /**
   * Subscribes to the controller's snapshot so a scene or interaction change made through the
   * controller — rather than through this view — still repaints.
   *
   * Without this, code that calls `controller.setScene(...)` directly (which is the normal pattern,
   * and what the Compose wrapper does) would update the snapshot with nothing telling the view to
   * redraw. The observer only ever calls `invalidateIfStale()`, so a revision the view has already
   * drawn costs nothing.
   */
  /** What the semantic tree looked like when it was last published. See [observeController]. */
  private var lastSemanticIdentity: List<Any?>? = null

  private fun observeController() {
    snapshotObserver?.cancel()
    if (!isAttachedToWindow) return
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    snapshotObserver = scope.launch {
      controller.state.collect {
        // A new scene can change the fit scale, so refresh it before anything hit-tests.
        syncContentScale()
        // And a host holding an overlay has to be told, for the same reason: the scale it was given
        // described the previous scene's size.
        reportPlacement()
        // **Only when the tree actually changed.** A pan publishes a snapshot per frame, and
        // rebuilding the virtual view tree on each one makes TalkBack re-announce the chart
        // continuously — the marks are the same marks in the same order, and only their frames
        // moved. `ExploreByTouchHelper` re-reads a node's bounds when it draws focus, so a moved
        // frame needs no invalidation; what needs one is a mark appearing, disappearing, changing
        // its description or changing its selected state.
        val tree = accessibilityHelper.semanticIdentity()
        if (tree != lastSemanticIdentity) {
          lastSemanticIdentity = tree
          accessibilityHelper.invalidateSemanticTree()
        }
        updatePreferredSize()
        applyChartDescription()
        invalidateIfStale()
      }
    }
  }

  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    val scene = controller.snapshot.scene
    val density = resources.displayMetrics.density
    val desiredWidth = (scene.width * density).roundToInt() + paddingLeft + paddingRight
    val desiredHeight = (scene.height * density).roundToInt() + paddingTop + paddingBottom
    setMeasuredDimension(
      resolveSize(desiredWidth.coerceAtLeast(suggestedMinimumWidth), widthMeasureSpec),
      resolveSize(desiredHeight.coerceAtLeast(suggestedMinimumHeight), heightMeasureSpec),
    )
  }

  override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
    super.onSizeChanged(width, height, oldWidth, oldHeight)
    syncContentScale()
    reportPlacement()
    controller.dispatch(
      ChartInputEvent.Resized(
        width = width.toDouble(),
        height = height.toDouble(),
        pixelScale = resources.displayMetrics.density.toDouble(),
      )
    )
  }

  /**
   * Tells the controller how much this view scales the scene, so hit testing can invert it.
   *
   * Without this the controller would hit-test in device pixels against scene-space geometry, and
   * every tap would miss by exactly the fit scale.
   */
  private fun syncContentScale() {
    controller.contentScale = fitScaleFor(controller.snapshot.scene)
  }

  /**
   * Where the chart is drawn inside this view: the fit scale, and the offset from the view's
   * top-left corner.
   *
   * **One placement, read by everything that has to agree about it.** The origin was written out
   * four separate times — the draw's viewport, a touch's conversion to scene coordinates, and the
   * accessibility helper's two mappings — each spelling it `paddingLeft`/`paddingTop` and each free
   * to drift from the others. A second copy of this arithmetic is how a reader's finger lands
   * beside the mark it looked like it hit, which this project has had twice.
   *
   * The **fit alone**, with no pan or zoom in it: `InteractionState` carries those and the
   * controller applies them, so folding them in here would apply each twice.
   *
   * **Centred in whatever is left over**, which the Compose Multiplatform and SwiftUI renderers
   * have always done and this one did not. A scene is scaled to fit, so a slot of a different
   * aspect ratio leaves a strip along one axis; this view used to put all of it on the right and
   * the bottom, and the same chart in the same slot therefore sat in a different place depending on
   * which host drew it. `SceneFit.Contain` on the Compose side calls centring what "makes a chart
   * in a slot of the wrong aspect ratio look placed rather than stuck to a corner", and that was as
   * true here.
   *
   * A view measured at its own preferred size has nothing left over and does not move, which is
   * most of them; a chart given `match_parent` on an axis moves by half the slack.
   */
  public fun placement(): ScenePlacement {
    val scene = controller.snapshot.scene
    val scale = fitScaleFor(scene)
    val availableWidth = (width - paddingLeft - paddingRight).toDouble()
    val availableHeight = (height - paddingTop - paddingBottom).toDouble()
    // Never negative. The fit scale has already made the scene no larger than the box, and a box
    // with
    // no room at all leaves the origin at the padding rather than pulling the drawing inwards.
    val slackX = (availableWidth - scene.width * scale).coerceAtLeast(0.0)
    val slackY = (availableHeight - scene.height * scale).coerceAtLeast(0.0)
    return ScenePlacement(
      scale = scale,
      left = paddingLeft + slackX / 2.0,
      top = paddingTop + slackY / 2.0,
    )
  }

  /**
   * Told where the chart was drawn, whenever that changes.
   *
   * The same seam the Compose Multiplatform and SwiftUI charts have, and the reason it was missing
   * here is worth recording: `ScenePlacement` was declared in `vega-compose-multiplatform`, which a
   * `View` cannot depend on. It lives in `vega-scene` now.
   *
   * For a host putting its own overlay on the chart, or turning a point of its own into scene
   * coordinates. Fired on a size change and after a compile, not per frame, and only when the
   * numbers actually differ.
   */
  public var onPlaced: ((ScenePlacement) -> Unit)? = null
    set(value) {
      field = value
      // A host that sets this after the view is laid out has missed the report that already
      // happened, and would otherwise wait for a resize that may never come.
      reportPlacement()
    }

  /** The placement last handed to [onPlaced], so an unchanged one is not reported again. */
  private var reportedPlacement: ScenePlacement? = null

  private fun reportPlacement() {
    val callback = onPlaced ?: return
    if (width <= 0 || height <= 0) return
    val current = placement()
    if (current == reportedPlacement) return
    reportedPlacement = current
    callback(current)
  }

  /** Uniform scale that fits [scene] inside the padded content box. */
  private fun fitScaleFor(scene: Scene): Double {
    val availableWidth = (width - paddingLeft - paddingRight).toDouble()
    val availableHeight = (height - paddingTop - paddingBottom).toDouble()
    if (
      scene.width <= 0.0 || scene.height <= 0.0 || availableWidth <= 0.0 || availableHeight <= 0.0
    ) {
      return 1.0
    }
    return minOf(availableWidth / scene.width, availableHeight / scene.height)
  }

  /**
   * Whether this view draws the tooltip itself.
   *
   * Off is the right setting for a host that renders its own — the controller still publishes the
   * datum and emits `TooltipChanged`, which is where a richer presentation belongs.
   */
  public var tooltipsEnabled: Boolean = true
    set(value) {
      field = value
      invalidate()
    }

  /**
   * Whether a drag moves the **viewport**.
   *
   * On by default, and worth turning off for a chart whose *specification* uses the drag — a brush,
   * or any `[mousedown, mouseup] > mousemove` handler. Both happen otherwise, and they fight: the
   * viewport slides under the finger by the same distance the finger travels, so in the chart's own
   * coordinates the pointer never moves and the brush selects an empty interval. A drag that both
   * pans and brushes is not a compromise between the two, it is neither.
   *
   * The viewport pan is **this engine's own idea** rather than something a specification asked for
   * — it produces no Vega event at all — which is why it is the one that yields. Upstream has no
   * viewport to pan, so a browser gives the whole drag to the chart.
   *
   * `ChartGestures.pan` is the SwiftUI counterpart: a host there asks for `[.tap, .pointer]` to get
   * the same thing. Left as a host decision on both rather than inferred from the specification,
   * because "does this chart use drags" is a question about handlers that a view should not be
   * guessing at.
   */
  public var panEnabled: Boolean = true

  private val tooltipRect = RectF()

  /** The icon currently set, so an unchanged cursor does not churn the window's pointer. */
  private var appliedCursor: Int? = null

  private fun asStringOf(value: dev.aster.vega.model.VegaValue): String = value.asString()

  private val tooltipFillPaint =
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
      style = android.graphics.Paint.Style.FILL
      color = android.graphics.Color.argb(242, 255, 255, 255)
    }

  private val tooltipStrokePaint =
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
      style = android.graphics.Paint.Style.STROKE
      color = android.graphics.Color.argb(255, 187, 187, 187)
      strokeWidth = 1f
    }

  private val tooltipTextPaint =
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
      color = android.graphics.Color.argb(255, 34, 34, 34)
    }

  override fun onDraw(canvas: Canvas) {
    // Everything below reads an already-built snapshot: no scene compilation, no text layout, no
    // JSON parsing, no large allocations (ADR 0009).
    val snapshot = controller.snapshot
    val scene = snapshot.scene
    if (scene.width <= 0.0 || scene.height <= 0.0) return

    // The origin comes from `placement()`, which is what a host is told and what a touch is
    // converted through. Writing `paddingLeft` here again is how the three drift apart.
    //
    // **Both corners**, and the far one used to be the padding box's rather than the drawing's:
    // `width - paddingRight`. `placement()` centres the scene in whatever the fit leaves over, so
    // the far edges were out by the whole of that slack — all of it on the right and the bottom.
    // Three things followed. A scene with an opaque background — Vega-Lite gives every chart
    // `"background": "white"` — painted the right and bottom slack and not the left and top, so a
    // chart on a dark surface had a white margin down two of its four sides. The clip let a zoomed
    // chart's content escape there. And the fit scale below was recomputed from the *wrong* box, so
    // it disagreed with the one `placement()` reports to the host and to every touch.
    val placed = placement()
    viewport.set(
      placed.left.toFloat(),
      placed.top.toFloat(),
      (placed.left + scene.width * placed.scale).toFloat(),
      (placed.top + scene.height * placed.scale).toFloat(),
    )
    if (viewport.width() <= 0f || viewport.height() <= 0f) return

    val fitScale = placed.scale.toFloat()
    val interaction = snapshot.interactionState

    val saveCount = canvas.save()
    try {
      canvas.translate(
        interaction.viewportOffset.dx.toFloat(),
        interaction.viewportOffset.dy.toFloat(),
      )
      renderer.render(
        scene = scene,
        canvas = canvas,
        viewport = viewport,
        pixelScale = fitScale * interaction.viewportScale.toFloat(),
      )
    } finally {
      canvas.restoreToCount(saveCount)
    }
    if (tooltipsEnabled) drawTooltip(canvas, interaction)
    applyCursor(interaction.cursor)
    drawnRevision = snapshot.revision
  }

  /**
   * Sets the pointer shape the item under the pointer asked for.
   *
   * A `cursor` channel is a CSS name, and Android has a fixed set of system icons rather than a
   * string — so the mapping is by name, and a name Android has no icon for leaves the pointer alone
   * rather than resetting it to the arrow. Only meaningful where there is a pointer at all: a
   * finger has no shape, and `setPointerIcon` is simply ignored for one.
   */
  private fun applyCursor(cursor: String?) {
    val icon = cursor?.let { pointerIconFor(it) }
    if (icon == appliedCursor) return
    appliedCursor = icon
    pointerIcon = icon?.let { android.view.PointerIcon.getSystemIcon(context, it) }
  }

  /** CSS cursor names to Android's system icons, for the ones that correspond. */
  private fun pointerIconFor(name: String): Int? =
    when (name.lowercase()) {
      "default" -> android.view.PointerIcon.TYPE_ARROW
      "pointer" -> android.view.PointerIcon.TYPE_HAND
      "crosshair" -> android.view.PointerIcon.TYPE_CROSSHAIR
      "text" -> android.view.PointerIcon.TYPE_TEXT
      "vertical-text" -> android.view.PointerIcon.TYPE_VERTICAL_TEXT
      "wait" -> android.view.PointerIcon.TYPE_WAIT
      "progress" -> android.view.PointerIcon.TYPE_WAIT
      "help" -> android.view.PointerIcon.TYPE_HELP
      "cell" -> android.view.PointerIcon.TYPE_CROSSHAIR
      "copy" -> android.view.PointerIcon.TYPE_COPY
      "alias" -> android.view.PointerIcon.TYPE_ALIAS
      "no-drop",
      "not-allowed" -> android.view.PointerIcon.TYPE_NO_DROP
      "grab" -> android.view.PointerIcon.TYPE_GRAB
      "grabbing" -> android.view.PointerIcon.TYPE_GRABBING
      "all-scroll" -> android.view.PointerIcon.TYPE_ALL_SCROLL
      "col-resize" -> android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
      "row-resize" -> android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
      "ew-resize" -> android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
      "ns-resize" -> android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
      "nesw-resize" -> android.view.PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW
      "nwse-resize" -> android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW
      "zoom-in" -> android.view.PointerIcon.TYPE_ZOOM_IN
      "zoom-out" -> android.view.PointerIcon.TYPE_ZOOM_OUT
      "none" -> android.view.PointerIcon.TYPE_NULL
      else -> null
    }

  /**
   * Draws the tooltip for whatever the pointer is on.
   *
   * Vega itself does not draw one — it publishes the datum and leaves the presentation to the host,
   * which in a browser is the separate `vega-tooltip` library. That division is right, and it still
   * left this view showing nothing at all for a specification that asks for a tooltip, which is
   * not. So there is a plain default here and a switch to turn it off for a host that wants its
   * own.
   *
   * Drawn **outside** the scene transform, in view coordinates, because a tooltip is chrome: it
   * does not scale with a pinch and it does not move with a pan.
   */
  private fun drawTooltip(canvas: Canvas, interaction: dev.aster.vega.runtime.InteractionState) {
    val datum = interaction.tooltip ?: return
    val anchor = interaction.tooltipAnchor ?: return
    val lines = tooltipLines(datum)
    if (lines.isEmpty()) return

    val pad = TOOLTIP_PADDING * resources.displayMetrics.density
    // `density`, not the deprecated `scaledDensity`: a tooltip is chrome and sized against the
    // chart it annotates, so it should not grow with the reader's font-scale setting while the
    // marks stay put.
    tooltipTextPaint.textSize = TOOLTIP_TEXT_SP * resources.displayMetrics.density
    val lineHeight = tooltipTextPaint.fontSpacing
    val textWidth = lines.maxOf { tooltipTextPaint.measureText(it) }
    val boxWidth = textWidth + 2 * pad
    val boxHeight = lineHeight * lines.size + 2 * pad

    // **Back into view coordinates first.** `interactionState.tooltipAnchor` is the point the host
    // dispatched, which `toPointD` had already made placement-relative; this canvas is not — the
    // draw restores the identity transform before the tooltip so the bubble is drawn in device
    // pixels. So the bubble sat off by exactly the placement's origin on every padded or centred
    // chart, which is every chart given `match_parent` in a slot of a different aspect ratio.
    val placed = placement()
    val anchorX = (anchor.x + placed.left).toFloat()
    val anchorY = (anchor.y + placed.top).toFloat()

    // Placed above and right of the pointer, and flipped when that would leave the view.
    var left = anchorX + pad
    var top = anchorY - boxHeight - pad
    if (left + boxWidth > width.toFloat()) left = anchorX - boxWidth - pad
    if (left < 0f) left = 0f
    if (top < 0f) top = anchorY + pad
    if (top + boxHeight > height.toFloat()) top = (height.toFloat() - boxHeight).coerceAtLeast(0f)

    tooltipRect.set(left, top, left + boxWidth, top + boxHeight)
    val radius = TOOLTIP_RADIUS * resources.displayMetrics.density
    canvas.drawRoundRect(tooltipRect, radius, radius, tooltipFillPaint)
    canvas.drawRoundRect(tooltipRect, radius, radius, tooltipStrokePaint)

    var baseline = top + pad - tooltipTextPaint.fontMetrics.top
    for (line in lines) {
      canvas.drawText(line, left + pad, baseline, tooltipTextPaint)
      baseline += lineHeight
    }
  }

  /**
   * A datum as the lines of a tooltip.
   *
   * An object becomes one `name: value` line per field, which is what upstream's tooltip library
   * shows; anything else is a single line. Numbers are written the way a reader expects rather than
   * the way a `Double` prints, so a count of three does not read "3.0".
   */
  private fun tooltipLines(datum: dev.aster.vega.model.VegaValue): List<String> =
    when (datum) {
      is dev.aster.vega.model.VegaValue.Obj ->
        datum.fields.entries.take(TOOLTIP_MAX_ROWS).map { (key, value) ->
          "$key: ${tooltipValue(value)}"
        }
      is dev.aster.vega.model.VegaValue.Null -> emptyList()
      else -> listOf(tooltipValue(datum))
    }

  private fun tooltipValue(value: dev.aster.vega.model.VegaValue): String =
    when (value) {
      is dev.aster.vega.model.VegaValue.Num -> dev.aster.vega.scene.spokenNumber(value.value)
      is dev.aster.vega.model.VegaValue.Null -> "-"
      is dev.aster.vega.model.VegaValue.Obj,
      is dev.aster.vega.model.VegaValue.Arr -> "…"
      else -> asStringOf(value)
    }

  /** Call after the controller's snapshot changed; only invalidates when the revision moved. */
  public fun invalidateIfStale() {
    if (controller.snapshot.revision != drawnRevision) invalidate()
  }

  @Suppress(
    "ClickableViewAccessibility"
  ) // Accessibility actions come from VegaAccessibilityHelper.
  override fun onTouchEvent(event: MotionEvent): Boolean {
    controller.setHitTestOptions(hitOptionsFor(event))
    val scaleHandled = scaleDetector.onTouchEvent(event)
    val gestureHandled = gestureDetector.onTouchEvent(event)

    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN ->
        dispatchChartEvent(
          ChartInputEvent.PointerDown(
            point = event.toPointD(),
            pointerId = event.getPointerId(0).toLong(),
            device = event.chartDevice(),
            buttons = event.buttonState,
          )
        )
      MotionEvent.ACTION_UP -> {
        endPan()
        dispatchChartEvent(
          ChartInputEvent.PointerUp(
            point = event.toPointD(),
            pointerId = event.getPointerId(0).toLong(),
            device = event.chartDevice(),
            buttons = event.buttonState,
          )
        )
      }
      // **A finger that is down and moving.** This was `else -> Unit`, so a drag emitted its
      // `mousedown` and its `mouseup` and nothing in between — and `mousemove` between the two is
      // the whole of `[mousedown, mouseup] > mousemove`, which is how every brush and every
      // interval selection in Vega is written. `PointerMoved` was reached only from
      // `onHoverEvent`, which fires for a pointer that is *not* pressed, so the one gesture that
      // needed it was the one gesture that could not produce it.
      //
      // A browser fires `mousemove` throughout a drag and updates what is under the pointer as it
      // goes, which is what `dispatch` does with this; the pan gesture is unaffected, since the
      // detectors above see the same `MotionEvent` and this is dispatched beside them exactly as
      // the down and the up already were.
      MotionEvent.ACTION_MOVE -> dispatchChartEvent(ChartInputEvent.PointerMoved(event.toPointD()))
      MotionEvent.ACTION_CANCEL -> {
        endPan()
        dispatchChartEvent(ChartInputEvent.PointerExited(null))
      }
      else -> Unit
    }
    return scaleHandled || gestureHandled || super.onTouchEvent(event)
  }

  override fun onHoverEvent(event: MotionEvent): Boolean {
    controller.setHitTestOptions(HitTestOptions.Mouse)
    when (event.actionMasked) {
      MotionEvent.ACTION_HOVER_ENTER ->
        dispatchChartEvent(ChartInputEvent.PointerEntered(event.toPointD()))
      MotionEvent.ACTION_HOVER_MOVE ->
        dispatchChartEvent(ChartInputEvent.PointerMoved(event.toPointD()))
      MotionEvent.ACTION_HOVER_EXIT ->
        dispatchChartEvent(ChartInputEvent.PointerExited(event.toPointD()))
      else -> return super.onHoverEvent(event)
    }
    return true
  }

  override fun onGenericMotionEvent(event: MotionEvent): Boolean {
    if (event.actionMasked == MotionEvent.ACTION_SCROLL) {
      val vertical = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
      if (vertical != 0f) {
        // A wheel notch is a fixed multiplicative step, which keeps zoom symmetric.
        val factor = if (vertical > 0) WHEEL_ZOOM_STEP else 1.0 / WHEEL_ZOOM_STEP
        dispatchChartEvent(ChartInputEvent.Zoom(factor, event.toPointD(), GesturePhase.ENDED))
        return true
      }
    }
    return super.onGenericMotionEvent(event)
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    val key =
      when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> ChartKey.ARROW_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> ChartKey.ARROW_RIGHT
        KeyEvent.KEYCODE_DPAD_UP -> ChartKey.ARROW_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> ChartKey.ARROW_DOWN
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER -> ChartKey.ENTER
        KeyEvent.KEYCODE_SPACE -> ChartKey.SPACE
        KeyEvent.KEYCODE_ESCAPE -> ChartKey.ESCAPE
        KeyEvent.KEYCODE_TAB -> ChartKey.TAB
        KeyEvent.KEYCODE_MOVE_HOME -> ChartKey.HOME
        KeyEvent.KEYCODE_MOVE_END -> ChartKey.END
        else -> return super.onKeyDown(keyCode, event)
      }
    dispatchChartEvent(
      ChartInputEvent.Key(
        key,
        Modifiers(
          shift = event.isShiftPressed,
          control = event.isCtrlPressed,
          alt = event.isAltPressed,
          meta = event.isMetaPressed,
        ),
      )
    )
    // **Reported, not consumed.** `VegaChartController.dispatch` has no built-in behaviour for a
    // key and no event stream reaches one either — `fireSignalHandlers` maps only the pointer
    // family — so returning true claimed a key the chart then did nothing with. That is a focus
    // trap: TAB never moved focus off the chart, ESC never dismissed the sheet it was in, and HOME
    // and END never scrolled the list it was in. On a television, where the d-pad *is* the
    // keyboard, the four arrows meant the chart could be entered and not left.
    //
    // A host that wants a key still gets one, on `ChartEvent`s, because the dispatch above happens
    // either way. When a `keydown` stream is implemented this should consume what a specification
    // actually listens for, and nothing else.
    return super.onKeyDown(keyCode, event)
  }

  override fun dispatchHoverEvent(event: MotionEvent): Boolean =
    accessibilityHelper.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)

  override fun dispatchKeyEvent(event: KeyEvent): Boolean =
    accessibilityHelper.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)

  override fun onFocusChanged(
    gainFocus: Boolean,
    direction: Int,
    previouslyFocusedRect: android.graphics.Rect?,
  ) {
    super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    accessibilityHelper.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
  }

  /** Applies an input event and invalidates only when the visible revision moved. */
  private fun dispatchChartEvent(event: ChartInputEvent) {
    val before = controller.snapshot.revision
    controller.dispatch(event)
    if (controller.snapshot.revision != before) invalidate()
  }

  private fun updatePreferredSize() {
    val scene = controller.snapshot.scene
    val density = resources.displayMetrics.density
    val size = (scene.width * density).roundToInt() to (scene.height * density).roundToInt()
    // requestLayout() is only for a genuine preferred-size change, never for hover, tooltip or
    // selection updates (ADR 0002).
    if (reportedSize != size) {
      reportedSize = size
      requestLayout()
    }
  }

  private fun hitOptionsFor(event: MotionEvent): HitTestOptions =
    when (event.chartDevice()) {
      PointerDevice.MOUSE -> HitTestOptions.Mouse
      else -> HitTestOptions.Touch
    }

  /** Whether a scroll gesture is in flight, so the finger lifting can close it. See [endPan]. */
  private var panning = false

  /**
   * Closes a pan when the finger lifts, which is what makes `ChartEvent.ViewportChanged` fire.
   *
   * `GestureDetector` has no "scroll ended" callback, so every pan increment was dispatched as
   * `CHANGED` and the `ENDED` that ends the gesture was never sent by this view at all. The
   * controller emits `ViewportChanged` only on `ENDED` — that is the whole point of the phase, so a
   * host can persist or announce a viewport once rather than sixty times a second — so the event
   * never fired here. A zero delta, because the movement has already been dispatched; what is being
   * reported is the end of it.
   */
  private fun endPan() {
    if (!panning) return
    panning = false
    dispatchChartEvent(ChartInputEvent.Pan(VectorD(0.0, 0.0), GesturePhase.ENDED))
  }

  private fun MotionEvent.toPointD(): PointD = placedPoint(x, y)

  /**
   * A raw view coordinate in the space a controller expects: the placement's origin taken off.
   *
   * The same origin the draw uses — see `placement()` — and the only conversion in this file, so a
   * new dispatch site cannot get it wrong by writing `paddingLeft` again. A `ScaleGestureDetector`
   * reports in view coordinates rather than in `MotionEvent`s, which is why this is separate from
   * `toPointD`.
   */
  internal fun placedPoint(x: Float, y: Float): PointD {
    val placed = placement()
    return PointD(x - placed.left, y - placed.top)
  }

  private fun MotionEvent.chartDevice(): PointerDevice =
    when (getToolType(0)) {
      MotionEvent.TOOL_TYPE_MOUSE -> PointerDevice.MOUSE
      MotionEvent.TOOL_TYPE_STYLUS,
      MotionEvent.TOOL_TYPE_ERASER -> PointerDevice.STYLUS
      MotionEvent.TOOL_TYPE_FINGER -> PointerDevice.TOUCH
      else -> PointerDevice.UNKNOWN
    }

  internal fun accessibilityHelperForTesting(): ExploreByTouchHelper = accessibilityHelper

  public companion object {
    /** Multiplicative zoom applied per mouse-wheel notch. */
    public const val WHEEL_ZOOM_STEP: Double = 1.15

    /** Density-independent, because a tooltip is chrome and reads at one size on every screen. */
    private const val TOOLTIP_PADDING = 6f
    private const val TOOLTIP_RADIUS = 4f
    private const val TOOLTIP_TEXT_SP = 12f

    /** A datum with fifty columns is a table, not a tooltip. */
    private const val TOOLTIP_MAX_ROWS = 12
  }
}
