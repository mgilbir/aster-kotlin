#if canImport(CoreGraphics)
import CoreGraphics
import AsterVega

/// Draws into a `CGContext` — the renderer a device actually uses.
///
/// Every coordinate arriving here is already in surface space, because ``SceneWalk`` composes the
/// transforms itself. So this type holds no matrix stack: the only state it keeps is the graphics
/// state it pushes for a group's clip and opacity, which is also the only state it has to unwind.
public struct CoreGraphicsTarget: DrawTarget {

  private let context: CGContext
  /// The text drawing is handed out, because measuring and shaping text is the one thing a scene
  /// cannot fully precompute for a foreign surface: the engine positions a run, and the platform
  /// draws the glyphs. A caller that has CoreText wires it in; one that does not gets no text rather
  /// than wrong text.
  private let drawText: ((TextRun, Paint?, CGContext) -> Void)?

  public init(context: CGContext, drawText: ((TextRun, Paint?, CGContext) -> Void)? = nil) {
    self.context = context
    self.drawText = drawText
  }

  public mutating func beginGroup(clip: Rect?) {
    context.saveGState()
    if let clip { context.clip(to: cg(clip)) }
    // No `setAlpha`: opacity arrives in each paint's alpha, and a group-wide alpha here would apply
    // it a second time to every child.
  }

  public mutating func endGroup() {
    context.restoreGState()
  }

  public mutating func rect(_ rect: Rect, corners: Corners, fill: Paint?, stroke: StrokePaint?) {
    let path = corners.isSquare ? CGPath(rect: cg(rect), transform: nil) : rounded(rect, corners)
    paint(path: path, fill: fill, stroke: stroke)
  }

  public mutating func line(from: Point, to: Point, stroke: StrokePaint?) {
    guard let stroke else { return }
    let path = CGMutablePath()
    path.move(to: cg(from))
    path.addLine(to: cg(to))
    paint(path: path, fill: nil, stroke: stroke)
  }

  public mutating func path(_ commands: [PathCommand], fill: Paint?, stroke: StrokePaint?) {
    let path = CGMutablePath()
    for command in commands {
      switch command {
      case .move(let to): path.move(to: cg(to))
      case .line(let to): path.addLine(to: cg(to))
      case .cubic(let one, let two, let to):
        path.addCurve(to: cg(to), control1: cg(one), control2: cg(two))
      case .close: path.closeSubpath()
      }
    }
    paint(path: path, fill: fill, stroke: stroke)
  }

  public mutating func text(_ run: TextRun, fill: Paint?, stroke: StrokePaint?) {
    drawText?(run, fill, context)
  }

  public mutating func image(url: String, in rect: Rect, opacity: Double) {
    // A URL is not an image, and fetching one is not a renderer's job; a caller that has the bytes
    // draws them, and one that does not leaves the space empty rather than blocking a frame on the
    // network.
  }

  // MARK: - Painting

  private func paint(path: CGPath, fill: Paint?, stroke: StrokePaint?) {
    if let fill {
      context.setFillColor(cg(fill))
      context.addPath(path)
      context.fillPath()
    }
    guard let stroke else { return }
    context.setStrokeColor(cg(stroke.paint))
    context.setLineWidth(CGFloat(stroke.width))
    context.setLineCap(
      stroke.cap == .round ? .round : stroke.cap == .square ? .square : .butt
    )
    context.setLineJoin(
      stroke.join == .round ? .round : stroke.join == .bevel ? .bevel : .miter
    )
    context.setMiterLimit(CGFloat(stroke.miterLimit))
    if stroke.dash.isEmpty {
      context.setLineDash(phase: 0, lengths: [])
    } else {
      context.setLineDash(
        phase: CGFloat(stroke.dashOffset), lengths: stroke.dash.map { CGFloat($0) }
      )
    }
    context.addPath(path)
    context.strokePath()
  }

  /// A rectangle with four independent radii, each clamped so opposite corners cannot overlap.
  private func rounded(_ rect: Rect, _ corners: Corners) -> CGPath {
    let limit = min(rect.width, rect.height) / 2
    let tl = min(corners.topLeft, limit), tr = min(corners.topRight, limit)
    let br = min(corners.bottomRight, limit), bl = min(corners.bottomLeft, limit)
    let box = cg(rect)
    let path = CGMutablePath()
    path.move(to: CGPoint(x: box.minX + tl, y: box.minY))
    path.addLine(to: CGPoint(x: box.maxX - tr, y: box.minY))
    path.addArc(tangent1End: CGPoint(x: box.maxX, y: box.minY),
                tangent2End: CGPoint(x: box.maxX, y: box.minY + tr), radius: tr)
    path.addLine(to: CGPoint(x: box.maxX, y: box.maxY - br))
    path.addArc(tangent1End: CGPoint(x: box.maxX, y: box.maxY),
                tangent2End: CGPoint(x: box.maxX - br, y: box.maxY), radius: br)
    path.addLine(to: CGPoint(x: box.minX + bl, y: box.maxY))
    path.addArc(tangent1End: CGPoint(x: box.minX, y: box.maxY),
                tangent2End: CGPoint(x: box.minX, y: box.maxY - bl), radius: bl)
    path.addLine(to: CGPoint(x: box.minX, y: box.minY + tl))
    path.addArc(tangent1End: CGPoint(x: box.minX, y: box.minY),
                tangent2End: CGPoint(x: box.minX + tl, y: box.minY), radius: tl)
    path.closeSubpath()
    return path
  }

  private func cg(_ point: Point) -> CGPoint { CGPoint(x: point.x, y: point.y) }

  private func cg(_ rect: Rect) -> CGRect {
    CGRect(x: rect.x, y: rect.y, width: rect.width, height: rect.height)
  }

  /// The colour space a scene's colours are actually in.
  ///
  /// A specification's colours are CSS colours, and CSS is sRGB. `CGColor(red:green:blue:alpha:)`
  /// does *not* mean that — it builds the colour in the generic RGB space, which the context then
  /// converts on the way in, and `steelblue` arrived on an sRGB surface as rgb(86,149,193) instead of
  /// rgb(70,130,180). Every colour was quietly wrong, by an amount too small to notice by eye and
  /// large enough to be a different colour; naming the space is the fix.
  private static let sRGB = CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()

  private func cg(_ paint: Paint) -> CGColor {
    CGColor(
      colorSpace: Self.sRGB,
      components: [
        CGFloat(paint.red), CGFloat(paint.green), CGFloat(paint.blue), CGFloat(paint.alpha),
      ]
    ) ?? CGColor(gray: 0, alpha: CGFloat(paint.alpha))
  }
}
#endif
