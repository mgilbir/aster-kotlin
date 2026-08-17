import XCTest
import AsterVega
@testable import AsterVegaRender

/// The data loader: what it resolves, and — mostly — what it refuses.
///
/// `DataLoader` denies by default because a specification is *data*, often data a reader pasted, and a
/// `url` in it asks this process to fetch an address the specification chose. Most of these tests are
/// therefore about the policy, which `sanitize` decides with no I/O at all. That split is the engine's
/// design and the reason the policy can be tested rather than argued about.
///
/// The one test that touches the network is skipped when there is none, so a build on a machine without
/// it stays green and says why.
final class VegaDataLoaderTests: XCTestCase {

  /// The repository's own data directory, which the iOS app bundles by reference.
  private static var dataDirectory: URL {
    URL(fileURLWithPath: #filePath)
      .deletingLastPathComponent()  // AsterVegaRenderTests
      .deletingLastPathComponent()  // Tests
      .deletingLastPathComponent()  // AsterVegaRender
      .deletingLastPathComponent()  // swift
      .deletingLastPathComponent()  // the repository — one more than it looks, and the reason
      // `testALocalFileIsPreferredAndNoNetworkIsNeeded` was quietly passing over the network: the
      // directory did not exist, so every read fell through to the fetch it was meant to prove
      // unnecessary.
      .appendingPathComponent("test-fixtures")
  }

  private func loader() -> VegaDataLoader {
    VegaDataLoader(localDirectory: Self.dataDirectory)
  }

  // MARK: - Policy

  func testARelativePathIsAllowedAndNormalised() throws {
    let loader = loader()
    XCTAssertEqual(try loader.sanitize(uri: "data/cars.json"), "data/cars.json")
    XCTAssertEqual(try loader.sanitize(uri: "  data/cars.json  "), "data/cars.json")
    XCTAssertEqual(try loader.sanitize(uri: "data//cars.json"), "data/cars.json")
  }

  func testSanitizeIsIdempotent() throws {
    // The interface requires this: `load` re-sanitizes its argument, so a loader whose output it would
    // itself reject would refuse its own answer on the second pass.
    let loader = loader()
    for uri in ["data/cars.json", VegaDataLoader.baseURL + "data/cars.json"] {
      let once = try loader.sanitize(uri: uri)
      XCTAssertEqual(try loader.sanitize(uri: once), once, "sanitizing \(uri) twice changed it")
    }
  }

  func testAnAbsoluteUrlUnderTheBaseIsReducedToItsPath() throws {
    // The two forms a specification can use meet at the same relative path, so the cache and the local
    // directory see one key for one dataset.
    XCTAssertEqual(
      try loader().sanitize(uri: VegaDataLoader.baseURL + "data/cars.json"),
      "data/cars.json"
    )
  }

  func testEverythingElseWithASchemeIsRefused() {
    let loader = loader()
    // The addresses that make an open loader a server-side request forgery primitive.
    for uri in [
      "http://169.254.169.254/latest/meta-data/",
      "http://localhost:8080/admin",
      "https://example.com/data.json",
      "file:///etc/passwd",
      // Not the allowed site: a different host that merely contains it.
      "https://vega.github.io.example.com/vega/data/cars.json",
      // The right host over the wrong scheme.
      "http://vega.github.io/vega/data/cars.json",
    ] {
      XCTAssertThrowsError(try loader.sanitize(uri: uri), "should refuse \(uri)")
    }
  }

  func testTraversalAndAbsolutePathsAreRefused() {
    let loader = loader()
    for uri in ["../../../etc/passwd", "data/../../secret", "/etc/passwd", "", "   ", "/"] {
      XCTAssertThrowsError(try loader.sanitize(uri: uri), "should refuse '\(uri)'")
    }
  }

  // MARK: - Resolving

  func testALocalFileIsPreferredAndNoNetworkIsNeeded() throws {
    // No session is given, so if this reached the network it would use the shared one — the assertion
    // is that it does not have to: `cars.json` is in the repository's data directory.
    let contents = try loader().load(uri: "data/cars.json")
    XCTAssertTrue(contents.contains("Miles_per_Gallon"), "the real dataset, read from disk")
  }

  func testAMissingLocalFileWithNoNetworkFailsAsADiagnosticRatherThanACrash() {
    // A loader pointed at an empty directory, with a session that cannot reach anything.
    let configuration = URLSessionConfiguration.ephemeral
    configuration.protocolClasses = []
    configuration.timeoutIntervalForRequest = 1
    let offline = VegaDataLoader(
      localDirectory: URL(fileURLWithPath: "/nonexistent-directory"),
      session: URLSession(configuration: configuration),
      timeout: 2
    )
    // What matters is that it throws rather than hanging or crashing: the engine turns this into a
    // diagnostic, and a chart with no data draws nothing and says why.
    XCTAssertThrowsError(try offline.load(uri: "data/not-a-real-dataset.json"))
  }

  func testTheCacheAnswersTheSecondCall() throws {
    // A bound signal recompiles the whole specification on every change, so without a cache a slider
    // over a remote dataset would refetch per frame. Checked here by reading twice from a loader whose
    // local directory disappears in between — the second read can only come from the cache.
    let temporary = URL(fileURLWithPath: NSTemporaryDirectory())
      .appendingPathComponent("aster-loader-\(ProcessInfo.processInfo.globallyUniqueString)")
    try FileManager.default.createDirectory(
      at: temporary.appendingPathComponent("data"), withIntermediateDirectories: true
    )
    let file = temporary.appendingPathComponent("data/tiny.json")
    try #"[{"a": 1}]"#.write(to: file, atomically: true, encoding: .utf8)

    let loader = VegaDataLoader(localDirectory: temporary)
    XCTAssertEqual(try loader.load(uri: "data/tiny.json"), #"[{"a": 1}]"#)

    try FileManager.default.removeItem(at: temporary)
    XCTAssertEqual(
      try loader.load(uri: "data/tiny.json"), #"[{"a": 1}]"#,
      "the second read came from the cache"
    )
  }

  // MARK: - The network half

  func testADatasetTheBundleHasNotGotIsFetchedFromVegasSite() throws {
    // Nothing local at all, so this can only succeed over the network.
    let remote = VegaDataLoader(localDirectory: nil, timeout: 20)
    do {
      let contents = try remote.load(uri: "data/cars.json")
      XCTAssertTrue(
        contents.contains("Miles_per_Gallon"),
        "fetched \(VegaDataLoader.baseURL)data/cars.json"
      )
    } catch {
      // A machine with no network is not a failing build; saying so beats a mysterious red.
      throw XCTSkip("no network: \(error.localizedDescription)")
    }
  }

  /// A specification whose data is a URL compiles to a chart with marks in it.
  func testASpecificationWithAUrlCompilesThroughTheLoader() throws {
    let compiled = SpecCompiler(
      textEngine: MetricTextEngine(advanceRatio: 0.6, ascentRatio: 0.8, descentRatio: 0.2),
      loader: loader(),
      randomSeed: 42,
      clock: ClockCompanion.shared.Fixed
    )
    .compileJson(
      json: """
        {"$schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 200, "height": 100, "padding": 0,
         "data": [{"name": "cars", "url": "data/cars.json", "format": {"type": "json"}}],
         "scales": [
           {"name": "x", "type": "linear", "domain": {"data": "cars", "field": "Horsepower"},
            "range": "width"},
           {"name": "y", "type": "linear", "domain": {"data": "cars", "field": "Miles_per_Gallon"},
            "range": "height"}],
         "marks": [{"type": "symbol", "from": {"data": "cars"}, "encode": {"enter": {
           "x": {"scale": "x", "field": "Horsepower"},
           "y": {"scale": "y", "field": "Miles_per_Gallon"},
           "size": {"value": 12}, "fill": {"value": "steelblue"}}}}]}
        """,
      signalOverrides: [:],
      itemEncodes: [:]
    )

    let fatal = compiled.diagnostics.filter {
      $0.severity == DiagnosticSeverity.error || $0.severity == DiagnosticSeverity.fatal
    }
    XCTAssertTrue(fatal.isEmpty, "compiled with errors: \(fatal.map { $0.message })")

    var target = RecordingTarget()
    SceneWalk().draw(scene: try XCTUnwrap(compiled.scene), into: &target)
    let symbols = target.calls.filter { $0.contains("path ") }
    // 406 cars in the dataset, so a scatter of them is not a handful of marks.
    XCTAssertGreaterThan(symbols.count, 300, "a point per car: \(symbols.count)")
  }
}
