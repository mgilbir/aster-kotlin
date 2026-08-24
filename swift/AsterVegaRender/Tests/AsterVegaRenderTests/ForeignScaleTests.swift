import AsterVega
import XCTest

@testable import AsterVegaRender

/// A Swift host can draw its own legend for a banded scale.
///
/// The numbers a legend is built from — where the buckets cut, what labels them, where a value sits
/// along the bar — are members of `BinnedScale` with **default bodies**. An Obj-C protocol cannot
/// carry a default, so Kotlin/Native leaves them out: a `QuantizeScale` arrives in Swift with
/// `domain`, `name` and `invertExtent` and none of the rest.
///
/// Nothing inside the engine noticed, because the engine draws its own legends. Found by enumerating
/// what does not cross rather than by a report — `scripts/foreign-coverage.py`.
final class ForeignScaleTests: XCTestCase {

  private let foreign = ForeignScale.shared

  /// Three buckets over 0…90, which cut at 30 and 60.
  private func quantize() -> QuantizeScale {
    QuantizeScale(
      name: "colour",
      domain: [KotlinDouble(value: 0), KotlinDouble(value: 90)],
      rangeValues: [
        VegaValueCompanion.shared.of(value___: "a"),
        VegaValueCompanion.shared.of(value___: "b"),
        VegaValueCompanion.shared.of(value___: "c"),
      ])
  }

  func testTheBucketCutsAreReadable() {
    let scale = quantize()
    XCTAssertTrue(foreign.isBanded(scale: scale))
    XCTAssertEqual([30, 60], foreign.thresholds(scale: scale).map { $0.doubleValue })
  }

  func testTheLegendLabelsAndTheirTop() {
    let scale = quantize()
    let values = foreign.legendValues(scale: scale).map { $0.doubleValue }

    // One per bucket at its lower edge, the first unbounded — which upstream marks by leaving that
    // swatch's label empty rather than printing a number nothing bounds.
    XCTAssertEqual(3, values.count)
    XCTAssertEqual(-Double.infinity, values.first)
    XCTAssertEqual([30, 60], Array(values.dropFirst()))

    // Nothing bounds a quantize scale's top bucket, so a host writes "≥ 60" rather than a number.
    XCTAssertEqual(Double.infinity, foreign.legendMax(scale: scale))
  }

  func testAValuesPlaceAlongTheBar() {
    let scale = quantize()
    XCTAssertEqual(0, foreign.legendExtentLow(scale: scale)?.doubleValue)
    XCTAssertEqual(90, foreign.legendExtentHigh(scale: scale)?.doubleValue)
    XCTAssertEqual(0.5, foreign.legendFraction(scale: scale, value: 45), accuracy: 0.0001)
    XCTAssertEqual(0, foreign.legendFraction(scale: scale, value: 0), accuracy: 0.0001)
  }

  func testAnUnboundedBucketEndReadsAsNilRatherThanZero() {
    let scale = quantize()
    // The lowest bucket has no lower cut: a threshold at 30 says nothing about how far below it the
    // first bucket reaches. Nil is that fact, and a host printing 0 there would be inventing one.
    XCTAssertNil(foreign.bucketLow(scale: scale, index: 0))
    XCTAssertEqual(30, foreign.bucketHigh(scale: scale, index: 0)?.doubleValue)
    XCTAssertEqual(60, foreign.bucketLow(scale: scale, index: 2)?.doubleValue)
    XCTAssertNil(foreign.bucketHigh(scale: scale, index: 2), "nothing bounds the top bucket")
  }

  func testAScaleWithNoBandsAnswersEmptyRatherThanFailing() {
    // A host does not have to know which kind it holds before asking.
    let linear = LinearScale(
      name: "x",
      domain: [KotlinDouble(value: 0), KotlinDouble(value: 1)],
      range: [KotlinDouble(value: 0), KotlinDouble(value: 100)],
      clamp: false,
      round: false,
      bins: nil)
    XCTAssertFalse(foreign.isBanded(scale: linear))
    XCTAssertEqual([], foreign.thresholds(scale: linear).map { $0.doubleValue })
    XCTAssertNil(foreign.bins(scale: linear))
  }
}
