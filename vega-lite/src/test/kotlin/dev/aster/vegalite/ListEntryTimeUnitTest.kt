package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An instant bucketed on one entry of a list channel is bucketed.
 *
 * `TimeUnitNode.makeFromEncoding` folds the encoding with `model.reduceFieldDef`, and `reduce`
 * spreads an array before it folds:
 * ```js
 * return keys(mapping).reduce((r, channel) => {
 *   const map = mapping[channel];
 *   if (isArray(map)) {
 *     return map.reduce((r1, channelDef) => f.call(thisArg, r1, channelDef, channel), r);
 *   } else {
 *     return f.call(thisArg, r, map, channel);
 *   }
 * }, init);
 * ```
 *
 * so a `tooltip` naming four columns is four definitions. Reading only the channel's own definition
 * left the transform unwritten, and the tooltip then read a column no step in the flow produces —
 * an empty line where a date should be.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ListEntryTimeUnitTest {

  private fun bucketed(channel: String, value: String): List<List<String>> {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"d":"2020-01-01","v":1}]},"mark":"point",
               "encoding":{"x":{"field":"v","type":"quantitative"},
                           "y":{"field":"v","type":"quantitative"},
                           "$channel":$value}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .flatMap { (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty() }
      .mapNotNull { it as? VegaValue.Obj }
      .filter { (it.fields["type"] as? VegaValue.Str)?.value == "timeunit" }
      .map { t ->
        (t.fields["as"] as VegaValue.Arr).values.mapNotNull { (it as? VegaValue.Str)?.value }
      }
  }

  private val utcDate = listOf(listOf("utcyearmonthdate_d", "utcyearmonthdate_d_end"))
  private val dated = """{"field":"d","timeUnit":"utcyearmonthdate","type":"temporal"}"""
  private val plain = """{"field":"v","type":"quantitative"}"""

  /** The reported shape: the date is the tooltip's **second** entry. */
  @Test
  fun `an instant on the second tooltip entry is bucketed`() {
    assertEquals(utcDate, bucketed("tooltip", """[$plain,$dated]"""))
  }

  /** And on the first, which worked before by accident of position. */
  @Test
  fun `an instant on the first tooltip entry is bucketed`() {
    assertEquals(utcDate, bucketed("tooltip", """[$dated,$plain]"""))
  }

  /** A tooltip of one definition, which is not a list at all. */
  @Test
  fun `a lone tooltip's instant is bucketed`() {
    assertEquals(utcDate, bucketed("tooltip", dated))
  }

  /** `detail` is a list channel too, and folds the same way. */
  @Test
  fun `an instant on a detail entry is bucketed`() {
    assertEquals(
      listOf(listOf("year_d", "year_d_end")),
      bucketed("detail", """[$plain,{"field":"d","timeUnit":"year","type":"temporal"}]"""),
    )
  }
}
