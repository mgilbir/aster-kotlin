import AsterVega

/// Walks a compiled scene and tells a ``DrawTarget`` what to draw.
///
/// This is the whole of the Swift renderer's logic, and deliberately the only place any of it lives:
/// the CoreGraphics target and the recording target used by the tests see the same sequence of
/// calls, so a test that passes is a statement about what a device will draw rather than about a
/// parallel implementation.
///
/// The engine has already done everything that needs a chart's semantics — scales, layout, text
/// measurement, arcs reduced to cubics, per-corner radii resolved. What is left is arithmetic:
/// compose each group's transform and hand over primitives.
///
/// Opacity is **per item**, and deliberately not inherited. A group's own opacity paints its own
/// background and nothing else: upstream's canvas renderer saves the graphics state, translates and
/// clips for a group, and never touches `globalAlpha` on the way in, so each child is drawn with its
/// own opacity alone. Propagating it down here instead — the obvious reading — drew a half-opaque
/// group's half-opaque child at a quarter, and a pixel test is what said so.
public struct SceneWalk {

  public init() {}

  /// Draws `scene` into `target`.
  /// Draws `scene` into `target`.
  ///
  /// **This recurses once per group.** A scene from `SpecCompiler` is bounded — the compiler refuses
  /// a mark tree deeper than its own `MAX_GROUP_DEPTH`, so no document a reader pastes can reach the
  /// limit here. A scene a *host* assembles from `GroupNode(...)` by hand has no such bound, and a
  /// stack overflow in Swift is a crash rather than something you can catch. Keep a hand-built tree
  /// shallower than a chart's, which is a handful of levels.
  public func draw<T: DrawTarget>(scene: Scene, into target: inout T) {
    // A scene's own background is not a node — nothing in the tree paints it — so a renderer that
    // only walked the tree would leave a transparent chart on whatever was behind it.
    if let background = scene.background {
      target.rect(
        Rect(x: 0, y: 0, width: scene.width, height: scene.height),
        corners: .square,
        fill: .solid(
          Paint(
            red: background.red, green: background.green,
            blue: background.blue, alpha: background.alpha
          )
        ),
        stroke: nil,
        blend: SceneBlendMode.normal
      )
    }
    walk(node: scene.root, transform: .identity, into: &target)
  }

  // MARK: - The traversal

  private func walk<T: DrawTarget>(
    node: any SceneNode,
    transform: Affine,
    into target: inout T
  ) {
    guard node.visible else { return }
    // This item's own opacity, multiplied into this item's own paints below.
    let own = node.opacity
    // **Nothing paints anything at zero opacity**, so leaving early is the same picture drawn faster.
    // A group is the exception, as it is in the Compose walk: a transparent group is not an invisible
    // one, and its children carry their own opacity.
    //
    // This line is the whole of the divergence behind a dense temporal axis coming out unreadable on
    // this renderer. The Compose walk has had it from the start with this same comment; this one had
    // only the `visible` check. An axis hides an overlapping label by setting its **opacity to zero**
    // rather than removing it — `AxisBuilder` says so, so that the mark count does not change with
    // the chart's width — so the node arrived here, `brush` answered nil for it, and
    // `CoreTextDrawing` read that nil as a paint it could not express and painted black. Measured on
    // `label-overlap.vg.json`: 43 labels of which 24 are hidden, and this renderer drew all 43 where
    // Compose drew the 19.
    if own <= 0, !(node is GroupNode) { return }

    switch ForeignRenderersKt.foreignKind(node) {
    case "group":
      guard let group = node as? GroupNode else { return }
      let local = transform.concatenating(Affine(group.transform))
      // A group's clip is in its *own* space, so it is mapped through the transform that has just
      // been composed rather than the one it was reached with.
      let clip = group.clip.map { local.apply(rect: $0) }
      target.beginGroup(clip: clip)
      // Its own paint, if any — a group is how an axis or a facet cell draws its panel, and this is
      // the only thing a group's opacity applies to.
      //
      // `paintRect` and `effectiveStrokeOffset` are the scene node's own answers rather than
      // `size`: a group stroked at about one unit is nudged half a unit so its outline lands on a
      // pixel boundary instead of straddling one, which is upstream's rule and not a renderer's.
      let panel = own > 0 ? group.paintRect : nil
      if let panelBox = panel {
        let nudge = group.effectiveStrokeOffset
        let nudged = RectD(
          left: panelBox.left + nudge, top: panelBox.top + nudge,
          right: panelBox.right + nudge, bottom: panelBox.bottom + nudge
        )
        let box = local.apply(rect: nudged)
        // `strokeForeground` puts the outline over the children rather than under them; the fill
        // still goes underneath.
        target.rect(
          box,
          corners: corners(of: group),
          fill: brush(group.fill, opacity: own, bounds: panelBox, through: local),
          stroke: group.strokeForeground
            ? nil : stroke(group.stroke, opacity: own, bounds: panelBox, through: local),
          blend: group.blendMode
        )
      }
      // Drawn whatever the group's own opacity is — a transparent group is not an invisible one.
      //
      // `paintOrder`, not `children`: an item's `zindex` reorders what is painted, and only the
      // SVG renderer was applying it, so a `zindex` raised a mark in the export and nowhere else.
      // The Compose walk reorders identically, which is what keeps the scene-walk goldens
      // byte-identical.
      for child in SceneKt.paintOrder(children: group.children) {
        walk(node: child, transform: local, into: &target)
      }
      if let panelBox = panel, group.strokeForeground {
        let nudge = group.effectiveStrokeOffset
        let nudged = RectD(
          left: panelBox.left + nudge, top: panelBox.top + nudge,
          right: panelBox.right + nudge, bottom: panelBox.bottom + nudge
        )
        target.rect(
          local.apply(rect: nudged),
          corners: corners(of: group),
          fill: nil,
          stroke: stroke(group.stroke, opacity: own, bounds: panelBox, through: local),
          blend: group.blendMode
        )
      }
      target.endGroup()

    case "rect":
      guard let rect = node as? RectNode else { return }
      let local = transform.concatenating(Affine(rect.transform))
      target.rect(
        local.apply(
          rect: RectD(
            left: rect.x, top: rect.y,
            right: rect.x + rect.width, bottom: rect.y + rect.height
          )
        ),
        corners: corners(of: rect),
        fill: brush(rect.fill, opacity: own, bounds: rect.bounds, through: local),
        stroke: stroke(rect.stroke, opacity: own, bounds: rect.bounds, through: local),
        blend: rect.blendMode
      )

    case "rule":
      guard let rule = node as? RuleNode else { return }
      let local = transform.concatenating(Affine(rule.transform))
      target.line(
        from: local.apply(point: Point(x: rule.x1, y: rule.y1)),
        to: local.apply(point: Point(x: rule.x2, y: rule.y2)),
        stroke: stroke(rule.stroke, opacity: own, bounds: rule.bounds, through: local),
        blend: rule.blendMode
      )

    case "path":
      guard let path = node as? PathNode, !path.absent else { return }
      let local = transform.concatenating(Affine(path.transform))
      target.path(
        commands(of: path.path, through: local),
        fill: brush(path.fill, opacity: own, bounds: path.bounds, through: local),
        stroke: stroke(path.stroke, opacity: own, bounds: path.bounds, through: local),
        blend: path.blendMode
      )

    case "symbol":
      guard let symbol = node as? SymbolNode else { return }
      // A symbol's shape is already a path — the engine turns a circle, a cross or a custom SVG
      // string into one — so there is no shape vocabulary for a renderer to reimplement.
      let local = transform.concatenating(Affine(symbol.transform))
      target.path(
        commands(of: symbol.outline, through: local),
        fill: brush(symbol.fill, opacity: own, bounds: symbol.bounds, through: local),
        stroke: stroke(symbol.stroke, opacity: own, bounds: symbol.bounds, through: local),
        blend: symbol.blendMode
      )

    case "text":
      guard let text = node as? TextNode, !text.absent else { return }
      let local = transform.concatenating(Affine(text.transform))
      let layout = text.layout
      let run = layout.run
      let style = run.style
      let metrics = layout.metrics
      let fill = brush(text.fill, opacity: own, bounds: text.bounds, through: local)
      let stroke = stroke(text.stroke, opacity: own, bounds: text.bounds, through: local)
      // **Nothing to paint means nothing to draw**, and it has to be decided here.
      //
      // An axis hides a label that would overlap its neighbour by setting the label's *opacity to
      // zero* rather than by removing it — `AxisBuilder` says so outright, so that the mark count
      // does not change with the chart's width. `brush` then answers nil, as it does for a mark with
      // no fill at all; and at the other end of the call `CoreTextDrawing` read a nil brush as a
      // paint it could not express and fell back to black, on the reasoning that black beats an
      // invisible label. So one nil meant two opposite things and every label the overlap pass had
      // hidden was drawn at full strength. Measured on a committed document — a temporal axis with a
      // twenty-day tick interval at 361 units — fourteen labels of which ten had opacity zero: the
      // Compose renderer drew the four, this one drew all fourteen, each about twice as wide as the
      // gap between them.
      //
      // Both ends are fixed: `CoreTextDrawing` no longer paints a nil brush, and the run is not
      // handed over at all. Two, because either alone leaves the trap in place for the next caller.
      if fill == nil && stroke == nil { return }

      // Where the *box* sits relative to the anchor. These are `textBounds` in `vega-scene`, which is
      // what the engine used to compute this node's own bounds — so the glyphs land inside the space the
      // layout reserved rather than beside it.
      let top: Double
      switch run.baseline {
      case TextBaseline.top, TextBaseline.lineTop: top = 0
      case TextBaseline.middle: top = -metrics.height / 2
      case TextBaseline.bottom, TextBaseline.lineBottom: top = -metrics.height
      default: top = -metrics.ascent  // alphabetic
      }

      // One call per line. Every line is anchored at the same point and aligned by *its own* width,
      // which is what an SVG `tspan` with an absolute `x` does.
      for line in layout.lines {
        let leading: Double
        switch run.align {
        case TextAlign.center: leading = -line.width / 2
        case TextAlign.right: leading = -line.width
        default: leading = 0  // left
        }
        let anchor = local.apply(point: Point(x: text.x, y: text.y))
        let pen = local.apply(
          point: Point(
            x: text.x + leading,
            // The baseline of this line: the box's top, down by the ascent, then by the stack.
            y: text.y + top + metrics.ascent + line.baselineY
          )
        )
        target.text(
          DrawTextRun(
            text: line.text,
            origin: pen,
            anchor: anchor,
            ascent: metrics.ascent,
            fontFamily: style.fontFamily,
            fontSize: style.fontSize,
            fontWeight: Int(style.fontWeight),
            italic: style.fontStyle == FontStyle.italic,
            angleDegrees: text.angleDegrees,
            // Carried through, because the engine **measured** with it: `CoreTextTextEngine` applies
            // the same style's `letterSpacing`, and until this the drawing did not — so a spaced
            // label sat in a box reserved for spaced glyphs and was painted unspaced.
            letterSpacing: style.letterSpacing
          ),
          fill: fill,
          stroke: stroke,
          blend: text.blendMode
        )
      }

    case "image":
      guard let image = node as? ImageNode else { return }
      let local = transform.concatenating(Affine(image.transform))
      // `rect` rather than x/y/width/height: the node has already applied `align` and `baseline` to
      // produce it, and using the raw channels drew every non-default alignment in the wrong place —
      // the same mistake text was making before its own fix.
      target.image(
        url: image.url,
        raster: image.raster.map { DrawRaster(image: $0) },
        in: local.apply(rect: image.rect),
        fit: image.fit == ImageFit.contain ? .contain : .fill,
        smooth: image.smooth,
        opacity: own,
        blend: image.blendMode
      )

    default:
      // A node kind this renderer has not been taught. Silence would draw a chart missing a mark
      // with nothing to say why, so it is worth being loud in a debug build.
      assertionFailure("unhandled scene node kind: \(ForeignRenderersKt.foreignKind(node))")
    }
  }

  // MARK: - Reading the scene

  private func commands(of path: PathData, through transform: Affine) -> [PathCommand] {
    path.commands.map { command in
      let reader = ForeignPath.shared
      let end = transform.apply(point: Point(x: reader.x(command: command), y: reader.y(command: command)))
      switch reader.kind(command: command) {
      case "move": return .move(to: end)
      case "line": return .line(to: end)
      case "cubic":
        return .cubic(
          control1: transform.apply(
            point: Point(x: reader.x1(command: command), y: reader.y1(command: command))
          ),
          control2: transform.apply(
            point: Point(x: reader.x2(command: command), y: reader.y2(command: command))
          ),
          to: end
        )
      default: return .close
      }
    }
  }

  private func corners(of node: RectNode) -> Corners {
    let all = node.cornerRadius
    return Corners(
      topLeft: node.cornerRadiusTopLeft?.doubleValue ?? all,
      topRight: node.cornerRadiusTopRight?.doubleValue ?? all,
      bottomRight: node.cornerRadiusBottomRight?.doubleValue ?? all,
      bottomLeft: node.cornerRadiusBottomLeft?.doubleValue ?? all
    )
  }

  private func corners(of node: GroupNode) -> Corners {
    let all = node.cornerRadius
    return Corners(
      topLeft: node.cornerRadiusTopLeft?.doubleValue ?? all,
      topRight: node.cornerRadiusTopRight?.doubleValue ?? all,
      bottomRight: node.cornerRadiusBottomRight?.doubleValue ?? all,
      bottomLeft: node.cornerRadiusBottomLeft?.doubleValue ?? all
    )
  }

  /// A fill as a brush, with the item's own opacity multiplied in, or nil when it paints nothing.
  ///
  /// `ForeignPaint` answers the solid case — the same function the Compose renderer calls, so there is
  /// one description of "what colour is this" rather than two that could drift.
  private func brush(
    _ fill: Fill?,
    opacity: Double,
    bounds: RectD,
    through transform: Affine
  ) -> Brush? {
    guard let fill else { return nil }
    let overall = opacity * fill.opacity
    if overall <= 0 { return nil }
    if let colour = ForeignPaint.shared.solidFill(fill: fill) {
      let solid = Paint(
        red: colour.red, green: colour.green, blue: colour.blue,
        alpha: colour.alpha * opacity
      )
      return solid.alpha > 0 ? .solid(solid) : nil
    }
    return gradient(fill.paint, alpha: overall, bounds: bounds, through: transform)
  }

  private func stroke(
    _ stroke: Stroke?,
    opacity: Double,
    bounds: RectD,
    through transform: Affine
  ) -> StrokePaint? {
    guard let stroke, stroke.width > 0 else { return nil }
    let overall = opacity * stroke.opacity
    if overall <= 0 { return nil }

    let brush: Brush
    if let colour = ForeignPaint.shared.solidStroke(stroke: stroke) {
      let solid = Paint(
        red: colour.red, green: colour.green, blue: colour.blue,
        alpha: colour.alpha * opacity
      )
      guard solid.alpha > 0 else { return nil }
      brush = .solid(solid)
    } else if let gradient = gradient(
      stroke.paint, alpha: overall, bounds: bounds, through: transform
    ) {
      brush = gradient
    } else {
      return nil
    }

    // A transform scales a stroke's width as well as its geometry, and the walk has already applied
    // the transform to the geometry — so the width has to follow it here or a scaled chart draws its
    // hairlines at their unscaled thickness.
    let scale = transform.averageScale
    return StrokePaint(
      brush: brush,
      width: stroke.width * scale,
      cap: cap(stroke.cap),
      join: join(stroke.join),
      miterLimit: stroke.miterLimit,
      dash: stroke.dashArray.map { $0.doubleValue * scale },
      dashOffset: stroke.dashOffset * scale
    )
  }

  /// A gradient resolved against the item it paints.
  ///
  /// Vega writes a gradient's coordinates as fractions of the item's own bounds — `x1: 0, x2: 1` is
  /// left edge to right edge whatever the mark's size — so they are multiplied through those bounds
  /// here and the target receives absolute surface points.
  private func gradient(
    _ paint: ScenePaint?,
    alpha: Double,
    bounds: RectD,
    through transform: Affine
  ) -> Brush? {
    let reader = ForeignPaint.shared
    if let linear = reader.linearGradient(paint: paint) {
      let stops = linear.stops.map {
        GradientStop(offset: $0.offset, paint: Paint.of($0.color))
      }
      guard !stops.isEmpty else { return nil }
      return .linear(
        from: transform.apply(
          point: Point(
            x: bounds.left + linear.x1 * bounds.width,
            y: bounds.top + linear.y1 * bounds.height
          )
        ),
        to: transform.apply(
          point: Point(
            x: bounds.left + linear.x2 * bounds.width,
            y: bounds.top + linear.y2 * bounds.height
          )
        ),
        stops: stops,
        alpha: alpha
      )
    }
    if let radial = reader.radialGradient(paint: paint) {
      let stops = radial.stops.map {
        GradientStop(offset: $0.offset, paint: Paint.of($0.color))
      }
      let radius = radial.radius * max(bounds.width, bounds.height) * transform.averageScale
      guard !stops.isEmpty, radius > 0 else { return nil }
      return .radial(
        centre: transform.apply(
          point: Point(
            x: bounds.left + radial.cx * bounds.width,
            y: bounds.top + radial.cy * bounds.height
          )
        ),
        radius: radius,
        stops: stops,
        alpha: alpha
      )
    }
    return nil
  }

  private func cap(_ value: StrokeCap) -> LineCap {
    switch value {
    case StrokeCap.round: return .round
    case StrokeCap.square: return .square
    default: return .butt
    }
  }

  private func join(_ value: StrokeJoin) -> LineJoin {
    switch value {
    case StrokeJoin.round: return .round
    case StrokeJoin.bevel: return .bevel
    default: return .miter
    }
  }
}

// MARK: - Affine composition

/// The 2×3 affine the scene uses, composed here rather than pushed onto a surface's stack.
///
/// Composing in the walk means a target never has to be trusted to pop what it pushed, and it is why
/// every coordinate a target receives is already where it belongs.
struct Affine {
  var a: Double, b: Double, c: Double, d: Double, e: Double, f: Double

  static let identity = Affine(a: 1, b: 0, c: 0, d: 1, e: 0, f: 0)

  init(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) {
    self.a = a
    self.b = b
    self.c = c
    self.d = d
    self.e = e
    self.f = f
  }

  init(_ transform: Transform2D) {
    self.init(
      a: transform.a, b: transform.b, c: transform.c,
      d: transform.d, e: transform.e, f: transform.f
    )
  }

  /// `self` then `other`, in the scene's own order: a child's transform applies inside its parent's.
  func concatenating(_ other: Affine) -> Affine {
    Affine(
      a: a * other.a + c * other.b,
      b: b * other.a + d * other.b,
      c: a * other.c + c * other.d,
      d: b * other.c + d * other.d,
      e: a * other.e + c * other.f + e,
      f: b * other.e + d * other.f + f
    )
  }

  /// How much this transform scales a length, averaged over the two axes.
  ///
  /// A stroke width is a length, not a coordinate, so it does not go through the matrix — but it has
  /// to follow it. Averaging is what a single width can say about a transform that scales the axes
  /// differently; the scenes this engine publishes scale them together.
  var averageScale: Double {
    let identity = a == 1 && b == 0 && c == 0 && d == 1
    return identity ? 1 : ((a * a + b * b).squareRoot() + (c * c + d * d).squareRoot()) / 2
  }

  func apply(point: Point) -> Point {
    Point(x: a * point.x + c * point.y + e, y: b * point.x + d * point.y + f)
  }

  /// A rectangle's **axis-aligned bounding box** under this transform.
  ///
  /// All four corners, not two. Two opposite corners describe the image only while the transform
  /// maps axes to axes — a translation, a scale, a flip — and `DrawTarget` has no rotated rectangle
  /// in its vocabulary, so the bounding box is the honest answer for anything else. Mapping the
  /// diagonal alone silently reported a box that is too small under rotation or shear: a
  /// 45-degree rotation maps two opposite corners of a square onto a *degenerate* rectangle, and a
  /// group `clip` built from it would have cut away most of what it was meant to keep.
  ///
  /// Nothing this engine compiles reaches it today — a scene's transforms are translations and
  /// uniform scales, which is why the parity goldens agreed while both walks had the same shape —
  /// and the Compose Multiplatform walk is corrected the same way. "Silently" is the part removed.
  func apply(rect: RectD) -> Rect {
    let corners = [
      apply(point: Point(x: rect.left, y: rect.top)),
      apply(point: Point(x: rect.right, y: rect.top)),
      apply(point: Point(x: rect.right, y: rect.bottom)),
      apply(point: Point(x: rect.left, y: rect.bottom)),
    ]
    let left = corners.map(\.x).min() ?? 0
    let top = corners.map(\.y).min() ?? 0
    return Rect(
      x: left, y: top,
      width: (corners.map(\.x).max() ?? 0) - left,
      height: (corners.map(\.y).max() ?? 0) - top
    )
  }
}
