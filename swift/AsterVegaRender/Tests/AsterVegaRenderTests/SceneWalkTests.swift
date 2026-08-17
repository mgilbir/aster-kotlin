import XCTest
import AsterVega
@testable import AsterVegaRender

/// The Swift renderer, checked against a scene the **engine actually compiled**.
///
/// Not a hand-built tree: the specification below goes through the same compiler an app would use,
/// so the test covers the boundary as well as the walk — a scene reaching Swift through the Obj-C
/// framework, with its paints, transforms and text runs read back through the accessors that exist
/// because a `value class` has no representation there.
///
/// What is asserted is the sequence of draw calls, which is what a renderer can get wrong. No pixels
/// and no simulator: a recording is comparable, printable, and runs anywhere `swift test` does.
final class SceneWalkTests: XCTestCase {

  /// Compiles a specification the way an application would.
  private func scene(_ json: String) throws -> Scene {
    // Kotlin's default arguments do not cross the Obj-C boundary, so each is given explicitly.
    // The ratios are `MetricTextEngine`'s own defaults; the clock is the pinned one the oracle uses,
    // so a specification mentioning `now()` is the same chart on every run.
    let compiler = SpecCompiler(
      textEngine: MetricTextEngine(advanceRatio: 0.6, ascentRatio: 0.8, descentRatio: 0.2),
      loader: DenyLoader(),
      randomSeed: 42,
      clock: ClockCompanion.shared.Fixed
    )
    let compiled = compiler.compileJson(json: json, signalOverrides: [:], itemEncodes: [:])
    let complaints = compiled.diagnostics.filter {
      $0.severity == DiagnosticSeverity.error || $0.severity == DiagnosticSeverity.fatal
    }
    XCTAssertTrue(
      complaints.isEmpty,
      "compiled with errors: \(complaints.map { $0.message }.joined(separator: "; "))"
    )
    return try XCTUnwrap(compiled.scene)
  }

  private func record(_ scene: Scene) -> [String] {
    var target = RecordingTarget()
    SceneWalk().draw(scene: scene, into: &target)
    return target.calls
  }

  func testBarsAreDrawnAsRectanglesInOrder() throws {
    let drawn = record(
      try scene(
        """
        {"$schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 100, "height": 50, "padding": 0,
         "background": "white",
         "data": [{"name": "t", "values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}]}],
         "scales": [
           {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
           {"name": "y", "domain": {"data": "t", "field": "v"}, "range": "height"}],
         "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
           "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
           "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
           "fill": {"value": "steelblue"}}}}]}
        """
      )
    )
    // The background first — it is not a node, so a walk that only visited the tree would miss it.
    XCTAssertEqual(drawn.first, "rect (0,0 100x50) fill #ffffff")
    let bars = drawn.filter { $0.contains("fill #4682b4") }
    XCTAssertEqual(bars.count, 2, "one rectangle per datum:\n\(drawn.joined(separator: "\n"))")
    // The taller bar reaches further up, which is to say its y is smaller.
    XCTAssertTrue(
      bars[0].contains("(0,25") && bars[1].contains("(50,0"),
      "bars in datum order, positioned by the scales:\n\(bars.joined(separator: "\n"))"
    )
  }

  func testAnAxisContributesRulesAndText() throws {
    let drawn = record(
      try scene(
        """
        {"$schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 120, "height": 60, "padding": 0,
         "scales": [{"name": "y", "domain": [0, 10], "range": "height"}],
         "axes": [{"orient": "left", "scale": "y", "tickCount": 2}],
         "marks": []}
        """
      )
    )
    let joined = drawn.joined(separator: "\n")
    XCTAssertTrue(drawn.contains { $0.contains("line ") }, "an axis draws its ticks:\n\(joined)")
    XCTAssertTrue(drawn.contains { $0.contains("text ") }, "and its labels:\n\(joined)")
    // A group is entered before anything inside it, and the indentation shows the nesting.
    XCTAssertTrue(drawn.contains { $0.hasPrefix("group") }, "an axis is a group:\n\(joined)")
    XCTAssertTrue(
      drawn.contains { $0.hasPrefix("  ") },
      "and its contents are inside it:\n\(joined)"
    )
  }

  func testASymbolBecomesAPath() throws {
    let drawn = record(
      try scene(
        """
        {"$schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 40, "height": 40, "padding": 0,
         "data": [{"name": "t", "values": [{"x": 20, "y": 20}]}],
         "marks": [{"type": "symbol", "from": {"data": "t"}, "encode": {"enter": {
           "x": {"field": "x"}, "y": {"field": "y"}, "size": {"value": 400},
           "fill": {"value": "red"}}}}]}
        """
      )
    )
    let paths = drawn.filter { $0.contains("path ") }
    XCTAssertEqual(paths.count, 1, "one symbol, one path:\n\(drawn.joined(separator: "\n"))")
    // A circle is cubics, because the engine reduces every curve before publishing a scene.
    XCTAssertTrue(paths[0].contains("fill #ff0000"), paths[0])
    XCTAssertTrue(paths[0].contains("commands"), paths[0])
  }

  /// A run's `align` and `baseline` are resolved by the walk, into a pen position.
  ///
  /// This is the bug that survived the CoreText engine: the metrics were right and the labels still sat
  /// over the axis line, because a right-aligned label was being drawn *rightwards* from its anchor
  /// instead of ending there. A target draws from a pen position and knows nothing about alignment, so
  /// this is where it has to be correct.
  func testAlignmentIsResolvedIntoThePenPosition() throws {
    // Three labels at the same x, differing only in alignment.
    let drawn = record(
      try scene(
        """
        {"$schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 200, "height": 60, "padding": 0,
         "marks": [
           {"type": "text", "encode": {"enter": {
             "x": {"value": 100}, "y": {"value": 20},
             "text": {"value": "MMMM"}, "align": {"value": "left"},
             "baseline": {"value": "alphabetic"}, "fill": {"value": "black"}}}},
           {"type": "text", "encode": {"enter": {
             "x": {"value": 100}, "y": {"value": 40},
             "text": {"value": "MMMM"}, "align": {"value": "right"},
             "baseline": {"value": "alphabetic"}, "fill": {"value": "black"}}}},
           {"type": "text", "encode": {"enter": {
             "x": {"value": 100}, "y": {"value": 55},
             "text": {"value": "MMMM"}, "align": {"value": "center"},
             "baseline": {"value": "alphabetic"}, "fill": {"value": "black"}}}}]}
        """
      )
    )
    let labels = drawn.filter { $0.contains("text ") }
    XCTAssertEqual(labels.count, 3, "\(drawn.joined(separator: "\n"))")

    func penX(_ line: String) throws -> Double {
      // `text "MMMM" at (x,y) …`
      let after = try XCTUnwrap(line.components(separatedBy: " at (").last)
      return try XCTUnwrap(Double(after.components(separatedBy: ",")[0]))
    }

    let left = try penX(labels[0])
    let right = try penX(labels[1])
    let centre = try penX(labels[2])

    // Left-aligned starts at the anchor; right-aligned ends there, so it starts a width earlier;
    // centred starts half a width earlier. The width is the engine's, whatever font it measured with.
    XCTAssertEqual(left, 100, accuracy: 0.01, "left-aligned starts at its anchor")
    XCTAssertLessThan(right, left, "right-aligned starts before it")
    XCTAssertEqual(
      centre, (left + right) / 2, accuracy: 0.01,
      "centred sits halfway between the two"
    )
  }

  /// Multi-line text is one call per line, stacked by the line height.
  func testEachLineOfATextRunIsDrawnSeparately() throws {
    let drawn = record(
      try scene(
        """
        {"$schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 120, "height": 80, "padding": 0,
         "marks": [{"type": "text", "encode": {"enter": {
           "x": {"value": 10}, "y": {"value": 20},
           "text": {"value": "first\\nsecond"},
           "fill": {"value": "black"}}}}]}
        """
      )
    )
    let labels = drawn.filter { $0.contains("text ") }
    // Two lines, two calls — a walk that drew `layout.run` once would emit one, and the second line
    // would silently never appear.
    XCTAssertEqual(labels.count, 2, "\(drawn.joined(separator: "\n"))")
    XCTAssertTrue(labels[0].contains("\"first\""), labels[0])
    XCTAssertTrue(labels[1].contains("\"second\""), labels[1])
  }

  func testNothingIsDrawnForAnEmptyChart() throws {
    let drawn = record(
      try scene(
        """
        {"$schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 10, "height": 10, "padding": 0, "marks": []}
        """
      )
    )
    // The groups are still entered — the root and the mark group inside it — and no default
    // background is invented. What matters is that no primitive is emitted: a chart with nothing in
    // it should leave the surface as it found it.
    XCTAssertTrue(
      drawn.allSatisfy { $0.trimmingCharacters(in: .whitespaces).hasPrefix("group") },
      "an empty chart paints nothing:\n\(drawn.joined(separator: "\n"))"
    )
    XCTAssertFalse(drawn.isEmpty, "the groups themselves are still reported")
  }
}
