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
  public func draw<T: DrawTarget>(scene: Scene, into target: inout T) {
    // A scene's own background is not a node — nothing in the tree paints it — so a renderer that
    // only walked the tree would leave a transparent chart on whatever was behind it.
    if let background = scene.background {
      target.rect(
        Rect(x: 0, y: 0, width: scene.width, height: scene.height),
        corners: .square,
        fill: Paint(
          red: background.red, green: background.green,
          blue: background.blue, alpha: background.alpha
        ),
        stroke: nil
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

    switch ForeignRenderersKt.foreignKind(node) {
    case "group":
      guard let group = node as? GroupNode else { return }
      let local = transform.concatenating(Affine(group.transform))
      // A group's clip is in its *own* space, so it is mapped through the transform that has just
      // been composed rather than the one it was reached with.
      let clip = group.clip.map { local.apply(rect: $0) }
      target.beginGroup(clip: clip)
      // Its own paint, if any, sits behind its children — a group is how an axis draws its panel.
      if let size = group.size {
        let box = local.apply(rect: RectD(left: 0, top: 0, right: size.width, bottom: size.height))
        let fill = ForeignPaint.shared.solidFill(fill: group.fill)
        let stroke = ForeignPaint.shared.solidStroke(stroke: group.stroke)
        if fill != nil || stroke != nil {
          target.rect(
            box,
            corners: corners(of: group),
            fill: paint(fill, opacity: own),
            stroke: strokePaint(group.stroke, colour: stroke, opacity: own)
          )
        }
      }
      // Drawn whatever the group's own opacity is — a transparent group is not an invisible one.
      for child in group.children {
        walk(node: child, transform: local, into: &target)
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
        fill: paint(ForeignPaint.shared.solidFill(fill: rect.fill), opacity: own),
        stroke: strokePaint(
          rect.stroke,
          colour: ForeignPaint.shared.solidStroke(stroke: rect.stroke),
          opacity: own
        )
      )

    case "rule":
      guard let rule = node as? RuleNode else { return }
      let local = transform.concatenating(Affine(rule.transform))
      target.line(
        from: local.apply(point: Point(x: rule.x1, y: rule.y1)),
        to: local.apply(point: Point(x: rule.x2, y: rule.y2)),
        stroke: strokePaint(
          rule.stroke,
          colour: ForeignPaint.shared.solidStroke(stroke: rule.stroke),
          opacity: own
        )
      )

    case "path":
      guard let path = node as? PathNode, !path.absent else { return }
      let local = transform.concatenating(Affine(path.transform))
      target.path(
        commands(of: path.path, through: local),
        fill: paint(ForeignPaint.shared.solidFill(fill: path.fill), opacity: own),
        stroke: strokePaint(
          path.stroke,
          colour: ForeignPaint.shared.solidStroke(stroke: path.stroke),
          opacity: own
        )
      )

    case "symbol":
      guard let symbol = node as? SymbolNode else { return }
      // A symbol's shape is already a path — the engine turns a circle, a cross or a custom SVG
      // string into one — so there is no shape vocabulary for a renderer to reimplement.
      let local = transform.concatenating(Affine(symbol.transform))
      target.path(
        commands(of: symbol.outline, through: local),
        fill: paint(ForeignPaint.shared.solidFill(fill: symbol.fill), opacity: own),
        stroke: strokePaint(
          symbol.stroke,
          colour: ForeignPaint.shared.solidStroke(stroke: symbol.stroke),
          opacity: own
        )
      )

    case "text":
      guard let text = node as? TextNode, !text.absent else { return }
      let local = transform.concatenating(Affine(text.transform))
      let run = text.layout.run
      let style = run.style
      target.text(
        TextRun(
          text: run.text,
          origin: local.apply(point: Point(x: text.x, y: text.y)),
          fontFamily: style.fontFamily,
          fontSize: style.fontSize,
          fontWeight: Int(style.fontWeight),
          italic: style.fontStyle == FontStyle.italic,
          angleDegrees: text.angleDegrees
        ),
        fill: paint(ForeignPaint.shared.solidFill(fill: text.fill), opacity: own),
        stroke: strokePaint(
          text.stroke,
          colour: ForeignPaint.shared.solidStroke(stroke: text.stroke),
          opacity: own
        )
      )

    case "image":
      guard let image = node as? ImageNode else { return }
      let local = transform.concatenating(Affine(image.transform))
      target.image(
        url: image.url,
        in: local.apply(
          rect: RectD(
            left: image.x, top: image.y,
            right: image.x + image.width, bottom: image.y + image.height
          )
        ),
        opacity: own
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

  private func paint(_ colour: SceneColor?, opacity: Double) -> Paint? {
    guard let colour, colour.alpha * opacity > 0 else { return nil }
    return Paint(
      red: colour.red, green: colour.green, blue: colour.blue,
      alpha: colour.alpha * opacity
    )
  }

  private func strokePaint(
    _ stroke: Stroke?,
    colour: SceneColor?,
    opacity: Double
  ) -> StrokePaint? {
    guard let stroke, stroke.width > 0, let paint = paint(colour, opacity: opacity) else {
      return nil
    }
    return StrokePaint(
      paint: paint,
      width: stroke.width,
      cap: cap(stroke.cap),
      join: join(stroke.join),
      miterLimit: stroke.miterLimit,
      dash: stroke.dashArray.map { $0.doubleValue },
      dashOffset: stroke.dashOffset
    )
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

  func apply(point: Point) -> Point {
    Point(x: a * point.x + c * point.y + e, y: b * point.x + d * point.y + f)
  }

  /// A rectangle's image, normalised — a transform with a negative scale would otherwise invert it.
  func apply(rect: RectD) -> Rect {
    let one = apply(point: Point(x: rect.left, y: rect.top))
    let two = apply(point: Point(x: rect.right, y: rect.bottom))
    return Rect(
      x: min(one.x, two.x), y: min(one.y, two.y),
      width: abs(two.x - one.x), height: abs(two.y - one.y)
    )
  }
}
