import XCTest
import AsterVega
@testable import AsterVegaRender

/// Export: SVG, PNG and PDF.
///
/// Android has had all three since Milestone 2 and iOS had none, for a reason that turned out to be one
/// line of build configuration: `vega-svg` was not on the framework's export list, so the serializer this
/// project verifies against upstream was unreachable from Swift. Nothing about it was Android-specific.
///
/// The split matters and is asserted here. SVG comes from the **engine**, so an exported file is markup the
/// differential harness has compared against Vega. PNG and PDF are the **platform's**, drawn through the
/// same walk and target that put pixels on screen — which is what makes an export look like what the reader
/// saw rather than like a second renderer's opinion.
final class SceneExportTests: XCTestCase {

  private func scene(_ json: String) throws -> Scene {
    let compiled = SpecCompiler(
      textEngine: CoreTextTextEngine(),
      loader: DenyLoader(),
      randomSeed: 42,
      clock: ClockCompanion.shared.Fixed,
      // Spelled out because a Kotlin default argument has no Obj-C representation: Swift names every
      // parameter or does not compile. `EnglishUS` is what upstream produces.
      locale: VegaLocale.Companion.shared.EnglishUS,
      hostConfig: nil,
      containerSize: nil
    )
    .compileJson(json: json, signalOverrides: [:], itemEncodes: [:])
    return try XCTUnwrap(compiled.scene)
  }

  private let bars = """
    {"$schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 120, "height": 60, "padding": 5,
     "background": "white",
     "data": [{"name": "t", "values": [{"c": "a", "v": 30}, {"c": "b", "v": 80}]}],
     "scales": [
       {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
       {"name": "y", "domain": [0, 100], "range": "height"}],
     "axes": [{"orient": "left", "scale": "y", "tickCount": 2}],
     "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
       "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
       "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
       "fill": {"value": "steelblue"}}}}]}
    """

  // MARK: - SVG

  func testSvgIsTheEnginesOwnMarkup() throws {
    let svg = SceneExport.svg(try scene(bars))

    XCTAssertTrue(svg.hasPrefix("<svg"), String(svg.prefix(80)))
    XCTAssertTrue(svg.contains("</svg>"))
    // The marks, the axis and the background are all in it, which is what distinguishes a real export from
    // an empty document with the right envelope.
    XCTAssertTrue(svg.contains("#4682b4") || svg.lowercased().contains("steelblue"), "the bars")
    XCTAssertTrue(svg.contains("<text"), "the axis labels")
    XCTAssertTrue(svg.contains("viewBox"), "sized from the scene")
  }

  /// The default precision comes from the engine rather than from a number typed here.
  ///
  /// Worth its own test: the first version of `defaultSvgOptions` hard-coded 3 where the engine says 6, and
  /// nothing would have failed — every exported file would just have been coarser than the ones every other
  /// host produces.
  func testTheDefaultPrecisionIsTheEnginesOwn() {
    XCTAssertEqual(
      SceneExport.defaultSvgOptions.precision,
      CanonicalNumberKt.DEFAULT_DECIMAL_PRECISION
    )
    XCTAssertEqual(CanonicalNumberKt.DEFAULT_DECIMAL_PRECISION, 6)
  }

  func testSvgOptionsAreHonoured() throws {
    let compact = SvgOptions(
      precision: 1,
      pretty: false,
      idPrefix: "x",
      imagePolicy: SvgImagePolicy.reference,
      includeMetadata: false,
      includeAccessibility: false
    )
    let terse = SceneExport.svg(try scene(bars), options: compact)
    let pretty = SceneExport.svg(try scene(bars))

    XCTAssertLessThan(terse.count, pretty.count, "not pretty-printed and no metadata is smaller")
    XCTAssertFalse(terse.contains("aria-"), "accessibility was switched off")
  }

  // MARK: - PNG

  func testPngIsAFileWithTheChartInIt() throws {
    let data = try XCTUnwrap(SceneExport.png(try scene(bars), scale: 2))

    // A real PNG signature, not an empty buffer.
    XCTAssertEqual(Array(data.prefix(4)), [0x89, 0x50, 0x4E, 0x47], "PNG magic")
    XCTAssertGreaterThan(data.count, 200)

    // Decoded, it is the scene at twice its size. Derived from the scene rather than written out: an
    // axis widens a chart past its `width` plus padding — this one is 153.7 rather than the 130 those
    // numbers suggest — so a hard-coded expectation here would be asserting my arithmetic, not the export.
    let compiled = try scene(bars)
    let source = try XCTUnwrap(CGImageSourceCreateWithData(data as CFData, nil))
    let image = try XCTUnwrap(CGImageSourceCreateImageAtIndex(source, 0, nil))
    XCTAssertEqual(Double(image.width), (compiled.width * 2).rounded(), accuracy: 1)
    XCTAssertEqual(Double(image.height), (compiled.height * 2).rounded(), accuracy: 1)
  }

  func testPngScaleChangesThePixelsAndNotTheChart() throws {
    let small = try XCTUnwrap(SceneExport.bitmap(try scene(bars), scale: 1))
    let large = try XCTUnwrap(SceneExport.bitmap(try scene(bars), scale: 3))
    // Within a pixel, not exactly: each size is rounded from the scene's own fractional width, so three
    // times a rounded number is not the same as rounding three times the number.
    XCTAssertEqual(Double(large.width), Double(small.width) * 3, accuracy: 2)
    XCTAssertEqual(Double(large.height), Double(small.height) * 3, accuracy: 2)
  }

  /// The export draws the same chart the screen does, including its text.
  func testAnExportedChartHasItsLabels() throws {
    let withText = try XCTUnwrap(SceneExport.bitmap(try scene(bars), scale: 2))
    // Without a text stack, for comparison: the same chart minus its glyphs.
    let withoutText = try XCTUnwrap(SceneExport.bitmap(try scene(bars), scale: 2, drawText: nil))

    XCTAssertGreaterThan(
      darkPixels(withText), darkPixels(withoutText),
      "CoreText is the default for an export, because a chart with no labels is rarely what was wanted"
    )
  }

  // MARK: - PDF

  func testPdfIsAOnePageVectorDocument() throws {
    let data = try XCTUnwrap(SceneExport.pdf(try scene(bars)))

    XCTAssertEqual(String(decoding: data.prefix(5), as: UTF8.self), "%PDF-", "a PDF header")
    let document = try XCTUnwrap(CGPDFDocument(CGDataProvider(data: data as CFData)!))
    XCTAssertEqual(document.numberOfPages, 1)

    let page = try XCTUnwrap(document.page(at: 1))
    let box = page.getBoxRect(.mediaBox)
    // The scene's own size — read from the scene, because an axis makes a chart wider than its declared
    // `width` plus padding and the point of the assertion is the *match*, not the number.
    let compiled = try scene(bars)
    XCTAssertEqual(Double(box.width), compiled.width, accuracy: 0.5)
    XCTAssertEqual(Double(box.height), compiled.height, accuracy: 0.5)
  }

  /// A degenerate scene exports nothing rather than a zero-sized file or a crash.
  func testAnEmptySceneExportsNothing() throws {
    let empty = Scene.companion.empty(width: 0, height: 0)
    XCTAssertNil(SceneExport.png(empty))
    XCTAssertNil(SceneExport.pdf(empty))
    XCTAssertNil(SceneExport.bitmap(empty))
    // SVG is still a document, because an empty chart is still a chart.
    XCTAssertTrue(SceneExport.svg(empty).hasPrefix("<svg"))
  }

  private func darkPixels(_ image: CGImage) -> Int {
    guard let space = CGColorSpace(name: CGColorSpace.sRGB),
      let context = CGContext(
        data: nil, width: image.width, height: image.height, bitsPerComponent: 8,
        bytesPerRow: 0, space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
      )
    else { return 0 }
    context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
    guard let data = context.data else { return 0 }
    let bytes = data.assumingMemoryBound(to: UInt8.self)
    var count = 0
    for index in stride(from: 0, to: image.width * image.height * 4, by: 4) where bytes[index] < 100 {
      count += 1
    }
    return count
  }
}
