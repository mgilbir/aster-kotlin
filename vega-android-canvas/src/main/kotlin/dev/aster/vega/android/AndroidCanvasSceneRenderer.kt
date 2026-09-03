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
import dev.aster.vega.scene.paintOrder

/**
 * Draws an immutable [Scene] onto an Android [Canvas].
 *
 * Contract (ADR 0002): save the canvas, apply the viewport and node transforms, apply clipping,
 * draw in scene order, restore, and never mutate the scene.
 *
 * The renderer owns its `Paint`, `Path` and `Matrix` instances and resets them per node, so drawing
 * a frame allocates nothing per mark (ADR 0009). It is therefore **not thread-safe**: one renderer
 * belongs to one drawing surface.
 */
public class AndroidCanvasSceneRenderer(
  private val textEngine: AndroidTextEngine = AndroidTextEngine(),
  private val imageResolver: AndroidImageResolver = AndroidImageResolver.None,
  /**
   * Told the first time a URL cannot be resolved, and not again for that URL.
   *
   * An unresolved image leaves a hole in the chart and the draw carries on, which is right — a
   * chart is better with one mark missing than not drawn at all — and a diagnostic is the only
   * trace. A host that wants to react to the hole rather than read a list gets this.
   *
   * **Called from the draw.** Treat it as a report and not as a place to set state that a layout
   * pass would read: launch, log, enqueue.
   */
  private val onUnresolvedImage: ((String) -> Unit)? = null,
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

      drawNode(scene.root, canvas, diagnostics)
    } finally {
      canvas.restoreToCount(saveCount)
      lastDiagnostics = diagnostics.diagnostics
    }
  }

  /**
   * Draws one node, with its **own** opacity and no inheritance.
   *
   * Opacity does not descend. That reads like an omission, so it is worth saying why: upstream's
   * canvas renderer saves the graphics state, translates and clips on the way into a group and
   * never touches `globalAlpha`, and its SVG renderer emits `opacity` on the group's background
   * `path` while leaving the child element bare. A group's opacity paints its own panel and nothing
   * else.
   *
   * This renderer used to multiply an inherited opacity into every descendant, which drew a
   * half-opaque group's opaque child at half. Nothing caught it: the differential fixtures compare
   * scene trees, and a scene tree is the same either way. The Swift renderer's pixel tests found
   * it, having made the identical mistake.
   */
  private fun drawNode(node: SceneNode, canvas: Canvas, diagnostics: DiagnosticCollector) {
    if (!node.visible) return
    // A fully transparent *group* still draws its children — upstream renders them and only its own
    // background disappears. For everything else nothing would be painted anyway, so leaving early
    // is the same picture drawn faster.
    if (node.opacity <= 0.0 && node !is GroupNode) return
    val opacity = node.opacity

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
    // This is the only thing the group's own opacity applies to.
    val paintRect = node.paintRect
    if (paintRect != null && opacity > 0.0) {
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

    // `paintOrder`, not `children`: an item's `zindex` reorders what is painted, and only the SVG
    // renderer was applying it. A `zindex` therefore raised a mark in the export and not on the
    // screen, and the hit index — which numbers entries in walk order — sent the tap to the mark
    // underneath.
    for (child in paintOrder(node.children)) drawNode(child, canvas, diagnostics)

    // `strokeForeground` puts the group's outline over its children rather than under them, which
    // is
    // the whole channel: a cell whose bars run to its own border either covers it or is covered.
    if (paintRect != null && node.strokeForeground && opacity > 0.0) {
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
    // **`node.bounds` for a gradient, `node.rect` for the geometry**, and the two differ by the
    // stroke's half-width. Upstream resolves a gradient against `item.bounds`, which `boundStroke`
    // has already widened — `vega-scenegraph`'s `color(context, item, value)` is
    // `gradient(context, value, item.bounds)` — and both the SVG renderer here and the Compose
    // Multiplatform walk do the same. This one passed `rect`, so a stroked, gradient-filled mark
    // had its ramp resolved over a slightly smaller box than everywhere else: the stops landed at
    // different coordinates on Android than in the exported SVG of the same chart.
    val gradientBounds = node.bounds
    // Rounded corners go through Vega's own outline rather than `drawRoundRect`: that primitive
    // draws one radius on all four corners, and a true arc where Vega draws a Bézier approximation.
    val rounded = node.roundedPath
    if (rounded != null) {
      rounded.toAndroidPath(androidPath)
      node.fill?.let { fill ->
        if (fill.isVisible) {
          preparePaint(fillPaint, fill, opacity, gradientBounds, node.blendMode, diagnostics)
          canvas.drawPath(androidPath, fillPaint)
        }
      }
      node.stroke?.let { stroke ->
        if (stroke.isVisible) {
          prepareStroke(stroke, opacity, gradientBounds, node.blendMode, diagnostics)
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
        preparePaint(fillPaint, fill, opacity, gradientBounds, node.blendMode, diagnostics)
        canvas.drawRect(scratchRect, fillPaint)
      }
    }
    node.stroke?.let { stroke ->
      if (stroke.isVisible) {
        prepareStroke(stroke, opacity, gradientBounds, node.blendMode, diagnostics)
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

  /**
   * A [RasterImage] as a [Bitmap], cached by the raster's own digest.
   *
   * The digest is stable for identical pixels, so a chart redrawn per frame builds each raster
   * once. The pixels are already `0xAARRGGBB`, which is exactly `ARGB_8888`, so this is a copy
   * rather than a conversion.
   */
  private fun rasterBitmap(raster: dev.aster.vega.scene.RasterImage): Bitmap? {
    if (raster.width <= 0 || raster.height <= 0) return null
    rasterCache[raster.digest]?.let {
      return it
    }
    val bitmap =
      Bitmap.createBitmap(raster.pixels, raster.width, raster.height, Bitmap.Config.ARGB_8888)
    // A small bound rather than none: a scene holds its own rasters, and this only exists to
    // survive
    // between frames.
    if (rasterCache.size > RASTER_CACHE_LIMIT) rasterCache.clear()
    rasterCache[raster.digest] = bitmap
    return bitmap
  }

  private val rasterCache = HashMap<Long, Bitmap>()

  /**
   * A URL asked of the resolver **once**, not once per frame.
   *
   * `render` runs per frame and an `image` mark resolves its URL every time it is drawn, so a
   * resolver reachable by a host — which is new; until now nothing outside this module could set
   * one — would have been called on every frame of every pan, for every image on the chart. A host
   * fetching over the network would have been doing it sixty times a second.
   *
   * **A refusal is remembered too**, which is the half that is easy to miss: caching only successes
   * sends an address that has already said no back to the resolver on every frame, which is the
   * expensive case rather than the cheap one. It is also what makes [onUnresolvedImage] once per
   * URL rather than once per frame, and a report that fired per frame could not be built on.
   *
   * A null value means "asked, and there is nothing there". [clearImageCache] gives a transient
   * failure a second chance, and is how a host says the image behind a URL has changed.
   */
  private val urlCache = HashMap<String, Bitmap?>()

  private fun resolveUrl(url: String): Bitmap? {
    // A raster the engine produced carries its pixels and an empty address; it is handled above,
    // and
    // reaching here with an empty URL means there was nothing to ask for. Not the host's business,
    // and not something to report as an unresolved image either.
    if (url.isEmpty()) return null
    if (urlCache.containsKey(url)) return urlCache[url]
    val bitmap = imageResolver.resolve(url)
    // The same small bound the raster cache keeps, for the same reason. Clearing may let a URL be
    // reported a second time, which is the honest cost of not growing without limit.
    if (urlCache.size > URL_CACHE_LIMIT) urlCache.clear()
    urlCache[url] = bitmap
    if (bitmap == null) onUnresolvedImage?.invoke(url)
    return bitmap
  }

  /**
   * Forgets every resolved and refused URL, so the next draw asks the resolver again.
   *
   * For a host whose image behind an address has changed, and for one giving a transient failure
   * another go. Engine-produced rasters are not affected: they are keyed by the digest of their own
   * pixels and cannot go stale.
   */
  public fun clearImageCache() {
    urlCache.clear()
  }

  private fun drawImage(
    node: ImageNode,
    canvas: Canvas,
    opacity: Double,
    diagnostics: DiagnosticCollector,
  ) {
    // A raster the engine produced comes first, because it needs no resolver and no address: a
    // `heatmap`
    // or an `isocontour` builds its image inside the compile and carries the pixels. Asking the
    // resolver
    // for its (empty) URL is how every one of those was silently dropped — the SVG renderer encodes
    // them
    // as data URLs and drew them all along, so the two renderers disagreed about a whole mark type.
    val bitmap = node.raster?.let { rasterBitmap(it) } ?: resolveUrl(node.url)
    if (bitmap == null) {
      // Never silently omit a mark (ADR 0010).
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
      strokePaint.pathEffect = dashEffect(stroke)
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

  /**
   * The dash pattern as a `DashPathEffect`, built once per distinct pattern.
   *
   * This file's header says it allocates nothing per mark, and this line did: three lists and a
   * `DashPathEffect` for **every dashed node on every frame**. A chart with a dashed gridline per
   * tick and a dashed rule per series allocates a few dozen of each per frame, which during a pan
   * is a few thousand a second — and the pattern is the same every time, because it comes from the
   * specification rather than from the data. Bounded, so a document that varies the pattern per
   * datum cannot grow it without limit; a miss is exactly the work that used to happen always.
   */
  private fun dashEffect(stroke: Stroke): DashPathEffect {
    val key = stroke.dashArray to stroke.dashOffset
    dashEffects[key]?.let {
      // Re-inserted, so it moves to the young end: least-recently-*used*, not least-recently-added.
      dashEffects.remove(key)
      dashEffects[key] = it
      return it
    }
    // Android requires an even-length interval array, so an odd pattern is written twice — which is
    // what CSS says an odd `stroke-dasharray` means as well.
    val count = stroke.dashArray.size
    val even = if (count % 2 == 0) count else count * 2
    val intervals = FloatArray(even) { stroke.dashArray[it % count].toFloat() }
    val effect = DashPathEffect(intervals, stroke.dashOffset.toFloat())
    if (dashEffects.size >= DASH_CACHE_LIMIT) {
      dashEffects.remove(dashEffects.keys.first())
    }
    dashEffects[key] = effect
    return effect
  }

  private val dashEffects = LinkedHashMap<Pair<List<Double>, Double>, DashPathEffect>()

  /**
   * A gradient shader, built once per (gradient, box) pair.
   *
   * The other half of the per-mark allocation this file's header disclaims: a `LinearGradient` or
   * `RadialGradient` plus two array copies for every gradient-painted node on every frame. A
   * gradient legend is one node redrawn per frame; a chart whose bars are gradient-filled is one
   * per bar. The box is part of the key because that is what the stops are resolved against — the
   * same gradient over two different marks is two different shaders.
   */
  private fun shader(
    key: Pair<ScenePaint, RectD>,
    build: () -> Shader?,
  ): Shader? {
    // `containsKey` rather than a null check, so a gradient that legitimately builds **no** shader
    // — too many stops, a zero radius — is remembered as such rather than rebuilt every frame.
    if (shaders.containsKey(key)) {
      val hit = shaders.remove(key)
      shaders[key] = hit
      return hit
    }
    val built = build()
    if (shaders.size >= SHADER_CACHE_LIMIT) shaders.remove(shaders.keys.first())
    shaders[key] = built
    return built
  }

  private val shaders = LinkedHashMap<Pair<ScenePaint, RectD>, Shader?>()

  private fun linearShader(
    gradient: ScenePaint.LinearGradient,
    bounds: RectD,
    diagnostics: DiagnosticCollector,
  ): Shader? =
    shader(gradient to bounds) {
      val count = fillGradientStops(gradient.stops, diagnostics) ?: return@shader null
      LinearGradient(
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
  ): Shader? =
    shader(gradient to bounds) {
      val count = fillGradientStops(gradient.stops, diagnostics) ?: return@shader null
      val radius = (gradient.radius * maxOf(bounds.width, bounds.height)).toFloat()
      if (radius <= 0f) return@shader null
      RadialGradient(
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
    // approximated (ADR 0011). The table is [porterDuffFor], which is where it can be tested: this
    // branch needs a device below API 29 and the emulator runs far above it, but what the row in
    // `SUPPORTED_FEATURES.md` claims is the *table*, and a table is testable anywhere.
    val porterDuff = porterDuffFor(mode)
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

  /**
   * The `PorterDuff` equivalent of a CSS blend mode, or null where there is none.
   *
   * Four of the sixteen, and the omission that matters is **`MULTIPLY`**. Android documents its own
   * as `[Sa * Da, Sc * Dc]`, which is *modulate* rather than CSS `multiply`: the two agree only
   * where the destination is fully opaque, and where it is transparent modulate produces
   * transparent while CSS multiply produces the source unchanged. So a `"blend": "multiply"` mark
   * drawn over an empty part of a chart — the ordinary case, since a chart's background is
   * transparent unless the specification paints one — simply vanished. It is refused rather than
   * approximated, like the other ten.
   *
   * `NORMAL` never reaches here: [applyBlendMode] clears the transfer mode and returns before the
   * API check, so it is available on every device.
   *
   * Internal so `AndroidBlendModeTableTest` can hold it: the *branch* that calls it needs a device
   * below API 29, which this project's test matrix has no way to run, but the table is what the
   * documented limitation is about.
   */
  internal fun porterDuffFor(mode: SceneBlendMode): PorterDuff.Mode? =
    when (mode) {
      SceneBlendMode.SCREEN -> PorterDuff.Mode.SCREEN
      SceneBlendMode.OVERLAY -> PorterDuff.Mode.OVERLAY
      SceneBlendMode.DARKEN -> PorterDuff.Mode.DARKEN
      SceneBlendMode.LIGHTEN -> PorterDuff.Mode.LIGHTEN
      // See the note above: `PorterDuff` stops at the four, and `MULTIPLY` is not the CSS one.
      else -> null
    }

  public companion object {
    /**
     * Reusable gradient arrays are sized once; longer gradients are truncated with a diagnostic.
     */
    public const val GRADIENT_STOP_LIMIT: Int = 32

    /**
     * How many distinct dash patterns and gradient shaders are kept.
     *
     * Both come from the *specification* rather than from the data on every chart anyone draws, so
     * a handful is the realistic working set and the numbers exist only so a document that varies
     * one per datum cannot grow the cache without bound.
     */
    private const val DASH_CACHE_LIMIT: Int = 64

    private const val SHADER_CACHE_LIMIT: Int = 128

    /**
     * How many decoded rasters to keep. A chart with more distinct images than this is not the
     * case.
     */
    private const val RASTER_CACHE_LIMIT: Int = 16

    /**
     * How many resolved addresses to keep, successes and refusals together. Larger than the raster
     * bound because an entry may be a remembered `null`, which costs nothing to hold and saves a
     * fetch per frame.
     */
    private const val URL_CACHE_LIMIT: Int = 64
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
