package dev.aster.vega.compose.mp

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Draws into Compose's [DrawScope] — the renderer a device actually uses, on every platform.
 *
 * This is the only file in the module that mentions a Compose type. Everything above it is plain
 * Kotlin, which is why [SceneWalk] can be tested in `commonTest` without a composition, and why one
 * traversal serves Android, iOS and the desktop rather than three of them.
 *
 * Every coordinate arriving here is already in the surface's own space, and every gradient is
 * already resolved to absolute points, because [SceneWalk] does both. So this class keeps no matrix
 * stack and needs no notion of an item's bounds: the only nesting it maintains is the clip a group
 * asked for.
 *
 * @param textMeasurer measures and shapes text. Positioning a run is the engine's job and it has
 *   done it already; turning a string into glyphs is the platform's, and a Compose measurer is how
 *   a caller lends us theirs — `rememberTextMeasurer()` in a composable. Without one, text is not
 *   drawn, which is better than text drawn wrongly.
 */
public class DrawScopeTarget(
  private val scope: DrawScope,
  private val textMeasurer: TextMeasurer? = null,
) : SceneDrawTarget {

  /**
   * The clips still open, innermost last.
   *
   * Compose's `clipRect` is a scoped function rather than a pair of calls, which does not fit a
   * push/pop interface — so the open clips are remembered and re-entered around each primitive.
   * That is more work than a graphics-state stack and it is correct, which is the right order to
   * get those two in; a group with a clip is the exception in a chart rather than the rule.
   */
  private val clips = mutableListOf<DrawRect?>()

  override fun beginGroup(clip: DrawRect?) {
    // A group with no clip still pushes, so `endGroup` can pop without counting.
    clips += clip
  }

  override fun endGroup() {
    if (clips.isNotEmpty()) clips.removeAt(clips.size - 1)
  }

  override fun rect(
    rect: DrawRect,
    corners: DrawCorners,
    fill: DrawBrush?,
    stroke: DrawStroke?,
  ) {
    if (!corners.isSquare) {
      // Compose has no four-radius rectangle primitive — `drawRoundRect` takes one `CornerRadius`
      // for
      // all four — so a rounded rectangle becomes a path. The SVG renderer reaches the same answer
      // for the same reason: `rx`/`ry` cannot hold four radii either.
      paint(roundedPath(rect, corners), fill, stroke)
      return
    }
    val topLeft = Offset(rect.x.toFloat(), rect.y.toFloat())
    val size = Size(rect.width.toFloat(), rect.height.toFloat())
    clipped {
      fill?.let {
        scope.drawRect(brush = brush(it), topLeft = topLeft, size = size, alpha = alpha(it))
      }
      stroke?.let {
        scope.drawRect(
          brush = brush(it.brush),
          topLeft = topLeft,
          size = size,
          alpha = alpha(it.brush),
          style = style(it),
        )
      }
    }
  }

  override fun line(from: DrawPoint, to: DrawPoint, stroke: DrawStroke?) {
    val paint = stroke ?: return
    clipped {
      scope.drawLine(
        brush = brush(paint.brush),
        start = Offset(from.x.toFloat(), from.y.toFloat()),
        end = Offset(to.x.toFloat(), to.y.toFloat()),
        strokeWidth = paint.width.toFloat(),
        cap = cap(paint.cap),
        pathEffect = dash(paint),
        alpha = alpha(paint.brush),
      )
    }
  }

  override fun path(commands: List<DrawPathCommand>, fill: DrawBrush?, stroke: DrawStroke?) {
    if (commands.isEmpty()) return
    val path = Path()
    for (command in commands) {
      when (command) {
        is DrawPathCommand.MoveTo -> path.moveTo(command.to.x.toFloat(), command.to.y.toFloat())
        is DrawPathCommand.LineTo -> path.lineTo(command.to.x.toFloat(), command.to.y.toFloat())
        is DrawPathCommand.CubicTo ->
          path.cubicTo(
            command.control1.x.toFloat(),
            command.control1.y.toFloat(),
            command.control2.x.toFloat(),
            command.control2.y.toFloat(),
            command.to.x.toFloat(),
            command.to.y.toFloat(),
          )
        DrawPathCommand.Close -> path.close()
      }
    }
    paint(path, fill, stroke)
  }

  override fun text(run: DrawTextRun, fill: DrawBrush?, stroke: DrawStroke?) {
    val measurer = textMeasurer ?: return
    val colour = (fill as? DrawBrush.Solid)?.paint ?: (stroke?.brush as? DrawBrush.Solid)?.paint
    val layout =
      measurer.measure(
        text = run.text,
        style =
          TextStyle(
            color = colour?.let { colour(it) } ?: Color.Black,
            fontSize = run.fontSize.sp,
            fontWeight = FontWeight(run.fontWeight.coerceIn(1, 1000)),
            fontStyle = if (run.italic) FontStyle.Italic else FontStyle.Normal,
            // A specification names a font family as a string, and resolving that string to a face
            // installed on the device is the platform's business. `Default` is the honest answer
            // here: a caller needing a particular face supplies a measurer that knows it.
            fontFamily = FontFamily.Default,
          ),
      )
    // The engine positions a run at its baseline anchor with alignment already resolved; Compose
    // draws from a top-left corner, and the measured height is what turns one into the other.
    val topLeft = Offset(run.origin.x.toFloat(), (run.origin.y - layout.size.height).toFloat())
    val alpha = colour?.alpha?.toFloat() ?: 1f
    clipped {
      if (run.angleDegrees == 0.0) {
        scope.drawText(layout, topLeft = topLeft, alpha = alpha)
      } else {
        // Rotated about the anchor the engine gave, not the corner Compose draws from.
        scope.rotate(
          degrees = run.angleDegrees.toFloat(),
          pivot = Offset(run.origin.x.toFloat(), run.origin.y.toFloat()),
        ) {
          drawText(layout, topLeft = topLeft, alpha = alpha)
        }
      }
    }
  }

  override fun image(url: String, rect: DrawRect, opacity: Double) {
    // A URL is not an image, and fetching one is not a renderer's job — a caller with the bytes
    // draws
    // them, and one without leaves the space empty rather than blocking a frame on the network.
  }

  // MARK: - Compose translation

  private fun paint(path: Path, fill: DrawBrush?, stroke: DrawStroke?) {
    clipped {
      fill?.let { scope.drawPath(path, brush = brush(it), alpha = alpha(it)) }
      stroke?.let {
        scope.drawPath(path, brush = brush(it.brush), alpha = alpha(it.brush), style = style(it))
      }
    }
  }

  /** Runs [body] inside every clip currently open. */
  private fun clipped(body: () -> Unit) {
    fun enter(index: Int) {
      if (index == clips.size) {
        body()
        return
      }
      val clip = clips[index]
      if (clip == null) {
        enter(index + 1)
      } else {
        scope.clipRect(
          left = clip.x.toFloat(),
          top = clip.y.toFloat(),
          right = (clip.x + clip.width).toFloat(),
          bottom = (clip.y + clip.height).toFloat(),
        ) {
          enter(index + 1)
        }
      }
    }
    enter(0)
  }

  /**
   * The colour without its alpha, which travels separately.
   *
   * Compose takes an `alpha` argument on every draw call, and a gradient's stops carry their own
   * colours — so multiplying an item's opacity into each stop would be both more work and less
   * exact than handing the opacity over once.
   */
  private fun colour(paint: DrawPaint): Color =
    Color(
      red = paint.red.toFloat(),
      green = paint.green.toFloat(),
      blue = paint.blue.toFloat(),
      alpha = 1f,
    )

  private fun alpha(brush: DrawBrush): Float = brush.alpha.toFloat().coerceIn(0f, 1f)

  private fun brush(brush: DrawBrush): Brush =
    when (brush) {
      is DrawBrush.Solid -> SolidColor(colour(brush.paint))
      is DrawBrush.Linear ->
        Brush.linearGradient(
          colorStops = brush.stops.map { it.offset.toFloat() to colour(it.paint) }.toTypedArray(),
          start = Offset(brush.from.x.toFloat(), brush.from.y.toFloat()),
          end = Offset(brush.to.x.toFloat(), brush.to.y.toFloat()),
        )
      is DrawBrush.Radial ->
        Brush.radialGradient(
          colorStops = brush.stops.map { it.offset.toFloat() to colour(it.paint) }.toTypedArray(),
          center = Offset(brush.center.x.toFloat(), brush.center.y.toFloat()),
          radius = brush.radius.toFloat(),
        )
    }

  private fun style(stroke: DrawStroke): Stroke =
    Stroke(
      width = stroke.width.toFloat(),
      cap = cap(stroke.cap),
      join =
        when (stroke.join) {
          DrawLineJoin.Round -> StrokeJoin.Round
          DrawLineJoin.Bevel -> StrokeJoin.Bevel
          DrawLineJoin.Miter -> StrokeJoin.Miter
        },
      miter = stroke.miterLimit.toFloat(),
      pathEffect = dash(stroke),
    )

  private fun cap(cap: DrawLineCap): StrokeCap =
    when (cap) {
      DrawLineCap.Round -> StrokeCap.Round
      DrawLineCap.Square -> StrokeCap.Square
      DrawLineCap.Butt -> StrokeCap.Butt
    }

  private fun dash(stroke: DrawStroke): PathEffect? {
    if (stroke.dash.isEmpty()) return null
    // An even number of intervals is required; a specification may give an odd number, which SVG
    // and
    // Canvas both read as the sequence repeated twice.
    val intervals = if (stroke.dash.size % 2 == 0) stroke.dash else stroke.dash + stroke.dash
    return PathEffect.dashPathEffect(
      intervals.map { it.toFloat() }.toFloatArray(),
      stroke.dashOffset.toFloat(),
    )
  }

  private fun roundedPath(rect: DrawRect, corners: DrawCorners): Path =
    Path().apply {
      addRoundRect(
        RoundRect(
          rect =
            Rect(
              rect.x.toFloat(),
              rect.y.toFloat(),
              (rect.x + rect.width).toFloat(),
              (rect.y + rect.height).toFloat(),
            ),
          topLeft = radius(corners.topLeft),
          topRight = radius(corners.topRight),
          bottomRight = radius(corners.bottomRight),
          bottomLeft = radius(corners.bottomLeft),
        )
      )
    }

  private fun radius(value: Double): CornerRadius = CornerRadius(value.toFloat(), value.toFloat())
}
