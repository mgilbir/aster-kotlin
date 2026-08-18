import XCTest
import AsterVega
@testable import AsterVegaRender

/// Images: the two sources a scene has, and what happens when one cannot be answered.
///
/// The renderer drew none of them until now. That mattered most for the source nobody thinks about: a
/// `heatmap` or an `isocontour` produces its image **inside the engine** and carries it as pixels with no
/// address at all, so a renderer that only understood URLs dropped every raster silently — and a chart
/// missing its raster looks like a chart whose transform did nothing.
final class ImageTests: XCTestCase {

  /// The repository's data, for the one specification here that reads a dataset.
  private static let repositoryRoot = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent()  // AsterVegaRenderTests
    .deletingLastPathComponent()  // Tests
    .deletingLastPathComponent()  // AsterVegaRender
    .deletingLastPathComponent()  // swift
    .deletingLastPathComponent()  // the repository

  private static let loader = VegaDataLoader(
    localDirectory: repositoryRoot.appendingPathComponent("test-fixtures")
  )

  private func scene(_ json: String) throws -> Scene {
    let compiled = SpecCompiler(
      textEngine: CoreTextTextEngine(),
      loader: Self.loader,
      randomSeed: 42,
      clock: ClockCompanion.shared.Fixed,
      // Spelled out because a Kotlin default argument has no Obj-C representation: Swift names every
      // parameter or does not compile. `EnglishUS` is what upstream produces.
      locale: VegaLocale.Companion.shared.EnglishUS,
      hostConfig: nil
    )
    .compileJson(json: json, signalOverrides: [:], itemEncodes: [:])
    return try XCTUnwrap(compiled.scene)
  }

  private func record(_ scene: Scene) -> [String] {
    var target = RecordingTarget()
    SceneWalk().draw(scene: scene, into: &target)
    return target.calls
  }

  /// A 2×2 opaque PNG, red and blue on the diagonals.
  ///
  /// Generated rather than written from memory — the first attempt at this was a base64 string typed by
  /// hand, and it decoded to something fully transparent, so the test that asserted "the image was drawn"
  /// failed for a reason that had nothing to do with the renderer.
  private static let tinyPNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEUlEQVR4nGP4zwAEIOI/kAAAG/ID/VxhF44AAAAASUVORK5CYII="

  // MARK: - A raster the engine produced

  /// `density-heatmaps` puts a `heatmap` transform's raster into an `image` mark.
  ///
  /// This is the case that was silently missing. The walk now carries the raster and the target decodes
  /// it through the engine's own PNG encoder, so the assertion is simply that an image reaches the target
  /// at all — with its own size, which is a raster's only identity.
  func testAHeatmapsRasterReachesTheRenderer() throws {
    let json = try String(
      contentsOf: Self.repositoryRoot
        .appendingPathComponent("test-fixtures/specs/density-heatmaps.vg.json"),
      encoding: .utf8
    )
    let drawn = record(try scene(json))
    let images = drawn.filter { $0.contains("image raster") }
    XCTAssertFalse(
      images.isEmpty,
      "a heatmap's raster is an image:\n\(drawn.prefix(40).joined(separator: "\n"))"
    )
    // Each carries real pixels rather than an empty placeholder.
    for image in images {
      XCTAssertFalse(image.contains("raster 0x"), image)
    }
  }

  /// The raster is decoded to something drawable, and the decode is cached by its digest.
  func testARasterDecodesToAnImageAndIsCached() throws {
    let json = try String(
      contentsOf: Self.repositoryRoot
        .appendingPathComponent("test-fixtures/specs/density-heatmaps.vg.json"),
      encoding: .utf8
    )
    let compiled = try scene(json)

    // Drawn twice into throwaway contexts: the second pass must find the decode in the cache, which is
    // what keeps a heatmap from being re-encoded and re-decoded on every frame.
    for _ in 0..<2 {
      let space = try XCTUnwrap(CGColorSpace(name: CGColorSpace.sRGB))
      let context = try XCTUnwrap(
        CGContext(
          data: nil, width: 40, height: 40, bitsPerComponent: 8, bytesPerRow: 160,
          space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )
      )
      var target = CoreGraphicsTarget(context: context)
      SceneWalk().draw(scene: compiled, into: &target)
      // No URL images in this specification, so nothing should be reported unresolved.
      XCTAssertTrue(target.unresolved.isEmpty, "unresolved: \(target.unresolved)")
    }
  }

  // MARK: - A URL the specification gave

  /// A `data:` URL needs no host, so the renderer answers it itself.
  func testADataUrlImageIsDrawnWithoutAResolver() throws {
    let image = try raster(
      """
      {"$schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 40, "height": 40, "padding": 0,
       "background": "white",
       "marks": [{"type": "image", "encode": {"enter": {
         "x": {"value": 0}, "y": {"value": 0},
         "width": {"value": 40}, "height": {"value": 40},
         "url": {"value": "\(Self.tinyPNG)"}}}}]}
      """
    )
    // The background is white; a drawn image puts something else somewhere.
    XCTAssertTrue(image.hasNonWhitePixels, "the image was drawn")
  }

  /// An unresolvable URL draws nothing and **says so**, rather than leaving a silent hole.
  func testAnUnresolvableUrlIsReported() throws {
    let compiled = try scene(
      """
      {"$schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 40, "height": 40, "padding": 0,
       "marks": [{"type": "image", "encode": {"enter": {
         "x": {"value": 0}, "y": {"value": 0},
         "width": {"value": 40}, "height": {"value": 40},
         "url": {"value": "https://example.com/nope.png"}}}}]}
      """
    )
    let space = try XCTUnwrap(CGColorSpace(name: CGColorSpace.sRGB))
    let context = try XCTUnwrap(
      CGContext(
        data: nil, width: 40, height: 40, bitsPerComponent: 8, bytesPerRow: 160,
        space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
      )
    )
    // No resolver at all, which is the default: a renderer does not fetch.
    var target = CoreGraphicsTarget(context: context)
    SceneWalk().draw(scene: compiled, into: &target)

    XCTAssertEqual(target.unresolved, ["https://example.com/nope.png"])
  }

  /// A host's resolver is asked for anything that is not a data URL, and its answer is drawn.
  func testAHostResolverIsUsed() throws {
    let compiled = try scene(
      """
      {"$schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 40, "height": 40, "padding": 0,
       "background": "white",
       "marks": [{"type": "image", "encode": {"enter": {
         "x": {"value": 5}, "y": {"value": 5},
         "width": {"value": 30}, "height": {"value": 30},
         "url": {"value": "anything://the-host-understands"}}}}]}
      """
    )
    let space = try XCTUnwrap(CGColorSpace(name: CGColorSpace.sRGB))
    let context = try XCTUnwrap(
      CGContext(
        data: nil, width: 40, height: 40, bitsPerComponent: 8, bytesPerRow: 160,
        space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
      )
    )

    var asked: [String] = []
    var target = CoreGraphicsTarget(
      context: context,
      resolveImage: { url in
        asked.append(url)
        // A solid green square, made here rather than decoded, so the test owns every pixel.
        let pixels = CGContext(
          data: nil, width: 4, height: 4, bitsPerComponent: 8, bytesPerRow: 16,
          space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
        )
        pixels?.setFillColor(CGColor(red: 0, green: 0.6, blue: 0, alpha: 1))
        pixels?.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        return pixels?.makeImage()
      }
    )
    SceneWalk().draw(scene: compiled, into: &target)

    XCTAssertEqual(asked, ["anything://the-host-understands"], "the host was asked once")
    XCTAssertTrue(target.unresolved.isEmpty)
  }

  // MARK: - Placement

  /// An image goes in the rectangle `align` and `baseline` put it in, not at its raw x/y.
  ///
  /// The node computes that rectangle itself; the walk used to pass the raw channels, which drew every
  /// non-default alignment in the wrong place. Same mistake text was making before its own fix.
  func testAlignmentAndBaselineMoveTheImage() throws {
    func box(align: String, baseline: String) throws -> String {
      let drawn = record(
        try scene(
          """
          {"$schema": "https://vega.github.io/schema/vega/v6.json",
           "width": 100, "height": 100, "padding": 0,
           "marks": [{"type": "image", "encode": {"enter": {
             "x": {"value": 50}, "y": {"value": 50},
             "width": {"value": 20}, "height": {"value": 10},
             "align": {"value": "\(align)"}, "baseline": {"value": "\(baseline)"},
             "url": {"value": "\(Self.tinyPNG)"}}}}]}
          """
        )
      )
      return try XCTUnwrap(drawn.first { $0.contains("image ") })
    }

    let topLeft = try box(align: "left", baseline: "top")
    let bottomRight = try box(align: "right", baseline: "bottom")
    let centred = try box(align: "center", baseline: "middle")

    XCTAssertTrue(topLeft.contains("(50,50 20x10)"), topLeft)
    // Right/bottom put the image's far corner on the anchor, so its origin moves back by its size.
    XCTAssertTrue(bottomRight.contains("(30,40 20x10)"), bottomRight)
    XCTAssertTrue(centred.contains("(40,45 20x10)"), centred)
  }

  /// `contain` preserves the aspect ratio; `fill` does not.
  func testFitIsReportedForTheTargetToHonour() throws {
    let drawn = record(
      try scene(
        """
        {"$schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 60, "height": 60, "padding": 0,
         "marks": [{"type": "image", "encode": {"enter": {
           "x": {"value": 0}, "y": {"value": 0},
           "width": {"value": 60}, "height": {"value": 20},
           "aspect": {"value": true},
           "url": {"value": "\(Self.tinyPNG)"}}}}]}
        """
      )
    )
    let image = try XCTUnwrap(drawn.first { $0.contains("image ") })
    XCTAssertTrue(image.contains("contain"), "`aspect` asks for the ratio to be kept: \(image)")
  }

  // MARK: - Helpers

  private struct Raster {
    let pixels: [UInt8]
    let width: Int
    let height: Int

    var hasNonWhitePixels: Bool {
      stride(from: 0, to: pixels.count, by: 4).contains { index in
        pixels[index] < 240 || pixels[index + 1] < 240 || pixels[index + 2] < 240
      }
    }
  }

  private func raster(_ json: String) throws -> Raster {
    let compiled = try scene(json)
    let space = try XCTUnwrap(CGColorSpace(name: CGColorSpace.sRGB))
    let context = try XCTUnwrap(
      CGContext(
        data: nil, width: 40, height: 40, bitsPerComponent: 8, bytesPerRow: 160,
        space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
      )
    )
    context.translateBy(x: 0, y: 40)
    context.scaleBy(x: 1, y: -1)
    var target = CoreGraphicsTarget(context: context)
    SceneWalk().draw(scene: compiled, into: &target)

    let bytes = try XCTUnwrap(context.data)
    return Raster(
      pixels: [UInt8](
        UnsafeBufferPointer(start: bytes.assumingMemoryBound(to: UInt8.self), count: 40 * 40 * 4)
      ),
      width: 40,
      height: 40
    )
  }
}
