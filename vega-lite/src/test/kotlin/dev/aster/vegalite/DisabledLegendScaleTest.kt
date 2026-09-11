package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A channel whose legend is switched off still names its scale in the legend it merges into.
 *
 * ```js
 * const legendCmpt = new LegendComponent({}, getLegendDefWithScale(model, channel));
 * parseInteractiveLegend(model, channel, legendCmpt);
 *
 * const disable = legend !== undefined ? !legend : legendConfig.disable;
 * legendCmpt.set('disable', disable, legend !== undefined);
 * if (disable) {
 *   return legendCmpt;
 * }
 * ```
 *
 * The component carries its scale from the moment it is made — before the disable is read — and
 * `assembleLegends` merges components by field whatever their disable says. So a chart telling its
 * lines apart by colour **and** by dash pattern keeps one key, and that key shows both: the dashes'
 * own `"legend": null` says only that there is no *second* key for them.
 *
 * This engine passed over a disabled channel entirely, so such a key came out showing colours alone
 * and the dashed line in it was drawn solid. One specification in the wild corpus is that chart.
 *
 * Which legend survives is settled the way every merged property is — an explicit statement beats a
 * derived one, and between two explicit ones the first wins — so the same two channels with no
 * legend block on the colour lose the key altogether.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class DisabledLegendScaleTest {

  private fun legends(spec: String): List<VegaValue> {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["legends"] as? VegaValue.Arr)?.values.orEmpty()
  }

  /** Only the properties that say which scales a key is drawn from. */
  private fun scales(spec: String): List<Map<String, VegaValue>> =
    legends(spec).map { legend ->
      (legend as VegaValue.Obj).fields.filterKeys {
        it in setOf("fill", "stroke", "strokeDash", "opacity", "size", "shape", "strokeWidth")
      }
    }

  private val data = """"data":{"values":[{"a":1,"c":"x"}]}"""

  private fun lines(color: String, strokeDash: String) =
    """{$data,"mark":"line","encoding":{"x":{"field":"a","type":"quantitative"},
       "y":{"field":"a","type":"quantitative"},
       "color":{"field":"c","type":"nominal"$color},
       "strokeDash":{"field":"c","type":"nominal"$strokeDash}}}"""

  /**
   * The reported shape: the colour states a legend, the dashes refuse one, and the key shows both.
   */
  @Test
  fun `a refused channel still names its scale in the key that survives`() {
    assertEquals(
      listOf(
        mapOf("stroke" to VegaValue.Str("color"), "strokeDash" to VegaValue.Str("strokeDash"))
      ),
      scales(lines(""","legend":{"orient":"bottom-right"}""", ""","legend":null""")),
    )
  }

  /** Both legended, the same key: the refusal was never what put them together. */
  @Test
  fun `two channels of one column are one key`() {
    assertEquals(
      listOf(
        mapOf("stroke" to VegaValue.Str("color"), "strokeDash" to VegaValue.Str("strokeDash"))
      ),
      scales(lines("", "")),
    )
  }

  /** With nothing stated on the colour, the refusal is the only statement and it wins. */
  @Test
  fun `a refusal beats a derived legend`() {
    assertEquals(emptyList<Map<String, VegaValue>>(), scales(lines("", ""","legend":null""")))
  }

  /** Between two statements the first wins, and the first here is the refusal. */
  @Test
  fun `the first refusal wins`() {
    assertEquals(
      emptyList<Map<String, VegaValue>>(),
      scales(lines(""","legend":null""", ""","legend":{"orient":"bottom-right"}""")),
    )
  }

  /** Two different columns are two keys, and a refused one is simply not drawn. */
  @Test
  fun `a refused channel of another column adds nothing`() {
    assertEquals(
      listOf(mapOf("stroke" to VegaValue.Str("color"))),
      scales(
        """{$data,"mark":"line","encoding":{"x":{"field":"a","type":"quantitative"},
           "y":{"field":"a","type":"quantitative"},
           "color":{"field":"c","type":"nominal"},
           "strokeDash":{"field":"a","type":"nominal","legend":null}}}"""
      ),
    )
  }
}
