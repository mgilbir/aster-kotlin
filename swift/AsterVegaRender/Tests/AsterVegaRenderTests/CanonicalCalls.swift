import AsterVega

@testable import AsterVegaRender

/// The sequence of draw calls a scene produces, written so that **two implementations can be
/// compared**.
///
/// The mirror of `CanonicalCalls.kt` in `vega-compose-multiplatform`, and the format is a contract
/// between the two: same fields, same order, same rounding, byte for byte. Its whole purpose is that
/// the goldens in `test-fixtures/scene-walk/` can be written by one walk and asserted by the other.
///
/// There are two walks over a scene and each is documented as emitting "the same calls in the same
/// order". Nothing checked it, and it cost a defect that shipped: this walk had no zero-opacity
/// guard, so on `label-overlap.vg.json` it emitted 43 text runs where the Compose walk emitted 19.
/// Both renderers' own tests passed throughout, each asserting about itself.
///
/// `RecordingTarget` cannot do the comparing. There is one on each side, their formats drifted the way
/// anything with two copies does, and aligning them would churn every assertion written against
/// either. So this is a third recorder whose only job is to be identical to its counterpart.
///
/// Every field is written, always, with no shorthand for a default: a recorder that omits what it
/// thinks is uninteresting cannot prove agreement about it. Numbers are three decimals with ties
/// rounded **away from zero**, spelled out rather than handed to `String(format:)` — C rounds a tie to
/// even and Java rounds it up, which would show up as a parity failure once in a thousand coordinates
/// and be unreadable.
struct CanonicalCalls: DrawTarget {

  private var lines: [String] = []
  private var depth = 0

  var text: String { lines.joined(separator: "\n") }

  mutating func beginGroup(clip: Rect?) {
    note("group clip=" + (clip.map { show($0) } ?? "-"))
    depth += 1
  }

  mutating func endGroup() {
    depth = max(0, depth - 1)
    note("end")
  }

  mutating func rect(
    _ rect: Rect, corners: AsterVegaRender.Corners, fill: Brush?, stroke: StrokePaint?,
    blend: SceneBlendMode
  ) {
    note(
      "rect \(show(rect)) corners=[\(num(corners.topLeft)),\(num(corners.topRight)),"
        + "\(num(corners.bottomRight)),\(num(corners.bottomLeft))]"
        + " fill=\(show(fill)) stroke=\(show(stroke)) blend=\(show(blend))"
    )
  }

  mutating func line(from: Point, to: Point, stroke: StrokePaint?, blend: SceneBlendMode) {
    note("line \(show(from))-\(show(to)) stroke=\(show(stroke)) blend=\(show(blend))")
  }

  mutating func path(
    _ commands: [AsterVegaRender.PathCommand], fill: Brush?, stroke: StrokePaint?,
    blend: SceneBlendMode
  ) {
    let written = commands.map { command -> String in
      switch command {
      case .move(let to): return "M\(show(to))"
      case .line(let to): return "L\(show(to))"
      case .cubic(let one, let two, let to): return "C\(show(one))\(show(two))\(show(to))"
      case .close: return "Z"
      }
    }
    .joined()
    note("path \(written) fill=\(show(fill)) stroke=\(show(stroke)) blend=\(show(blend))")
  }

  mutating func text(
    _ run: DrawTextRun, fill: Brush?, stroke: StrokePaint?, blend: SceneBlendMode
  ) {
    note(
      "text \(quoted(run.text)) origin=\(show(run.origin)) anchor=\(show(run.anchor))"
        + " ascent=\(num(run.ascent)) font=\(quoted(run.fontFamily)) size=\(num(run.fontSize))"
        + " weight=\(run.fontWeight) italic=\(flag(run.italic))"
        + " angle=\(num(run.angleDegrees)) spacing=\(num(run.letterSpacing))"
        + " fill=\(show(fill)) stroke=\(show(stroke)) blend=\(show(blend))"
    )
  }

  mutating func image(
    url: String,
    raster: DrawRaster?,
    in rect: Rect,
    fit: DrawImageFit,
    smooth: Bool,
    opacity: Double,
    blend: SceneBlendMode
  ) {
    note(
      "image url=\(quoted(url)) raster="
        + (raster.map { "\($0.width)x\($0.height)" } ?? "-")
        + " \(show(rect)) fit=\(fit.rawValue) smooth=\(flag(smooth)) opacity=\(num(opacity))"
        + " blend=\(show(blend))"
    )
  }

  private mutating func note(_ line: String) {
    lines.append(String(repeating: "  ", count: depth) + line)
  }

  private func flag(_ value: Bool) -> String { value ? "1" : "0" }

  /// The blend mode's own name, lower-cased, which is what the Kotlin recorder writes.
  ///
  /// Kotlin's enum entries come across as `SceneBlendMode` objects whose `name` is the Kotlin
  /// spelling — `COLOR_DODGE` — so lower-casing it gives one string on both sides.
  private func show(_ blend: SceneBlendMode) -> String { blend.name.lowercased() }

  private func show(_ point: Point) -> String { "(\(num(point.x)),\(num(point.y)))" }

  private func show(_ rect: Rect) -> String {
    "(\(num(rect.x)),\(num(rect.y)),\(num(rect.width)),\(num(rect.height)))"
  }

  private func show(_ brush: Brush?) -> String {
    switch brush {
    case nil: return "-"
    case .solid(let paint): return show(paint)
    case .linear(let from, let to, let stops, _):
      return "linear\(show(from))-\(show(to))[\(show(stops))]"
    case .radial(let centre, let radius, let stops, _):
      return "radial\(show(centre))r=\(num(radius))[\(show(stops))]"
    }
  }

  private func show(_ stops: [AsterVegaRender.GradientStop]) -> String {
    stops.map { "\(num($0.offset))=\(show($0.paint))" }.joined(separator: ",")
  }

  private func show(_ stroke: StrokePaint?) -> String {
    guard let stroke else { return "-" }
    return "\(show(stroke.brush)) w=\(num(stroke.width)) cap=\(stroke.cap.rawValue)"
      + " join=\(stroke.join.rawValue) miter=\(num(stroke.miterLimit))"
      + " dash=[\(stroke.dash.map(num).joined(separator: ","))]"
      + " dashOffset=\(num(stroke.dashOffset))"
  }

  private func show(_ paint: Paint) -> String {
    "#"
      + [paint.red, paint.green, paint.blue, paint.alpha].map { channel -> String in
        let byte = Int(halfAwayFromZero(min(max(channel, 0), 1) * 255))
        return String(byte, radix: 16, uppercase: false).count == 1
          ? "0" + String(byte, radix: 16) : String(byte, radix: 16)
      }
      .joined()
  }

  /// JSON-ish quoting, so a label containing a quote or a newline cannot break a line.
  private func quoted(_ value: String) -> String {
    var out = "\""
    for character in value {
      switch character {
      case "\"": out += "\\\""
      case "\\": out += "\\\\"
      case "\n": out += "\\n"
      case "\r": out += "\\r"
      case "\t": out += "\\t"
      default: out.append(character)
      }
    }
    return out + "\""
  }

  /// Three decimals, ties away from zero, `-0` written as `0`.
  private func num(_ value: Double) -> String {
    if value.isNaN { return "nan" }
    if value.isInfinite { return value > 0 ? "inf" : "-inf" }
    let thousandths = halfAwayFromZero(value * 1000)
    let magnitude = abs(thousandths)
    let whole = Int64(magnitude / 1000)
    let fraction = Int64(magnitude.truncatingRemainder(dividingBy: 1000))
    let sign = thousandths < 0 && (whole != 0 || fraction != 0) ? "-" : ""
    var digits = String(fraction)
    while digits.count < 3 { digits = "0" + digits }
    return "\(sign)\(whole).\(digits)"
  }

  private func halfAwayFromZero(_ value: Double) -> Double {
    value >= 0 ? (value + 0.5).rounded(.down) : -((-value + 0.5).rounded(.down))
  }
}
