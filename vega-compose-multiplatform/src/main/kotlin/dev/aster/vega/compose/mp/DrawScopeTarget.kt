package dev.aster.vega.compose.mp

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.aster.vega.scene.FontStyle as SceneFontStyle
import dev.aster.vega.scene.RasterImage
import dev.aster.vega.scene.SceneBlendMode
import dev.aster.vega.scene.TextStyle as SceneTextStyle
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.roundToInt

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
  /**
   * Turns a specification's font family name into a Compose family, as [ComposeTextEngine] does.
   *
   * It has to be the **same** resolver the engine measured with. A chart laid out in one face and
   * drawn in another is the defect that `MetricTextEngine` used to cause here, with the faces the
   * wrong way round; `VegaChart` takes the engine itself and reads this off it so a caller cannot
   * pass one and forget the other.
   */
  private val fontFamilyResolver: (String) -> FontFamily? = ::genericFontFamily,
  /**
   * Resolves an image URL to something drawable. Null draws no URL images, which is the default.
   *
   * A URL is not an image and fetching one is not a renderer's job: a chart is often data a reader
   * pasted, so the address in it is the specification's choice and the policy about following it
   * belongs to the host — the same argument `DataLoader` makes for data.
   */
  private val resolveImage: ((String) -> ImageBitmap?)? = null,
  /**
   * Where decoded images are kept, across frames.
   *
   * A target is built **per frame** — `VegaChart` constructs one inside the `Canvas` draw lambda —
   * so a cache held in this object is a cache with a lifetime of one draw. That was survivable
   * while the only images were engine-produced rasters and nothing else could reach them; it
   * stopped being survivable the moment a host could supply a resolver, because then every frame
   * calls the host's fetcher once per image. A heatmap's raster was already being PNG-decoded per
   * frame.
   *
   * So the cache is the caller's to own, and `VegaChart` remembers one against the composition. The
   * default is a fresh one, which is right for a caller drawing a scene once — an export, a test.
   */
  private val imageCache: ImageCache = ImageCache(),
  /**
   * Told the first time a URL cannot be resolved, and not again.
   *
   * "The first time" is what [imageCache] makes possible: a refusal is remembered there, so this
   * fires once per URL per cache rather than once per frame — which is what a callback from a
   * *draw* has to do to be usable at all. See `VegaChart`'s parameter of the same name for what a
   * host may safely do in it.
   */
  private val onUnresolvedImage: ((String) -> Unit)? = null,
) : SceneDrawTarget {

  /**
   * URLs the resolver could not answer, for a caller that wants to say so.
   *
   * This target's own, so it holds what *this draw* could not resolve. The set that outlives a
   * frame is [ImageCache.unresolvedImages], which is where a host reads them.
   */
  public val unresolvedImages: MutableList<String> = mutableListOf()

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
    blend: SceneBlendMode,
  ) {
    if (!corners.isSquare) {
      // Compose has no four-radius rectangle primitive — `drawRoundRect` takes one `CornerRadius`
      // for
      // all four — so a rounded rectangle becomes a path. The SVG renderer reaches the same answer
      // for the same reason: `rx`/`ry` cannot hold four radii either.
      paint(roundedPath(rect, corners), fill, stroke, blend)
      return
    }
    val topLeft = Offset(rect.x.toFloat(), rect.y.toFloat())
    val size = Size(rect.width.toFloat(), rect.height.toFloat())
    clipped {
      val mode = blendMode(blend)
      fill?.let {
        scope.drawRect(
          brush = brush(it),
          topLeft = topLeft,
          size = size,
          alpha = alpha(it),
          blendMode = mode,
        )
      }
      stroke?.let {
        scope.drawRect(
          brush = brush(it.brush),
          topLeft = topLeft,
          size = size,
          alpha = alpha(it.brush),
          style = style(it),
          blendMode = mode,
        )
      }
    }
  }

  override fun line(
    from: DrawPoint,
    to: DrawPoint,
    stroke: DrawStroke?,
    blend: SceneBlendMode,
  ) {
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
        blendMode = blendMode(blend),
      )
    }
  }

  override fun path(
    commands: List<DrawPathCommand>,
    fill: DrawBrush?,
    stroke: DrawStroke?,
    blend: SceneBlendMode,
  ) {
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
    paint(path, fill, stroke, blend)
  }

  override fun text(
    run: DrawTextRun,
    fill: DrawBrush?,
    stroke: DrawStroke?,
    blend: SceneBlendMode,
  ) {
    val measurer = textMeasurer ?: return
    // **What paints the glyphs**, which is three different answers and used to be one.
    //
    // - A *gradient* fill painted them solid **black**: the cast to `DrawBrush.Solid` failed and
    //   `?: Color.Black` caught it. The Android View draws the gradient, and a text mark filled
    //   from a colour scale is how a chart labels its own bars in the scale's own colours.
    // - A run with a stroke and **no fill** was *filled* with the stroke's colour, which is a
    //   different picture from an outline — heavier, and solid where upstream leaves the counters
    //   open. `strokeText` with no `fillText` is what upstream's canvas renderer does.
    // - A run with neither was drawn in black rather than not drawn.
    val paintBrush = fill ?: stroke?.brush
    if (paintBrush == null) return
    val outlineOnly = fill == null && stroke != null
    val layout =
      measurer.measure(
        text = run.text,
        style =
          composeStyleOf(
              SceneTextStyle(
                fontFamily = run.fontFamily,
                fontSize = run.fontSize,
                fontWeight = run.fontWeight,
                fontStyle = if (run.italic) SceneFontStyle.ITALIC else SceneFontStyle.NORMAL,
                letterSpacing = run.letterSpacing,
              ),
              // **One sp per scene unit divided by the density**, and this is the line the whole
              // conversion turns on. Every coordinate arriving here is in scene units, and the
              // scope
              // this draws into has already been scaled by the density — so a Compose pixel *is* a
              // scene unit here. An `sp` is not: it is a pixel times the density times the reader's
              // font scale. Passing `run.fontSize.sp` therefore applied the density a second time,
              // and at 3x every label was drawn three times the size of the box the layout reserved
              // for it. The one test that could have caught it pins the density to 1.
              //
              // Dividing by the density leaves the font scale, which is deliberate: the engine
              // measured with it, so the reserved box is already the larger one.
              spPerSceneUnit = 1f / scope.density,
              fontFamilyResolver = fontFamilyResolver,
            )
            .copy(
              brush = brush(paintBrush),
              // A stroked run is drawn as an outline of the stroke's own width, not as a fill.
              drawStyle = if (outlineOnly) style(stroke) else Fill,
            ),
      )
    // The walk has already resolved `align` and `baseline` into a pen position, which is the
    // *baseline*
    // of this line — Compose draws from a top-left corner, and the run's ascent is what turns one
    // into
    // the other. The ascent comes from the scene's own layout rather than from this measurement, so
    // the
    // glyphs sit where the chart's arithmetic put them even if the two disagree about the font.
    val topLeft = Offset(run.origin.x.toFloat(), (run.origin.y - run.ascent).toFloat())
    val alpha = alpha(paintBrush)
    clipped {
      if (run.angleDegrees == 0.0) {
        scope.drawText(layout, topLeft = topLeft, alpha = alpha, blendMode = blendMode(blend))
      } else {
        // Rotation turns about the run's **anchor**, not its pen position: a rotated axis label
        // pivots
        // on the point the axis put it at, and pivoting on the left end of the text instead swings
        // a
        // right-aligned label away from its tick.
        scope.rotate(
          degrees = run.angleDegrees.toFloat(),
          pivot = Offset(run.anchor.x.toFloat(), run.anchor.y.toFloat()),
        ) {
          drawText(layout, topLeft = topLeft, alpha = alpha, blendMode = blendMode(blend))
        }
      }
    }
  }

  override fun image(
    url: String,
    raster: RasterImage?,
    rect: DrawRect,
    fit: DrawImageFit,
    smooth: Boolean,
    opacity: Double,
    blend: SceneBlendMode,
  ) {
    val decoded = resolve(url, raster)
    if (decoded == null) {
      // Said rather than swallowed: a hole in a chart that nobody mentions looks like a
      // specification
      // that asked for nothing, which is the opposite of this project's discipline about silence.
      if (url.isNotEmpty()) unresolvedImages += url
      return
    }

    val destination = if (fit == DrawImageFit.CONTAIN) contained(decoded, rect) else rect
    clipped {
      scope.drawImage(
        image = decoded,
        dstOffset = IntOffset(destination.x.roundToInt(), destination.y.roundToInt()),
        dstSize =
          IntSize(
            destination.width.roundToInt().coerceAtLeast(1),
            destination.height.roundToInt().coerceAtLeast(1),
          ),
        alpha = opacity.toFloat().coerceIn(0f, 1f),
        filterQuality = if (smooth) FilterQuality.Medium else FilterQuality.None,
        blendMode = blendMode(blend),
      )
    }
  }

  /**
   * The image for a URL or a raster, decoded once and kept.
   *
   * Keyed by the raster's digest, which is stable for identical pixels, or by the URL — so a chart
   * redrawn per frame decodes each image once rather than once per frame.
   */
  private fun resolve(url: String, raster: RasterImage?): ImageBitmap? {
    if (raster != null) {
      imageCache.raster(raster.digest)?.let {
        return it
      }
      return decodeRaster(raster)?.also { imageCache.putRaster(raster.digest, it) }
    }
    if (url.isEmpty()) return null
    imageCache.url(url)?.let {
      return it
    }
    // **A refusal is remembered too.** Only successes were cached, so an address that had already
    // said no went back to the host's resolver on every frame — a fetch per frame for an image that
    // will never appear. Once per URL is both the cheaper answer and the only one a report of it
    // could be built on. `ImageCache.clear()` gives a transient failure a second chance.
    if (imageCache.isUnresolvable(url)) return null
    // A `data:` URL needs no host, so it is answered here rather than pushed onto a resolver that
    // would
    // have to know how. Anything else is the host's business.
    val decoded = if (url.startsWith("data:")) decodeDataUrl(url) else resolveImage?.invoke(url)
    if (decoded == null) {
      imageCache.putUnresolvable(url)
      onUnresolvedImage?.invoke(url)
      return null
    }
    imageCache.putUrl(url, decoded)
    return decoded
  }

  @OptIn(ExperimentalEncodingApi::class)
  private fun decodeDataUrl(url: String): ImageBitmap? {
    val comma = url.indexOf(',')
    if (comma < 0) return null
    return runCatching { decodeImageBytes(Base64.decode(url.substring(comma + 1))) }.getOrNull()
  }

  /** Fits an image inside [rect], centred, preserving its aspect ratio. */
  private fun contained(image: ImageBitmap, rect: DrawRect): DrawRect {
    val width = image.width.toDouble()
    val height = image.height.toDouble()
    if (width <= 0.0 || height <= 0.0) return rect
    val scale = minOf(rect.width / width, rect.height / height)
    val drawnWidth = width * scale
    val drawnHeight = height * scale
    return DrawRect(
      x = rect.x + (rect.width - drawnWidth) / 2.0,
      y = rect.y + (rect.height - drawnHeight) / 2.0,
      width = drawnWidth,
      height = drawnHeight,
    )
  }

  // MARK: - Compose translation

  private fun paint(path: Path, fill: DrawBrush?, stroke: DrawStroke?, blend: SceneBlendMode) {
    val mode = blendMode(blend)
    clipped {
      fill?.let { scope.drawPath(path, brush = brush(it), alpha = alpha(it), blendMode = mode) }
      stroke?.let {
        scope.drawPath(
          path,
          brush = brush(it.brush),
          alpha = alpha(it.brush),
          style = style(it),
          blendMode = mode,
        )
      }
    }
  }

  /**
   * CSS `mix-blend-mode` as Compose's own, which has all sixteen.
   *
   * The MP renderer ignored the channel entirely and said nothing about it, while the Android View
   * mapped every mode — so one specification produced two different pictures and only one of the
   * two hosts admitted to a gap.
   *
   * The Android caveat from the View renderer still applies underneath: Compose maps to
   * `android.graphics.BlendMode` on API 29 and up and to `PorterDuff` below, where the eleven modes
   * past `LIGHTEN` do not exist and `MULTIPLY` is *modulate* rather than CSS multiply. That is a
   * platform difference below this layer rather than something this file can fix, and it is why the
   * feature table's entry for `blend` is Partial rather than Supported.
   */
  private fun blendMode(blend: SceneBlendMode): BlendMode =
    when (blend) {
      SceneBlendMode.NORMAL -> BlendMode.SrcOver
      SceneBlendMode.MULTIPLY -> BlendMode.Multiply
      SceneBlendMode.SCREEN -> BlendMode.Screen
      SceneBlendMode.OVERLAY -> BlendMode.Overlay
      SceneBlendMode.DARKEN -> BlendMode.Darken
      SceneBlendMode.LIGHTEN -> BlendMode.Lighten
      SceneBlendMode.COLOR_DODGE -> BlendMode.ColorDodge
      SceneBlendMode.COLOR_BURN -> BlendMode.ColorBurn
      SceneBlendMode.HARD_LIGHT -> BlendMode.Hardlight
      SceneBlendMode.SOFT_LIGHT -> BlendMode.Softlight
      SceneBlendMode.DIFFERENCE -> BlendMode.Difference
      SceneBlendMode.EXCLUSION -> BlendMode.Exclusion
      SceneBlendMode.HUE -> BlendMode.Hue
      SceneBlendMode.SATURATION -> BlendMode.Saturation
      SceneBlendMode.COLOR -> BlendMode.Color
      SceneBlendMode.LUMINOSITY -> BlendMode.Luminosity
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

/**
 * Decoded images, kept across frames.
 *
 * A [DrawScopeTarget] is built once per frame, so its own fields cannot cache anything: an
 * engine-produced raster was PNG-decoded on every draw, and a URL image would call the host's
 * resolver on every draw. `rememberVegaImageCache` is how a composable gets one that outlives a
 * frame; [VegaChart] does that for its caller.
 *
 * Bounded, and by **count** rather than by bytes, which is the honest limit to offer here: an
 * `ImageBitmap`'s footprint is a platform's business and this module has no way to ask. A chart
 * draws a handful of images, so the default is three orders of magnitude more than a real
 * specification needs and exists only so a generated one cannot grow the map without bound.
 *
 * Insertion-ordered with a hit moved back to the young end by hand — `LinkedHashMap`'s access-order
 * mode is JVM-only, and this is the same policy in three lines. `CachingExpressionCompiler` and
 * `TextLayoutCache` cache the same way for the same reason.
 *
 * Not thread-safe: a cache belongs to one composition, as a draw does.
 */
public class ImageCache(private val maxEntries: Int = 64) {
  private val rasters = LinkedHashMap<Long, ImageBitmap>()
  private val urls = LinkedHashMap<String, ImageBitmap>()
  private val unresolvable = LinkedHashSet<String>()

  /** How many images are held, for a host that wants to say so or a test that wants to check. */
  public val size: Int
    get() = rasters.size + urls.size

  /**
   * URLs no resolver could answer, in the order they were first met.
   *
   * The half of the image seam a host could not see. An unresolved image leaves a hole in the chart
   * and the draw carries on, which is right — a chart is better with one mark missing than not
   * drawn — but the hole was all a host got, because the target that collected these is built per
   * frame and discarded with it. They live here because this object is the host's and outlives a
   * draw.
   *
   * Kept per URL rather than per attempt: a refusal is cached, so a resolver is asked once and this
   * grows once. [clear] empties it along with the images, which is how a host that has recovered
   * from a failed fetch asks for another go.
   */
  public val unresolvedImages: Set<String>
    get() = unresolvable.toSet()

  internal fun isUnresolvable(url: String): Boolean = url in unresolvable

  internal fun putUnresolvable(url: String) {
    unresolvable.add(url)
  }

  internal fun raster(digest: Long): ImageBitmap? = youngest(rasters, digest)

  internal fun putRaster(digest: Long, image: ImageBitmap) {
    rasters[digest] = image
    evict(rasters)
  }

  internal fun url(url: String): ImageBitmap? = youngest(urls, url)

  internal fun putUrl(url: String, image: ImageBitmap) {
    urls[url] = image
    evict(urls)
  }

  /**
   * Empties it, for a host whose images have changed behind a URL that has not.
   *
   * And for one that has **recovered**: a refusal is remembered so a resolver is asked once per URL
   * rather than once per frame, which means a fetch that failed while the network was down stays
   * failed until somebody says otherwise.
   */
  public fun clear() {
    rasters.clear()
    urls.clear()
    unresolvable.clear()
  }

  private fun <K> youngest(map: LinkedHashMap<K, ImageBitmap>, key: K): ImageBitmap? {
    val hit = map.remove(key) ?: return null
    map[key] = hit
    return hit
  }

  private fun <K> evict(map: LinkedHashMap<K, ImageBitmap>) {
    // Insertion order puts the least recently used first, which is the one to drop.
    while (map.size > maxEntries) map.remove(map.keys.first())
  }
}
