import AsterVega

/// Where a scene is drawn — one protocol, so the walk is written once.
///
/// The walk in `SceneWalk` decides *what* to draw and in what order; a target decides *how*. That
/// split is what lets the same traversal feed CoreGraphics on a device and a recording target in a
/// test, and it is why the tests need no simulator and no pixels: what a renderer can get wrong is
/// which primitives it emits, with which geometry, in which order, and a recording target compares
/// exactly that.
///
/// Every coordinate reaching a target is already in the surface's own space. The walk applies each
/// group's transform itself, because a renderer that pushed and popped a matrix per group would have
/// to be trusted to pop it again.
public protocol DrawTarget {
  /// Begins a group: a clip that applies until the matching ``endGroup()``.
  ///
  /// No opacity, because a group does not composite its children — see ``SceneWalk``. Every paint
  /// arriving at a target already carries its own item's opacity in its alpha, so a target needs no
  /// alpha state at all.
  mutating func beginGroup(clip: Rect?)

  mutating func endGroup()

  /// An axis-aligned rectangle, with per-corner radii already resolved.
  mutating func rect(_ rect: Rect, corners: Corners, fill: Brush?, stroke: StrokePaint?)

  /// A straight segment. A rule is a line, not a thin rectangle, so its caps matter.
  mutating func line(from: Point, to: Point, stroke: StrokePaint?)

  /// An arbitrary path, already reduced to move/line/cubic/close by the engine.
  mutating func path(_ commands: [PathCommand], fill: Brush?, stroke: StrokePaint?)

  /// One line of text, positioned at its anchor with alignment already resolved by the engine.
  mutating func text(_ run: DrawTextRun, fill: Brush?, stroke: StrokePaint?)

  /// A bitmap, by the URL the specification gave; a target resolves it however it can.
  mutating func image(url: String, in rect: Rect, opacity: Double)
}

// MARK: - The vocabulary a target is spoken to in

public struct Point: Equatable, Sendable {
  public let x: Double
  public let y: Double
  public init(x: Double, y: Double) {
    self.x = x
    self.y = y
  }
}

public struct Rect: Equatable, Sendable {
  public let x: Double
  public let y: Double
  public let width: Double
  public let height: Double
  public init(x: Double, y: Double, width: Double, height: Double) {
    self.x = x
    self.y = y
    self.width = width
    self.height = height
  }
}

/// Per-corner radii. A `rect` mark may round three corners and square the fourth.
public struct Corners: Equatable, Sendable {
  public let topLeft: Double
  public let topRight: Double
  public let bottomRight: Double
  public let bottomLeft: Double

  public static let square = Corners(topLeft: 0, topRight: 0, bottomRight: 0, bottomLeft: 0)

  public init(topLeft: Double, topRight: Double, bottomRight: Double, bottomLeft: Double) {
    self.topLeft = topLeft
    self.topRight = topRight
    self.bottomRight = bottomRight
    self.bottomLeft = bottomLeft
  }

  public var isSquare: Bool {
    topLeft == 0 && topRight == 0 && bottomRight == 0 && bottomLeft == 0
  }
}

/// A colour with its own opacity already multiplied in by the walk.
public struct Paint: Equatable, Sendable {
  public let red: Double
  public let green: Double
  public let blue: Double
  public let alpha: Double
  public init(red: Double, green: Double, blue: Double, alpha: Double) {
    self.red = red
    self.green = green
    self.blue = blue
    self.alpha = alpha
  }

  /// A scene colour, as it stands. Used for a gradient's stops, which carry their own alpha.
  public static func of(_ colour: SceneColor) -> Paint {
    Paint(red: colour.red, green: colour.green, blue: colour.blue, alpha: colour.alpha)
  }
}

/// What a shape is painted with: a colour, or a gradient already resolved to surface coordinates.
///
/// A specification writes a gradient in fractions of the item it fills — `x1: 0, x2: 1` is left edge to
/// right edge whatever the mark's size — so the walk multiplies those fractions through the item's
/// bounds and a target receives absolute points. That keeps "what does x1 mean" in one place rather
/// than in every renderer, and it is the same split the Compose renderer uses.
public enum Brush: Equatable, Sendable {
  case solid(Paint)
  case linear(from: Point, to: Point, stops: [GradientStop], alpha: Double)
  case radial(centre: Point, radius: Double, stops: [GradientStop], alpha: Double)

  /// The opacity to draw the whole brush at, from the item's own `opacity` channel.
  public var alpha: Double {
    switch self {
    case .solid(let paint): return paint.alpha
    case .linear(_, _, _, let alpha): return alpha
    case .radial(_, _, _, let alpha): return alpha
    }
  }
}

public struct GradientStop: Equatable, Sendable {
  public let offset: Double
  public let paint: Paint
  public init(offset: Double, paint: Paint) {
    self.offset = offset
    self.paint = paint
  }
}

public enum LineCap: String, Sendable { case butt, round, square }

public enum LineJoin: String, Sendable { case miter, round, bevel }

public struct StrokePaint: Equatable, Sendable {
  public let brush: Brush
  public let width: Double
  public let cap: LineCap
  public let join: LineJoin
  public let miterLimit: Double
  public let dash: [Double]
  public let dashOffset: Double

  public init(
    brush: Brush,
    width: Double,
    cap: LineCap,
    join: LineJoin,
    miterLimit: Double,
    dash: [Double],
    dashOffset: Double
  ) {
    self.brush = brush
    self.width = width
    self.cap = cap
    self.join = join
    self.miterLimit = miterLimit
    self.dash = dash
    self.dashOffset = dashOffset
  }
}

/// A path reduced to the four commands every surface has.
///
/// The engine normalises arcs and quadratics to cubics before a scene is published, so a target
/// never has to know how to draw an ellipse — which is also why an arc mark and a Bézier path reach
/// a renderer the same way.
public enum PathCommand: Equatable, Sendable {
  case move(to: Point)
  case line(to: Point)
  case cubic(control1: Point, control2: Point, to: Point)
  case close
}

/// One line of text to draw.
///
/// Named `DrawTextRun` rather than `TextRun` because the engine exports a `TextRun` of its own — the
/// one a scene holds, with a full style attached — and a file importing both modules could not then say
/// which it meant. The Compose renderer's equivalent has the same name for the same reason.
public struct DrawTextRun: Equatable, Sendable {
  public let text: String
  public let origin: Point
  public let fontFamily: String
  public let fontSize: Double
  public let fontWeight: Int
  public let italic: Bool
  public let angleDegrees: Double

  public init(
    text: String,
    origin: Point,
    fontFamily: String,
    fontSize: Double,
    fontWeight: Int,
    italic: Bool,
    angleDegrees: Double
  ) {
    self.text = text
    self.origin = origin
    self.fontFamily = fontFamily
    self.fontSize = fontSize
    self.fontWeight = fontWeight
    self.italic = italic
    self.angleDegrees = angleDegrees
  }
}
