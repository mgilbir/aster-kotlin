package dev.aster.vega.compose.mp

import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.FontStyle
import dev.aster.vega.scene.ForeignPaint
import dev.aster.vega.scene.GroupNode
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
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.Transform2D
import kotlin.math.abs
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
    if (!node.visible) return
    // Nothing else paints anything at zero opacity, so leaving early is the same picture drawn
    // faster. A group is the exception: see the note on opacity above.
    if (node.opacity <= 0.0 && node !is GroupNode) return
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
        )
      }
      is RuleNode ->
        target.line(
          local.applyTo(DrawPoint(node.x1, node.y1)),
          local.applyTo(DrawPoint(node.x2, node.y2)),
          stroke(node.stroke, node.opacity, node.bounds, local),
        )
      is PathNode ->
        if (!node.absent) {
          target.path(
            commands(node.path, local),
            brush(node.fill, node.opacity, node.bounds, local),
            stroke(node.stroke, node.opacity, node.bounds, local),
          )
        }
      is SymbolNode ->
        // A symbol's shape is already a path — the engine turns a circle, a cross or a custom SVG
        // string into one — so there is no shape vocabulary for a renderer to reimplement.
        target.path(
          commands(node.outline, local),
          brush(node.fill, node.opacity, node.bounds, local),
          stroke(node.stroke, node.opacity, node.bounds, local),
        )
      is TextNode ->
        if (!node.absent) {
          val run = node.layout.run
          val style = run.style
          target.text(
            DrawTextRun(
              text = run.text,
              origin = local.applyTo(DrawPoint(node.x, node.y)),
              fontFamily = style.fontFamily,
              fontSize = style.fontSize,
              fontWeight = style.fontWeight,
              italic = style.fontStyle == FontStyle.ITALIC,
              angleDegrees = node.angleDegrees,
            ),
            brush(node.fill, node.opacity, node.bounds, local),
            stroke(node.stroke, node.opacity, node.bounds, local),
          )
        }
      is ImageNode ->
        target.image(
          node.url,
          local.applyTo(RectD(node.x, node.y, node.x + node.width, node.y + node.height)),
          node.opacity,
        )
    }
  }

  private fun walkGroup(node: GroupNode, local: Transform2D, target: SceneDrawTarget) {
    // A group's clip is in its own space, so it is mapped through the transform just composed.
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
      )
    } else if (panel != null) {
      // `strokeForeground` puts the outline over the children; the fill still goes underneath.
      target.rect(panel, corners(node), brush(node.fill, node.opacity, paintRect!!, local), null)
    }

    for (child in node.children) walk(child, local, target)

    if (panel != null && node.strokeForeground) {
      target.rect(panel, corners(node), null, stroke(node.stroke, node.opacity, paintRect!!, local))
    }

    target.endGroup()
  }

  // MARK: reading the scene

  private fun commands(path: PathData, transform: Transform2D): List<DrawPathCommand> =
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

  private fun corners(node: RectNode): DrawCorners =
    DrawCorners(
      node.cornerRadiusTopLeft ?: node.cornerRadius,
      node.cornerRadiusTopRight ?: node.cornerRadius,
      node.cornerRadiusBottomRight ?: node.cornerRadius,
      node.cornerRadiusBottomLeft ?: node.cornerRadius,
    )

  private fun corners(node: GroupNode): DrawCorners =
    DrawCorners(
      node.cornerRadiusTopLeft ?: node.cornerRadius,
      node.cornerRadiusTopRight ?: node.cornerRadius,
      node.cornerRadiusBottomRight ?: node.cornerRadius,
      node.cornerRadiusBottomLeft ?: node.cornerRadius,
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
 * A rectangle's image, normalised — a transform with a negative scale would otherwise invert it.
 */
private fun Transform2D.applyTo(rect: RectD): DrawRect {
  val one = applyTo(rect.left, rect.top)
  val two = applyTo(rect.right, rect.bottom)
  return DrawRect(
    x = minOf(one.x, two.x),
    y = minOf(one.y, two.y),
    width = abs(two.x - one.x),
    height = abs(two.y - one.y),
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
