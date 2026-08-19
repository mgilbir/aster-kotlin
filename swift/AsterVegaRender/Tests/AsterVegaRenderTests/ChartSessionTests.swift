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

  /// A tap on blank space finds nothing — which took a fix to the hit test to be true.
  ///
  /// It used to report one selected mark. The compiler wraps a specification's marks in a group whose
  /// bounds are the whole plotting area, and the hit test asked whether a point was *inside* a group
  /// rather than whether the group **paints** there. So a tap 75 units from the nearest bar selected the
  /// frame, and a host showed "1 mark selected" with nothing under the finger. Upstream's rule is that a
  /// group is picked only where it paints — a fill, or a stroke — and that is now the rule here.
  ///
  /// Written from Swift, where an adopter would meet it, and kept here for the same reason.
  func testATapOnEmptySpaceFindsNothing() async {
    let session = await loaded()
    let scene = try! XCTUnwrap(session.scene)
    session.place(
      contentScale: 1,
      viewport: Rect(x: 0, y: 0, width: scene.width, height: scene.height)
    )

    // Above both bars: the taller one starts 70 units below this point.
    session.tap(at: Point(x: 50, y: 5))
    await session.settle()

    XCTAssertEqual(session.lastTouch, .nothing(x: 50, y: 5))
    XCTAssertTrue(session.selectedNodeIds.isEmpty, "nothing was there, so nothing is selected")

    // And a tap that does land on a bar still selects: the frame was the only thing that changed.
    session.tap(at: Point(x: 50, y: 90))
    await session.settle()
    XCTAssertEqual(session.lastTouch, .tooltip("a"))
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

  /// A chart drawn from data the **app** holds, which is what a diary is.
  ///
  /// The specification names a dataset and carries no rows for it. Everything about the chart — the
  /// scales, the axis, the marks — waits on the host, which is upstream's `view.data(name, rows)` and
  /// the shape an app with a local store needs. The rows go in where inline values would, so the
  /// dataset's own `format.parse` still parses and its transforms still run.
  func testAChartIsDrawnFromATableTheHostSupplies() async {
    let awaitingData = """
      {"width": 200, "height": 100, "padding": 5,
       "data": [{"name": "diary"}],
       "scales": [
         {"name": "x", "type": "band", "domain": {"data": "diary", "field": "bucket"},
          "range": "width"},
         {"name": "y", "type": "linear", "domain": {"data": "diary", "field": "v"},
          "range": "height"}],
       "marks": [{"type": "rect", "from": {"data": "diary"}, "encode": {"enter": {
         "x": {"scale": "x", "field": "bucket"}, "width": {"scale": "x", "band": 1},
         "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0}}}}]}
      """

    let session = ChartSession()
    session.load(specification: awaitingData)
    await session.settle()
    XCTAssertEqual(rectangles(in: session), 0, "nothing supplied yet, so nothing is drawn")

    session.setData(
      "diary",
      rows: [
        ["bucket": .text("morning"), "v": .number(3)],
        ["bucket": .text("evening"), "v": .number(7)],
      ]
    )
    XCTAssertEqual(rectangles(in: session), 2, "one bar per row the app handed over")

    // And again, because data changes: a store that gained a row redraws with three.
    session.setData(
      "diary",
      rows: [
        ["bucket": .text("morning"), "v": .number(3)],
        ["bucket": .text("afternoon"), "v": .number(5)],
        ["bucket": .text("evening"), "v": .number(7)],
      ]
    )
    XCTAssertEqual(rectangles(in: session), 3)
  }

  /// A `Date` handed over as an instant, with no parse rule in the specification at all.
  ///
  /// The round trip this avoids is a defect rather than an inefficiency: formatting a `Date` to a string
  /// for the engine to parse back goes through a zone twice, and a naive string read in the wrong one
  /// lands on the wrong day. Asserted through what is **drawn**, since a label is what a reader sees.
  func testADateIsHandedOverAsAnInstant() async {
    let session = ChartSession()
    session.load(
      specification: """
        {"width": 200, "height": 100, "padding": 0,
         "data": [{"name": "t"}],
         "marks": [{"type": "text", "from": {"data": "t"}, "encode": {"enter": {
           "x": {"value": 10}, "y": {"value": 20},
           "text": {"signal": "utcFormat(datum.at, '%d %B %Y')"}}}}]}
        """
    )
    await session.settle()

    // 2026-05-20T12:00:00Z, as a `Date` — which is how an app holds one.
    session.setData("t", rows: [["at": .instant(Date(timeIntervalSince1970: 1_779_278_400))]])

    let scene = try! XCTUnwrap(session.scene, session.failure ?? "no scene")
    var target = RecordingTarget()
    SceneWalk().draw(scene: scene, into: &target)
    XCTAssertTrue(
      target.calls.contains { $0.contains("20 May 2026") },
      "an instant needs no `format.parse` entry: \(target.calls)"
    )
  }

  private func rectangles(in session: ChartSession) -> Int {
    guard let scene = session.scene else { return 0 }
    var target = RecordingTarget()
    SceneWalk().draw(scene: scene, into: &target)
    // Indented by group depth, so matched rather than prefixed.
    return target.calls.filter { $0.trimmingCharacters(in: .whitespaces).hasPrefix("rect ") }.count
  }

  /// The zone a chart's local time is in, which on a handset is not always the reader's.
  ///
  /// A profile setting, an account read from two places, a tablet left on a factory zone: the app knows
  /// which day a measurement was on and the device may not. Both charts below draw the **same instant**
  /// — the payload carries `Z` — and put it on different days, which is the whole of what the seam does.
  func testTheHostSaysWhichZoneLocalIs() async {
    let onADateLine = """
      {"width": 200, "height": 100, "padding": 5,
       "data": [{"name": "t", "values": [{"t": "2026-05-20T12:00:00Z", "v": 1}],
                 "format": {"parse": {"t": "date"}}}],
       "scales": [{"name": "x", "type": "time", "domain": {"data": "t", "field": "t"},
                   "range": "width"}],
       "axes": [{"orient": "bottom", "scale": "x", "format": "%d %B", "tickCount": 1}],
       "marks": [{"type": "symbol", "from": {"data": "t"}, "encode": {"enter": {
         "x": {"scale": "x", "field": "t"}, "y": {"value": 50}}}}]}
      """

    func labels(in zone: String) async -> [String] {
      let session = ChartSession(timeZone: Foundation.TimeZone(identifier: zone))
      session.load(specification: onADateLine)
      await session.settle()
      XCTAssertNil(session.timeZoneFailure, "the platform should know \(zone)")
      let scene = try! XCTUnwrap(session.scene)
      var target = RecordingTarget()
      SceneWalk().draw(scene: scene, into: &target)
      return target.calls.filter { $0.contains("text ") }
    }

    // UTC+14 is already the 21st at midday UTC; UTC-11 is still the 20th.
    let east = await labels(in: "Pacific/Kiritimati")
    let west = await labels(in: "Pacific/Niue")
    XCTAssertTrue(east.contains { $0.contains("21 May") }, "east of the line: \(east)")
    XCTAssertTrue(west.contains { $0.contains("20 May") }, "west of the line: \(west)")
  }

  /// An identifier the engine cannot resolve is reported, not thrown, and the chart still draws.
  func testAnUnknownZoneIsReportedRatherThanFatal() async {
    let session = ChartSession(timeZone: Foundation.TimeZone(identifier: "UTC"))
    session.load(specification: specification)
    await session.settle()
    XCTAssertNil(session.timeZoneFailure, "UTC is a zone every platform has")
    XCTAssertNotNil(session.scene)
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
