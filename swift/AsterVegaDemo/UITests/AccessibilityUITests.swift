import XCTest

/// The accessibility tree, as the system actually sees it.
///
/// Everything else about this is tested below the surface: the rules live in `AccessibilityTree` in the core
/// and are tested there, and Swift unit tests confirm a host can read the elements. What none of that covers
/// is the last step — whether the SwiftUI wiring turns them into elements VoiceOver can reach. That question
/// can only be asked of a running app, which is why this target exists.
///
/// It is worth asking. `accessibilityChildren` is easy to write in a way that compiles, draws correctly and
/// exposes nothing at all — the first version of these tests passed with the whole accessibility block
/// deleted, because they asserted on things a navigation bar provides. They now assert on labels only the
/// engine produces.
final class AccessibilityUITests: XCTestCase {

  private func launch(chart: String) -> XCUIApplication {
    let app = XCUIApplication()
    app.launchArguments = ["-chart", chart]
    app.launch()
    return app
  }

  /// Every bar is its own element, labelled with the datum a reader wants to hear.
  ///
  /// The `bar` fixture is eight months, and the engine joins each mark's label to its value — so the labels
  /// are "Jan: 28", "Feb: 55" and so on. Nothing but the scene's own accessibility descriptors produces
  /// those, which is what makes this test about the wiring rather than about the chrome around it.
  func testEveryBarIsItsOwnElementLabelledWithItsDatum() {
    let app = launch(chart: "bar")
    XCTAssertTrue(
      app.staticTexts["377 × 228 points, 48 marks"].waitForExistence(timeout: 30),
      "the chart appeared"
    )

    let months = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug"]
    let values = ["28", "55", "43", "91", "81", "53", "19", "87"]

    for (month, value) in zip(months, values) {
      let element = app.buttons["\(month): \(value)"]
      XCTAssertTrue(
        element.exists,
        "a reader can reach \(month) and hear its value; exposed labels were: "
          + app.buttons.allElementsBoundByIndex.map { $0.label }.joined(separator: " | ")
      )
    }
  }

  /// The axes describe themselves, which is how a reader knows what the numbers mean.
  func testTheAxesAreDescribed() {
    let app = launch(chart: "bar")
    XCTAssertTrue(
      app.staticTexts["377 × 228 points, 48 marks"].waitForExistence(timeout: 30),
      "the chart appeared"
    )

    let vertical = app.buttons.matching(
      NSPredicate(format: "label BEGINSWITH 'Y-axis for a linear scale'")
    )
    let horizontal = app.buttons.matching(
      NSPredicate(format: "label BEGINSWITH 'X-axis for a discrete scale'")
    )
    XCTAssertGreaterThan(vertical.count, 0, "the value axis says what it is")
    XCTAssertGreaterThan(horizontal.count, 0, "the category axis says what it is")
  }

  /// The elements are activatable, so a reader can select a mark rather than only hear about it.
  func testAnElementCanBeActivatedToSelectItsMark() {
    let app = launch(chart: "bar")
    XCTAssertTrue(
      app.staticTexts["377 × 228 points, 48 marks"].waitForExistence(timeout: 30),
      "the chart appeared"
    )
    // Before: the screen says a tap has not happened.
    XCTAssertTrue(app.staticTexts["tap a mark"].exists)

    let element = app.buttons["Apr: 91"]
    // A real rectangle over the bar it describes, not the `(inf, inf, 0, 0)` that `accessibilityChildren`
    // produces — which is the difference between a chart a reader can explore by touch and one they can
    // only swipe through.
    XCTAssertTrue(element.frame.width > 1 && element.frame.height > 1, "the element has a frame")
    element.tap()

    // After: the chart reports what the activation selected, which means it reached the dataflow.
    XCTAssertTrue(
      app.staticTexts["selected 1 mark"].waitForExistence(timeout: 5),
      "activating an element selects the mark it stands for"
    )
  }
}
