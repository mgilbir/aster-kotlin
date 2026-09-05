import XCTest
import AsterVega
@testable import AsterVegaRender

/// The chart's **own** accessibility actions, reached from this host.
///
/// `VegaChartController.accessibilityActions` has offered zooming and resetting since it was
/// written, and **no host wired them** — the feature was built, tested and documented against
/// `UIAccessibilityCustomAction` and the call was never made, so a reader could reach every mark in
/// a chart and never the view they were drawn in (#226).
///
/// `VegaChartView` presents them with `.accessibilityAction(named:)`, which is that primitive in
/// SwiftUI's spelling. What a test can hold without a view hierarchy is the half either side of it:
/// that the session offers the list, that it changes as the chart changes, and that performing one
/// does the work and reports whether it did.
@available(macOS 14.0, iOS 17.0, tvOS 17.0, watchOS 10.0, *)
final class ChartActionsTests: XCTestCase {

  private let spec = """
    {"$schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 200, "height": 100, "padding": 0, "autosize": "none",
     "data": [{"name": "t", "values": [{"c": "a", "v": 3}, {"c": "b", "v": 7}]}],
     "scales": [
       {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
       {"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"}, "range": "height"}],
     "axes": [{"orient": "left", "scale": "y"}],
     "marks": [{"type": "rect", "from": {"data": "t"},
                "encode": {"enter": {"x": {"scale": "x", "field": "c"},
                                     "width": {"scale": "x", "band": 1},
                                     "y": {"value": 0}, "y2": {"value": 100}}}}]}
    """

  @MainActor
  private func loaded() async -> ChartSession {
    let session = ChartSession()
    session.load(specification: spec)
    await session.settle()
    return session
  }

  @MainActor
  private func kinds(_ session: ChartSession) -> [ChartActionKind] {
    session.accessibilityActions.map { $0.kind }
  }

  /// At rest: the two zooms, and no reset, because there is nothing to undo.
  @MainActor
  func testAChartAtRestOffersZoomingButNoReset() async {
    let session = await loaded()
    XCTAssertEqual(
      kinds(session), [.zoomIn, .zoomOut],
      "the session offers the wrong actions for a chart at rest")
  }

  /// Performing one does the work, reports it, and the way back appears.
  @MainActor
  func testPerformingZoomInChangesTheViewAndOffersAReset() async {
    let session = await loaded()
    XCTAssertTrue(session.perform(.zoomIn), "zooming in through the session reported no change")
    XCTAssertGreaterThan(
      session.viewport.scale, 1.0, "the viewport did not zoom")
    XCTAssertTrue(
      kinds(session).contains(.resetZoom), "a zoomed chart offers no way back")
    XCTAssertTrue(session.perform(.resetZoom), "the reset did nothing")
    XCTAssertFalse(
      kinds(session).contains(.resetZoom),
      "a chart back at rest still offers a reset that would do nothing")
  }

  /// An action that is not offered is refused, so a caller knows not to announce a change.
  @MainActor
  func testAnActionThatIsNotOfferedIsRefused() async {
    let session = await loaded()
    XCTAssertFalse(
      session.perform(.resetZoom), "a reset was performed on a chart already at rest")
  }

  /// And the axis reset appears only once an axis has been adjusted.
  @MainActor
  func testTheAxisResetAppearsOnlyAfterAnAdjustment() async {
    let session = await loaded()
    XCTAssertFalse(
      kinds(session).contains(.resetDomains), "an unadjusted chart offers an axis reset")
    XCTAssertTrue(
      session.adjustScaleDomain(scale: "y", narrow: true),
      "the axis could not be adjusted, so this decides nothing")
    XCTAssertTrue(
      kinds(session).contains(.resetDomains), "an adjusted chart offers no way back")
    XCTAssertTrue(session.perform(.resetDomains), "the axis reset did nothing")
  }

  /// The labels are the engine's, in the chart's own locale — a host has nowhere else to get them.
  @MainActor
  func testTheLabelsComeFromTheEngine() async {
    let session = await loaded()
    XCTAssertFalse(
      session.accessibilityActions.contains { $0.label.isEmpty },
      "an action reached the host with no label, so a reader hears a nameless action")
  }
}
