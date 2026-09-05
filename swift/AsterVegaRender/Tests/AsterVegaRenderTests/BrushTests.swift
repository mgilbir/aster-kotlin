import XCTest
import AsterVega
@testable import AsterVegaRender

/// A **brush** — `[mousedown, mouseup] > mousemove` — driven from this host.
///
/// The idiom every interval selection in Vega and Vega-Lite is written in, and it could not be driven
/// from here at all: `ChartSession` exposed a tap, a long press, a pan, a zoom and a hover, and a pan
/// produces no Vega event whatsoever — the viewport transform is this engine's own idea rather than
/// something a specification asked for. So a chart that brushes compiled, drew, and never responded,
/// with nothing to say why.
///
/// Verified here rather than only on the JVM because that is the whole point of the seam: the engine
/// is shared, so a bug in it shows up everywhere, and a bug in *reaching* it shows up on one host and
/// is invisible from the others. No host test dragged a brush before this one.
@available(macOS 14.0, iOS 17.0, tvOS 17.0, watchOS 10.0, *)
final class BrushTests: XCTestCase {

  private let brushable = """
    {
      "$schema": "https://vega.github.io/schema/vega/v6.json",
      "width": 200, "height": 100, "padding": 0, "autosize": "none",
      "data": [{"name": "t", "values": [{"v": 1}]}],
      "signals": [
        {"name": "anchor", "value": 0, "on": [{"events": "mousedown", "update": "x()"}]},
        {"name": "brush", "value": [0, 0],
         "on": [{"events": "[mousedown, mouseup] > mousemove",
                 "update": "[min(anchor, x()), max(anchor, x())]"}]}
      ],
      "marks": [
        {"type": "rect", "from": {"data": "t"},
         "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                              "width": {"value": 200}, "height": {"value": 100},
                              "fill": {"value": "#eeeeee"}}}},
        {"type": "rect", "from": {"data": "t"},
         "encode": {"enter": {"y": {"value": 0}, "height": {"value": 20},
                              "fill": {"value": "#333333"}},
                    "update": {"x": {"signal": "brush[0]"}, "x2": {"signal": "brush[1]"}}}}]
    }
    """

  @MainActor
  private func loaded() async -> ChartSession {
    let session = ChartSession()
    session.load(specification: brushable)
    await session.settle()
    return session
  }

  /// The brush rect's span, read off the **scene** rather than the signal.
  ///
  /// `ChartSession` exposes no way to read an arbitrary signal, and adding one for a test would be
  /// the wrong shape: what matters is that the chart a reader looks at changed. The second rect's
  /// `x` and `x2` come straight from `brush`, so its edges are the interval.
  @MainActor
  private func brush(_ session: ChartSession) -> [Double] {
    guard let scene = session.scene else { return [] }
    var rects: [RectNode] = []
    func walk(_ node: SceneNode) {
      if let rect = node as? RectNode { rects.append(rect) }
      if let group = node as? GroupNode { group.children.forEach(walk) }
    }
    walk(scene.root)
    // The backdrop is the full-width one; the brush is the other.
    guard let brushed = rects.first(where: { $0.rect.width < 200 }) ?? rects.last else { return [] }
    return [brushed.rect.left, brushed.rect.right]
  }

  /// A press, a move and a release select the interval between them.
  @MainActor
  func testADragBrushesTheIntervalItCovers() async throws {
    let session = await loaded()
    session.pointerDown(at: Point(x: 20, y: 50))
    await session.settle()
    session.pointerMoved(at: Point(x: 60, y: 50))
    await session.settle()

    XCTAssertEqual(
      brush(session), [20, 60],
      "a drag through the session did not brush; the pointer seam is not reaching the dataflow")
  }

  /// And the release closes it, so the brush stops following the pointer.
  @MainActor
  func testAReleasedDragStopsFollowing() async throws {
    let session = await loaded()
    session.pointerDown(at: Point(x: 20, y: 50))
    await session.settle()
    session.pointerMoved(at: Point(x: 60, y: 50))
    await session.settle()
    session.pointerUp(at: Point(x: 60, y: 50))
    await session.settle()
    session.pointerMoved(at: Point(x: 190, y: 50))
    await session.settle()

    XCTAssertEqual(
      brush(session), [20, 60],
      "the brush kept following after the release, so the latch never closed")
  }

  /// A move with no press does nothing, so the gate is doing the gating.
  @MainActor
  func testAMoveWithNoPressDoesNothing() async throws {
    let session = await loaded()
    session.pointerMoved(at: Point(x: 60, y: 50))
    await session.settle()
    XCTAssertEqual(brush(session), [0, 0], "an ungated move fired the brush")
  }

  /// `.pointer` is in `ChartGestures.all`, so a chart that owns its space brushes without asking.
  func testPointerIsPartOfEveryGesture() {
    XCTAssertTrue(
      ChartGestures.all.contains(.pointer),
      "a chart asking for every gesture cannot brush")
    XCTAssertFalse(
      ChartGestures.withoutDrag.contains(.pointer),
      "a chart in a scroll view claims the drag it was promised it would not")
  }

  /// A hover **enters** before it moves, which is what `mouseover` is.
  ///
  /// `PointerEntered` is what the controller turns into `pointerover` and `mouseover`, and this host
  /// never produced one: `hover(at:)` dispatched a move for every point and an exit for `nil`. So
  /// `@mark:mouseover` — what most highlight and tooltip specifications are written against — was
  /// inert here while working on Android (#228).
  ///
  /// A browser fires `mouseover` once on entry and `mousemove` thereafter, and that is the shape
  /// asserted: the counts are drawn as rect widths so they can be read off the scene, since
  /// `ChartSession` exposes no way to read an arbitrary signal.
  @MainActor
  func testAHoverEntersOnceAndMovesAfterwards() async throws {
    let counting = """
      {"$schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 200, "height": 100, "padding": 0, "autosize": "none",
       "data": [{"name": "t", "values": [{"v": 1}]}],
       "signals": [
         {"name": "overs", "value": 0, "on": [{"events": "mouseover", "update": "overs + 1"}]},
         {"name": "moves", "value": 0, "on": [{"events": "mousemove", "update": "moves + 1"}]}
       ],
       "marks": [
         {"type": "rect", "from": {"data": "t"},
          "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                               "width": {"value": 200}, "height": {"value": 100},
                               "fill": {"value": "#eeeeee"}}}},
         {"type": "rect", "name": "overs", "from": {"data": "t"},
          "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0}, "height": {"value": 10}},
                     "update": {"width": {"signal": "overs"}}}},
         {"type": "rect", "name": "moves", "from": {"data": "t"},
          "encode": {"enter": {"x": {"value": 0}, "y": {"value": 20}, "height": {"value": 10}},
                     "update": {"width": {"signal": "moves"}}}}]}
      """
    let session = ChartSession()
    session.load(specification: counting)
    await session.settle()

    /// The two counters, as the widths of the two rects that draw them.
    func counts() -> (overs: Double, moves: Double) {
      guard let scene = session.scene else { return (-1, -1) }
      var rects: [RectNode] = []
      func walk(_ node: SceneNode) {
        if let rect = node as? RectNode { rects.append(rect) }
        if let group = node as? GroupNode { group.children.forEach(walk) }
      }
      walk(scene.root)
      let counters = rects.filter { $0.rect.height == 10 }
      guard counters.count == 2 else { return (-1, -1) }
      return (counters[0].rect.width, counters[1].rect.width)
    }

    session.hover(at: Point(x: 20, y: 50))
    await session.settle()
    XCTAssertEqual(
      counts().overs, 1,
      "the first hover did not fire mouseover, so `@mark:mouseover` is inert on this host")
    XCTAssertEqual(counts().moves, 0, "the entry also fired a move")

    session.hover(at: Point(x: 40, y: 50))
    await session.settle()
    session.hover(at: Point(x: 60, y: 50))
    await session.settle()
    XCTAssertEqual(counts().overs, 1, "a move inside the chart fired a second mouseover")
    XCTAssertEqual(counts().moves, 2, "the moves after the entry did not fire mousemove")

    // Leaving and returning enters again, exactly as it would in a page.
    session.hover(at: nil)
    await session.settle()
    session.hover(at: Point(x: 20, y: 50))
    await session.settle()
    XCTAssertEqual(
      counts().overs, 2, "returning to the chart did not fire a second mouseover")
  }
}
