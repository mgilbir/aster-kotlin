#if canImport(CoreGraphics)
import CoreGraphics
import XCTest
import AsterVega
@testable import AsterVegaRender

/// The CoreGraphics target, checked in pixels.
///
/// ``SceneWalkTests`` asserts the *calls* a scene produces, which is where a renderer's logic lives —
/// but it never executes a line of CoreGraphics, so a target that built its paths wrong would pass
/// every one of those tests. These render into a bitmap and read the result back.
///
/// What they deliberately do **not** do is compare against a committed golden PNG. Antialiasing and
/// path rasterisation are the platform's, not ours, and they change between OS releases: a byte-exact
/// golden would fail on an upgrade that broke nothing, and the pressure would then be to loosen the
/// comparison. Sampling instead — this pixel is the bar's colour, that one is the background — tests
/// the thing that would actually be wrong, and says why when it fails.
final class CoreGraphicsTargetTests: XCTestCase {

  private let width = 100
  private let height = 50

  /// Renders a specification and returns a reader of the resulting pixels.
  private func raster(
    _ json: String,
    drawText: ((DrawTextRun, Brush?, CGContext) -> Void)? = nil
  ) throws -> Raster {
    let compiler = SpecCompiler(
      textEngine: MetricTextEngine(advanceRatio: 0.6, ascentRatio: 0.8, descentRatio: 0.2),
      loader: DenyLoader(),
      randomSeed: 42,
      clock: ClockCompanion.shared.Fixed,
      locale: VegaLocale.Companion.shared.EnglishUS,
      hostConfig: nil,
      containerSize: nil,
      timeZone: nil
    )
    let compiled = compiler.compileJson(json: json, signalOverrides: [:], itemEncodes: [:])
    let scene = try XCTUnwrap(compiled.scene)

    // Named sRGB rather than "device" RGB, so what is asserted below is the renderer's colour and
    // not this machine's idea of what a device is.
    let space = try XCTUnwrap(CGColorSpace(name: CGColorSpace.sRGB))
    let context = try XCTUnwrap(
      CGContext(
        data: nil, width: width, height: height, bitsPerComponent: 8,
        bytesPerRow: width * 4, space: space,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
      )
    )
    // A scene's y grows downward and a bitmap context's grows upward, so a chart drawn into one
    // unflipped comes out upside down. The flip belongs to the caller — a view on iOS is already
    // flipped and would be inverted twice if the renderer did it — so the test does what a caller
    // must, and this is the line that documents the requirement.
    context.translateBy(x: 0, y: CGFloat(height))
    context.scaleBy(x: 1, y: -1)

    var target = CoreGraphicsTarget(context: context, drawText: drawText)
    SceneWalk().draw(scene: scene, into: &target)

    // Copied out rather than held as a pointer: `context.data` belongs to the context, and the
    // context is a local here — reading it after this function returned dereferenced freed memory,
    // which crashed rather than failed. A copy of 20kB costs nothing and cannot dangle.
    let bytes = try XCTUnwrap(context.data)
    let pixels = [UInt8](
      UnsafeBufferPointer(
        start: bytes.assumingMemoryBound(to: UInt8.self), count: width * height * 4
      )
    )
    return Raster(pixels: pixels, width: width, height: height)
  }

  /// Reads pixels back in the scene's own coordinates, undoing the flip applied when drawing.
  private struct Raster {
    let pixels: [UInt8]
    let width: Int
    let height: Int

    /// The colour at a point in *scene* space, as bytes.
    ///
    /// No un-flipping here, which is worth saying because it looks like an omission. A bitmap
    /// context's user space has its origin at the bottom left while its buffer is stored top row
    /// first; the flip applied before drawing cancels against that, so buffer row `y` *is* scene row
    /// `y`. Undoing the flip a second time here read the chart upside down, and the first version of
    /// this test did exactly that.
    func at(_ x: Int, _ y: Int) -> (r: UInt8, g: UInt8, b: UInt8, a: UInt8) {
      let base = (y * width + x) * 4
      return (pixels[base], pixels[base + 1], pixels[base + 2], pixels[base + 3])
    }

    func isNear(
      _ x: Int, _ y: Int, _ r: UInt8, _ g: UInt8, _ b: UInt8, tolerance: Int = 2
    ) -> Bool {
      let got = at(x, y)
      return abs(Int(got.r) - Int(r)) <= tolerance
        && abs(Int(got.g) - Int(g)) <= tolerance
        && abs(Int(got.b) - Int(b)) <= tolerance
    }

    /// How many pixels are dark, which is how a test asks "is there ink here" without asserting on
    /// glyph shapes — those belong to the platform's font and would change with it.
    var darkPixels: Int {
      var count = 0
      for y in 0..<height {
        for x in 0..<width where at(x, y).r < 128 {
          count += 1
        }
      }
      return count
    }

    func describe(_ x: Int, _ y: Int) -> String {
      let c = at(x, y)
      return "(\(x),\(y)) = rgba(\(c.r),\(c.g),\(c.b),\(c.a))"
    }
  }

  private static let bars = """
    {"$schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 100, "height": 50, "padding": 0,
     "background": "white",
     "data": [{"name": "t", "values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}]}],
     "scales": [
       {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
       {"name": "y", "domain": {"data": "t", "field": "v"}, "range": "height"}],
     "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
       "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
       "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
       "fill": {"value": "steelblue"}}}}]}
    """

  func testBarsLandWhereTheScaleSaysAndTheBackgroundFillsTheRest() throws {
    let image = try raster(Self.bars)
    // steelblue is rgb(70,130,180); the shorter bar occupies the lower half of the left column.
    XCTAssertTrue(image.isNear(10, 40, 70, 130, 180), "left bar: \(image.describe(10, 40))")
    XCTAssertTrue(image.isNear(10, 10, 255, 255, 255), "above it: \(image.describe(10, 10))")
    // The taller bar is twice the value, so its column is painted to the top.
    XCTAssertTrue(image.isNear(60, 10, 70, 130, 180), "right bar top: \(image.describe(60, 10))")
    XCTAssertTrue(image.isNear(60, 40, 70, 130, 180), "right bar foot: \(image.describe(60, 40))")
  }

  func testACircleIsRoundRatherThanItsBoundingBox() throws {
    let image = try raster(
      """
      {"$schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 100, "height": 50, "padding": 0,
       "background": "white",
       "data": [{"name": "t", "values": [{"x": 50, "y": 25}]}],
       "marks": [{"type": "symbol", "from": {"data": "t"}, "encode": {"enter": {
         "x": {"field": "x"}, "y": {"field": "y"}, "size": {"value": 1600},
         "shape": {"value": "circle"}, "fill": {"value": "black"}}}}]}
      """
    )
    XCTAssertTrue(image.isNear(50, 25, 0, 0, 0), "centre filled: \(image.describe(50, 25))")
    // A symbol's size is an *area*, so 1600 is a radius near 22.6 — which is why the first version
    // of this test sampled 15 across and 15 up and found ink there, correctly. The bounding box's
    // corner is 22.6 out along both axes, so 21 is comfortably outside the circle and inside the
    // box. Finding background there is the whole point: a renderer that read the engine's cubics as
    // a rectangle, or closed them wrongly, would have filled it.
    XCTAssertTrue(
      image.isNear(50 - 21, 25 - 21, 255, 255, 255),
      "bounding-box corner is background: \(image.describe(29, 4))"
    )
  }

  /// A group's opacity paints its own background and is **not** inherited by its children.
  ///
  /// That is upstream's behaviour in both of its renderers, which is worth recording because the
  /// opposite is the natural guess and this renderer originally guessed it: `vega-scenegraph`'s canvas
  /// group saves the graphics state, translates and clips, and never sets `globalAlpha` for the
  /// descent, while its SVG renderer emits `opacity` on the group's background `path` and leaves the
  /// child element bare. So a half-opaque group containing an opaque mark draws a solid mark on a
  /// washed-out panel — which is what this asserts, in pixels.
  /// A gradient runs across the mark it fills, in absolute coordinates the target never re-derives.
  ///
  /// CoreGraphics has no gradient *fill*: a gradient is drawn over a region, so the region has to be
  /// the clip. This is the test that the clip is the mark's path and not the whole surface — and that
  /// the stops land on the mark's own edges, since a specification writes them as fractions of it.
  func testAGradientRunsAcrossTheMarkItFills() throws {
    let image = try raster(
      """
      {"$schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 100, "height": 50, "padding": 0,
       "background": "white",
       "marks": [{"type": "rect", "encode": {"enter": {
         "x": {"value": 20}, "y": {"value": 10},
         "width": {"value": 60}, "height": {"value": 30},
         "fill": {"value": {"gradient": "linear", "stops": [
           {"offset": 0, "color": "red"}, {"offset": 1, "color": "blue"}]}}}}}]}
      """
    )
    // Red at the mark's left edge, blue at its right — x=20 and x=80, not the chart's 0 and 100.
    let left = image.at(22, 25)
    let right = image.at(78, 25)
    XCTAssertTrue(left.r > 200 && left.b < 60, "red end: \(image.describe(22, 25))")
    XCTAssertTrue(right.b > 200 && right.r < 60, "blue end: \(image.describe(78, 25))")
    // A gradient, not two halves: the middle is a mixture of the two.
    let middle = image.at(50, 25)
    XCTAssertTrue(
      middle.r > 60 && middle.r < 220 && middle.b > 60 && middle.b < 220,
      "the middle is mixed: \(image.describe(50, 25))"
    )
    // And it is clipped to the mark. Outside it the background survives, which is the assertion that
    // says the gradient was drawn through a clip rather than across the surface.
    XCTAssertTrue(image.isNear(5, 25, 255, 255, 255), "outside the mark: \(image.describe(5, 25))")
    XCTAssertTrue(image.isNear(50, 3, 255, 255, 255), "above the mark: \(image.describe(50, 3)))")
  }

  /// Text reaches the surface when a caller lends the renderer CoreText, and not before.
  ///
  /// The renderer draws no glyphs of its own — shaping is the platform's job — so this checks the seam
  /// in both positions: with `CoreTextDrawing.draw` there are dark pixels where the label is, and
  /// without it the same chart draws everything else and leaves the text out. A renderer that silently
  /// dropped text would otherwise look identical to one that was never given a way to draw it.
  func testTextIsDrawnWhenCoreTextIsLentAndOmittedWhenItIsNot() throws {
    let spec = """
      {"$schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 100, "height": 50, "padding": 0,
       "background": "white",
       "marks": [{"type": "text", "encode": {"enter": {
         "x": {"value": 10}, "y": {"value": 30},
         "text": {"value": "IIIIIIII"}, "fontSize": {"value": 28},
         "fill": {"value": "black"}}}}]}
      """
    let withText = try raster(spec, drawText: CoreTextDrawing.draw)
    let withoutText = try raster(spec)

    XCTAssertTrue(withText.darkPixels > 40, "glyphs were drawn: \(withText.darkPixels) dark pixels")
    XCTAssertEqual(withoutText.darkPixels, 0, "no glyphs without a text engine")
  }

  func testAGroupOpacityPaintsItsPanelAndNotItsChildren() throws {
    let image = try raster(
      """
      {"$schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 100, "height": 50, "padding": 0,
       "background": "white",
       "marks": [{"type": "group", "encode": {"enter": {
          "x": {"value": 0}, "y": {"value": 0},
          "width": {"value": 100}, "height": {"value": 50},
          "fill": {"value": "black"}, "opacity": {"value": 0.5}}},
        "marks": [{"type": "rect", "encode": {"enter": {
          "x": {"value": 10}, "y": {"value": 10},
          "width": {"value": 30}, "height": {"value": 30},
          "fill": {"value": "red"}}}}]}]}
      """
    )
    // The panel: black at half opacity over white is mid grey. A renderer that ignored the group's
    // own opacity would draw it solid black.
    let panel = image.at(80, 40)
    XCTAssertTrue(
      abs(Int(panel.r) - 128) <= 3 && abs(Int(panel.g) - 128) <= 3,
      "the group's own background is half-opaque: \(image.describe(80, 40))"
    )
    // The child: fully red. Inheriting the group's opacity would have blended it toward the panel,
    // which is exactly the bug the earlier version of this renderer had.
    XCTAssertTrue(image.isNear(25, 25, 255, 0, 0), "the child is opaque: \(image.describe(25, 25))")
  }
}
#endif
