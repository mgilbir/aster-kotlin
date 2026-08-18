import XCTest
import AsterVega
@testable import AsterVegaRender

/// A chart's controls, across the Obj-C boundary.
///
/// This is the half of the demo app that a screenshot cannot check. The simulator can launch an app but
/// cannot drag a slider, so what a control *does* — recompile the specification with the reader's value
/// and produce a different scene — is asserted here instead.
///
/// It is also the test that `ForeignSignals` exists at all. Every interesting `VegaValue` is a
/// `@JvmInline value class` and therefore absent from the generated header, so before those accessors a
/// host could draw a slider and had no way to say where the reader had put it. Each assertion below
/// would have been impossible to write.
final class SignalControlTests: XCTestCase {

  /// A chart with one of each bound control, so all four paths are covered by one compile.
  private let specification = """
    {"$schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 200, "height": 100, "padding": 0,
     "signals": [
       {"name": "scale", "value": 1,
        "bind": {"input": "range", "min": 0.5, "max": 3, "step": 0.5}},
       {"name": "colour", "value": "steelblue",
        "bind": {"input": "select", "options": ["steelblue", "firebrick"]}},
       {"name": "outlined", "value": false, "bind": {"input": "checkbox"}}
     ],
     "data": [{"name": "t", "values": [{"v": 20}, {"v": 30}]}],
     "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"update": {
       "x": {"value": 0}, "width": {"value": 40},
       "y": {"value": 0}, "height": {"signal": "datum.v * scale"},
       "fill": {"signal": "colour"},
       "stroke": {"signal": "outlined ? 'black' : null"},
       "strokeWidth": {"value": 2}}}}]}
    """

  private func compile(_ overrides: [String: VegaValue] = [:]) -> CompiledSpec {
    SpecCompiler(
      textEngine: MetricTextEngine(advanceRatio: 0.6, ascentRatio: 0.8, descentRatio: 0.2),
      loader: DenyLoader(),
      randomSeed: 42,
      clock: ClockCompanion.shared.Fixed,
      locale: VegaLocale.Companion.shared.EnglishUS,
      hostConfig: nil,
      containerSize: nil
    )
    .compileJson(json: specification, signalOverrides: overrides, itemEncodes: [:])
  }

  private func record(_ compiled: CompiledSpec) throws -> [String] {
    var target = RecordingTarget()
    SceneWalk().draw(scene: try XCTUnwrap(compiled.scene), into: &target)
    return target.calls
  }

  func testAValueSurvivesTheRoundTripAcrossTheBoundary() {
    let signals = ForeignSignals.shared

    XCTAssertEqual(signals.number(value: signals.ofNumber(value: 42.5))?.doubleValue, 42.5)
    XCTAssertEqual(signals.boolean(value: signals.ofBoolean(value: true))?.boolValue, true)
    XCTAssertEqual(signals.text(value: signals.ofString(value: "firebrick")), "firebrick")

    // The kinds a host switches on to pick a control.
    XCTAssertEqual(signals.kind(value: signals.ofNumber(value: 1)), "number")
    XCTAssertEqual(signals.kind(value: signals.ofBoolean(value: false)), "boolean")
    XCTAssertEqual(signals.kind(value: signals.ofString(value: "x")), "string")
    XCTAssertEqual(signals.kind(value: nil), "null")

    // A number read as a boolean is not a boolean, which is what keeps a checkbox from claiming a
    // slider's value.
    XCTAssertNil(signals.boolean(value: signals.ofNumber(value: 1)))
    XCTAssertNil(signals.number(value: signals.ofString(value: "1")))
  }

  func testTheControlsAChartAsksForAreReportedWithTheirValues() throws {
    let signals = ForeignSignals.shared
    let inputs = signals.inputs(compiled: compile())

    XCTAssertEqual(inputs.count, 3, "one control per bound signal")
    XCTAssertEqual(inputs.map { $0.signal }, ["scale", "colour", "outlined"])
    XCTAssertEqual(
      inputs.map { signals.bindKind(bind: $0.bind) },
      ["range", "choice", "checkbox"],
      "each control keeps the shape the specification asked for"
    )

    // A range's bounds arrive resolved, so a slider knows where its ends are.
    let bounds = try XCTUnwrap(signals.rangeBounds(bind: inputs[0].bind)).map { $0.doubleValue }
    XCTAssertEqual(bounds, [0.5, 3, 0.5])

    // A choice's options arrive as the values a signal would become, with labels to show.
    XCTAssertEqual(signals.choiceLabels(bind: inputs[1].bind), ["steelblue", "firebrick"])
    XCTAssertEqual(signals.choiceOptions(bind: inputs[1].bind)?.count, 2)

    // And the current values, which is what puts a handle in the right place on first draw.
    XCTAssertEqual(signals.number(value: inputs[0].value)?.doubleValue, 1)
    XCTAssertEqual(signals.text(value: inputs[1].value), "steelblue")
    XCTAssertEqual(signals.boolean(value: inputs[2].value)?.boolValue, false)
  }

  func testMovingASliderChangesTheChart() throws {
    let signals = ForeignSignals.shared
    let before = try record(compile())
    let after = try record(compile(["scale": signals.ofNumber(value: 3)]))

    // The taller bar is three times its height, so the geometry has to differ. Comparing recordings
    // rather than a single number is the point: this is the whole drawing, and if a control changed
    // nothing the two would be identical.
    XCTAssertNotEqual(before, after, "a slider that changes nothing is not a control")
    XCTAssertTrue(
      before.contains { $0.contains("40x20") } && after.contains { $0.contains("40x60") },
      "20 units at scale 3 is 60:\nbefore:\n\(before.joined(separator: "\n"))\n"
        + "after:\n\(after.joined(separator: "\n"))"
    )

    // And the control now reports the reader's value, not the specification's, which is what keeps
    // the handle where they left it.
    let inputs = signals.inputs(compiled: compile(["scale": signals.ofNumber(value: 3)]))
    XCTAssertEqual(signals.number(value: inputs[0].value)?.doubleValue, 3)
  }

  func testAChoiceAndACheckboxReachTheDrawing() throws {
    let signals = ForeignSignals.shared

    let red = try record(compile(["colour": signals.ofString(value: "firebrick")]))
    XCTAssertTrue(
      red.contains { $0.contains("#b22222") },
      "the chosen colour is painted:\n\(red.joined(separator: "\n"))"
    )

    let plain = try record(compile())
    let outlined = try record(compile(["outlined": signals.ofBoolean(value: true)]))
    XCTAssertFalse(plain.contains { $0.contains("stroke") }, "unchecked draws no outline")
    XCTAssertTrue(
      outlined.contains { $0.contains("stroke") },
      "checked draws one:\n\(outlined.joined(separator: "\n"))"
    )
  }

  /// A specification with no `bind` asks for no controls, which is most of them.
  func testAChartWithoutBindingsAsksForNothing() {
    let compiled = SpecCompiler(
      textEngine: MetricTextEngine(advanceRatio: 0.6, ascentRatio: 0.8, descentRatio: 0.2),
      loader: DenyLoader(),
      randomSeed: 42,
      clock: ClockCompanion.shared.Fixed,
      locale: VegaLocale.Companion.shared.EnglishUS,
      hostConfig: nil,
      containerSize: nil
    )
    .compileJson(
      json: """
        {"$schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 50, "height": 50, "padding": 0,
         "signals": [{"name": "plain", "value": 3}],
         "marks": []}
        """,
      signalOverrides: [:],
      itemEncodes: [:]
    )
    XCTAssertTrue(ForeignSignals.shared.inputs(compiled: compiled).isEmpty)
  }
}
