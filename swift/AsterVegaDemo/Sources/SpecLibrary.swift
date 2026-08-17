import AsterVega
import Foundation

/// A Vega specification bundled with the app.
///
/// Listing is separate from compiling on purpose. The list is what the first screen needs and reading a
/// dozen small files is quick; compiling them is not, and two of them read a dataset — which may be
/// fetched. So the list appears immediately and each specification's status fills in behind it.
struct BundledSpec: Identifiable, Sendable {
  let id: String
  let name: String
  let json: String
}

/// What compiling one produced: the count of diagnostics, or why it failed.
struct SpecStatus: Sendable {
  let diagnostics: Int
  let failure: String?
}

/// Loads and compiles the bundled specifications, **never on the main thread**.
///
/// Both halves are `nonisolated` and awaited from a task: a `Bundle` directory listing is file I/O, and
/// a compile is the whole engine plus possibly a network fetch. Doing either on the thread that answers
/// taps is how a demo stops answering them — the Android demo has the same split for the same reason.
enum SpecLibrary {

  /// The one loader the whole app shares.
  ///
  /// Shared because its cache is: a dataset fetched for the list's status is the same dataset the detail
  /// screen needs, and a slider recompiles the specification on every change. One loader means one fetch
  /// per dataset per launch instead of one per compile.
  static let loader = VegaDataLoader(localDirectory: Bundle.main.resourceURL)

  /// The bundled specifications, by name. File I/O only, so this is quick, but still off the main thread.
  static func list() async -> [BundledSpec] {
    await Task.detached(priority: .userInitiated) {
      guard let folder = Bundle.main.url(forResource: "Specs", withExtension: nil) else { return [] }
      let files =
        (try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil))
        ?? []
      return files
        .filter { $0.pathExtension == "json" }
        .sorted { $0.lastPathComponent < $1.lastPathComponent }
        .compactMap { url in
          guard let json = try? String(contentsOf: url, encoding: .utf8) else { return nil }
          let id = url.deletingPathExtension().lastPathComponent
          return BundledSpec(
            id: id, name: id.replacingOccurrences(of: "-", with: " "), json: json
          )
        }
    }.value
  }

  /// Compiles one, to say in the list whether it is clean. Off the main thread, and may fetch data.
  static func status(of spec: BundledSpec, loader: VegaDataLoader) async -> SpecStatus {
    let request = Request(json: spec.json, loader: loader)
    return await Task.detached(priority: .background) {
      let compiled = SpecCompiler(
        textEngine: CoreTextTextEngine(),
        loader: request.loader,
        randomSeed: 42,
        clock: ClockCompanion.shared.Fixed
      )
      .compileJson(json: request.json, signalOverrides: [:], itemEncodes: [:])

      let fatal = compiled.diagnostics.first {
        $0.severity == DiagnosticSeverity.fatal || $0.severity == DiagnosticSeverity.error
      }
      return SpecStatus(
        diagnostics: compiled.diagnostics.count,
        failure: compiled.scene == nil ? (fatal?.message ?? "produced no scene") : nil
      )
    }.value
  }

  /// Carries a compile's inputs across the actor boundary; see `ChartSession.Request`.
  private struct Request: @unchecked Sendable {
    let json: String
    let loader: VegaDataLoader
  }
}
