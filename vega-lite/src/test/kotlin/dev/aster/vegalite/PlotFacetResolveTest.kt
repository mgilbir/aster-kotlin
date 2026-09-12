package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A grid inside a concatenation reads **its own plot's** `resolve`, not the chart's.
 *
 * ```js
 * function getCardinalityAggregateForChild(model: FacetModel) { ... }
 * ```
 *
 * A concatenation's plot that grids its cell is a facet model, and everything a facet model decides
 * about its cells is decided from the `resolve` written on it. Two of those decisions were read off
 * the chart instead, where a concatenation's own `resolve` speaks about its plots and says nothing
 * about anybody's cells:
 *
 * - the **count** a cell sizes itself by. A cell whose discrete position is its own has no width
 *   for the grid to share, so it counts its own categories — `distinct_c` beside the row's values.
 *   Left out, such a grid sized every cell from a width that does not exist.
 * - whether the cells can be **aligned**. Cells whose plotting areas are different sizes cannot be
 *   lined up, and `align: "none"` is how a grid says so.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class PlotFacetResolveTest {

  /** The grid's own values, and how it lays its cells out. */
  private fun grid(resolve: String): String {
    val spec =
      """{"data":{"values":[{"r":"a","c":"p","v":1}]},
         "hconcat":[{$resolve"facet":{"row":{"field":"r","type":"nominal"}},
           "spec":{"mark":"bar","encoding":{"x":{"field":"v","type":"quantitative"},
                                            "y":{"field":"c","type":"nominal"}}}}]}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val domain =
      (compiled.fields["data"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first { it.string("name")?.endsWith("row_domain") == true }
    val layout = ((compiled.fields["marks"] as VegaValue.Arr).values[0] as VegaValue.Obj)["layout"]
    return (VegaJson.write(domain) + " | " + VegaJson.write(layout!!)).replace(
      Regex("""\n\s*"""),
      "",
    )
  }

  /** The reported shape: a plot that measures each cell's categories for itself. */
  @Test
  fun `a plot's own resolve gives its cells their own count`() {
    assertEquals(
      """{"name": "concat_0_row_domain","source": "data_0","transform": [{"type": "aggregate",""" +
        """"groupby": ["r"],"fields": ["c"],"ops": ["distinct"],"as": ["distinct_c"]}]} | """ +
        """{"padding": 20,"offset": {"rowTitle": 10},"columns": 1,"bounds": "full",""" +
        """"align": "none"}""",
      grid(""""resolve":{"scale":{"y":"independent"}},"""),
    )
  }

  /** Without it the cells share a scale, so there is nothing to count and they line up. */
  @Test
  fun `a plot with no resolve of its own shares its cells' count`() {
    assertEquals(
      """{"name": "concat_0_row_domain","source": "data_0","transform": [{"type": "aggregate",""" +
        """"groupby": ["r"]}]} | {"padding": 20,"offset": {"rowTitle": 10},"columns": 1,""" +
        """"bounds": "full","align": "all"}""",
      grid(""),
    )
  }
}
