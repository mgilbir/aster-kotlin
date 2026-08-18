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

  public override init() {
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
      size: style.fontSize,
      weight: Int(style.fontWeight),
      italic: style.fontStyle == FontStyle.italic
    )
  }
}
#endif
