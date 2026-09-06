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
///
/// **Main-actor isolated**, because `XCUIApplication` and every query on it are.
///
/// `XCTestCase` methods are nonisolated, so under Swift 6 each of the thirty XCUI calls below was a
/// main-actor member reached from a nonisolated context. The compiler said so thirty times and CI
/// stayed green, since they are warnings rather than errors — and they will not stay warnings: this
/// is the diagnostic that becomes an error as the concurrency rules finish landing.
///
/// One annotation on the class rather than thirty on the calls, and on the class rather than on each
/// test, because the isolation is a property of what this target *is*: a UI test drives an
/// application through its interface, and that interface lives on the main actor.
///
/// Not reproducible on every toolchain, which is worth knowing before deleting it. Xcode 26.6 /
/// Swift 6.3.3 emits none of these warnings; the CI runner's older Swift emits all thirty. Both are
/// right about their own rules, and the annotation is correct under either.
@MainActor
final class AccessibilityUITests: XCTestCase {

  private func launch(chart: String) -> XCUIApplication {
    let app = XCUIApplication()
    app.launchArguments = ["-chart", chart]
    app.launch()
    return app
  }

  /// Waits for the chart to be on screen, without asserting on its **size**.
  ///
  /// This used to wait for the whole status line — `"377 × 228 points, 48 marks"` — and that made
  /// the precondition of all three tests depend on a SwiftUI layout metric. It went stale: on iOS
  /// 26.5 the same chart reports `378 × 230`, one point wider and two taller, so every test failed
  /// on "the chart appeared" having never reached the accessibility labels it exists to check.
  ///
  /// The mark count is the engine's answer and is stable across devices; the point size is the
  /// device's. Matching on the count says "the chart rendered" without pinning anything a phone
  /// model can change.
  private func waitForChart(_ app: XCUIApplication, marks: Int) -> Bool {
    let status = app.staticTexts.matching(
      NSPredicate(format: "label ENDSWITH %@", "points, \(marks) marks")
    )
    return status.firstMatch.waitForExistence(timeout: 60)
  }

  /// Every bar is its own element, labelled with the datum a reader wants to hear.
  ///
  /// The `bar` fixture is eight months, and the engine joins each mark's label to its value — so the labels
  /// are "Jan: 28", "Feb: 55" and so on. Nothing but the scene's own accessibility descriptors produces
  /// those, which is what makes this test about the wiring rather than about the chrome around it.
  func testEveryBarIsItsOwnElementLabelledWithItsDatum() {
    let app = launch(chart: "bar")
    XCTAssertTrue(
      waitForChart(app, marks: 48),
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
      waitForChart(app, marks: 48),
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
      waitForChart(app, marks: 48),
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
