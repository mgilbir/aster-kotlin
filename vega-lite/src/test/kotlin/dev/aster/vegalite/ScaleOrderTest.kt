package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The scales come out in `SCALE_CHANNELS` order, not in the order the encoding was written.
 *
 * `parseUnitScaleCore` walks the list and fills a dictionary keyed by channel:
 * ```js
 * for (const channel of SCALE_CHANNELS) { … scaleComponents[channel] = … }
 * ```
 *
 * and `assembleScales` reads it back in insertion order. The list itself is four lists joined — `x,
 * y`, then `theta, radius`, then `xOffset, yOffset`, then every non-position channel in
 * `UNIT_CHANNELS` order.
 *
 * This engine walked the *encoding* instead. The two agree for every chart that writes its channels
 * where they belong — the encoding is itself re-ordered into `UNIT_CHANNELS` order — and part
 * company the moment one is **moved**: an `angle` on an `arc` is read as `theta` at the place the
 * angle was written, which is after the colour, so a pie written that way came out with its colour
 * scale first.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ScaleOrderTest {

  private fun scales(spec: String): List<String> {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["scales"] as? VegaValue.Arr)?.values.orEmpty().mapNotNull {
      (it as? VegaValue.Obj)?.string("name")
    }
  }

  private fun chart(mark: String, encoding: String) =
    """{"data":{"values":[{"a":1,"b":2,"t":"x","u":"y"}]},"mark":"$mark",
       "encoding":{$encoding}}"""

  /** The reported shape: the one channel that ends up somewhere other than its own slot. */
  @Test
  fun `a pie whose slice is written as an angle scales the slice first`() {
    assertEquals(
      listOf("theta", "color"),
      scales(
        chart(
          "arc",
          """"angle":{"field":"a","type":"quantitative"},
             "color":{"field":"t","type":"nominal"}""",
        )
      ),
    )
  }

  /** A polar position comes straight after the pixel ones, however late it is written. */
  @Test
  fun `the polar positions come before everything but x and y`() {
    assertEquals(
      listOf("theta", "radius", "color"),
      scales(
        chart(
          "arc",
          """"color":{"field":"t","type":"nominal"},
             "radius":{"field":"b","type":"quantitative"},
             "theta":{"field":"a","type":"quantitative"}""",
        )
      ),
    )
  }

  /** Then the offsets, then everything a legend can stand for. */
  @Test
  fun `an offset comes before the channels a legend stands for`() {
    assertEquals(
      listOf("x", "y", "xOffset", "color"),
      scales(
        chart(
          "bar",
          """"color":{"field":"t","type":"nominal"},
             "xOffset":{"field":"u","type":"nominal"},
             "x":{"field":"t","type":"nominal"},
             "y":{"field":"b","type":"quantitative"}""",
        )
      ),
    )
  }

  /** And the non-position channels in `UNIT_CHANNELS` order, which is not alphabetical. */
  @Test
  fun `the non-position scales keep upstream's own order`() {
    assertEquals(
      listOf("x", "opacity", "strokeDash", "size", "shape"),
      scales(
        chart(
          "point",
          """"shape":{"field":"t","type":"nominal"},
             "size":{"field":"b","type":"quantitative"},
             "strokeDash":{"field":"u","type":"nominal"},
             "opacity":{"field":"a","type":"quantitative"},
             "x":{"field":"a","type":"quantitative"}""",
        )
      ),
    )
  }

  /** A chart that lists `y` before `x` is the same chart, which was already true. */
  @Test
  fun `a plot written backwards scales x first`() {
    assertEquals(
      listOf("x", "y"),
      scales(
        chart(
          "point",
          """"y":{"field":"b","type":"quantitative"},"x":{"field":"a","type":"quantitative"}""",
        )
      ),
    )
  }
}
