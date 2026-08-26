package dev.aster.vega.compose.mp

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.scene.MetricTextEngine
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * This renderer against `test-fixtures/host-conformance/image-resolver.txt`.
 *
 * One golden, one reader per host. The contract — a URL is asked once however many frames are
 * drawn, and a refusal is remembered — is implemented separately by each renderer: a cache in the
 * Android renderer, an `ImageCache` here, a static one on `CoreGraphicsTarget`. Three
 * implementations of one contract is the shape that drifts.
 *
 * Drives the **real** composable rather than the cache underneath it. A test that reimplemented the
 * resolution order would be a second copy of the thing being checked, which is the mistake
 * `CanonicalCalls` was written to avoid.
 */
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
  fun `asks for a url once, however many frames`() {
    val golden = File(HostConformance.repositoryRoot, HostConformance.IMAGE_RESOLVER)

    for ((case, expected) in HostConformance.cases(golden)) {
      val (urls, frames) = HostConformance.repeatedCase(case)
      val asked = mutableListOf<String>()
      val scene =
        SpecCompiler(textEngine = MetricTextEngine(), loader = DenyLoader)
          .compileJson(specification(urls))
          .scene!!
      // One cache for the run, which is what a chart on screen has: the draw target is rebuilt
      // every
      // frame and the cache is what outlives it.
      val cache = ImageCache()
      val composed = ImageComposeScene(20, 20, density = Density(1f))
      try {
        val record: (String) -> ImageBitmap? = { url ->
          asked.add(url)
          null
        }
        composed.setContent {
          VegaChart(scene, fit = SceneFit.None, imageCache = cache, resolveImage = record)
        }
        repeat(frames) { composed.render() }
      } finally {
        composed.close()
      }
      assertEquals(expected, asked, "for $case")
    }
  }
}
