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

  /// A bitmap.
  ///
  /// Two sources, because a scene has two. Most images are a `url` the specification gave, which only a
  /// host can resolve. But a `heatmap` or an `isocontour` produces its image *in the engine* and carries
  /// it as a [DrawRaster] — pixels, no address, nothing to fetch — and a renderer that only understood
  /// URLs would silently drop every one of them.
  ///
  /// The rectangle is where the image goes, with `align` and `baseline` already applied. `fit` says what
  /// to do when the image's own aspect ratio differs from it.
  mutating func image(
    url: String,
    raster: DrawRaster?,
    in rect: Rect,
    fit: DrawImageFit,
    smooth: Bool,
    opacity: Double
  )
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

/// One line of text to draw, positioned.
///
/// Named `DrawTextRun` rather than `TextRun` because the engine exports a `TextRun` of its own — the
/// one a scene holds, with a full style attached — and a file importing both modules could not then say
/// which it meant. The Compose renderer's equivalent has the same name for the same reason.
///
/// **One of these per line, already aligned.** A scene gives a run an anchor plus an `align` and a
/// `baseline`, and turning those into a pen position needs the measured width — which the walk has, from
/// the layout the engine produced, and a target does not. So the walk does that arithmetic and a target
/// simply draws: no alignment logic in any renderer, and no chance of two of them disagreeing.
/// An image the engine produced rather than fetched.
///
/// Carried as a handle to the scene's own `RasterImage` plus its digest, and deliberately not as pixels:
/// the pixels are a `KotlinIntArray`, and reading one from Swift is a call per element — 120,000 of them
/// for a modest heatmap. A target turns this into a platform image once and caches it by [digest], which
/// is why the digest is here at all.
public struct DrawRaster {
  public let image: RasterImage
  /// Stable for identical pixels, so a target can cache across frames without comparing them.
  public let digest: Int64
  public let width: Int
  public let height: Int

  public init(image: RasterImage) {
    self.image = image
    self.digest = image.digest
    self.width = Int(image.width)
    self.height = Int(image.height)
  }
}

/// What to do when an image's aspect ratio differs from the rectangle it goes in. The engine's own two.
public enum DrawImageFit: String, Sendable {
  /// Stretch to the rectangle, ignoring the source's aspect ratio.
  case fill
  /// Preserve the aspect ratio, fitting inside the rectangle and centring what is left over.
  case contain
}

public struct DrawTextRun: Equatable, Sendable {
  public let text: String

  /// Where the pen starts: the left edge of this line, **on its baseline**.
  public let origin: Point

  /// The run's own anchor, which is what rotation turns about — not the pen position.
  public let anchor: Point

  /// The font's ascent, for a surface that draws from a box's top corner rather than from a baseline.
  public let ascent: Double
  public let fontFamily: String
  public let fontSize: Double
  public let fontWeight: Int
  public let italic: Bool
  public let angleDegrees: Double

  /// Extra space between the characters, in scene units.
  ///
  /// Carried because the **measurement** already applies it — `CoreTextTextEngine` sets
  /// `kCTKernAttributeName` from the same style — and the drawing did not, so a specification with a
  /// `letterSpacing` was laid out in a box reserved for spaced glyphs and then painted with unspaced
  /// ones. That is the defect `CoreTextDrawing`'s own header warns about, in a field nobody had
  /// noticed was missing: measured with one thing, drawn with another.
  ///
  /// Defaulted, so a caller that constructs a run without one is unaffected.
  public let letterSpacing: Double

  public init(
    text: String,
    origin: Point,
    anchor: Point,
    ascent: Double,
    fontFamily: String,
    fontSize: Double,
    fontWeight: Int,
    italic: Bool,
    angleDegrees: Double,
    letterSpacing: Double = 0
  ) {
    self.text = text
    self.origin = origin
    self.anchor = anchor
    self.ascent = ascent
    self.fontFamily = fontFamily
    self.fontSize = fontSize
    self.fontWeight = fontWeight
    self.italic = italic
    self.angleDegrees = angleDegrees
    self.letterSpacing = letterSpacing
  }
}
