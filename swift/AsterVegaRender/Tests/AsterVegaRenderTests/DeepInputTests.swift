import AsterVega
import XCTest

@testable import AsterVegaRender

/// **Nothing crashes, on input built to make something recurse or allocate.**
///
/// The mirror of `DeepInputTest` in `vega-runtime`'s `commonTest`, and it is not redundant with it.
/// That one proves the *engine's* limits hold on every Kotlin target. This one proves they still
/// hold when an app reaches them the way an app actually does: through `ChartSession`, across the
/// Obj-C boundary, on text a reader pasted into `PasteSpecView`.
///
/// Two things could break here that cannot break there. The bridge could lose a diagnostic — a
/// Kotlin `null` and a refusal look alike from Swift if a wrapper is careless. And `ChartSession`
/// parses one thing *itself*, `hostConfigJson`, on a path the Kotlin tests never take.
///
/// ### Why a crash and not a failure
///
/// If a bound is missing, this does not report — the process dies. Swift has no catchable stack
/// overflow, exactly as Kotlin/Native has none, and that is the whole reason the engine bounds
/// these rather than catching them. A red run here is a `SIGSEGV` with the test name that was
/// executing, and the fix is a bound in the engine, never a `try` on this side.
@MainActor
final class DeepInputTests: XCTestCase {

  /// Depths chosen the way the Kotlin suite chooses them: past where any stack survives, and far
  /// past it, so a bound that is really "the stack happened to be big enough" fails at one of them.
  private let depths = [500, 5_000, 100_000]

  private func session() -> ChartSession {
    ChartSession(textEngine: CoreTextTextEngine())
  }

  // MARK: - Depth

  func testNoShapeOfDeeplyNestedInputCrashes() async throws {
    for depth in depths {
      for (name, document) in Self.shapes(depth: depth) {
        let chart = session()
        chart.load(specification: document)
        await chart.settled()
        // The assertion is deliberately weak: what is being tested is that this line is reached.
        XCTAssertFalse(chart.loading, "\(name) at depth \(depth) left the session loading")
      }
    }
  }

  /// The shapes, each a document with a depth a reader controls.
  private static func shapes(depth n: Int) -> [(String, String)] {
    let brackets = String(repeating: "[", count: n) + "mousedown" + String(repeating: "]", count: n)
    return [
      (
        "vega: nested group marks",
        """
        {"width":10,"height":10,"padding":0,"marks":
        \(String(repeating: "[{\"type\":\"group\",\"marks\":", count: n))[]\
        \(String(repeating: "}]", count: n))}
        """
      ),
      (
        "vega-lite: nested layers",
        String(repeating: "{\"layer\":[", count: n)
          + #"{"mark":"point","encoding":{}}"#
          + String(repeating: "]}", count: n)
      ),
      (
        "vega-lite: nested select.on brackets",
        """
        {"data":{"values":[{"a":1}]},"mark":"point",
         "params":[{"name":"s","select":{"type":"point","on":"\(brackets)"}}],
         "encoding":{"x":{"field":"a","type":"quantitative"}}}
        """
      ),
      (
        "json: nested objects",
        String(repeating: #"{"a":"#, count: n) + "1" + String(repeating: "}", count: n)
      ),
    ]
  }

  // MARK: - Size

  /// Input that asks for more memory than the device has, which is the same failure one resource
  /// over — and on this platform an allocation failure is a termination, not an exception.
  func testNoShapeOfOversizedInputCrashes() async throws {
    for rows in [1_000_000, 1_000_000_000] {
      let chart = session()
      chart.load(
        specification: """
          {"width":10,"height":10,"padding":0,
           "data":[{"name":"t","transform":[
             {"type":"sequence","start":0,"stop":\(rows)}]}],"marks":[]}
          """)
      await chart.settled()
      XCTAssertFalse(chart.loading, "a sequence of \(rows) rows left the session loading")
    }
  }

  // MARK: - The one thing this side parses itself

  /// `hostConfigJson` is parsed by `ChartSession`, on a path no Kotlin test takes.
  ///
  /// It goes through `VegaJson.parseOrNull`, so the depth bound applies — but that is a fact about
  /// today's implementation rather than something the type says, and this is what would notice it
  /// changing. A refusal must surface as `hostConfigFailure`, not as a crash and not as silence.
  func testADeepHostConfigIsRefusedRatherThanParsed() async throws {
    let deep = String(repeating: #"{"a":"#, count: 5_000) + "1" + String(repeating: "}", count: 5_000)
    let chart = ChartSession(textEngine: CoreTextTextEngine(), hostConfigJson: deep)
    XCTAssertNotNil(
      chart.hostConfigFailure,
      "a host configuration too deep to parse must say so rather than being dropped")

    // And an ordinary one still works, so the guard is a ceiling rather than a refusal of everything.
    let fine = ChartSession(
      textEngine: CoreTextTextEngine(), hostConfigJson: #"{"axis":{"labelFontSize":9}}"#)
    XCTAssertNil(fine.hostConfigFailure)
  }

  // MARK: - And the ordinary case still draws

  /// Without this the tests above are satisfied by a session that refuses everything.
  func testTheSameShapesStillDrawAtAnOrdinaryDepth() async throws {
    let chart = session()
    chart.load(
      specification: String(repeating: "{\"layer\":[", count: 3)
        + #"{"data":{"values":[{"a":1}]},"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"}}}"#
        + String(repeating: "]}", count: 3))
    await chart.settled()
    XCTAssertNotNil(chart.scene, "three layers is an ordinary chart")
  }
}
