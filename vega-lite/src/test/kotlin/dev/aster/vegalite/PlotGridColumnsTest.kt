package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `columns` belongs to the level that **wrote the facet**, which may be a plot rather than the
 * chart.
 *
 * A wrapped grid has no direction of its own, so the number of cells to put in a row is written
 * beside the facet and `getFacetMappingAndLayout` lifts it from there onto the layout. A plot of a
 * concatenation that grids its cell writes both in the same place.
 *
 * This compiler read the number off the **chart's** specification alone, so a wrapped grid written
 * on a plot found nothing to wrap at and laid its cells out in one long row — as wide as the data
 * happened to be, however narrow the plot beside it.
 *
 * Two specifications in the wild corpus wrap a grid inside a plot.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class PlotGridColumnsTest {

  /** Every group's layout, by the group it is written on, and the chart's own. */
  private fun layouts(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun walk(marks: List<VegaValue>): List<String> = marks.flatMap {
      it as VegaValue.Obj
      listOfNotNull(
        it.fields["layout"]?.let { layout ->
          "${it.string("name")}=${VegaJson.write(layout).replace(Regex("""\n\s*"""), "")}"
        }
      ) + walk((it.fields["marks"] as? VegaValue.Arr)?.values.orEmpty())
    }
    val chart =
      compiled.fields["layout"]?.let {
        "chart=${VegaJson.write(it).replace(Regex("""\n\s*"""), "")}"
      }
    return (walk((compiled.fields["marks"] as VegaValue.Arr).values) + listOfNotNull(chart))
      .joinToString(" | ")
  }

  private val rows = """"data":{"values":[{"a":1,"b":2,"r":"x"}]}"""
  private val cell =
    """"spec":{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
                                          "y":{"field":"b","type":"quantitative"}}}"""
  private val wrap = """"facet":{"field":"r","type":"nominal"}"""

  /** The reported shape: the plot says how wide its grid is, and the plot's group wraps there. */
  @Test
  fun `a plot wraps its own grid at its own columns`() {
    assertEquals(
      """concat_0_group={"padding": 20,"bounds": "full","align": "all","columns": 3} | """ +
        """chart={"padding": 20,"columns": 1,"bounds": "full","align": "each"}""",
      layouts("""{$rows,"vconcat":[{"columns":3,$wrap,$cell}]}"""),
    )
  }

  /** With no number the grid is one row, which is the shape that must not change. */
  @Test
  fun `a plot that names no columns is one row`() {
    assertEquals(
      """concat_0_group={"padding": 20,"bounds": "full","align": "all"} | """ +
        """chart={"padding": 20,"columns": 1,"bounds": "full","align": "each"}""",
      layouts("""{$rows,"vconcat":[{$wrap,$cell}]}"""),
    )
  }

  /**
   * And everything else the layout takes from beside the facet comes from the same place: `bounds`
   * and `center` are lifted by `getFacetMappingAndLayout` exactly as `columns` is.
   */
  @Test
  fun `a plot's own bounds and centring are the plot's too`() {
    assertEquals(
      """concat_0_group={"padding": 20,"bounds": "flush","align": "all","center": true,""" +
        """"columns": 3} | """ +
        """chart={"padding": 20,"columns": 1,"bounds": "full","align": "each"}""",
      layouts("""{$rows,"vconcat":[{"columns":3,"bounds":"flush","center":true,$wrap,$cell}]}"""),
    )
  }

  /** The **chart's** own wrapped grid reads it from the chart, which is where it always did. */
  @Test
  fun `a chart wraps its own grid`() {
    assertEquals(
      """chart={"padding": 20,"bounds": "full","align": "all","columns": 3}""",
      layouts("""{$rows,"columns":3,$wrap,$cell}"""),
    )
  }
}
