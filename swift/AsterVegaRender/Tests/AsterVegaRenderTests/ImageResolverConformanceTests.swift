import AsterVega
import CoreGraphics
import XCTest

@testable import AsterVegaRender

/// This renderer against `test-fixtures/host-conformance/image-resolver.txt`.
///
/// One golden, one reader per host. The contract — a URL is asked once however many frames are
/// drawn, and a refusal is remembered — is implemented separately by each renderer: a cache in the
/// Android renderer, an `ImageCache` in the Compose Multiplatform target, a static one here. Three
/// implementations of one contract is the shape that drifts.
final class ImageResolverConformanceTests: XCTestCase {

  private func specification(_ urls: [String]) -> String {
    let marks = urls.map {
      """
      {"type": "image", "encode": {"enter": {
        "x": {"value": 0}, "y": {"value": 0}, "width": {"value": 10}, "height": {"value": 10},
        "aspect": {"value": false}, "url": {"value": "\($0)"}}}}
      """
    }.joined(separator: ",")
    return """
      {"width": 20, "height": 20, "padding": 0, "marks": [\(marks)]}
      """
  }

  func testAsksForAUrlOnceHoweverManyFrames() throws {
    let expected = HostConformance.cases(try HostConformance.golden("image-resolver.txt"))
    XCTAssertFalse(expected.isEmpty)

    for (rawCase, names) in expected {
      let (urls, frames) = try HostConformance.repeatedCase(rawCase)
      // The cache here is static and outlives a target, which is what makes a URL asked once across
      // frames — and what makes it necessary to clear between cases.
      CoreGraphicsTarget.clearImageCache()

      var asked: [String] = []
      let compiled = SpecCompiler(
        textEngine: MetricTextEngine(advanceRatio: 0.6, ascentRatio: 0.8, descentRatio: 0.2),
        loader: DenyLoader(), randomSeed: 42, clock: ClockCompanion.shared.Fixed,
        locale: VegaLocale.Companion.shared.EnglishUS, hostConfig: nil, containerSize: nil,
        hostData: nil, timeZone: nil
      )
      .compileJson(json: specification(urls), signalOverrides: [:], itemEncodes: [:])
      let drawn = try XCTUnwrap(compiled.scene, "\(compiled.diagnostics)")

      let space = try XCTUnwrap(CGColorSpace(name: CGColorSpace.sRGB))
      for _ in 0..<frames {
        let context = try XCTUnwrap(
          CGContext(
            data: nil, width: 20, height: 20, bitsPerComponent: 8, bytesPerRow: 80,
            space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
        var target = CoreGraphicsTarget(
          context: context,
          resolveImage: { url in
            asked.append(url)
            return nil
          })
        SceneWalk().draw(scene: drawn, into: &target)
      }
      XCTAssertEqual(names, asked, "for \(rawCase)")
    }
  }
}
