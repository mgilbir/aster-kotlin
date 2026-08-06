package dev.aster.vega.runtime

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.HitTestOptions
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneHitIndex
import dev.aster.vega.scene.SceneNode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable pair of scene plus interaction state, published to renderers as one unit. */
public data class ChartSnapshot(
  val scene: Scene,
  val interactionState: InteractionState,
  val revision: Long,
)

public data class ChartState(
  val snapshot: ChartSnapshot,
  val isLoading: Boolean = false,
  val diagnostics: List<VegaDiagnostic> = emptyList(),
) {
  public companion object {
    public fun empty(): ChartState =
      ChartState(
        snapshot =
          ChartSnapshot(
            scene = Scene.empty(),
            interactionState = InteractionState.Initial,
            revision = 0L,
          )
      )
  }
}

/**
 * Owns a chart's scene, interaction state and diagnostics, and is the single place a host view
 * reads from.
 *
 * A snapshot is fully built before it is published, so drawing never observes a half-updated scene
 * (PROJECT_BRIEF.md 10.2). The controller itself does no Android work and no drawing; it holds
 * immutable state and hands it to whichever surface is rendering.
 *
 * Specification compilation arrives in Milestone 3. Until then [setScene] accepts hand-authored
 * scenes, which is exactly what Milestone 1 needs, and [setSpec] reports an explicit diagnostic
 * rather than silently rendering nothing.
 */
public class VegaChartController(initialScene: Scene = Scene.empty()) {

  private var nextRevision = initialScene.revision + 1

  private val _state =
    MutableStateFlow(
      ChartState(
        snapshot =
          ChartSnapshot(
            scene = initialScene,
            interactionState = InteractionState.Initial,
            revision = initialScene.revision,
          )
      )
    )

  public val state: StateFlow<ChartState> = _state.asStateFlow()

  private val _events =
    MutableSharedFlow<ChartEvent>(
      replay = 0,
      extraBufferCapacity = 32,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

  public val events: Flow<ChartEvent> = _events.asSharedFlow()

  private val _diagnostics = MutableStateFlow<List<VegaDiagnostic>>(emptyList())

  public val diagnostics: StateFlow<List<VegaDiagnostic>> = _diagnostics.asStateFlow()

  /** Hit index for the current scene, rebuilt only when the scene itself changes. */
  private var hitIndex: SceneHitIndex = SceneHitIndex(initialScene, HitTestOptions.Touch)
  private var hitOptions: HitTestOptions = HitTestOptions.Touch

  public val snapshot: ChartSnapshot
    get() = _state.value.snapshot

  /**
   * Replaces the scene and bumps the revision, so a view can decide to invalidate by comparing
   * revisions rather than diffing the scene.
   */
  public fun setScene(scene: Scene) {
    val revision = nextRevision++
    hitIndex = SceneHitIndex(scene, hitOptions)
    _state.value =
      _state.value.copy(
        snapshot =
          ChartSnapshot(
            scene = scene.copy(revision = revision),
            interactionState = _state.value.snapshot.interactionState,
            revision = revision,
          ),
        isLoading = false,
      )
  }

  /** Switches hit-test tuning, e.g. when the last input device changed from touch to mouse. */
  public fun setHitTestOptions(options: HitTestOptions) {
    if (options == hitOptions) return
    hitOptions = options
    hitIndex = SceneHitIndex(snapshot.scene, options)
  }

  /**
   * Device pixels per scene unit contributed by the host surface fitting the scene to its viewport.
   *
   * The host scales the scene to fit, so an incoming pointer coordinate is not in scene units. The
   * controller has to know that factor or every hit test misses by exactly it — which is why this
   * is a required part of the host contract, not an optimization. Interactive pan and zoom are
   * tracked separately in [InteractionState] and applied on top.
   */
  public var contentScale: Double = 1.0
    set(value) {
      // A zero or non-finite scale would make the inverse mapping meaningless; ignore it rather
      // than poisoning every subsequent hit test.
      if (value > 0.0 && value.isFinite()) field = value
    }

  /**
   * Loads a compiled Vega specification.
   *
   * Not implemented before Milestone 3; reports `VEGA_TRANSFORM_NOT_IMPLEMENTED` rather than
   * leaving the caller with a blank chart and no explanation.
   */
  public fun setSpec(@Suppress("UNUSED_PARAMETER") json: String) {
    report(
      VegaDiagnostic(
        severity = DiagnosticSeverity.ERROR,
        code = DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
        message =
          "Vega specification compilation is not implemented yet (planned for Milestone 3). " +
            "Use setScene() with a hand-authored scene until then.",
      )
    )
  }

  public fun report(diagnostic: VegaDiagnostic) {
    _diagnostics.value = _diagnostics.value + diagnostic
    _state.value = _state.value.copy(diagnostics = _diagnostics.value)
  }

  public fun clearDiagnostics() {
    _diagnostics.value = emptyList()
    _state.value = _state.value.copy(diagnostics = emptyList())
  }

  /**
   * Applies an input event.
   *
   * Hover, selection and tooltip updates only replace [InteractionState]; they never rebuild the
   * scene, which is what keeps a hover from rerunning the dataflow (PROJECT_BRIEF.md 19).
   */
  public fun dispatch(event: ChartInputEvent) {
    when (event) {
      is ChartInputEvent.PointerMoved -> updateHover(event.point)
      is ChartInputEvent.PointerEntered -> updateHover(event.point)
      is ChartInputEvent.PointerExited -> updateHover(null)
      is ChartInputEvent.Tap -> handleTap(event.point)
      is ChartInputEvent.LongPress -> handleLongPress(event.point)
      is ChartInputEvent.Pan -> handlePan(event)
      is ChartInputEvent.Zoom -> handleZoom(event)
      is ChartInputEvent.PointerDown,
      is ChartInputEvent.PointerUp,
      is ChartInputEvent.Key,
      is ChartInputEvent.Resized -> Unit // Handled in later milestones; deliberately inert.
    }
  }

  private fun updateHover(point: PointD?) {
    val hit = point?.let { hitIndex.hitTest(toSceneSpace(it)) }
    val node = hit?.node
    val current = _state.value.snapshot.interactionState
    if (current.hoveredNodeId == node?.id) return

    publishInteraction(
      current.copy(
        hoveredNodeId = node?.id,
        tooltip = node?.metadata?.tooltip,
        tooltipAnchor = if (node?.metadata?.tooltip != null) point else null,
      )
    )
    emit(ChartEvent.MarkHovered(node?.id, node?.metadata?.markName, datumOf(node)))
    emit(ChartEvent.TooltipChanged(node?.metadata?.tooltip, point))
  }

  private fun handleTap(point: PointD) {
    val hit = hitIndex.hitTest(toSceneSpace(point))
    val current = _state.value.snapshot.interactionState
    if (hit == null) {
      if (!current.selection.isEmpty) {
        publishInteraction(current.copy(selection = ChartSelection.Empty))
        emit(ChartEvent.SelectionChanged(ChartSelection.Empty))
      }
      return
    }
    val node = hit.node
    val selection =
      ChartSelection(
        nodeIds = setOf(node.id),
        datumIds = node.metadata.datumId?.let { setOf(it) } ?: emptySet(),
      )
    publishInteraction(current.copy(selection = selection, focusedNodeId = node.id))
    emit(ChartEvent.MarkClicked(node.id, node.metadata.markName, datumOf(node), point))
    emit(ChartEvent.SelectionChanged(selection))
  }

  private fun handleLongPress(point: PointD) {
    val hit = hitIndex.hitTest(toSceneSpace(point)) ?: return
    val node = hit.node
    publishInteraction(
      _state.value.snapshot.interactionState.copy(
        tooltip = node.metadata.tooltip,
        tooltipAnchor = point,
      )
    )
    emit(ChartEvent.MarkLongPressed(node.id, node.metadata.markName, datumOf(node)))
  }

  private fun handlePan(event: ChartInputEvent.Pan) {
    val current = _state.value.snapshot.interactionState
    val moved =
      current.copy(
        viewportOffset =
          dev.aster.vega.scene.VectorD(
            current.viewportOffset.dx + event.delta.dx,
            current.viewportOffset.dy + event.delta.dy,
          )
      )
    publishInteraction(moved)
    if (event.phase == GesturePhase.ENDED) emit(ChartEvent.ViewportChanged(visibleViewport(moved)))
  }

  private fun handleZoom(event: ChartInputEvent.Zoom) {
    if (event.scaleFactor <= 0.0 || !event.scaleFactor.isFinite()) {
      report(
        VegaDiagnostic(
          severity = DiagnosticSeverity.WARNING,
          code = DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          message = "Ignoring a zoom with a non-positive scale factor: ${event.scaleFactor}",
        )
      )
      return
    }
    val current = _state.value.snapshot.interactionState
    val newScale = (current.viewportScale * event.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
    // Keep the anchor point stationary while the scale changes.
    val ratio = newScale / current.viewportScale
    val zoomed =
      current.copy(
        viewportScale = newScale,
        viewportOffset =
          dev.aster.vega.scene.VectorD(
            dx = event.anchor.x - ratio * (event.anchor.x - current.viewportOffset.dx),
            dy = event.anchor.y - ratio * (event.anchor.y - current.viewportOffset.dy),
          ),
      )
    publishInteraction(zoomed)
    if (event.phase == GesturePhase.ENDED) emit(ChartEvent.ViewportChanged(visibleViewport(zoomed)))
  }

  public fun resetViewport() {
    publishInteraction(
      _state.value.snapshot.interactionState.copy(
        viewportOffset = dev.aster.vega.scene.VectorD.Zero,
        viewportScale = 1.0,
      )
    )
  }

  /**
   * Maps a point in surface coordinates to scene coordinates, undoing the interactive pan and zoom
   * and the host's fit-to-viewport scale.
   *
   * This is step 1 of hit testing (PROJECT_BRIEF.md 11.2) and must invert exactly what the renderer
   * applies: translate by the pan offset, then scale by `contentScale * viewportScale`.
   */
  private fun toSceneSpace(point: PointD): PointD {
    val interaction = _state.value.snapshot.interactionState
    val scale = contentScale * interaction.viewportScale
    return PointD(
      (point.x - interaction.viewportOffset.dx) / scale,
      (point.y - interaction.viewportOffset.dy) / scale,
    )
  }

  /** The part of the scene currently visible, in scene coordinates. */
  private fun visibleViewport(interaction: InteractionState): dev.aster.vega.scene.RectD {
    val scene = _state.value.snapshot.scene
    val scale = contentScale * interaction.viewportScale
    val left = -interaction.viewportOffset.dx / scale
    val top = -interaction.viewportOffset.dy / scale
    return dev.aster.vega.scene.RectD(
      left,
      top,
      left + scene.width / interaction.viewportScale,
      top + scene.height / interaction.viewportScale,
    )
  }

  private fun publishInteraction(interaction: InteractionState) {
    val revision = nextRevision++
    val previous = _state.value.snapshot
    _state.value =
      _state.value.copy(
        snapshot =
          ChartSnapshot(scene = previous.scene, interactionState = interaction, revision = revision)
      )
  }

  private fun datumOf(node: SceneNode?): VegaValue? = node?.metadata?.tooltip

  private fun emit(event: ChartEvent) {
    _events.tryEmit(event)
  }

  public companion object {
    public const val MIN_ZOOM: Double = 0.1
    public const val MAX_ZOOM: Double = 50.0

    /** Creates a controller for a hand-authored scene. */
    public fun fromScene(scene: Scene): VegaChartController = VegaChartController(scene)
  }
}
