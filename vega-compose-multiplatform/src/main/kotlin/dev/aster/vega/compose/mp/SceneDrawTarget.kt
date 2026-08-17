package dev.aster.vega.compose.mp

import dev.aster.vega.scene.RasterImage
import dev.aster.vega.scene.SceneColor

/**
 * Where a scene is drawn — one interface, so [SceneWalk] is written once.
 *
 * The walk decides *what* to draw and in what order; a target decides *how*. That split is what
 * lets the same traversal feed Compose's `DrawScope` on a phone, on iOS and on the desktop, and
 * feed a [RecordingTarget] in a test that runs on every one of those targets with no screen at all.
 *
 * It is deliberately the same shape as the Swift renderer's `DrawTarget` in
 * `swift/AsterVegaRender`. Two renderers that answer the same questions in the same order are two
 * renderers that can be compared when one of them is wrong — and one of them was: see [SceneWalk]
 * on opacity.
 *
 * Every coordinate reaching a target is already in the surface's own space. The walk applies each
 * group's transform itself rather than pushing a matrix, because a renderer that pushed one would
 * have to be trusted to pop it.
 */
public interface SceneDrawTarget {

  /** Begins a group, whose [clip] applies until the matching [endGroup]. */
  public fun beginGroup(clip: DrawRect?)

  public fun endGroup()

  /** An axis-aligned rectangle, with per-corner radii already resolved. */
  public fun rect(rect: DrawRect, corners: DrawCorners, fill: DrawBrush?, stroke: DrawStroke?)

  /** A straight segment. A rule is a line, not a thin rectangle, so its caps matter. */
  public fun line(from: DrawPoint, to: DrawPoint, stroke: DrawStroke?)

  /** An arbitrary path, already reduced to move/line/cubic/close by the engine. */
  public fun path(commands: List<DrawPathCommand>, fill: DrawBrush?, stroke: DrawStroke?)

  /** One line of text, positioned at its anchor with alignment already resolved by the engine. */
  public fun text(run: DrawTextRun, fill: DrawBrush?, stroke: DrawStroke?)

  /**
   * A bitmap.
   *
   * Two sources, because a scene has two. Most images are a `url` only a host can resolve, but a
   * `heatmap` or an `isocontour` produces its image *in the engine* and carries it as [raster] —
   * pixels, no address, nothing to fetch. A renderer that only understood URLs dropped every one of
   * them, which looks exactly like a transform that did nothing.
   *
   * The rectangle already has `align` and `baseline` applied; [fit] says what to do when the
   * image's own aspect ratio differs from it.
   */
  public fun image(
    url: String,
    raster: RasterImage?,
    rect: DrawRect,
    fit: DrawImageFit,
    smooth: Boolean,
    opacity: Double,
  )
}

// The vocabulary a target is spoken to in. Plain data, so `commonTest` can assert on it and so no
// part of the walk depends on a Compose type — that dependency belongs to one file,
// DrawScopeTarget.

public data class DrawPoint(val x: Double, val y: Double)

public data class DrawRect(
  val x: Double,
  val y: Double,
  val width: Double,
  val height: Double,
)

/** Per-corner radii. A `rect` mark may round three corners and square the fourth. */
public data class DrawCorners(
  val topLeft: Double,
  val topRight: Double,
  val bottomRight: Double,
  val bottomLeft: Double,
) {
  public val isSquare: Boolean
    get() = topLeft == 0.0 && topRight == 0.0 && bottomRight == 0.0 && bottomLeft == 0.0

  public companion object {
    public val Square: DrawCorners = DrawCorners(0.0, 0.0, 0.0, 0.0)
  }
}

/** A colour with its item's own opacity already multiplied into the alpha by the walk. */
public data class DrawPaint(
  val red: Double,
  val green: Double,
  val blue: Double,
  val alpha: Double,
) {
  public companion object {
    public fun of(colour: SceneColor): DrawPaint =
      DrawPaint(colour.red, colour.green, colour.blue, colour.alpha)
  }
}

/**
 * What a shape is painted with: a colour, or a gradient already resolved to surface coordinates.
 *
 * A specification writes a gradient in fractions of the item it fills — `x1: 0, x2: 1` is left edge
 * to right edge, whatever the mark's size — so the walk multiplies those fractions through the
 * item's bounds and a target receives absolute points. That keeps "what does x1 mean" in one place
 * instead of in every renderer.
 */
public sealed interface DrawBrush {

  /** The opacity to draw the whole brush at, from the item's own `opacity` channel. */
  public val alpha: Double

  public data class Solid(val paint: DrawPaint) : DrawBrush {
    override val alpha: Double
      get() = paint.alpha
  }

  public data class Linear(
    val from: DrawPoint,
    val to: DrawPoint,
    val stops: List<DrawStop>,
    override val alpha: Double,
  ) : DrawBrush

  public data class Radial(
    val center: DrawPoint,
    val radius: Double,
    val stops: List<DrawStop>,
    override val alpha: Double,
  ) : DrawBrush
}

public data class DrawStop(val offset: Double, val paint: DrawPaint)

/**
 * What to do when an image's aspect ratio differs from the rectangle it goes in. The engine's own
 * two.
 */
public enum class DrawImageFit {
  /** Stretch to the rectangle, ignoring the source's aspect ratio. */
  FILL,
  /** Preserve the aspect ratio, fitting inside the rectangle and centring what is left over. */
  CONTAIN,
}

public enum class DrawLineCap {
  Butt,
  Round,
  Square,
}

public enum class DrawLineJoin {
  Miter,
  Round,
  Bevel,
}

public data class DrawStroke(
  val brush: DrawBrush,
  val width: Double,
  val cap: DrawLineCap,
  val join: DrawLineJoin,
  val miterLimit: Double,
  val dash: List<Double>,
  val dashOffset: Double,
)

/**
 * A path reduced to the four commands every surface has.
 *
 * The engine normalises arcs and quadratics to cubics before publishing a scene, so a target never
 * has to know how to draw an ellipse — which is why an arc mark and a Bézier path arrive the same
 * way.
 */
public sealed interface DrawPathCommand {
  public data class MoveTo(val to: DrawPoint) : DrawPathCommand

  public data class LineTo(val to: DrawPoint) : DrawPathCommand

  public data class CubicTo(
    val control1: DrawPoint,
    val control2: DrawPoint,
    val to: DrawPoint,
  ) : DrawPathCommand

  public data object Close : DrawPathCommand
}

/**
 * One line of text to draw, positioned.
 *
 * **One of these per line, already aligned.** A scene gives a run an anchor plus an `align` and a
 * `baseline`, and turning those into a pen position needs the measured width — which the walk has,
 * from the layout the engine produced, and a target does not. So the walk does that arithmetic and
 * a target simply draws: no alignment logic in any renderer, and no chance of two of them
 * disagreeing.
 */
public data class DrawTextRun(
  val text: String,
  /** Where the pen starts: the left edge of this line, **on its baseline**. */
  val origin: DrawPoint,
  /** The run's own anchor, which is what rotation turns about — not the pen position. */
  val anchor: DrawPoint,
  /** The font's ascent, for a surface that draws from a box's top corner rather than a baseline. */
  val ascent: Double,
  val fontFamily: String,
  val fontSize: Double,
  val fontWeight: Int,
  val italic: Boolean,
  val angleDegrees: Double,
)
