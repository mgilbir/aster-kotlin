package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A wrapped grid whose values are **listed** is ordered by the place each cell holds in that list.
 *
 * ```js
 * } else if (isArray(sort)) {
 *   const outputName = sortArrayIndexField(fieldDef, channel);
 *   fields.push(outputName);
 *   ops.push('max');
 *   as.push(outputName);
 * }
 * ```
 *
 * The list is a stated sequence, and a cell's place in it cannot be read off the column being
 * faceted on: the place is computed onto every row first and the grid takes the **greatest** of
 * each cell's, every row of a cell carrying the same number. Both the grid's own value list and the
 * partition have to carry it up, or the cells are left in whatever order their column comes in.
 *
 * This compiler wrote the column and then ordered the grid by the faceted column anyway — the two
 * places that carry it were written for an aggregate `sort` and answered nothing for a list. Four
 * specifications in the wild corpus are a wrapped trellis with a stated order, and each came out
 * with its cells in the wrong order.
 *
 * The index is carried under the name it already has, where an aggregate's is suffixed with the
 * faceted column: it is computed once above the grid rather than a second time per cell, so there
 * is no second column for it to collide with.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class WrappedFacetOrderTest {

  /** The grid's own value list, the partition it cuts, and the order its cells run in. */
  private fun grid(sort: String): String {
    val spec =
      """{"data":{"values":[{"a":1,"b":2,"g":"r"}]},
         "facet":{"field":"g","type":"nominal"$sort},"columns":2,
         "spec":{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
                                            "y":{"field":"b","type":"quantitative"}}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun written(value: VegaValue?) =
      VegaJson.write(value ?: VegaValue.Null).replace(Regex("""\n\s*"""), "")
    val domain =
      (compiled.fields["data"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first {
          it.string("name")?.endsWith("facet_domain") == true
        }
    val cell =
      (compiled.fields["marks"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first {
          it.string("name")?.endsWith("cell") == true
        }
    return "domain=${written(domain.fields["transform"])} " +
      "from=${written(cell.fields["from"])} sort=${written(cell.fields["sort"])}"
  }

  /** The reported shape: a wrapped grid whose values are listed in a stated order. */
  @Test
  fun `a listed order is carried up as the place each cell holds in it`() {
    assertEquals(
      """domain=[{"type": "aggregate","groupby": ["g"],"fields": ["facet_g_sort_index"],""" +
        """"ops": ["max"],"as": ["facet_g_sort_index"]}] """ +
        """from={"facet": {"name": "facet","data": "data_0","groupby": ["g"],""" +
        """"aggregate": {"fields": ["facet_g_sort_index"],"ops": ["max"],""" +
        """"as": ["facet_g_sort_index"]}}} """ +
        """sort={"field": ["datum[\"facet_g_sort_index\"]"],"order": ["ascending"]}""",
      grid(""","sort":["s","r"]"""),
    )
  }

  /** With nothing stated the grid runs in its own column's order, as it always did. */
  @Test
  fun `an unsorted grid runs in its column's order`() {
    assertEquals(
      """domain=[{"type": "aggregate","groupby": ["g"]}] """ +
        """from={"facet": {"name": "facet","data": "data_0","groupby": ["g"]}} """ +
        """sort={"field": ["datum[\"g\"]"],"order": ["ascending"]}""",
      grid(""),
    )
  }

  /** An **aggregate** order is carried up as it was, suffixed where the cell measures its own. */
  @Test
  fun `an aggregate order is still measured per cell`() {
    assertEquals(
      """domain=[{"type": "aggregate","groupby": ["g"],"fields": ["a"],"ops": ["mean"],""" +
        """"as": ["mean_a"]}] """ +
        """from={"facet": {"name": "facet","data": "data_0","groupby": ["g"],""" +
        """"aggregate": {"fields": ["a"],"ops": ["mean"],"as": ["mean_a_by_g"]}}} """ +
        """sort={"field": ["datum[\"mean_a_by_g\"]"],"order": ["ascending"]}""",
      grid(""","sort":{"op":"mean","field":"a"}"""),
    )
  }

  /** And a bare direction turns the same column round rather than measuring anything. */
  @Test
  fun `a bare direction orders the column itself`() {
    assertEquals(
      """domain=[{"type": "aggregate","groupby": ["g"]}] """ +
        """from={"facet": {"name": "facet","data": "data_0","groupby": ["g"]}} """ +
        """sort={"field": ["datum[\"g\"]"],"order": ["descending"]}""",
      grid(""","sort":"descending""""),
    )
  }
}
