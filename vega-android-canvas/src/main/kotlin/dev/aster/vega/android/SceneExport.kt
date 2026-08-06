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

    val pixelWidth = (logicalWidth * options.pixelScale).roundToInt().coerceAtLeast(1)
    val pixelHeight = (logicalHeight * options.pixelScale).roundToInt().coerceAtLeast(1)
    val bitmap = createBitmap(pixelWidth, pixelHeight, options.config)
    val canvas = Canvas(bitmap)

    val exported =
      if (options.background != null) scene.copy(background = options.background) else scene
    renderer.render(
      scene = exported,
      canvas = canvas,
      viewport = RectF(0f, 0f, pixelWidth.toFloat(), pixelHeight.toFloat()),
      pixelScale = options.pixelScale * scaleToFit(scene, logicalWidth, logicalHeight),
    )
    return BitmapExport(bitmap, renderer.lastDiagnostics)
  }

  public fun toPng(
    scene: Scene,
    options: BitmapExportOptions = BitmapExportOptions(),
    quality: Int = 100,
  ): ByteExport {
    val export = toBitmap(scene, options)
    val stream = ByteArrayOutputStream()
    val compressed = export.bitmap.compress(Bitmap.CompressFormat.PNG, quality, stream)
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
      renderer.render(
        scene = scene,
        canvas = page.canvas,
        viewport = RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat()),
        pixelScale = scaleToFit(scene, widthPoints, heightPoints),
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
}
