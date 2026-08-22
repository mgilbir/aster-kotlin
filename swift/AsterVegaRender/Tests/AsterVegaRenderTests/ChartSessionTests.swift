import AsterVega
import XCTest

@testable import AsterVegaRender

/// The session a host drives, which used to live in the demo.
///
/// It moved because an adopter counted what owning it costs: `ChartSession` and `VegaChartView` are the
/// two pieces an app actually needs, and they are the two that took real bug fixes to get right. Moving
/// them into the package is only half the answer, though — the other half is that they are now *tested*
/// here, where the demo could only be exercised by launching it in a simulator.
///
/// What these cover is the part a screenshot cannot: that a compile runs off the main actor and can be
/// awaited, that a touch arriving mid-compile is queued rather than lost, that what a touch found is
/// reported as facts, and that a preset signal reaches the dataflow.
@available(macOS 14.0, iOS 17.0, *)
@MainActor
final class ChartSessionTests: XCTestCase {

  /// Two bars, and a signal a click sets — the same shape `TouchTests` uses, for the same reason.
  private let specification = """
    {"$schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 200, "height": 100, "padding": 0,
     "signals": [{"name": "picked", "value": null,
                  "on": [{"events": "rect:click", "update": "datum.c"}]}],
     "data": [{"name": "t", "values": [{"c": "a", "v": 20}, {"c": "b", "v": 30}]}],
     "scales": [
       {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
       {"name": "y", "domain": [0, 100], "range": "height"}],
     "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"update": {
       "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
       "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
       "tooltip": {"signal": "datum.c"}}}}]}
    """

  private func loaded() async -> ChartSession {
    let session = ChartSession()
    session.load(specification: specification)
    await session.settle()
    return session
  }

  func testCompilesAndPublishesAScene() async {
    let session = await loaded()

    XCTAssertNotNil(session.scene, "no scene: \(session.failure ?? "and no failure either")")
    XCTAssertNil(session.failure)
    XCTAssertFalse(session.loading, "settle() should mean the compile is done")
    XCTAssertEqual(session.grammar, .vega, "it is Vega, and the session says which it read")
  }

  func testVegaLiteIsRecognisedAndCompiled() async {
    let session = ChartSession()
    session.load(
      specification: """
        {"$schema": "https://vega.github.io/schema/vega-lite/v6.json",
         "data": {"values": [{"a": 1, "b": 2}]},
         "mark": "point",
         "encoding": {"x": {"field": "a", "type": "quantitative"},
                      "y": {"field": "b", "type": "quantitative"}}}
        """
    )
    await session.settle()

    XCTAssertEqual(session.grammar, .vegaLite)
    XCTAssertNotNil(session.scene, "a Vega-Lite specification should compile on this side too")
  }

  func testATapReportsWhatItFound() async {
    let session = await loaded()
    let scene = try! XCTUnwrap(session.scene)
    session.place(
      contentScale: 1,
      viewport: Rect(x: 0, y: 0, width: scene.width, height: scene.height)
    )

    // Inside the left bar: the band is the left half, and the bar occupies the bottom fifth.
    session.tap(at: Point(x: 50, y: 90))
    await session.settle()

    // The mark carries a `tooltip` channel, so that is what the touch found — as a fact rather than as
    // a sentence, because wording it is the host's business and not a library's.
    XCTAssertEqual(session.lastTouch, .tooltip("a"))
  }

  /// A tap on blank space finds nothing — which took a fix to the hit test to be true.
  ///
  /// It used to report one selected mark. The compiler wraps a specification's marks in a group whose
  /// bounds are the whole plotting area, and the hit test asked whether a point was *inside* a group
  /// rather than whether the group **paints** there. So a tap 75 units from the nearest bar selected the
  /// frame, and a host showed "1 mark selected" with nothing under the finger. Upstream's rule is that a
  /// group is picked only where it paints — a fill, or a stroke — and that is now the rule here.
  ///
  /// Written from Swift, where an adopter would meet it, and kept here for the same reason.
  func testATapOnEmptySpaceFindsNothing() async {
    let session = await loaded()
    let scene = try! XCTUnwrap(session.scene)
    session.place(
      contentScale: 1,
      viewport: Rect(x: 0, y: 0, width: scene.width, height: scene.height)
    )

    // Above both bars: the taller one starts 70 units below this point.
    session.tap(at: Point(x: 50, y: 5))
    await session.settle()

    XCTAssertEqual(session.lastTouch, .nothing(x: 50, y: 5))
    XCTAssertTrue(session.selectedNodeIds.isEmpty, "nothing was there, so nothing is selected")

    // And a tap that does land on a bar still selects: the frame was the only thing that changed.
    session.tap(at: Point(x: 50, y: 90))
    await session.settle()
    XCTAssertEqual(session.lastTouch, .tooltip("a"))
  }

  /// A tooltip as **lines**, which is what a host can put in a bubble.
  ///
  /// The value was always reported and every host had to work out what to do with it; this session's
  /// answer was to stringify it and compare against the literal `"{}"` to tell an empty tooltip from a
  /// real one. The engine formats it now, in the chart's own locale, and the anchor comes back in this
  /// view's own pixels so positioning needs no conversion.
  func testATooltipArrivesAsRowsAndAnAnchor() async {
    let session = await loaded()
    let scene = try! XCTUnwrap(session.scene)
    session.place(
      contentScale: 1,
      viewport: Rect(x: 0, y: 0, width: scene.width, height: scene.height)
    )

    XCTAssertNil(session.tooltip, "nothing is under the pointer yet")

    session.tap(at: Point(x: 50, y: 90))
    await session.settle()

    let tooltip = try! XCTUnwrap(session.tooltip, "a tap on the bar produced no tooltip")
    // This specification's tooltip channel is `datum.c`, a bare value — so one unlabelled row, and the
    // text is exactly the value rather than something with a colon invented in front of it.
    XCTAssertEqual(tooltip.rows, [ChartTooltip.Row(label: "", value: "a")])
    XCTAssertEqual(tooltip.text, "a")
    XCTAssertEqual(tooltip.anchor, CGPoint(x: 50, y: 90), "the anchor is where the finger was")

    // And a tap on nothing clears it, rather than leaving a bubble over an empty chart.
    session.tap(at: Point(x: 50, y: 5))
    await session.settle()
    XCTAssertNil(session.tooltip)
  }

  /// A row-valued tooltip, which is what `"tooltip": true` compiles to and what an app will meet.
  func testARowTooltipBecomesOneLinePerField() async {
    let session = ChartSession()
    session.load(
      specification: """
        {"$schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 200, "height": 100, "padding": 0,
         "data": [{"name": "t", "values": [{"c": "Total", "v": 18}]}],
         "scales": [
           {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
           {"name": "y", "type": "linear", "domain": [0, 27], "range": "height"}],
         "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
           "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
           "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
           "tooltip": {"signal": "datum"}}}}]}
        """
    )
    await session.settle()
    let scene = try! XCTUnwrap(session.scene, session.failure ?? "no scene")
    session.place(
      contentScale: 1,
      viewport: Rect(x: 0, y: 0, width: scene.width, height: scene.height)
    )

    session.tap(at: Point(x: 100, y: 60))
    await session.settle()

    let tooltip = try! XCTUnwrap(session.tooltip)
    XCTAssertEqual(
      tooltip.rows,
      [
        ChartTooltip.Row(label: "c", value: "Total"),
        ChartTooltip.Row(label: "v", value: "18"),
      ]
    )
    XCTAssertEqual(tooltip.text, "c: Total\nv: 18")
  }

  /// A pan that the chart actually follows — which it did not, on this renderer or on Compose.
  ///
  /// `VegaChartController` owns the viewport: it accumulates a pan into `viewportOffset`, multiplies a
  /// pinch into `viewportScale`, clamps the zoom and keeps the anchor still. The **Android View** read
  /// that back and drew through it; this side did not, so a pan made `canReset` true and left the chart
  /// exactly where it was. A gesture that does nothing reads as a broken renderer rather than an
  /// unfinished one.
  ///
  /// Two halves, and both are here: the session **publishes** the viewport so the drawing can apply it,
  /// and a tap after a pan still finds the mark that is now under the finger — which is the controller's
  /// own inverse working, and the thing that breaks the moment a host subtracts the pan twice.
  func testAPanMovesTheChartAndTapsFollowIt() async {
    let session = await loaded()
    let scene = try! XCTUnwrap(session.scene)
    session.place(
      contentScale: 1,
      viewport: Rect(x: 0, y: 0, width: scene.width, height: scene.height)
    )

    XCTAssertEqual(session.viewport, .identity, "nothing has moved yet")
    // Five points in from the left edge: inside the left bar, and — unlike its middle — far enough out
    // that a pan of forty takes it off the bar entirely. A bar a hundred wide would still be under a
    // finger at its centre after that pan, which is what makes the centre useless for this test.
    session.tap(at: Point(x: 5, y: 90))
    await session.settle()
    XCTAssertEqual(session.lastTouch, .tooltip("a"))

    // Now move the chart 40 points to the right and let it settle.
    session.pan(by: Point(x: 40, y: 0), phase: GesturePhase.changed)
    session.pan(by: Point(x: 0, y: 0), phase: GesturePhase.ended)
    await session.settle()

    XCTAssertEqual(session.viewport.offsetX, 40, "the pan was not published for the drawing")
    XCTAssertEqual(session.viewport.scale, 1)
    XCTAssertTrue(session.canReset)

    // The bar's left edge is now forty points in, and that is where a finger finds it.
    session.tap(at: Point(x: 45, y: 90))
    await session.settle()
    XCTAssertEqual(session.lastTouch, .tooltip("a"), "a tap where the bar now is did not find it")

    // And where its edge used to be there is now nothing: the chart moved out from under that point.
    session.tap(at: Point(x: 5, y: 90))
    await session.settle()
    XCTAssertEqual(session.lastTouch, .nothing(x: 5, y: 90))
  }

  /// The pixels move too, which is the half a session cannot show on its own.
  ///
  /// `VegaChartView.draw(into:size:)` is called directly, as `CoreGraphicsTargetTests` does, and the ink
  /// is counted either side of a pan. Composing the viewport onto the fit in the wrong order — or
  /// forgetting it, which was the defect — leaves this identical.
  @available(macOS 14.0, iOS 17.0, *)
  func testTheDrawingFollowsThePan() async throws {
    let session = await loaded()
    let scene = try XCTUnwrap(session.scene)
    session.place(
      contentScale: 1,
      viewport: Rect(x: 0, y: 0, width: scene.width, height: scene.height)
    )

    func leftmostInkedColumn() throws -> Int {
      let width = Int(scene.width)
      let height = Int(scene.height)
      let space = try XCTUnwrap(CGColorSpace(name: CGColorSpace.sRGB))
      let context = try XCTUnwrap(
        CGContext(
          data: nil, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
          space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue))
      VegaChartView(scene: scene, session: session)
        .draw(into: context, size: CGSize(width: scene.width, height: scene.height))
      let image = try XCTUnwrap(context.makeImage())
      let data = try XCTUnwrap(image.dataProvider?.data as Data?)
      for x in 0..<width {
        for y in 0..<height where data[(y * width + x) * 4 + 3] > 128 {
          return x
        }
      }
      return -1
    }

    let resting = try leftmostInkedColumn()
    XCTAssertGreaterThanOrEqual(resting, 0, "nothing was drawn at all")

    session.pan(by: Point(x: 30, y: 0), phase: GesturePhase.changed)
    await session.settle()
    let panned = try leftmostInkedColumn()

    XCTAssertEqual(panned, resting + 30, "the drawing did not follow the pan: \(resting) then \(panned)")
  }

  /// A key reaching the dataflow, which from here it could not.
  ///
  /// The Android View has translated keys since it was written, so a specification bound to `keydown`
  /// worked on one platform and not the other — and a keyboard is how Switch Control and Full Keyboard
  /// Access drive an app, so this is an accessibility path rather than a desktop nicety.
  func testAKeyReachesTheDataflow() async {
    let keyed = """
      {"$schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 100, "height": 50, "padding": 0,
       "signals": [{"name": "steps", "value": 0,
                    "on": [{"events": "keydown", "update": "steps + 1"}]}],
       "data": [{"name": "t", "values": [{"v": 1}]}],
       "marks": [{"type": "symbol", "from": {"data": "t"},
                  "encode": {"enter": {"x": {"value": 50}, "y": {"value": 25}}}}]}
      """
    let session = ChartSession()
    session.load(specification: keyed)
    await session.settle()
    XCTAssertNotNil(session.scene, session.failure ?? "no scene")

    session.press(.arrowRight)
    await session.settle()

    // The chart is still there and the press went through the controller rather than being dropped on
    // the floor: a handler that fired recompiles, and a recompile that failed would have cleared this.
    XCTAssertNotNil(session.scene, session.failure ?? "the key press took the chart down")
  }

  /// A touch arriving while the first compile is still running.
  ///
  /// The queue is not an optimisation. The controller is not safe for concurrent use — `setSpec`
  /// rebuilds the signal updater and the event dispatcher a touch then reads — and the compile
  /// deliberately runs off this actor. Dispatching a tap during that rebuild once left the chart stuck
  /// showing "no scene", because the touch published an empty snapshot over a compile that had not
  /// finished. A queued touch is also better behaviour than a dropped one.
  func testATouchDuringACompileIsQueuedRatherThanLost() async {
    let session = ChartSession()
    session.load(specification: specification)
    // No `settle()` first: this is the race.
    session.tap(at: Point(x: 50, y: 90))
    await session.settle()
    // The queued tap runs in a task of its own once the compile has finished, so give the main actor a
    // turn for it to land.
    await Task.yield()

    XCTAssertNotNil(session.scene, "the touch published over the compile: \(session.failure ?? "")")
  }

  func testAPresetSignalReachesTheDataflow() async {
    let session = ChartSession()
    session.load(
      specification: specification,
      signals: ["picked": ForeignSignals.shared.ofString(value: "b")]
    )
    await session.settle()

    let control = session.controls.first { $0.signal == "picked" }
    if let control {
      XCTAssertEqual(ForeignSignals.shared.text(value: session.value(of: control)), "b")
    }
    // Whether or not the signal is *bound* to a control, the chart still compiled with it applied.
    XCTAssertNotNil(session.scene)
  }

  func testAPanCanBeReset() async {
    let session = await loaded()
    XCTAssertFalse(session.canReset, "nothing has moved yet")

    session.pan(by: Point(x: 20, y: 0), phase: GesturePhase.changed)
    XCTAssertTrue(session.canReset, "a pan is something to reset")

    session.resetViewport()
    XCTAssertFalse(session.canReset)
  }

  /// A chart in Dutch, from iOS.
  ///
  /// The locale seam landed in the engine and was then unreachable from here: this session hard-coded
  /// `en-US`, so an iOS app using the surface it is told to use could not render a localised chart at
  /// all while an Android app could. Parity is the point of this test as much as the locale is.
  func testTheLocaleReachesTheChartFromHere() async {
    let months = """
      {"width": 300, "height": 120, "padding": 5,
       "data": [{"name": "t", "values": [{"t": "2026-05-20T10:00:00", "v": 1},
                                         {"t": "2026-06-17T10:00:00", "v": 2}],
                 "format": {"parse": {"t": "date"}}}],
       "scales": [{"name": "x", "type": "time", "domain": {"data": "t", "field": "t"},
                   "range": "width"}],
       "axes": [{"orient": "bottom", "scale": "x", "format": "%b %Y", "tickCount": 2}],
       "marks": [{"type": "symbol", "from": {"data": "t"}, "encode": {"enter": {
         "x": {"scale": "x", "field": "t"}, "y": {"value": 60}}}}]}
      """

    func drawn(with locale: VegaLocale?) async -> [String] {
      let session = ChartSession(locale: locale)
      session.load(specification: months)
      await session.settle()
      let scene = try! XCTUnwrap(session.scene, session.failure ?? "no scene")
      var target = RecordingTarget()
      SceneWalk().draw(scene: scene, into: &target)
      return target.calls.filter { $0.contains("text ") }
    }

    let english = await drawn(with: nil)
    XCTAssertTrue(english.contains { $0.contains("May 2026") }, "the default is d3's en-US: \(english)")

    let dutch = VegaLocale(
      months: [
        "januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september",
        "oktober", "november", "december",
      ],
      shortMonths: [
        "jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec",
      ],
      days: ["zondag", "maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag"],
      shortDays: ["zo", "ma", "di", "wo", "do", "vr", "za"],
      periods: ["a.m.", "p.m."],
      date: "%d-%m-%Y",
      time: "%H:%M:%S",
      dateTime: "%a %e %B %Y %X",
      // The order of a date's fields, which is the part a locale could not reach: the axis format
      // came from a table with no locale in it, so a Dutch chart read `mei 21, 2026`.
      // Nil, so both are derived from `date` and `time` above — which is what a host copying a d3
      // locale JSON across gets, and the whole of the fix.
      timeUnitSpecifierOverrides: nil,
      timeTickFormatOverrides: nil,
      decimal: ",",
      thousands: ".",
      grouping: [KotlinInt(value: 3)],
      minus: "\u{2212}",
      captions: VegaCaptionsCompanion.shared.English,
      // No rules: this locale's tables are enough for what it asserts.
      rules: nil
    )
    let localised = await drawn(with: dutch)
    XCTAssertTrue(localised.contains { $0.contains("mei 2026") }, "in Dutch: \(localised)")
  }

  /// A Vega-Lite document that will not compile is reported as one, not reinterpreted as Vega.
  ///
  /// The session used to fall back to the text as written when Vega-Lite compilation produced
  /// nothing, on the theory that the runtime would report on it. It does not: the unconverted text
  /// goes to a parser that only understands Vega, where `mark` and `encoding` are unknown properties
  /// and `marks` is absent — so a reader got a complaint about the wrong grammar, or an empty chart.
  ///
  /// The construct here is one the compiler refuses by name: a `layer` containing an `hconcat`.
  /// `VegaLiteTests` already asserts that `toVega` answers nil and says why; this is about what the
  /// session does with that.
  func testAVegaLiteDocumentThatWillNotCompileIsReportedAsOne() async {
    let session = ChartSession()
    session.load(
      specification: """
        {"$schema": "https://vega.github.io/schema/vega-lite/v6.json",
         "data": {"values": [{"a": 1}]},
         "layer": [{"hconcat": [{"mark": "bar",
           "encoding": {"x": {"field": "a", "type": "quantitative"}}}]}]}
        """)
    await session.settle()

    XCTAssertEqual(session.grammar, .vegaLite, "it was read as Vega-Lite, and stays read that way")
    XCTAssertNil(session.scene, "no chart, rather than a chart of the wrong grammar's leftovers")
    XCTAssertNotNil(session.failure, "a host has to be able to put something in front of a reader")
    XCTAssertFalse(session.vegaLiteDiagnostics.isEmpty)
    // The **conversion**'s own report, and in the channel a host that shows one channel is showing.
    XCTAssertEqual(
      session.diagnostics.map { $0.message }, session.vegaLiteDiagnostics.map { $0.message },
      "the diagnostics are the conversion's, not a second opinion from the Vega parser")
    XCTAssertFalse(session.loading)

    // The other half: text that is not Vega-Lite at all still reaches the Vega parser untouched.
    session.load(specification: specification)
    await session.settle()
    XCTAssertEqual(session.grammar, .vega)
    XCTAssertNotNil(session.scene)
    XCTAssertNil(session.failure)
  }

  /// `settle()` waits for work queued **during** a compile, not only for the compile.
  ///
  /// Only the compile was ever held. Everything `serialised` deferred — a container-size recompile, a
  /// tap that arrived mid-load, a reset — was started as a task nobody kept, so a caller that set a
  /// size while a compile was in flight and then settled returned before the resize had run. That is
  /// the race a screenshot test exists to rule out, and it was in the synchronising primitive itself.
  ///
  /// The size is set **without** awaiting the load first, which is the whole point: it has to land in
  /// the queue behind a compile that is still running.
  func testSettleWaitsForWorkQueuedDuringACompile() async {
    let responsive = """
      {"$schema": "https://vega.github.io/schema/vega-lite/v6.json",
       "width": "container",
       "data": {"values": [{"a": 1, "b": 2}, {"a": 3, "b": 4}]},
       "mark": "line",
       "encoding": {"x": {"field": "a", "type": "quantitative"},
                    "y": {"field": "b", "type": "quantitative"}}}
      """

    let session = ChartSession(containerSize: SizeD(width: 200, height: 400))
    session.load(specification: responsive)
    // No await here. The compile is in flight, so this goes into the queue behind it.
    session.containerSize = SizeD(width: 600, height: 400)

    await session.settle()

    let width = try! XCTUnwrap(session.scene, session.failure ?? "no scene").width
    XCTAssertGreaterThan(
      width, 500,
      "settle() returned before the queued resize had run: the chart is still 200 wide")
  }

  /// The **order** of a date's fields, which the locale seam could not reach from here at all.
  ///
  /// Two things had to be true for this to work, and neither was. The pattern a bucketed axis is
  /// formatted with is written by the *Vega-Lite* compiler, and this session never gave that compiler
  /// its locale — so the pattern was `%b %d, %Y` whatever the host said. And `TimeUnits`'s table took
  /// no locale, so nothing downstream could move it either.
  ///
  /// The middle assertion is the defect itself: Dutch month names in American order, which is what a
  /// host got for supplying a locale.
  func testALocaleDecidesTheOrderOfADateOnABucketedAxis() async {
    let bucketed = """
      {"$schema": "https://vega.github.io/schema/vega-lite/v6.json",
       "width": 400, "height": 120,
       "data": {"values": [{"t": "2026-05-20T10:00:00", "v": 1},
                           {"t": "2026-06-17T10:00:00", "v": 2}]},
       "mark": "point",
       "encoding": {"x": {"field": "t", "type": "temporal", "timeUnit": "yearmonthdate"},
                    "y": {"field": "v", "type": "quantitative"}}}
      """

    func labels(_ locale: VegaLocale?) async -> [String] {
      let session = ChartSession(locale: locale)
      session.load(specification: bucketed)
      await session.settle()
      let scene = try! XCTUnwrap(session.scene, session.failure ?? "no scene")
      var target = RecordingTarget()
      SceneWalk().draw(scene: scene, into: &target)
      return target.calls.filter { $0.contains("text ") }
    }

    let english = await labels(nil)
    XCTAssertTrue(english.contains { $0.contains("May 21, 2026") }, "en-US: \(english)")

    // Pinned to upstream's table, which is what a host got for *every* locale before this: the names
    // move and the order does not.
    let namesOnly = await labels(Self.dutch(dayFirst: false))
    XCTAssertTrue(
      namesOnly.contains { $0.contains("mei 21, 2026") },
      "an upstream-pinned locale keeps the American order: \(namesOnly)")

    let dayFirst = await labels(Self.dutch(dayFirst: true))
    XCTAssertTrue(dayFirst.contains { $0.contains("21 mei 2026") }, "day first: \(dayFirst)")
    XCTAssertFalse(
      dayFirst.contains { $0.contains("mei 21") },
      "the American order is gone rather than joined: \(dayFirst)")
  }

  /// A host's own **rules** reach a chart's labels, and cannot change what the format asked for.
  ///
  /// The seam that is behaviour rather than data, and the one place a *device's* preferences can get
  /// in: everything else about a locale is a table, and a table only answers what somebody thought to
  /// tabulate. The two cases here are the ones it provably cannot — a numbering system, since the
  /// engine writes `value.toString()` and that is ASCII always, and a name whose form depends on the
  /// rest of the format.
  ///
  /// The precedence is what the assertions are really about. The specification writes
  /// `"format": "%d/%m/%Y"`, and it keeps that order, those fields and those separators: what the
  /// rules decide is which digits write them. A real host would read the numbering system off
  /// `Locale.current` here rather than hard-coding one.
  func testAHostsOwnRulesReachTheLabelsWithoutChangingTheFormat() async {
    let months = """
      {"width": 300, "height": 120, "padding": 5,
       "data": [{"name": "t", "values": [{"t": "2026-05-21T10:00:00", "v": 1}],
                 "format": {"parse": {"t": "date"}}}],
       "scales": [{"name": "x", "type": "time", "domain": {"data": "t", "field": "t"},
                   "range": "width"}],
       "axes": [{"orient": "bottom", "scale": "x", "format": "%d/%m/%Y", "tickCount": 1}],
       "marks": [{"type": "symbol", "from": {"data": "t"}, "encode": {"enter": {
         "x": {"scale": "x", "field": "t"}, "y": {"value": 60}}}}]}
      """

    func drawn(with locale: VegaLocale?) async -> [String] {
      let session = ChartSession(locale: locale)
      session.load(specification: months)
      await session.settle()
      let scene = try! XCTUnwrap(session.scene, session.failure ?? "no scene")
      var target = RecordingTarget()
      SceneWalk().draw(scene: scene, into: &target)
      return target.calls.filter { $0.contains("text ") }
    }

    let ascii = await drawn(with: nil)
    XCTAssertTrue(ascii.contains { $0.contains("21/05/2026") }, "ASCII by default: \(ascii)")

    let eastern = await drawn(
      with: VegaLocale.Companion.shared.EnglishUS.doCopy(
        months: VegaLocale.Companion.shared.EnglishUS.months,
        shortMonths: VegaLocale.Companion.shared.EnglishUS.shortMonths,
        days: VegaLocale.Companion.shared.EnglishUS.days,
        shortDays: VegaLocale.Companion.shared.EnglishUS.shortDays,
        periods: VegaLocale.Companion.shared.EnglishUS.periods,
        date: VegaLocale.Companion.shared.EnglishUS.date,
        time: VegaLocale.Companion.shared.EnglishUS.time,
        dateTime: VegaLocale.Companion.shared.EnglishUS.dateTime,
        timeUnitSpecifierOverrides: [:],
        timeTickFormatOverrides: [:],
        decimal: VegaLocale.Companion.shared.EnglishUS.decimal,
        thousands: VegaLocale.Companion.shared.EnglishUS.thousands,
        grouping: VegaLocale.Companion.shared.EnglishUS.grouping,
        minus: VegaLocale.Companion.shared.EnglishUS.minus,
        captions: VegaLocale.Companion.shared.EnglishUS.captions,
        rules: EasternArabicRules()
      ))

    // The digits are the host's; the order, the fields and the slashes are still the document's.
    XCTAssertTrue(
      eastern.contains { $0.contains("٢١/٠٥/٢٠٢٦") },
      "the host's numbering system, in the specification's own format: \(eastern)")
    XCTAssertFalse(
      eastern.contains { $0.contains("21/05/2026") }, "and not both: \(eastern)")
  }

  /// A numbering system a `VegaLocale` field could not have expressed.
  ///
  /// `name` abstains, which is the ordinary case: a host implements the rules it has and inherits the
  /// rest, because every method may answer nil.
  private final class EasternArabicRules: VegaFormatRules {
    private static let arabic = ["٠", "١", "٢", "٣", "٤", "٥", "٦", "٧", "٨", "٩"]

    func name(field: DateName, index: Int32, context: DateNameContext, locale: VegaLocale) -> String?
    {
      nil
    }

    func digits(number: String) -> String? {
      String(
        number.map { character in
          guard let digit = character.wholeNumberValue, character.isNumber, digit < 10 else {
            return character
          }
          return Character(Self.arabic[digit])
        })
    }
  }

  /// Dutch, deriving its date order from its own `%x` or pinned to upstream's table.
  private static func dutch(dayFirst: Bool) -> VegaLocale {
    VegaLocale(
      months: [
        "januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september",
        "oktober", "november", "december",
      ],
      shortMonths: [
        "jan", "feb", "mrt", "apr", "mei", "jun", "jul", "aug", "sep", "okt", "nov", "dec",
      ],
      days: ["zondag", "maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag"],
      shortDays: ["zo", "ma", "di", "wo", "do", "vr", "za"],
      periods: ["a.m.", "p.m."],
      date: "%d-%m-%Y",
      time: "%H:%M:%S",
      dateTime: "%a %e %B %Y %X",
      // **Nil derives from `date` above**, which is the whole of the fix: `%d-%m-%Y` says this
      // language writes the day first, and until now nothing read it — so a Dutch chart said
      // `mei 21, 2026`, the right month name in the American order. An empty map pins a locale to
      // upstream's own table instead, and `VegaLocale.EnglishUS` is the only one that does.
      timeUnitSpecifierOverrides: dayFirst ? nil : [:],
      timeTickFormatOverrides: dayFirst ? nil : [:],
      decimal: ",",
      thousands: ".",
      grouping: [KotlinInt(value: 3)],
      minus: "\u{2212}",
      captions: VegaCaptionsCompanion.shared.English,
      // No rules: this locale's tables are enough for what it asserts.
      rules: nil
    )
  }

  /// A dark chart, themed by the app, from iOS — the other seam that was unreachable from here.
  ///
  /// The configuration is JSON because a theme is written as JSON, and it has to reach **both** the
  /// Vega-Lite compiler and the runtime: Vega-Lite merges `config` before it compiles, so a theme
  /// applied on one side only is a chart half in the app's colours.
  func testTheHostConfigurationReachesBothCompilers() async {
    let vegaLite = """
      {"$schema": "https://vega.github.io/schema/vega-lite/v6.json",
       "data": {"values": [{"a": 1, "b": 2}]},
       "mark": "point",
       "encoding": {"x": {"field": "a", "type": "quantitative"},
                    "y": {"field": "b", "type": "quantitative"}}}
      """

    let theme = "{\"background\": \"#101820\", \"mark\": {\"color\": \"#7fd1b9\"}}"
    let session = ChartSession(hostConfigJson: theme)
    session.load(specification: vegaLite)
    await session.settle()

    XCTAssertNil(session.hostConfigFailure)
    let scene = try! XCTUnwrap(session.scene, session.failure ?? "no scene")
    // The background is the scene's own property, and it came from the configuration this app supplied
    // to a specification that never mentioned one.
    let background = try! XCTUnwrap(scene.background)
    XCTAssertEqual(Int((background.red * 255).rounded()), 0x10)
    XCTAssertEqual(Int((background.green * 255).rounded()), 0x18)
    XCTAssertEqual(Int((background.blue * 255).rounded()), 0x20)
  }

  func testAHostConfigurationThatIsNotAnObjectIsReportedAndTheChartStillDraws() async {
    let session = ChartSession(hostConfigJson: "not a configuration")
    session.load(specification: specification)
    await session.settle()

    XCTAssertNotNil(session.hostConfigFailure, "a theme that could not be read has to say so")
    XCTAssertNotNil(session.scene, "and the chart is drawn unthemed rather than not drawn")
  }

  /// `width: "container"`, which an iOS host could not answer at all before.
  func testTheContainerWidthIsTheChartsWidth() async {
    let responsive = """
      {"$schema": "https://vega.github.io/schema/vega-lite/v6.json",
       "width": "container",
       "data": {"values": [{"a": 1, "b": 2}, {"a": 3, "b": 4}]},
       "mark": "line",
       "encoding": {"x": {"field": "a", "type": "quantitative"},
                    "y": {"field": "b", "type": "quantitative"}}}
      """

    let narrow = ChartSession(containerSize: SizeD(width: 200, height: 400))
    narrow.load(specification: responsive)
    await narrow.settle()
    let narrowWidth = try! XCTUnwrap(narrow.scene).width

    let wide = ChartSession(containerSize: SizeD(width: 600, height: 400))
    wide.load(specification: responsive)
    await wide.settle()
    XCTAssertGreaterThan(try! XCTUnwrap(wide.scene).width, narrowWidth + 300)

    // And set again after the fact, which is what a layout change is. Awaited, because the setter
    // queues a recompile off this actor rather than running one inline — a resize arrives on the
    // main thread and a compile does not belong there. It was never reliably synchronous anyway:
    // with a compile in flight it was already deferred, and the deferred task was one nobody held.
    narrow.containerSize = SizeD(width: 600, height: 400)
    await narrow.settle()
    XCTAssertGreaterThan(try! XCTUnwrap(narrow.scene).width, narrowWidth + 300)
  }

  /// A chart drawn from data the **app** holds, which is what a diary is.
  ///
  /// The specification names a dataset and carries no rows for it. Everything about the chart — the
  /// scales, the axis, the marks — waits on the host, which is upstream's `view.data(name, rows)` and
  /// the shape an app with a local store needs. The rows go in where inline values would, so the
  /// dataset's own `format.parse` still parses and its transforms still run.
  func testAChartIsDrawnFromATableTheHostSupplies() async {
    let awaitingData = """
      {"width": 200, "height": 100, "padding": 5,
       "data": [{"name": "diary"}],
       "scales": [
         {"name": "x", "type": "band", "domain": {"data": "diary", "field": "bucket"},
          "range": "width"},
         {"name": "y", "type": "linear", "domain": {"data": "diary", "field": "v"},
          "range": "height"}],
       "marks": [{"type": "rect", "from": {"data": "diary"}, "encode": {"enter": {
         "x": {"scale": "x", "field": "bucket"}, "width": {"scale": "x", "band": 1},
         "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0}}}}]}
      """

    let session = ChartSession()
    session.load(specification: awaitingData)
    await session.settle()
    XCTAssertEqual(rectangles(in: session), 0, "nothing supplied yet, so nothing is drawn")

    session.setData(
      "diary",
      rows: [
        ["bucket": .text("morning"), "v": .number(3)],
        ["bucket": .text("evening"), "v": .number(7)],
      ]
    )
    XCTAssertEqual(rectangles(in: session), 2, "one bar per row the app handed over")

    // And again, because data changes: a store that gained a row redraws with three.
    session.setData(
      "diary",
      rows: [
        ["bucket": .text("morning"), "v": .number(3)],
        ["bucket": .text("afternoon"), "v": .number(5)],
        ["bucket": .text("evening"), "v": .number(7)],
      ]
    )
    XCTAssertEqual(rectangles(in: session), 3)
  }

  /// A `Date` handed over as an instant, with no parse rule in the specification at all.
  ///
  /// The round trip this avoids is a defect rather than an inefficiency: formatting a `Date` to a string
  /// for the engine to parse back goes through a zone twice, and a naive string read in the wrong one
  /// lands on the wrong day. Asserted through what is **drawn**, since a label is what a reader sees.
  func testADateIsHandedOverAsAnInstant() async {
    let session = ChartSession()
    session.load(
      specification: """
        {"width": 200, "height": 100, "padding": 0,
         "data": [{"name": "t"}],
         "marks": [{"type": "text", "from": {"data": "t"}, "encode": {"enter": {
           "x": {"value": 10}, "y": {"value": 20},
           "text": {"signal": "utcFormat(datum.at, '%d %B %Y')"}}}}]}
        """
    )
    await session.settle()

    // 2026-05-20T12:00:00Z, as a `Date` — which is how an app holds one.
    session.setData("t", rows: [["at": .instant(Date(timeIntervalSince1970: 1_779_278_400))]])

    let scene = try! XCTUnwrap(session.scene, session.failure ?? "no scene")
    var target = RecordingTarget()
    SceneWalk().draw(scene: scene, into: &target)
    XCTAssertTrue(
      target.calls.contains { $0.contains("20 May 2026") },
      "an instant needs no `format.parse` entry: \(target.calls)"
    )
  }

  private func rectangles(in session: ChartSession) -> Int {
    guard let scene = session.scene else { return 0 }
    var target = RecordingTarget()
    SceneWalk().draw(scene: scene, into: &target)
    // Indented by group depth, so matched rather than prefixed.
    return target.calls.filter { $0.trimmingCharacters(in: .whitespaces).hasPrefix("rect ") }.count
  }

  /// The zone a chart's local time is in, which on a handset is not always the reader's.
  ///
  /// A profile setting, an account read from two places, a tablet left on a factory zone: the app knows
  /// which day a measurement was on and the device may not. Both charts below draw the **same instant**
  /// — the payload carries `Z` — and put it on different days, which is the whole of what the seam does.
  func testTheHostSaysWhichZoneLocalIs() async {
    let onADateLine = """
      {"width": 200, "height": 100, "padding": 5,
       "data": [{"name": "t", "values": [{"t": "2026-05-20T12:00:00Z", "v": 1}],
                 "format": {"parse": {"t": "date"}}}],
       "scales": [{"name": "x", "type": "time", "domain": {"data": "t", "field": "t"},
                   "range": "width"}],
       "axes": [{"orient": "bottom", "scale": "x", "format": "%d %B", "tickCount": 1}],
       "marks": [{"type": "symbol", "from": {"data": "t"}, "encode": {"enter": {
         "x": {"scale": "x", "field": "t"}, "y": {"value": 50}}}}]}
      """

    func labels(in zone: String) async -> [String] {
      let session = ChartSession(timeZone: Foundation.TimeZone(identifier: zone))
      session.load(specification: onADateLine)
      await session.settle()
      XCTAssertNil(session.timeZoneFailure, "the platform should know \(zone)")
      let scene = try! XCTUnwrap(session.scene)
      var target = RecordingTarget()
      SceneWalk().draw(scene: scene, into: &target)
      return target.calls.filter { $0.contains("text ") }
    }

    // UTC+14 is already the 21st at midday UTC; UTC-11 is still the 20th.
    let east = await labels(in: "Pacific/Kiritimati")
    let west = await labels(in: "Pacific/Niue")
    XCTAssertTrue(east.contains { $0.contains("21 May") }, "east of the line: \(east)")
    XCTAssertTrue(west.contains { $0.contains("20 May") }, "west of the line: \(west)")
  }

  /// An identifier the engine cannot resolve is reported, not thrown, and the chart still draws.
  func testAnUnknownZoneIsReportedRatherThanFatal() async {
    let session = ChartSession(timeZone: Foundation.TimeZone(identifier: "UTC"))
    session.load(specification: specification)
    await session.settle()
    XCTAssertNil(session.timeZoneFailure, "UTC is a zone every platform has")
    XCTAssertNotNil(session.scene)
  }

  func testTextThatIsNotASpecificationFailsWithAReason() async {
    let session = ChartSession()
    session.load(specification: "not a chart")
    await session.settle()

    XCTAssertNil(session.scene)
    XCTAssertNotNil(session.failure, "a failure a host can put in front of a reader")
  }

  /// `usermeta` reaches the host, from either grammar.
  ///
  /// It used to reach nobody: the Vega parser dropped it with a warning, so the only property whose
  /// purpose is to survive compilation did not. And a Swift host had no path to it even once the
  /// parser kept it, because `ChartSession` publishes what a host reads and did not publish this —
  /// the same shape of gap as a capability that exists for Kotlin alone.
  ///
  /// Both grammars are asserted because a Vega-Lite document loses it in two places otherwise: the
  /// Vega-Lite compiler has to carry it onto the Vega it emits, and the Vega parser has to keep it.
  func testUsermetaReachesTheHostFromEitherGrammar() async {
    let session = ChartSession()

    session.load(
      specification: specification.replacingOccurrences(
        of: "\"width\": 200", with: "\"usermeta\": {\"source\": \"diary\"}, \"width\": 200"))
    await session.settle()
    XCTAssertEqual(
      VegaValueKt.asString(session.usermeta?["source"] ?? VegaValueNull.shared), "diary")

    session.load(
      specification: """
        {"$schema": "https://vega.github.io/schema/vega-lite/v6.json",
         "usermeta": {"source": "diary"},
         "data": {"values": [{"c": "a", "v": 30}]},
         "mark": "bar",
         "encoding": {"x": {"field": "c", "type": "nominal"},
                      "y": {"field": "v", "type": "quantitative"}}}
        """)
    await session.settle()
    XCTAssertEqual(session.grammar, .vegaLite)
    XCTAssertEqual(
      VegaValueKt.asString(session.usermeta?["source"] ?? VegaValueNull.shared), "diary")

    // A document that carries none says none, rather than keeping the last one's.
    session.load(specification: specification)
    await session.settle()
    XCTAssertNil(session.usermeta)
  }
}

/// The arithmetic a host might have to repeat, and therefore should not have to.
final class ChartPlacementTests: XCTestCase {

  func testAPointIsInvertedThroughTheSamePlacementTheDrawingUsed() {
    let placement = ChartPlacement(scale: 2, left: 10, top: 4)

    // The offset comes off and the scale does **not**: `contentScale` is part of the controller's
    // contract and it divides by that itself. Applying the fit twice here is the trap this type exists
    // to keep a host out of — it has caught this project twice, once on Android and once on iOS.
    let point = placement.scenePoint(of: CGPoint(x: 30, y: 24))
    XCTAssertEqual(point.x, 20)
    XCTAssertEqual(point.y, 20)
  }
}
