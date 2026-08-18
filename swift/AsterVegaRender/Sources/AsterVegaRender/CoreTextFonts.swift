#if canImport(CoreText)
import CoreText
import Foundation

/// Turns a specification's font description into a `CTFont`.
///
/// One place, because two things need the answer and they must agree: `CoreTextTextEngine` measures with
/// it and `CoreTextDrawing` draws with it. A label sits where the layout put it, so measuring with one
/// font and drawing with another is how a chart gets labels that overhang their own boxes — which is
/// exactly the bug this file was extracted to fix.
enum CoreTextFonts {

  /// - Parameters:
  ///   - family: the CSS font family list from the specification. The first name is tried.
  ///   - weight: a CSS weight, 100–900; 600 and over asks for bold.
  static func font(family: String, size: Double, weight: Int, italic: Bool) -> CTFont {
    let key = Key(family: family, size: size, weight: weight, italic: italic)
    if let cached = cache.value(for: key) { return cached }

    // A specification names a family as a string and whether that face is installed is the device's
    // business. A name that resolves is used; one that does not falls back to the system font at the
    // right size and traits, which is legible rather than absent.
    let points = CGFloat(size)
    var attributes: [CFString: Any] = [kCTFontSizeAttribute: points]

    let first = family.split(separator: ",").first.map {
      $0.trimmingCharacters(in: CharacterSet(charactersIn: " '\""))
    }
    if let first, !first.isEmpty, !Self.generic.contains(first.lowercased()) {
      attributes[kCTFontFamilyNameAttribute] = first
    }

    var symbolic: CTFontSymbolicTraits = []
    if weight >= 600 { symbolic.insert(.traitBold) }
    if italic { symbolic.insert(.traitItalic) }

    // The traits are applied to a font, not asked for in the descriptor, and the difference is not
    // cosmetic. A descriptor carrying a bold trait and *no* family name — which is every generic
    // family, since `sans-serif` names no installed face — does not mean "the system font, bold". It
    // means "some font that is bold", and CoreText is free to answer with an unrelated family. On one
    // machine that is a bold system face; on GitHub's runner it was a face **narrower than the
    // regular one**, which is how a bold label came out shorter than its plain equivalent.
    //
    // Anchoring the family first and asking that font for its bold variant keeps the answer in the
    // family that was asked for. A family with no bold face on the device keeps the regular one,
    // which is the same "legible rather than absent" bargain as an unresolvable family name.
    let base: CTFont =
      if attributes.count > 1 {
        CTFontCreateWithFontDescriptor(
          CTFontDescriptorCreateWithAttributes(attributes as CFDictionary), points, nil)
      } else {
        CTFontCreateUIFontForLanguage(.system, points, nil)
          ?? CTFontCreateWithFontDescriptor(
            CTFontDescriptorCreateWithAttributes(attributes as CFDictionary), points, nil)
      }

    let font =
      symbolic.isEmpty
        ? base
        : CTFontCreateCopyWithSymbolicTraits(base, points, nil, symbolic, symbolic) ?? base
    cache.store(font, for: key)
    return font
  }

  /// CSS generic families, which name no installed face — the system font is the honest answer.
  private static let generic: Set<String> = [
    "sans-serif", "serif", "monospace", "cursive", "fantasy", "system-ui",
  ]

  private struct Key: Hashable {
    let family: String
    let size: Double
    let weight: Int
    let italic: Bool
  }

  /// Fonts are resolved once per style rather than once per label.
  ///
  /// A chart measures every tick label, every legend entry and every title, and descriptor matching is
  /// the expensive part of that — the same handful of styles over and over. A lock rather than an actor
  /// because measurement is called synchronously from Kotlin and cannot await.
  private static let cache = Cache()

  /// `@unchecked Sendable` because every access is behind the lock; the assertion is made once, here.
  private final class Cache: @unchecked Sendable {
    private var fonts: [Key: CTFont] = [:]
    private let lock = NSLock()

    func value(for key: Key) -> CTFont? {
      lock.lock()
      defer { lock.unlock() }
      return fonts[key]
    }

    func store(_ font: CTFont, for key: Key) {
      lock.lock()
      defer { lock.unlock() }
      fonts[key] = font
    }
  }
}
#endif
