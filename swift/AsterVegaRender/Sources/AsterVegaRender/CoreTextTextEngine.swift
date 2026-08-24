#if canImport(CoreText)
import AsterVega
import CoreText
import Foundation

/// Measures text with **CoreText**, so a chart's labels are laid out by the font that draws them.
///
/// This is the fix for a visible defect. The demo compiled with `MetricTextEngine`, whose advance widths
/// are a fixed fraction of the font size — stable across machines and matching no real font — and then
/// drew the glyphs with CoreText. So every reserved box was the wrong width: right-aligned axis labels
/// sat over the domain line, and a long label overhung the space the layout had kept for it. The
/// engine's own documentation says it plainly: *the same implementation must be used for measuring and
/// for drawing.*
///
/// It is a subclass rather than a reimplementation. `MeasuredTextEngine` owns the layout — newlines,
/// `limit` and its ellipsis, wrapping, baselines, bounds from the alignment — and asks only how wide a
/// string is in a style. So this file is three measurements and nothing else, which is the whole reason
/// a platform engine can be trusted to agree with the reference one about everything except the numbers
/// only the platform knows.
///
/// The font comes from ``CoreTextFonts``, which ``CoreTextDrawing`` also uses. Measuring with one font
/// and drawing with another is the bug in a different disguise.
///
/// Not thread-safe by any special effort and not needing to be: it holds no mutable state, and the font
/// cache underneath it takes a lock.
public final class CoreTextTextEngine: MeasuredTextEngine {

  /// What the reader's text-size setting multiplies every font size by; 1 is the size as written.
  ///
  /// Android has honoured its device's `fontScale` since the text engines were consolidated, and the
  /// Compose renderer honours it by measuring in `sp`. This side did not, so the same chart obeyed a
  /// reader's accessibility text size on two hosts out of three — and a chart that ignores it is not
  /// accessible, whatever its VoiceOver tree says.
  ///
  /// It has to be applied to **measuring and drawing alike**, or the labels are laid out for one size
  /// and painted at another. `CoreTextDrawing.draw(_:fill:in:textScale:)` takes the same number, and
  /// `VegaChartView` reads this property to pass it, so there is one source for both.
  ///
  /// Uncapped on purpose: a host that wants a ceiling passes one it chose, which is a decision about a
  /// chart's legibility rather than about text. See `ChartSession.textScale`.
  public let textScale: Double

  /// - Parameter textScale: the reader's text-size factor. 1, the default, is the size as written.
  /// The host's own answer for a font family, tried before CoreText.
  ///
  /// CoreText resolves a family name against the faces the *process* has registered, so an app that
  /// bundles a font — which most design systems do — could only reach a chart by calling
  /// `CTFontManagerRegisterGraphicsFont` and hoping the name matched. That works and is the wrong
  /// shape: it mutates process-wide state to configure one chart, it is invisible at the call site,
  /// and it left one specification and one host configuration drawing in different faces on Apple
  /// and on the two Kotlin renderers, both of which have taken a resolver for exactly this.
  /// Reported from outside as #106.
  ///
  /// **It must be the same resolver the drawing uses.** A label sits where the layout put it, so
  /// measuring with one face and painting with another is how a chart gets labels overhanging their
  /// own boxes — the defect `CoreTextFonts` was extracted to fix. `ChartSession` and `VegaChartView`
  /// take this and hand it to both sides; a host wiring them separately has to do the same.
  public let resolveFont: ((String) -> CTFont?)?

  public init(textScale: Double = 1, resolveFont: ((String) -> CTFont?)? = nil) {
    self.textScale = textScale > 0 ? textScale : 1
    self.resolveFont = resolveFont
    super.init()
  }

  public override func advanceOf(line: String, style: TextStyle) -> Double {
    guard !line.isEmpty else { return 0 }

    var attributes: [NSAttributedString.Key: Any] = [
      NSAttributedString.Key(kCTFontAttributeName as String): font(for: style)
    ]
    // CoreText's `kCTKernAttributeName` is spacing *between* characters, which is what a
    // specification's `letterSpacing` means — so the trailing gap a naive `count * spacing` would add
    // does not appear, and neither does the one the reference engine subtracts by hand.
    //
    // Set only when there is spacing to apply. Setting it to zero is not the same as leaving it out:
    // an explicit kern **replaces** the font's own kerning, so a zero switched pair kerning off and
    // measured every string slightly wider than CoreText would draw it. The drawing path sets no kern
    // attribute at all, so leaving it out here is what makes the two agree — and agreement is the one
    // property this engine exists for.
    if style.letterSpacing != 0 {
      attributes[NSAttributedString.Key(kCTKernAttributeName as String)] = style.letterSpacing
    }
    let attributed = NSAttributedString(string: line, attributes: attributes)
    // A typographic width, not a bounding box: this is the advance the drawing will use, so the two
    // agree by construction. `CTLineGetTypographicBounds` is the same number `CTLineDraw` walks.
    let ctLine = CTLineCreateWithAttributedString(attributed)
    return CTLineGetTypographicBounds(ctLine, nil, nil, nil)
  }

  public override func ascentOf(style: TextStyle) -> Double {
    Double(CTFontGetAscent(font(for: style)))
  }

  public override func descentOf(style: TextStyle) -> Double {
    Double(CTFontGetDescent(font(for: style)))
  }

  /// The font's own line height, rather than upstream's `fontSize + 2`.
  ///
  /// A platform engine is expected to prefer this — the base class says so — and it is the right answer
  /// for the same reason the advances are: leading is part of what the font says about itself, and a
  /// stack of lines drawn by CoreText sits where CoreText's metrics put it.
  public override func defaultLineHeightOf(style: TextStyle) -> Double {
    let resolved = font(for: style)
    let height =
      CTFontGetAscent(resolved) + CTFontGetDescent(resolved) + CTFontGetLeading(resolved)
    // A degenerate font size would otherwise collapse every line onto one baseline.
    return height > 0 ? Double(height) : style.fontSize + 2.0
  }

  private func font(for style: TextStyle) -> CTFont {
    CoreTextFonts.font(
      family: style.fontFamily,
      size: style.fontSize * textScale,
      weight: Int(style.fontWeight),
      italic: style.fontStyle == FontStyle.italic,
      resolveFont: resolveFont
    )
  }
}
#endif

#if canImport(SwiftUI)
import SwiftUI

@available(macOS 13.0, iOS 16.0, *)
extension DynamicTypeSize {

  /// The factor to build a `ChartSession` with, so a chart follows the reader's text-size setting.
  ///
  /// A **table** rather than `UIFontMetrics`, for two reasons. `UIFontMetrics` is UIKit, and this package
  /// draws on macOS as well; and a chart is not a paragraph — scaling it by the metrics of one text style
  /// would tie every label in it to whatever style was asked about. These are the ratios of Apple's own
  /// body sizes to the default 17pt, which is the same curve `UIFontMetrics.default` applies and is the
  /// one a reader recognises from the rest of the app.
  ///
  /// The accessibility sizes reach 3.1×, and nothing here caps them: a chart whose labels are three times
  /// the size is usually *not* the right answer, and choosing a ceiling is a decision about that chart —
  /// a host that wants one passes `min(typeSize.chartTextScale, 1.6)`. What is not a decision is ignoring
  /// the setting entirely, which is what this side did before.
  public var chartTextScale: Double {
    switch self {
    case .xSmall: return 14.0 / 17.0
    case .small: return 15.0 / 17.0
    case .medium: return 16.0 / 17.0
    case .large: return 1
    case .xLarge: return 19.0 / 17.0
    case .xxLarge: return 21.0 / 17.0
    case .xxxLarge: return 23.0 / 17.0
    case .accessibility1: return 28.0 / 17.0
    case .accessibility2: return 33.0 / 17.0
    case .accessibility3: return 40.0 / 17.0
    case .accessibility4: return 47.0 / 17.0
    case .accessibility5: return 53.0 / 17.0
    @unknown default: return 1
    }
  }
}
#endif
