import AsterVega
import Foundation
import Observation

/// One chart being looked at: the scene, its controls, and the touches that reach it.
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
@Observable
@MainActor
final class ChartSession {

  private(set) var scene: AsterVega.Scene?
  private(set) var diagnostics: [VegaDiagnostic] = []
  private(set) var controls: [SignalInput] = []
  private(set) var failure: String?
  /// True while a compile is in flight, which for a remote dataset is long enough to say so.
  private(set) var loading = false

  /// What the last touch found, shown under the chart — so a tap that reached the dataflow is visible
  /// rather than merely believed.
  private(set) var lastTouch: String?

  /// The controller owns the compiled dataflow and the interaction state.
  ///
  /// Held for the session's lifetime, because a tap is only meaningful against the dataflow the scene
  /// came from: recreating it per compile would discard every selection with it.
  private let controller = VegaChartController(
    // Kotlin's default arguments do not cross the Obj-C boundary, so each is given explicitly.
    initialScene: Scene.companion.empty(width: 0, height: 0),
    // The same CoreText engine the renderer draws with, so a hit test lands on the label a reader sees.
    textEngine: CoreTextTextEngine(),
    // Wall-clock milliseconds for throttling an event stream — a lambda, not the compiler's `Clock`,
    // which is a different seam with a different job.
    clock: { KotlinLong(value: Int64(Date().timeIntervalSince1970 * 1000)) },
    loader: SpecLibrary.loader,
    scheduler: nil
  )

  private var json = ""
  private var overrides: [String: VegaValue] = [:]
  private var work: Task<Void, Never>?

  // MARK: - Loading

  /// Compiles `specification` from scratch, discarding any control values a previous one had.
  ///
  /// `-signal name=value` on the command line presets one, which is how a screenshot of a chart *under*
  /// a control is scripted: the simulator can launch an app but cannot drag a slider.
  func load(specification: String) {
    json = specification
    overrides = Self.launchOverrides()
    compile()
  }

  /// Waits for the compile in flight, so a screenshot or a test can be sure the chart is drawn.
  func settle() async {
    await work?.value
  }

  /// `-signal DataPoints=200`, parsed. A number if it reads as one, a boolean if it reads as one, else
  /// a string — which is the same order a specification's own `value` is read in.
  private static func launchOverrides() -> [String: VegaValue] {
    guard let argument = UserDefaults.standard.string(forKey: "signal") else { return [:] }
    let signals = ForeignSignals.shared
    var result: [String: VegaValue] = [:]
    for pair in argument.split(separator: ",") {
      let halves = pair.split(separator: "=", maxSplits: 1)
      guard halves.count == 2 else { continue }
      let name = String(halves[0]).trimmingCharacters(in: .whitespaces)
      let text = String(halves[1]).trimmingCharacters(in: .whitespaces)
      if let number = Double(text) {
        result[name] = signals.ofNumber(value: number)
      } else if text == "true" || text == "false" {
        result[name] = signals.ofBoolean(value: text == "true")
      } else {
        result[name] = signals.ofString(value: text)
      }
    }
    return result
  }

  private func compile() {
    guard !json.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
      scene = nil
      diagnostics = []
      controls = []
      failure = nil
      return
    }

    work?.cancel()
    let specification = json
    let presets = overrides
    loading = true

    let handle = Holder(controller: controller)
    work = Task { [weak self] in
      // `setSpecAsync` would be the natural call and cannot be made from here: its `dispatcher`
      // parameter is non-optional in the generated header and `Dispatchers` is not in the exported
      // surface at all, so there is no way to name one. The synchronous `setSpec` on a detached task
      // reaches the same place — off this actor, which is the point — and the boxing below is the
      // assertion that a controller crossing that boundary is safe because nothing else touches it.
      let compiled = await Task.detached(priority: .userInitiated) {
        Compiled(spec: handle.controller.setSpec(json: specification))
      }.value
      guard let self, !Task.isCancelled else { return }

      // A preset control is applied through the dataflow rather than by recompiling with it.
      for (name, value) in presets {
        self.controller.setSignal(name: name, value: value)
      }

      self.diagnostics = compiled.spec.diagnostics
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
    let snapshot = controller.snapshot
    scene = snapshot.scene
    if snapshot.scene == nil {
      let fatal = diagnostics.first {
        $0.severity == DiagnosticSeverity.fatal || $0.severity == DiagnosticSeverity.error
      }
      failure = fatal?.message ?? "the specification compiled to no scene"
    } else {
      failure = nil
    }
  }

  /// Carries the controller across the actor boundary; see `Request` in the loader's own notes.
  private struct Holder: @unchecked Sendable {
    let controller: VegaChartController
  }

  /// The same, for the compiled specification coming back.
  private struct Compiled: @unchecked Sendable {
    let spec: CompiledSpec
  }

  private func refreshControls() {
    guard let compiled = controller.lastCompiled else { return }
    controls = ForeignSignals.shared.inputs(compiled: compiled)
  }

  // MARK: - Controls

  /// Sets a bound signal **through the dataflow**, which is what a control is for.
  func set(signal: String, to value: VegaValue) {
    overrides[signal] = value
    controller.setSignal(name: signal, value: value)
    refreshControls()
    publish()
  }

  func value(of control: SignalInput) -> VegaValue {
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
  func tap(at point: Point) {
    serialised {
      self.controller.setHitTestOptions(options: HitTestOptions.companion.Touch)
      self.controller.dispatch(event: ChartInputEventTap(point: PointD(x: point.x, y: point.y)))
      self.after(point)
    }
  }

  func longPress(at point: Point) {
    serialised {
      self.controller.setHitTestOptions(options: HitTestOptions.companion.Touch)
      self.controller.dispatch(
        event: ChartInputEventLongPress(point: PointD(x: point.x, y: point.y))
      )
      self.after(point)
    }
  }

  /// Tells the controller how the chart is placed, so its hit testing matches the drawing.
  func place(contentScale: Double, viewport: Rect) {
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
      lastTouch = "tooltip: \(described)"
    } else if !selection.isEmpty {
      // `nodeIds` counts the marks the hit test found. `datumIds` is empty unless the data carries an
      // identifier, so counting that reported "selected 0 items" for a tap that had selected one.
      let count = selection.nodeIds.count
      lastTouch = "selected \(count) mark\(count == 1 ? "" : "s")"
    } else {
      lastTouch = String(format: "nothing at (%.0f, %.0f)", point.x, point.y)
    }
  }
}
