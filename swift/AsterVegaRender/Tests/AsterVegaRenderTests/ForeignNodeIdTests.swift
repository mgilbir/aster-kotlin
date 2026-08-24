import AsterVega
import XCTest

@testable import AsterVegaRender

/// A Swift host can read a node id, including the ones that arrive boxed.
///
/// `SceneNodeId` is a `value class`, and Kotlin/Native unwraps one at the boundary **wherever it
/// can** — which turns out to be most places. The gap is narrower than #120 reported, and knowing
/// which half is which is the whole of the fix:
///
/// - a **non-null** id already crosses as an `Int64`. `SceneNode.id` and `AccessibleElement.nodeId`
///   have always been readable from Swift. A `ForeignNodeId.value(id:)` for those would have been
///   `Int64` to `Int64` — an identity function that reads as a fix.
/// - a **nullable** or **collected** id boxes, because a box is the only representation an optional
///   or a set element has. `InteractionState.hoveredNodeId`, `focusedNodeId` and
///   `ChartSelection.nodeIds` are opaque, which is why `ChartSession.selectedNodeIds` is a
///   `Set<AnyHashable>` that can only be passed back untouched.
@available(macOS 14.0, iOS 17.0, *)
final class ForeignNodeIdTests: XCTestCase {

  /// Two bars, one of which a tap can land on.
  private let specification = """
    {"$schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 200, "height": 100, "padding": 0,
     "data": [{"name": "t", "values": [{"c": "a", "v": 20}, {"c": "b", "v": 30}]}],
     "scales": [
       {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
       {"name": "y", "domain": [0, 100], "range": "height"}],
     "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"update": {
       "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
       "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0}}}}]}
    """

  /// The assertion the rest of this file was missing.
  ///
  /// Every other test here builds a box with `setOf` and reads it back with `values` — its own
  /// function feeding its own function, which proves the plumbing and *not* the reported case. This
  /// one taps a real chart, takes the ids the **engine** produced, and checks the number that comes
  /// out matches the `id_` of the node actually under the finger.
  ///
  /// That is the loop: engine → opaque box → number → the same node. Without it, `ForeignNodeId`
  /// would be a set of functions that agree with each other and possibly with nothing else.
  @MainActor
  func testAnIdTheEngineProducedUnboxesToTheNodeItNames() async throws {
    let session = ChartSession()
    session.load(specification: specification)
    await session.settle()
    let scene = try XCTUnwrap(session.scene, session.failure ?? "no scene")
    session.place(
      contentScale: 1, viewport: Rect(x: 0, y: 0, width: scene.width, height: scene.height))

    // Inside the left bar: the band is the left half, and the bar occupies the bottom fifth.
    session.tap(at: Point(x: 50, y: 90))
    await session.settle()

    // Numbers, straight from the session. This read `Set<AnyHashable>` until #120 — boxes a host
    // could only hand back — and the first draft of this test passed that set to `values(ids:)`,
    // which crashed with `kotlin.Long cannot be cast to SceneNodeId` once the property started
    // returning numbers. Worth recording: `values(ids:)` is `NSSet<id>` at the boundary, so Swift
    // types it as `Set<AnyHashable>` and a `Set<Int64>` satisfies it at compile time and fails
    // inside Kotlin at run time. A host reading `selectedNodeIds` never meets that edge now.
    let numbers = session.selectedNodeIds
    XCTAssertFalse(numbers.isEmpty, "the tap should have hit a bar")

    // And they name real nodes: every number is the `id_` of some node in the scene this drew.
    var idsInScene: Set<Int64> = []
    func walk(_ node: any AsterVega.SceneNode) {
      idsInScene.insert(node.id_)
      if let group = node as? GroupNode {
        for child in group.children { walk(child) }
      }
    }
    walk(scene.root)

    for number in numbers {
      XCTAssertTrue(
        idsInScene.contains(number),
        "\(number) came out of a selection and names no node in the scene")
    }

    // And the raw form still round-trips, for a host driving the controller rather than a session.
    let boxed = ForeignNodeId.shared.setOf(values: numbers.map { KotlinLong(value: $0) })
    XCTAssertEqual(
      numbers.sorted(),
      ForeignNodeId.shared.values(ids: boxed).map { $0.int64Value }.sorted())
  }

  /// The same loop for the **nullable** position, which boxes for a different reason.
  ///
  /// Driven through `VegaChartController` rather than `ChartSession`, because the session does not
  /// expose the interaction state at all — it publishes `selectedNodeIds` and nothing else. A host
  /// that wants a hover uses the controller, which the framework exports, so that is what this uses.
  @MainActor
  func testAHoveredIdUnboxesToTheNodeUnderThePointer() throws {
    let controller = VegaChartController(
      initialScene: Scene.Companion.shared.empty(width: 0, height: 0),
      textEngine: MetricTextEngine(advanceRatio: 0.6, ascentRatio: 0.8, descentRatio: 0.2),
      clock: { KotlinLong(value: 0) },
      loader: DenyLoader(),
      scheduler: nil,
      locale: VegaLocale.Companion.shared.EnglishUS,
      hostConfig: nil,
      containerSize: nil,
      hostData: nil,
      timeZone: nil)
    _ = controller.setSpec(json: specification)
    let scene = controller.snapshot.scene
    controller.contentScale = 1
    controller.dispatch(event: ChartInputEventPointerMoved(point: PointD(x: 50, y: 90)))

    let hovered = controller.snapshot.interactionState.hoveredNodeId
    XCTAssertNotNil(hovered, "the pointer is over a bar")
    let number = try XCTUnwrap(ForeignNodeId.shared.valueOrNull(id: hovered)).int64Value

    var idsInScene: Set<Int64> = []
    func walk(_ node: any AsterVega.SceneNode) {
      idsInScene.insert(node.id_)
      if let group = node as? GroupNode {
        for child in group.children { walk(child) }
      }
    }
    walk(scene.root)
    XCTAssertTrue(idsInScene.contains(number), "\(number) names no node in the scene")
  }

  private func scene() throws -> AsterVega.Scene {
    let compiled = SpecCompiler(
      textEngine: MetricTextEngine(advanceRatio: 0.6, ascentRatio: 0.8, descentRatio: 0.2),
      loader: DenyLoader(),
      randomSeed: 42,
      clock: ClockCompanion.shared.Fixed,
      locale: VegaLocale.Companion.shared.EnglishUS,
      hostConfig: nil,
      containerSize: nil,
      hostData: nil,
      timeZone: nil
    )
    .compileJson(
      json: """
        {"width": 20, "height": 20, "padding": 0,
         "marks": [{"type": "rect", "encode": {"update": {
           "x": {"value": 0}, "y": {"value": 0},
           "width": {"value": 20}, "height": {"value": 20}}}}]}
        """,
      signalOverrides: [:],
      itemEncodes: [:]
    )
    return try XCTUnwrap(compiled.scene, "\(compiled.diagnostics)")
  }

  func testANodesOwnIdWasAlreadyReadable() throws {
    // The half of #120 that was already fine, asserted so nobody "fixes" it into an identity
    // function later. It is an `Int64`, not an opaque box — and it is spelled **`id_`**, because
    // Kotlin/Native renames a property called `id` to avoid Obj-C's keyword. That rename is very
    // likely why the report concluded it was unreadable: `SceneNodeId` really is absent from
    // `foreign-api.txt` as a *type*, while the property crosses unwrapped under another name.
    let root = try scene().root
    let value: Int64 = root.id_
    XCTAssertGreaterThanOrEqual(value, 0)
  }

  func testABoxedIdCanBeUnwrapped() throws {
    // The shape a host actually meets: an id that arrived inside a set, or as an optional, and is
    // therefore opaque. Round-tripping through `setOf` is how a host builds one to hand back and
    // reads one it was given.
    let boxed = ForeignNodeId.shared.setOf(values: [7, 11])
    XCTAssertEqual(2, boxed.count)

    let read = ForeignNodeId.shared.values(ids: boxed).map { $0.int64Value }.sorted()
    XCTAssertEqual([7, 11], read)

    let one = try XCTUnwrap(boxed.first)
    let unwrapped = try XCTUnwrap(ForeignNodeId.shared.valueOrNull(id: one))
    XCTAssertTrue([7, 11].contains(unwrapped.int64Value))
  }

  func testAbsenceIsAnsweredWithNilRatherThanZero() throws {
    // `SceneNodeId.None` is 0, so a host that received 0 for "no node" could not tell it from a real
    // node whose id happens to be 0. Nil says absent; `noneValue()` is there to compare against.
    XCTAssertNil(ForeignNodeId.shared.valueOrNull(id: nil))
    XCTAssertEqual(0, ForeignNodeId.shared.noneValue())
  }

  func testASelectionReadsAsNumbers() throws {
    // The case `ChartSession.selectedNodeIds` documents as unreachable: a set of ids that could only
    // be passed straight back, because the boxes were opaque.
    let selection = ChartSelection(
      nodeIds: ForeignNodeId.shared.setOf(values: [3]), datumIds: [], interval: nil)
    XCTAssertEqual(
      [3], ForeignNodeId.shared.values(ids: selection.nodeIds).map { $0.int64Value })
  }
}
