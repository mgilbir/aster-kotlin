package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The far end of a position takes the **base channel's** nudge, unless the far end was spoken of.
 *
 * ```js
 * const {offset} =
 *   channel in encoding || channel in markDef
 *     ? positionOffset({channel, markDef, encoding, model})
 *     : positionOffset({channel: baseChannel, markDef, encoding, model});
 * ```
 *
 * A mark nudged round the circle by a `thetaOffset` is nudged at **both** ends of its wedge, or the
 * wedge is drawn a different size rather than in a different place. This engine asked each end for
 * its own offset, so a donut rotated that way came out with its slices starting where they were
 * asked to and ending where they were not. One specification in the wild corpus is that donut.
 *
 * The test is for the channel itself and not for its offset: `theta2` in the encoding or in the
 * mark definition, which a `theta2Offset` alone is not — so a mark stating both offsets is still
 * nudged at both ends by the base channel's.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class PositionOffsetTest {

  private fun update(spec: String): VegaValue.Obj {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val mark = (compiled.fields["marks"] as VegaValue.Arr).values.first() as VegaValue.Obj
    return (mark.fields["encode"] as VegaValue.Obj).fields["update"] as VegaValue.Obj
  }

  private fun ends(spec: String, first: String, second: String) =
    listOf(first, second).map { update(spec).fields[it] }

  private val data = """"data":{"values":[{"a":1,"c":"x"}]}"""

  private fun donut(mark: String) =
    """{$data,"mark":$mark,"encoding":{"theta":{"field":"a","type":"quantitative"},
       "color":{"field":"c","type":"nominal"}}}"""

  private fun json(text: String) = VegaJson.parse(text)

  /** The reported shape: a rotated donut, nudged at both ends of every wedge. */
  @Test
  fun `a wedge is nudged at both of its ends`() {
    assertEquals(
      listOf(
        json("""{"scale":"theta","field":"a_end","offset":2.65}"""),
        json("""{"scale":"theta","field":"a_start","offset":2.65}"""),
      ),
      ends(donut("""{"type":"arc","thetaOffset":2.65}"""), "startAngle", "endAngle"),
    )
  }

  /** A `theta2Offset` beside it is not the far end being spoken of, and changes nothing. */
  @Test
  fun `an offset named for the far end alone does not claim it`() {
    assertEquals(
      listOf(
        json("""{"scale":"theta","field":"a_end","offset":2.65}"""),
        json("""{"scale":"theta","field":"a_start","offset":2.65}"""),
      ),
      ends(
        donut("""{"type":"arc","thetaOffset":2.65,"theta2Offset":1}"""),
        "startAngle",
        "endAngle",
      ),
    )
  }

  /** A stacked bar is the same shape in Cartesian coordinates. */
  @Test
  fun `a stacked bar is nudged at both of its ends`() {
    assertEquals(
      listOf(
        json("""{"scale":"x","field":"a_end","offset":5}"""),
        json("""{"scale":"x","field":"a_start","offset":5}"""),
      ),
      ends(
        """{$data,"mark":{"type":"bar","xOffset":5},
           "encoding":{"x":{"field":"a","type":"quantitative"},
                       "y":{"field":"c","type":"nominal"},
                       "color":{"field":"c","type":"nominal"}}}""",
        "x",
        "x2",
      ),
    )
  }

  /** Where the far end **is** encoded, it takes its own offset — which is to say none. */
  @Test
  fun `an encoded far end takes its own nudge`() {
    assertEquals(
      listOf(
        json("""{"scale":"x","field":"a","offset":5}"""),
        json("""{"scale":"x","value":0}"""),
      ),
      ends(
        """{$data,"mark":{"type":"bar","xOffset":5},
           "encoding":{"x":{"field":"a","type":"quantitative"},"x2":{"datum":0}}}""",
        "x",
        "x2",
      ),
    )
  }
}
