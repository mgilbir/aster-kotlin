package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.runtime.scale.SequentialColorScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A scheme's **extent**: which stretch of the ramp a scale reads.
 *
 * ```js
 * return (isFunction(scheme) && (extent || reverse))
 *   ? interpolateRange(scheme, flip(extent || [0, 1], reverse))
 *   : scheme;
 * ```
 *
 * `{"scheme": "blues", "extent": [0.3, 0.7]}` asks for the middle of the ramp, which is how a chart
 * keeps its palest and darkest shades legible. Written backwards it reads the ramp backwards — and
 * that is not a curiosity, because the named range **`diverging` is `blueorange` with an extent of
 * `[1, 0]`**: a chart asking for the named range and one asking for the scheme get opposite
 * colours. This engine read neither, so every `"range": "diverging"` chart was painted the wrong
 * way round.
 *
 * Every colour was read off a live upstream view.
 */
class SchemeExtentTest {

  private fun ramp(scale: String): String {
    val compiled =
      SpecCompiler(VegaHeadlessTextEngine())
        .compileJson(
          """{"width": 10, "height": 10, "padding": 0, "autosize": "none", "scales": [$scale]}"""
        )
    val built = compiled.scales.values.single() as SequentialColorScale
    return listOf(0.0, 0.5, 1.0).joinToString(" ") { built.colorAt(it)?.toCssHex().orEmpty() }
  }

  /** The named range, which reads its ramp backwards. */
  @Test
  fun `the diverging range reads blueorange backwards`() {
    assertEquals(
      "#994a07 #f2f0eb #134b85",
      ramp("""{"name": "c", "type": "linear", "domain": [0, 1], "range": "diverging"}"""),
    )
  }

  /** And the scheme it is made of, which reads forwards. */
  @Test
  fun `the blueorange scheme reads forwards`() {
    assertEquals(
      "#134b85 #f2f0eb #994a07",
      ramp(
        """{"name": "c", "type": "linear", "domain": [0, 1], "range": {"scheme": "blueorange"}}"""
      ),
    )
  }

  /** An extent narrows the ramp to a stretch of itself. */
  @Test
  fun `an extent narrows the ramp`() {
    assertEquals(
      "#8fc1de #5ba3cf #3181bd",
      ramp(
        """{"name": "c", "type": "linear", "domain": [0, 1],
            "range": {"scheme": "blues", "extent": [0.3, 0.7]}}"""
      ),
    )
  }

  /** And `reverse` flips the stretch rather than the list of stops. */
  @Test
  fun `reverse reads the ramp backwards`() {
    assertEquals(
      "#0a4a90 #5ba3cf #cfe1f2",
      ramp(
        """{"name": "c", "type": "linear", "domain": [0, 1],
            "range": {"scheme": "blues"}, "reverse": true}"""
      ),
    )
  }
}
