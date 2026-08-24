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
final class ForeignNodeIdTests: XCTestCase {

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
