package dev.aster.vega.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import androidx.core.graphics.createBitmap
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.scene.Scene
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** A rendered bitmap plus anything that could not be drawn faithfully. */
public data class BitmapExport(val bitmap: Bitmap, val warnings: List<VegaDiagnostic>)

public data class ByteExport(val bytes: ByteArray, val warnings: List<VegaDiagnostic>) {
  // ByteArray needs structural equality spelled out for a data class to behave sensibly.
  override fun equals(other: Any?): Boolean =
    other is ByteExport && bytes.contentEquals(other.bytes) && warnings == other.warnings

  override fun hashCode(): Int = 31 * bytes.contentHashCode() + warnings.hashCode()
}

public data class BitmapExportOptions(
  /** Logical width; defaults to the scene's own width. */
  val width: Double? = null,
  val height: Double? = null,
  val pixelScale: Float = 1f,
  /** Overrides the scene background; `null` keeps the scene's own. */
  val background: dev.aster.vega.scene.SceneColor? = null,
  val config: Bitmap.Config = Bitmap.Config.ARGB_8888,
)

/**
 * Bitmap, PNG and PDF export.
 *
 * All three go through the same [AndroidCanvasSceneRenderer] as the live view, so exported geometry
 * matches what is on screen (PROJECT_BRIEF.md 13.2, 13.3). Unsupported drawing operations surface
 * as warnings rather than silently missing marks.
 *
 * **Pass the view's own renderer** for an export that matches the view. The default builds a fresh
 * one, and a fresh one has the default text engine — font scale 1, no host faces — and no image
 * resolver. So a host that bundles a font, or that resolves image marks, exports a chart drawn in a
 * different face with holes where its images are, from a class whose whole promise is that the
 * export matches the screen. `VegaChartView.exporter()` hands over one built from the view's own
 * seams, which is the answer for the common case.
 */
public class SceneExporter(
  private val renderer: AndroidCanvasSceneRenderer = AndroidCanvasSceneRenderer()
) {

  public fun toBitmap(
    scene: Scene,
    options: BitmapExportOptions = BitmapExportOptions(),
  ): BitmapExport {
    val logicalWidth = options.width ?: scene.width
    val logicalHeight = options.height ?: scene.height
    require(logicalWidth > 0 && logicalHeight > 0) {
      "Export size must be positive, was ${logicalWidth}x$logicalHeight"
    }
    require(options.pixelScale > 0f) { "pixelScale must be positive, was ${options.pixelScale}" }

    val fit = scaleToFit(scene, logicalWidth, logicalHeight)
    val pixelWidth = (logicalWidth * options.pixelScale).roundToInt().coerceAtLeast(1)
    val pixelHeight = (logicalHeight * options.pixelScale).roundToInt().coerceAtLeast(1)
    // **Bounded.** A bitmap is four bytes a pixel, so `pixelScale = 30f` on an ordinary chart asks
    // for a nine-gigabyte allocation and an `OutOfMemoryError` — which is an `Error`, so nothing in
    // the diagnostic net catches it and the host dies. `require` is right here rather than a
    // diagnostic: the size is the caller's own argument, not something a specification chose, and
    // the alternative to refusing is a truncated image that looks like a rendering bug.
    require(pixelWidth.toLong() * pixelHeight.toLong() <= MAX_EXPORT_PIXELS) {
      "Export of ${pixelWidth}x$pixelHeight pixels is larger than the limit of " +
        "$MAX_EXPORT_PIXELS pixels; lower the size or the pixel scale"
    }
    val bitmap = createBitmap(pixelWidth, pixelHeight, options.config)
    val canvas = Canvas(bitmap)

    val exported =
      if (options.background != null) scene.copy(background = options.background) else scene
    // **The page first, then the drawing centred on it.** A page at an aspect ratio other than the
    // scene's own has slack, and the background belongs to the *page*: an exported chart on a red
    // background with transparent bars down two of its sides is not what anyone asked for. The
    // renderer paints its own background inside the drawing box as well, which is the same colour
    // over the same pixels.
    exported.background?.let { if (!it.isTransparent) canvas.drawColor(it.toArgb()) }
    renderer.render(
      scene = exported,
      canvas = canvas,
      // **Device pixels on both sides.** `render` reads the viewport in device pixels and divides
      // by `pixelScale` to clip, so the drawn extent here is the scene times the *effective* scale
      // — the fit and the caller's own pixel scale together — and not the fit alone.
      viewport =
        centred(scene, pixelWidth.toDouble(), pixelHeight.toDouble(), options.pixelScale * fit),
      pixelScale = options.pixelScale * fit,
    )
    return BitmapExport(bitmap, renderer.lastDiagnostics)
  }

  /**
   * The bitmap as PNG bytes.
   *
   * There is no `quality`, and there never meaningfully was: PNG is lossless, and Android documents
   * the argument as ignored for it. A public parameter that does nothing is worse than no parameter
   * — a caller passing 80 to make a smaller file gets exactly the same bytes and no way to find out
   * why. A smaller file means fewer pixels, which is `BitmapExportOptions.pixelScale`.
   */
  public fun toPng(
    scene: Scene,
    options: BitmapExportOptions = BitmapExportOptions(),
  ): ByteExport {
    val export = toBitmap(scene, options)
    // Sized from the bitmap rather than left at the default 32 bytes: a full-page export is
    // megabytes, and a `ByteArrayOutputStream` that starts at 32 doubles its buffer twenty times
    // getting there, copying everything each time. `byteCount` is the decoded size, which is a
    // generous upper bound for a compressed PNG and therefore exactly one allocation.
    val stream = ByteArrayOutputStream(export.bitmap.byteCount.coerceAtLeast(1024))
    val compressed = export.bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    check(compressed) { "Bitmap.compress reported failure for PNG output" }
    return ByteExport(stream.toByteArray(), export.warnings)
  }

  /**
   * Renders into a single-page PDF via Android's [PdfDocument] canvas.
   *
   * That canvas records vector drawing commands, so text and paths stay vectors instead of being
   * rasterized.
   */
  public fun toPdf(
    scene: Scene,
    widthPoints: Double = scene.width,
    heightPoints: Double = scene.height,
  ): ByteExport {
    require(widthPoints > 0 && heightPoints > 0) {
      "PDF size must be positive, was ${widthPoints}x$heightPoints"
    }
    val document = PdfDocument()
    try {
      val pageWidth = widthPoints.roundToInt().coerceAtLeast(1)
      val pageHeight = heightPoints.roundToInt().coerceAtLeast(1)
      val page = document.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create())
      val fit = scaleToFit(scene, widthPoints, heightPoints)
      renderer.render(
        scene = scene,
        canvas = page.canvas,
        viewport = centred(scene, pageWidth.toDouble(), pageHeight.toDouble(), fit),
        pixelScale = fit,
      )
      document.finishPage(page)

      val stream = ByteArrayOutputStream()
      document.writeTo(stream)
      return ByteExport(stream.toByteArray(), renderer.lastDiagnostics)
    } finally {
      document.close()
    }
  }

  /** Uniform scale that fits the scene into the requested size without distorting it. */
  private fun scaleToFit(scene: Scene, width: Double, height: Double): Float {
    if (scene.width <= 0.0 || scene.height <= 0.0) return 1f
    return minOf(width / scene.width, height / scene.height).toFloat()
  }

  /**
   * The drawing's box inside a page of [width] by [height], **centred** in whatever the fit leaves
   * over.
   *
   * The export used to draw at the top-left corner and clip to the whole page, while
   * `VegaChartView.placement()` centres — so exporting a chart at an aspect ratio other than its
   * own produced a picture that was not the one on screen, from the class whose contract is that it
   * is. `SceneFit.Contain` centres on the Compose Multiplatform and SwiftUI renderers too; this was
   * the last place that did not.
   */
  private fun centred(scene: Scene, width: Double, height: Double, fit: Float): RectF {
    val drawnWidth = scene.width * fit
    val drawnHeight = scene.height * fit
    val left = ((width - drawnWidth) / 2.0).coerceAtLeast(0.0)
    val top = ((height - drawnHeight) / 2.0).coerceAtLeast(0.0)
    return RectF(
      left.toFloat(),
      top.toFloat(),
      (left + drawnWidth).toFloat(),
      (top + drawnHeight).toFloat(),
    )
  }

  public companion object {
    /**
     * The largest bitmap this will build, in pixels: 16,384 squared, or about a gigabyte at four
     * bytes each.
     *
     * Comfortably above any page anyone prints and far below what a stray `pixelScale` reaches.
     */
    public const val MAX_EXPORT_PIXELS: Long = 16_384L * 16_384L
  }
}
