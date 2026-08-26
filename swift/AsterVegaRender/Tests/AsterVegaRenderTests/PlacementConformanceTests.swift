import AsterVega
import XCTest

@testable import AsterVegaRender

/// This renderer against `test-fixtures/host-conformance/placement.txt`.
///
/// One golden, one reader per host. The three renderers each compute where a scene goes in a slot,
/// and they disagreed until #99 in 0.3.0 — this one and the Compose Multiplatform one centred a
/// scene, and the Android view pinned it to the padded top-left, so the same chart sat in a
/// different place depending on the host. Nothing compared them, and `scripts/host-parity.py`
/// could not: a signature says nothing about arithmetic.
@available(macOS 14.0, iOS 17.0, *)
@MainActor
final class PlacementConformanceTests: XCTestCase {

  func testPlacesASceneWhereEveryOtherRendererPlacesIt() throws {
    let expected = HostConformance.cases(try HostConformance.golden("placement.txt"))
    XCTAssertFalse(expected.isEmpty)

    for (rawCase, numbers) in expected {
      let (sceneSize, slot) = try HostConformance.placementCase(rawCase)
      let view = VegaChartView(
        scene: Scene.Companion.shared.empty(width: sceneSize.0, height: sceneSize.1),
        session: nil)
      let placed = try XCTUnwrap(
        view.placement(in: CGSize(width: slot.0, height: slot.1)), "for \(rawCase)")

      XCTAssertEqual(
        numbers,
        [
          HostConformance.six(placed.scale),
          HostConformance.six(Double(placed.left)),
          HostConformance.six(Double(placed.top)),
        ],
        "for \(rawCase)")
    }
  }
}
