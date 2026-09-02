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
