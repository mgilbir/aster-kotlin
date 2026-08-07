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
import dev.aster.vega.runtime.ChartInputEvent
import dev.aster.vega.runtime.ChartKey
import dev.aster.vega.runtime.GesturePhase
import dev.aster.vega.runtime.Modifiers
import dev.aster.vega.runtime.PointerDevice
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.HitTestOptions
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.Scene
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
 * There is deliberately no view, drawable, render node or accessibility view per mark
 * (PROJECT_BRIEF.md 4.3). `onDraw` consumes an already compiled snapshot and does no scene
 * compilation, JSON parsing, transform evaluation or text layout.
 *
 * The Compose API wraps this class rather than reimplementing drawing, so both APIs share identical
 * text metrics, hit testing and accessibility (PROJECT_BRIEF.md 6.1, `vega-compose`).
 */
public open class VegaChartView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
  View(context, attrs, defStyleAttr) {

  private val textEngine = AndroidTextEngine()
  private val renderer = AndroidCanvasSceneRenderer(textEngine)
  private val viewport = RectF()

  /** Revision last drawn; `invalidate()` is only called when this falls behind. */
  private var drawnRevision = Long.MIN_VALUE

  /** Preferred size last reported; `requestLayout()` is only called when it actually changes. */
  private var reportedSize: Pair<Int, Int>? = null

  private val accessibilityHelper = VegaAccessibilityHelper(this)

  /** Watches the current controller's snapshot while the view is attached. */
  private var snapshotObserver: Job? = null

  public var controller: VegaChartController = VegaChartController()
    set(value) {
      field = value
      drawnRevision = Long.MIN_VALUE
      accessibilityHelper.invalidateSemanticTree()
      syncContentScale()
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
  public fun newCompatibleTextEngine(): AndroidTextEngine = AndroidTextEngine()

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
          // GestureDetector reports the distance travelled, which is the negation of the pan.
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
              anchor = PointD(detector.focusX.toDouble(), detector.focusY.toDouble()),
              phase = GesturePhase.CHANGED,
            )
          )
          return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
          dispatchChartEvent(
            ChartInputEvent.Zoom(
              scaleFactor = 1.0,
              anchor = PointD(detector.focusX.toDouble(), detector.focusY.toDouble()),
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
  private fun observeController() {
    snapshotObserver?.cancel()
    if (!isAttachedToWindow) return
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    snapshotObserver = scope.launch {
      controller.state.collect {
        // A new scene can change the fit scale, so refresh it before anything hit-tests.
        syncContentScale()
        accessibilityHelper.invalidateSemanticTree()
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

  override fun onDraw(canvas: Canvas) {
    // Everything below reads an already-built snapshot: no scene compilation, no text layout, no
    // JSON parsing, no large allocations (PROJECT_BRIEF.md 4.5).
    val snapshot = controller.snapshot
    val scene = snapshot.scene
    if (scene.width <= 0.0 || scene.height <= 0.0) return

    viewport.set(
      paddingLeft.toFloat(),
      paddingTop.toFloat(),
      (width - paddingRight).toFloat(),
      (height - paddingBottom).toFloat(),
    )
    if (viewport.width() <= 0f || viewport.height() <= 0f) return

    val fitScale = minOf(viewport.width() / scene.width, viewport.height() / scene.height).toFloat()
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
    drawnRevision = snapshot.revision
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
      MotionEvent.ACTION_UP ->
        dispatchChartEvent(
          ChartInputEvent.PointerUp(
            point = event.toPointD(),
            pointerId = event.getPointerId(0).toLong(),
            device = event.chartDevice(),
            buttons = event.buttonState,
          )
        )
      MotionEvent.ACTION_CANCEL -> dispatchChartEvent(ChartInputEvent.PointerExited(null))
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
    return true
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
    // selection updates (PROJECT_BRIEF.md 8.4).
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

  private fun MotionEvent.toPointD(): PointD =
    PointD((x - paddingLeft).toDouble(), (y - paddingTop).toDouble())

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
  }
}
