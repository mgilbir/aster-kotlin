import XCTest
import AsterVega
@testable import AsterVegaRender

/// Every specification the iOS demo bundles, compiled and drawn.
///
/// The demo app cannot be run on a machine with no iOS simulator runtime, but the specifications it
/// ships and the renderer it uses are both testable here — and a chart that compiles to nothing, or
/// draws nothing, would make the app look broken in a way that has nothing to do with iOS. This is the
/// part of the demo that can be checked without a device, so it is.
///
/// It reads the app's own resource folder rather than a copy, so a specification added to the demo is
/// covered the moment it is added, and one that is deleted stops being tested.
final class DemoSpecsTests: XCTestCase {

  /// The demo's bundled specifications, located from this file rather than a test bundle — SwiftPM
  /// would need a `resources:` declaration for the latter, and these files belong to the app.
  private static var specsDirectory: URL {
    URL(fileURLWithPath: #filePath)
      .deletingLastPathComponent()  // .../Tests/AsterVegaRenderTests
      .deletingLastPathComponent()  // .../Tests
      .deletingLastPathComponent()  // .../AsterVegaRender, the package root
      .deletingLastPathComponent()  // .../swift, which is where the demo sits beside it
      .appendingPathComponent("AsterVegaDemo/Resources/Specs")
  }

  /// The loader the app uses, pointed at the repository's data rather than a bundle.
  ///
  /// Two of the bundled specifications read their data from a `url`, so `DenyLoader` would fail them
  /// here for a reason that has nothing to do with the demo. Sharing one loader across the whole run
  /// also shares its cache, which is what keeps this test from reading `cars.json` twice.
  private static let loader = VegaDataLoader(
    localDirectory: URL(fileURLWithPath: #filePath)
      .deletingLastPathComponent()  // AsterVegaRenderTests
      .deletingLastPathComponent()  // Tests
      .deletingLastPathComponent()  // AsterVegaRender
      .deletingLastPathComponent()  // swift
      .deletingLastPathComponent()  // the repository
      .appendingPathComponent("test-fixtures")
  )

  private func compiler() -> SpecCompiler {
    SpecCompiler(
      textEngine: MetricTextEngine(advanceRatio: 0.6, ascentRatio: 0.8, descentRatio: 0.2),
      loader: Self.loader,
      randomSeed: 42,
      clock: ClockCompanion.shared.Fixed,
      // Spelled out because a Kotlin default argument has no Obj-C representation: Swift names every
      // parameter or does not compile. `EnglishUS` is what upstream produces.
      locale: VegaLocale.Companion.shared.EnglishUS,
      hostConfig: nil
    )
  }

  func testEveryBundledSpecificationCompilesAndDrawsSomething() throws {
    let directory = Self.specsDirectory
    let files = try FileManager.default
      .contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
      .filter { $0.pathExtension == "json" }
      .sorted { $0.lastPathComponent < $1.lastPathComponent }

    // A floor, so an empty or moved directory fails rather than passing vacuously.
    XCTAssertGreaterThanOrEqual(
      files.count, 10, "expected the demo's specifications at \(directory.path)"
    )

    var report: [String] = []
    var broken: [String] = []

    for file in files {
      let name = file.deletingPathExtension().lastPathComponent
      let json = try String(contentsOf: file, encoding: .utf8)
      let compiled = compiler().compileJson(json: json, signalOverrides: [:], itemEncodes: [:])

      let fatal = compiled.diagnostics.filter {
        $0.severity == DiagnosticSeverity.error || $0.severity == DiagnosticSeverity.fatal
      }
      guard fatal.isEmpty else {
        broken.append("\(name): \(fatal.map { $0.message }.joined(separator: "; "))")
        continue
      }
      guard let scene = compiled.scene else {
        broken.append("\(name): compiled to no scene")
        continue
      }

      var target = RecordingTarget()
      SceneWalk().draw(scene: scene, into: &target)
      // Groups alone are not a chart: a walk that emitted only `group` lines drew nothing a reader
      // would see, which is the failure this is looking for.
      let primitives = target.calls.filter {
        !$0.trimmingCharacters(in: .whitespaces).hasPrefix("group")
      }
      if primitives.count < 2 {
        broken.append("\(name): drew \(primitives.count) primitives")
        continue
      }
      report.append(
        "\(name): \(primitives.count) primitives, \(compiled.diagnostics.count) diagnostics"
      )
    }

    XCTAssertTrue(
      broken.isEmpty,
      "specifications the demo would show as broken:\n" + broken.joined(separator: "\n")
    )
    // Printed on success too, because "12 charts, this many marks each" is the summary worth having
    // when the app cannot be launched here.
    print("Demo specifications:\n" + report.joined(separator: "\n"))
  }

  /// The two features the demo pushed into the renderer, over the specifications that use them.
  func testTheDemoExercisesGradientsAndText() throws {
    let directory = Self.specsDirectory
    var sawGradient = false
    var sawText = false

    for file in try FileManager.default.contentsOfDirectory(
      at: directory, includingPropertiesForKeys: nil
    ) where file.pathExtension == "json" {
      let json = try String(contentsOf: file, encoding: .utf8)
      let compiled = compiler().compileJson(json: json, signalOverrides: [:], itemEncodes: [:])
      guard let scene = compiled.scene else { continue }
      var target = RecordingTarget()
      SceneWalk().draw(scene: scene, into: &target)
      for call in target.calls {
        if call.contains("linear") || call.contains("radial") { sawGradient = true }
        if call.contains("text ") { sawText = true }
      }
    }

    // If either of these ever goes false, the demo stopped covering a feature it was the reason for.
    XCTAssertTrue(sawGradient, "a bundled specification should paint a gradient")
    XCTAssertTrue(sawText, "a bundled specification should draw a label")
  }
}
