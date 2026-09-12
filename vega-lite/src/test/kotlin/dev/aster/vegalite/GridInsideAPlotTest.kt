package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A grid that belongs to a **plot** is answered by that plot, not by the chart above it.
 *
 * Two questions were asked of the chart and should have been asked of the grid's own model:
 *
 * - **Which axes stand in a band beside the grid.** `parseGuideResolve` is asked of the model the
 *   grid belongs to, and a facet's cells share their positions whatever the plots beside it do
 *   about theirs. Asked of the chart, every position axis of a faceted plot in a concatenation
 *   looked like a cell's own — a concatenation resolving `x` and `y` independently by default — so
 *   the grid was credited with no shared band and counted no cells, and the `facet_domain_row` and
 *   `facet_domain_column` sequences went unwritten. The bands themselves were still drawn, from
 *   `assembleFacetMarks`, which does ask the plot's own resolve: they read a dataset nothing had
 *   written, and Vega refuses a chart that names a dataset it was never given. The chart did not
 *   render at all.
 * - **Which grid a selection's view is a cell of.** `unitName(model, {escape: false})` writes the
 *   cell's name *and the values that cell holds*, since every cell is the same model drawn once per
 *   value. A row the store opens with has to say which cell it was picked in; named with the bare
 *   cell name, a faceted plot that opened with a brush had that brush belong to no cell.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class GridInsideAPlotTest {

  private fun compiled(spec: String) =
    VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
      as VegaValue.Obj

  /** Every dataset the chart writes, by name. */
  private fun datasets(spec: String): String =
    (compiled(spec).fields["data"] as VegaValue.Arr).values.joinToString(" ") {
      (it as VegaValue.Obj).string("name").orEmpty()
    }

  /** The cell a row already in the store says it was picked in. */
  private fun storedUnit(spec: String): String =
    ((compiled(spec).fields["data"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first { it.string("name") == "brush_store" }
        .fields["values"]
        as VegaValue.Arr)
      .values
      .joinToString(" ") { (it as VegaValue.Obj).string("unit").orEmpty() }

  private val at =
    """"encoding":{"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"quantitative"}}"""
  private val cell = """{"mark":"point",$at}"""
  private val wrapped = """{"facet":{"field":"r","type":"nominal"},"columns":2,"spec":$cell}"""
  private val crossed = """{"facet":{"row":{"field":"r","type":"nominal"}},"spec":$cell}"""

  /** A grid that is the whole chart counts its cells, which is what already worked. */
  @Test
  fun `a grid that is the chart counts its cells`() {
    assertEquals(
      "source data_0 facet_domain facet_domain_row facet_domain_column",
      datasets(wrapped),
    )
  }

  /** The reported shape: the same grid inside a plot of a concatenation. */
  @Test
  fun `a grid inside a plot counts its cells too`() {
    assertEquals(
      "source data_0 concat_0_facet_domain concat_0_facet_domain_row concat_0_facet_domain_column",
      datasets("""{"hconcat":[$wrapped]}"""),
    )
  }

  /** And with a plain plot beside it, which is what makes the chart a concatenation at all. */
  @Test
  fun `a grid beside a plain plot counts its cells`() {
    assertEquals(
      "source data_0 concat_0_facet_domain concat_0_facet_domain_row concat_0_facet_domain_column",
      datasets("""{"hconcat":[$wrapped,$cell]}"""),
    )
  }

  /** A crossed grid inside a plot names its values the same way, one dataset per direction. */
  @Test
  fun `a crossed grid inside a plot names its values`() {
    assertEquals("source data_0 concat_0_row_domain", datasets("""{"hconcat":[$crossed]}"""))
  }

  private val brushed =
    """{"facet":{"field":"r","type":"nominal"},"columns":2,
        "spec":{"mark":"point",$at,
        "params":[{"name":"brush","select":"interval","value":{"a":[1,2]}}]}}"""

  /** A grid that is the chart names the cell its opening brush was drawn in. */
  @Test
  fun `a brush a grid opens with names its cell`() {
    assertEquals("child + '__facet_facet_' + (facet[\"r\"])", storedUnit(brushed))
  }

  /** So does one inside a plot, whose grid the chart itself has none of. */
  @Test
  fun `a brush a grid inside a plot opens with names its cell`() {
    assertEquals(
      "concat_0_child + '__facet_facet_' + (facet[\"r\"])",
      storedUnit("""{"hconcat":[$brushed]}"""),
    )
  }
}
