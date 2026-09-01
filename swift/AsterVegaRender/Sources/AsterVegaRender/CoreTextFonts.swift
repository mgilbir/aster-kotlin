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

    // **The host's answer is cached too, keyed on the face it returned.**
    //
    // It used to return here uncached, because the cache is process-wide and two charts in one app
    // may hand in different resolvers: caching one host's face under a *family name* would draw the
    // other chart with it. That hazard is real and the key was the cause of it. Keying on the face
    // the resolver actually returned removes it — two resolvers answering differently occupy
    // different entries, and two answering identically share one correctly.
    //
    // Without this, a host that supplies a resolver — which is what the documentation asks a host to
    // do — rebuilt a `CTFontCreateCopyWithAttributes` copy for every advance, ascent, descent and
    // line height, on every measured line, and again on every drawn run. `styled` is the cost, not
    // the resolver call: a resolver is expected to be a lookup in a dictionary the host already has,
    // which is what Android's `typefaceResolver` is. The Compose path never had this problem because
    // `rememberTextMeasurer` holds the measurement downstream of the resolver, so the same host
    // pattern cost a Compose host nothing and an Apple host a font copy per metric (#152).
    //
    // `CFEqual` and `CFHash` rather than the hash alone, so this is face *identity* and not a
    // collision risk: a wrong hit here would draw a chart in another chart's font.
    //
    // **Every name in the stack, in order.** This used to offer the resolver the first entry only,
    // and nothing at all when that entry was a generic — so a host that had registered `Chart Sans`
    // was never asked for it if the specification wrote `"Noto Sans, Chart Sans"`, and never asked at
    // all for `"sans-serif, Chart Sans"`. The Compose Multiplatform engine had always read the whole
    // stack, so one specification drew in different faces on different hosts (#123). `FontStack` is
    // that rule, shared, and it comes from the engine rather than being restated here.
    if let resolveFont {
      for name in families(of: family) {
        if let supplied = resolveFont(name) {
          let hostKey = HostKey(base: supplied, size: size, weight: weight, italic: italic)
          if let cached = hostCache.value(for: hostKey) { return cached }
          let font = styled(supplied, size: size, weight: weight, italic: italic)
          hostCache.store(font, for: hostKey)
          return font
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

  /// `FontStack.shared.families(stack:)`, memoised per family string.
  ///
  /// **This is where the time went.** `FontStack` is a Kotlin object reached across the Obj-C
  /// bridge, and the call was made on every font lookup — so a chart paid a bridge crossing and a
  /// `List<String>` conversion for every advance, ascent, descent and line height of every label.
  /// Measured at 2.77us of a 3.23us call: 86 per cent of the cost of resolving a font, for a
  /// function that is a comma split.
  ///
  /// Safe to memoise because it is *pure*: a string in, a list of names out, with no reference to
  /// what the process has registered or to what a host resolver would answer. That is the whole
  /// difference between this and the face cache below, which needs the resolver's answer in its key.
  ///
  /// Memoised rather than reimplemented in Swift, deliberately. The rule lives in the engine so that
  /// every renderer splits a family list identically — one specification drew in different faces on
  /// different hosts when it did not (#123). Paying the bridge once per distinct family keeps the
  /// single source of truth and drops the per-metric cost.
  private static func families(of stack: String) -> [String] {
    if let cached = stackCache.value(for: stack) { return cached }
    let names = FontStack.shared.families(stack: stack)
    stackCache.store(names, for: stack)
    return names
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
    for name in families(of: family) where !generic.contains(name.lowercased()) {
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

  /// A host-supplied face, plus the style the chart asked to see it in.
  ///
  /// The face rather than the family name it was found under, which is the whole point: the
  /// process-wide cache is shared by every chart in the app, and a family name does not say which
  /// resolver answered. `CTFont` is a `CFType`, so `CFEqual` is the exact question — two resolvers
  /// returning the same face share an entry, two returning different faces do not, and neither
  /// depends on the name either of them was asked for.
  ///
  /// [size] is here as well as inside the face because a host may hand back a face at any size and
  /// `styled` resizes it. Two charts asking the same face for 11pt and 14pt are two entries.
  private struct HostKey: Hashable {
    let base: CTFont
    let size: Double
    let weight: Int
    let italic: Bool

    static func == (left: HostKey, right: HostKey) -> Bool {
      left.size == right.size && left.weight == right.weight && left.italic == right.italic
        && CFEqual(left.base, right.base)
    }

    func hash(into hasher: inout Hasher) {
      hasher.combine(CFHash(base))
      hasher.combine(size)
      hasher.combine(weight)
      hasher.combine(italic)
    }
  }

  /// Fonts are resolved once per style rather than once per label.
  ///
  /// A chart measures every tick label, every legend entry and every title, and descriptor matching is
  /// the expensive part of that — the same handful of styles over and over. A lock rather than an actor
  /// because measurement is called synchronously from Kotlin and cannot await.
  private static let cache = Cache<Key, CTFont>()

  /// The same, for faces a host's resolver returned, keyed on the face rather than on a family name.
  ///
  /// A second cache and not a second kind of entry in the first, because the two keys answer
  /// different questions — one is "what did CoreText match this family to", the other is "what did
  /// this face, resized, come out as". Sharing a bound between them would let a chart with a
  /// resolver evict the entries of one without a resolver, and neither cache is large.
  private static let hostCache = Cache<HostKey, CTFont>()

  /// The family-stack parse, memoised. See `families(of:)` for why this one is safe to share.
  private static let stackCache = Cache<String, [String]>()

  /// `@unchecked Sendable` because every access is behind the lock; the assertion is made once, here.
  ///
  /// **Bounded**, and that needs saying because this is a `static` cache and therefore lives for the
  /// life of the process. The key is a family, a size, a weight and a slant — and a *size*, which is
  /// the part that made it unbounded in practice: a chart whose label size comes from a signal, or a
  /// legend whose swatches step through sizes, mints an entry per distinct size for as long as the
  /// app runs. Least-recently-used, so the working set of a chart being redrawn stays resident.
  private final class Cache<K: Hashable, V>: @unchecked Sendable {
    /// Enough for every face, weight and size a handful of charts asks for at once.
    ///
    /// An instance property rather than a `static` one because this type is generic now, and Swift
    /// has no static storage in a generic type. Each cache carrying its own bound is the shape the
    /// two of them want anyway.
    private let limit = 256

    /// A cached value and when it was last wanted, for the eviction below.
    private struct Entry {
      let value: V
      var used: UInt64
    }

    private var fonts: [K: Entry] = [:]
    /// Monotonic, so "least recently used" is a number to compare rather than a list to reorder.
    private var clock: UInt64 = 0
    private let lock = NSLock()

    /// **A counter, not an ordered list of keys**, and the difference is measurable.
    ///
    /// This used to hold the keys in LRU order and do `order.removeAll { $0 == key }` on every
    /// *hit* — a linear scan of up to `limit` keys, each one an `==`, on the path a chart takes for
    /// every advance, ascent, descent and line height of every label. For `HostKey` each of those
    /// comparisons is a `CFEqual` on a font. Stamping a counter instead makes a hit a dictionary
    /// read and an integer write, and moves the only scan to eviction, which happens once per new
    /// style rather than once per metric.
    func value(for key: K) -> V? {
      lock.lock()
      defer { lock.unlock() }
      guard let held = fonts[key]?.value else { return nil }
      clock += 1
      fonts[key]?.used = clock
      return held
    }

    func store(_ value: V, for key: K) {
      lock.lock()
      defer { lock.unlock() }
      clock += 1
      fonts[key] = Entry(value: value, used: clock)
      // One entry in, at most one out, so the scan is over `limit` and not unbounded. `min` rather
      // than a sorted structure because it runs on a miss and a miss already built a font.
      guard fonts.count > limit else { return }
      if let oldest = fonts.min(by: { $0.value.used < $1.value.used })?.key {
        fonts.removeValue(forKey: oldest)
      }
    }
  }
}
#endif
