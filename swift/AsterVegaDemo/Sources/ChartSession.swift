import AsterVega
import Foundation
import Observation

/// One chart being looked at: its specification, the scene compiled from it, and its controls.
///
/// A specification's `bind` signals are the reason this exists. A chart with a slider is not a picture
/// but a small program a reader drives, and driving it means compiling again with the value they chose:
/// `compileJson(json:signalOverrides:…)` takes exactly that. Recompiling rather than nudging a live
/// dataflow is the honest simple thing here — a bound signal can change a scale's domain, a
/// transform's parameter or the data itself, and a fresh compile is right in every one of those cases
/// where a partial update has to be argued about.
///
/// It is also fast enough: the specifications here compile in milliseconds.
///
/// **Off the main thread**, though, because a specification's data may not be local. `VegaDataLoader`
/// falls back to Vega's own site for a dataset the bundle does not carry, and `DataLoader.load` is
/// synchronous — so a compile can block on a network fetch, and blocking the main thread would freeze
/// the app on exactly the specifications that make it interesting. The loader caches, so this happens
/// once per dataset rather than once per slider position.
@Observable
@MainActor
final class ChartSession {

  private(set) var scene: AsterVega.Scene?
  private(set) var diagnostics: [VegaDiagnostic] = []
  private(set) var controls: [SignalInput] = []
  private(set) var failure: String?
  /// True while a compile is in flight, which for a remote dataset is long enough to say so.
  private(set) var loading = false

  /// The app's one loader, whose cache is the reason a slider over a remote dataset is usable at all.
  private let loader = SpecLibrary.loader

  /// The compile in flight, cancelled when another starts — a dragged slider queues many.
  private var work: Task<Void, Never>?

  /// What the reader has changed, by signal name. Sent back into every recompile.
  private var overrides: [String: VegaValue] = [:]
  private var json: String = ""

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

  /// Sets a bound signal and recompiles, which is what moves the chart under a control.
  func set(signal: String, to value: VegaValue) {
    overrides[signal] = value
    compile()
  }

  func value(of control: SignalInput) -> VegaValue {
    overrides[control.signal] ?? control.value
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
    let request = Request(json: json, overrides: overrides, loader: loader)
    loading = true

    work = Task { [weak self] in
      // Off the main actor: the compile may block on a fetch for a dataset the bundle has not got.
      let outcome = await Task.detached(priority: .userInitiated) {
        Outcome(
          compiled: SpecCompiler(
            // CoreText, so a label is laid out by the font that draws it. With the portable ratio
            // engine every reserved box was the wrong width and the axis numbers sat over the line.
            textEngine: CoreTextTextEngine(),
            loader: request.loader,
            randomSeed: 42,
            clock: ClockCompanion.shared.Fixed
          )
          .compileJson(
            json: request.json, signalOverrides: request.overrides, itemEncodes: [:]
          )
        )
      }.value

      guard let self, !Task.isCancelled else { return }
      self.apply(outcome.compiled)
      self.loading = false
    }
  }

  /// Carries a compile's inputs across the actor boundary.
  ///
  /// `VegaValue` comes from Kotlin and Swift does not know it is `Sendable`; it is immutable, and this
  /// dictionary is a copy nothing else holds. `@unchecked` is that assertion, made once.
  private struct Request: @unchecked Sendable {
    let json: String
    let overrides: [String: VegaValue]
    let loader: VegaDataLoader
  }

  /// Carries a compiled specification across the actor boundary.
  ///
  /// `CompiledSpec` comes from Kotlin and is not `Sendable` as far as Swift knows, which is a statement
  /// about the compiler's knowledge rather than about the value: it is immutable and nothing else holds
  /// it. `@unchecked` is that assertion, made in one place instead of at every use.
  private struct Outcome: @unchecked Sendable {
    let compiled: CompiledSpec
  }

  private func apply(_ compiled: CompiledSpec) {
    diagnostics = compiled.diagnostics
    // `ForeignSignals.inputs` exists because assembling this from Swift takes three calls — the
    // specification's signals, the scope's resolved values, and `SignalInput.of` over both — and a
    // host that has to assemble it can assemble it wrongly.
    controls = ForeignSignals.shared.inputs(compiled: compiled)
    scene = compiled.scene

    if compiled.scene == nil {
      let fatal = compiled.diagnostics.first {
        $0.severity == DiagnosticSeverity.fatal || $0.severity == DiagnosticSeverity.error
      }
      failure = fatal?.message ?? "the specification compiled to no scene"
    } else {
      failure = nil
    }
  }
}
