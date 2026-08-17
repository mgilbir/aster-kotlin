import XCTest
import AsterVega
@testable import AsterVegaRender

/// A touch reaching the chart, from Swift.
///
/// This is the half no screenshot proves. A tap has to travel through `VegaChartController.dispatch`
/// into the compiled dataflow, be hit-tested against the scene, fire whatever `on` handlers the
/// specification declared, and come back out as a different scene. If any link is missing the app looks
/// exactly the same as one where the gesture was never wired — which is what it was until now.
///
/// The controller is the host contract the Android view uses, so these tests also pin down the two
/// details that contract has: `contentScale` for a chart drawn scaled to fit, and points given in the
/// surface's coordinates rather than the scene's.
final class TouchTests: XCTestCase {

  /// A chart whose fill depends on a signal a click sets. Tapping the left bar should recolour it.
  private let specification = """
    {"$schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 200, "height": 100, "padding": 0,
     "signals": [
       {"name": "picked", "value": null,
        "on": [{"events": "rect:click", "update": "datum.c"}]}
     ],
     "data": [{"name": "t", "values": [{"c": "a", "v": 60}, {"c": "b", "v": 90}]}],
     "scales": [
       {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
       {"name": "y", "domain": [0, 100], "range": "height"}],
     "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"update": {
       "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
       "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
       "fill": [
         {"test": "picked === datum.c", "value": "firebrick"},
         {"value": "steelblue"}
       ]}}}]}
    """

  private func controller() -> VegaChartController {
    VegaChartController(
      initialScene: Scene.companion.empty(width: 0, height: 0),
      textEngine: CoreTextTextEngine(),
      clock: { KotlinLong(value: 0) },
      loader: DenyLoader(),
      scheduler: nil
    )
  }

  private func record(_ controller: VegaChartController) throws -> [String] {
    var target = RecordingTarget()
    SceneWalk().draw(scene: try XCTUnwrap(controller.snapshot.scene), into: &target)
    return target.calls
  }

  func testATapOnAMarkReachesTheDataflowAndChangesTheChart() throws {
    let chart = controller()
    _ = chart.setSpec(json: specification)

    let before = try record(chart)
    XCTAssertEqual(
      before.filter { $0.contains("#4682b4") }.count, 2,
      "both bars start steelblue:\n\(before.joined(separator: "\n"))"
    )

    // The left bar spans x 0…100, y 40…100 — so this is inside it.
    chart.dispatch(event: ChartInputEventTap(point: PointD(x: 50, y: 70)))

    let after = try record(chart)
    XCTAssertEqual(
      after.filter { $0.contains("#b22222") }.count, 1,
      "the tapped bar is recoloured, which only happens if the click reached `on`:\n"
        + after.joined(separator: "\n")
    )
    XCTAssertEqual(after.filter { $0.contains("#4682b4") }.count, 1, "the other bar is unchanged")
  }

  func testATapOnEmptySpaceChangesNothing() throws {
    let chart = controller()
    _ = chart.setSpec(json: specification)
    let before = try record(chart)

    // Above both bars: the shorter one reaches y=40, so y=5 is over neither.
    chart.dispatch(event: ChartInputEventTap(point: PointD(x: 50, y: 5)))

    XCTAssertEqual(try record(chart), before, "a miss is not a selection")
  }

  /// `contentScale` is the host contract for a chart drawn scaled to fit.
  ///
  /// The controller divides an incoming point by it, so a host that drew at half size and dispatched
  /// raw view coordinates would miss every mark by exactly the fit factor. That was a real defect on
  /// Android; this is the same trap on a second platform, checked rather than remembered.
  func testAScaledChartIsHitAtTheScaledPoint() throws {
    let chart = controller()
    _ = chart.setSpec(json: specification)
    chart.contentScale = 0.5

    // Half scale, so the left bar's interior at scene (50,70) is at surface (25,35).
    chart.dispatch(event: ChartInputEventTap(point: PointD(x: 25, y: 35)))
    XCTAssertEqual(
      try record(chart).filter { $0.contains("#b22222") }.count, 1,
      "a scaled chart is hit where it was drawn"
    )
  }

  func testTheSameSurfacePointMissesWhenTheScaleIsIgnored() throws {
    let chart = controller()
    _ = chart.setSpec(json: specification)
    chart.contentScale = 0.5

    // The unscaled point a host would send if it forgot `contentScale`: scene (100,140) is off the
    // chart entirely, so nothing is selected. This is the failure the test above prevents.
    chart.dispatch(event: ChartInputEventTap(point: PointD(x: 50, y: 70)))
    XCTAssertTrue(
      try record(chart).filter { $0.contains("#b22222") }.isEmpty,
      "ignoring the fit factor misses, which is why it is part of the contract"
    )
  }

  // MARK: - The rest of the gesture vocabulary

  /// A pan moves the viewport, and the controller accumulates the deltas it is given.
  ///
  /// Incremental by contract: a host that handed over a gesture's *cumulative* translation on every
  /// change would accelerate the pan, which is why the view sends the difference each time.
  func testAPanMovesTheViewportByTheDeltasGiven() throws {
    let chart = controller()
    _ = chart.setSpec(json: specification)
    XCTAssertEqual(chart.snapshot.interactionState.viewportOffset.dx, 0)

    chart.dispatch(
      event: ChartInputEventPan(delta: VectorD(dx: 10, dy: 4), phase: GesturePhase.changed)
    )
    chart.dispatch(
      event: ChartInputEventPan(delta: VectorD(dx: 5, dy: 1), phase: GesturePhase.changed)
    )
    chart.dispatch(
      event: ChartInputEventPan(delta: VectorD(dx: 0, dy: 0), phase: GesturePhase.ended)
    )

    let offset = chart.snapshot.interactionState.viewportOffset
    XCTAssertEqual(offset.dx, 15, accuracy: 0.001, "the two deltas add up")
    XCTAssertEqual(offset.dy, 5, accuracy: 0.001)
  }

  /// A pinch scales the viewport about its anchor, and multiplies rather than replaces.
  func testAZoomScalesTheViewport() throws {
    let chart = controller()
    _ = chart.setSpec(json: specification)
    XCTAssertEqual(chart.snapshot.interactionState.viewportScale, 1)

    chart.dispatch(
      event: ChartInputEventZoom(
        scaleFactor: 2, anchor: PointD(x: 100, y: 50), phase: GesturePhase.changed
      )
    )
    XCTAssertEqual(chart.snapshot.interactionState.viewportScale, 2, accuracy: 0.001)

    // Multiplied, not assigned: two pinches of 2 make 4.
    chart.dispatch(
      event: ChartInputEventZoom(
        scaleFactor: 2, anchor: PointD(x: 100, y: 50), phase: GesturePhase.changed
      )
    )
    XCTAssertEqual(chart.snapshot.interactionState.viewportScale, 4, accuracy: 0.001)
  }

  /// A panned or zoomed chart can be put back, which is what makes the gestures safe to offer.
  func testTheViewportResets() throws {
    let chart = controller()
    _ = chart.setSpec(json: specification)
    chart.dispatch(
      event: ChartInputEventPan(delta: VectorD(dx: 30, dy: 12), phase: GesturePhase.ended)
    )
    chart.dispatch(
      event: ChartInputEventZoom(
        scaleFactor: 3, anchor: PointD(x: 0, y: 0), phase: GesturePhase.ended
      )
    )

    chart.resetViewport()

    let state = chart.snapshot.interactionState
    XCTAssertEqual(state.viewportScale, 1, accuracy: 0.001)
    XCTAssertEqual(state.viewportOffset.dx, 0, accuracy: 0.001)
    XCTAssertEqual(state.viewportOffset.dy, 0, accuracy: 0.001)
  }

  /// A pan is inverted along with the fit scale when a point is hit-tested.
  ///
  /// The two together are what a host gets wrong most easily: the controller divides by
  /// `contentScale * viewportScale` and subtracts the offset, so a chart that has been panned is hit at
  /// the panned position — not where the mark was drawn before the finger moved.
  func testAMarkIsHitAtItsPannedPosition() throws {
    let chart = controller()
    _ = chart.setSpec(json: specification)
    // Push the chart 40 units right, so the left bar's interior at scene x=50 is now at surface x=90.
    chart.dispatch(
      event: ChartInputEventPan(delta: VectorD(dx: 40, dy: 0), phase: GesturePhase.ended)
    )

    chart.dispatch(event: ChartInputEventTap(point: PointD(x: 90, y: 70)))
    XCTAssertEqual(
      try record(chart).filter { $0.contains("#b22222") }.count, 1,
      "the tap follows the chart it can see"
    )
  }

  /// A pointer moving without touching. No fingers involved, and iPad has them.
  func testAPointerMoveIsAccepted() throws {
    let chart = controller()
    _ = chart.setSpec(json: specification)

    chart.setHitTestOptions(options: HitTestOptions.companion.Mouse)
    chart.dispatch(event: ChartInputEventPointerMoved(point: PointD(x: 50, y: 70)))
    // Hovering a bar is what a tooltip hangs off, so the hovered node is the thing to check.
    XCTAssertNotNil(
      chart.snapshot.interactionState.hoveredNodeId,
      "a pointer over a mark hovers it"
    )

    chart.dispatch(event: ChartInputEventPointerExited(point: PointD(x: 50, y: 70)))
    XCTAssertNil(chart.snapshot.interactionState.hoveredNodeId, "leaving clears it")
  }

  /// A selection is readable from Swift, which is what lets the app say what was touched.
  func testTheSelectionIsReadableAfterATap() throws {
    let chart = controller()
    _ = chart.setSpec(json: specification)
    XCTAssertTrue(chart.snapshot.interactionState.selection.isEmpty, "nothing is selected to begin")

    chart.dispatch(event: ChartInputEventTap(point: PointD(x: 50, y: 70)))

    // `nodeIds` are `SceneNodeId`s — a value class, and therefore opaque across the Obj-C boundary —
    // but `datumIds` and `isEmpty` cross, which is enough for a host to report a touch.
    let selection = chart.snapshot.interactionState.selection
    XCTAssertFalse(selection.isEmpty, "the tap selected something")
  }
}
