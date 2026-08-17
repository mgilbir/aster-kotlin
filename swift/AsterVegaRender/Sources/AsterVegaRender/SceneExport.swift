#if canImport(CoreGraphics)
import AsterVega
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Exports a chart to a file: SVG, PNG or PDF.
///
/// The three come from two places, and the split is the point.
///
/// **SVG is the engine's**, through `vega-svg`'s `toSvg` — the same serializer the differential harness
/// compares against upstream, so an exported file is markup this project has verified rather than a second
/// opinion written in Swift. It was unreachable from here only because `vega-svg` was not on the
/// framework's export list; nothing about it was Android-specific.
///
/// **PNG and PDF are the platform's.** They are the drawn chart, so they go through ``SceneWalk`` and
/// ``CoreGraphicsTarget`` — the same code that puts pixels on screen, which is what makes an export look
/// like what the reader saw. A PDF is drawn rather than rasterised, so text stays text and a curve stays a
/// curve at any zoom.
public enum SceneExport {

  /// The SVG for `scene`, from the engine's own serializer.
  ///
  /// Text is measured with whatever engine compiled the scene, so pass the same one a chart was laid out
  /// with or the markup will place labels differently from the screen.
  public static func svg(_ scene: AsterVega.Scene, options: SvgOptions? = nil) -> String {
    scene.toSvg(options: options ?? defaultSvgOptions)
  }

  /// `SvgOptions` with the engine's own defaults, because Kotlin's do not cross the boundary.
  ///
  /// The precision is **read from the engine** rather than copied: it is `DEFAULT_DECIMAL_PRECISION`, and
  /// writing the number here by hand got it wrong on the first attempt — 3 instead of 6, which would have
  /// silently exported coarser markup than every other host produces. A constant that exists on both sides
  /// of a boundary should only exist on one.
  public static var defaultSvgOptions: SvgOptions {
    SvgOptions(
      precision: CanonicalNumberKt.DEFAULT_DECIMAL_PRECISION,
      pretty: true,
      idPrefix: "v",
      imagePolicy: SvgImagePolicy.reference,
      includeMetadata: true,
      includeAccessibility: true
    )
  }

  /// A PNG of `scene` at `scale` device pixels per scene unit.
  ///
  /// - Parameter drawText: how to draw glyphs, as ``CoreGraphicsTarget`` requires. Defaults to CoreText,
  ///   because an exported chart with no labels is rarely what a caller wanted — the on-screen default is
  ///   nil for a different reason, that a renderer should not assume a text stack exists.
  public static func png(
    _ scene: AsterVega.Scene,
    scale: Double = 2,
    drawText: ((DrawTextRun, Brush?, CGContext) -> Void)? = CoreTextDrawing.draw,
    resolveImage: ((String) -> CGImage?)? = nil
  ) -> Data? {
    guard let image = bitmap(scene, scale: scale, drawText: drawText, resolveImage: resolveImage),
      let data = CFDataCreateMutable(nil, 0),
      let destination = CGImageDestinationCreateWithData(data, UTType.png.identifier as CFString, 1, nil)
    else { return nil }

    CGImageDestinationAddImage(destination, image, nil)
    guard CGImageDestinationFinalize(destination) else { return nil }
    return data as Data
  }

  /// A one-page PDF of `scene`, drawn at its own size.
  ///
  /// Vector rather than a rasterised image: the walk hands CoreGraphics paths and text, and a PDF context
  /// keeps them, so the result scales without softening. That is the whole reason to offer PDF beside PNG.
  public static func pdf(
    _ scene: AsterVega.Scene,
    drawText: ((DrawTextRun, Brush?, CGContext) -> Void)? = CoreTextDrawing.draw,
    resolveImage: ((String) -> CGImage?)? = nil
  ) -> Data? {
    let box = CGRect(x: 0, y: 0, width: scene.width, height: scene.height)
    guard box.width > 0, box.height > 0,
      let data = CFDataCreateMutable(nil, 0),
      let consumer = CGDataConsumer(data: data)
    else { return nil }

    var mediaBox = box
    guard let context = CGContext(consumer: consumer, mediaBox: &mediaBox, nil) else { return nil }

    context.beginPDFPage(nil)
    draw(scene, into: context, height: box.height, scale: 1, drawText: drawText, resolveImage: resolveImage)
    context.endPDFPage()
    context.closePDF()
    return data as Data
  }

  /// A `CGImage` of `scene`, for a caller that wants pixels rather than a file.
  public static func bitmap(
    _ scene: AsterVega.Scene,
    scale: Double = 2,
    drawText: ((DrawTextRun, Brush?, CGContext) -> Void)? = CoreTextDrawing.draw,
    resolveImage: ((String) -> CGImage?)? = nil
  ) -> CGImage? {
    let width = Int((scene.width * scale).rounded())
    let height = Int((scene.height * scale).rounded())
    guard width > 0, height > 0,
      let space = CGColorSpace(name: CGColorSpace.sRGB),
      let context = CGContext(
        data: nil,
        width: width,
        height: height,
        bitsPerComponent: 8,
        bytesPerRow: 0,
        space: space,
        bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
      )
    else { return nil }

    draw(
      scene, into: context, height: Double(height), scale: scale,
      drawText: drawText, resolveImage: resolveImage
    )
    return context.makeImage()
  }

  /// The one place the scene is drawn, so a PNG and a PDF cannot drift apart.
  private static func draw(
    _ scene: AsterVega.Scene,
    into context: CGContext,
    height: Double,
    scale: Double,
    drawText: ((DrawTextRun, Brush?, CGContext) -> Void)?,
    resolveImage: ((String) -> CGImage?)?
  ) {
    context.saveGState()
    // A scene's y grows downward and a CoreGraphics context's grows up, so the flip belongs here — the
    // same asymmetry a bitmap test has to handle, and the reason the renderer itself flips nothing.
    context.translateBy(x: 0, y: CGFloat(height))
    context.scaleBy(x: CGFloat(scale), y: CGFloat(-scale))
    var target = CoreGraphicsTarget(
      context: context, drawText: drawText, resolveImage: resolveImage
    )
    SceneWalk().draw(scene: scene, into: &target)
    context.restoreGState()
  }
}
#endif
