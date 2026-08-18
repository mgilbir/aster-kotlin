import AsterVega
import Foundation

/// The launch arguments this demo answers, and the wording it puts on what a touch found.
///
/// All of it is **the demo's**, which is the point of the file existing. `ChartSession` and
/// `VegaChartView` moved into `AsterVegaRender` so an app does not have to own them, and these three
/// things stayed behind because a library has no business reading `UserDefaults` or producing English:
///
///   `-signal name=value`  presets a bound signal, so a screenshot of a chart *under* a control can be
///                         scripted — the simulator can launch an app but cannot drag a slider.
///   `-tap x,y`            taps the chart at a point in **scene** coordinates once it is on screen,
///                         which is how a screenshot after a touch is scripted.
///
/// A library reading the first would let any host's stray default change a chart, and a library
/// producing the second would be producing user-facing text in one language.
enum DemoLaunch {

  /// `-signal DataPoints=200,Series=alpha`, parsed.
  ///
  /// A number if it reads as one, a boolean if it reads as one, else a string — which is the same order
  /// a specification's own `value` is read in.
  static func presetSignals() -> [String: VegaValue] {
    guard let argument = UserDefaults.standard.string(forKey: "signal") else { return [:] }
    let signals = ForeignSignals.shared
    var result: [String: VegaValue] = [:]
    for pair in argument.split(separator: ",") {
      let halves = pair.split(separator: "=", maxSplits: 1)
      guard halves.count == 2 else { continue }
      let name = String(halves[0]).trimmingCharacters(in: .whitespaces)
      let text = String(halves[1]).trimmingCharacters(in: .whitespaces)
      if let number = Double(text) {
        result[name] = signals.ofNumber(value: number)
      } else if text == "true" || text == "false" {
        result[name] = signals.ofBoolean(value: text == "true")
      } else {
        result[name] = signals.ofString(value: text)
      }
    }
    return result
  }

  /// `-tap x,y` as a scene point, or nil when the argument is absent or malformed.
  static func launchTapPoint() -> Point? {
    guard let argument = UserDefaults.standard.string(forKey: "tap") else { return nil }
    let halves = argument.split(separator: ",")
    guard halves.count == 2, let x = Double(halves[0]), let y = Double(halves[1]) else { return nil }
    return Point(x: x, y: y)
  }

  /// The launch tap, sent through the same inversion a finger goes through.
  ///
  /// Deliberately the long way round — scene point to view location, then back through the placement —
  /// so what it exercises is the arithmetic a real tap uses rather than a shortcut that could pass while
  /// taps miss.
  @MainActor
  static func performLaunchTap(on session: ChartSession, placement: ChartPlacement) {
    guard let scenePoint = launchTapPoint() else { return }
    let location = CGPoint(
      x: CGFloat(scenePoint.x) * CGFloat(placement.scale) + placement.left,
      y: CGFloat(scenePoint.y) * CGFloat(placement.scale) + placement.top
    )
    session.tap(at: placement.scenePoint(of: location))
  }

  /// What a touch found, in words. The demo's wording, not the library's.
  static func describe(_ outcome: ChartSession.TouchOutcome) -> String {
    switch outcome {
    case .tooltip(let text):
      return "tooltip: \(text)"
    case .selected(let count):
      return "selected \(count) mark\(count == 1 ? "" : "s")"
    case .nothing(let x, let y):
      return String(format: "nothing at (%.0f, %.0f)", x, y)
    }
  }

  /// Which grammar the text was taken for, in words.
  ///
  /// Said rather than inferred: someone pasting a chart has pasted a chart, not a dialect, so the
  /// decision is made for them — and then shown, because a Vega-Lite specification read as Vega fails
  /// for a reason that reads like nonsense.
  static func describe(_ grammar: ChartSession.Grammar) -> String {
    switch grammar {
    case .vega: return "read as Vega"
    case .vegaLite: return "read as Vega-Lite and compiled"
    }
  }
}
