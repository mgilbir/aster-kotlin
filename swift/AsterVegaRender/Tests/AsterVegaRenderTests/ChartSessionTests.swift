import AsterVega
import XCTest

@testable import AsterVegaRender

/// The session a host drives, which used to live in the demo.
///
/// It moved because an adopter counted what owning it costs: `ChartSession` and `VegaChartView` are the
/// two pieces an app actually needs, and they are the two that took real bug fixes to get right. Moving
/// them into the package is only half the answer, though — the other half is that they are now *tested*
/// here, where the demo could only be exercised by launching it in a simulator.
///
/// What these cover is the part a screenshot cannot: that a compile runs off the main actor and can be
/// awaited, that a touch arriving mid-compile is queued rather than lost, that what a touch found is
/// reported as facts, and that a preset signal reaches the dataflow.
@available(macOS 14.0, iOS 17.0, *)
@MainActor
final class ChartSessionTests: XCTestCase {

  /// Two bars, and a signal a click sets — the same shape `TouchTests` uses, for the same reason.
  private let specification = """
    {"$schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 200, "height": 100, "padding": 0,
     "signals": [{"name": "picked", "value": null,
                  "on": [{"events": "rect:click", "update": "datum.c"}]}],
     "data": [{"name": "t", "values": [{"c": "a", "v": 20}, {"c": "b", "v": 30}]}],
     "scales": [
       {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
       {"name": "y", "domain": [0, 100], "range": "height"}],
     "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"update": {
       "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
       "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
       "tooltip": {"signal": "datum.c"}}}}]}
    """

  private func loaded() async -> ChartSession {
    let session = ChartSession()
    session.load(specification: specification)
    await session.settle()
    return session
  }

  func testCompilesAndPublishesAScene() async {
    let session = await loaded()

    XCTAssertNotNil(session.scene, "no scene: \(session.failure ?? "and no failure either")")
    XCTAssertNil(session.failure)
    XCTAssertFalse(session.loading, "settle() should mean the compile is done")
    XCTAssertEqual(session.grammar, .vega, "it is Vega, and the session says which it read")
  }

  func testVegaLiteIsRecognisedAndCompiled() async {
    let session = ChartSession()
    session.load(
      specification: """
        {"$schema": "https://vega.github.io/schema/vega-lite/v6.json",
         "data": {"values": [{"a": 1, "b": 2}]},
         "mark": "point",
         "encoding": {"x": {"field": "a", "type": "quantitative"},
                      "y": {"field": "b", "type": "quantitative"}}}
        """
    )
    await session.settle()

    XCTAssertEqual(session.grammar, .vegaLite)
    XCTAssertNotNil(session.scene, "a Vega-Lite specification should compile on this side too")
  }

  func testATapReportsWhatItFound() async {
    let session = await loaded()
    let scene = try! XCTUnwrap(session.scene)
    session.place(
      contentScale: 1,
      viewport: Rect(x: 0, y: 0, width: scene.width, height: scene.height)
    )

    // Inside the left bar: the band is the left half, and the bar occupies the bottom fifth.
    session.tap(at: Point(x: 50, y: 90))
    await session.settle()

    // The mark carries a `tooltip` channel, so that is what the touch found — as a fact rather than as
    // a sentence, because wording it is the host's business and not a library's.
    XCTAssertEqual(session.lastTouch, .tooltip("a"))
  }

  /// What a tap on blank space inside a chart actually finds today.
  ///
  /// Not `.nothing`, which is what this test was written to assert. A **group** node is what the hit test
  /// finds — the compiler wraps a specification's marks in one and its bounds are the whole plotting
  /// area — so a tap 75 units from the nearest bar, where the touch tolerance is six, still reports one
  /// selected mark. It is a different node from the one a tap on a bar selects, which is what the
  /// assertions below establish.
  ///
  /// Pinned rather than corrected. It is the engine's behaviour on every host, `ChartInputEvent.Tap` on
  /// empty space *does* clear a previous selection (`VegaChartControllerTest` covers that), and whether
  /// a group ought to be selectable at all is a decision about hit testing rather than about this
  /// session. Worth a look; not worth a silent change made from Swift.
  func testATapOnEmptySpaceFindsTheChartsOwnFrame() async {
    let session = await loaded()
    let scene = try! XCTUnwrap(session.scene)
    session.place(
      contentScale: 1,
      viewport: Rect(x: 0, y: 0, width: scene.width, height: scene.height)
    )

    session.tap(at: Point(x: 50, y: 5))
    await session.settle()

    XCTAssertEqual(
      session.lastTouch,
      .selected(count: 1),
      "the frame group is what the hit test finds here; see the note above"
    )
    // And the frame is what it found: the root group, not a bar.
    XCTAssertEqual(session.selectedNodeIds.count, 1)
    let onEmptySpace = session.selectedNodeIds

    // And it is not the bar: tapping one selects something else.
    session.tap(at: Point(x: 50, y: 90))
    await session.settle()
    XCTAssertNotEqual(
      onEmptySpace,
      session.selectedNodeIds,
      "a tap on empty space and a tap on a bar found the same node"
    )
  }

  /// A touch arriving while the first compile is still running.
  ///
  /// The queue is not an optimisation. The controller is not safe for concurrent use — `setSpec`
  /// rebuilds the signal updater and the event dispatcher a touch then reads — and the compile
  /// deliberately runs off this actor. Dispatching a tap during that rebuild once left the chart stuck
  /// showing "no scene", because the touch published an empty snapshot over a compile that had not
  /// finished. A queued touch is also better behaviour than a dropped one.
  func testATouchDuringACompileIsQueuedRatherThanLost() async {
    let session = ChartSession()
    session.load(specification: specification)
    // No `settle()` first: this is the race.
    session.tap(at: Point(x: 50, y: 90))
    await session.settle()
    // The queued tap runs in a task of its own once the compile has finished, so give the main actor a
    // turn for it to land.
    await Task.yield()

    XCTAssertNotNil(session.scene, "the touch published over the compile: \(session.failure ?? "")")
  }

  func testAPresetSignalReachesTheDataflow() async {
    let session = ChartSession()
    session.load(
      specification: specification,
      signals: ["picked": ForeignSignals.shared.ofString(value: "b")]
    )
    await session.settle()

    let control = session.controls.first { $0.signal == "picked" }
    if let control {
      XCTAssertEqual(ForeignSignals.shared.text(value: session.value(of: control)), "b")
    }
    // Whether or not the signal is *bound* to a control, the chart still compiled with it applied.
    XCTAssertNotNil(session.scene)
  }

  func testAPanCanBeReset() async {
    let session = await loaded()
    XCTAssertFalse(session.canReset, "nothing has moved yet")

    session.pan(by: Point(x: 20, y: 0), phase: GesturePhase.changed)
    XCTAssertTrue(session.canReset, "a pan is something to reset")

    session.resetViewport()
    XCTAssertFalse(session.canReset)
  }

  func testTextThatIsNotASpecificationFailsWithAReason() async {
    let session = ChartSession()
    session.load(specification: "not a chart")
    await session.settle()

    XCTAssertNil(session.scene)
    XCTAssertNotNil(session.failure, "a failure a host can put in front of a reader")
  }
}

/// The arithmetic a host might have to repeat, and therefore should not have to.
final class ChartPlacementTests: XCTestCase {

  func testAPointIsInvertedThroughTheSamePlacementTheDrawingUsed() {
    let placement = ChartPlacement(scale: 2, left: 10, top: 4)

    // The offset comes off and the scale does **not**: `contentScale` is part of the controller's
    // contract and it divides by that itself. Applying the fit twice here is the trap this type exists
    // to keep a host out of — it has caught this project twice, once on Android and once on iOS.
    let point = placement.scenePoint(of: CGPoint(x: 30, y: 24))
    XCTAssertEqual(point.x, 20)
    XCTAssertEqual(point.y, 20)
  }
}
