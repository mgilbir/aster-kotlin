import AsterVega
import CoreText
import XCTest

@testable import AsterVegaRender

/// This engine against `test-fixtures/host-conformance/font-stack.txt`.
///
/// One golden, one reader per host — see that directory's README. `scripts/host-parity.py` checks a
/// seam exists; this checks the three engines **agree about what it does**, which is where the
/// defects were. #123 was four hosts carrying a font resolver and three reading a CSS stack three
/// different ways, and this renderer was the one that asked for the first entry only.
final class FontStackConformanceTests: XCTestCase {

  func testAsksItsResolverTheSameNamesEveryOtherEngineAsks() throws {
    let expected = HostConformance.cases(try HostConformance.golden("font-stack.txt"))
    XCTAssertFalse(expected.isEmpty, "the golden should have cases in it")

    for (stack, names) in expected {
      var asked: [String] = []
      _ = CoreTextFonts.font(
        family: stack, size: 11, weight: 400, italic: false,
        resolveFont: { name in
          asked.append(name)
          return nil
        })
      XCTAssertEqual(names, asked, "for \(stack)")
    }
  }
}
