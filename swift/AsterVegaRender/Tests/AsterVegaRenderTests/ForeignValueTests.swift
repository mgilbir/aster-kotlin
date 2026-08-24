import AsterVega
import XCTest

@testable import AsterVegaRender

/// A Swift host can read a datum whose shape it does not know.
///
/// `Obj` and `Arr` are value classes, so `fields` and `values` have no Obj-C representation: a host
/// could read a *named* field through `field(_:path:)` and could not ask what fields there were.
/// Reported as #120 by an adopter reading `NodeMetadata.datum`.
///
/// The coercion tests are the ones that matter. `asString` renders a number, a boolean and an object
/// all as text, so a host using it cannot tell a field that held `"3"` from one that held `3` — which
/// is right for an expression and wrong for deciding how to display a value.
final class ForeignValueTests: XCTestCase {

  private let foreign = ForeignValue.shared

  private func datum() -> any AsterVega.VegaValue {
    VegaJson.shared.parse(
      text: """
        {"variety": "Manchuria", "yield": 27, "ok": true, "tags": ["a", "b"], "nested": {"x": 1}}
        """)
  }

  func testAnObjectsKeysCanBeEnumerated() {
    XCTAssertEqual(
      ["variety", "yield", "ok", "tags", "nested"], foreign.keys(value: datum()))
    XCTAssertEqual(5, foreign.count(value: datum()))
  }

  func testAnArrayCanBeCountedAndIndexed() {
    let tags = foreign.get(value: datum(), key: "tags")
    XCTAssertEqual("array", foreign.kind(value: tags))
    XCTAssertEqual(2, foreign.count(value: tags))
    XCTAssertEqual("a", foreign.string(value: foreign.at(value: tags, index: 0)))
    XCTAssertEqual("b", foreign.string(value: foreign.at(value: tags, index: 1)))
    XCTAssertNil(foreign.at(value: tags, index: 2), "an index past the end is nil, not a crash")
  }

  func testTheReadersDoNotCoerce() {
    let d = datum()
    // The whole point: a host can tell what a field held.
    XCTAssertEqual("Manchuria", foreign.string(value: foreign.get(value: d, key: "variety")))
    XCTAssertNil(
      foreign.string(value: foreign.get(value: d, key: "yield")),
      "a number is not a string, where asString would have rendered it as one")
    XCTAssertEqual(27, foreign.number(value: foreign.get(value: d, key: "yield")))
    XCTAssertNil(foreign.number(value: foreign.get(value: d, key: "variety")))
    XCTAssertEqual(true, foreign.boolean(value: foreign.get(value: d, key: "ok"))?.boolValue)
    XCTAssertNil(foreign.boolean(value: foreign.get(value: d, key: "yield")))
  }

  func testKindNamesTheShapeSoAHostNeedNotGuess() {
    let d = datum()
    XCTAssertEqual("object", foreign.kind(value: d))
    XCTAssertEqual("string", foreign.kind(value: foreign.get(value: d, key: "variety")))
    XCTAssertEqual("number", foreign.kind(value: foreign.get(value: d, key: "yield")))
    XCTAssertEqual("boolean", foreign.kind(value: foreign.get(value: d, key: "ok")))
    XCTAssertEqual("array", foreign.kind(value: foreign.get(value: d, key: "tags")))
    XCTAssertEqual("object", foreign.kind(value: foreign.get(value: d, key: "nested")))
    XCTAssertEqual("missing", foreign.kind(value: foreign.get(value: d, key: "absent")))
  }

  func testAWholeDatumCanBeWalkedWithoutKnowingItsShape() {
    // What the issue actually asked for: read a datum you were handed, not one you designed.
    var seen: [String: String] = [:]
    let d = datum()
    for key in foreign.keys(value: d) {
      seen[key] = foreign.kind(value: foreign.get(value: d, key: key))
    }
    XCTAssertEqual(
      ["variety": "string", "yield": "number", "ok": "boolean", "tags": "array",
       "nested": "object"],
      seen)
  }
}
