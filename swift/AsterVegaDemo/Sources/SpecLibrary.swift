import AsterVega
import Foundation

/// A Vega specification bundled with the app, and what the engine made of it.
struct CompiledChart: Identifiable {
  let id: String
  let name: String
  let json: String
  let scene: AsterVega.Scene?
  /// Everything the engine could not honour, which this app shows rather than hides.
  let diagnostics: [VegaDiagnostic]
  let failure: String?
}

/// Loads the bundled specifications and compiles them with the engine.
///
/// The specifications are the repository's own differential fixtures, chosen for having their data
/// inline: this app has no network loader, so a specification reading a URL would compile to a chart
/// with no marks and look like a renderer bug. `DenyLoader` is what makes that a diagnostic instead.
enum SpecLibrary {

  static func load() -> [CompiledChart] {
    guard let folder = Bundle.main.url(forResource: "Specs", withExtension: nil) else { return [] }
    let files =
      (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil))
      ?? []
    return files
      .filter { $0.pathExtension == "json" }
      .sorted { $0.lastPathComponent < $1.lastPathComponent }
      .map { compile(url: $0) }
  }

  private static func compile(url: URL) -> CompiledChart {
    let name = url.deletingPathExtension().lastPathComponent
    guard let json = try? String(contentsOf: url, encoding: .utf8) else {
      return CompiledChart(
        id: name, name: name, json: "", scene: nil, diagnostics: [],
        failure: "could not be read from the bundle"
      )
    }

    // The same seams the tests use. The clock is pinned, so a specification mentioning `now()` draws
    // the same chart every launch; the text engine is the portable ratio-based one, which means a
    // label is laid out by approximate metrics and then drawn by CoreText — see the README.
    let compiler = SpecCompiler(
      textEngine: MetricTextEngine(advanceRatio: 0.6, ascentRatio: 0.8, descentRatio: 0.2),
      loader: DenyLoader(),
      randomSeed: 42,
      clock: ClockCompanion.shared.Fixed
    )
    let compiled = compiler.compileJson(json: json, signalOverrides: [:], itemEncodes: [:])
    let fatal = compiled.diagnostics.first {
      $0.severity == DiagnosticSeverity.fatal || $0.severity == DiagnosticSeverity.error
    }
    return CompiledChart(
      id: name,
      name: name.replacingOccurrences(of: "-", with: " "),
      json: json,
      scene: compiled.scene,
      diagnostics: compiled.diagnostics,
      failure: compiled.scene == nil ? (fatal?.message ?? "produced no scene") : nil
    )
  }
}
