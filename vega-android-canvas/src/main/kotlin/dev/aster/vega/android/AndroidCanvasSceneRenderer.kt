package dev.aster.vega.android

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.scene.Fill
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
import dev.aster.vega.scene.SceneBlendMode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.Stroke
import dev.aster.vega.scene.StrokeCap
import dev.aster.vega.scene.StrokeJoin
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.Transform2D

/**
 * Draws an immutable [Scene] onto an Android [Canvas].
 *
 * Contract (PROJECT_BRIEF.md 8.1): save the canvas, apply the viewport and node transforms, apply
 * clipping, draw in scene order, restore, and never mutate the scene.
 *
 * The renderer owns its `Paint`, `Path` and `Matrix` instances and resets them per node, so drawing
 * a frame allocates nothing per mark (PROJECT_BRIEF.md 4.5, 8.2). It is therefore **not
 * thread-safe**: one renderer belongs to one drawing surface.
 */
public class AndroidCanvasSceneRenderer(
  private val textEngine: AndroidTextEngine = AndroidTextEngine(),
  private val imageResolver: AndroidImageResolver = AndroidImageResolver.None,
) {

  private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val androidPath = Path()
  private val matrix = Matrix()
  private val scratchRect = RectF()
  private val scratchSrc = Rect()
  private val scratchDst = RectF()
  private val gradientColors = IntArray(GRADIENT_STOP_LIMIT)
  private val gradientOffsets = FloatArray(GRADIENT_STOP_LIMIT)

  /** Diagnostics from the most recent [render] call, e.g. unresolved images. */
  public var lastDiagnostics: List<dev.aster.vega.model.VegaDiagnostic> = emptyList()
    private set

  /**
   * @param viewport the destination rectangle in device pixels.
   * @param pixelScale device pixels per logical unit, applied on top of the scene's own
   *   coordinates.
   */
  public fun render(scene: Scene, canvas: Canvas, viewport: RectF, pixelScale: Float) {
    val diagnostics = DiagnosticCollector()
    val saveCount = canvas.save()
    try {
      canvas.translate(viewport.left, viewport.top)
      canvas.scale(pixelScale, pixelScale)
      canvas.clipRect(0f, 0f, viewport.width() / pixelScale, viewport.height() / pixelScale)

      scene.background?.let { background ->
        if (!background.isTransparent) canvas.drawColor(background.toArgb())
      }

      drawNode(scene.root, canvas, 1.0, diagnostics)
    } finally {
      canvas.restoreToCount(saveCount)
      lastDiagnostics = diagnostics.diagnostics
    }
  }

  private fun drawNode(
    node: SceneNode,
    canvas: Canvas,
    inheritedOpacity: Double,
    diagnostics: DiagnosticCollector,
  ) {
    if (!node.visible) return
    val opacity = inheritedOpacity * node.opacity
    if (opacity <= 0.0) return

    val saveCount = canvas.save()
    try {
      if (!node.transform.isIdentity) canvas.concat(node.transform.toMatrix(matrix))

      when (node) {
        is GroupNode -> drawGroup(node, canvas, opacity, diagnostics)
        is RectNode -> drawRect(node, canvas, opacity, diagnostics)
        is RuleNode -> drawRule(node, canvas, opacity, diagnostics)
        is PathNode -> drawPath(node, canvas, opacity, diagnostics)
        is SymbolNode -> drawSymbol(node, canvas, opacity, diagnostics)
        is TextNode -> drawText(node, canvas, opacity, diagnostics)
        is ImageNode -> drawImage(node, canvas, opacity, diagnostics)
      }
    } finally {
      canvas.restoreToCount(saveCount)
    }
  }

  private fun drawGroup(
    node: GroupNode,
    canvas: Canvas,
    opacity: Double,
    diagnostics: DiagnosticCollector,
  ) {
    node.clipPath?.let { clip ->
      clip.toAndroidPath(androidPath)
      canvas.clipPath(androidPath)
    }
    val clipRect = node.clip
    if (clipRect != null && node.clipPath == null) {
      canvas.clipRect(
        clipRect.left.toFloat(),
        clipRect.top.toFloat(),
        clipRect.right.toFloat(),
        clipRect.bottom.toFloat(),
      )
    }

    // A group with its own paint draws a rectangle of its declared size, as Vega group marks do.
    val paintRect = node.paintRect
    if (paintRect != null) {
      drawGroupPaint(
        node,
        paintRect,
        canvas,
        opacity,
        diagnostics,
        filled = true,
        stroked = !node.strokeForeground,
      )
    }

    for (child in node.children) drawNode(child, canvas, opacity, diagnostics)

    // `strokeForeground` puts the group's outline over its children rather than under them, which
    // is
    // the whole channel: a cell whose bars run to its own border either covers it or is covered.
    if (paintRect != null && node.strokeForeground) {
      drawGroupPaint(node, paintRect, canvas, opacity, diagnostics, filled = false, stroked = true)
    }
  }

  private fun drawGroupPaint(
    node: GroupNode,
    paintRect: RectD,
    canvas: Canvas,
    opacity: Double,
    diagnostics: DiagnosticCollector,
    filled: Boolean,
    stroked: Boolean,
  ) {
    val rounded = node.roundedPaintPath
    if (rounded != null) {
      rounded.toAndroidPath(androidPath)
    } else {
      // A thin stroke is nudged onto a pixel boundary, as upstream nudges it; see
      // `GroupNode.effectiveStrokeOffset`.
      val offset = node.effectiveStrokeOffset.toFloat()
      scratchRect.set(
        paintRect.left.toFloat() + offset,
        paintRect.top.toFloat() + offset,
        paintRect.right.toFloat() + offset,
        paintRect.bottom.toFloat() + offset,
      )
    }
    if (filled) {
      node.fill?.let { fill ->
        preparePaint(fillPaint, fill, opacity, paintRect, node.blendMode, diagnostics)
        if (rounded != null) canvas.drawPath(androidPath, fillPaint)
        else canvas.drawRect(scratchRect, fillPaint)
      }
    }
    if (stroked) {
      node.stroke?.let { stroke ->
        prepareStroke(stroke, opacity, paintRect, node.blendMode, diagnostics)
        if (rounded != null) canvas.drawPath(androidPath, strokePaint)
        else canvas.drawRect(scratchRect, strokePaint)
      }
    }
  }

  private fun drawRect(
    node: RectNode,
    canvas: Canvas,
    opacity: Double,
    diagnostics: DiagnosticCollector,
  ) {
    val rect = node.rect
    // Rounded corners go through Vega's own outline rather than `drawRoundRect`: that primitive
    // draws one radius on all four corners, and a true arc where Vega draws a Bézier approximation.
    val rounded = node.roundedPath
    if (rounded != null) {
      rounded.toAndroidPath(androidPath)
      node.fill?.let { fill ->
        if (fill.isVisible) {
          preparePaint(fillPaint, fill, opacity, rect, node.blendMode, diagnostics)
          canvas.drawPath(androidPath, fillPaint)
        }
      }
      node.stroke?.let { stroke ->
        if (stroke.isVisible) {
          prepareStroke(stroke, opacity, rect, node.blendMode, diagnostics)
          canvas.drawPath(androidPath, strokePaint)
        }
      }
      return
    }
    scratchRect.set(
      rect.left.toFloat(),
      rect.top.toFloat(),
      rect.right.toFloat(),
      rect.bottom.toFloat(),
    )
    node.fill?.let { fill ->
      if (fill.isVisible) {
        preparePaint(fillPaint, fill, opacity, rect, node.blendMode, diagnostics)
        canvas.drawRect(scratchRect, fillPaint)
      }
    }
    node.stroke?.let { stroke ->
      if (stroke.isVisible) {
        prepareStroke(stroke, opacity, rect, node.blendMode, diagnostics)
        canvas.drawRect(scratchRect, strokePaint)
      }
    }
  }

  private fun drawRule(
    node: RuleNode,
    canvas: Canvas,
    opacity: Double,
    diagnostics: DiagnosticCollector,
  ) {
    if (!node.stroke.isVisible) return
    prepareStroke(node.stroke, opacity, node.bounds, node.blendMode, diagnostics)
    canvas.drawLine(
      node.x1.toFloat(),
      node.y1.toFloat(),
      node.x2.toFloat(),
      node.y2.toFloat(),
      strokePaint,
    )
  }

  private fun drawPath(
    node: PathNode,
    canvas: Canvas,
    opacity: Double,
    diagnostics: DiagnosticCollector,
  ) {
    if (node.path.isEmpty) return
    node.path.toAndroidPath(androidPath)
    val bounds = node.path.bounds

    node.fill?.let { fill ->
      if (fill.isVisible) {
        preparePaint(fillPaint, fill, opacity, bounds, node.blendMode, diagnostics)
        canvas.drawPath(androidPath, fillPaint)
      }
    }
    node.stroke?.let { stroke ->
      if (stroke.isVisible) {
        prepareStroke(stroke, opacity, bounds, node.blendMode, diagnostics)
        canvas.drawPath(androidPath, strokePaint)
      }
    }
  }

  private fun drawSymbol(
    node: SymbolNode,
    canvas: Canvas,
    opacity: Double,
    diagnostics: DiagnosticCollector,
  ) {
    val outline = node.outline
    if (outline.isEmpty) return
    outline.toAndroidPath(androidPath)
    val bounds = outline.bounds

    node.fill?.let { fill ->
      if (fill.isVisible) {
        preparePaint(fillPaint, fill, opacity, bounds, node.blendMode, diagnostics)
        canvas.drawPath(androidPath, fillPaint)
      }
    }
    node.stroke?.let { stroke ->
      if (stroke.isVisible) {
        prepareStroke(stroke, opacity, bounds, node.blendMode, diagnostics)
        canvas.drawPath(androidPath, strokePaint)
      }
    }
  }

  private fun drawText(
    node: TextNode,
    canvas: Canvas,
    opacity: Double,
    diagnostics: DiagnosticCollector,
  ) {
    val fill = node.fill ?: return
    if (!fill.isVisible) return

    val run = node.layout.run
    val paint = textEngine.paintFor(run.style)
    paint.textAlign = textEngine.androidAlign(run.align)
    applyPaint(paint, fill.paint, fill.opacity * opacity, node.bounds, node.blendMode, diagnostics)

    val saveCount = canvas.save()
    try {
      if (node.angleDegrees != 0.0) {
        canvas.rotate(node.angleDegrees.toFloat(), node.x.toFloat(), node.y.toFloat())
      }
      // The layout already accounts for align and baseline; only the vertical offset of the first
      // baseline has to be reapplied here.
      val firstBaseline = node.y + baselineOffset(node)
      for (line in node.layout.lines) {
        canvas.drawText(
          line.text,
          node.x.toFloat(),
          (firstBaseline + line.baselineY).toFloat(),
          paint,
        )
      }
    } finally {
      canvas.restoreToCount(saveCount)
    }
  }

  /**
   * Distance from the node's anchor to the first line's baseline, per Vega's baseline vocabulary.
   */
  private fun baselineOffset(node: TextNode): Double {
    val metrics = node.layout.metrics
    return when (node.layout.run.baseline) {
      dev.aster.vega.scene.TextBaseline.ALPHABETIC -> 0.0
      dev.aster.vega.scene.TextBaseline.TOP,
      dev.aster.vega.scene.TextBaseline.LINE_TOP -> metrics.ascent
      dev.aster.vega.scene.TextBaseline.MIDDLE -> metrics.ascent - metrics.height / 2.0
      dev.aster.vega.scene.TextBaseline.BOTTOM,
      dev.aster.vega.scene.TextBaseline.LINE_BOTTOM -> metrics.ascent - metrics.height
    }
  }

  private fun drawImage(
    node: ImageNode,
    canvas: Canvas,
    opacity: Double,
    diagnostics: DiagnosticCollector,
  ) {
    val bitmap = imageResolver.resolve(node.url)
    if (bitmap == null) {
      // Never silently omit a mark (PROJECT_BRIEF.md 13.3).
      diagnostics.error(
        code = DiagnosticCodes.EXPORT_IMAGE_UNRESOLVED,
        message = "Could not resolve image '${node.url}'; nothing was drawn for this mark",
        operator = "image",
      )
      return
    }

    imagePaint.reset()
    imagePaint.isAntiAlias = node.smooth
    imagePaint.isFilterBitmap = node.smooth
    imagePaint.alpha = (opacity.coerceIn(0.0, 1.0) * 255).toInt()

    val rect = node.rect
    scratchSrc.set(0, 0, bitmap.width, bitmap.height)
    if (node.fit == ImageFit.CONTAIN && bitmap.width > 0 && bitmap.height > 0) {
      val scale =
        minOf(rect.width / bitmap.width.toDouble(), rect.height / bitmap.height.toDouble())
      val drawWidth = bitmap.width * scale
      val drawHeight = bitmap.height * scale
      val left = rect.left + (rect.width - drawWidth) / 2.0
      val top = rect.top + (rect.height - drawHeight) / 2.0
      scratchDst.set(
        left.toFloat(),
        top.toFloat(),
        (left + drawWidth).toFloat(),
        (top + drawHeight).toFloat(),
      )
    } else {
      scratchDst.set(
        rect.left.toFloat(),
        rect.top.toFloat(),
        rect.right.toFloat(),
        rect.bottom.toFloat(),
      )
    }
    canvas.drawBitmap(bitmap, scratchSrc, scratchDst, imagePaint)
  }

  // ---- paint preparation ---------------------------------------------------

  private fun preparePaint(
    paint: Paint,
    fill: Fill,
    opacity: Double,
    bounds: RectD,
    blendMode: SceneBlendMode,
    diagnostics: DiagnosticCollector,
  ) {
    paint.reset()
    paint.isAntiAlias = true
    paint.style = Paint.Style.FILL
    applyPaint(paint, fill.paint, fill.opacity * opacity, bounds, blendMode, diagnostics)
  }

  private fun prepareStroke(
    stroke: Stroke,
    opacity: Double,
    bounds: RectD,
    blendMode: SceneBlendMode,
    diagnostics: DiagnosticCollector,
  ) {
    strokePaint.reset()
    strokePaint.isAntiAlias = true
    strokePaint.style = Paint.Style.STROKE
    strokePaint.strokeWidth = stroke.width.toFloat()
    strokePaint.strokeCap =
      when (stroke.cap) {
        StrokeCap.BUTT -> Paint.Cap.BUTT
        StrokeCap.ROUND -> Paint.Cap.ROUND
        StrokeCap.SQUARE -> Paint.Cap.SQUARE
      }
    strokePaint.strokeJoin =
      when (stroke.join) {
        StrokeJoin.MITER -> Paint.Join.MITER
        StrokeJoin.ROUND -> Paint.Join.ROUND
        StrokeJoin.BEVEL -> Paint.Join.BEVEL
      }
    strokePaint.strokeMiter = stroke.miterLimit.toFloat()
    if (stroke.dashArray.isNotEmpty()) {
      // Android requires an even-length interval array.
      val intervals = stroke.dashArray.map { it.toFloat() }
      val even = if (intervals.size % 2 == 0) intervals else intervals + intervals
      strokePaint.pathEffect = DashPathEffect(even.toFloatArray(), stroke.dashOffset.toFloat())
    }
    applyPaint(strokePaint, stroke.paint, stroke.opacity * opacity, bounds, blendMode, diagnostics)
  }

  private fun applyPaint(
    paint: Paint,
    scenePaint: ScenePaint,
    opacity: Double,
    bounds: RectD,
    blendMode: SceneBlendMode,
    diagnostics: DiagnosticCollector,
  ) {
    when (scenePaint) {
      is ScenePaint.Solid -> {
        paint.shader = null
        paint.color = scenePaint.color.toArgb()
        paint.alpha = ((scenePaint.color.alpha * opacity).coerceIn(0.0, 1.0) * 255).toInt()
      }
      is ScenePaint.LinearGradient -> {
        paint.color = android.graphics.Color.BLACK
        paint.shader = linearShader(scenePaint, bounds, diagnostics)
        paint.alpha = (opacity.coerceIn(0.0, 1.0) * 255).toInt()
      }
      is ScenePaint.RadialGradient -> {
        paint.color = android.graphics.Color.BLACK
        paint.shader = radialShader(scenePaint, bounds, diagnostics)
        paint.alpha = (opacity.coerceIn(0.0, 1.0) * 255).toInt()
      }
    }
    applyBlendMode(paint, blendMode, diagnostics)
  }

  private fun linearShader(
    gradient: ScenePaint.LinearGradient,
    bounds: RectD,
    diagnostics: DiagnosticCollector,
  ): Shader? {
    val count = fillGradientStops(gradient.stops, diagnostics) ?: return null
    return LinearGradient(
      (bounds.left + gradient.x1 * bounds.width).toFloat(),
      (bounds.top + gradient.y1 * bounds.height).toFloat(),
      (bounds.left + gradient.x2 * bounds.width).toFloat(),
      (bounds.top + gradient.y2 * bounds.height).toFloat(),
      gradientColors.copyOf(count),
      gradientOffsets.copyOf(count),
      Shader.TileMode.CLAMP,
    )
  }

  private fun radialShader(
    gradient: ScenePaint.RadialGradient,
    bounds: RectD,
    diagnostics: DiagnosticCollector,
  ): Shader? {
    val count = fillGradientStops(gradient.stops, diagnostics) ?: return null
    val radius = (gradient.radius * maxOf(bounds.width, bounds.height)).toFloat()
    if (radius <= 0f) return null
    return RadialGradient(
      (bounds.left + gradient.cx * bounds.width).toFloat(),
      (bounds.top + gradient.cy * bounds.height).toFloat(),
      radius,
      gradientColors.copyOf(count),
      gradientOffsets.copyOf(count),
      Shader.TileMode.CLAMP,
    )
  }

  /**
   * Copies stops into the reusable arrays. Returns the stop count, or `null` when the gradient
   * cannot be represented, having recorded a diagnostic.
   */
  private fun fillGradientStops(
    stops: List<dev.aster.vega.scene.GradientStop>,
    diagnostics: DiagnosticCollector,
  ): Int? {
    if (stops.size < 2) {
      diagnostics.warn(
        code = DiagnosticCodes.RENDER_UNSUPPORTED_NODE,
        message = "A gradient needs at least two stops; found ${stops.size}",
      )
      return null
    }
    if (stops.size > GRADIENT_STOP_LIMIT) {
      diagnostics.warn(
        code = DiagnosticCodes.RENDER_UNSUPPORTED_NODE,
        message =
          "Gradient has ${stops.size} stops; only the first $GRADIENT_STOP_LIMIT are rendered",
      )
    }
    val count = minOf(stops.size, GRADIENT_STOP_LIMIT)
    for (i in 0 until count) {
      gradientColors[i] = stops[i].color.toArgb()
      gradientOffsets[i] = stops[i].offset.toFloat()
    }
    return count
  }

  private fun applyBlendMode(
    paint: Paint,
    mode: SceneBlendMode,
    diagnostics: DiagnosticCollector,
  ) {
    if (mode == SceneBlendMode.NORMAL) {
      paint.xfermode = null
      return
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      paint.blendMode =
        when (mode) {
          SceneBlendMode.NORMAL -> BlendMode.SRC_OVER
          SceneBlendMode.MULTIPLY -> BlendMode.MULTIPLY
          SceneBlendMode.SCREEN -> BlendMode.SCREEN
          SceneBlendMode.OVERLAY -> BlendMode.OVERLAY
          SceneBlendMode.DARKEN -> BlendMode.DARKEN
          SceneBlendMode.LIGHTEN -> BlendMode.LIGHTEN
          SceneBlendMode.COLOR_DODGE -> BlendMode.COLOR_DODGE
          SceneBlendMode.COLOR_BURN -> BlendMode.COLOR_BURN
          SceneBlendMode.HARD_LIGHT -> BlendMode.HARD_LIGHT
          SceneBlendMode.SOFT_LIGHT -> BlendMode.SOFT_LIGHT
          SceneBlendMode.DIFFERENCE -> BlendMode.DIFFERENCE
          SceneBlendMode.EXCLUSION -> BlendMode.EXCLUSION
          SceneBlendMode.HUE -> BlendMode.HUE
          SceneBlendMode.SATURATION -> BlendMode.SATURATION
          SceneBlendMode.COLOR -> BlendMode.COLOR
          SceneBlendMode.LUMINOSITY -> BlendMode.LUMINOSITY
        }
      return
    }
    // Pre-Q only the PorterDuff subset exists; anything outside it is reported rather than
    // approximated (PROJECT_BRIEF.md 3.3).
    val porterDuff =
      when (mode) {
        SceneBlendMode.MULTIPLY -> PorterDuff.Mode.MULTIPLY
        SceneBlendMode.SCREEN -> PorterDuff.Mode.SCREEN
        SceneBlendMode.OVERLAY -> PorterDuff.Mode.OVERLAY
        SceneBlendMode.DARKEN -> PorterDuff.Mode.DARKEN
        SceneBlendMode.LIGHTEN -> PorterDuff.Mode.LIGHTEN
        SceneBlendMode.NORMAL -> null
        // `PorterDuff` stops at the five above. The rest are reported below rather than swapped for
        // whichever mode looks closest.
        else -> null
      }
    if (porterDuff == null) {
      diagnostics.warn(
        code = DiagnosticCodes.RENDER_UNSUPPORTED_BLEND_MODE,
        message = "Blend mode $mode is unavailable below API 29; drawn with normal blending",
      )
      paint.xfermode = null
    } else {
      paint.xfermode = PorterDuffXfermode(porterDuff)
    }
  }

  public companion object {
    /**
     * Reusable gradient arrays are sized once; longer gradients are truncated with a diagnostic.
     */
    public const val GRADIENT_STOP_LIMIT: Int = 32
  }
}

/** Writes this transform into [out] and returns it, avoiding a per-node `Matrix` allocation. */
internal fun Transform2D.toMatrix(out: Matrix): Matrix {
  out.reset()
  out.setValues(
    floatArrayOf(
      a.toFloat(),
      c.toFloat(),
      e.toFloat(),
      b.toFloat(),
      d.toFloat(),
      f.toFloat(),
      0f,
      0f,
      1f,
    )
  )
  return out
}

/** Rewrites [out] to hold this path's commands. */
internal fun PathData.toAndroidPath(out: Path): Path {
  out.reset()
  for (command in commands) {
    when (command) {
      is PathCommand.MoveTo -> out.moveTo(command.x.toFloat(), command.y.toFloat())
      is PathCommand.LineTo -> out.lineTo(command.x.toFloat(), command.y.toFloat())
      is PathCommand.CubicTo ->
        out.cubicTo(
          command.x1.toFloat(),
          command.y1.toFloat(),
          command.x2.toFloat(),
          command.y2.toFloat(),
          command.x.toFloat(),
          command.y.toFloat(),
        )
      PathCommand.Close -> out.close()
    }
  }
  return out
}

/**
 * Resolves an image URL to a bitmap. Returning `null` produces a diagnostic, never a silent gap.
 */
public fun interface AndroidImageResolver {
  public fun resolve(url: String): Bitmap?

  public companion object {
    /** Resolves nothing; every image mark reports `VEGA_EXPORT_IMAGE_UNRESOLVED`. */
    public val None: AndroidImageResolver = AndroidImageResolver { null }
  }
}
