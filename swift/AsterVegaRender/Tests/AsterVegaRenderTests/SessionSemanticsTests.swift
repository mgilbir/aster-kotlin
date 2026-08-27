import AsterVega
import XCTest

@testable import AsterVegaRender

/// What ``ChartSession`` owes a host that is touching it while it is busy, and what the drawing owes
/// a reader who has moved the chart.
///
/// The session is the one mutable object on this side and the one an app holds; the controller
/// underneath is documented as unsafe for concurrent use, which is why every mutation goes through
/// ``ChartSession/serialised(_:)``. Each test below is a path that was not going through it, or a
/// point converted through the wrong half of the placement.
@MainActor
final class SessionSemanticsTests: XCTestCase {

  private let specification = """
    {"$schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 200, "height": 100, "padding": 0,
     "signals": [{"name": "size", "value": 20,
                  "bind": {"input": "range", "min": 5, "max": 50}}],
     "data": [{"name": "t", "values": [{"c": "a", "v": 60}, {"c": "b", "v": 90}]}],
     "scales": [
       {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
       {"name": "y", "domain": [0, 100], "range": "height"}],
     "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"update": {
       "x": {"scale": "x", "field": "c"}, "width": {"signal": "size"},
       "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
       "fill": {"value": "steelblue"}}}}]}
    """

  private func session() -> ChartSession {
    ChartSession(textEngine: CoreTextTextEngine())
  }

  // MARK: - Clearing a chart

  /// `load("")` clears the chart, and a compile that was in flight does not put it back.
  ///
  /// The clear was synchronous while the compile was not: it emptied everything the compile was
  /// about to write, and the compile then wrote it back. So a host emptying its editor while a
  /// specification was still compiling was left looking at the previous chart — and `loading` stayed
  /// true, because the block that would have cleared it had been cancelled after it was counted. A
  /// spinner over a chart the host had asked to be rid of, with no way out of either.
  func testClearingWhileCompilingLeavesNoChartAndNoSpinner() async throws {
    let chart = session()
    chart.load(specification: specification)
    // Not awaited: the point is that the clear arrives with the compile still in flight.
    chart.load(specification: "")
    await chart.settled()

    XCTAssertNil(chart.scene, "the chart was asked to be cleared")
    XCTAssertFalse(chart.loading, "and nothing is still loading")
    XCTAssertTrue(chart.diagnostics.isEmpty)
    XCTAssertTrue(chart.controls.isEmpty)
  }

  /// And clearing a settled chart still clears it, which is the ordinary case.
  func testClearingASettledChartClearsIt() async throws {
    let chart = session()
    chart.load(specification: specification)
    await chart.settled()
    XCTAssertNotNil(chart.scene)

    chart.load(specification: "")
    await chart.settled()
    XCTAssertNil(chart.scene)
    XCTAssertFalse(chart.loading)
  }

  // MARK: - Setting a signal

  /// A control set while a compile is in flight reaches the chart the compile produces.
  ///
  /// `set(signal:to:)` was the one mutating entry point not queued, so it called `setSignal` — which
  /// walks the signal updater and the event dispatcher — while `setSpecAsync` was rebuilding both of
  /// them off this actor. That is the exact race the queue exists to prevent, and the reason it
  /// exists is that a touch during the first compile once left a chart stuck showing "no scene".
  ///
  /// A reader can reach this: a slider on a chart that is still loading.
  func testASignalSetDuringACompileSurvivesIntoTheChart() async throws {
    let chart = session()
    chart.load(specification: specification)
    chart.set(signal: "size", to: ForeignSignals.shared.ofNumber(value: 44))
    await chart.settled()

    XCTAssertNotNil(chart.scene)
    let control = try XCTUnwrap(chart.controls.first { $0.signal == "size" })
    XCTAssertEqual(
      ForeignSignals.shared.number(value: chart.value(of: control))?.doubleValue, 44)
  }

  // MARK: - What a failed compile reports

  /// A document that fails reports **its own** diagnostics, not the previous document's.
  ///
  /// `try?` gave nil on a throw, `diagnostics` was left holding the last chart's, and the failure
  /// message was read out of it — so a host was told a new document had failed for a reason
  /// belonging to the one before it, which is worse than being told nothing at all.
  func testAFailedCompileDoesNotReportThePreviousDocumentsDiagnostics() async throws {
    let chart = session()
    // A first document that compiles and carries a diagnostic of its own: a `url` with no loader.
    chart.load(
      specification: """
      {"$schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 100, "height": 50, "padding": 0,
       "data": [{"name": "t", "url": "https://example.com/rows.json"}],
       "marks": []}
      """
    )
    await chart.settled()
    let first = chart.diagnostics.map(\.message)
    XCTAssertFalse(first.isEmpty, "the first document should have said something")

    // A second that is not a specification at all.
    chart.load(specification: "{\"not\": \"a chart\"}")
    await chart.settled()

    for message in first {
      XCTAssertFalse(
        chart.diagnostics.map(\.message).contains(message),
        "a diagnostic from the previous document survived: \(message)")
      XCTAssertNotEqual(
        chart.failure, message,
        "the failure was taken from the previous document's diagnostics")
    }
  }

  // MARK: - Gestures

  /// A pan moves the viewport by the **pixels** it was given, undivided — at any `contentScale`.
  ///
  /// This is the contract `VegaChartView`'s drag gesture has to satisfy and was not:
  /// `InteractionState.viewportOffset` holds the pan in surface pixels, and `visibleViewport` is
  /// what divides by `contentScale * viewportScale`. The view divided *before* dispatching, so at
  /// any fit other than 1 the chart moved by a fraction of the finger — the further from 1 the fit,
  /// the further the drawing lagged behind the drag.
  ///
  /// Asserted here rather than on the gesture, because a SwiftUI view hierarchy cannot be inspected
  /// from `swift test` — the same reason `placement(in:)` and `claimsTouches` are internal and
  /// checked through `test-fixtures/host-conformance`. What this pins is the rule: `contentScale`
  /// makes no difference to what a pan of thirty pixels does, so a caller dividing by it is wrong.
  func testAPanMovesTheViewportByThePixelsGiven() async throws {
    let chart = session()
    chart.load(specification: specification)
    await chart.settled()
    // A fit of one half, which is where a second division would show.
    chart.place(contentScale: 0.5, viewport: Rect(x: 0, y: 0, width: 100, height: 50))

    chart.pan(by: Point(x: 30, y: -12), phase: GesturePhase.changed)
    XCTAssertEqual(chart.viewport.offsetX, 30, accuracy: 0.001)
    XCTAssertEqual(chart.viewport.offsetY, -12, accuracy: 0.001)

    // And the same pan at a different fit does the same thing, which is the whole point: the number
    // a gesture hands over is a distance on the surface and has nothing to divide by.
    chart.resetViewport()
    chart.place(contentScale: 2.0, viewport: Rect(x: 0, y: 0, width: 400, height: 200))
    chart.pan(by: Point(x: 30, y: -12), phase: GesturePhase.changed)
    XCTAssertEqual(chart.viewport.offsetX, 30, accuracy: 0.001)
    XCTAssertEqual(chart.viewport.offsetY, -12, accuracy: 0.001)
  }
}

extension ChartSession {
  /// Waits until nothing is queued, so a test can assert about a settled session.
  ///
  /// Polling rather than awaiting the queue directly: `pending` is private and the queue is
  /// deliberately not a public seam. A handful of yields is enough — every task here is a compile of
  /// a two-mark chart — and the loop is bounded so a hang fails the test rather than the suite.
  func settled() async {
    for _ in 0..<200 {
      if !loading {
        // One more turn, so a block that was scheduled behind the last one has run.
        await Task.yield()
        if !loading { return }
      }
      await Task.yield()
    }
  }
}
