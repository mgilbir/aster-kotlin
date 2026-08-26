package dev.aster.vega.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aster.vega.runtime.compile.SpecCompiler
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This renderer against `test-fixtures/host-conformance/image-resolver.txt`.
 *
 * One golden, one reader per host. The contract — a URL is asked once however many frames are
 * drawn, and a refusal is remembered — is implemented separately by each renderer, and three
 * implementations of one contract is the shape that drifts.
 */
@RunWith(AndroidJUnit4::class)
class ImageResolverConformanceTest {

  private fun specification(urls: List<String>): String {
    val marks =
      urls.joinToString(",") { url ->
        """{"type": "image", "from": {"data": "t"}, "encode": {"enter": {
             "url": {"value": "$url"}, "x": {"value": 0}, "y": {"value": 0},
             "width": {"value": 10}, "height": {"value": 10}, "aspect": {"value": false}}}}"""
      }
    return """{"width": 20, "height": 20, "padding": 0,
               "data": [{"name": "t", "values": [{"i": 0}]}],
               "marks": [$marks]}"""
  }

  @Test
  fun asksForAUrlOnceHoweverManyFrames() {
    val golden =
      InstrumentationRegistry.getInstrumentation()
        .context
        .assets
        .open("image-resolver.txt")
        .bufferedReader()
        .use { it.readText() }

    for ((case, expected) in HostConformance.cases(golden)) {
      val (urls, frames) = HostConformance.repeatedCase(case)
      val asked = mutableListOf<String>()
      val compiled = SpecCompiler(AndroidTextEngine()).compileJson(specification(urls))
      val bitmap = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888)
      // One renderer for the run: it owns the cache, the way a view owns one across frames.
      val renderer =
        AndroidCanvasSceneRenderer(
          imageResolver =
            AndroidImageResolver { url ->
              asked.add(url)
              null
            }
        )
      repeat(frames) {
        renderer.render(compiled.scene!!, Canvas(bitmap), RectF(0f, 0f, 20f, 20f), 1f)
      }
      assertEquals("for $case", expected, asked)
    }
  }
}
