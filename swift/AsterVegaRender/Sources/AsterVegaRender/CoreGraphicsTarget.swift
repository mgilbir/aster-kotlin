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
  private let drawText: ((DrawTextRun, Brush?, CGContext) -> Void)?

  public init(context: CGContext, drawText: ((DrawTextRun, Brush?, CGContext) -> Void)? = nil) {
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

  public mutating func rect(_ rect: Rect, corners: Corners, fill: Brush?, stroke: StrokePaint?) {
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

  public mutating func path(_ commands: [PathCommand], fill: Brush?, stroke: StrokePaint?) {
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

  public mutating func text(_ run: DrawTextRun, fill: Brush?, stroke: StrokePaint?) {
    drawText?(run, fill, context)
  }

  public mutating func image(url: String, in rect: Rect, opacity: Double) {
    // A URL is not an image, and fetching one is not a renderer's job; a caller that has the bytes
    // draws them, and one that does not leaves the space empty rather than blocking a frame on the
    // network.
  }

  // MARK: - Painting

  private func paint(path: CGPath, fill: Brush?, stroke: StrokePaint?) {
    if let fill {
      switch fill {
      case .solid(let colour):
        context.setFillColor(cg(colour))
        context.addPath(path)
        context.fillPath()
      case .linear, .radial:
        // CoreGraphics has no gradient *fill*: a gradient is drawn over a region, so the region is
        // the clip. Saved and restored around it, because a clip is the one piece of state here that
        // would otherwise leak into the next mark.
        context.saveGState()
        context.addPath(path)
        context.clip()
        draw(gradient: fill)
        context.restoreGState()
      }
    }
    guard let stroke else { return }
    // A gradient-stroked mark is drawn by clipping to the *stroked* outline and filling that, which
    // is the same trick: `replacePathWithStrokedPath` turns the pen into a region.
    if case .solid(let colour) = stroke.brush {
      context.setStrokeColor(cg(colour))
    } else {
      context.saveGState()
      context.addPath(path)
      applyStrokeStyle(stroke)
      context.replacePathWithStrokedPath()
      context.clip()
      draw(gradient: stroke.brush)
      context.restoreGState()
      return
    }
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

  /// Sets the pen up: width, caps, joins and dashes, without touching colour.
  private func applyStrokeStyle(_ stroke: StrokePaint) {
    context.setLineWidth(CGFloat(stroke.width))
    context.setLineCap(stroke.cap == .round ? .round : stroke.cap == .square ? .square : .butt)
    context.setLineJoin(stroke.join == .round ? .round : stroke.join == .bevel ? .bevel : .miter)
    context.setMiterLimit(CGFloat(stroke.miterLimit))
    if stroke.dash.isEmpty {
      context.setLineDash(phase: 0, lengths: [])
    } else {
      context.setLineDash(
        phase: CGFloat(stroke.dashOffset), lengths: stroke.dash.map { CGFloat($0) }
      )
    }
  }

  /// Draws a gradient across the current clip.
  ///
  /// `drawsBeforeStartLocation`/`drawsAfterEndLocation` are what make this match every other
  /// renderer: a gradient's stops describe the span between its two points, and the area outside that
  /// span takes the nearest stop's colour rather than nothing. Without them a bar whose gradient runs
  /// between two interior points would have transparent ends.
  private func draw(gradient brush: Brush) {
    let alpha = brush.alpha
    switch brush {
    case .solid:
      return
    case .linear(let from, let to, let stops, _):
      guard let gradient = cg(stops: stops, alpha: alpha) else { return }
      context.drawLinearGradient(
        gradient,
        start: cg(from),
        end: cg(to),
        options: [.drawsBeforeStartLocation, .drawsAfterEndLocation]
      )
    case .radial(let centre, let radius, let stops, _):
      guard let gradient = cg(stops: stops, alpha: alpha) else { return }
      context.drawRadialGradient(
        gradient,
        startCenter: cg(centre),
        startRadius: 0,
        endCenter: cg(centre),
        endRadius: CGFloat(radius),
        options: [.drawsBeforeStartLocation, .drawsAfterEndLocation]
      )
    }
  }

  private func cg(stops: [GradientStop], alpha: Double) -> CGGradient? {
    let colours = stops.map { stop -> CGColor in
      // The item's opacity is multiplied into each stop rather than set as a context alpha, because a
      // gradient is drawn inside a saved state whose alpha would also apply to the clip's edges.
      cg(
        Paint(
          red: stop.paint.red, green: stop.paint.green, blue: stop.paint.blue,
          alpha: stop.paint.alpha * alpha
        )
      )
    }
    return CGGradient(
      colorsSpace: Self.sRGB,
      colors: colours as CFArray,
      locations: stops.map { CGFloat($0.offset) }
    )
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
