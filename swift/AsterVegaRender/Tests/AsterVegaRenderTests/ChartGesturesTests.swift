import AsterVega
import XCTest

@testable import AsterVegaRender

/// What `ChartGestures.none` promises, and what it delivers.
///
/// It documented itself as "the same as passing no session", and three behaviours keyed off whether a
/// session was present rather than off the gesture set — so a caller following that sentence got a
/// chart that still claimed touches and still blocked a surrounding scroll view. Reported as #124,
/// the other half of #72: that change made the *nil-session* case correct and left this one.
///
/// A SwiftUI view hierarchy cannot be inspected from `swift test`, so these read the predicates the
/// modifiers are built from. That is why they are internal.
@available(macOS 14.0, iOS 17.0, *)
@MainActor
final class ChartGesturesTests: XCTestCase {

  private func scene() -> AsterVega.Scene {
    Scene.Companion.shared.empty(width: 10, height: 10)
  }

  func testNoSessionClaimsNoTouches() {
    let view = VegaChartView(scene: scene(), session: nil)
    XCTAssertFalse(view.claimsTouches)
  }

  func testASessionWithNoGesturesClaimsNoTouchesEither() {
    // The defect. This was true of the nil case and false of this one, so a chart with `.none`
    // blocked the scroll view around it — the exact symptom #72 fixed for the other case.
    let view = VegaChartView(scene: scene(), session: ChartSession(), gestures: .none)
    XCTAssertFalse(view.claimsTouches, "`.none` installs no gesture, so it takes no touch")
  }

  func testASessionWithGesturesDoesClaimTouches() {
    let view = VegaChartView(scene: scene(), session: ChartSession(), gestures: .all)
    XCTAssertTrue(view.claimsTouches)
  }

  func testWithoutDragStillClaimsTouches() {
    // `.withoutDrag` is the set for a chart inside a scroll view: it keeps taps and hover, which do
    // need hit testing, and claims no drag. It must not be swept up with `.none`.
    let view = VegaChartView(scene: scene(), session: ChartSession(), gestures: .withoutDrag)
    XCTAssertTrue(view.claimsTouches)
  }

  func testVoiceOverCanStillActivateWithNoGestures() {
    // The one respect in which `.none` is deliberately *not* "the same as passing no session".
    // Activation goes through an accessibility action rather than a gesture, and a session is what
    // makes it work — so a reader keeps it even when no finger is answered.
    let element = AccessibleElement(
      label: "a bar",
      bounds: RectD(left: 0, top: 0, right: 1, bottom: 1),
      nodeId: ForeignNodeId.shared.setOf(values: [7]).first,
      selected: false,
      isSummary: false,
      role: "mark",
      roleDescription: nil,
      activatable: true,
      adjustableScale: nil)
    let withGestures = VegaChartView(scene: scene(), session: ChartSession(), gestures: .none)

    XCTAssertTrue(
      withGestures.activatable(element),
      "a reader can still activate a mark on a chart that answers no finger")
    XCTAssertFalse(
      VegaChartView(scene: scene(), session: nil).activatable(element),
      "and cannot when there is no session to dispatch to")
  }
}
