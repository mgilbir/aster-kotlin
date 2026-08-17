package dev.aster.vega.compose.mp

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.flatten
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Image

/**
 * Images, in pixels, through Compose's own Skia backend.
 *
 * This renderer drew none until now, and the reason it could not was real rather than an oversight:
 * decoding an image has no common API in Compose Multiplatform. Android has `BitmapFactory` and the
 * Skia-backed targets have `org.jetbrains.skia.Image`, so `decodeImageBytes` is an
 * `expect`/`actual` pair — which is what "limited only by the host" looks like when the hosts
 * genuinely differ.
 *
 * These run on the JVM, which is the Skia half. The Android half shares the walk and the target and
 * differs only in that one function.
 */
class ImageTest {

  private val tinyPNG =
    "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAEUlEQVR4nGP4zwAEIOI/kAAAG/ID/VxhF44AAAAASUVORK5CYII="

  private fun scene(json: String) =
    SpecCompiler(textEngine = MetricTextEngine(), loader = DenyLoader).compileJson(json).scene!!

  private fun raster(json: String, width: Int = 40, height: Int = 40): Raster {
    val composed = ImageComposeScene(width, height, density = Density(1f))
    try {
      val drawn = scene(json)
      composed.setContent { VegaChart(drawn, fit = SceneFit.None) }
      val map =
        Image.makeFromEncoded(composed.render().encodeToData()!!.bytes)
          .toComposeImageBitmap()
          .toPixelMap()
      return Raster(map, width, height)
    } finally {
      composed.close()
    }
  }

  private class Raster(
    private val pixels: androidx.compose.ui.graphics.PixelMap,
    val width: Int,
    val height: Int,
  ) {
    fun at(x: Int, y: Int): Triple<Int, Int, Int> {
      val colour = pixels[x, y]
      return Triple(
        (colour.red * 255f).toInt(),
        (colour.green * 255f).toInt(),
        (colour.blue * 255f).toInt(),
      )
    }

    fun isNear(x: Int, y: Int, r: Int, g: Int, b: Int, tolerance: Int = 12): Boolean {
      val (gr, gg, gb) = at(x, y)
      return abs(gr - r) <= tolerance && abs(gg - g) <= tolerance && abs(gb - b) <= tolerance
    }

    fun describe(x: Int, y: Int): String {
      val (r, g, b) = at(x, y)
      return "($x,$y) = rgb($r,$g,$b)"
    }
  }

  @Test
  fun `a data url image is drawn without a resolver`() {
    val image =
      raster(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 40, "height": 40, "padding": 0, "background": "white",
         "marks": [{"type": "image", "encode": {"enter": {
           "x": {"value": 0}, "y": {"value": 0},
           "width": {"value": 40}, "height": {"value": 40},
           "smooth": {"value": false},
           "url": {"value": "$tinyPNG"}}}}]}
        """
      )
    // The PNG is red/blue on the diagonals, stretched over the whole 40x40 box.
    assertTrue(image.isNear(10, 10, 255, 0, 0), "top left is red: ${image.describe(10, 10)}")
    assertTrue(image.isNear(30, 10, 0, 0, 255), "top right is blue: ${image.describe(30, 10)}")
    assertTrue(image.isNear(10, 30, 0, 0, 255), "bottom left is blue: ${image.describe(10, 30)}")
  }

  @Test
  fun `an engine produced raster is drawn without a resolver`() {
    // `density-heatmaps` puts a `heatmap` transform's raster into an image mark: pixels the engine
    // built
    // during the compile, with no URL to resolve. That is exactly the case a URL-only renderer
    // dropped in
    // silence, so this reads the real fixture with a real loader rather than asserting on a
    // refusal.
    val json = File("../test-fixtures/specs/density-heatmaps.vg.json").readText()
    val compiled =
      SpecCompiler(
          textEngine = MetricTextEngine(),
          loader = dev.aster.vega.loader.FileDataLoader(File("../test-fixtures")),
        )
        .compileJson(json)

    val target = RecordingTarget()
    SceneWalk().draw(compiled.scene!!, target)
    val images = target.calls.filter { it.contains("image raster") }
    assertTrue(
      images.isNotEmpty(),
      "a heatmap's raster reaches the renderer:\n" + target.calls.take(30).joinToString("\n"),
    )
    assertTrue(images.none { it.contains("raster 0x") }, "with real pixels: $images")

    // And it decodes on this platform, which is the half `expect`/`actual` exists for.
    val raster =
      compiled.scene!!.let { scene ->
        scene.flatten().mapNotNull { (it.node as? dev.aster.vega.scene.ImageNode)?.raster }.first()
      }
    assertTrue(decodeRaster(raster) != null, "the raster decodes through Skia")
  }

  @Test
  fun `an unresolvable url is reported rather than silently skipped`() {
    val drawn =
      scene(
        """
      {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 40, "height": 40, "padding": 0,
       "marks": [{"type": "image", "encode": {"enter": {
         "x": {"value": 0}, "y": {"value": 0},
         "width": {"value": 40}, "height": {"value": 40},
         "url": {"value": "https://example.com/nope.png"}}}}]}
      """
      )
    val composed = ImageComposeScene(40, 40, density = Density(1f))
    try {
      var reported: List<String> = emptyList()
      composed.setContent {
        androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier) {
          val target = DrawScopeTarget(this)
          SceneWalk().draw(drawn, target)
          reported = target.unresolvedImages.toList()
        }
      }
      composed.render()
      assertEquals(listOf("https://example.com/nope.png"), reported)
    } finally {
      composed.close()
    }
  }
}
