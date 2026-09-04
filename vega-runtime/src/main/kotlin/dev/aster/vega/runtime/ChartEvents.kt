package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.SceneNodeId
import dev.aster.vega.scene.VectorD

/** Where a pointer event came from; touch input gets a larger hit-test tolerance. */
public enum class PointerDevice {
  TOUCH,
  MOUSE,
  STYLUS,
  UNKNOWN,
}

public enum class GesturePhase {
  BEGAN,
  CHANGED,
  ENDED,
  CANCELLED,
}

/** Keys the chart reacts to. Anything else is left to the host view. */
public enum class ChartKey {
  ARROW_LEFT,
  ARROW_RIGHT,
  ARROW_UP,
  ARROW_DOWN,
  ENTER,
  SPACE,
  ESCAPE,
  TAB,
  HOME,
  END,
}

public data class Modifiers(
  val shift: Boolean = false,
  val control: Boolean = false,
  val alt: Boolean = false,
  val meta: Boolean = false,
) {
  public companion object {
    public val None: Modifiers = Modifiers()
  }
}

/**
 * Platform-neutral input events.
 *
 * Android `MotionEvent` and friends are translated at the view boundary and never reach the runtime
 * (ADR 0007), which is what keeps the core testable on the JVM.
 */
public sealed interface ChartInputEvent {
  public data class PointerEntered(val point: PointD) : ChartInputEvent

  public data class PointerMoved(val point: PointD) : ChartInputEvent

  /**
   * [point] is `null` when the pointer left through an unknown position, e.g. a cancelled gesture.
   */
  public data class PointerExited(val point: PointD?) : ChartInputEvent

  public data class PointerDown(
    val point: PointD,
    val pointerId: Long,
    val device: PointerDevice,
    val buttons: Int,
  ) : ChartInputEvent

  public data class PointerUp(
    val point: PointD,
    val pointerId: Long,
    val device: PointerDevice,
    val buttons: Int,
  ) : ChartInputEvent

  public data class Tap(val point: PointD) : ChartInputEvent

  public data class LongPress(val point: PointD) : ChartInputEvent

  public data class Pan(val delta: VectorD, val phase: GesturePhase) : ChartInputEvent

  public data class Zoom(val scaleFactor: Double, val anchor: PointD, val phase: GesturePhase) :
    ChartInputEvent

  public data class Key(val key: ChartKey, val modifiers: Modifiers = Modifiers.None) :
    ChartInputEvent

  /** The drawing surface changed size; layout must rerun. */
  public data class Resized(val width: Double, val height: Double, val pixelScale: Double) :
    ChartInputEvent
}

/** A selection of marks, identified by scene node and by datum so it survives a scene rebuild. */
public data class ChartSelection(
  val nodeIds: Set<SceneNodeId> = emptySet(),
  val datumIds: Set<Long> = emptySet(),
  /** Set for interval selections; `null` for point selections. */
  val interval: RectD? = null,
) {
  public val isEmpty: Boolean
    get() = nodeIds.isEmpty() && datumIds.isEmpty() && interval == null

  public companion object {
    public val Empty: ChartSelection = ChartSelection()
  }
}

/** Events the chart emits to the host application. */
public sealed interface ChartEvent {
  public data class MarkClicked(
    val nodeId: SceneNodeId,
    val markName: String?,
    val datum: VegaValue?,
    val point: PointD,
  ) : ChartEvent

  public data class MarkHovered(
    val nodeId: SceneNodeId?,
    val markName: String?,
    val datum: VegaValue?,
  ) : ChartEvent

  public data class MarkLongPressed(
    val nodeId: SceneNodeId,
    val markName: String?,
    val datum: VegaValue?,
  ) : ChartEvent

  public data class SelectionChanged(val selection: ChartSelection) : ChartEvent

  public data class SignalChanged(val name: String, val value: VegaValue) : ChartEvent

  /** Emitted when the tooltip should appear, move or (with a `null` content) disappear. */
  public data class TooltipChanged(val content: VegaValue?, val anchor: PointD?) : ChartEvent

  public data class ViewportChanged(val viewport: RectD) : ChartEvent
}

/**
 * Interaction state that a renderer may need but that is not part of the scene itself.
 *
 * Kept separate from [dev.aster.vega.scene.Scene] so a hover or selection change can bump the
 * snapshot revision without rebuilding the scene (ADR 0002, ADR 0012).
 */
public data class InteractionState(
  val hoveredNodeId: SceneNodeId? = null,
  val focusedNodeId: SceneNodeId? = null,
  val selection: ChartSelection = ChartSelection.Empty,
  val tooltip: VegaValue? = null,
  val tooltipAnchor: PointD? = null,
  /**
   * The pointer shape the item under the pointer asks for, as the CSS name a specification writes.
   *
   * Published rather than applied, because what a cursor *is* differs by platform: a host maps it
   * to a `PointerIcon` on Android and a browser writes it straight into a style attribute. Null
   * when nothing under the pointer asks for one.
   */
  val cursor: String? = null,
  /** Pan/zoom applied on top of the scene, so panning does not rebuild static content. */
  val viewportOffset: VectorD = VectorD.Zero,
  val viewportScale: Double = 1.0,
) {
  public companion object {
    public val Initial: InteractionState = InteractionState()
  }
}

/**
 * A chart-level action assistive technology can offer, beyond activating a mark.
 *
 * A chart that pans and zooms had no accessible way to do either: the accessibility tree offers an
 * activation per element and nothing else, so a reader could reach every bar and not the view they
 * were drawn in. These are the actions that belong to the **chart** rather than to any one mark,
 * which is why they are not on [dev.aster.vega.scene.AccessibleElement]: a host attaches them to
 * the chart's own node — `AccessibilityNodeInfo.addAction` on Android,
 * `UIAccessibilityCustomAction` on Apple — and asks the controller to perform them.
 *
 * Only the actions that would **do** something are offered. Zooming in at the limit and resetting a
 * view already at rest are both absent from the list rather than present and inert, because an
 * action a reader invokes to no effect is worse than one that was never offered.
 */
public enum class ChartActionKind {
  ZOOM_IN,
  ZOOM_OUT,
  RESET_ZOOM,
  /**
   * Puts every axis a reader adjusted back to the domain the specification computed.
   *
   * A chart action rather than a per-axis one, and there is no `NARROW`/`WIDEN` beside it, because
   * adjusting an axis is **not** an action: it is the increment and decrement of an adjustable
   * element, reached from the axis itself through
   * [dev.aster.vega.scene.AccessibleElement.adjustableScale]. Making them actions would put one
   * pair per axis in this list — eight entries on a two-axis chart — and a reader rotoring through
   * eight custom actions on every chart is worse served than one who swipes on the axis they are
   * standing on.
   *
   * Undoing is the part that has nowhere else to live: a reader who has narrowed two axes is not
   * standing on either of them any more, so the way back belongs to the chart. Separate from
   * [RESET_ZOOM] because the two are different work — one magnifies the drawing, the other changes
   * the interval the data is drawn against — and a single reset would undo work nobody asked to
   * lose.
   */
  RESET_DOMAINS,
}

/**
 * One offered action: what it does, and what to call it in the chart's own language.
 *
 * The label travels with the action because a host has nowhere else to get it. Leaving hosts to
 * write their own would put the chart's wording in three places and none of them in the chart's
 * locale.
 */
public data class ChartAction(val kind: ChartActionKind, val label: String)
