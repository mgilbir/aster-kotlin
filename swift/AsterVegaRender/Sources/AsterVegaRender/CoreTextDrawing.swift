#if canImport(CoreText)
import CoreGraphics
import CoreText
import Foundation

/// Draws a scene's text with CoreText, ready to hand to ``CoreGraphicsTarget``.
///
/// ``CoreGraphicsTarget`` draws no glyphs itself and takes a closure instead, because shaping text is
/// the platform's job and a renderer that guessed at it would draw the wrong thing rather than
/// nothing. This is that closure for Apple platforms:
///
/// ```swift
/// var target = CoreGraphicsTarget(context: context, drawText: CoreTextDrawing.draw)
/// ```
///
/// The engine has already decided *where* the run goes and how it is aligned — `x` and `y` are the
/// anchor it computed with its own metrics — so this positions from that anchor and does not re-align.
/// It does mean a device font wider than the metrics the scene was laid out with will overhang its
/// measured box; laying a chart out with the device's own metrics is what `AndroidTextEngine` does on
/// the other platform, and the same seam exists here through `SpecCompiler(textEngine:)`.
public enum CoreTextDrawing {

  /// Draws one run. Unlabelled, so it can be passed straight in as `drawText:`.
  ///
  /// A function rather than a stored closure because Swift 6 will not have a non-`Sendable` closure as
  /// a static property, and a function reference is both concurrency-safe and shorter at the call site.
  public static func draw(_ run: DrawTextRun, _ fill: Brush?, _ context: CGContext) {
    guard !run.text.isEmpty else { return }

    let colour: CGColor
    if case .solid(let paint) = fill {
      colour = CGColor(
        colorSpace: sRGB,
        components: [
          CGFloat(paint.red), CGFloat(paint.green), CGFloat(paint.blue), CGFloat(paint.alpha),
        ]
      ) ?? CGColor(gray: 0, alpha: 1)
    } else {
      // A gradient-filled label is not something a specification can express through this engine's
      // scene, and black is a better answer than an invisible label.
      colour = CGColor(gray: 0, alpha: 1)
    }

    // The CoreText attribute names, not UIKit's `.font`/`.foregroundColor` — this file deliberately
    // imports neither UIKit nor AppKit so that it is the same code on iOS and on macOS.
    let attributed = NSAttributedString(
      string: run.text,
      attributes: [
        NSAttributedString.Key(kCTFontAttributeName as String): font(for: run),
        NSAttributedString.Key(kCTForegroundColorAttributeName as String): colour,
      ]
    )
    let line = CTLineCreateWithAttributedString(attributed)

    context.saveGState()
    // CoreText draws up from a baseline, and every coordinate this renderer produces is in a space
    // whose y grows *down* — the caller has already flipped the context to make that true. Flipping
    // back around the anchor is what keeps the glyphs upright inside it; without this the text is
    // drawn mirrored, which is the classic symptom and worth naming.
    context.translateBy(x: CGFloat(run.origin.x), y: CGFloat(run.origin.y))
    context.scaleBy(x: 1, y: -1)
    if run.angleDegrees != 0 {
      context.rotate(by: -CGFloat(run.angleDegrees) * .pi / 180)
    }
    context.textPosition = .zero
    CTLineDraw(line, context)
    context.restoreGState()
  }

  private static func font(for run: DrawTextRun) -> CTFont {
    // A specification names a font family as a string, and whether that face is installed is the
    // device's business. A name that resolves is used; one that does not falls back to the system
    // font at the right size and weight, which is legible rather than absent.
    let size = CGFloat(run.fontSize)
    var traits: [CFString: Any] = [:]
    if run.fontWeight >= 600 || run.italic {
      var symbolic: CTFontSymbolicTraits = []
      if run.fontWeight >= 600 { symbolic.insert(.traitBold) }
      if run.italic { symbolic.insert(.traitItalic) }
      traits[kCTFontSymbolicTrait] = symbolic.rawValue
    }

    var attributes: [CFString: Any] = [kCTFontSizeAttribute: size]
    let family = run.fontFamily.split(separator: ",").first.map {
      $0.trimmingCharacters(in: CharacterSet(charactersIn: " '\""))
    }
    if let family, !family.isEmpty, family.lowercased() != "sans-serif" {
      attributes[kCTFontFamilyNameAttribute] = family
    }
    if !traits.isEmpty {
      attributes[kCTFontTraitsAttribute] = traits as CFDictionary
    }

    let descriptor = CTFontDescriptorCreateWithAttributes(attributes as CFDictionary)
    return CTFontCreateWithFontDescriptor(descriptor, size, nil)
  }

  private static let sRGB = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
}
#endif
