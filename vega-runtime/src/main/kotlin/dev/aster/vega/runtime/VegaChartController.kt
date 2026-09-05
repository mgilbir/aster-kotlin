@file:OptIn(ExperimentalAtomicApi::class, InternalAsterVegaApi::class)

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
import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.model.spec.EventConfig
import dev.aster.vega.model.spec.EventStream
import dev.aster.vega.model.spec.MarkSpec
import dev.aster.vega.model.spec.MarkType
import dev.aster.vega.runtime.compile.CompiledSpec
import dev.aster.vega.runtime.compile.ItemEncode
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.interaction.EventDispatcher
import dev.aster.vega.runtime.interaction.FiredHandler
import dev.aster.vega.runtime.interaction.HandlerBinding
import dev.aster.vega.runtime.interaction.InputEvent as VegaEvent
import dev.aster.vega.runtime.interaction.ScheduledTask
import dev.aster.vega.runtime.interaction.Scheduler
import dev.aster.vega.runtime.interaction.SignalUpdater
import dev.aster.vega.runtime.load.CachingDataLoader
import dev.aster.vega.runtime.load.DataLoader
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.runtime.scale.InvertibleScale
import dev.aster.vega.runtime.scale.PositionScale
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.AccessibilityTree
import dev.aster.vega.scene.AccessibleElement
import dev.aster.vega.scene.ChartAction
import dev.aster.vega.scene.ChartActionKind
import dev.aster.vega.scene.HitResult
import dev.aster.vega.scene.HitTestOptions
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneHitIndex
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeId
import dev.aster.vega.scene.SizeD
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.flatten
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndDecrement
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.concurrent.atomics.incrementAndFetch
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
import kotlinx.coroutines.flow.update
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
  /**
   * Why the last compile drew nothing, or null when the chart on screen is the one asked for.
   *
   * A compile that produces no scene deliberately **keeps** the previous [snapshot] — a reader
   * holds on to what they were looking at rather than watching it blank — which leaves the state
   * saying nothing about what just happened. Without this, a host wanting its own "this chart
   * cannot be drawn" copy has to infer the failure from [diagnostics], and that inference is wrong
   * in both directions: `PARSE_NOTHING_TO_DRAW` is INFO by deliberate choice, and a document can
   * report several warnings and still draw perfectly.
   *
   * So it is stated instead. Set to the first ERROR or FATAL diagnostic's message where a compile
   * produced no scene, and to a plain sentence where nothing said anything more useful; cleared by
   * the next compile that does produce one, and by [VegaChartController.setScene]. `ChartSession`
   * on the Swift side carries the same value under the same rules, so a host expressing this logic
   * expresses it identically on both platforms.
   *
   * Not a substitute for [diagnostics]: this says the chart is absent, and those say what the
   * specification asked for that could not be done. A chart can draw with errors in it.
   */
  val failure: String? = null,
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
 * (ADR 0007). The controller itself does no Android work and no drawing; it holds immutable state
 * and hands it to whichever surface is rendering.
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

  /**
   * The host's loader, wrapped so a URL is fetched **once per document** rather than once per
   * compile.
   *
   * Every interaction here recompiles the whole specification and a compile resolves every dataset
   * from scratch, so with a loader opted in a tap issued a blocking GET per `url` dataset — on the
   * dispatching thread, with the loader's own timeouts — and a `{"type": "timer", "throttle": 500}`
   * stream polled the network twice a second. Upstream loads once, because its dataflow only
   * re-runs what changed; this is what makes "correct, and slower" honest rather than merely slow.
   */
  private val loads = CachingDataLoader(loader)

  private var compiler = newCompiler(containerSize, hostData)

  private fun newCompiler(size: SizeD?, data: Map<String, List<VegaValue>>?) =
    SpecCompiler(
      textEngine,
      loads,
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
      val generation = nextRequest()
      val compiled =
        compileNow(compiler, json, signals.overrides, signals.itemEncodes, signals.scopedOverrides)
      if (isCurrent(generation)) publish(compiled)
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
  public var containerSize: SizeD?
    get() = container
    set(value) {
      // Runs on the calling thread, like [setSpec]. A host that reports its layout size on every
      // resize should prefer [setContainerSizeAsync], which is the same work on a dispatcher.
      val work = adoptContainerSize(value) ?: return
      val generation = nextRequest()
      val compiled = compileNow(work.compiler, work.json, signals.overrides, signals.itemEncodes)
      if (isCurrent(generation)) publish(compiled)
    }

  private var container: SizeD? = containerSize

  /** A recompile that is worth doing, with the compiler that was current when it was decided. */
  private class SizeRecompile(val json: String, val compiler: SpecCompiler)

  /**
   * Records a new container size, and answers the recompile it implies — or null for none.
   *
   * Three reasons there may be none, and the third is the one that was missing. The size may be
   * unchanged. There may be no specification loaded. Or **the loaded document may never ask**: the
   * setter used to recompile unconditionally, so a host that reports its layout size on every
   * resize paid a full compile for every chart it draws, including every chart that declares its
   * own width and height — which is most of them. `CompiledSpec.readsContainerSize` is the exact
   * answer to that question, and it comes from the expressions rather than from the text, because
   * `width: "container"` reaches the engine as a signal whose `update` calls `containerSize()`.
   *
   * The size is recorded and the compiler replaced whatever happens, so a specification loaded
   * *after* a skipped resize still compiles against the size the host stated.
   *
   * The compiler is carried out with the decision rather than read again later: two overlapping
   * asynchronous sets would otherwise both compile with whichever size arrived last, and one of
   * them would publish a scene that does not match the size it was asked for.
   */
  private fun adoptContainerSize(value: SizeD?): SizeRecompile? {
    if (container == value) return null
    container = value
    compiler = newCompiler(value, hostData)
    val json = loadedSpecJson ?: return null
    if (lastCompiled?.readsContainerSize == false) return null
    return SizeRecompile(json, compiler)
  }

  /**
   * The same as assigning [containerSize], off the calling thread.
   *
   * Why this exists rather than being advice. A resize arrives on whatever thread runs a host's
   * layout, which on both hosts is the main one, and the assignment above recompiles inline — so a
   * chart sized to its container paid a compile on the main thread for every step of a split-view
   * drag. This is that work on a dispatcher, serialized against every other compile through the
   * same lock, and awaitable, which is what lets a host or a test synchronise on the chart having
   * caught up.
   *
   * Answers **null** where nothing was recompiled: the size was already this, no specification is
   * loaded, or — the common case — the loaded document never reads `containerSize()`. Null is not a
   * failure; it means the chart a reader is looking at is still the right one.
   */
  public suspend fun setContainerSizeAsync(size: SizeD?): CompiledSpec? =
    setContainerSizeAsync(size, Dispatchers.Default)

  /**
   * The same, on a dispatcher of the caller's choosing.
   *
   * A host that is not Kotlin should call the single-argument form: a default argument does not
   * survive the Obj-C boundary, so from Swift this overload demands a `CoroutineDispatcher` that no
   * exported symbol can produce. The same reason [setSpecAsync] is spelled twice.
   */
  public suspend fun setContainerSizeAsync(
    size: SizeD?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
  ): CompiledSpec? {
    val work = adoptContainerSize(size) ?: return null
    val generation = nextRequest()
    _state.update { it.copy(isLoading = true) }
    val compiled =
      try {
        compileLock.withLock {
          withContext(dispatcher) {
            compileNow(work.compiler, work.json, signals.overrides, signals.itemEncodes)
          }
        }
      } catch (e: CancellationException) {
        _state.update { it.copy(isLoading = false) }
        throw e
      }
    // A resize that has been overtaken publishes nothing: the compile it raced is the one the host
    // asked for last, and putting this scene on screen after it would show a size nobody is at.
    if (!isCurrent(generation)) {
      _state.update { it.copy(isLoading = false) }
      return compiled
    }
    return publish(compiled)
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
   *
   * The scope path is part of it: two cells of a faceted group declare the same signal name at the
   * same throttle and are two timers, so a key that named only the signal would collapse them and
   * leave one cell ticking for both.
   */
  private var timerKeys: List<Triple<String, String, Double>> = emptyList()

  /**
   * Domains a reader has adjusted from an axis, by scale name, and how far from where they started.
   *
   * The interval reaches the scale through `domainRaw`, which is upstream's own door for a control
   * choosing an exact interval, so `zero`, `nice` and the `domain*` limits are all short-circuited
   * and the axis is recomputed against what the reader picked — ticks, labels and every mark placed
   * through the scale.
   *
   * The factor beside it is only for the limits. Each step is computed from the scale as it stands,
   * so the interval is always the one on screen narrowed once more; the factor is what says when to
   * stop offering another step, the way [MIN_ZOOM] and [MAX_ZOOM] do for the viewport.
   *
   * Empty for every chart nobody has adjusted, which is almost all of them.
   */
  private var pinnedDomains: Map<String, List<Double>> = emptyMap()

  private var domainFactors: Map<String, Double> = emptyMap()

  /**
   * Serializes the two `…Async` entry points against each other. **Not every compile** — see
   * [compilesRunning], which is what covers the synchronous paths and why they cannot take this.
   */
  private val compileLock = Mutex()

  /**
   * Stamps every compile request, so a slow one cannot overwrite a newer answer.
   *
   * `setSpecAsync(A)` in flight, the host calls `setSpec(B)`, A resumes and publishes: the reader
   * saw B for a moment and is now looking at A, and every subsequent interaction recompiles A
   * because `loadedSpecJson` was overwritten too. The compile itself ran under `compileLock`; the
   * three lines *after* it — `loadedSpecJson`, `signals.reset()`, `publish` — did not, and there
   * was no epoch anywhere in the class to compare against.
   *
   * Every entry point takes a generation at the moment it is called, and a publish happens only
   * while that is still the newest. Last writer wins, which is what a host reading these names
   * expects and what the class documentation claimed.
   */
  private val requests = AtomicLong(0L)

  private val newestRequest = AtomicLong(0L)

  /**
   * How many compiles are running.
   *
   * The lock above serializes the two `…Async` entry points and nothing else: `setSpec`, the
   * `hostData` and `containerSize` setters and every interaction recompile call the compiler
   * inline, and a `Mutex` cannot be taken from a function that does not suspend. Kotlin's common
   * standard library has no blocking lock, and this module's source set cannot reach `runBlocking`
   * — so a *fully* serialized sync path would mean an `expect`/`actual` per target and a main
   * thread that blocks for the length of a background compile, which is an ANR on Android by
   * another name.
   *
   * What is here instead: one place that every compile goes through, and a counter that turns the
   * race from a silent one into a reported one. A host that sees `VEGA_COMPILE_CONCURRENT` is being
   * told the exact thing it can act on — that it is mixing the synchronous and asynchronous entry
   * points, which the class documentation asks it not to do.
   */
  private val compilesRunning = AtomicInt(0)

  private var nextRevision = AtomicLong(initialScene.revision + 1)

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
    val revision = nextRevision.fetchAndIncrement()
    hitIndex = SceneHitIndex(scene, hitOptions)
    _state.update { previous ->
      previous.copy(
        snapshot =
          ChartSnapshot(
            scene = scene.copy(revision = revision),
            interactionState = previous.snapshot.interactionState,
            revision = revision,
          ),
        isLoading = false,
        // A hand-authored scene is a chart on screen by definition, so a failure recorded by an
        // earlier compile no longer describes anything.
        failure = null,
      )
    }
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
   *
   * **One controller draws into one view.** This property is the reason it has to be said: two
   * views of different sizes sharing a controller each write their own fit here, so the last one
   * laid out wins and the other one's taps miss by the ratio between them. Nothing else in the
   * class is per-view, so this is the whole of the restriction — and the same controller behind two
   * views is otherwise a reasonable-looking thing to try, which is why it is now refused loudly in
   * a debug build rather than producing a chart that ignores half its taps.
   *
   * A host that genuinely wants two views of one chart builds two controllers from the same
   * specification; they compile to the same scene, which is a value.
   */
  public var contentScale: Double = 1.0
    set(value) {
      // A zero or non-finite scale would make the inverse mapping meaningless; ignore it rather
      // than poisoning every subsequent hit test.
      if (value > 0.0 && value.isFinite()) {
        // Two *different* non-trivial fits mean two views. Reported rather than thrown: a host may
        // legitimately be re-laying-out one view, and a resize writes the same property.
        if (field != 1.0 && value != field) {
          report(
            VegaDiagnostic(
              severity = DiagnosticSeverity.WARNING,
              code = DiagnosticCodes.INTERACTION_UNSUPPORTED,
              message =
                "contentScale changed from $field to $value. If two views share this controller, " +
                  "each will overwrite the other's fit and one of them will hit-test wrongly: a " +
                  "controller belongs to one view. If this is a resize, ignore this.",
            )
          )
        }
        field = value
      }
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
    // A new document is a new decision about whether this chart runs; see [stop] — and a new set of
    // data, so nothing the previous one downloaded is kept.
    stopped = false
    loads.clear()
    val generation = nextRequest()
    val compiled = compileNow(compiler, json)
    if (!isCurrent(generation)) return compiled
    loadedSpecJson = json
    signals.reset()
    // A drag does not span two documents. `publish` carries the open `between` latches into the
    // dispatcher it builds, which is what lets a brush survive the recompile its own first event
    // causes; a new specification is the one time that would be wrong, since the latch belongs to
    // streams that no longer exist.
    vegaEvents = null
    return publish(compiled)
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

  /**
   * How each top-level scale resolved at the last publish, so the next one can tell which moved.
   *
   * The whole of what a scale-sourced handler needs, and the reason it is a fingerprint rather than
   * the scales themselves: keeping the previous `CompiledSpec` alive would hold its scene too, and
   * a scene is the largest thing this class owns. See [scaleCascade].
   */
  private var scaleFingerprints: Map<String, String> = emptyMap()

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
   * The two `…Async` entry points are serialized **against each other**, so two of them never touch
   * the text engine at once. They are not serialized against `setSpec`, the `hostData` and
   * `containerSize` setters or an interaction's recompile, which call the compiler inline and
   * cannot take a `Mutex` from a function that does not suspend — see [compilesRunning] for why
   * that is a report rather than a lock, and for the diagnostic a host gets when it happens. This
   * sentence used to say "compilations are serialized", unqualified, which is the claim the audit
   * was right to call the class's own.
   *
   * The host still must not hand this controller the engine its renderer draws with; see the class
   * docs.
   *
   * A host that is not Kotlin should call the single-argument [setSpecAsync] instead: a default
   * argument does not survive the Obj-C boundary, so from Swift this overload demands a
   * `CoroutineDispatcher` that no exported symbol can produce.
   */
  public suspend fun setSpecAsync(
    json: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
  ): CompiledSpec {
    stopped = false
    loads.clear()
    val generation = nextRequest()
    _state.update { it.copy(isLoading = true) }
    val compiled =
      try {
        compileLock.withLock { withContext(dispatcher) { compileNow(compiler, json) } }
      } catch (e: CancellationException) {
        _state.update { it.copy(isLoading = false) }
        throw e
      }
    // **The generation check, and it is the whole of C5.** These three lines used to run
    // unconditionally, outside the lock: a `setSpecAsync(A)` that was still compiling when the host
    // called `setSpec(B)` resumed here and overwrote B — the reader saw B for a moment and was left
    // looking at A, and every interaction from then on recompiled A, because `loadedSpecJson` had
    // been overwritten too.
    if (!isCurrent(generation)) {
      _state.update { it.copy(isLoading = false) }
      return compiled
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
    // A drag does not span two documents. `publish` carries the open `between` latches into the
    // dispatcher it builds, which is what lets a brush survive the recompile its own first event
    // causes; a new specification is the one time that would be wrong, since the latch belongs to
    // streams that no longer exist.
    vegaEvents = null
    return publish(compiled)
  }

  /**
   * The one place `SpecCompiler` is called from, whichever entry point asked.
   *
   * See [compilesRunning] for what this can and cannot serialize.
   */
  private fun compileNow(
    compiler: SpecCompiler,
    json: String,
    overrides: Map<String, VegaValue> = emptyMap(),
    itemEncodes: Map<SceneNodeId, ItemEncode> = emptyMap(),
    scopedOverrides: Map<String, Map<String, VegaValue>> = emptyMap(),
  ): CompiledSpec {
    val concurrent = compilesRunning.fetchAndIncrement() > 0
    try {
      val compiled =
        compiler.compileJsonWithDomains(
          json,
          overrides,
          itemEncodes,
          scopedOverrides,
          pinnedDomains,
        )
      if (!concurrent) return compiled
      return compiled.copy(
        diagnostics =
          compiled.diagnostics +
            VegaDiagnostic(
              severity = DiagnosticSeverity.WARNING,
              code = DiagnosticCodes.COMPILE_CONCURRENT,
              message =
                "Two compiles ran at once on this controller, which share one text engine and one " +
                  "signal table. Use the suspending entry points for every change, or make every " +
                  "call from one thread; mixing them is what this reports.",
            )
      )
    } finally {
      compilesRunning.fetchAndDecrement()
    }
  }

  /** A request number, taken at the moment a caller asks rather than when the work starts. */
  private fun nextRequest(): Long {
    val generation = requests.incrementAndFetch()
    newestRequest.store(generation)
    return generation
  }

  /** Whether [generation] is still the newest request, and so still worth publishing. */
  private fun isCurrent(generation: Long): Boolean = newestRequest.load() == generation

  /**
   * Every handler in the specification, including those declared inside a group mark.
   *
   * `publish` used to read `spec.signals` — the top level only — so a handler declared in a group
   * carried its streams into a compile that nothing ever dispatched to. Vega's own
   * `overview-plus-detail` is that shape, and brushing its overview changed nothing at all.
   *
   * The **declaration** comes from the specification and the **instances** from
   * [CompiledSpec.groupScopes], which is what says how many times a group was drawn. A group drawn
   * once is bound at its own path; a **faceted** one is bound once per cell, at each cell's path,
   * so a chart of small multiples gets one live copy of its handler per multiple.
   *
   * That is upstream's shape and not a convenience: a faceted group's signals are per-cell — each
   * cell has its own `brush`, its own `hover` — and binding one handler for the whole group would
   * make every cell share one, so brushing one small multiple would move the brush in all of them.
   * [scopesAt] is what keeps them apart: each binding fires only for an event whose item is inside
   * that cell.
   */
  private fun bindingsOf(
    compiled: CompiledSpec,
    diagnostics: DiagnosticCollector,
  ): List<HandlerBinding> {
    val spec = compiled.spec ?: return emptyList()
    val bindings =
      spec.signals
        .flatMap { signal -> signal.on.map { HandlerBinding(signal.name, it) } }
        .toMutableList()

    // The walk carries the **concrete** scopes of the enclosing group, not its path in the
    // specification: a group inside a faceted one is a different scope in each cell — `cell/
    // cells[0]/inner` and `cell/cells[1]/inner` — and a single spec-shaped prefix names neither.
    fun walk(marks: List<MarkSpec>, prefixes: List<String>) {
      marks.forEachIndexed { index, mark ->
        if (mark.type != MarkType.GROUP) return@forEachIndexed
        // Every scope this group was drawn as, under every scope its parent was drawn as, paired
        // with the parent that produced it — which is where a `push: "outer"` writes. Its own path
        // where it was drawn once, and one `.../cells[n]` per cell where it was faceted;
        // `cellPath` is the compiler's, and this is its only reader, so both shapes are asked for.
        // The `cells[n]` match is anchored so a *nested* group's scope is not mistaken for a cell
        // of this one.
        val instances = prefixes.flatMap { prefix ->
          val here = (if (prefix.isEmpty()) "" else "$prefix/") + (mark.name ?: "[$index]")
          val cells = Regex(Regex.escape(here) + """/cells\[\d+]""")
          if (here in compiled.groupScopes) listOf(here to prefix)
          else compiled.groupScopes.keys.filter { cells.matches(it) }.map { it to prefix }
        }
        val handlers = mark.signals.sumOf { it.on.size }
        for ((scopePath, prefix) in instances) {
          for (signal in mark.signals) {
            // A `push: "outer"` handler reads *here* and writes *there*: its update sees the
            // group's own signals and scales — `invert('xOverview', brush)` is both at once — and
            // the value it produces belongs to the enclosing scope's signal of that name. That is
            // the whole of how a group hands a value back out.
            val writes = if (signal.pushesOuter) prefix else scopePath
            for (handler in signal.on) {
              bindings += HandlerBinding(signal.name, handler, scopePath, writePath = writes)
            }
          }
        }
        if (instances.isEmpty() && handlers > 0) {
          // A group that declares handlers and was drawn as no scope at all — the compile failed
          // before it, or its facet produced no cells. Reported rather than silently unbound, which
          // is what this whole change is about.
          diagnostics.warn(
            DiagnosticCodes.INTERACTION_UNSUPPORTED,
            "Group '${mark.name ?: "[$index]"}' declares $handlers signal handler(s) and was not " +
              "drawn, so there is no scope to dispatch them in and they do not fire",
            operator = mark.name,
          )
        }
        walk(mark.marks, instances.map { it.first })
      }
    }
    walk(spec.marks, listOf(""))
    return bindings
  }

  private fun publish(compiled: CompiledSpec): CompiledSpec {
    lastCompiled = compiled
    // Before anything else, and on **every** path that publishes a compile — including the first
    // one. A scale-sourced handler asks whether a scale moved, and the answer is only meaningful
    // against a baseline; recording it only where a handler fires would compare the second compile
    // against nothing and fire on the whole specification.
    scaleFingerprints = fingerprintScales(compiled)
    // Built before the collector below is read, because a group whose handlers cannot be bound is
    // reported here rather than at the compiler: it is a property of dispatch, not of the document.
    val listenerDiagnostics = DiagnosticCollector()
    val bindings = bindingsOf(compiled, listenerDiagnostics)
    // The dispatcher reports as it registers — a stream a policy blocked, a debounce nothing can
    // schedule — and those went into a collector nobody read. They are published with the
    // compiler's
    // own, since a listener that was refused is exactly the kind of thing a host needs told.
    startTimers(compiled, bindings)
    // **The open `between` latches, carried across.** A drag outlives a recompile and a recompile
    // is what a fired handler causes, so the latch a `mousedown` had just opened was thrown away
    // before the `mousemove` it gates arrived — the dispatcher is rebuilt here and every `Gate`
    // starts closed. That is the standard brush idiom, an `anchor` set on `mousedown` beside a
    // `brush` on `[mousedown, mouseup] > mousemove`, so the first drag of every brush was lost.
    //
    // It hid because the failure depends on whether the opening event *changed* anything: a second
    // drag from the same point sets `anchor` to the value it already had, changes no signal,
    // rebuilds nothing, and works. Upstream never rebuilds its streams at all — a `View`'s dataflow
    // outlives every signal update — so carrying these is what matches it. The same lesson
    // `startTimers` above records for timers, which were being cancelled mid-flight for the same
    // reason.
    val carriedLatches = vegaEvents?.openLatches().orEmpty()
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
          scopes = compiled.groupScopes,
          openLatches = carriedLatches,
        )
      }
    val diagnostics = compiled.diagnostics + listenerDiagnostics.diagnostics
    _diagnostics.value = diagnostics
    // Re-read rather than remembered: a signal a handler or a timer changed has to move the control
    // that shows it, and the compile that just happened is where its new value is.
    _inputs.value = SignalInput.of(compiled.spec?.signals.orEmpty(), compiled.signals.values)
    val scene = compiled.scene
    if (scene == null) {
      // The snapshot is kept — see `ChartState.failure` — so this is the only thing that says a
      // compile just failed. The first ERROR or FATAL is what a reader is shown, and the fallback
      // covers a compile that produced neither a scene nor a complaint, which is a bug elsewhere
      // but must not read as success here.
      _state.update {
        it.copy(
          isLoading = false,
          diagnostics = diagnostics,
          failure =
            diagnostics.firstOrNull { d -> d.severity >= DiagnosticSeverity.ERROR }?.message
              ?: "the specification compiled to no scene",
        )
      }
      return compiled
    }
    val revision = nextRevision.fetchAndIncrement()
    val published = scene.copy(revision = revision)
    hitIndex = SceneHitIndex(published, hitOptions)
    _state.update { previous ->
      ChartState(
        snapshot =
          ChartSnapshot(
            scene = published,
            interactionState = previous.snapshot.interactionState,
            revision = revision,
          ),
        isLoading = false,
        diagnostics = diagnostics,
        // Explicitly, rather than by the constructor default: a scene was produced, so whatever the
        // last failure was is over, and saying so here is what keeps a stale message off the
        // screen.
        failure = null,
      )
    }
    return compiled
  }

  public fun report(diagnostic: VegaDiagnostic) {
    _diagnostics.value = _diagnostics.value + diagnostic
    _state.update { it.copy(diagnostics = _diagnostics.value) }
  }

  public fun clearDiagnostics() {
    _diagnostics.value = emptyList()
    _state.update { it.copy(diagnostics = emptyList()) }
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
   * scene, which is what keeps a hover from rerunning the dataflow (ADR 0012).
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
      is ChartInputEvent.Key -> handleKey(event.key, event.modifiers)
      is ChartInputEvent.PointerDown,
      is ChartInputEvent.PointerUp,
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
        // `keydown` is one of the events upstream's own handler binds on the **view** element —
        // `vega-scenegraph`'s `Events` list has it beside `pointerdown` — so it is a view stream
        // here too, and a specification writing `{"events": "keydown"}` is asking for something
        // this engine can actually deliver. `keyup` and `keypress` are on that list as well and
        // are *not* produced: a host reports one `ChartInputEvent.Key` per press with no phase,
        // so there is nothing to tell a release from a repeat. They are refused by name at
        // registration rather than left to never match.
        is ChartInputEvent.Key -> listOf("keydown")
        else -> return
      }
    val point = pointOf(event)
    val scenePoint = point?.let { toSceneSpace(it) }
    val hitResult = scenePoint?.let { hitIndex.hitTest(it) }
    val hit = hitResult?.node
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
            properties = (event as? ChartInputEvent.Key)?.let { keyProperties(it) } ?: emptyMap(),
            scopes = scopesAt(compiled, hitResult),
          )
        )
      // A stream carrying a `debounce` is *held* rather than applied: it fires after a quiet
      // period, so each event cancels the one waiting and starts the wait again — upstream's
      // `debounce`, which schedules with the latest event and keeps only that one. With no
      // scheduler in hand nothing is deferred and the dispatcher says so instead.
      val (deferred, immediate) = fired.partition { it.deferByMillis != null }
      for (entry in deferred) defer(entry)
      if (immediate.isNotEmpty()) {
        changed += signals.apply(immediate, compiled.signals, compiled.groupScopes)
      }
    }
    applyFired(changed, compiled)
  }

  /**
   * Which group scopes an event is **in**, by the paths the compiler recorded them under.
   *
   * The answer every `scope`-sourced handler needs, and the reason those used to be refused: a
   * handler declared in a group listens on that group's own item, so `{"events": "mousedown"}`
   * inside one means "a mousedown anywhere in this group", and the dispatcher has neither the scene
   * nor the point in world space.
   *
   * **Ancestry of the item that was hit, not geometry.** Upstream compiles `inScope(event.item)`
   * into every scope-sourced stream, and its `inScope` walks `item.mark.group` upwards looking for
   * the scope's own group item. Containment of the group's rectangle is a different question and
   * gives a different answer twice over: a press on the group's background where it has no fill is
   * inside the rectangle and hits no item, so upstream does *not* fire; and a mark that overflows
   * an unclipped group is outside the rectangle and still upstream's descendant, so it does. The
   * hit index already carries the chain — `HitResult.ancestors` — so this is the cheaper reading as
   * well as the faithful one, and it drops a whole-scene walk from every pointer event.
   *
   * The chain is matched against the **id the compiler recorded for that cell**, not against mark
   * names: the scene holds group nodes for axes and legends too, and pairing them to scopes by
   * shape is the inference that has been wrong every time it was tried here.
   */
  private fun scopesAt(compiled: CompiledSpec, hit: HitResult?): Set<String> {
    if (hit == null || compiled.groupNodes.isEmpty()) return emptySet()
    val chain = HashSet<SceneNodeId>(hit.ancestors.size + 1)
    chain += hit.node.id
    for (ancestor in hit.ancestors) chain += ancestor.id
    return compiled.groupNodes.filterValues { it in chain }.keys
  }

  /**
   * What `event.key` and its neighbours answer, in the browser's own vocabulary.
   *
   * A specification reads `event.key` because that is what a `KeyboardEvent` carries, so these are
   * the DOM's names and numbers rather than this engine's enum — `ArrowRight` and 39, not
   * `ARROW_RIGHT`. A chart written for the web has to keep working, and a reader of the
   * specification should not have to learn a second set of names.
   *
   * `keyCode` is deprecated in the DOM and still carried, because a specification written any time
   * in the last fifteen years may read it and no substitute reaches the same charts.
   */
  private fun keyProperties(event: ChartInputEvent.Key): Map<String, VegaValue> {
    val (name, code) =
      when (event.key) {
        ChartKey.ARROW_LEFT -> "ArrowLeft" to 37
        ChartKey.ARROW_UP -> "ArrowUp" to 38
        ChartKey.ARROW_RIGHT -> "ArrowRight" to 39
        ChartKey.ARROW_DOWN -> "ArrowDown" to 40
        ChartKey.ENTER -> "Enter" to 13
        ChartKey.SPACE -> " " to 32
        ChartKey.ESCAPE -> "Escape" to 27
        ChartKey.TAB -> "Tab" to 9
        ChartKey.HOME -> "Home" to 36
        ChartKey.END -> "End" to 35
      }
    return mapOf(
      "key" to VegaValue.Str(name),
      "keyCode" to VegaValue.Num(code.toDouble()),
      "shiftKey" to VegaValue.Bool(event.modifiers.shift),
      "ctrlKey" to VegaValue.Bool(event.modifiers.control),
      "altKey" to VegaValue.Bool(event.modifiers.alt),
      "metaKey" to VegaValue.Bool(event.modifiers.meta),
    )
  }

  /**
   * Holds a handler until its stream has been quiet for as long as it opened for.
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
    val generation = nextRequest()
    // Captured before the recompile replaces it: this is the "before" a scale-sourced handler is
    // asking about.
    var before = scaleFingerprints
    val compiled =
      compileNow(compiler, json, signals.overrides, signals.itemEncodes, signals.scopedOverrides)
    if (!isCurrent(generation)) return
    publish(compiled)
    // **Then the scales.** A handler sourced on a scale fires here and not in [cascade]: "did this
    // scale move" is a question only a completed recompile can answer. Each round changes a signal,
    // which is another recompile, which can move a further scale.
    var latest = compiled
    var round = 0
    while (true) {
      val fired = scaleCascade(latest, before)
      if (fired.isEmpty()) break
      if (++round > MAX_CASCADE_ROUNDS) {
        report(
          VegaDiagnostic(
            code = DiagnosticCodes.SIGNAL_CYCLE,
            severity = DiagnosticSeverity.WARNING,
            message =
              "Signals '${fired.sorted().joinToString("', '")}' are still changing after " +
                "$MAX_CASCADE_ROUNDS rounds of scale-driven handlers, so a signal and a scale are " +
                "moving each other; their last values are kept",
          )
        )
        break
      }
      before = scaleFingerprints
      val next = nextRequest()
      val recompiled = compileNow(compiler, json, signals.overrides, signals.itemEncodes)
      if (!isCurrent(next)) return
      publish(recompiled)
      latest = recompiled
    }
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
    // Every scope, not only the top level: `detailDomain` in Vega's `overview-plus-detail` is
    // sourced on `{"signal": "brush"}`, and `brush` is the group's own. Reusing the same walk as
    // the event bindings is what keeps the two from disagreeing about which handlers exist; the
    // diagnostics it can raise were already raised by `publish`, so they are dropped here.
    val bindings =
      bindingsOf(compiled, DiagnosticCollector()).filter {
        it.handler.signalSources.isNotEmpty()
      }
    if (bindings.isEmpty()) return emptyList()
    var frontier: Set<String> = changed.toSet()
    var round = 0
    while (frontier.isNotEmpty()) {
      if (++round > MAX_CASCADE_ROUNDS) {
        return listOf(
          VegaDiagnostic(
            code = DiagnosticCodes.SIGNAL_CYCLE,
            severity = DiagnosticSeverity.WARNING,
            message =
              "Signals '${frontier.sorted().joinToString("', '")}' are still changing after " +
                "$MAX_CASCADE_ROUNDS rounds of signal-driven handlers, so they are on a cycle; " +
                "their last values are kept",
          )
        )
      }
      // A source is a bare name written in the scope that declared the handler, and the frontier
      // holds qualified ones. So a handler in a group matches its *own* scope's signal first and
      // an enclosing one after — which is the ordinary shadowing rule, applied to what changed.
      val due =
        bindings
          .filter { binding ->
            binding.handler.signalSources.any { source ->
              (binding.scopePath.isNotEmpty() && "${binding.scopePath}/$source" in frontier) ||
                source in frontier
            }
          }
          .map {
            FiredHandler(
              it.signalName,
              it.handler,
              scopePath = it.scopePath,
              writePath = it.writePath,
            )
          }
      if (due.isEmpty()) return emptyList()
      // Read against the overrides accumulated so far, which is what makes a chain see the value
      // the
      // round before it produced rather than the one the last compile resolved.
      frontier = signals.apply(due, compiled.signals, compiled.groupScopes)
      changed += frontier
    }
    return emptyList()
  }

  /**
   * Fires the handlers whose source is a **scale**, for the scales a recompile actually moved.
   *
   * `{"events": {"scale": "x"}}` fires when the scale is rebuilt. Upstream knows which one that is
   * because a scale is an operator in its dataflow and only re-runs when an input changed. Here a
   * changed signal recompiles the whole specification, so *every* scale is rebuilt every time —
   * which is why this used to be reported and refused: firing on all of them would run the handler
   * when nothing about the scale had changed, and a handler that fires spuriously is worse than one
   * that does not fire.
   *
   * So "rebuilt" is answered as "resolves differently", by comparing the scale against the one the
   * previous compile produced. That is closer to upstream than firing always, and it needs no
   * incremental dataflow — which the measurement said this engine does not want, a full recompile
   * of the heaviest fixture costing 366 microseconds against a 16,600 microsecond frame.
   *
   * A fired handler can change a signal, which recompiles, which can move another scale; the round
   * bound is [MAX_CASCADE_ROUNDS], the same one the signal cascade uses and for the same reason.
   */
  private fun scaleCascade(compiled: CompiledSpec, previous: Map<String, String>): Set<String> {
    val moved =
      scaleFingerprints
        .filterKeys { it in previous }
        .filter { (name, print) -> previous.getValue(name) != print }
        .keys
    if (moved.isEmpty()) return emptySet()
    val due =
      compiled.spec?.signals.orEmpty().flatMap { signal ->
        signal.on
          .filter { handler -> handler.scaleSources.any { it in moved } }
          .map {
            FiredHandler(signal.name, it)
          }
      }
    if (due.isEmpty()) return emptySet()
    return signals.apply(due, compiled.signals)
  }

  /**
   * How each of [compiled]'s scales resolves, or nothing at all where no handler is asking.
   *
   * The guard is not an optimisation so much as a statement of cost: a scale source is the rarest
   * source there is — not one of Vega's ninety-three published examples uses it — so the
   * overwhelming majority of charts must pay nothing for it, and here they pay one `isEmpty` per
   * publish.
   */
  private fun fingerprintScales(compiled: CompiledSpec): Map<String, String> {
    val watched =
      compiled.spec?.signals.orEmpty().flatMapTo(mutableSetOf()) { signal ->
        signal.on.flatMap { it.scaleSources }
      }
    if (watched.isEmpty()) return emptyMap()
    return compiled.scales
      .filterKeys { it in watched }
      .mapValues { (_, scale) ->
        scale.movementFingerprint()
      }
  }

  /**
   * A value that changes when the scale would map things differently, and not otherwise.
   *
   * Sampled rather than structural, deliberately. The scale classes expose their resolved state
   * unevenly — some take `domain` and `range` as constructor properties, some derive them — so an
   * exhaustive `when` over the sealed hierarchy would read half of them through a different door
   * and would need editing every time a scale type is added. Every `VegaScale` can do one thing:
   * map a value. So this asks it to.
   *
   * What the sampling can miss is two scales differing only outside the probes, which would leave a
   * handler unfired. That is exactly today's behaviour for *every* scale, so the failure mode is no
   * worse than the one being replaced — where firing wrongly would have been.
   */
  private fun VegaScale.movementFingerprint(): String =
    FINGERPRINT_PROBES.joinToString("|") { probe -> scale(probe).toString() }

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
  private fun startTimers(compiled: CompiledSpec, all: List<HandlerBinding>) {
    val scheduler = this.scheduler
    if (stopped || scheduler == null) {
      stopTimers()
      return
    }
    // **From the same bindings the dispatcher gets**, which is the whole of the fix here. This read
    // `spec.signals` — the top level only — while `bindingsOf` beside it walks the group marks, so
    // a `{"type": "timer"}` declared inside a group was bound to the dispatcher and then never
    // dispatched to: nothing produces a timer event except this, and this had not been told. An
    // animation inside a trellis cell simply stood still, with no diagnostic.
    //
    // A timer is the one stream in a group that is **not** scope-filtered, and upstream says so by
    // construction: `parseStream` builds it as `scope.event(Timer, throttle)` and then replaces the
    // stream object with `{between, filter}`, dropping `source`, so the `inScope(event.item)` it
    // appends to every other scope-sourced stream is not appended to this one. It has no item to be
    // in scope of. What makes it the group's is only which scope its update reads and writes, and
    // that is [HandlerBinding.scopePath] — already correct, and already one binding per facet cell.
    val timers = all.flatMap { binding ->
      binding.handler.streams
        .filter { it.source == EventStream.SOURCE_TIMER }
        .map { stream -> binding to stream }
    }
    // The scope is part of the key: two cells of a facet declare the same signal name at the same
    // throttle and are two timers, not one.
    val keys = timers.map { (binding, stream) ->
      Triple(binding.scopePath, binding.signalName, stream.throttle ?: 0.0)
    }
    if (keys == timerKeys) return
    stopTimers()
    timerKeys = keys
    if (timers.isEmpty()) return
    val started = clock()
    timerTasks = timers.map { (binding, stream) ->
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
              listOf(
                FiredHandler(
                  binding.signalName,
                  binding.handler,
                  event,
                  scopePath = binding.scopePath,
                  writePath = binding.writePath,
                )
              ),
              compiledNow.signals,
              compiledNow.groupScopes,
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
    // **Latched.** `stop()` cancelled the timers and the next publish started them again, and a
    // host that keeps feeding `setData` to a detached view publishes constantly — so a chart the
    // host had explicitly stopped went on ticking against a view nobody is looking at. Cleared by
    // `setSpec`, which is a new document and therefore a new decision.
    stopped = true
    stopTimers()
    for (task in pendingDebounces.values) task.cancel()
    pendingDebounces.clear()
  }

  /** See [stop]: a latch, not a one-shot cancel. */
  private var stopped = false

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
    // The **anchor moves with the pointer**, even over one mark. Returning early on an unchanged
    // node froze `tooltipAnchor` at wherever the pointer entered, so a bubble a host places at that
    // point stayed at the mark's edge while the pointer walked across it. Only a *node* change
    // costs a republish of the scene; an anchor change is a cheaper update of the same one.
    if (current.hoveredNodeId == node?.id) {
      val anchor = if (node?.metadata?.tooltip != null) point else null
      if (current.tooltipAnchor != anchor) publishInteraction(current.copy(tooltipAnchor = anchor))
      return
    }

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

  /**
   * Moves focus between marks with the keyboard, and says whether the key was **consumed**.
   *
   * The return value is the whole reason this is a method of its own rather than a return type on
   * [dispatch]: a host that cannot tell whether the chart used a key has no way to avoid a **focus
   * trap**, where TAB never leaves the chart and, on a television where the d-pad is the keyboard,
   * the four arrows let a reader in and not out. Declining to claim keys was the previous answer to
   * that, and it was the right one while nothing moved.
   *
   * So the rule is that a key is consumed **only when it actually did something**:
   *
   * - **TAB is never consumed**, whatever the state. It is how a reader leaves, and no traversal
   *   worth having is worth trapping them for.
   * - An **arrow** at the end of the order is not consumed either, so focus continues outward
   *   rather than stopping dead. This is what makes a d-pad usable: pressing right past the last
   *   mark leaves the chart the way it would leave any other widget.
   * - `ENTER` and `SPACE` activate the focused element, and are consumed only when there is one and
   *   it is activatable — an axis caption is not.
   * - `ESCAPE` clears the focus and the selection, and is consumed only when there was something to
   *   clear, so a reader can press it twice to dismiss the sheet the chart sits in.
   *
   * The order is the accessibility tree's, not the drawing's: a reader moves through a chart the
   * way it reads. That is the same order and the same policy a screen reader gets, including the
   * summary that stands in for the marks of a dense chart — so keyboard and screen reader cannot
   * disagree about what is in the chart.
   */
  public fun handleKey(key: ChartKey, modifiers: Modifiers = Modifiers.None): Boolean {
    // Never, under any state. See above.
    if (key == ChartKey.TAB) return false

    val snapshot = _state.value.snapshot
    val elements =
      AccessibilityTree.elements(snapshot.scene, snapshot.interactionState.selection.nodeIds)
        .filter { it.nodeId != null }
    if (elements.isEmpty()) return false

    val current = snapshot.interactionState
    val at = elements.indexOfFirst { it.nodeId == current.focusedNodeId }

    fun focus(index: Int): Boolean {
      val target = elements.getOrNull(index) ?: return false
      if (target.nodeId == current.focusedNodeId) return false
      publishInteraction(current.copy(focusedNodeId = target.nodeId))
      return true
    }

    return when (key) {
      // A chart with nothing focused takes the first element from either direction, which is what
      // makes arriving at it by keyboard work at all.
      ChartKey.ARROW_RIGHT,
      ChartKey.ARROW_DOWN -> if (at < 0) focus(0) else focus(at + 1)
      ChartKey.ARROW_LEFT,
      ChartKey.ARROW_UP -> if (at < 0) focus(elements.lastIndex) else focus(at - 1)
      ChartKey.HOME -> focus(0)
      ChartKey.END -> focus(elements.lastIndex)
      ChartKey.ENTER,
      ChartKey.SPACE -> activateFocused(elements, at)
      ChartKey.ESCAPE -> {
        if (current.focusedNodeId == null && current.selection.isEmpty) {
          false
        } else {
          publishInteraction(current.copy(focusedNodeId = null, selection = ChartSelection.Empty))
          emit(ChartEvent.SelectionChanged(ChartSelection.Empty))
          true
        }
      }
      ChartKey.TAB -> false
    }
  }

  /**
   * `ENTER` on a focused mark, which reaches the dataflow exactly where a tap does.
   *
   * Deliberately the same path and the same events: a reader activating a bar by keyboard and one
   * tapping it must leave the chart in the same state, or a specification that reacts to a click
   * reacts to only half its audience.
   */
  private fun activateFocused(elements: List<AccessibleElement>, at: Int): Boolean {
    val element = elements.getOrNull(at) ?: return false
    // An axis caption is focusable and not activatable: offering an activation that does nothing is
    // the thing `AccessibleElement.activatable` was added to stop.
    if (!element.activatable) return false
    val id = element.nodeId ?: return false
    val node = nodeById(id) ?: return false
    val current = _state.value.snapshot.interactionState
    val selection =
      ChartSelection(
        nodeIds = setOf(id),
        datumIds = node.metadata.datumId?.let { setOf(it) } ?: emptySet(),
      )
    publishInteraction(current.copy(selection = selection, focusedNodeId = id))
    val centre = PointD(element.bounds.centerX, element.bounds.centerY)
    emit(ChartEvent.MarkClicked(id, node.metadata.markName, datumOf(node), centre))
    emit(ChartEvent.SelectionChanged(selection))
    return true
  }

  /** The scene node an accessibility element came from. */
  private fun nodeById(id: SceneNodeId): SceneNode? =
    _state.value.snapshot.scene.flatten().firstOrNull { it.node.id == id }?.node

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
    // A NaN delta poisons the viewport **permanently**: every subsequent offset is NaN, every hit
    // test maps to NaN and misses, and nothing short of `resetViewport()` recovers. A gesture
    // recogniser that divides by a zero-length interval produces one, and the zoom path already
    // guarded its factor — this one guarded nothing.
    if (!event.delta.dx.isFinite() || !event.delta.dy.isFinite()) {
      report(
        VegaDiagnostic(
          severity = DiagnosticSeverity.WARNING,
          code = DiagnosticCodes.INTERACTION_UNSUPPORTED,
          message =
            "Ignoring a pan whose delta is not a number: (${event.delta.dx}, ${event.delta.dy})",
        )
      )
      return
    }
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
          code = DiagnosticCodes.INTERACTION_UNSUPPORTED,
          message = "Ignoring a zoom with a non-positive scale factor: ${event.scaleFactor}",
        )
      )
      return
    }
    // The **anchor** as well as the factor: the arithmetic below multiplies the anchor into the
    // offset, so a NaN there poisons the viewport exactly as a NaN pan delta does.
    if (!event.anchor.x.isFinite() || !event.anchor.y.isFinite()) {
      report(
        VegaDiagnostic(
          severity = DiagnosticSeverity.WARNING,
          code = DiagnosticCodes.INTERACTION_UNSUPPORTED,
          message =
            "Ignoring a zoom whose anchor is not a number: (${event.anchor.x}, ${event.anchor.y})",
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

  /**
   * The chart-level actions worth offering **right now**, in the chart's own language.
   *
   * Recomputed from the viewport rather than fixed, and that is the point: an action is listed only
   * when invoking it would change something. A reader at the zoom limit is not offered a zoom, and
   * a chart at rest is not offered a reset — an action that does nothing is worse than one that was
   * never there, because the reader has no way to tell which they met.
   *
   * A host attaches these to the chart's **own** accessibility node, not to a mark:
   * `AccessibilityNodeInfo.addAction` on Android, `UIAccessibilityCustomAction` on Apple. That is
   * also why they are not on `AccessibleElement` — panning is a property of the view, and the
   * scene, which is what builds that tree, does not know it has been panned.
   */
  public val accessibilityActions: List<ChartAction>
    get() {
      val state = _state.value.snapshot.interactionState
      val actions = mutableListOf<ChartAction>()
      if (state.viewportScale < MAX_ZOOM) {
        actions += ChartAction(ChartActionKind.ZOOM_IN, locale.captions.zoomInAction())
      }
      if (state.viewportScale > MIN_ZOOM) {
        actions += ChartAction(ChartActionKind.ZOOM_OUT, locale.captions.zoomOutAction())
      }
      if (state.viewportScale != 1.0 || state.viewportOffset != dev.aster.vega.scene.VectorD.Zero) {
        actions += ChartAction(ChartActionKind.RESET_ZOOM, locale.captions.resetZoomAction())
      }
      // Only once an axis has actually been adjusted, by the same rule as the reset above: an
      // action that would do nothing is worse than one that was never offered. There is no narrow
      // or widen beside it — those are the increment and decrement of the axis itself, which is why
      // this list does not grow with the number of axes.
      if (pinnedDomains.isNotEmpty()) {
        actions += ChartAction(ChartActionKind.RESET_DOMAINS, locale.captions.resetAxesAction())
      }
      return actions
    }

  /**
   * Performs one, and says whether it did anything.
   *
   * The same false-means-nothing-happened contract [handleKey] uses, and for a related reason: a
   * host that cannot tell has to guess whether to announce a change, and announcing one that did
   * not happen is how a reader loses track of where they are.
   *
   * A zoom is anchored at the **middle of the surface**, because a reader invoking an action has no
   * pointer to anchor it at. That is the only difference from the gesture path, which this then
   * goes down in full so the two cannot drift apart.
   */
  public fun perform(action: ChartActionKind): Boolean {
    if (accessibilityActions.none { it.kind == action }) return false
    val before = _state.value.snapshot.interactionState
    when (action) {
      // Not measured by the viewport below, because it does not move it: this recompiles, and what
      // changed is the scales. It answers for itself.
      ChartActionKind.RESET_DOMAINS -> return resetScaleDomains()
      ChartActionKind.RESET_ZOOM -> resetViewport()
      ChartActionKind.ZOOM_IN,
      ChartActionKind.ZOOM_OUT -> {
        val size = _state.value.snapshot.scene.let { PointD(it.width / 2.0, it.height / 2.0) }
        handleZoom(
          ChartInputEvent.Zoom(
            anchor = size,
            scaleFactor = if (action == ChartActionKind.ZOOM_IN) ZOOM_STEP else 1.0 / ZOOM_STEP,
            phase = GesturePhase.ENDED,
          )
        )
      }
    }
    return _state.value.snapshot.interactionState != before
  }

  /**
   * Narrows or widens the interval [scale] draws its data against, and says whether it moved.
   *
   * What an **adjustable axis** does: a host announces an axis element carrying
   * [dev.aster.vega.scene.AccessibleElement.adjustableScale] with its platform's adjustable
   * primitive — `UIAccessibilityTraitAdjustable` and its increment and decrement on Apple, the
   * scroll actions on Android — and calls this. `false` means nothing happened, so the host knows
   * not to announce a change; announcing one that did not happen is how a reader loses track of
   * where they are.
   *
   * **Not a zoom.** A zoom magnifies the drawing and leaves every scale where the specification put
   * it, so the axis a reader hears never changes, and a reader exploring a crowded region gets
   * bigger pixels and the same labels. This changes the domain, so the ticks and the labels are
   * recomputed and the chart says something new.
   *
   * **Stepped in range space, then inverted**, rather than interpolated between the domain's ends.
   * That is the difference between a step that is right for a linear scale and one that is right
   * for every scale: narrowing a **log** axis about its arithmetic midpoint is not a log step at
   * all, and widening one that way walks the low end towards zero and off the scale. Moving the
   * *positions* and asking the scale what they mean gives a geometric step on a log axis, a
   * calendar-correct one on a time axis, and cannot produce a domain the scale could not have had —
   * inverting any finite position of a log scale is a positive number.
   *
   * Each step is taken from the scale **as it stands**, so it is always "the interval on screen,
   * once more". [domainFactors] only decides when to stop.
   */
  public fun adjustScaleDomain(scale: String, narrow: Boolean): Boolean {
    val compiled = lastCompiled ?: return false
    val built = compiled.scales[scale] ?: return false
    if (built !is InvertibleScale || built !is PositionScale) return false
    val range = built.range
    if (range.size < 2) return false

    val factor = domainFactors[scale] ?: 1.0
    val next = if (narrow) factor / DOMAIN_STEP else factor * DOMAIN_STEP
    // At the end of the range, and it is the *offered* end: a host asking for a step past it gets
    // `false` and announces nothing, which is what an adjustable element at its limit should do.
    if (next < MIN_DOMAIN_FACTOR || next > MAX_DOMAIN_FACTOR) return false

    val low = range.first()
    val high = range.last()
    val middle = (low + high) / 2.0
    val half = (high - low) / 2.0 * (if (narrow) 1.0 / DOMAIN_STEP else DOMAIN_STEP)
    val ends = listOf(built.invert(middle - half), built.invert(middle + half)).sorted()
    if (ends.any { !it.isFinite() } || ends[0] == ends[1]) return false

    pinnedDomains = pinnedDomains + (scale to ends)
    domainFactors = domainFactors + (scale to next)
    return recompileForDomains()
  }

  /**
   * Puts every adjusted axis back to the domain the specification computed.
   *
   * The way back, and it belongs to the chart rather than to any one axis: a reader who has
   * narrowed two of them is not standing on either any more. Separate from [resetViewport] because
   * the two are different work, and one reset for both would undo what nobody asked to lose.
   */
  public fun resetScaleDomains(): Boolean {
    if (pinnedDomains.isEmpty()) return false
    pinnedDomains = emptyMap()
    domainFactors = emptyMap()
    return recompileForDomains()
  }

  private fun recompileForDomains(): Boolean {
    val json = loadedSpecJson ?: return false
    val generation = nextRequest()
    val compiled =
      compileNow(compiler, json, signals.overrides, signals.itemEncodes, signals.scopedOverrides)
    if (!isCurrent(generation)) return false
    publish(compiled)
    return true
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
   * This is step 1 of hit testing (ADR 0007) and must invert exactly what the renderer applies:
   * translate by the pan offset, then scale by `contentScale * viewportScale`.
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
    val revision = nextRevision.fetchAndIncrement()
    _state.update {
      it.copy(
        snapshot = ChartSnapshot(scene = scene, interactionState = interaction, revision = revision)
      )
    }
  }

  /**
   * The **row** a mark was encoded from, which is not its tooltip.
   *
   * This read `metadata.tooltip`, so `MarkClicked.datum` was the tooltip's *value* wherever a chart
   * declared a `tooltip` channel — the string `"bar b"` rather than the row `{c: "b", v: 2}` — and
   * the README's own example passes it straight to a host's `handleClick`. The two are separate
   * properties on the item for exactly this reason.
   */
  private fun datumOf(node: SceneNode?): VegaValue? = node?.metadata?.datum

  private fun emit(event: ChartEvent) {
    _events.tryEmit(event)
  }

  public companion object {
    /**
     * How far one accessible zoom action moves, which a gesture has no equivalent of.
     *
     * A pinch reports a continuous factor; an action is a single step, so it needs a size. A
     * quarter is large enough to be worth invoking and small enough that a reader can stop where
     * they meant to — five presses roughly triple the view.
     */
    public const val ZOOM_STEP: Double = 1.25

    public const val MIN_ZOOM: Double = 0.1
    public const val MAX_ZOOM: Double = 50.0

    /**
     * How much one increment narrows or widens an adjustable axis.
     *
     * The same quarter as [ZOOM_STEP], and for the same reason: large enough to be worth a swipe,
     * small enough that a reader can stop where they meant to. A reader adjusting an axis is
     * usually hunting for a value, and a step that overshoots it is a step they have to undo.
     */
    public const val DOMAIN_STEP: Double = 1.25

    /**
     * How far from the specification's own domain an axis may be adjusted, in either direction.
     *
     * [MIN_ZOOM] and [MAX_ZOOM]'s counterpart, and symmetric where those are not: a reader who has
     * narrowed an axis a long way has to be able to get back out the way they came, one step at a
     * time, without the widening end running out first.
     */
    public const val MIN_DOMAIN_FACTOR: Double = 1.0 / 50.0

    public const val MAX_DOMAIN_FACTOR: Double = 50.0

    /**
     * How many rounds of signal-driven handlers to run before calling it a cycle.
     *
     * A chain is as deep as the specification is: the longest in the corpus is two. Anything past a
     * handful is not depth but a loop, and the number only has to be far enough above real depth
     * that no honest specification meets it.
     */
    private const val MAX_CASCADE_ROUNDS = 64

    /**
     * The values a scale is asked to map, to tell whether it moved.
     *
     * Numbers spanning what a chart's domains take, plus two strings, plus a date-shaped instant. A
     * band scale answers on names and a linear one on numbers, so a probe set covering only one
     * kind would call half the scales unchanged for ever — and an unchanged scale fires nothing,
     * which is the failure that would be silent.
     */
    private val FINGERPRINT_PROBES: List<VegaValue> =
      listOf(
        VegaValue.Num(0.0),
        VegaValue.Num(0.5),
        VegaValue.Num(1.0),
        VegaValue.Num(-1.0),
        VegaValue.Num(100.0),
        VegaValue.Num(1e6),
        VegaValue.Num(1.7e12),
        VegaValue.Str("a"),
        VegaValue.Str("z"),
      )

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
