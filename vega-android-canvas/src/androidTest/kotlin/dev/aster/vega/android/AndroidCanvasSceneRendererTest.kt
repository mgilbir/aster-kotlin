package dev.aster.vega.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aster.vega.fixtures.SampleScenes
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.ImageNode
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.Transform2D
import dev.aster.vega.scene.toCanonicalJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCanvasSceneRendererTest {

  private val ids = SceneNodeIdAllocator()

  private fun sceneOf(
    vararg children: SceneNode,
    background: SceneColor? = SceneColor.White,
  ): Scene =
    Scene(
      width = 100.0,
      height = 100.0,
      background = background,
      root = GroupNode(id = ids.allocate(), children = children.toList()),
    )

  private fun renderToBitmap(
    scene: Scene,
    size: Int = 100,
    renderer: AndroidCanvasSceneRenderer = AndroidCanvasSceneRenderer(),
  ): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    renderer.render(scene, Canvas(bitmap), RectF(0f, 0f, size.toFloat(), size.toFloat()), 1f)
    return bitmap
  }

  @Test
  fun backgroundIsPainted() {
    val bitmap = renderToBitmap(sceneOf(background = SceneColor.parse("#ff0000")))
    assertEquals(Color.RED, bitmap.getPixel(50, 50))
  }

  @Test
  fun transparentBackgroundLeavesPixelsUntouched() {
    val bitmap = renderToBitmap(sceneOf(background = null))
    assertEquals(0, bitmap.getPixel(50, 50))
  }

  @Test
  fun filledRectCoversItsGeometryAndNothingElse() {
    val rect =
      RectNode(
        id = ids.allocate(),
        x = 10.0,
        y = 10.0,
        width = 30.0,
        height = 30.0,
        fill = Fill.of(SceneColor.parse("#0000ff")!!),
      )
    val bitmap = renderToBitmap(sceneOf(rect))

    assertEquals(Color.BLUE, bitmap.getPixel(25, 25))
    assertEquals(Color.WHITE, bitmap.getPixel(60, 60))
    assertEquals(Color.WHITE, bitmap.getPixel(5, 5))
  }

  @Test
  fun nodeTransformMovesTheMark() {
    val rect =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 10.0,
        height = 10.0,
        fill = Fill.of(SceneColor.parse("#0000ff")!!),
        transform = Transform2D.translate(50.0, 50.0),
      )
    val bitmap = renderToBitmap(sceneOf(rect))
    assertEquals(Color.BLUE, bitmap.getPixel(55, 55))
    assertEquals(Color.WHITE, bitmap.getPixel(5, 5))
  }

  @Test
  fun groupClipHidesOverflow() {
    val wide =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 100.0,
        height = 100.0,
        fill = Fill.of(SceneColor.parse("#0000ff")!!),
      )
    val clipped =
      GroupNode(id = ids.allocate(), children = listOf(wide), clip = RectD(0.0, 0.0, 20.0, 20.0))
    val bitmap = renderToBitmap(sceneOf(clipped))

    assertEquals(Color.BLUE, bitmap.getPixel(10, 10))
    assertEquals(Color.WHITE, bitmap.getPixel(50, 50))
  }

  @Test
  fun opacityBlendsTowardsTheBackground() {
    val rect =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 100.0,
        height = 100.0,
        fill = Fill.of(SceneColor.parse("#000000")!!),
        opacity = 0.5,
      )
    val pixel = renderToBitmap(sceneOf(rect)).getPixel(50, 50)
    assertNotEquals(Color.BLACK, pixel)
    assertNotEquals(Color.WHITE, pixel)
    // Halfway between black and white, within rounding.
    assertTrue("unexpected pixel ${Integer.toHexString(pixel)}", Color.red(pixel) in 120..136)
  }

  @Test
  fun invisibleNodeDrawsNothing() {
    val hidden =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 100.0,
        height = 100.0,
        fill = Fill.of(SceneColor.parse("#000000")!!),
        visible = false,
      )
    assertEquals(Color.WHITE, renderToBitmap(sceneOf(hidden)).getPixel(50, 50))
  }

  @Test
  fun unresolvedImageReportsADiagnosticInsteadOfDrawingNothingSilently() {
    val image =
      ImageNode(
        id = ids.allocate(),
        url = "asset://missing.png",
        x = 0.0,
        y = 0.0,
        width = 50.0,
        height = 50.0,
      )
    val renderer = AndroidCanvasSceneRenderer()
    renderToBitmap(sceneOf(image), renderer = renderer)

    assertEquals(1, renderer.lastDiagnostics.size)
    assertEquals(
      dev.aster.vega.model.DiagnosticCodes.EXPORT_IMAGE_UNRESOLVED,
      renderer.lastDiagnostics.single().code,
    )
  }

  @Test
  fun renderingDoesNotMutateTheScene() {
    val scene = SampleScenes.barChart(AndroidTextEngine())
    val before = scene.toCanonicalJson()
    renderToBitmap(scene, size = 400)
    assertEquals(before, scene.toCanonicalJson())
  }

  @Test
  fun repeatedRendersProduceIdenticalPixels() {
    val scene = SampleScenes.lineChart(AndroidTextEngine())
    val first = renderToBitmap(scene, size = 400)
    val second = renderToBitmap(scene, size = 400)
    assertTrue("renders differed", first.sameAs(second))
  }

  @Test
  fun everySampleChartDrawsSomething() {
    val textEngine = AndroidTextEngine()
    val scenes =
      listOf(
        SampleScenes.barChart(textEngine),
        SampleScenes.stackedBarChart(textEngine),
        SampleScenes.lineChart(textEngine),
        SampleScenes.areaChart(textEngine),
        SampleScenes.scatterPlot(textEngine),
      )
    for (scene in scenes) {
      val bitmap = renderToBitmap(scene, size = 400)
      var nonBackground = 0
      for (x in 0 until 400 step 4) {
        for (y in 0 until 400 step 4) {
          if (bitmap.getPixel(x, y) != Color.WHITE) nonBackground++
        }
      }
      assertTrue("scene rendered blank", nonBackground > 50)
    }
  }

  @Test
  fun sceneLargerThanTheViewportIsScaledToFit() {
    val rect =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 100.0,
        height = 100.0,
        fill = Fill.of(SceneColor.parse("#0000ff")!!),
      )
    val bitmap = Bitmap.createBitmap(50, 50, Bitmap.Config.ARGB_8888)
    AndroidCanvasSceneRenderer()
      .render(sceneOf(rect), Canvas(bitmap), RectF(0f, 0f, 50f, 50f), 0.5f)
    assertEquals(Color.BLUE, bitmap.getPixel(25, 25))
    assertFalse(bitmap.getPixel(49, 49) == 0)
  }
}
