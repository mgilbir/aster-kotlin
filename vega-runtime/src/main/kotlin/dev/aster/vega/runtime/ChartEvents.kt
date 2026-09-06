package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.ChartKey
import dev.aster.vega.scene.Modifiers
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.SceneColor
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
 * How a chart's focus ring is drawn, and whether a pointer draws one.
 *
 * **Keyboard-only by default**, which is the web's `:focus-visible` rule and the answer to what a
 * tap should show: a tap already says what it did through the tooltip and whatever the
 * specification's `hover` block paints, and an outline around every mark anyone touches reads as
 * clutter rather than as information. The ring exists so that a reader moving through a chart with
 * arrow keys can see where they are, and that is when it is drawn.
 *
 * A host that wants the other behaviour — a kiosk driven entirely by touch, say, where nothing else
 * indicates the current mark — sets [showsOnPointer].
 *
 * The style is here rather than in the renderers because the ring is drawn **into the scene**,
 * once, so that three renderers cannot disagree about a thing a reader depends on. Its default
 * colour is deliberately not taken from the chart: a ring has to be findable against whatever the
 * specification chose to paint.
 */
public data class FocusRing(
  /** How far outside the mark the ring sits, so a ring around a rule is not a thicker rule. */
  val inset: Double = 2.0,
  val width: Double = 2.0,
  /** The platform-neutral blue every focus indicator converges on. */
  val colour: SceneColor = SceneColor(0x1A / 255.0, 0x73 / 255.0, 0xE8 / 255.0),
  /** Whether a pointer tap draws the ring as well as moving focus. Off, as `:focus-visible` is. */
  val showsOnPointer: Boolean = false,
)

/**
 * Interaction state that a renderer may need but that is not part of the scene itself.
 *
 * Kept separate from [dev.aster.vega.scene.Scene] so a hover or selection change can bump the
 * snapshot revision without rebuilding the scene (ADR 0002, ADR 0012).
 */
public data class InteractionState(
  val hoveredNodeId: SceneNodeId? = null,
  val focusedNodeId: SceneNodeId? = null,
  /**
   * Whether [focusedNodeId] should be **drawn**, which is not the same as whether it is set.
   *
   * The web's `:focus-visible` distinction, and for its reason. Focus follows a tap as well as a
   * key — so that arrowing away from a mark a reader has just tapped carries on from there rather
   * than from the beginning — but a ring drawn around every mark anyone clicks is noise, and it
   * says "focused" about something the reader is looking straight at.
   *
   * True for the keyboard and for a host moving focus on behalf of a screen reader; false for a
   * pointer, unless `FocusRing.showsOnPointer` asks otherwise.
   */
  val focusVisible: Boolean = true,
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
  /**
   * The same state with everything that **names a mark** dropped, for a chart that has been
   * replaced.
   *
   * Every field cleared here points at a `SceneNodeId` from the scene that is going away, so
   * carrying them over publishes a tooltip, a selection and a focus ring belonging to a chart the
   * reader is no longer looking at. Reported as three faults on the demo: tapping a stacked bar and
   * then switching to the line chart left the tooltip sitting on top of it.
   *
   * **The viewport is kept**, and that is the line between the two halves of this class. A pan and
   * a zoom are a statement about the surface rather than about any mark, so they survive a
   * recompile — which is the whole reason this state is carried across one at all — and a host that
   * wants them dropped has `resetViewport`.
   */
  public fun forNewChart(): InteractionState =
    copy(
      hoveredNodeId = null,
      focusedNodeId = null,
      selection = ChartSelection.Empty,
      tooltip = null,
      tooltipAnchor = null,
      cursor = null,
    )

  public companion object {
    public val Initial: InteractionState = InteractionState()
  }
}
