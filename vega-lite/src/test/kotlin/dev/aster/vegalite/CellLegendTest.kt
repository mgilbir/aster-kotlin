package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A key a trellis resolves **per cell** stands in the cell, not beside the grid.
 *
 * ```js
 * resolve.legend[channel] = parseGuideResolve(model.component.resolve, channel);
 *
 * if (resolve.legend[channel] === 'shared') {
 *   legends[channel] = mergeLegendComponent(legends[channel], child.component.legends[channel]);
 * ```
 *
 * `parseNonUnitLegend` merges a child's key up into the composition only where the resolve says
 * shared, and `parseGuideResolve` answers `independent` for any channel whose **scale** is
 * independent. A key is a reading of one scale — its swatches are that scale's colours — so a
 * trellis whose cells colour themselves has a key per cell, written in the cell group where the
 * scale it reads is.
 *
 * Asked of the **channel**, not of the scale: `{"legend": {"color": "independent"}}` is a key per
 * cell for a scale every cell shares, which is a reader's answer to a grid too crowded to carry one
 * key beside it.
 *
 * This engine placed a key by the composition alone — inside a plot of a concatenation, and
 * otherwise beside the chart — so a trellis's own key was written beside the grid, drawn from a
 * scale that does not exist at the level it was written on. Two specifications in the wild corpus
 * are that chart, both a trellis of pies.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class CellLegendTest {

  /** Where the keys stand, each named by the scale its swatches read, and the cell's own scales. */
  private fun keys(resolve: String, encoding: String = ""): String {
    val spec =
      """{"data":{"values":[{"a":1,"b":2,"c":"x","g":"r"}]},$resolve
         "facet":{"row":{"field":"g","type":"nominal"}},
         "spec":{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
           "y":{"field":"b","type":"quantitative"},
           "color":{"field":"c","type":"nominal"}$encoding}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun scalesOf(holder: VegaValue.Obj?, key: String) =
      (holder?.fields?.get(key) as? VegaValue.Arr)?.values.orEmpty().map { entry ->
        entry as VegaValue.Obj
        listOf("fill", "stroke", "size", "shape").firstNotNullOfOrNull { entry.string(it) }
      }
    val cell =
      (compiled.fields["marks"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .firstOrNull { it.string("name")?.endsWith("cell") == true }
    val cellScales =
      (cell?.fields?.get("scales") as? VegaValue.Arr)?.values.orEmpty().map {
        (it as VegaValue.Obj).string("name")
      }
    return "beside=${scalesOf(compiled, "legends")} " +
      "inside=${scalesOf(cell, "legends")} cellScales=$cellScales"
  }

  /** The reported shape: cells that colour themselves carry the key that explains them. */
  @Test
  fun `an independently scaled colour puts its key in the cell`() {
    assertEquals(
      "beside=[] inside=[child_color] cellScales=[child_color]",
      keys(""""resolve":{"scale":{"color":"independent"}},"""),
    )
  }

  /** A colour every cell shares is one key beside the grid, as it always was. */
  @Test
  fun `a shared colour keeps its key beside the grid`() {
    assertEquals("beside=[color] inside=[] cellScales=[]", keys(""))
  }

  /**
   * The **guide's** own resolution says it too, for a scale every cell shares: one key per cell
   * reading the one scale, which is what a grid too crowded to carry a key beside it asks for.
   */
  @Test
  fun `an independently resolved key is in the cell over a shared scale`() {
    assertEquals(
      "beside=[] inside=[color] cellScales=[]",
      keys(""""resolve":{"legend":{"color":"independent"}},"""),
    )
  }

  /** Each channel is asked for itself, and both keys go where their scales went. */
  @Test
  fun `two independently scaled channels put both keys in the cell`() {
    assertEquals(
      "beside=[] inside=[child_color, child_size] cellScales=[child_color, child_size]",
      keys(
        """"resolve":{"scale":{"color":"independent","size":"independent"}},""",
        encoding = ""","size":{"field":"b","type":"quantitative"}""",
      ),
    )
  }
}
