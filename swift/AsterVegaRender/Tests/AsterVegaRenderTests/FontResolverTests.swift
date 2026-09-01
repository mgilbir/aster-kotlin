import AsterVega
import CoreText
import XCTest

@testable import AsterVegaRender

/// A host's own face reaches a chart, and reaches **both** halves of it.
///
/// CoreText resolves a family name against the faces the process has registered, so an app bundling a
/// font could only reach a chart through `CTFontManagerRegisterGraphicsFont` — process-wide state, to
/// configure one chart, invisible at the call site. Both Kotlin renderers have taken a resolver for
/// exactly this since before 0.2.0. Reported as #106.
///
/// The assertion that matters is not "the resolver was called". It is that the face it returns is the
/// one the layout was **measured** with *and* the one the glyphs are **drawn** with. A seam wired into
/// one of those and not the other is worse than no seam: every label sits off the baseline of a box
/// reserved for a different font, and the chart looks subtly broken rather than obviously unwired.
final class FontResolverTests: XCTestCase {

  /// A face that is emphatically not the system font, so a width computed with it cannot coincide.
  private func courier(_ points: CGFloat = 0) -> CTFont {
    CTFontCreateWithName("Courier" as CFString, points, nil)
  }

  private func style(_ family: String, size: Double = 11, weight: Int = 400) -> TextStyle {
    TextStyle(
      fontFamily: family,
      fontSize: size,
      fontWeight: Int32(weight),
      fontStyle: FontStyle.normal,
      letterSpacing: 0,
      lineHeight: nil,
      locale: "und",
      direction: TextDirection.ltr
    )
  }

  func testAResolvedFaceIsWhatTheEngineMeasuresWith() {
    let resolving = CoreTextTextEngine(resolveFont: { _ in self.courier() })
    let plain = CoreTextTextEngine()

    let text = "Miles_per_Gallon"
    let resolved = resolving.advanceOf(line: text, style: style("Whatever"))
    let byHand = CTLineGetTypographicBounds(
      CTLineCreateWithAttributedString(
        NSAttributedString(
          string: text,
          attributes: [
            NSAttributedString.Key(kCTFontAttributeName as String): courier(11)
          ])),
      nil, nil, nil)

    XCTAssertEqual(resolved, byHand, accuracy: 0.001, "the host's face should be what was measured")
    XCTAssertNotEqual(
      resolved, plain.advanceOf(line: text, style: style("Whatever")), accuracy: 0.001,
      "and it should differ from the face CoreText would have picked, or this proves nothing")
  }

  func testTheSizeAndTraitsBelongToTheChartAndNotToTheHost() {
    // The host hands over a *face*. It may be at any size — the natural thing to write is
    // `CTFontCreateWithName(name, 0, nil)` — and the chart's own size and weight are applied here.
    let engine = CoreTextTextEngine(resolveFont: { _ in self.courier() })
    let bold = style("Whatever", size: 30, weight: 700)

    let font = CoreTextFonts.font(
      family: "Whatever", size: 30, weight: 700, italic: false,
      resolveFont: { _ in self.courier() })

    XCTAssertEqual(CTFontGetSize(font), 30, accuracy: 0.001, "the chart's size, not the host's")
    XCTAssertTrue(
      CTFontGetSymbolicTraits(font).contains(.traitBold), "the specification asked for bold")
    XCTAssertGreaterThan(engine.advanceOf(line: "W", style: bold), 0)
  }

  func testEveryNameInTheStackIsOffered() {
    // This asserted the opposite until #123: a generic was **not** offered to the host, on the
    // grounds that answering one would draw differently here than on the Kotlin renderers. That was
    // wrong on the facts — the Compose Multiplatform registry is consulted before its generic
    // mapping, so a host registering `sans-serif` was answered there and not here, which is the
    // divergence the reasoning claimed to prevent.
    //
    // Every name is offered now, in order, generics included: a host that registers one has said
    // what its sans is.
    var asked: [String] = []
    let font = CoreTextFonts.font(
      family: "Noto Sans, sans-serif, Chart Sans", size: 11, weight: 400, italic: false,
      resolveFont: { name in
        asked.append(name)
        return name == "Chart Sans" ? self.courier() : nil
      })

    XCTAssertEqual(
      ["Noto Sans", "sans-serif", "Chart Sans"], asked,
      "the whole stack, in order, until something answers")
    XCTAssertEqual(
      CTFontCopyFamilyName(font) as String, "Courier",
      "the face the host answered with, from the third entry")
  }

  func testTheStackStopsAtTheFirstAnswer() {
    // A host is not asked for names after one it has answered.
    var asked: [String] = []
    _ = CoreTextFonts.font(
      family: "Chart Sans, Noto Sans", size: 11, weight: 400, italic: false,
      resolveFont: { name in
        asked.append(name)
        return self.courier()
      })
    XCTAssertEqual(["Chart Sans"], asked)
  }

  func testANilAnswerFallsBackToCoreText() {
    let resolved = CoreTextFonts.font(
      family: "Helvetica", size: 11, weight: 400, italic: false, resolveFont: { _ in nil })
    let plain = CoreTextFonts.font(family: "Helvetica", size: 11, weight: 400, italic: false)

    XCTAssertEqual(
      CTFontCopyFamilyName(resolved) as String, CTFontCopyFamilyName(plain) as String,
      "a name the host does not recognise should resolve as it always did")
  }

  @MainActor
  func testTheSessionHandsItsResolverToWhateverDraws() {
    // The wiring that keeps measuring and drawing in step. A host configures the session once; the
    // view reads the resolver back off it rather than asking for the closure a second time, because a
    // seam that has to be wired twice is a seam that will be wired once.
    let session = ChartSession(resolveFont: { _ in self.courier() })
    XCTAssertNotNil(session.resolveFont, "the session should carry it for the drawing to read")

    let font = CoreTextFonts.font(
      family: "Whatever", size: 11, weight: 400, italic: false, resolveFont: session.resolveFont)
    XCTAssertEqual(CTFontCopyFamilyName(font) as String, "Courier")
  }

  @MainActor
  func testAHostsOwnEngineKeepsItsOwnResolver() {
    // A host that built its own engine has said what it measures with. Guessing a resolver for the
    // drawing would be painting faces the boxes were not measured for.
    let session = ChartSession(textEngine: CoreTextTextEngine(resolveFont: { _ in self.courier() }))
    XCTAssertNotNil(session.resolveFont, "read back off the engine it was given")
  }

  func testAFamilyNothingCouldAnswerIsRecorded() {
    // CoreText answers an unknown family with the system font: legible, wrong, and silent. Two of
    // the three renderers fell back that way and only the Compose one said so, which is part of why
    // they disagreed about reading a stack for as long as they did (#123).
    let engine = CoreTextTextEngine()
    _ = engine.advanceOf(line: "M", style: style("Definitely Not Installed"))

    XCTAssertEqual(["Definitely Not Installed"], engine.unresolvedFontFamilies)
  }

  func testAStackThatEndsInAGenericIsNotAMiss() {
    // A well-formed stack asks for the reader's default as its last resort and gets it. Recording
    // that as unresolved would make the set noise, and a set nobody can act on gets ignored.
    let engine = CoreTextTextEngine()
    _ = engine.advanceOf(line: "M", style: style("sans-serif"))

    XCTAssertEqual([], engine.unresolvedFontFamilies)
  }

  func testAFamilyTheHostAnsweredIsNotAMiss() {
    let engine = CoreTextTextEngine(resolveFont: { _ in self.courier() })
    _ = engine.advanceOf(line: "M", style: style("Chart Sans"))

    XCTAssertEqual([], engine.unresolvedFontFamilies, "the host answered, so nothing was missed")
  }

  // MARK: - Caching the host's answer (#152)

  /// A host's face is resolved once per style, not once per metric.
  ///
  /// The host branch used to return before the cache was ever read, so a host that supplied a
  /// resolver — which is what the documentation asks a host to do — rebuilt a
  /// `CTFontCreateCopyWithAttributes` copy for every advance, ascent, descent and line height, on
  /// every measured line, and again on every drawn run. Identity is the assertion because it is the
  /// one that cannot pass by coincidence: two equal fonts would satisfy `==`, only a cache hit
  /// returns the same object.
  ///
  /// A base size of 0 against a requested 15 makes the resize real, so a returned-unchanged face
  /// could not pass this by doing nothing.
  func testAHostsFaceIsResolvedOncePerStyle() {
    let face = courier()
    let first = CoreTextFonts.font(
      family: "Cached Sans", size: 15, weight: 400, italic: false, resolveFont: { _ in face })
    let second = CoreTextFonts.font(
      family: "Cached Sans", size: 15, weight: 400, italic: false, resolveFont: { _ in face })
    XCTAssertTrue(first === second, "a host's face should be styled once and reused")
    XCTAssertEqual(CTFontGetSize(first), 15, "and still resized to what the chart asked for")
  }

  /// Two hosts answering differently get different entries, which is why the key is the *face*.
  ///
  /// This is the hazard the code used to avoid by not caching at all: the cache is process-wide and
  /// a family name does not say which resolver answered, so an entry stored under `"Shared Name"`
  /// by one chart would have been served to another chart whose resolver answers differently. Keyed
  /// on the returned face, the two cannot collide — and they are asked for under *the same family
  /// name* here, because that is the collision being denied.
  func testTwoResolversAnsweringDifferentlyDoNotShareAnEntry() {
    let courierFont = CoreTextFonts.font(
      family: "Shared Name", size: 16, weight: 400, italic: false,
      resolveFont: { _ in self.courier() })
    let helvetica = CTFontCreateWithName("Helvetica" as CFString, 0, nil)
    let helveticaFont = CoreTextFonts.font(
      family: "Shared Name", size: 16, weight: 400, italic: false, resolveFont: { _ in helvetica })

    XCTAssertEqual(CTFontCopyFamilyName(courierFont) as String, "Courier")
    XCTAssertEqual(CTFontCopyFamilyName(helveticaFont) as String, "Helvetica")
  }

  /// The style is part of the key, so one face at two sizes is two entries rather than one wrong one.
  func testTheSameFaceAtTwoSizesIsTwoEntries() {
    let face = courier()
    let small = CoreTextFonts.font(
      family: "Sized Sans", size: 9, weight: 400, italic: false, resolveFont: { _ in face })
    let large = CoreTextFonts.font(
      family: "Sized Sans", size: 21, weight: 400, italic: false, resolveFont: { _ in face })
    XCTAssertEqual(CTFontGetSize(small), 9)
    XCTAssertEqual(CTFontGetSize(large), 21)
    XCTAssertFalse(small === large)
  }
}
