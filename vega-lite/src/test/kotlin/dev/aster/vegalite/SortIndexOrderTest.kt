package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A grid's sort index is written above its cells' — the facet's model parses first.
 *
 * `model.parse()` walks the tree top-down, so a `FacetModel` parses its data before its child does
 * and `CalculateNode.parseAllForSortIndex` writes the grid's index above the cell's chain. Below
 * it, the cell's own channels are indexed in the order the encoding lists them.
 *
 * This engine lifted the facet channels out of the encoding and appended their indices, so the
 * formulas came out the other way about. Four specifications in the wild corpus are a trellis whose
 * columns *and* whose marks are listed in stated orders, and every one of their formulas was in the
 * wrong place.
 *
 * A crossed grid writes its two in the order it was written — row before column where the
 * specification wrote it that way — which is what `forEachFieldDef` walks.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class SortIndexOrderTest {

  /** The sort-index formulas, in the order they are written. */
  private fun indices(spec: String): List<String> {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .map { it as VegaValue.Obj }
      .flatMap { (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty() }
      .map { it as VegaValue.Obj }
      .filter { it.string("type") == "formula" && it.string("as")?.endsWith("_sort_index") == true }
      .mapNotNull { it.string("as") }
  }

  private val data = """"data":{"values":[{"a":1,"c":"x","s":"p"}]}"""

  private fun chart(encoded: String) =
    """{$data,"mark":"bar","encoding":{"x":{"field":"c","type":"nominal","sort":["x","y"]},
       "y":{"field":"a","type":"quantitative"},$encoded}}"""

  /** The reported shape: the grid's column index comes before the cell's own two. */
  @Test
  fun `a column index is written before the cell's`() {
    assertEquals(
      listOf("column_s_sort_index", "x_c_sort_index", "color_c_sort_index"),
      indices(
        chart(
          """"color":{"field":"c","type":"nominal","sort":["y","x"]},
             "column":{"field":"s","type":"nominal","sort":["p","q"]}"""
        )
      ),
    )
  }

  /** A crossed grid writes its two as they were written, and both before the cell's. */
  @Test
  fun `a crossed grid writes its two indices in the order written`() {
    assertEquals(
      listOf("row_s_sort_index", "column_c_sort_index", "x_c_sort_index"),
      indices(
        chart(
          """"row":{"field":"s","type":"nominal","sort":["p","q"]},
             "column":{"field":"c","type":"nominal","sort":["x","y"]}"""
        )
      ),
    )
  }

  /** With no grid, the cell's channels are indexed in the encoding's own order. */
  @Test
  fun `a plot with no grid indexes its channels as written`() {
    assertEquals(
      listOf("x_c_sort_index", "color_c_sort_index"),
      indices(chart(""""color":{"field":"c","type":"nominal","sort":["y","x"]}""")),
    )
  }
}
