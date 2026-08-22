import AsterVega
import Foundation
import Observation

/// One chart being looked at: the scene, its controls, and the touches that reach it.
///
/// The part of a host that is not drawing. It lived in the demo, which meant an app adopting this
/// renderer owned a copy of it — and a copy of the four things in it that took real bug fixes to get
/// right: the `@MainActor` isolation, the serialised queue behind it, the off-thread compile, and the
/// reading back of what a touch actually found. None of those is demo scaffolding; they are what using
/// `VegaChartController` from Swift requires.
///
/// Built on `VegaChartController` rather than on `SpecCompiler` directly, because that is the seam the
/// engine offers a host for everything past drawing: hit testing, selections, tooltips, and signals set
/// **through the dataflow** instead of by recompiling.
///
/// A touch needs that. A specification's `on` handlers are what turn a tap into a signal and a signal
/// into a different chart, and they only exist in a running dataflow — a host that recompiled from JSON
/// on every tap would throw away the state each tap had just created. Setting a bound signal goes the
/// same way now, which is both cheaper than a recompile and what a specification actually describes:
/// dependent scales and handlers react rather than being rebuilt.
///
/// **Off the main thread.** A compile may block on a fetch for a dataset the bundle has not got, and
/// blocking the main thread would freeze the app on exactly the specifications that make it
/// interesting. `setSpecAsync` dispatches to `Dispatchers.Default` itself; the loader caches, so a
/// dataset is fetched once rather than once per interaction.
/// Needs Observation and SwiftUI, so it asks for the versions those arrived in. The renderer itself —
/// `SceneWalk`, `CoreGraphicsTarget`, `CoreTextTextEngine` — stays available on the package's own floor,
/// because an app on an older system can still draw a chart even if it cannot use this session.
@available(macOS 14.0, iOS 17.0, tvOS 17.0, watchOS 10.0, *)
@Observable
@MainActor
public final class ChartSession {

  /// Which grammar the text was taken for. Said rather than inferred; see ``grammar``.
  public enum Grammar: Sendable {
    case vega
    case vegaLite
  }

  /// What a touch found, as facts rather than as a sentence.
  ///
  /// A host words this itself: a library that produced "selected 2 marks" would be producing English,
  /// and an app that draws charts for readers in Dutch would have to undo it.
  public enum TouchOutcome: Sendable, Equatable {
    /// The mark's tooltip, as the specification asked for it to read.
    case tooltip(String)
    /// How many marks the touch selected.
    case selected(count: Int)
    /// Nothing was there, at this point in the chart's own coordinates.
    case nothing(x: Double, y: Double)
  }

  /// Creates a session.
  ///
  /// - Parameters:
  ///   - textEngine: how text is measured. Nil measures with CoreText, which is what the renderer draws
  ///     with — the two have to agree or every label sits where a different font put it.
  ///   - textScale: the reader's text-size factor, applied to every font size when measuring **and**
  ///     drawing. 1 is the size as written. Android has honoured its device's `fontScale` since the
  ///     engines were consolidated and Compose honours it through `sp`; this is that, here. Fixed for
  ///     the session's lifetime, as it is on Android — where a text-size change recreates the view and
  ///     its engine — so a host that follows the setting live builds a new session, which is what
  ///     keying one on `DynamicTypeSize.chartTextScale` does.
  ///   - loader: how a specification's `url` data is fetched. Nil refuses everything, which is the
  ///     engine's own default and the right one: a specification is data, often pasted data, and a URL
  ///     in it asks this process to fetch an address the specification chose.
  ///   - clock: wall-clock milliseconds, for throttling an event stream. Nil uses the system clock.
  ///   - locale: the language everything the engine *generates* is written in — a month name on a time
  ///     axis, a thousands separator, the sentence VoiceOver is given. Nil is d3's `en-US`, which is
  ///     what upstream produces. Build one field for field from a d3 locale definition; it does **not**
  ///     change how a specification's own dates are parsed, which is part of the wire format.
  ///   - hostConfigJson: a Vega `config` block **this app** supplies, as JSON, which the
  ///     specification's own beats key by key. How a chart drawn on a dark surface is legible when the
  ///     server that produced it chose colours for a white page. JSON rather than a built object
  ///     because a theme is written as JSON and building a `VegaValue` tree across this boundary is
  ///     needless work; text that is not an object is reported in `hostConfigFailure` and the chart is
  ///     drawn unthemed rather than not drawn.
  ///   - containerSize: the surface the chart is laid out to, which `width: "container"` asks for. Nil
  ///     falls back to `config.view.continuousWidth`, 300, which is what upstream does outside a
  ///     browser. See the `containerSize` property for why a host sets this from a *stable* width.
  ///   - timeZone: which zone the chart's **local** time is in. Nil is the device's own, which is what
  ///     a browser has and is right for most charts. Pass one where the reader's zone is not the
  ///     handset's — a profile setting, an account read from two places — because it decides which day
  ///     a `time` axis puts a measurement on, which day a `timeunit` buckets it into, and which zone a
  ///     timestamp with no offset in the data is read in. A `Foundation.TimeZone` is converted by its
  ///     identifier; one the engine cannot resolve falls back to the device's and says so in
  ///     `timeZoneFailure` rather than crashing, since an identifier usually comes from a server.
  public init(
    textEngine: MeasuredTextEngine? = nil,
    textScale: Double = 1,
    loader: VegaDataLoader? = nil,
    clock: (@Sendable () -> Int64)? = nil,
    locale: VegaLocale? = nil,
    hostConfigJson: String? = nil,
    containerSize: SizeD? = nil,
    timeZone: Foundation.TimeZone? = nil
  ) {
    let ticker = clock ?? { Int64(Date().timeIntervalSince1970 * 1000) }
    let engine = textEngine ?? CoreTextTextEngine(textScale: textScale)
    // Read back off the engine where it is one of ours, so a host that built its own
    // `CoreTextTextEngine(textScale:)` and passed it gets its glyphs *drawn* at that size too. A host
    // with an engine of its own keeps whatever it measured with, and the drawing is told 1 — its
    // metrics are its business.
    self.textScale = (engine as? CoreTextTextEngine)?.textScale ?? 1
    let theme = hostConfigJson.flatMap { Self.parsedConfig($0) }
    if hostConfigJson != nil, theme == nil {
      hostConfigFailure =
        "The host configuration is not a JSON object, so this chart is drawn with the "
        + "specification's own configuration alone."
    }
    hostConfig = theme
    let resolved = timeZone.flatMap { VegaTimeZones.shared.of(zoneId: $0.identifier) }
    if let timeZone, resolved == nil {
      timeZoneFailure =
        "The engine does not know the time zone '\(timeZone.identifier)', so this chart is drawn in "
        + "the device's own zone."
    }
    engineTimeZone = resolved
    engineLocale = locale ?? VegaLocale.Companion.shared.EnglishUS
    controller = VegaChartController(
      // Kotlin's default arguments do not cross the Obj-C boundary, so each is given explicitly.
      initialScene: Scene.companion.empty(width: 0, height: 0),
      textEngine: engine,
      clock: { KotlinLong(value: ticker()) },
      // Every argument spelled out: a Kotlin default does not cross the Obj-C boundary, so Swift has
      // to name each one. `DenyLoader` is the engine's own default and refuses every URL, which is the
      // right default for a specification that may be pasted data.
      loader: loader ?? DenyLoader.shared,
      scheduler: nil,
      locale: engineLocale,
      hostConfig: theme,
      containerSize: containerSize,
      // Nil, and then filled through `setData(_:rows:)` — a session is created before the app has its
      // data as often as after, and the controller keeps whatever arrives first.
      hostData: nil,
      timeZone: resolved
    )
  }

  /// The zone handed to the engine, or nil for the device's own.
  ///
  /// Kept because the Vega-Lite compiler needs it too: a selection whose `init` is a written date is
  /// turned into a millisecond while compiling, and a store on a different clock from the axis is a
  /// brush that starts in the wrong place.
  private let engineTimeZone: Kotlinx_datetimeTimeZone?

  /// The locale handed to the engine, or d3's `en-US`.
  ///
  /// Kept for the same reason `engineTimeZone` is: the Vega-Lite compiler needs it too. A month name
  /// is resolved by the runtime from the pattern the compiler writes, so `%b` is enough and always
  /// was — but the *pattern* is written on the Vega-Lite side, and the order of a date's fields is a
  /// property of a language. Supplying a locale to one and not the other gives an axis whose field
  /// order and whose month names come from different places.
  private let engineLocale: VegaLocale

  /// What to show in a tooltip, and where — nil when there is nothing under the pointer.
  ///
  /// The engine turns the dataflow's tooltip value into lines in the chart's own locale, so a number in
  /// a bubble is written the way the number on the axis beside it is. The **anchor** is in this view's
  /// own pixels, being the point that was dispatched, so a host positions a bubble without converting
  /// anything.
  ///
  /// No renderer draws one, deliberately: a bubble is a design-system decision. What was missing was
  /// this step, and its absence showed here — the session used to tell an empty tooltip from a real one
  /// by stringifying the value and comparing it against `"{}"`.
  public private(set) var tooltip: ChartTooltip?

  /// The pan and zoom the controller has accumulated, in the units it accumulates them in.
  ///
  /// A **drawing** input: `VegaChartView` composes it onto the fit so that the chart, the touch target
  /// and the VoiceOver frames all move together. A host drawing the scene itself applies it the way the
  /// controller documents its own inverse — translate by the offset, then scale by
  /// `contentScale * viewportScale`.
  public private(set) var viewport = ChartViewport(offsetX: 0, offsetY: 0, scale: 1)

  /// What the reader's text-size setting multiplies every font size by, as the engine measured with it.
  ///
  /// Read by `VegaChartView` so the drawing is scaled by the same number the layout reserved room for.
  /// One source rather than two, which is the whole reason it is published at all.
  public let textScale: Double

  /// The parsed host configuration, kept because the Vega-Lite compiler needs it as well as the
  /// runtime: a Vega-Lite `config` is merged before the specification is compiled, and a theme applied
  /// on only one side of that is a chart half in the app's colours.
  private let hostConfig: (any AsterVega.VegaValue)?

  /// Why a host configuration was not applied, if it was not. Nil in every ordinary case.
  ///
  /// Reported rather than thrown, for the same reason `timeZoneFailure` is: a theme is often assembled
  /// from strings, and a chart in the wrong colours beats no chart at all.
  public private(set) var hostConfigFailure: String?

  /// The surface the chart is laid out to, which `width: "container"` asks for.
  ///
  /// Setting it **recompiles**, because Vega-Lite turns `"container"` into a signal and every scale
  /// range, axis extent and mark position downstream is resolved from it. So a host sets this on a
  /// layout change, not on every frame of an animation.
  ///
  /// Take the width from something **stable** — the parent's width, a size class, a fixed column — and
  /// not from the chart view's own geometry: a chart sized to its container changes its scene's width,
  /// the view's aspect ratio follows the scene, and a width read back from that view can oscillate.
  /// That loop is why this is not wired up automatically.
  ///
  /// **Setting it is asynchronous**, and the recompile is skipped entirely where the loaded document
  /// never reads `containerSize()` — which is every chart that states its own width and height. Await
  /// ``settle()`` where you need the new scene, as a screenshot or a test does.
  public var containerSize: SizeD? {
    get { controller.containerSize }
    set {
      guard newValue != controller.containerSize else { return }
      let size = newValue
      // **Off this actor**, like the compile a load does. This used to run a whole recompile inline,
      // and the class is `@MainActor`, so a host that reported its layout size on every resize paid a
      // compile on the main thread for every step of a split-view drag. Queued, so it lands behind a
      // compile that is still running rather than beside it, and awaited by ``settle()`` like
      // everything else here — so setting this is **not** synchronous. It never reliably was: with a
      // compile in flight it was already deferred, into a task nobody held.
      enqueue { [weak self] in
        guard let self else { return }
        // Nil where nothing was recompiled — most often because the loaded document never reads
        // `containerSize()`, which is true of every chart that states its own width and height.
        // Nothing to publish then: the chart on screen is still the right one.
        guard let compiled = try? await self.controller.setContainerSizeAsync(size: size) else {
          return
        }
        self.diagnostics = compiled.diagnostics
        if compiled.scene != nil {
          self.hasScene = true
          self.failure = nil
        }
        self.refreshControls()
        self.publish()
      }
    }
  }

  private static func parsedConfig(_ json: String) -> (any AsterVega.VegaValue)? {
    let parsed = VegaJson.shared.parseOrNull(text: json, diagnostics: DiagnosticCollector())
    // An object, or nothing: `mergeConfig` takes objects, and a bare array or number would be dropped
    // silently one layer further down where nobody would connect it to what was passed in here.
    return ForeignSignals.shared.kind(value: parsed) == "object" ? parsed : nil
  }

  /// Why a supplied time zone was not used, if it was not. Nil in every ordinary case.
  ///
  /// Reported rather than thrown, and reported rather than swallowed: an identifier a server chose can
  /// be one this platform does not carry, and a chart drawn in the wrong zone silently is worse than
  /// one that says which zone it is in.
  public private(set) var timeZoneFailure: String?

  public private(set) var scene: AsterVega.Scene?
  public private(set) var diagnostics: [VegaDiagnostic] = []
  public private(set) var controls: [SignalInput] = []
  public private(set) var failure: String?
  /// True while a compile is in flight, which for a remote dataset is long enough to say so.
  public private(set) var loading = false

  /// What the last touch found, shown under the chart — so a tap that reached the dataflow is visible
  /// rather than merely believed.
  public private(set) var lastTouch: TouchOutcome?

  /// Which grammar the text was taken for, and what compiling it reported.
  ///
  /// Said rather than inferred. Someone pasting a chart has pasted a chart, not a dialect, so the
  /// decision is made for them — and then shown, because a Vega-Lite specification read as Vega fails
  /// for a reason that reads like nonsense. The Android demo has said this from the start; iOS could
  /// not, because the compiler was not on this side of the boundary at all.
  public private(set) var grammar: Grammar?

  public private(set) var vegaLiteDiagnostics: [VegaDiagnostic] = []

  /// `usermeta` — metadata the document carries for **this app**, not for the engine.
  ///
  /// Nothing in the engine reads it, which is the whole point: upstream's schema calls it "optional
  /// metadata that will be passed to Vega", and it is how whoever wrote the chart hands a host
  /// something the grammar has no channel for — a table of the values behind marks that carry no
  /// accessible text of their own, a version to branch on, an identifier to log against. Vega-Lite
  /// carries it onto the Vega it emits, so a document in either grammar arrives with it intact.
  ///
  /// Nil where the document carried none, and where nothing has compiled yet; **empty** where it wrote
  /// `{}`. Those are different statements, and reading them as one loses the difference between a
  /// document with no metadata and one whose metadata was filtered to nothing.
  ///
  /// Published here rather than left behind `controller.lastCompiled` for the reason every other seam
  /// on this class exists: a capability Kotlin has and Swift does not is a gap in this boundary rather
  /// than a fact about the platform.
  public private(set) var usermeta: [String: any AsterVega.VegaValue]?

  /// The controller owns the compiled dataflow and the interaction state.
  ///
  /// Held for the session's lifetime, because a tap is only meaningful against the dataflow the scene
  /// came from: recreating it per compile would discard every selection with it.
  private let controller: VegaChartController

  private var json = ""
  private var overrides: [String: VegaValue] = [:]

  /// The **tail** of everything queued: the compile in flight, or the last block handed to
  /// ``serialised(_:)``, whichever came last.
  ///
  /// A chain rather than a single tracked task, and that is the fix. Each queued block begins by
  /// awaiting the one before it, so awaiting the tail is awaiting all of them — where before only the
  /// *compile* was held and everything `serialised` deferred was started as a task nobody kept. A
  /// caller that set `containerSize` during a compile and then called ``settle()`` returned before the
  /// resize had run, which is exactly the race a screenshot test exists to rule out.
  private var pending: Task<Void, Never>?

  /// How many blocks have been queued, so the last of them can tell that it *is* the last.
  ///
  /// A counter rather than comparing task identities: it answers "did anything arrive behind me"
  /// without depending on `Task` equality, and it is what lets the tail clear itself so the inline
  /// fast path in ``serialised(_:)`` reopens without anybody having to await.
  private var queued = 0

  /// The compile in flight, held separately because a new load **cancels** it.
  ///
  /// Not the same handle as ``pending``: cancelling the tail would cancel a queued touch as readily
  /// as a compile, and a load supersedes the previous compile alone.
  private var compileTask: Task<Void, Never>?

  // MARK: - Loading

  /// Compiles `specification` from scratch, discarding any control values a previous one had.
  ///
  /// - Parameter signals: values for the specification's own bound signals, applied **through the
  ///   dataflow** once it has compiled rather than by rewriting the text. A chart opened at a
  ///   particular control value — a date range a host remembers, a slider a screenshot needs moved —
  ///   is what this is for.
  public func load(specification: String, signals: [String: VegaValue] = [:]) {
    json = specification
    overrides = signals
    compile()
  }

  /// Waits for **everything** queued, so a screenshot or a test can be sure the chart is drawn.
  ///
  /// A loop rather than one await, because a queued block may queue more: setting `containerSize`
  /// recompiles, and a recompile started from inside the queue is a task that did not exist when the
  /// first await began. "Settled" has to mean the queue is empty, not that whichever task happened to
  /// be last when you asked has finished — and it used to mean neither, since only the compile was
  /// held at all and everything else ran as a task nobody kept.
  ///
  /// It terminates because the tail clears itself once nothing has arrived behind it; each further
  /// turn of the loop is another block that really was queued.
  public func settle() async {
    while let tail = pending {
      await tail.value
      if pending == nil { return }
    }
  }

  /// Appends `body` to the queue and makes it what ``settle()`` waits for.
  private func enqueue(_ body: @escaping @MainActor () async -> Void) {
    let previous = pending
    queued += 1
    let mine = queued
    pending = Task { @MainActor in
      await previous?.value
      await body()
      // The queue is empty again only if nothing arrived behind this one.
      if self.queued == mine { self.pending = nil }
    }
  }

  private func compile() {
    guard !json.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
      scene = nil
      hasScene = false
      diagnostics = []
      controls = []
      failure = nil
      grammar = nil
      vegaLiteDiagnostics = []
      usermeta = nil
      return
    }

    compileTask?.cancel()
    let specification = json
    let presets = overrides
    loading = true

    // Queued like everything else, so a load cannot run *beside* a touch that was already waiting.
    // The controller is not safe for concurrent use, and the old code started a compile immediately
    // while a deferred block sat awaiting the previous compile's task — which meant the deferred
    // block ran as soon as that finished, with the new compile already under way.
    enqueue { [weak self] in
      guard let self else { return }
      // **Either grammar.** Translating Vega-Lite is a compile of its own, so it happens here rather
      // than on the main actor, for the same reason the engine's own compile does. A specification
      // that is already Vega passes straight through untouched.
      // `hostConfig` spelled out for the same reason every other argument here is: a Kotlin default
      // argument has no Obj-C representation, so Swift names it or does not compile. A host that themes
      // its charts passes its configuration here and through `VegaChartController`.
      let converted =
        VegaLiteInput.shared.toVega(
          json: specification,
          hostConfig: self.hostConfig,
          timeZone: engineTimeZone,
          locale: self.engineLocale
        )
      self.grammar = converted.wasVegaLite ? .vegaLite : .vega
      self.vegaLiteDiagnostics = converted.wasVegaLite ? converted.diagnostics : []
      // Falling back to the text as written where Vega-Lite compilation produced nothing: the runtime
      // will then report on it, which is a better failure than this layer inventing one.
      let vega = converted.vegaJson ?? specification
      // The engine's own off-thread compile, on its default dispatcher. This is the single-argument
      // overload, which exists because a Kotlin default argument does not cross the Obj-C boundary:
      // the two-argument form demands a `CoroutineDispatcher` that no exported symbol can produce, so
      // a foreign host could not reach this path at all and had to run the synchronous `setSpec` on a
      // thread of its own.
      let compiled = try? await self.controller.setSpecAsync(json: vega)
      guard !Task.isCancelled else { return }

      // A preset control is applied through the dataflow rather than by recompiling with it.
      for (name, value) in presets {
        self.controller.setSignal(name: name, value: value)
      }

      if let compiled { self.diagnostics = compiled.diagnostics }
      // Republished with every compile, like the diagnostics: it belongs to the document now loaded,
      // and carrying the previous one's metadata forward would be worse than carrying none.
      self.usermeta = compiled?.spec?.usermeta
      // **Whether there is a chart is decided here**, from the compilation, and not from the snapshot.
      // `ChartSnapshot.scene` is not optional — the controller always holds one, the empty scene before
      // anything has compiled and the *previous* chart after a compile that failed, which is deliberate
      // and documented: a reader keeps the chart they were looking at. So a snapshot cannot say whether
      // a specification drew anything, and the code that asked it was dead: `snapshot.scene == nil` is
      // always false, the failure was never reported, and an empty 0×0 scene was published as though it
      // were a chart.
      if compiled?.scene != nil {
        self.hasScene = true
        self.failure = nil
      } else {
        let fatal =
          self.diagnostics.first {
            $0.severity == DiagnosticSeverity.fatal || $0.severity == DiagnosticSeverity.error
          }
        self.failure = fatal?.message ?? "the specification compiled to no scene"
      }
      self.refreshControls()
      self.publish()
      self.loading = false
    }
    compileTask = pending
  }

  /// A value in a row a host hands over.
  ///
  /// Spelled out as a Swift enum rather than exposing `VegaValue`, whose cases are Kotlin *value
  /// classes* and have no Obj-C representation at all — the same reason `ForeignSignals` exists for
  /// signals. Five cases, which is every JSON leaf a Vega row can hold plus the one JSON cannot: an
  /// `instant`, so a host with a `Date` never formats it to a string for the engine to parse back. That
  /// round trip goes through a zone twice, and twice is where a day goes missing.
  public enum ChartDatum: Sendable, Equatable {
    case number(Double)
    case text(String)
    case flag(Bool)
    case instant(Date)
    /// Present and empty, which a chart draws as a gap rather than as a zero.
    case missing
  }

  /// Fills a dataset the specification declared but did not carry — upstream's `view.data(name, rows)`.
  ///
  /// This is how a chart is drawn from data the **app** holds: a diary in a local store, a query's
  /// result, rows assembled from a channel the chart knows nothing about. The specification says
  /// `{"data": {"name": "diary"}}` in Vega-Lite or `{"name": "diary"}` in Vega, and the name it wrote is
  /// the name used here. The dataset's own `format.parse` and transforms then run over these rows, so a
  /// host does not reimplement a parse rule to get its own table drawn.
  ///
  /// It **recompiles**, because that is how the engine answers a change of any compile input — so it is
  /// a seam for new data rather than somewhere to write per frame. Rows supplied before a specification
  /// is loaded are kept and used by the load.
  ///
  /// Serialised against an in-flight compile for the same reason a touch is: the controller is not safe
  /// for concurrent use, and this recompiles.
  public func setData(_ name: String, rows: [[String: ChartDatum]]) {
    let converted = rows.map { row in
      ForeignData.shared.row(
        fields: row.reduce(into: [String: any AsterVega.VegaValue]()) { fields, entry in
          fields[entry.key] = Self.value(of: entry.value)
        }
      )
    }
    serialised { [weak self] in
      guard let self else { return }
      self.controller.setData(name: name, rows: converted)
      if let compiled = self.controller.lastCompiled {
        self.diagnostics = compiled.diagnostics
        if compiled.scene != nil {
          self.hasScene = true
          self.failure = nil
        }
      }
      self.refreshControls()
      self.publish()
    }
  }

  private static func value(of datum: ChartDatum) -> any AsterVega.VegaValue {
    switch datum {
    case .number(let value): return ForeignSignals.shared.ofNumber(value: value)
    case .text(let value): return ForeignSignals.shared.ofString(value: value)
    case .flag(let value): return ForeignSignals.shared.ofBoolean(value: value)
    case .instant(let date):
      return ForeignData.shared.instant(epochMillis: date.timeIntervalSince1970 * 1000)
    case .missing: return ForeignData.shared.missing()
    }
  }

  /// Reads the controller's current scene.
  ///
  /// `snapshot` is synchronous, which is the only way a Kotlin `StateFlow` is usefully consumed from
  /// Swift — so it is read after anything that could change it rather than observed.
  private func publish() {
    // Nil until something has compiled, so a host draws nothing rather than drawing the empty scene the
    // controller starts with — a 0×0 chart looks like a rendering bug and reads like one in a report.
    scene = hasScene ? controller.snapshot.scene : nil
    // The pan and the zoom, read back so the **drawing** can apply them. Published rather than left in
    // the controller because `VegaChartView` has to see them change: without this a pan updated the
    // controller's state, `canReset` became true, and the chart stayed exactly where it was.
    // The tooltip as **lines**, from the engine, in the chart's own locale — replacing the check this
    // file used to make against the literal `"{}"`. See `TooltipContent`.
    if let content = controller.tooltipContent {
      let anchor = controller.snapshot.interactionState.tooltipAnchor
      tooltip = ChartTooltip(
        rows: content.rows.map { ChartTooltip.Row(label: $0.label, value: $0.value) },
        text: content.text,
        anchor: anchor.map { CGPoint(x: $0.x, y: $0.y) }
      )
    } else {
      tooltip = nil
    }
    let state = controller.snapshot.interactionState
    viewport = ChartViewport(
      offsetX: state.viewportOffset.dx,
      offsetY: state.viewportOffset.dy,
      scale: state.viewportScale
    )
  }

  /// Whether anything has ever compiled to a scene in this session.
  ///
  /// Sticky on purpose: a recompile that fails leaves the previous chart on screen, which is the
  /// controller's own documented behaviour, and [failure] is what says so.
  private var hasScene = false

  private func refreshControls() {
    guard let compiled = controller.lastCompiled else { return }
    controls = ForeignSignals.shared.inputs(compiled: compiled)
  }

  // MARK: - Controls

  /// Sets a bound signal **through the dataflow**, which is what a control is for.
  public func set(signal: String, to value: VegaValue) {
    overrides[signal] = value
    controller.setSignal(name: signal, value: value)
    refreshControls()
    publish()
  }

  public func value(of control: SignalInput) -> VegaValue {
    // The controller's own resolved value, so a control shows what the chart holds — including a signal
    // that another signal, or a tap, has changed.
    controls.first { $0.signal == control.signal }?.value ?? control.value
  }

  // MARK: - Touch

  /// A tap, in the chart's own surface coordinates — the view has already removed the offset it drew at.
  ///
  /// `contentScale` is the other half of this and is set by ``place(contentScale:viewport:)``: the
  /// controller divides by it to reach scene coordinates, so a chart drawn scaled-to-fit whose hit test
  /// ignored the scale would miss by exactly the fit factor. That was a real defect on Android before
  /// `contentScale` became part of the host contract, which is why nothing here does its own inverse.
  ///
  /// Queued behind any compile in flight — see ``serialised(_:)``.
  public func tap(at point: Point) {
    serialised {
      self.controller.setHitTestOptions(options: HitTestOptions.companion.Touch)
      self.controller.dispatch(event: ChartInputEventTap(point: PointD(x: point.x, y: point.y)))
      self.after(point)
    }
  }

  public func longPress(at point: Point) {
    serialised {
      self.controller.setHitTestOptions(options: HitTestOptions.companion.Touch)
      self.controller.dispatch(
        event: ChartInputEventLongPress(point: PointD(x: point.x, y: point.y))
      )
      self.after(point)
    }
  }

  /// A drag, which pans the chart. `phase` separates a drag in progress from the end of one.
  public func pan(by delta: Point, phase: GesturePhase) {
    serialised {
      self.controller.dispatch(
        event: ChartInputEventPan(delta: VectorD(dx: delta.x, dy: delta.y), phase: phase)
      )
      self.publish()
    }
  }

  /// A pinch, which zooms about the point it was centred on.
  public func zoom(by scaleFactor: Double, at anchor: Point, phase: GesturePhase) {
    serialised {
      self.controller.dispatch(
        event: ChartInputEventZoom(
          scaleFactor: scaleFactor,
          anchor: PointD(x: anchor.x, y: anchor.y),
          phase: phase
        )
      )
      self.publish()
    }
  }

  /// A pointer moving without touching — a trackpad or a mouse on iPad, and nothing on a phone.
  ///
  /// Wired anyway rather than dismissed as "iOS has no hover": a chart whose tooltips only work on one
  /// platform is a gap in this host, not a property of the device. Where there genuinely is no pointer
  /// the gesture simply never fires.
  /// A key the chart reacts to, for a reader driving it from a keyboard.
  ///
  /// The Android View has translated keys since it was written; from here there was no way to reach
  /// `ChartInputEvent.Key` at all, so a specification bound to `keydown` worked on one platform. A
  /// SwiftUI host wires it with the modifiers SwiftUI already provides:
  ///
  /// ```swift
  /// VegaChartView(scene: scene, session: session)
  ///   .focusable()
  ///   .onKeyPress(.leftArrow) { session.press(.arrowLeft); return .handled }
  /// ```
  ///
  /// Which keys mean something is the specification's business — `ChartKey` is the set the engine
  /// translates — and this reports what the dataflow made of it, exactly as a tap does.
  public func press(_ key: ChartKey, modifiers: Modifiers = Modifiers.Companion.shared.None) {
    serialised { [weak self] in
      guard let self else { return }
      self.controller.dispatch(event: ChartInputEventKey(key: key, modifiers: modifiers))
      self.refreshControls()
      self.publish()
    }
  }

  public func hover(at point: Point?) {
    serialised {
      self.controller.setHitTestOptions(options: HitTestOptions.companion.Mouse)
      if let point {
        self.controller.dispatch(
          event: ChartInputEventPointerMoved(point: PointD(x: point.x, y: point.y))
        )
      } else {
        // The exit still carries a point — the last one, as far as the chart is concerned.
        self.controller.dispatch(event: ChartInputEventPointerExited(point: PointD(x: 0, y: 0)))
      }
      self.publish()
    }
  }

  /// The marks currently selected, for the accessibility tree to mark as such.
  ///
  /// `Set<AnyHashable>` rather than a set of node ids: `SceneNodeId` is a value class, so it has no Obj-C
  /// representation and crosses as an opaque box. Opaque is enough here — the set is handed straight back
  /// to the engine, which knows what is in it.
  public var selectedNodeIds: Set<AnyHashable> {
    controller.snapshot.interactionState.selection.nodeIds
  }

  /// Whether the chart has been panned or zoomed away from where it started.
  ///
  /// Read from the controller's own interaction state rather than tracked here, so the button appears
  /// when the chart has actually moved — including a move some other gesture or handler made.
  public var canReset: Bool {
    let state = controller.snapshot.interactionState
    return state.viewportScale != 1.0 || state.viewportOffset.dx != 0 || state.viewportOffset.dy != 0
  }

  /// Puts the chart back where it started, since a pan and a zoom are otherwise one-way.
  public func resetViewport() {
    serialised {
      self.controller.resetViewport()
      self.publish()
    }
  }

  /// Tells the controller how the chart is placed, so its hit testing matches the drawing.
  public func place(contentScale: Double, viewport: Rect) {
    serialised {
      self.controller.contentScale = contentScale
      self.controller.dispatch(
        event: ChartInputEventResized(
          width: viewport.width,
          height: viewport.height,
          // The chart is drawn scaled by `contentScale`, so its own coordinates are already logical:
          // a second scale factor here would apply the fit twice.
          pixelScale: 1
        )
      )
    }
  }

  /// Runs `body` once no compile is in flight.
  ///
  /// The controller is **not** safe for concurrent use — its own docs say as much about the text engine,
  /// and `setSpec` rebuilds the signal updater and the event dispatcher that a touch then reads. The
  /// compile deliberately runs off this actor, so a touch arriving mid-compile would be a second thread
  /// in the middle of that rebuild.
  ///
  /// This is not a hypothetical: dispatching a tap during the first compile left the chart stuck showing
  /// "no scene", because the touch published an empty snapshot over a compile that had not finished. A
  /// queued touch is also better behaviour than a dropped one — a tap during a slow remote load lands
  /// when the chart appears.
  private func serialised(_ body: @escaping @MainActor () -> Void) {
    // Inline while the queue is empty, which is most touches: a tap on a settled chart should be
    // readable by its caller on the same turn rather than a task hop later.
    if pending == nil {
      body()
      return
    }
    enqueue { body() }
  }

  /// Reads back what the touch did: a selection, a tooltip, or neither.
  private func after(_ point: Point) {
    refreshControls()
    publish()

    let state = controller.snapshot.interactionState
    let selection = state.selection

    // A tooltip only counts if it has something in it. A mark with no `tooltip` channel still gets an
    // **empty object** here, and preferring that over the selection reported a touch as `tooltip:` with
    // nothing after it — which looked like a broken tooltip rather than a working tap. That rule is the
    // engine's now, in `TooltipContent.of`, rather than a comparison against `"{}"` made here.
    let described = tooltip?.text ?? ""

    if !described.isEmpty {
      lastTouch = .tooltip(described)
    } else if !selection.isEmpty {
      // `nodeIds` counts the marks the hit test found. `datumIds` is empty unless the data carries an
      // identifier, so counting that reported "selected 0 items" for a tap that had selected one.
      lastTouch = .selected(count: selection.nodeIds.count)
    } else {
      lastTouch = .nothing(x: point.x, y: point.y)
    }
  }
}
