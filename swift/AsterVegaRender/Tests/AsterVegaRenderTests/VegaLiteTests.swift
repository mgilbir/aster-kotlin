import XCTest
import AsterVega
@testable import AsterVegaRender

/// Vega-Lite, from a Swift host.
///
/// A reader who pastes a chart has pasted a chart, not a dialect, so a host that accepts text has to
/// accept **either grammar**. Android could: the demo there hands the text to `VegaLiteInput` and
/// draws whatever comes back. iOS could not, and not for any reason to do with iOS — `:vega-lite` was
/// declared a JVM module and so was neither compiled for Native nor on the framework's export list.
/// Nothing in the compiler touches the JVM, which is what made that a build accident rather than a
/// host restriction.
///
/// So these assertions are about *reach* as much as about correctness: that the compiler is on the
/// other side of the Obj-C boundary at all, that the specification it emits is one this runtime can
/// draw, and that the two grammars end at the same picture.
final class VegaLiteTests: XCTestCase {

  private func compiler() -> SpecCompiler {
    // Kotlin's default arguments do not cross the Obj-C boundary, so each is given explicitly.
    SpecCompiler(
      textEngine: MetricTextEngine(advanceRatio: 0.6, ascentRatio: 0.8, descentRatio: 0.2),
      loader: DenyLoader(),
      randomSeed: 42,
      clock: ClockCompanion.shared.Fixed,
      // Spelled out because a Kotlin default argument has no Obj-C representation: Swift names every
      // parameter or does not compile. `EnglishUS` is what upstream produces.
      locale: VegaLocale.Companion.shared.EnglishUS
    )
  }

  /// A Vega-Lite bar chart, which is the shortest thing that is unmistakably not Vega.
  private let vegaLite = """
    {"$schema": "https://vega.github.io/schema/vega-lite/v6.json",
     "width": 120, "height": 60,
     "data": {"values": [{"c": "a", "v": 30}, {"c": "b", "v": 80}]},
     "mark": "bar",
     "encoding": {
       "x": {"field": "c", "type": "nominal"},
       "y": {"field": "v", "type": "quantitative"}}}
    """

  func testAVegaLiteSpecificationIsRecognisedAndCompiled() throws {
    let converted = VegaLiteInput.shared.toVega(json: vegaLite)

    XCTAssertTrue(converted.wasVegaLite, "the schema says Vega-Lite, so it should be read as such")
    let vega = try XCTUnwrap(converted.vegaJson, "compilation produced nothing")
    XCTAssertTrue(vega.contains("\"marks\""), "what comes back is Vega, which has `marks`")
    XCTAssertFalse(vega.contains("\"encoding\""), "and not Vega-Lite, which has `encoding`")
    XCTAssertTrue(
      converted.diagnostics.allSatisfy {
        $0.severity != DiagnosticSeverity.error && $0.severity != DiagnosticSeverity.fatal
      },
      "and it should compile cleanly: \(converted.diagnostics)"
    )
  }

  /// The whole point: the compiled specification **draws**, through this runtime and this renderer.
  func testAVegaLiteSpecificationDrawsThroughTheSwiftRenderer() throws {
    let vega = try XCTUnwrap(VegaLiteInput.shared.toVega(json: vegaLite).vegaJson)
    let compiled = compiler().compileJson(json: vega, signalOverrides: [:], itemEncodes: [:])
    let serious = compiled.diagnostics.filter {
      $0.severity == DiagnosticSeverity.error || $0.severity == DiagnosticSeverity.fatal
    }
    XCTAssertTrue(serious.isEmpty, "the emitted Vega should run: \(serious)")

    var target = RecordingTarget()
    SceneWalk().draw(scene: try XCTUnwrap(compiled.scene), into: &target)

    // Two bars and the axes around them. Asserted as *what was drawn* rather than as a count, so a
    // renderer that drew nothing and a renderer that drew the wrong thing fail differently.
    XCTAssertTrue(
      target.calls.contains { $0.contains("rect") },
      "the bars:\n\(target.calls.joined(separator: "\n"))"
    )
    XCTAssertTrue(
      target.calls.contains { $0.contains("text") },
      "the axis labels:\n\(target.calls.joined(separator: "\n"))"
    )
  }

  /// Either grammar, one picture — which is what lets a host stop asking which it was given.
  func testTheTwoGrammarsEndAtTheSameDrawing() throws {
    let viaVegaLite = try XCTUnwrap(VegaLiteInput.shared.toVega(json: vegaLite).vegaJson)
    // The same chart handed over as Vega, by compiling it once and feeding the result back. A host
    // may be given either, and neither is a special case for it.
    let asVega = VegaLiteInput.shared.toVega(json: viaVegaLite)
    XCTAssertFalse(asVega.wasVegaLite, "Vega in, Vega out, and no compilation attempted")
    XCTAssertEqual(asVega.vegaJson, viaVegaLite, "and unchanged")

    func record(_ json: String) throws -> [String] {
      let compiled = compiler().compileJson(json: json, signalOverrides: [:], itemEncodes: [:])
      var target = RecordingTarget()
      SceneWalk().draw(scene: try XCTUnwrap(compiled.scene), into: &target)
      return target.calls
    }
    XCTAssertEqual(try record(viaVegaLite), try record(try XCTUnwrap(asVega.vegaJson)))
  }

  /// A specification that is not Vega-Lite is passed through rather than mangled.
  func testVegaIsLeftAlone() {
    let vega = """
      {"$schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 50, "height": 50, "marks": []}
      """
    let converted = VegaLiteInput.shared.toVega(json: vega)
    XCTAssertFalse(converted.wasVegaLite)
    XCTAssertEqual(converted.vegaJson, vega)
    XCTAssertTrue(converted.diagnostics.isEmpty, "nothing was compiled, so nothing is reported")
  }

  /// And Vega-Lite this compiler cannot honour reports, across the boundary, rather than drawing.
  func testAnUnsupportedVegaLiteConstructIsReportedToTheHost() {
    let converted = VegaLiteInput.shared.toVega(
      json: """
        {"$schema": "https://vega.github.io/schema/vega-lite/v6.json",
         "data": {"values": [{"a": 1}]},
         "layer": [{"hconcat": [{"mark": "bar",
           "encoding": {"x": {"field": "a", "type": "quantitative"}}}]}]}
        """
    )
    XCTAssertTrue(converted.wasVegaLite, "it was read as Vega-Lite")
    XCTAssertNil(converted.vegaJson, "and produced nothing, rather than a chart nobody asked for")
    XCTAssertFalse(
      converted.diagnostics.isEmpty,
      "a host has to be able to say why, so the report crosses the boundary"
    )
  }
}
