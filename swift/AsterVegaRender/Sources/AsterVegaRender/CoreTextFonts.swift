#if canImport(CoreText)
import AsterVega
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
  ///   - resolveFont: the host's own answer for a family name, tried **before** CoreText. Return nil
  ///     for a name the host does not recognise and the descriptor path below runs as it always has.
  ///     The face may be at any size: it is resized to `size` here and given the weight and slant the
  ///     specification asked for, so a host can hand back
  ///     `CTFontCreateWithName("Whatever" as CFString, 0, nil)` without thinking about it.
  static func font(
    family: String,
    size: Double,
    weight: Int,
    italic: Bool,
    resolveFont: ((String) -> CTFont?)? = nil
  ) -> CTFont {
    let key = Key(family: family, size: size, weight: weight, italic: italic)

    // **Only the CoreText answer is cached, and the host's is not.** The cache is process-wide, and
    // two charts in one app may hand in different resolvers — caching one host's face under a family
    // name would draw the other chart with it. A resolver is expected to be a lookup in a dictionary
    // the host already has, which is what Android's `typefaceResolver` is; a host doing something
    // expensive there should memoise, as it would for `resolveImage`.
    // **Every name in the stack, in order.** This used to offer the resolver the first entry only,
    // and nothing at all when that entry was a generic — so a host that had registered `Chart Sans`
    // was never asked for it if the specification wrote `"Noto Sans, Chart Sans"`, and never asked at
    // all for `"sans-serif, Chart Sans"`. The Compose Multiplatform engine had always read the whole
    // stack, so one specification drew in different faces on different hosts (#123). `FontStack` is
    // that rule, shared, and it comes from the engine rather than being restated here.
    if let resolveFont {
      for name in FontStack.shared.families(stack: family) {
        if let supplied = resolveFont(name) {
          return styled(supplied, size: size, weight: weight, italic: italic)
        }
      }
    }

    if let cached = cache.value(for: key) { return cached }

    // A specification names a family as a string and whether that face is installed is the device's
    // business. A name that resolves is used; one that does not falls back to the system font at the
    // right size and traits, which is legible rather than absent.
    let points = CGFloat(size)
    var attributes: [CFString: Any] = [kCTFontSizeAttribute: points]

    if let first = firstFamily(of: family) {
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

  /// The first name CoreText should be asked for, or nil where the stack names no installed face.
  ///
  /// A generic — `sans-serif`, `monospace` — answers nil here, because it names nothing for a
  /// *descriptor* to look up and the system font is the honest answer.
  ///
  /// It used to gate the **host resolver** too, on the grounds that answering a generic would draw
  /// differently here than on the Kotlin renderers. That was wrong on the facts: the Compose
  /// Multiplatform registry is consulted before its generic mapping, so a host registering
  /// `sans-serif` was answered there and not here. A host that registers a generic has said what its
  /// sans is, and every renderer now takes it — see `FontStack`.
  private static func firstFamily(of family: String) -> String? {
    for name in FontStack.shared.families(stack: family) where !generic.contains(name.lowercased()) {
      return name
    }
    return nil
  }

  /// A face the host handed over, at the size and with the traits the specification asked for.
  ///
  /// The host supplies a *face*; the size and the weight belong to the chart. Applying the traits to
  /// the font rather than asking for them in a descriptor is the same care the path below takes, and
  /// for the same reason recorded there: a descriptor carrying a bold trait is free to answer with an
  /// unrelated family.
  private static func styled(_ base: CTFont, size: Double, weight: Int, italic: Bool) -> CTFont {
    let points = CGFloat(size)
    let resized = CTFontCreateCopyWithAttributes(base, points, nil, nil)
    var symbolic: CTFontSymbolicTraits = []
    if weight >= 600 { symbolic.insert(.traitBold) }
    if italic { symbolic.insert(.traitItalic) }
    guard !symbolic.isEmpty else { return resized }
    return CTFontCreateCopyWithSymbolicTraits(resized, points, nil, symbolic, symbolic) ?? resized
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
  ///
  /// **Bounded**, and that needs saying because this is a `static` cache and therefore lives for the
  /// life of the process. The key is a family, a size, a weight and a slant — and a *size*, which is
  /// the part that made it unbounded in practice: a chart whose label size comes from a signal, or a
  /// legend whose swatches step through sizes, mints an entry per distinct size for as long as the
  /// app runs. Least-recently-used, so the working set of a chart being redrawn stays resident.
  private final class Cache: @unchecked Sendable {
    /// Enough for every face, weight and size a handful of charts asks for at once.
    private static let limit = 256

    private var fonts: [Key: CTFont] = [:]
    /// Keys in least-recently-used order, oldest first.
    private var order: [Key] = []
    private let lock = NSLock()

    func value(for key: Key) -> CTFont? {
      lock.lock()
      defer { lock.unlock() }
      guard let font = fonts[key] else { return nil }
      order.removeAll { $0 == key }
      order.append(key)
      return font
    }

    func store(_ font: CTFont, for key: Key) {
      lock.lock()
      defer { lock.unlock() }
      fonts[key] = font
      order.removeAll { $0 == key }
      order.append(key)
      while order.count > Self.limit {
        fonts.removeValue(forKey: order.removeFirst())
      }
    }
  }
}
#endif
