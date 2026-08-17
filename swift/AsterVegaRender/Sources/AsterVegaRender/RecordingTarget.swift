import AsterVega

/// A ``DrawTarget`` that writes down what it was asked to draw instead of drawing it.
///
/// This is how the Swift renderer is tested without a simulator and without pixels. What a renderer
/// can get wrong is which primitives it emits, with what geometry, in what order — a recording says
/// exactly that, and it says it as text a failure message can print.
///
/// Numbers are rounded when described, because a comparison that fails on the last bit of a double
/// is a comparison nobody can read.
public struct RecordingTarget: DrawTarget {

  public private(set) var calls: [String] = []
  private var depth = 0

  public init() {}

  private mutating func note(_ text: String) {
    calls.append(String(repeating: "  ", count: depth) + text)
  }

  public mutating func beginGroup(clip: Rect?) {
    note("group" + (clip.map { " clip \(show($0))" } ?? ""))
    depth += 1
  }

  public mutating func endGroup() {
    depth = max(0, depth - 1)
  }

  public mutating func rect(_ rect: Rect, corners: Corners, fill: Brush?, stroke: StrokePaint?) {
    var text = "rect \(show(rect))"
    if !corners.isSquare { text += " corners \(show(corners))" }
    note(text + paints(fill, stroke))
  }

  public mutating func line(from: Point, to: Point, stroke: StrokePaint?) {
    note("line \(show(from)) -> \(show(to))" + paints(nil, stroke))
  }

  public mutating func path(_ commands: [PathCommand], fill: Brush?, stroke: StrokePaint?) {
    note("path \(commands.count) commands \(summary(commands))" + paints(fill, stroke))
  }

  public mutating func text(_ run: DrawTextRun, fill: Brush?, stroke: StrokePaint?) {
    var text = "text \(quoted(run.text)) at \(show(run.origin))"
    text += " \(run.fontFamily) \(show(run.fontSize)) w\(run.fontWeight)"
    if run.italic { text += " italic" }
    if run.angleDegrees != 0 { text += " rotated \(show(run.angleDegrees))" }
    note(text + paints(fill, stroke))
  }

  public mutating func image(url: String, in rect: Rect, opacity: Double) {
    note("image \(quoted(url)) in \(show(rect)) opacity \(show(opacity))")
  }

  // MARK: - Description

  private func paints(_ fill: Brush?, _ stroke: StrokePaint?) -> String {
    var text = ""
    if let fill { text += " fill \(show(fill))" }
    if let stroke {
      text += " stroke \(show(stroke.brush)) w\(show(stroke.width))"
      if stroke.cap != .butt { text += " \(stroke.cap.rawValue)" }
      if stroke.join != .miter { text += " \(stroke.join.rawValue)" }
      if !stroke.dash.isEmpty { text += " dash[\(stroke.dash.map(show).joined(separator: ","))]" }
    }
    return text
  }

  /// The first and last command, which is enough to tell one shape from another in a failure.
  private func summary(_ commands: [PathCommand]) -> String {
    guard let first = commands.first else { return "(empty)" }
    let last = commands.count > 1 ? " … \(show(commands[commands.count - 1]))" : ""
    return "\(show(first))\(last)"
  }

  private func show(_ command: PathCommand) -> String {
    switch command {
    case .move(let to): return "M\(show(to))"
    case .line(let to): return "L\(show(to))"
    case .cubic(_, _, let to): return "C\(show(to))"
    case .close: return "Z"
    }
  }

  private func show(_ value: Double) -> String {
    let rounded = (value * 1000).rounded() / 1000
    return rounded == rounded.rounded() ? String(Int(rounded)) : String(rounded)
  }

  private func show(_ point: Point) -> String { "(\(show(point.x)),\(show(point.y)))" }

  private func show(_ rect: Rect) -> String {
    "(\(show(rect.x)),\(show(rect.y)) \(show(rect.width))x\(show(rect.height)))"
  }

  private func show(_ corners: Corners) -> String {
    "\(show(corners.topLeft))/\(show(corners.topRight))/\(show(corners.bottomRight))/\(show(corners.bottomLeft))"
  }

  /// A brush, described the way the Compose renderer's recorder describes one, so the two
  /// renderers' recordings can be read side by side.
  private func show(_ brush: Brush) -> String {
    switch brush {
    case .solid(let paint):
      return show(paint)
    case .linear(let from, let to, let stops, let alpha):
      return "linear \(show(from))->\(show(to)) \(stops.count) stops"
        + (alpha < 1 ? "@\(show(alpha))" : "")
    case .radial(let centre, let radius, let stops, let alpha):
      return "radial \(show(centre)) r\(show(radius)) \(stops.count) stops"
        + (alpha < 1 ? "@\(show(alpha))" : "")
    }
  }

  private func show(_ paint: Paint) -> String {
    let byte = { (value: Double) in Int((value * 255).rounded()) }
    let alpha = paint.alpha == 1 ? "" : " a\(show(paint.alpha))"
    return "#\(hex(byte(paint.red)))\(hex(byte(paint.green)))\(hex(byte(paint.blue)))\(alpha)"
  }

  private func hex(_ value: Int) -> String {
    String(format: "%02x", max(0, min(255, value)))
  }

  private func quoted(_ text: String) -> String { "\"\(text)\"" }
}
