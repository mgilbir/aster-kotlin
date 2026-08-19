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
  private var renderer = AndroidCanvasSceneRenderer(textEngine)

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
      renderer = AndroidCanvasSceneRenderer(textEngine)
      invalidate()
    }

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

    // Placed above and right of the pointer, and flipped when that would leave the view.
    var left = anchor.x.toFloat() + pad
    var top = anchor.y.toFloat() - boxHeight - pad
    if (left + boxWidth > width.toFloat()) left = anchor.x.toFloat() - boxWidth - pad
    if (left < 0f) left = 0f
    if (top < 0f) top = anchor.y.toFloat() + pad
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

    /** Density-independent, because a tooltip is chrome and reads at one size on every screen. */
    private const val TOOLTIP_PADDING = 6f
    private const val TOOLTIP_RADIUS = 4f
    private const val TOOLTIP_TEXT_SP = 12f

    /** A datum with fifty columns is a table, not a tooltip. */
    private const val TOOLTIP_MAX_ROWS = 12
  }
}
