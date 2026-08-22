import AsterVega
import XCTest

@testable import AsterVegaRender

/// **The two walks emit the same calls in the same order** — asserted, at last.
///
/// Both `SceneWalk` implementations say that about themselves in their own headers, and nothing
/// checked it. Each renderer's tests assert about that renderer, so a divergence passed both: this
/// walk had no zero-opacity guard, so on `label-overlap.vg.json` it emitted 43 text runs where the
/// Compose walk emitted 19 — a dense temporal axis came out as an unreadable band on Apple and was
/// correct everywhere else, and the only thing that would have caught it was a person reading two
/// differently-formatted recordings side by side.
///
/// So the recordings are the same format now, in a third recorder built for it, and
/// `SceneWalkGoldenTest` in `vega-compose-multiplatform` writes `test-fixtures/scene-walk/*.calls.txt`
/// from the Compose walk. This reads the same files.
///
/// **The scene is identical by construction**, which is what makes this a comparison of the walks
/// rather than of two compilers: both sides compile the same fixture with the same Kotlin engine
/// through the same compiler, which Swift reaches across the framework boundary. Every input is
/// therefore pinned on both sides and has to stay in step — the text engine's ratios, the fixed clock,
/// the random seed, `EnglishUS`, and **UTC**, which is the one that would otherwise slip: a `time`
/// scale is local, the JVM tests pin `Europe/Amsterdam` through Gradle and `swift test` pins nothing.
///
/// When this fails, one of the walks changed. Read the diff before regenerating anything.
final class SceneWalkParityTests: XCTestCase {

  /// The same list `SceneWalkGoldenTest` writes, and it is asserted rather than trusted below.
  private let fixtures = [
    "bar",
    "label-overlap",
    "line-area",
    "symbols-and-curves",
    "gradient-fills",
    "arc-padding",
    "text-anchors",
    "axis-style",
    "density-heatmaps",
  ]

  private static let repositoryRoot = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent()  // AsterVegaRenderTests
    .deletingLastPathComponent()  // Tests
    .deletingLastPathComponent()  // AsterVegaRender
    .deletingLastPathComponent()  // swift
    .deletingLastPathComponent()  // the repository

  func testEveryGoldenIsReproducedByThisWalk() throws {
    for name in fixtures {
      let golden = Self.repositoryRoot
        .appendingPathComponent("test-fixtures/scene-walk/\(name).calls.txt")
      let expected = try String(contentsOf: golden, encoding: .utf8)
        .trimmingCharacters(in: CharacterSet(charactersIn: "\n"))
      let recorded = try canonical(name)

      // Line by line, because a whole-file diff of two thousand characters says nothing a reader can
      // act on. The first differing line is the finding.
      let expectedLines = expected.split(separator: "\n", omittingEmptySubsequences: false)
      let recordedLines = recorded.split(separator: "\n", omittingEmptySubsequences: false)
      for index in 0..<min(expectedLines.count, recordedLines.count) where
        expectedLines[index] != recordedLines[index]
      {
        XCTFail(
          """
          \(name): the two walks disagree at line \(index + 1).
            Compose: \(expectedLines[index])
              Swift: \(recordedLines[index])
          """)
        break
      }
      XCTAssertEqual(
        recordedLines.count, expectedLines.count,
        "\(name): \(recordedLines.count) calls here against \(expectedLines.count) from the Compose "
          + "walk — one of them is drawing something the other is not")
    }
  }

  /// The goldens exist and say something, so a missing directory cannot read as agreement.
  ///
  /// The failure mode this guards is the one this repository keeps finding: a comparison that passes
  /// because it compared nothing. An absent golden, an empty one, or a fixture list that stopped
  /// resolving would all otherwise be silent.
  func testTheGoldensAreArmed() throws {
    for name in fixtures {
      let golden = Self.repositoryRoot
        .appendingPathComponent("test-fixtures/scene-walk/\(name).calls.txt")
      XCTAssertTrue(
        FileManager.default.fileExists(atPath: golden.path),
        "no golden for \(name): regenerate with "
          + "./gradlew :vega-compose-multiplatform:jvmTest -PupdateGoldens=true --rerun-tasks")
      let text = try String(contentsOf: golden, encoding: .utf8)
      XCTAssertGreaterThan(text.split(separator: "\n").count, 4, "\(name)'s golden is nearly empty")
    }
  }

  private func canonical(_ name: String) throws -> String {
    let json = try String(
      contentsOf: Self.repositoryRoot
        .appendingPathComponent("test-fixtures/specs/\(name).vg.json"),
      encoding: .utf8
    )
    // Every argument spelled out, and every one of them has to match `SceneWalkGoldenTest`: a Kotlin
    // default argument does not cross the Obj-C boundary, which here is a help rather than a nuisance
    // — the compile inputs a golden depends on are impossible to leave implicit.
    let compiled = SpecCompiler(
      textEngine: MetricTextEngine(advanceRatio: 0.6, ascentRatio: 0.8, descentRatio: 0.2),
      loader: VegaDataLoader(
        localDirectory: Self.repositoryRoot.appendingPathComponent("test-fixtures")),
      randomSeed: 42,
      clock: ClockCompanion.shared.Fixed,
      locale: VegaLocale.Companion.shared.EnglishUS,
      hostConfig: nil,
      containerSize: nil,
      hostData: nil,
      timeZone: VegaTimeZones.shared.utc
    )
    .compileJson(json: json, signalOverrides: [:], itemEncodes: [:])
    let scene = try XCTUnwrap(
      compiled.scene, "\(name) did not compile: \(compiled.diagnostics)")
    var target = CanonicalCalls()
    SceneWalk().draw(scene: scene, into: &target)
    return target.text
  }
}
