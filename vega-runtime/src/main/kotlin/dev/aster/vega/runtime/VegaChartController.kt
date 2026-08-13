package dev.aster.vega.runtime

// Aliased: this class already has a `ChartInputEvent`, and the two are different things — one is a
// gesture the host reports, the other is the Vega event a selector matches.
import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.EventConfig
import dev.aster.vega.runtime.compile.CompiledSpec
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.interaction.EventDispatcher
import dev.aster.vega.runtime.interaction.FiredHandler
import dev.aster.vega.runtime.interaction.HandlerBinding
import dev.aster.vega.runtime.interaction.InputEvent as VegaEvent
import dev.aster.vega.runtime.interaction.SignalUpdater
import dev.aster.vega.runtime.load.DataLoader
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.scene.HitTestOptions
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneHitIndex
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeId
import dev.aster.vega.scene.TextEngine
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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
 * [setSpec] compiles a Vega specification; [setScene] accepts a hand-authored one. Both publish the
 * same way, so a host does not care which produced the scene it is drawing.
 *
 * @param textEngine measures text while compiling a specification. It must be the same
 *   implementation the surface draws with, or labels will not sit where the layout expected them
 *   (docs/adr/0006) — and, for a platform engine, **not the same instance**, since compiling off
 *   the main thread while that thread draws would race on the engine's shared paint.
 *   `AndroidTextEngine` instances configured alike measure alike, so a second one is all this
 *   needs.
 */
public class VegaChartController(
  initialScene: Scene = Scene.empty(),
  textEngine: TextEngine = MetricTextEngine(),
  /**
   * Wall-clock milliseconds, for throttling an event stream.
   *
   * Injected rather than read directly so a test can drive a throttle without sleeping, and so the
   * core stays free of a platform clock.
   */
  private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
  /**
   * How a specification's `url` data is fetched. Refuses everything unless the host opts in.
   *
   * The default is deliberate and is the whole of the policy: a specification is *data*, often data
   * a user pasted, and a `url` in it asks this process to fetch an address the specification chose.
   * A host that wants loading says so, and says what it is opening — `VegaDataLoaders` in
   * `vega-loader` has the two shapes worth having, a directory and a directory backed by a base
   * URL. Without this seam a host could not opt in at all, whatever the loader could do.
   */
  loader: DataLoader = DenyLoader,
) {

  private val compiler = SpecCompiler(textEngine, loader)

  /** Serializes compilation, so one text engine is only ever used by one compile at a time. */
  private val compileLock = Mutex()

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

  /** The most recent compilation, so a host can read the scales and signals it resolved. */
  public var lastCompiled: CompiledSpec? = null
    private set

  /**
   * Compiles a Vega specification and publishes the scene it produces.
   *
   * Runs on the calling thread. That is the right default — a specification with a few hundred
   * marks compiles in well under a frame — but a large dataset does not belong on the main thread,
   * and [setSpecAsync] exists for that.
   *
   * Diagnostics are **replaced**, not appended: they describe the specification now loaded, and
   * carrying the previous one's complaints forward would make a fixed problem look unfixed.
   *
   * A specification that fails to produce a scene at all leaves the current chart alone rather than
   * blanking it. The diagnostics say why, and a reader keeps the chart they were looking at.
   */
  public fun setSpec(json: String): CompiledSpec {
    loadedSpecJson = json
    signals.reset()
    return publish(compiler.compileJson(json))
  }

  /** The text of the loaded specification, so a fired handler can recompile it. */
  private var loadedSpecJson: String? = null

  private val expressions = CachingExpressionCompiler(VegaExpressionCompiler())

  /**
   * What a handler's own evaluation reported — an expression that could not be read, a function
   * whose argument form this engine refuses.
   *
   * Collected separately from the compiler's and published beside them: a handler that failed at
   * the moment it fired is exactly what a host needs told, and it went into a collector nobody
   * read.
   */
  private val handlerDiagnostics = DiagnosticCollector()

  private val signals = SignalUpdater(expressions, handlerDiagnostics)

  /** Rebuilt on every publish, because the handlers and the scales both come from the compile. */
  private var vegaEvents: EventDispatcher? = null

  /**
   * Compiles a specification off the calling thread, then publishes on it.
   *
   * Compilations are serialized, so the text engine is only ever touched by one at a time. The host
   * still must not hand this controller the engine its renderer draws with; see the class docs.
   */
  public suspend fun setSpecAsync(
    json: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
  ): CompiledSpec {
    _state.value = _state.value.copy(isLoading = true)
    val compiled =
      try {
        compileLock.withLock { withContext(dispatcher) { compiler.compileJson(json) } }
      } catch (e: CancellationException) {
        _state.value = _state.value.copy(isLoading = false)
        throw e
      }
    return publish(compiled)
  }

  private fun publish(compiled: CompiledSpec): CompiledSpec {
    lastCompiled = compiled
    val bindings =
      compiled.spec?.signals.orEmpty().flatMap { signal ->
        signal.on.map { HandlerBinding(signal.name, it) }
      }
    // The dispatcher reports as it registers — a stream a policy blocked, a debounce nothing can
    // schedule — and those went into a collector nobody read. They are published with the
    // compiler's
    // own, since a listener that was refused is exactly the kind of thing a host needs told.
    val listenerDiagnostics = DiagnosticCollector()
    vegaEvents =
      if (bindings.isEmpty()) {
        null
      } else {
        EventDispatcher(
          bindings,
          expressions,
          listenerDiagnostics,
          compiled.signals,
          // The embedder's event policy, refused at the listener rather than at the event.
          compiled.spec?.events ?: EventConfig(),
        )
      }
    val diagnostics = compiled.diagnostics + listenerDiagnostics.diagnostics
    _diagnostics.value = diagnostics
    val scene = compiled.scene
    if (scene == null) {
      _state.value = _state.value.copy(isLoading = false, diagnostics = diagnostics)
      return compiled
    }
    val revision = nextRevision++
    val published = scene.copy(revision = revision)
    hitIndex = SceneHitIndex(published, hitOptions)
    _state.value =
      ChartState(
        snapshot =
          ChartSnapshot(
            scene = published,
            interactionState = _state.value.snapshot.interactionState,
            revision = revision,
          ),
        isLoading = false,
        diagnostics = diagnostics,
      )
    return compiled
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
      is ChartInputEvent.Resized -> Unit // No built-in behaviour; a signal handler may still fire.
    }
    fireSignalHandlers(event)
  }

  /**
   * Turns a gesture into the Vega events a specification's selectors are written against, and
   * recompiles if a handler changed a signal.
   *
   * One gesture produces **several** event names, which is not padding: a browser on a touch screen
   * fires the touch family *and* synthesises the pointer, mouse and click families from it, and
   * almost every specification in the wild is written against `click` or `mousedown`. Emitting only
   * `touchstart` would leave those specifications inert on Android for no reason a reader could
   * see.
   *
   * The order within a gesture is the browser's: pointer, then touch, then mouse, then `click`.
   */
  private fun fireSignalHandlers(event: ChartInputEvent) {
    val dispatcher = vegaEvents ?: return
    val compiled = lastCompiled ?: return
    val types =
      when (event) {
        is ChartInputEvent.PointerDown -> listOf("pointerdown", "touchstart", "mousedown")
        is ChartInputEvent.PointerUp -> listOf("pointerup", "touchend", "mouseup")
        is ChartInputEvent.PointerMoved -> listOf("pointermove", "touchmove", "mousemove")
        is ChartInputEvent.PointerEntered -> listOf("pointerover", "mouseover")
        is ChartInputEvent.PointerExited -> listOf("pointerout", "mouseout")
        is ChartInputEvent.Tap -> listOf("click")
        else -> return
      }
    val point = pointOf(event)
    val scenePoint = point?.let { toSceneSpace(it) }
    val hit = scenePoint?.let { hitIndex.hitTest(it) }?.node
    // What `x()`, `y()` and `xy()` answer: the point with the chart's padding and autosize origin
    // taken off, which the root group carries as its own translation — upstream's `offset(view)`.
    val origin = _state.value.snapshot.scene.root.transform
    val rootPoint = scenePoint?.let { PointD(it.x - origin.e, it.y - origin.f) } ?: PointD(0.0, 0.0)
    val changed = LinkedHashSet<String>()
    for (type in types) {
      val fired =
        dispatcher.dispatch(
          VegaEvent(
            type = type,
            timestampMillis = clock(),
            itemId = hit?.id,
            markType = hit?.metadata?.markKind,
            markName = hit?.metadata?.markName,
            datum = hit?.metadata?.datum ?: VegaValue.Null,
            x = point?.x ?: 0.0,
            y = point?.y ?: 0.0,
            rootX = rootPoint.x,
            rootY = rootPoint.y,
          )
        )
      if (fired.isNotEmpty()) changed += signals.apply(fired, compiled.signals)
    }
    // A handler whose whole effect is `encode(item(), 'select')` changes no signal value: its
    // update
    // expression returns the item it was handed, which is the same object as before. So a fresh
    // overlay is a reason to redraw in its own right, and testing only the signals meant a press
    // styled nothing.
    val overlaid = signals.itemEncodes.values.any { it.fresh }
    if (changed.isEmpty() && !overlaid) return
    val cascaded = cascade(changed, compiled) + drainHandlerDiagnostics()

    // Recompile rather than patch. Measured at well under a frame for the heaviest fixture, which
    // is what makes this simple enough to be obviously correct (STATUS.md, Performance
    // observations).
    val json = loadedSpecJson ?: return
    publish(compiler.compileJson(json, signals.overrides, signals.itemEncodes))
    // The overlays that were fresh have now been applied once; from here on the mark's own `update`
    // takes back the channels it sets, which is what ageing them expresses.
    signals.ageItemEncodes()
    // After publishing, which replaces the diagnostics with the new compile's: what a handler
    // reported as it fired — a cycle among the signal-driven ones, an expression that could not be
    // read — is a fact about this interaction rather than about the specification's text, and it
    // would otherwise be wiped by the very recompile it caused.
    for (diagnostic in cascaded) report(diagnostic)
  }

  /** Takes what the handlers reported and clears it, so the same message is not published twice. */
  private fun drainHandlerDiagnostics(): List<VegaDiagnostic> {
    val drained = handlerDiagnostics.diagnostics.toList()
    if (drained.isNotEmpty()) handlerDiagnostics.clear()
    return drained
  }

  /**
   * Fires the handlers whose source is a **signal** rather than an event, until nothing more
   * changes.
   *
   * `{"events": {"signal": "brush"}, "update": "..."}` is how one signal is derived from another,
   * and it is what a specification writes when the value it wants is a function of a control rather
   * than of a pointer. Upstream makes it a dataflow edge, so it fires whenever the source changes
   * and cascades: probed with a chain two deep, setting `a` to 5 left `b` at 10 and `c` at 11 in
   * the same run. Not at *initialization*, though — also probed: with no change there is nothing to
   * fire, and both signals keep their declared values, which is why the differential fixtures see
   * none of this.
   *
   * Ordering falls out of the loop rather than needing a sort: each round fires only the handlers
   * whose source changed in the round before, so a chain is walked in dependency order however the
   * signals were declared. A **cycle** would never settle, and upstream refuses such a
   * specification outright; this engine reports it and stops, which is what [DataflowOrder] does
   * with a cycle among `update` expressions — a chart that draws with one signal stuck beats a
   * chart that does not draw.
   */
  private fun cascade(
    changed: MutableSet<String>,
    compiled: CompiledSpec,
  ): List<VegaDiagnostic> {
    val bindings =
      compiled.spec?.signals.orEmpty().flatMap { signal ->
        signal.on.filter { it.signalSources.isNotEmpty() }.map { HandlerBinding(signal.name, it) }
      }
    if (bindings.isEmpty()) return emptyList()
    var frontier: Set<String> = changed.toSet()
    var round = 0
    while (frontier.isNotEmpty()) {
      if (++round > MAX_CASCADE_ROUNDS) {
        return listOf(
          VegaDiagnostic(
            code = DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            severity = DiagnosticSeverity.WARNING,
            message =
              "Signals '${frontier.sorted().joinToString("', '")}' are still changing after " +
                "$MAX_CASCADE_ROUNDS rounds of signal-driven handlers, so they are on a cycle; " +
                "their last values are kept",
          )
        )
      }
      val due =
        bindings
          .filter { binding -> binding.handler.signalSources.any { it in frontier } }
          .map { FiredHandler(it.signalName, it.handler) }
      if (due.isEmpty()) return emptyList()
      // Read against the overrides accumulated so far, which is what makes a chain see the value
      // the
      // round before it produced rather than the one the last compile resolved.
      frontier = signals.apply(due, compiled.signals)
      changed += frontier
    }
    return emptyList()
  }

  private fun pointOf(event: ChartInputEvent): PointD? =
    when (event) {
      is ChartInputEvent.PointerDown -> event.point
      is ChartInputEvent.PointerUp -> event.point
      is ChartInputEvent.PointerMoved -> event.point
      is ChartInputEvent.PointerEntered -> event.point
      is ChartInputEvent.PointerExited -> event.point
      is ChartInputEvent.Tap -> event.point
      else -> null
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
        cursor = node?.metadata?.cursor,
      ),
      scene = hoveredScene(node?.id),
    )
    emit(ChartEvent.MarkHovered(node?.id, node?.metadata?.markName, datumOf(node)))
    emit(ChartEvent.TooltipChanged(node?.metadata?.tooltip, point))
  }

  private fun handleTap(point: PointD) {
    // A touch screen has no pointer to hover with, so a tap is also how a `hover` block and a
    // tooltip are reached. A browser does the same thing: it synthesises `pointerover` from a touch
    // before it reports the click.
    updateHover(point)
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

  /**
   * The scene with the pointed-at item drawn from its mark's `hover` block.
   *
   * A `hover` block is the one part of an encoding that depends on where the pointer is, and it is
   * applied by **swapping a node** rather than by recompiling: the item was encoded twice at
   * compile time, once resting and once hovered, sharing its id. So a pointer moving across a
   * scatter plot costs one map lookup and a rebuilt group chain, not a pass over the specification.
   *
   * Its bounds may differ slightly from the resting item's — a thicker stroke reaches further — and
   * the hit index is deliberately **not** rebuilt for that: re-indexing ten thousand nodes on every
   * pointer move to account for a hairline would cost more than it could ever be worth, and the
   * item under the pointer is the one that just tested positive anyway.
   */
  private fun hoveredScene(hovered: SceneNodeId?): Scene {
    val compiled = lastCompiled
    val base = compiled?.scene ?: _state.value.snapshot.scene
    val variant = hovered?.let { compiled?.hoverVariants?.get(it) } ?: return base
    return base.replacing(mapOf(hovered to variant))
  }

  private fun publishInteraction(
    interaction: InteractionState,
    /** Defaults to whatever is on screen, so a selection or a pan keeps the hover styling. */
    scene: Scene = _state.value.snapshot.scene,
  ) {
    val revision = nextRevision++
    _state.value =
      _state.value.copy(
        snapshot = ChartSnapshot(scene = scene, interactionState = interaction, revision = revision)
      )
  }

  private fun datumOf(node: SceneNode?): VegaValue? = node?.metadata?.tooltip

  private fun emit(event: ChartEvent) {
    _events.tryEmit(event)
  }

  public companion object {
    public const val MIN_ZOOM: Double = 0.1
    public const val MAX_ZOOM: Double = 50.0

    /**
     * How many rounds of signal-driven handlers to run before calling it a cycle.
     *
     * A chain is as deep as the specification is: the longest in the corpus is two. Anything past a
     * handful is not depth but a loop, and the number only has to be far enough above real depth
     * that no honest specification meets it.
     */
    private const val MAX_CASCADE_ROUNDS = 64

    /** Creates a controller for a hand-authored scene. */
    public fun fromScene(scene: Scene): VegaChartController = VegaChartController(scene)

    /** Creates a controller and immediately loads [json], returning both it and the compilation. */
    public fun fromSpec(
      json: String,
      textEngine: TextEngine = MetricTextEngine(),
    ): Pair<VegaChartController, CompiledSpec> {
      val controller = VegaChartController(textEngine = textEngine)
      return controller to controller.setSpec(json)
    }
  }
}
