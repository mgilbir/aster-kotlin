import XCTest
import AsterVega
@testable import AsterVegaRender

/// The CoreText text engine: does the layout agree with what gets drawn?
///
/// That is the whole question. A label sits where the layout put it, so if the engine that measures and
/// the code that draws disagree about a string's width, a right-aligned axis label lands over the domain
/// line — which is exactly what the demo looked like before this engine existed.
final class CoreTextTextEngineTests: XCTestCase {

  private let engine = CoreTextTextEngine()
  private let reference = MetricTextEngine(advanceRatio: 0.6, ascentRatio: 0.8, descentRatio: 0.2)

  private func run(_ text: String, size: Double = 11, weight: Int = 400) -> TextRun {
    TextRun(
      text: text,
      style: TextStyle(
        fontFamily: "sans-serif",
        fontSize: size,
        fontWeight: Int32(weight),
        fontStyle: FontStyle.normal,
        letterSpacing: 0,
        lineHeight: nil,
        locale: "und",
        direction: TextDirection.ltr
      ),
      align: TextAlign.left,
      baseline: TextBaseline.alphabetic,
      limit: 0,
      ellipsis: "…",
      // Null, so the run splits on newlines. A specification naming a `lineBreak` splits on that
      // instead, which the shared layout handles and this engine never sees.
      lineBreak: nil,
      // Also null: an explicit line list is what a `text` channel whose value is an *array* produces, and
      // `displayLines` prefers it over splitting. Kotlin gives both of these a default; a default does not
      // cross the Obj-C boundary, so Swift has to name every parameter — which is why adding one to
      // `TextRun` breaks Swift callers that a Kotlin caller would never notice.
      lines: nil
    )
  }

  /// The measured width is the width CoreText will advance the pen by.
  ///
  /// Checked against `CTLine` directly rather than against a constant: a constant would be this
  /// machine's system font today, and the property that matters is the agreement, not the number.
  func testTheMeasuredWidthIsTheWidthCoreTextWillDraw() {
    for text in ["100", "Miles_per_Gallon", "8.0", "W", "iiii"] {
      let measured = engine.measure(text: run(text), constraint: nil).width

      let font = CoreTextFonts.font(family: "sans-serif", size: 11, weight: 400, italic: false)
      let attributed = NSAttributedString(
        string: text,
        attributes: [NSAttributedString.Key(kCTFontAttributeName as String): font]
      )
      let drawn = CTLineGetTypographicBounds(
        CTLineCreateWithAttributedString(attributed), nil, nil, nil
      )

      XCTAssertEqual(measured, drawn, accuracy: 0.001, "'\(text)' measures as it draws")
    }
  }

  /// A proportional font is not a fixed fraction of the font size, which is the defect in one line.
  func testAProportionalFontDisagreesWithTheRatioEngineAsItMust() {
    // The reference engine gives every character the same width, so `iiii` and `WWWW` measure the same.
    // A real font does not, and that difference is the whole reason this engine exists.
    let narrowRatio = reference.measure(text: run("iiii"), constraint: nil).width
    let wideRatio = reference.measure(text: run("WWWW"), constraint: nil).width
    XCTAssertEqual(narrowRatio, wideRatio, accuracy: 0.001, "the ratio engine cannot tell them apart")

    let narrow = engine.measure(text: run("iiii"), constraint: nil).width
    let wide = engine.measure(text: run("WWWW"), constraint: nil).width
    XCTAssertLessThan(narrow, wide, "CoreText knows an i from a W")
  }

  func testMetricsScaleWithTheFontAndHaveARealAscent() {
    let small = engine.measure(text: run("Hg", size: 10), constraint: nil)
    let large = engine.measure(text: run("Hg", size: 20), constraint: nil)

    XCTAssertGreaterThan(large.width, small.width)
    XCTAssertGreaterThan(large.ascent, small.ascent)
    // A descent, because `g` has one — and the reference engine's flat 20% of the size is not it.
    XCTAssertGreaterThan(small.descent, 0)
    XCTAssertGreaterThan(small.ascent, small.descent)
    // The line height is the font's, not `fontSize + 2`.
    XCTAssertGreaterThan(small.lineHeight, 0)
  }

  func testBoldIsWiderThanRegular() {
    let regular = engine.measure(text: run("Weight", weight: 400), constraint: nil).width
    let bold = engine.measure(text: run("Weight", weight: 700), constraint: nil).width
    XCTAssertGreaterThan(bold, regular, "a bold face is wider, and the layout has to know")
  }

  /// The shared layout still applies: this subclass supplies numbers, not behaviour.
  func testTheSharedLayoutStillDoesTheLayout() {
    let twoLines = engine.layout(text: run("first\nsecond"), constraint: nil)
    XCTAssertEqual(twoLines.metrics.lineCount, 2, "newlines still split")
    XCTAssertEqual(twoLines.lines.count, 2)
    XCTAssertEqual(twoLines.lines[0].baselineY, 0)
    XCTAssertEqual(
      twoLines.lines[1].baselineY, twoLines.metrics.lineHeight, accuracy: 0.001,
      "baselines are stacked by the line height"
    )
    // And the second line is measured with the same engine, not assumed to match the first.
    XCTAssertNotEqual(twoLines.lines[0].width, twoLines.lines[1].width)
  }

  /// A chart compiled with this engine reserves boxes the drawn labels fit inside.
  ///
  /// The end of the chain, and the reason for all of the above: an axis label's own bounds should hold
  /// the text CoreText draws for it. With the ratio engine these disagreed by a few points per label,
  /// which is what put the numbers on top of the axis line.
  func testAnAxisLabelsBoundsHoldTheTextThatIsDrawn() throws {
    let compiled = SpecCompiler(
      textEngine: CoreTextTextEngine(),
      loader: DenyLoader(),
      randomSeed: 42,
      clock: ClockCompanion.shared.Fixed,
      locale: VegaLocale.Companion.shared.EnglishUS,
      hostConfig: nil,
      containerSize: nil,
      timeZone: nil
    )
    .compileJson(
      json: """
        {"$schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 200, "height": 100, "padding": 5,
         "scales": [{"name": "y", "domain": [0, 100], "range": "height"}],
         "axes": [{"orient": "left", "scale": "y", "tickCount": 5}],
         "marks": []}
        """,
      signalOverrides: [:],
      itemEncodes: [:]
    )
    let scene = try XCTUnwrap(compiled.scene)

    var labels: [TextNode] = []
    func collect(_ node: any SceneNode) {
      if let text = node as? TextNode { labels.append(text) }
      if let group = node as? GroupNode { group.children.forEach(collect) }
    }
    collect(scene.root)
    XCTAssertFalse(labels.isEmpty, "an axis draws its labels")

    let engine = CoreTextTextEngine()
    for label in labels {
      let run = label.layout.run
      let width = engine.measure(text: run, constraint: nil).width
      // The node's own recorded width is what the layout reserved; the engine's is what will be drawn.
      XCTAssertEqual(
        label.layout.metrics.width, width, accuracy: 0.001,
        "'\(run.text)' was laid out with the metrics it will be drawn with"
      )
    }
  }
}
