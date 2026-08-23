import AsterVega
import CoreGraphics
import XCTest

@testable import AsterVegaRender

/// The **call shapes** this package's documentation promises, pinned so that adding a parameter cannot
/// quietly break one.
///
/// These assert almost nothing at runtime and that is the point: they are compile-time pins. If a
/// parameter is inserted in the wrong place, this file stops building and the Swift gate fails —
/// which is cheaper and louder than the way the last such break was found.
///
/// **How it was found.** Three closure parameters were added to `VegaChartView.init`, all with
/// defaults, all additive as far as the exported surface was concerned. But Swift matches a trailing
/// closure by scanning *forward* from the last argument a caller labelled and taking the first
/// parameter that can accept one — so `VegaChartView(scene:session:) { placement in … }`, the idiom in
/// this package's own README and in both demo screens, rebound to the newly added image resolver and
/// failed with `cannot convert value of type '()' to closure result type 'CGImage?'`. Nothing in
/// `swift test` noticed, because nothing in `swift test` used the trailing-closure form; the failure
/// surfaced in `scripts/ios-demo.sh --check`, on CI, after the change had landed.
///
/// A trailing closure is not part of a signature, so `foreign-api.txt` could not have caught it
/// either. It is a property of the *order* of the parameters, and this is where that order is asserted.
@available(macOS 14.0, iOS 17.0, *)
final class CallShapeTests: XCTestCase {

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

  /// A trailing closure on `VegaChartView` is `onPlaced`, which is what the README shows.
  ///
  /// `placement` having a type means the closure bound to `onPlaced` and not to something else: a
  /// resolver would make it a `String`, and the body returning nothing would make it a `CGImage?`
  /// mismatch. Both are compile errors, so this test failing to build *is* the assertion.
  @MainActor
  func testATrailingClosureIsOnPlaced() throws {
    var placed: ChartPlacement?
    let view = VegaChartView(scene: try scene(), session: nil) { placement in
      placed = placement
    }
    // Nothing calls `onPlaced` without a layout pass, so there is nothing to await here. The value is
    // read so the capture is not optimised into a different meaning.
    XCTAssertNil(placed)
    XCTAssertNotNil(view)
  }

  /// And the same shape with a session, which is how both demo screens call it.
  @MainActor
  func testATrailingClosureIsOnPlacedWithASession() throws {
    let session = ChartSession()
    let view = VegaChartView(scene: try scene(), session: session) { placement in
      _ = placement.scale
    }
    XCTAssertNotNil(view)
  }

  /// The new parameters are still reachable, by label, alongside a trailing `onPlaced`.
  @MainActor
  func testTheAddedParametersAreReachableByLabel() throws {
    let view = VegaChartView(
      scene: try scene(),
      session: nil,
      gestures: .withoutDrag,
      // In declaration order, which the compiler enforces — `onPlaced` before the two that were added
      // after it. Worth having written down: the order exists so a *trailing* closure lands on
      // `onPlaced`, and this is the shape a caller that labels everything has to use.
      onPlaced: { _ in },
      resolveImage: { _ in nil },
      onUnresolvedImage: { _ in }
    )
    XCTAssertNotNil(view)
  }
}
