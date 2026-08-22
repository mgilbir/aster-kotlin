package dev.aster.vega.compose.mp

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.VectorD
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

  /**
   * A host's resolver reaches the drawing **through `VegaChart`**.
   *
   * `DrawScopeTarget` has taken a resolver from the start and the composable had no parameter for
   * it, so it was always built with the resolver defaulted to null. A chart with a remote image
   * therefore drew every other mark and a hole where the image would be, with no way through the
   * supported entry point to supply a fetcher — the same gap the SwiftUI view had.
   *
   * The resolver here decodes the same tiny PNG the data-URL test uses, so the pixels asserted are
   * the pixels that test already pins: what is new is the path they arrived by.
   */
  @Test
  fun `a host's resolver reaches the drawing through the composable`() {
    val bytes = kotlin.io.encoding.Base64.decode(tinyPNG.substringAfter(","))
    var asked = 0
    val drawn =
      scene(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
         "width": 40, "height": 40, "padding": 0, "background": "white",
         "marks": [{"type": "image", "encode": {"enter": {
           "x": {"value": 0}, "y": {"value": 0},
           "width": {"value": 40}, "height": {"value": 40},
           "smooth": {"value": false},
           "url": {"value": "https://example.com/tile.png"}}}}]}
        """
      )
    val composed = ImageComposeScene(40, 40, density = Density(1f))
    try {
      composed.setContent {
        VegaChart(
          drawn,
          fit = SceneFit.None,
          resolveImage = { url ->
            asked += 1
            if (url == "https://example.com/tile.png") decodeImageBytes(bytes) else null
          },
        )
      }
      val map =
        Image.makeFromEncoded(composed.render().encodeToData()!!.bytes)
          .toComposeImageBitmap()
          .toPixelMap()
      val image = Raster(map, 40, 40)
      assertTrue(image.isNear(10, 10, 255, 0, 0), "top left is red: ${image.describe(10, 10)}")
      assertTrue(image.isNear(30, 10, 0, 0, 255), "top right is blue: ${image.describe(30, 10)}")
      assertEquals(1, asked, "asked once, not once per frame")

      // A second frame, which is where the cache earns its place: the target is rebuilt per draw,
      // so
      // without a cache that outlives one the host's fetcher is called again for the same URL.
      composed.render()
      assertEquals(1, asked, "still once after a redraw: the cache outlives the frame")
    } finally {
      composed.close()
    }
  }

  /**
   * The cache is bounded, and by count.
   *
   * An unbounded map keyed by URL is a leak with a chart generator in front of it. Bounded by count
   * rather than by bytes because an `ImageBitmap`'s footprint is a platform's business and this
   * module has no way to ask.
   */
  @Test
  fun `the image cache is bounded and least recently used goes first`() {
    val bytes = kotlin.io.encoding.Base64.decode(tinyPNG.substringAfter(","))
    val decoded = requireNotNull(decodeImageBytes(bytes))
    val cache = ImageCache(maxEntries = 2)

    cache.putUrl("a", decoded)
    cache.putUrl("b", decoded)
    assertEquals(2, cache.size)
    // Touching "a" makes "b" the oldest.
    assertTrue(cache.url("a") != null)
    cache.putUrl("c", decoded)
    assertEquals(2, cache.size)
    assertTrue(cache.url("a") != null, "the one that was used again survived")
    assertEquals(null, cache.url("b"), "the least recently used went first")
    assertTrue(cache.url("c") != null)

    cache.clear()
    assertEquals(0, cache.size)
  }

  /**
   * A host is **told** about a hole in its chart, once per URL — and the resolver asked once.
   *
   * An unresolved image leaves a hole and the draw carries on, which is right: a chart is better
   * with one mark missing than not drawn at all. But the hole was all a host got. `DrawScopeTarget`
   * collected the URLs and `VegaChart` builds a target per frame and discards it, so there was
   * nowhere for them to arrive — and only successes were cached, so the resolver was asked again
   * for an address that had already said no.
   *
   * Both halves are one fix: a refusal is remembered, so the resolver is asked once and the report
   * fires once. Which is what makes a callback from the *draw* usable at all.
   *
   * **Two marks on the same URL**, deliberately. It makes the duplication visible inside a single
   * draw, so the assertion does not rest on a redraw happening — `ImageComposeScene.render()` does
   * nothing when nothing has invalidated, and an earlier draft of this test proved exactly nothing
   * by calling it three times. The cross-frame half is asserted below, with a state change to force
   * the redraw and `onPlaced` to count it.
   */
  @Test
  fun `an unresolved image is reported once per url and the resolver asked once`() {
    val drawn =
      scene(
        """
      {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 20, "height": 20, "padding": 0,
       "marks": [
         {"type": "image", "encode": {"enter": {
           "x": {"value": 0}, "y": {"value": 0},
           "width": {"value": 10}, "height": {"value": 20},
           "url": {"value": "https://example.com/missing.png"}}}},
         {"type": "image", "encode": {"enter": {
           "x": {"value": 10}, "y": {"value": 0},
           "width": {"value": 10}, "height": {"value": 20},
           "url": {"value": "https://example.com/missing.png"}}}}]}
      """
      )
    val composed = ImageComposeScene(20, 20, density = Density(1f))
    val cache = ImageCache()
    var asked = 0
    var draws = 0
    val reported = mutableListOf<String>()
    // Read inside the composition, so changing it invalidates the draw. Without something like this
    // `render()` returns the frame it already had.
    val nudge = mutableStateOf(0.0)
    try {
      composed.setContent {
        VegaChart(
          drawn,
          fit = SceneFit.None,
          imageCache = cache,
          viewportOffset = VectorD(nudge.value, 0.0),
          resolveImage = {
            asked += 1
            null
          },
          onUnresolvedImage = { reported += it },
          onPlaced = { draws += 1 },
        )
      }
      composed.render()
      assertEquals(1, draws)
      // Two marks, one URL, one draw: without the negative cache this is two fetches and two
      // reports.
      assertEquals(1, asked, "one fetch for two marks on the same URL")
      assertEquals(listOf("https://example.com/missing.png"), reported)

      // And across frames. The nudge is what makes the redraw real; `draws` is what proves it was.
      nudge.value = 1.0
      composed.render()
      assertEquals(2, draws, "the scene really was drawn again")
      assertEquals(1, asked, "still one fetch: a refusal outlives the frame")
      assertEquals(1, reported.size, "and still one report")

      // A host that has recovered asks for another go.
      assertEquals(setOf("https://example.com/missing.png"), cache.unresolvedImages)
      cache.clear()
      nudge.value = 2.0
      composed.render()
      assertEquals(3, draws)
      assertEquals(2, asked)
      assertEquals(2, reported.size)
    } finally {
      composed.close()
    }
  }
}
