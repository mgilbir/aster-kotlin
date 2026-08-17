package dev.aster.vega.android

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aster.vega.fixtures.SampleScenes
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.ImageNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.flatten
import dev.aster.vega.svg.toSvg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneExportTest {

  private val exporter = SceneExporter()
  private val textEngine = AndroidTextEngine()

  @Test
  fun bitmapUsesTheRequestedPixelSize() {
    val scene = SampleScenes.barChart(textEngine)
    val export =
      exporter.toBitmap(
        scene,
        BitmapExportOptions(width = 600.0, height = 300.0, pixelScale = 2f),
      )
    assertEquals(1200, export.bitmap.width)
    assertEquals(600, export.bitmap.height)
    assertTrue(export.warnings.isEmpty())
  }

  @Test
  fun bitmapDefaultsToTheSceneSize() {
    val scene = SampleScenes.lineChart(textEngine)
    val export = exporter.toBitmap(scene)
    assertEquals(scene.width.toInt(), export.bitmap.width)
    assertEquals(scene.height.toInt(), export.bitmap.height)
  }

  @Test
  fun backgroundOverrideIsApplied() {
    val scene = SampleScenes.barChart(textEngine)
    val export =
      exporter.toBitmap(
        scene,
        BitmapExportOptions(width = 40.0, height = 40.0, background = SceneColor.parse("#ff0000")),
      )
    // The very corner is outside every mark, so it shows the background.
    assertEquals(Color.RED, export.bitmap.getPixel(0, 0))
  }

  @Test
  fun exportedGeometryMatchesTheLiveScene() {
    val scene = SampleScenes.barChart(textEngine)
    val direct =
      Bitmap.createBitmap(scene.width.toInt(), scene.height.toInt(), Bitmap.Config.ARGB_8888)
    AndroidCanvasSceneRenderer(textEngine)
      .render(
        scene,
        android.graphics.Canvas(direct),
        android.graphics.RectF(0f, 0f, scene.width.toFloat(), scene.height.toFloat()),
        1f,
      )
    val exported = exporter.toBitmap(scene).bitmap
    assertTrue("export differs from a direct render", direct.sameAs(exported))
  }

  @Test
  fun pngIsAValidStreamWithASignature() {
    val export = exporter.toPng(SampleScenes.scatterPlot(textEngine))
    assertTrue(export.bytes.size > 100)
    val signature =
      byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
    assertArrayPrefix(signature, export.bytes)
  }

  @Test
  fun pdfIsAValidStreamWithAHeader() {
    val export = exporter.toPdf(SampleScenes.areaChart(textEngine))
    assertTrue(export.bytes.size > 100)
    assertArrayPrefix("%PDF".toByteArray(), export.bytes)
  }

  @Test
  fun unresolvedImageProducesAnExportWarningRatherThanASilentGap() {
    val ids = SceneNodeIdAllocator()
    val scene =
      Scene(
        width = 50.0,
        height = 50.0,
        background = SceneColor.White,
        root =
          GroupNode(
            id = ids.allocate(),
            children =
              listOf(
                ImageNode(
                  id = ids.allocate(),
                  url = "asset://nope.png",
                  x = 0.0,
                  y = 0.0,
                  width = 50.0,
                  height = 50.0,
                )
              ),
          ),
      )
    val export = exporter.toPdf(scene)
    assertEquals(1, export.warnings.size)
    assertEquals(
      dev.aster.vega.model.DiagnosticCodes.EXPORT_IMAGE_UNRESOLVED,
      export.warnings.single().code,
    )
  }

  @Test
  fun nonPositiveSizesAreRejected() {
    val scene = SampleScenes.barChart(textEngine)
    assertThrows(IllegalArgumentException::class.java) {
      exporter.toBitmap(scene, BitmapExportOptions(width = 0.0))
    }
    assertThrows(IllegalArgumentException::class.java) {
      exporter.toBitmap(scene, BitmapExportOptions(pixelScale = 0f))
    }
    assertThrows(IllegalArgumentException::class.java) { exporter.toPdf(scene, widthPoints = -1.0) }
  }

  @Test
  fun svgAndCanvasAgreeOnMarkCount() {
    val scene = SampleScenes.stackedBarChart(textEngine)
    val svg = scene.toSvg()
    val body = svg.replace(Regex("<defs>.*?</defs>", RegexOption.DOT_MATCHES_ALL), "")
    val drawableNodes =
      scene.flatten().map { it.node }.count { it.visible && it.opacity > 0.0 && it !is GroupNode }
    // Minus one for the background rect the SVG writes but the scene does not model as a node.
    val svgMarks = Regex("<(rect|line|path|text|image)[ >]").findAll(body).count() - 1
    assertEquals(drawableNodes, svgMarks)
  }

  private fun assertArrayPrefix(expected: ByteArray, actual: ByteArray) {
    assertTrue("stream shorter than its expected prefix", actual.size >= expected.size)
    for (i in expected.indices) {
      assertEquals("byte $i", expected[i], actual[i])
    }
  }
}
