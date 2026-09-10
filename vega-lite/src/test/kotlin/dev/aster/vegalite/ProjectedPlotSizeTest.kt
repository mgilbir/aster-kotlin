package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A map is as tall as a continuous plot, for the same reason a pie is.
 *
 * ```js
 * if (scaleComponent) {
 *   …
 * } else if (model.hasProjection || model.mark === 'arc') {
 *   // arc should use continuous size by default otherwise the pie is extremely small
 *   return getViewConfigContinuousSize(config.view, sizeType);
 * } else {
 *   const size = getViewConfigDiscreteSize(config.view, sizeType);
 *   return isStep(size) ? size.step : size;
 * }
 * ```
 *
 * A chart drawn through a projection has no position scale on either channel, so without this arm
 * it falls to the *discrete* size — twenty units, which is a strip rather than a map. This engine
 * had the `arc` half of the arm and not the projection half.
 *
 * `hasProjection` is a `geoshape` mark **or** a geographic position channel, so a point placed by
 * latitude and longitude counts as much as an outline does.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ProjectedPlotSizeTest {

  private fun size(spec: String): Pair<VegaValue?, VegaValue?> {
    val compiled =
      VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "did not compile" }
      ) as VegaValue.Obj
    return compiled.fields["width"] to compiled.fields["height"]
  }

  private val three = VegaValue.Num(300.0)
  private val strip = VegaValue.Num(20.0)

  /** The reported shape: an outline with nothing said about its size. */
  @Test
  fun `a geoshape is three hundred by three hundred`() {
    assertEquals(three to three, size("""{"data":{"values":[{}]},"mark":"geoshape"}"""))
  }

  /** A stated width leaves the height to the same rule. */
  @Test
  fun `a geoshape given a width still gets a continuous height`() {
    assertEquals(
      three to three,
      size("""{"data":{"values":[{}]},"width":300,"mark":"geoshape"}"""),
    )
  }

  /** A geographic **channel** counts as much as the mark does. */
  @Test
  fun `a point placed by latitude and longitude is sized the same`() {
    assertEquals(
      three to three,
      size(
        """
        {"data":{"values":[{"la":1,"lo":2}]},"mark":"point",
         "encoding":{"longitude":{"field":"lo","type":"quantitative"},
                     "latitude":{"field":"la","type":"quantitative"}}}
        """
      ),
    )
  }

  /** The half of the arm that already worked, kept honest beside it. */
  @Test
  fun `an arc is three hundred by three hundred`() {
    assertEquals(
      three to three,
      size(
        """
        {"data":{"values":[{"a":1}]},"mark":"arc",
         "encoding":{"theta":{"field":"a","type":"quantitative"}}}
        """
      ),
    )
  }

  /** And a plot that is neither still falls to the discrete size, so the arm reaches no further. */
  @Test
  fun `an ordinary plot with no vertical channel is a strip`() {
    assertEquals(
      three to strip,
      size(
        """
        {"data":{"values":[{"a":1}]},"mark":"point",
         "encoding":{"x":{"field":"a","type":"quantitative"}}}
        """
      ),
    )
    assertEquals(strip to strip, size("""{"data":{"values":[{}]},"mark":"point"}"""))
  }
}
