import XCTest
import AsterVega
@testable import AsterVegaRender

/// The semantic tree, read from Swift.
///
/// The policy — which marks are announced, in what order, and when a dense chart becomes a summary — lives
/// in `AccessibilityTree` in the core, because a screen reader's experience of a chart is not a platform
/// detail. It used to live inside Android's `ExploreByTouchHelper` subclass, which is why iOS had no
/// accessibility at all: the rules were in a class no other host could reach.
///
/// These tests are the boundary half. The rules themselves are tested in `AccessibilityTreeTest` on the
/// Kotlin side; what matters here is that a Swift host can read the elements, their labels, their frames
/// and their selection, and therefore build VoiceOver elements from them.
final class AccessibilityTests: XCTestCase {

  private func scene(_ json: String) throws -> Scene {
    let compiled = SpecCompiler(
      textEngine: CoreTextTextEngine(),
      loader: DenyLoader(),
      randomSeed: 42,
      clock: ClockCompanion.shared.Fixed,
      locale: VegaLocale.Companion.shared.EnglishUS,
      hostConfig: nil,
      containerSize: nil,
      hostData: nil,
      timeZone: nil
    )
    .compileJson(json: json, signalOverrides: [:], itemEncodes: [:])
    return try XCTUnwrap(compiled.scene)
  }

  private let bars = """
    {"$schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 200, "height": 100, "padding": 0,
     "data": [{"name": "t", "values": [{"c": "a", "v": 30}, {"c": "b", "v": 80}]}],
     "scales": [
       {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
       {"name": "y", "domain": [0, 100], "range": "height"}],
     "marks": [{"type": "rect", "from": {"data": "t"},
       "encode": {"enter": {
         "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
         "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
         "fill": {"value": "steelblue"},
         "description": {"signal": "'Category ' + datum.c + ', value ' + datum.v"}}}}]}
    """

  func testAChartsMarksAreReadableAsAccessibleElements() throws {
    let elements = AccessibilityTree.shared.elements(
      scene: try scene(bars),
      selectedNodeIds: [],
      captions: VegaCaptionsCompanion.shared.English,
      maxExposedMarks: AccessibilityTree.shared.MAX_EXPOSED_MARKS
    )

    XCTAssertFalse(elements.isEmpty, "a chart with described marks has elements to announce")
    // The engine builds each label from the mark's own description, which is what a reader hears.
    XCTAssertTrue(
      elements.contains { $0.label.contains("Category a") },
      "labels come from the specification: \(elements.map { $0.label })"
    )
    for element in elements {
      XCTAssertFalse(element.label.isEmpty, "an element with no label announces nothing")
      // A frame a reader can touch: VoiceOver needs somewhere to put its cursor.
      XCTAssertGreaterThan(element.bounds.width, 0)
      XCTAssertGreaterThan(element.bounds.height, 0)
      XCTAssertFalse(element.isSummary, "two marks is not a dense chart")
    }
  }

  /// A dense chart is one summary, not thousands of elements a reader cannot escape.
  func testADenseChartIsSummarised() throws {
    let many = (0..<(Int(AccessibilityTree.shared.MAX_EXPOSED_MARKS) + 40)).map {
      "{\"c\": \"p\($0)\", \"v\": \($0 % 90 + 5)}"
    }
    .joined(separator: ", ")

    let elements = AccessibilityTree.shared.elements(
      scene: try scene(
        """
        {"$schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 400, "height": 200, "padding": 0,
         "data": [{"name": "t", "values": [\(many)]}],
         "scales": [
           {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
           {"name": "y", "domain": [0, 100], "range": "height"}],
         "marks": [{"type": "rect", "from": {"data": "t"},
           "encode": {"enter": {
             "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
             "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
             "description": {"signal": "'point ' + datum.c"}}}}]}
        """
      ),
      selectedNodeIds: [],
      captions: VegaCaptionsCompanion.shared.English,
      maxExposedMarks: AccessibilityTree.shared.MAX_EXPOSED_MARKS
    )

    XCTAssertEqual(elements.count, 1, "a dense chart says what it is instead of enumerating itself")
    XCTAssertTrue(elements[0].isSummary)
    XCTAssertTrue(elements[0].label.contains("marks"), elements[0].label)
  }

  /// The threshold counts **data marks**, and an app may set it itself.
  ///
  /// It used to count every focusable element, so a chart's axes and its legend pushed it over before
  /// the data was dense — measured: 118 points, two axes and a legend is 121 focusable elements, and
  /// the whole tree collapsed at 118 marks. A reader then lost per-mark exploration of the entire
  /// chart rather than of the crowded part, and lost the axes and the legend with it, which are a
  /// handful of elements and are exactly what is worth reading when the data cannot be walked.
  func testTheThresholdCountsMarksAndTheHostMaySetIt() throws {
    let cap = Int(AccessibilityTree.shared.MAX_EXPOSED_MARKS)
    let points = (0..<(cap - 2)).map { "{\"c\": \"p\($0)\", \"v\": \($0 % 90 + 5)}" }
      .joined(separator: ", ")
    let withGuides = try scene(
      """
      {"$schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 400, "height": 200, "padding": 0,
       "data": [{"name": "t", "values": [\(points)]}],
       "scales": [
         {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
         {"name": "y", "domain": [0, 100], "range": "height"}],
       "axes": [{"orient": "bottom", "scale": "x"}, {"orient": "left", "scale": "y"}],
       "marks": [{"type": "rect", "from": {"data": "t"},
         "encode": {"enter": {
           "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
           "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
           "description": {"signal": "'point ' + datum.c"}}}}]}
      """
    )

    let all = AccessibilityTree.shared.elements(
      scene: withGuides,
      selectedNodeIds: [],
      captions: VegaCaptionsCompanion.shared.English,
      maxExposedMarks: AccessibilityTree.shared.MAX_EXPOSED_MARKS
    )
    // `cap - 2` marks plus two axes is `cap` focusable elements, which the old rule left alone —
    // one more point and it would have collapsed the axes too.
    XCTAssertEqual(all.count, cap)
    XCTAssertFalse(all.contains { $0.isSummary }, "the data is not dense")

    // And an app that wants a shorter list says so. The axes survive the collapse.
    let tight = AccessibilityTree.shared.elements(
      scene: withGuides,
      selectedNodeIds: [],
      captions: VegaCaptionsCompanion.shared.English,
      maxExposedMarks: 10
    )
    XCTAssertEqual(tight.count, 3, "a summary and the two axes: \(tight.map { $0.label })")
    XCTAssertTrue(tight[0].isSummary)
    XCTAssertFalse(tight[1].isSummary)
    XCTAssertFalse(tight[2].isSummary)
  }

  /// The cap is one number, shared, so the two hosts cannot describe the same chart differently.
  func testTheCapIsTheEnginesOwn() {
    XCTAssertEqual(AccessibilityTree.shared.MAX_EXPOSED_MARKS, 120)
  }
}
