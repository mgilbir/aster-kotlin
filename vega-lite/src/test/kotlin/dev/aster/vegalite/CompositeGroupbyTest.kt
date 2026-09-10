package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Every entry of a list channel breaks a composite mark's summary down by its own column.
 *
 * `extractTransformsFromEncoding` walks the encoding with `forEach`, which spreads an array before
 * it calls:
 * ```js
 * for (const channel of keys(mapping)) {
 *   const el = mapping[channel];
 *   if (isArray(el)) {
 *     for (const channelDef of el as unknown[]) {
 *       f.call(thisArg, channelDef, channel);
 *     }
 *   } else {
 *     f.call(thisArg, el, channel);
 *   }
 * }
 * ```
 *
 * so a `tooltip` naming four columns contributes four groupings. Reading only the channel's own
 * definition summarised across all of them — an error bar over one interval per category came out
 * as one interval for everything, which is a different chart rather than a differently-written one.
 *
 * A **box plot** is the exception, and stays one: `filterTooltipWithAggregatedField` takes the
 * tooltip out of the encoding before the grouping is read, and it is called from `boxplot.ts`
 * alone. An error bar keeps its tooltip.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class CompositeGroupbyTest {

  private fun groupbys(mark: String, extra: String): List<List<String>> {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"x":1,"y":2,"c":"a","d":"b"}]},"mark":"$mark",
               "encoding":{"x":{"field":"x","type":"quantitative"},
                           "y":{"field":"y","type":"quantitative"},$extra}}
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
      .filter { (it.fields["type"] as? VegaValue.Str)?.value == "aggregate" }
      .map { t ->
        (t.fields["groupby"] as? VegaValue.Arr)?.values.orEmpty().mapNotNull {
          (it as? VegaValue.Str)?.value
        }
      }
  }

  private val twoTooltips =
    """"tooltip":[{"field":"c","type":"nominal"},{"field":"d","type":"nominal"}]"""

  /** The reported shape: an error bar whose tooltip names two more columns. */
  @Test
  fun `every tooltip entry groups an error bar's summary`() {
    assertEquals(listOf(listOf("x", "c", "d")), groupbys("errorbar", twoTooltips))
  }

  /** `detail` is a list channel too, and groups the same way. */
  @Test
  fun `every detail entry groups an error bar's summary`() {
    assertEquals(
      listOf(listOf("x", "c", "d")),
      groupbys(
        "errorbar",
        """"detail":[{"field":"c","type":"nominal"},{"field":"d","type":"nominal"}]""",
      ),
    )
  }

  /** An entry that **aggregates** is a measure and not a grouping, wherever it sits in the list. */
  @Test
  fun `an aggregated tooltip entry does not group`() {
    assertEquals(
      listOf(listOf("x", "c")),
      groupbys(
        "errorbar",
        """"tooltip":[{"field":"c","type":"nominal"},
                      {"field":"y","aggregate":"mean","type":"quantitative"}]""",
      ),
    )
  }

  /**
   * A **box plot** takes its tooltip out of the encoding before the grouping is read, so none of
   * this applies to it. Checked so the rule stays the error bar's.
   */
  @Test
  fun `a box plot's tooltip does not group at all`() {
    assertEquals(listOf(listOf("x"), listOf("x")), groupbys("boxplot", twoTooltips))
  }
}
