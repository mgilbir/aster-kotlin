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
  ///   - textEngine: how text is measured. The default measures with CoreText, which is what the
  ///     renderer draws with — the two have to agree or every label sits where a different font put it.
  ///   - loader: how a specification's `url` data is fetched. Nil refuses everything, which is the
  ///     engine's own default and the right one: a specification is data, often pasted data, and a URL
  ///     in it asks this process to fetch an address the specification chose.
  ///   - clock: wall-clock milliseconds, for throttling an event stream. Nil uses the system clock.
  ///   - timeZone: which zone the chart's **local** time is in. Nil is the device's own, which is what
  ///     a browser has and is right for most charts. Pass one where the reader's zone is not the
  ///     handset's — a profile setting, an account read from two places — because it decides which day
  ///     a `time` axis puts a measurement on, which day a `timeunit` buckets it into, and which zone a
  ///     timestamp with no offset in the data is read in. A `Foundation.TimeZone` is converted by its
  ///     identifier; one the engine cannot resolve falls back to the device's and says so in
  ///     `timeZoneFailure` rather than crashing, since an identifier usually comes from a server.
  public init(
    textEngine: MeasuredTextEngine = CoreTextTextEngine(),
    loader: VegaDataLoader? = nil,
    clock: (@Sendable () -> Int64)? = nil,
    timeZone: Foundation.TimeZone? = nil
  ) {
    let ticker = clock ?? { Int64(Date().timeIntervalSince1970 * 1000) }
    let resolved = timeZone.flatMap { VegaTimeZones.shared.of(zoneId: $0.identifier) }
    if let timeZone, resolved == nil {
      timeZoneFailure =
        "The engine does not know the time zone '\(timeZone.identifier)', so this chart is drawn in "
        + "the device's own zone."
    }
    engineTimeZone = resolved
    controller = VegaChartController(
      // Kotlin's default arguments do not cross the Obj-C boundary, so each is given explicitly.
      initialScene: Scene.companion.empty(width: 0, height: 0),
      textEngine: textEngine,
      clock: { KotlinLong(value: ticker()) },
      // Every argument spelled out: a Kotlin default does not cross the Obj-C boundary, so Swift has
      // to name each one. `DenyLoader` is the engine's own default and refuses every URL, which is the
      // right default for a specification that may be pasted data.
      loader: loader ?? DenyLoader.shared,
      scheduler: nil,
      locale: VegaLocale.Companion.shared.EnglishUS,
      hostConfig: nil,
      containerSize: nil,
      timeZone: resolved
    )
  }

  /// The zone handed to the engine, or nil for the device's own.
  ///
  /// Kept because the Vega-Lite compiler needs it too: a selection whose `init` is a written date is
  /// turned into a millisecond while compiling, and a store on a different clock from the axis is a
  /// brush that starts in the wrong place.
  private let engineTimeZone: Kotlinx_datetimeTimeZone?

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

  /// The controller owns the compiled dataflow and the interaction state.
  ///
  /// Held for the session's lifetime, because a tap is only meaningful against the dataflow the scene
  /// came from: recreating it per compile would discard every selection with it.
  private let controller: VegaChartController

  private var json = ""
  private var overrides: [String: VegaValue] = [:]
  private var work: Task<Void, Never>?

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

  /// Waits for the compile in flight, so a screenshot or a test can be sure the chart is drawn.
  public func settle() async {
    await work?.value
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
      return
    }

    work?.cancel()
    let specification = json
    let presets = overrides
    loading = true

    work = Task { [weak self] in
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
          hostConfig: nil,
          timeZone: engineTimeZone
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
      // Cleared so later touches run straight through rather than awaiting a finished task.
      self.work = nil
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
    if work == nil {
      body()
      return
    }
    Task { @MainActor in
      await self.settle()
      body()
    }
  }

  /// Reads back what the touch did: a selection, a tooltip, or neither.
  private func after(_ point: Point) {
    refreshControls()
    publish()

    let state = controller.snapshot.interactionState
    let selection = state.selection

    // A tooltip only counts if it has something in it. A mark with no `tooltip` channel still gets an
    // empty object here, and preferring that over the selection reported a touch as `tooltip:` with
    // nothing after it — which looked like a broken tooltip rather than a working tap.
    let tooltip = state.tooltip.map { ForeignSignals.shared.text(value: $0) } ?? ""
    let described = tooltip.trimmingCharacters(in: .whitespacesAndNewlines)

    if !described.isEmpty, described != "{}" {
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
