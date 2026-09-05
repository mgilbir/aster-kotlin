package dev.aster.vega.compose.mp

import dev.aster.vega.scene.Corners
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.FontStyle
import dev.aster.vega.scene.ForeignPaint
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.ImageFit
import dev.aster.vega.scene.ImageNode
import dev.aster.vega.scene.PathCommand
import dev.aster.vega.scene.PathData
import dev.aster.vega.scene.PathNode
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.Stroke
import dev.aster.vega.scene.StrokeCap
import dev.aster.vega.scene.StrokeJoin
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextBaseline
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.Transform2D
import dev.aster.vega.scene.paintOrder
import dev.aster.vega.scene.paintsNothing
import kotlin.math.max

/**
 * Walks an immutable [Scene] and tells a [SceneDrawTarget] what to draw.
 *
 * This is the whole of the renderer's logic, and the only place any of it lives: the Compose
 * `DrawScope` target and the [RecordingTarget] the tests use see the same calls in the same order,
 * so a passing test is a statement about what a device will draw rather than about a parallel
 * implementation.
 *
 * The engine has already done everything requiring a chart's semantics — scales, layout, text
 * measurement, arcs reduced to cubics, per-corner radii resolved, gradient stops in order. What is
 * left is arithmetic: compose each group's transform, resolve gradients against the item they fill,
 * and hand over primitives.
 *
 * ### Opacity is per item
 *
 * A group's opacity paints its own panel and is **not** inherited by its children. That is
 * upstream's behaviour in both of its renderers: `vega-scenegraph`'s canvas group saves the
 * graphics state, translates and clips on the way in and never touches `globalAlpha`, and its SVG
 * renderer emits `opacity` on the group's background `path` while leaving the child element bare. A
 * group whose opacity is zero therefore still draws its children — it is a group with no panel, not
 * an invisible subtree.
 *
 * This is written out because the opposite is the natural guess, and two of this project's
 * renderers guessed it before pixels said otherwise.
 */
public class SceneWalk {

  private companion object {
    /**
     * How many transformed paths are kept.
     *
     * A frame's working set is one entry per path mark, so this is sized for a dense scene — the
     * `airport-connections` fixture draws six hundred Voronoi cells — and exists only so a chart
     * that rebuilds its paths every frame anyway cannot grow the map without bound.
     */
    const val PATH_CACHE_LIMIT: Int = 4096
  }

  /** Draws [scene] into [target]. */
  public fun draw(scene: Scene, target: SceneDrawTarget) {
    // A scene's background is not a node — nothing in the tree paints it — so a renderer that only
    // walked the tree would leave a transparent chart over whatever was behind it.
    scene.background?.let { background ->
      if (!background.isTransparent) {
        target.rect(
          DrawRect(0.0, 0.0, scene.width, scene.height),
          DrawCorners.Square,
          DrawBrush.Solid(DrawPaint.of(background)),
          stroke = null,
        )
      }
    }
    walk(scene.root, Transform2D.Identity, target)
  }

  private fun walk(node: SceneNode, transform: Transform2D, target: SceneDrawTarget) {
    // Hidden, transparent, or an item with no text and no outline at all. The predicate is shared
    // with the other three walks — see `paintsNothing`, whose documentation is the record of what
    // it cost to have four copies of it.
    if (paintsNothing(node)) return
    val local = transform then node.transform

    when (node) {
      is GroupNode -> walkGroup(node, local, target)
      is RectNode -> {
        val box = local.applyTo(RectD(node.x, node.y, node.x + node.width, node.y + node.height))
        target.rect(
          box,
          corners(node),
          brush(node.fill, node.opacity, node.bounds, local),
          stroke(node.stroke, node.opacity, node.bounds, local),
          node.blendMode,
        )
      }
      is RuleNode ->
        target.line(
          local.applyTo(DrawPoint(node.x1, node.y1)),
          local.applyTo(DrawPoint(node.x2, node.y2)),
          stroke(node.stroke, node.opacity, node.bounds, local),
          node.blendMode,
        )
      is PathNode ->
        target.path(
          commands(node.path, local),
          brush(node.fill, node.opacity, node.bounds, local),
          stroke(node.stroke, node.opacity, node.bounds, local),
          node.blendMode,
        )
      is SymbolNode ->
        // A symbol's shape is already a path — the engine turns a circle, a cross or a custom SVG
        // string into one — so there is no shape vocabulary for a renderer to reimplement.
        target.path(
          commands(node.outline, local),
          brush(node.fill, node.opacity, node.bounds, local),
          stroke(node.stroke, node.opacity, node.bounds, local),
          node.blendMode,
        )
      is TextNode -> walkText(node, local, target)
      is ImageNode ->
        // `rect` rather than the raw channels: the node has already applied `align` and `baseline`
        // to
        // produce it, so using x/y/width/height drew every non-default alignment in the wrong place
        // — the
        // same mistake text was making before its own fix.
        target.image(
          url = node.url,
          raster = node.raster,
          rect = local.applyTo(node.rect),
          fit = if (node.fit == ImageFit.CONTAIN) DrawImageFit.CONTAIN else DrawImageFit.FILL,
          smooth = node.smooth,
          opacity = node.opacity,
          blend = node.blendMode,
        )
    }
  }

  private fun walkGroup(node: GroupNode, local: Transform2D, target: SceneDrawTarget) {
    // A group's clip is in its own space, so it is mapped through the transform just composed.
    //
    // **`clipPath` is not implemented here**, and this comment is the whole of the report: a group
    // whose `encode` block gives it a `path` clips to that outline on the Android canvas and in
    // exported SVG, and to nothing at all in this renderer. `SceneDrawTarget.beginGroup` takes a
    // rectangle, so honouring one means widening that seam — and widening it for the Swift walk in
    // the same step, since the two are compared call for call. Until then a chart using it draws
    // *more* than it should here rather than less, which is visible rather than silent.
    target.beginGroup(node.clip?.let { local.applyTo(it) })

    // A group with its own paint draws a rectangle of its declared size, and this is the only thing
    // its own opacity applies to. `paintRect` and `effectiveStrokeOffset` are the scene node's own
    // answers: a group stroked at about one unit is nudged half a unit so the outline lands on a
    // pixel boundary instead of straddling one, which is upstream's rule and not a renderer's.
    val paintRect = node.paintRect
    val panel =
      if (paintRect == null || node.opacity <= 0.0) {
        null
      } else {
        val offset = node.effectiveStrokeOffset
        local.applyTo(
          RectD(
            paintRect.left + offset,
            paintRect.top + offset,
            paintRect.right + offset,
            paintRect.bottom + offset,
          )
        )
      }

    if (panel != null && !node.strokeForeground) {
      target.rect(
        panel,
        corners(node),
        brush(node.fill, node.opacity, paintRect!!, local),
        stroke(node.stroke, node.opacity, paintRect, local),
        node.blendMode,
      )
    } else if (panel != null) {
      // `strokeForeground` puts the outline over the children; the fill still goes underneath.
      target.rect(
        panel,
        corners(node),
        brush(node.fill, node.opacity, paintRect!!, local),
        null,
        node.blendMode,
      )
    }

    // `paintOrder`, not `children`; see the note in `AndroidCanvasSceneRenderer`. The Swift walk
    // reorders identically, which is what keeps the scene-walk goldens byte-identical.
    for (child in paintOrder(node.children)) walk(child, local, target)

    if (panel != null && node.strokeForeground) {
      target.rect(
        panel,
        corners(node),
        null,
        stroke(node.stroke, node.opacity, paintRect!!, local),
        node.blendMode,
      )
    }

    target.endGroup()
  }

  /**
   * Draws a text node: one call per line, with `align` and `baseline` resolved into a pen position.
   *
   * The offsets are [textBounds]' own, which is what the engine used to compute this node's bounds
   * — so the glyphs land inside the space the layout reserved rather than beside it. Getting this
   * wrong is not subtle in a chart: a right-aligned axis label drawn rightwards from its anchor
   * sits on top of the axis line, which is exactly how it looked before this existed.
   */
  private fun walkText(node: TextNode, local: Transform2D, target: SceneDrawTarget) {
    val layout = node.layout
    val run = layout.run
    val style = run.style
    val metrics = layout.metrics
    val fill = brush(node.fill, node.opacity, node.bounds, local)
    val stroke = stroke(node.stroke, node.opacity, node.bounds, local)

    val top =
      when (run.baseline) {
        TextBaseline.TOP,
        TextBaseline.LINE_TOP -> 0.0
        TextBaseline.MIDDLE -> -metrics.height / 2.0
        TextBaseline.BOTTOM,
        TextBaseline.LINE_BOTTOM -> -metrics.height
        TextBaseline.ALPHABETIC -> -metrics.ascent
      }
    val anchor = local.applyTo(DrawPoint(node.x, node.y))

    // Every line is anchored at the same point and aligned by *its own* width, which is what an SVG
    // `tspan` with an absolute `x` does.
    for (line in layout.lines) {
      val leading =
        when (run.align) {
          TextAlign.LEFT -> 0.0
          TextAlign.CENTER -> -line.width / 2.0
          TextAlign.RIGHT -> -line.width
        }
      target.text(
        DrawTextRun(
          text = line.text,
          origin =
            local.applyTo(
              node.x + leading,
              // The baseline of this line: the box's top, down by the ascent, then by the stack.
              node.y + top + metrics.ascent + line.baselineY,
            ),
          anchor = anchor,
          ascent = metrics.ascent,
          fontFamily = style.fontFamily,
          fontSize = style.fontSize,
          fontWeight = style.fontWeight,
          italic = style.fontStyle == FontStyle.ITALIC,
          angleDegrees = node.angleDegrees,
          letterSpacing = style.letterSpacing,
        ),
        fill,
        stroke,
        node.blendMode,
      )
    }
  }

  // MARK: reading the scene

  /**
   * A path's commands in surface space, **built once per (path, transform)** rather than per frame.
   *
   * A scene is immutable and republished by identity, so the `PathData` behind a mark is the same
   * object on every frame of a pan; the transform is a value, and it changes only when the chart
   * does. So this list is the same list every frame, and it was rebuilt every frame: a
   * ten-thousand-symbol scene allocated ten thousand lists and about thirty thousand `DrawPoint`s
   * per frame, sixty times a second, for a picture that had not changed. Keyed on the `PathData`'s
   * **identity** rather than its contents, because comparing a thousand commands to decide whether
   * to rebuild a thousand commands saves nothing.
   *
   * **Confined**, like the caches in `TextLayoutCache` and `CachingExpressionCompiler` and for the
   * same reason: an LRU mutates on a hit, so two threads drawing through one `SceneWalk` would
   * corrupt the map. A `SceneWalk` belongs to one surface, which draws on one thread.
   */
  private fun commands(path: PathData, transform: Transform2D): List<DrawPathCommand> {
    val key = PathKey(path, transform)
    transformed[key]?.let {
      // Re-inserted, so it moves to the young end: least-recently-used, not least-recently-added.
      transformed.remove(key)
      transformed[key] = it
      return it
    }
    val built = transform(path, transform)
    if (transformed.size >= PATH_CACHE_LIMIT) transformed.remove(transformed.keys.first())
    transformed[key] = built
    return built
  }

  /** A path by identity and the matrix it is drawn through. See [commands]. */
  private class PathKey(private val path: PathData, private val transform: Transform2D) {
    override fun hashCode(): Int = 31 * path.hashCode() + transform.hashCode()

    override fun equals(other: Any?): Boolean =
      other is PathKey && other.path === path && other.transform == transform
  }

  private val transformed = LinkedHashMap<PathKey, List<DrawPathCommand>>()

  private fun transform(path: PathData, transform: Transform2D): List<DrawPathCommand> =
    path.commands.map { command ->
      when (command) {
        is PathCommand.MoveTo -> DrawPathCommand.MoveTo(transform.applyTo(command.x, command.y))
        is PathCommand.LineTo -> DrawPathCommand.LineTo(transform.applyTo(command.x, command.y))
        is PathCommand.CubicTo ->
          DrawPathCommand.CubicTo(
            transform.applyTo(command.x1, command.y1),
            transform.applyTo(command.x2, command.y2),
            transform.applyTo(command.x, command.y),
          )
        PathCommand.Close -> DrawPathCommand.Close
      }
    }

  /**
   * The radii to draw with, **clamped by the scene** rather than by whatever receives them.
   *
   * These used to be the raw `cornerRadius` overrides, and that is a divergence rather than a
   * shortcut: `Corners.of` clamps the four as one group against `min(width, height) / 2`, which is
   * upstream's rule, and a platform primitive handed the unclamped value applies its own. Skia's
   * `RoundRect` scales all four radii by a common factor so they fit — the CSS rule — so a 100×20
   * bar with a 40-unit top-left radius came out with a 20-unit corner here and a 10-unit one on the
   * Android canvas and in an exported SVG of the same chart, which read the clamped
   * `RectNode.corners`.
   *
   * The Apple target was right by doing the work again itself, which is what hid this: the two
   * walks are compared call for call, and both were emitting the same wrong number.
   */
  private fun corners(node: RectNode): DrawCorners = corners(node.corners)

  private fun corners(node: GroupNode): DrawCorners = corners(node.paintCorners)

  private fun corners(corners: Corners): DrawCorners =
    DrawCorners(
      corners.topLeft,
      corners.topRight,
      corners.bottomRight,
      corners.bottomLeft,
    )

  /**
   * A fill as a brush, with the item's own opacity multiplied in, or null when it paints nothing.
   *
   * [ForeignPaint] answers the solid case, which is the same function the Swift renderer calls —
   * one description of "what colour is this" rather than two that could drift.
   */
  private fun brush(
    fill: Fill?,
    opacity: Double,
    bounds: RectD,
    transform: Transform2D,
  ): DrawBrush? {
    if (fill == null) return null
    val alpha = opacity * fill.opacity
    if (alpha <= 0.0) return null
    ForeignPaint.solidFill(fill)?.let { colour ->
      val solid = DrawPaint(colour.red, colour.green, colour.blue, colour.alpha * opacity)
      return if (solid.alpha <= 0.0) null else DrawBrush.Solid(solid)
    }
    return gradient(fill.paint, alpha, bounds, transform)
  }

  private fun stroke(
    stroke: Stroke?,
    opacity: Double,
    bounds: RectD,
    transform: Transform2D,
  ): DrawStroke? {
    if (stroke == null || stroke.width <= 0.0) return null
    val alpha = opacity * stroke.opacity
    if (alpha <= 0.0) return null
    val brush =
      ForeignPaint.solidStroke(stroke)?.let { colour ->
        val solid = DrawPaint(colour.red, colour.green, colour.blue, colour.alpha * opacity)
        if (solid.alpha <= 0.0) null else DrawBrush.Solid(solid)
      } ?: gradient(stroke.paint, alpha, bounds, transform) ?: return null

    return DrawStroke(
      brush = brush,
      // A transform scales a stroke's width as well as its geometry, and the walk has already
      // applied the transform to the geometry — so the width has to be scaled here to match.
      width = stroke.width * transform.averageScale,
      cap =
        when (stroke.cap) {
          StrokeCap.ROUND -> DrawLineCap.Round
          StrokeCap.SQUARE -> DrawLineCap.Square
          else -> DrawLineCap.Butt
        },
      join =
        when (stroke.join) {
          StrokeJoin.ROUND -> DrawLineJoin.Round
          StrokeJoin.BEVEL -> DrawLineJoin.Bevel
          else -> DrawLineJoin.Miter
        },
      miterLimit = stroke.miterLimit,
      dash = stroke.dashArray.map { it * transform.averageScale },
      dashOffset = stroke.dashOffset * transform.averageScale,
    )
  }

  /**
   * A gradient resolved against the item it paints.
   *
   * Vega writes a gradient's coordinates as fractions of the item's own bounds — `x1: 0, x2: 1` is
   * left edge to right edge whatever the mark's size — so they are multiplied through those bounds
   * here and the target receives absolute surface points.
   */
  private fun gradient(
    paint: ScenePaint?,
    alpha: Double,
    bounds: RectD,
    transform: Transform2D,
  ): DrawBrush? =
    when (paint) {
      is ScenePaint.LinearGradient ->
        if (paint.stops.isEmpty()) {
          null
        } else {
          DrawBrush.Linear(
            from =
              transform.applyTo(
                bounds.left + paint.x1 * bounds.width,
                bounds.top + paint.y1 * bounds.height,
              ),
            to =
              transform.applyTo(
                bounds.left + paint.x2 * bounds.width,
                bounds.top + paint.y2 * bounds.height,
              ),
            stops = paint.stops.map { DrawStop(it.offset, DrawPaint.of(it.color)) },
            alpha = alpha,
          )
        }
      is ScenePaint.RadialGradient -> {
        val radius = paint.radius * max(bounds.width, bounds.height) * transform.averageScale
        if (paint.stops.isEmpty() || radius <= 0.0) {
          null
        } else {
          DrawBrush.Radial(
            center =
              transform.applyTo(
                bounds.left + paint.cx * bounds.width,
                bounds.top + paint.cy * bounds.height,
              ),
            radius = radius,
            stops = paint.stops.map { DrawStop(it.offset, DrawPaint.of(it.color)) },
            alpha = alpha,
          )
        }
      }
      else -> null
    }
}

// Transform composition. Done here rather than pushed onto a surface's stack, so a target never has
// to be trusted to pop what it pushed and every coordinate it receives is already where it belongs.

private infix fun Transform2D.then(child: Transform2D): Transform2D =
  if (child.isIdentity) {
    this
  } else if (isIdentity) {
    child
  } else {
    Transform2D(
      a = a * child.a + c * child.b,
      b = b * child.a + d * child.b,
      c = a * child.c + c * child.d,
      d = b * child.c + d * child.d,
      e = a * child.e + c * child.f + e,
      f = b * child.e + d * child.f + f,
    )
  }

private fun Transform2D.applyTo(x: Double, y: Double): DrawPoint =
  DrawPoint(a * x + c * y + e, b * x + d * y + f)

private fun Transform2D.applyTo(point: DrawPoint): DrawPoint = applyTo(point.x, point.y)

/**
 * A rectangle's **axis-aligned bounding box** under this transform.
 *
 * All four corners, not two. Two opposite corners describe the image only while the transform maps
 * axes to axes — a translation, a scale, a flip — and the target's vocabulary has no rotated
 * rectangle, so the bounding box is the honest answer for anything else. Mapping the diagonal alone
 * silently reported a box that is too small under rotation or shear: a 45-degree rotation of a unit
 * square has a diagonal of two opposite corners that maps to a *degenerate* rectangle, and a clip
 * built from it would have cut away most of what it was meant to keep.
 *
 * Nothing this engine compiles reaches it today — a scene's transforms are translations and uniform
 * scales — but a `clip` is the caller that would be silently wrong if one ever did, and "silently"
 * is the part worth removing.
 */
private fun Transform2D.applyTo(rect: RectD): DrawRect {
  val corners =
    listOf(
      applyTo(rect.left, rect.top),
      applyTo(rect.right, rect.top),
      applyTo(rect.right, rect.bottom),
      applyTo(rect.left, rect.bottom),
    )
  val left = corners.minOf { it.x }
  val top = corners.minOf { it.y }
  return DrawRect(
    x = left,
    y = top,
    width = corners.maxOf { it.x } - left,
    height = corners.maxOf { it.y } - top,
  )
}

/**
 * How much this transform scales a length, averaged over the two axes.
 *
 * A stroke width is a length, not a coordinate, so it does not go through the matrix — but it does
 * have to follow it, or a scaled chart draws hairlines at their unscaled thickness. Averaging is
 * what a single width can say about a transform that scales the axes differently; the scenes this
 * engine publishes scale them together.
 */
private val Transform2D.averageScale: Double
  get() = if (isIdentity) 1.0 else (kotlin.math.hypot(a, b) + kotlin.math.hypot(c, d)) / 2.0
