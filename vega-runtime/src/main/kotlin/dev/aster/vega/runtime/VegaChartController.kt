package dev.aster.vega.runtime

// Aliased: this class already has a `ChartInputEvent`, and the two are different things — one is a
// gesture the host reports, the other is the Vega event a selector matches.
import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.Evaluator
import dev.aster.vega.expression.Functions
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.model.spec.EventConfig
import dev.aster.vega.model.spec.EventStream
import dev.aster.vega.runtime.compile.CompiledSpec
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.interaction.EventDispatcher
import dev.aster.vega.runtime.interaction.FiredHandler
import dev.aster.vega.runtime.interaction.HandlerBinding
import dev.aster.vega.runtime.interaction.InputEvent as VegaEvent
import dev.aster.vega.runtime.interaction.ScheduledTask
import dev.aster.vega.runtime.interaction.Scheduler
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
import dev.aster.vega.scene.SizeD
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
import kotlinx.datetime.TimeZone

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
  private val textEngine: TextEngine = MetricTextEngine(),
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
  private val loader: DataLoader = DenyLoader,
  /**
   * How the chart waits, for the two constructs that need to be woken rather than prompted.
   *
   * A `debounce` fires after a quiet period and a **timer** stream fires with nothing to prompt it,
   * so both need a clock the compiler cannot own — see [Scheduler]. Null by default, and then a
   * debounce fires on every matching event and a timer does not fire at all, both saying so. A host
   * that wants either passes one whose lifetime it controls, which on Android means the view's.
   */
  private val scheduler: Scheduler? = null,
  /**
   * The language every generated name and number is written in, beside [textEngine] because the two
   * are the same kind of thing: something the platform knows and the engine cannot.
   *
   * A tick label's month name, a thousands separator, the sentence a screen reader is given. See
   * `VegaLocale`, whose fields are d3's own locale definitions. Defaults to d3's `en-US`, so a
   * chart built without one is what upstream draws — and does **not** change how a specification's
   * own dates are parsed, which is part of the wire format rather than of the language.
   */
  private val locale: VegaLocale = VegaLocale.EnglishUS,
  /**
   * A `config` block this host supplies, which a specification's own beats key by key.
   *
   * A chart arriving from a server carries the colours that server chose; an app drawing it on a
   * dark surface says otherwise here rather than by rewriting the payload. See `SpecCompiler`.
   */
  private val hostConfig: VegaValue? = null,
  /**
   * The size of the surface the chart is drawn in; see the [containerSize] property, which a host
   * sets again whenever its layout changes.
   */
  containerSize: SizeD? = null,
  /**
   * Tables this host supplies, by the dataset name the specification uses; see the [hostData]
   * property, which a host sets again whenever its own data changes.
   */
  hostData: Map<String, List<VegaValue>>? = null,
  /**
   * What **local** time means for this chart, or null for the device's own zone.
   *
   * Beside [locale] and for the same reason: the platform knows it and the engine cannot, and it is
   * not the same question as the language — a Dutch reader in Curaçao needs one of each. It settles
   * a `time` scale's ticks, `timeunit`'s buckets, the local expression functions and the zone a
   * naive timestamp in the data is read in. See `SpecCompiler.timeZone` and `VegaTimeZones`.
   */
  private val timeZone: TimeZone? = null,
) {

  private var compiler = newCompiler(containerSize, hostData)

  private fun newCompiler(size: SizeD?, data: Map<String, List<VegaValue>>?) =
    SpecCompiler(
      textEngine,
      loader,
      locale = locale,
      hostConfig = hostConfig,
      containerSize = size,
      timeZone = timeZone,
      hostData = data,
    )

  /**
   * Tables the **host** supplies, which is how a chart is drawn from data the app already holds.
   *
   * Upstream's `view.data(name, rows)`. A specification declares `{"name": "diary"}` — no values,
   * no url, no source — and this fills it; in Vega-Lite that is `{"data": {"name": "diary"}}` and
   * the name survives compilation, so a host uses the name it wrote. The rows arrive as inline
   * values would, so the dataset's `format.parse` and its transforms run over them unchanged.
   *
   * Setting it **recompiles** the loaded specification, because that is how this engine answers a
   * change of any compile input: there is no incremental dataflow, and a whole recompile of the
   * heaviest fixture is well inside a frame. So this is a seam for *new data* — a store that
   * changed, a query that returned — and not somewhere to write on every frame. A set whose rows
   * are equal to the ones already loaded does nothing at all, which is the cheaper half of that
   * comparison.
   *
   * The signal values a reader has set survive the recompile: new rows are not a reason to forget
   * which bar they had selected.
   */
  public var hostData: Map<String, List<VegaValue>>? = hostData
    set(value) {
      if (field == value) return
      field = value
      compiler = newCompiler(containerSize, value)
      val json = loadedSpecJson ?: return
      publish(compiler.compileJson(json, signals.overrides, signals.itemEncodes))
    }

  /**
   * One table, by name — `view.data(name, rows)` spelled the way a host actually calls it.
   *
   * Recompiles once, as [hostData] does. Passing an empty list is a table that is *there and
   * empty*, which is a different chart from one whose dataset was never filled: the scales see no
   * rows and the axes say so, rather than the specification's own values being used.
   */
  public fun setData(name: String, rows: List<VegaValue>) {
    hostData = (hostData ?: emptyMap()) + (name to rows)
  }

  /**
   * The size of the surface the chart is drawn in, which `width: "container"` asks for.
   *
   * A responsive width cannot come from the specification: `"container"` means "ask the page", and
   * a host is the one party that knows how much room a chart has. Setting it **recompiles** the
   * loaded specification, because the size reaches the chart as a signal the compiler resolves —
   * which is why a host sets this on a layout change rather than on every frame of a resize
   * animation.
   *
   * Null, and a chart falls back to `config.view.continuousWidth` — 300, which is upstream's own
   * answer outside a browser. A zero or absent dimension does the same for that dimension alone, so
   * a host that knows only its width says only its width.
   *
   * The signal values a reader has set survive the recompile: a resize is not a reason to forget
   * them.
   */
  public var containerSize: SizeD? = containerSize
    set(value) {
      if (field == value) return
      field = value
      compiler = newCompiler(value, hostData)
      val json = loadedSpecJson ?: return
      publish(compiler.compileJson(json, signals.overrides, signals.itemEncodes))
    }

  /** The debounced handlers waiting, by the signal and delay that identify them. */
  private val pendingDebounces = mutableMapOf<Pair<String, Double>, ScheduledTask>()

  /** The timer streams running, one per handler that asked for one. */
  private var timerTasks: List<ScheduledTask> = emptyList()

  /**
   * What those timers are, so a redraw does not restart them.
   *
   * A tick recompiles, and a recompile used to start the timers again — which cancelled the ones
   * mid-flight, reset the `elapsed` every handler reads, and dropped the ticks that were between
   * the two. They are left alone unless the specification's timers have actually changed.
   */
  private var timerKeys: List<Pair<String, Double>> = emptyList()

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

  private val _inputs = MutableStateFlow<List<SignalInput>>(emptyList())

  /**
   * The controls this chart asks a reader for, with the values they currently hold.
   *
   * A specification's `bind` says what control a signal wants — a slider, a checkbox, a choice —
   * and this is the list of them, in declaration order, republished on every compile. That is what
   * makes the binding **two-way**: a signal moved by a tap, by another signal or by a timer moves
   * the control that shows it, with no host code involved.
   *
   * Empty for a chart that binds nothing, which is most of them. See [setSignal] for the way back.
   */
  public val inputs: StateFlow<List<SignalInput>> = _inputs.asStateFlow()

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

  /**
   * Compiles off the calling thread on the default dispatcher.
   *
   * The same as the two-argument [setSpecAsync] with its default, spelled as its own function
   * because a **default argument does not cross the Obj-C boundary**. Kotlin/Native exports only
   * the full parameter list, and `Dispatchers` is not in the exported surface at all — so from
   * Swift the other overload asks for a value that cannot be named, and compiling off the main
   * thread was unreachable from a foreign host. An iOS host had to fall back to the synchronous
   * [setSpec] on a thread of its own, which works and is not the API this class advertises.
   *
   * Parity between hosts is the point: a capability that exists for Kotlin and not for Swift is a
   * gap in this boundary rather than a limitation of the platform.
   */
  public suspend fun setSpecAsync(json: String): CompiledSpec =
    setSpecAsync(json, Dispatchers.Default)

  /** The text of the loaded specification, so a fired handler can recompile it. */
  private var loadedSpecJson: String? = null

  // The same locale-bound function table the compiler uses, so a handler's own `timeFormat` writes
  // the same month name the axis does.
  private val expressions =
    CachingExpressionCompiler(
      VegaExpressionCompiler(Evaluator(Functions.functionsFor(locale, timeZone = timeZone)))
    )

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
   *
   * A host that is not Kotlin should call the single-argument [setSpecAsync] instead: a default
   * argument does not survive the Obj-C boundary, so from Swift this overload demands a
   * `CoroutineDispatcher` that no exported symbol can produce.
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
    // The same two lines [setSpec] runs, and leaving them out made this the quietest bug in the
    // controller: a chart loaded through here had no text to recompile from, so **no signal change
    // could redraw it** — not a control, not a handler, not a tap on a mark. Every JVM test used
    // the
    // synchronous path and every interactive specification in the demo was inert. Recorded after
    // the
    // compile rather than before it, so a load that was cancelled leaves the chart on screen alone.
    loadedSpecJson = json
    signals.reset()
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
    startTimers(compiled)
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
          deferrable = scheduler != null,
        )
      }
    val diagnostics = compiled.diagnostics + listenerDiagnostics.diagnostics
    _diagnostics.value = diagnostics
    // Re-read rather than remembered: a signal a handler or a timer changed has to move the control
    // that shows it, and the compile that just happened is where its new value is.
    _inputs.value = SignalInput.of(compiled.spec?.signals.orEmpty(), compiled.signals.values)
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
   * Sets a signal from outside the chart, which is how a control drives one.
   *
   * The same path a fired handler takes: the value is pinned, the handlers sourced on that signal
   * cascade from it, and the specification is compiled again with the new value in place. So a
   * slider bound to `size` reaches everything that reads `size`, including a signal derived from it
   * and a scale built on that — which is the whole point of a binding and is not something a host
   * could arrange from outside.
   *
   * Ignores a name the specification does not declare, rather than inventing a signal: a stray
   * write from a control that outlived its chart should do nothing.
   */
  public fun setSignal(name: String, value: VegaValue) {
    val compiled = lastCompiled ?: return
    val spec = compiled.spec ?: return
    if (spec.signals.none { it.name == name }) return
    if (compiled.signals.signal(name) == value) return
    val changed = LinkedHashSet<String>()
    signals.set(name, value)
    changed += name
    applyFired(changed, compiled)
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
      // A stream carrying a `debounce` is *held* rather than applied: it fires after a quiet
      // period, so each event cancels the one waiting and starts the wait again — upstream's
      // `debounce`, which schedules with the latest event and keeps only that one. With no
      // scheduler in hand nothing is deferred and the dispatcher says so instead.
      val (deferred, immediate) = fired.partition { it.deferByMillis != null }
      for (entry in deferred) defer(entry)
      if (immediate.isNotEmpty()) changed += signals.apply(immediate, compiled.signals)
    }
    applyFired(changed, compiled)
  }

  /**
   * Holds a handler until its stream has been quiet for as long as it asked for.
   *
   * Keyed by the signal it sets *and* the delay, so two debounced handlers on one signal wait
   * independently while a second event on the same one replaces the first — which is the whole
   * behaviour: a drag that ends produces one update rather than four hundred.
   */
  private fun defer(entry: FiredHandler) {
    val scheduler = this.scheduler ?: return
    val delay = entry.deferByMillis ?: return
    val key = entry.signalName to delay
    pendingDebounces.remove(key)?.cancel()
    pendingDebounces[key] =
      scheduler.schedule(delay.toLong(), repeating = false) {
        pendingDebounces.remove(key)
        val compiled = lastCompiled ?: return@schedule
        val changed = LinkedHashSet(signals.apply(listOf(entry), compiled.signals))
        applyFired(changed, compiled)
      }
  }

  /** What every handler ends with: cascade, redraw if anything moved, then say what went wrong. */
  private fun applyFired(changed: MutableSet<String>, compiled: CompiledSpec) {
    // A handler whose whole effect is `encode(item(), 'select')` changes no signal value: its
    // update expression returns the item it was handed, which is the same object as before. So a
    // fresh overlay is a reason to redraw in its own right, and testing only the signals meant a
    // press styled nothing.
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

  /**
   * Starts a repeating run for every timer stream in the specification, and stops the last lot.
   *
   * A timer is not dispatched from an input event: nothing prompts it, which is why it needs the
   * clock at all. `{"type": "timer", "throttle": 500}` asks for a tick every 500ms, and the event
   * it fires with carries the two fields upstream's does — the wall-clock `timestamp` and the
   * `elapsed` since the timer started, which is what an animation reads to know where it is.
   *
   * Restarted on every publish because the handlers come from the compile; a specification that no
   * longer has a timer stops ticking, which is the behaviour a host reloading a chart expects.
   */
  private fun startTimers(compiled: CompiledSpec) {
    val scheduler = this.scheduler
    if (scheduler == null) {
      stopTimers()
      return
    }
    val bindings =
      compiled.spec?.signals.orEmpty().flatMap { signal ->
        signal.on.flatMap { handler ->
          handler.streams
            .filter { it.source == EventStream.SOURCE_TIMER }
            .map { stream -> HandlerBinding(signal.name, handler) to stream }
        }
      }
    val keys = bindings.map { (binding, stream) -> binding.signalName to (stream.throttle ?: 0.0) }
    if (keys == timerKeys) return
    stopTimers()
    timerKeys = keys
    if (bindings.isEmpty()) return
    val started = clock()
    timerTasks = bindings.map { (binding, stream) ->
      val interval = (stream.throttle ?: 0.0).coerceAtLeast(1.0)
      scheduler.schedule(interval.toLong(), repeating = true) {
        val compiledNow = lastCompiled ?: return@schedule
        val now = clock()
        val event =
          VegaEvent(
            type = EventStream.SOURCE_TIMER,
            timestampMillis = now,
            source = EventStream.SOURCE_TIMER,
            properties =
              mapOf(
                "timestamp" to VegaValue.Num(now.toDouble()),
                "elapsed" to VegaValue.Num((now - started).toDouble()),
              ),
          )
        val changed =
          LinkedHashSet(
            signals.apply(
              listOf(FiredHandler(binding.signalName, binding.handler, event)),
              compiledNow.signals,
            )
          )
        applyFired(changed, compiledNow)
      }
    }
  }

  /**
   * Stops everything waiting: the timers, and any debounced handler that has not fired.
   *
   * A host with a scheduler has to call this when the chart goes away — a timer that outlives the
   * view it draws into is a leak with a repaint attached to it. Harmless without one, and safe to
   * call twice.
   */
  public fun stop() {
    stopTimers()
    for (task in pendingDebounces.values) task.cancel()
    pendingDebounces.clear()
  }

  private fun stopTimers() {
    for (task in timerTasks) task.cancel()
    timerTasks = emptyList()
    timerKeys = emptyList()
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

  /**
   * The tooltip under the pointer, as lines a host can show, or null where there is nothing to
   * show.
   *
   * The value itself is in `snapshot.interactionState.tooltip`, and every host reported it and then
   * had to work out what to do with it. This is that step, done once and in the chart's own
   * **locale**, so a number in a tooltip is written the way the number on the axis beside it is
   * written. Null covers the case that used to trip hosts up as well as the obvious one: a mark
   * with no `tooltip` channel produces an *empty object*, which is not a tooltip.
   *
   * `interactionState.tooltipAnchor` is where to put it — the point the host dispatched, in its own
   * pixels, so no conversion is needed on the way back.
   */
  public val tooltipContent: TooltipContent?
    get() = TooltipContent.of(snapshot.interactionState.tooltip, locale, timeZone)

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
