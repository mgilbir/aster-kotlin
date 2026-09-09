package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A grid's cells are aligned only along a direction they can be aligned along.
 *
 * `assembleDefaultLayout` in `compile/facet.ts` starts from `align: 'all'` and drops to `'none'`
 * where the scale along a direction is each cell's own: the cells' plotting areas are then
 * different sizes, and lining them up would be lining up nothing.
 *
 * ```js
 * if (!row && this.component.resolve.scale.x === 'independent') align = 'none';
 * else if (!column && this.component.resolve.scale.y === 'independent') align = 'none';
 * ```
 *
 * A **crossed** grid is aligned regardless — every cell shares a row and a column, so both guards
 * fail — and that is what the `!row` and `!column` are for. A **wrapped** facet has neither, so
 * both arms are live and either direction being independent is enough. This engine wrote `align:
 * "all"` on every wrapped facet and never consulted the resolution at all: 42 specifications in the
 * wild corpus disagreed with upstream here, for most of them as their only disagreement.
 */
class FacetLayoutAlignTest {

  private fun align(json: String): String? {
    val spec =
      VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(json).toJson()) { "did not compile" }
      ) as VegaValue.Obj
    val layout = spec.fields["layout"] as? VegaValue.Obj
    return (layout?.fields?.get("align") as? VegaValue.Str)?.value
  }

  private fun wrapped(extra: String, facetExtra: String = "") =
    align(
      """
      {"data":{"values":[{"a":1,"b":2,"c":"x"}]},
       "mark":"point",
       $extra
       "encoding":{
         "x":{"field":"a","type":"quantitative"},
         "y":{"field":"b","type":"quantitative"},
         "facet":{"field":"c","type":"nominal","columns":2$facetExtra}}}
      """
    )

  /** The reported shape: `y` is each cell's own, so the cells cannot be aligned. */
  @Test
  fun `a wrapped facet with an independent y is not aligned`() {
    assertEquals("none", wrapped(""""resolve":{"scale":{"y":"independent"}},"""))
  }

  /** The other arm, which a crossed grid's `!row` would have blocked. */
  @Test
  fun `a wrapped facet with an independent x is not aligned`() {
    assertEquals("none", wrapped(""""resolve":{"scale":{"x":"independent"}},"""))
  }

  /** With both scales shared there is something to line up, and the default stands. */
  @Test
  fun `a wrapped facet with shared scales is aligned`() {
    assertEquals("all", wrapped(""))
  }

  /**
   * `getFacetMappingAndLayout` lifts `align` off the facet definition onto the grid, and
   * `assembleLayout` spreads it *after* the default — so a stated value outranks the computed one,
   * in either direction.
   */
  @Test
  fun `a stated align outranks the default`() {
    assertEquals(
      "each",
      wrapped(""""resolve":{"scale":{"y":"independent"}},""", ""","align":"each""""),
      "stated on the facet definition, where the encoding form writes it",
    )
    assertEquals(
      "each",
      wrapped("", ""","align":"each""""),
      "and it outranks the `all` a shared-scale grid would otherwise get",
    )
  }

  /**
   * A **crossed** grid is aligned even with an independent scale, because every cell shares a row
   * and a column with another. The guards that make this true are the ones a wrapped facet lacks,
   * so it is checked alongside.
   */
  @Test
  fun `a crossed grid is aligned despite an independent scale`() {
    val crossed =
      align(
        """
        {"data":{"values":[{"a":1,"b":2,"c":"x","d":"y"}]},
         "mark":"point",
         "resolve":{"scale":{"y":"independent"}},
         "encoding":{
           "x":{"field":"a","type":"quantitative"},
           "y":{"field":"b","type":"quantitative"},
           "row":{"field":"c","type":"nominal"},
           "column":{"field":"d","type":"nominal"}}}
        """
      )
    assertEquals("all", crossed)
  }

  /** And a grid faceted one way only keeps the arm that applies to it. */
  @Test
  fun `a row-only grid with an independent y is not aligned`() {
    val rows =
      align(
        """
        {"data":{"values":[{"a":1,"b":2,"c":"x"}]},
         "mark":"point",
         "resolve":{"scale":{"y":"independent"}},
         "encoding":{
           "x":{"field":"a","type":"quantitative"},
           "y":{"field":"b","type":"quantitative"},
           "row":{"field":"c","type":"nominal"}}}
        """
      )
    assertEquals("none", rows, "it has no column, so the `y` arm is live")
  }
}
