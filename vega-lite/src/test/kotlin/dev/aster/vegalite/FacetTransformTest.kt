package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A grid's own transforms run **as well as** its cell's, and above them.
 *
 * ```js
 * if (model.transforms.length > 0) {
 *   head = parseTransformArray(head, model, ancestorParse);
 * }
 * ```
 *
 * `parseData` runs once per model, and a faceted chart has one model per level plus the cell's. The
 * grid's pass writes its transforms and then the partition; the cell's writes its own below. A
 * chart that computes a column and grids a view that filters on it is two passes, not a choice
 * between them.
 *
 * This compiler folded a grid's properties into its cell and let the cell's own win, which is right
 * for a `data` or a `width` and wrong for a `transform`: the grid's were dropped outright wherever
 * the cell wrote any of its own, so the cell filtered on a column nothing had written and the chart
 * came out empty. A grid whose cells are grids is the same rule twice over, outermost first.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class FacetTransformTest {

  /** Each dataset and the steps it runs. */
  private fun steps(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr).values.joinToString(" | ") {
      it as VegaValue.Obj
      val steps =
        (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty().joinToString(",") { step ->
          step as VegaValue.Obj
          step.string("type").orEmpty() + (step.string("as")?.let { name -> ":$name" } ?: "")
        }
      "${it.string("name")}($steps)"
    }
  }

  private val rows = """"data":{"values":[{"a":1,"b":2,"g":"x","h":"y"}]}"""
  private val enc =
    """"encoding":{"x":{"field":"a","type":"quantitative"},
                                   "y":{"field":"b","type":"quantitative"}}"""

  /** The reported shape: a grid and its cell each writing a transform. */
  @Test
  fun `a grid's transform runs above its cell's`() {
    assertEquals(
      "source_0() | data_0(formula:outer,formula:inner,filter) | column_domain(aggregate)",
      steps(
        """{$rows,"transform":[{"calculate":"1","as":"outer"}],
           "facet":{"column":{"field":"g","type":"nominal"}},
           "spec":{"transform":[{"calculate":"2","as":"inner"}],"mark":"point",$enc}}"""
      ),
    )
  }

  /** The motivating shape: a cell filtering on a column the grid computes. */
  @Test
  fun `a cell can filter on a column the grid computed`() {
    assertEquals(
      "source_0() | data_0(formula:plus,filter,filter) | column_domain(aggregate)",
      steps(
        """{$rows,"transform":[{"calculate":"datum.a+1","as":"plus"}],
           "facet":{"column":{"field":"g","type":"nominal"}},
           "spec":{"transform":[{"filter":"datum.plus > 0"}],"mark":"point",$enc}}"""
      ),
    )
  }

  /** The grid's alone, which is what a cell that writes none has always had. */
  @Test
  fun `a grid's transform alone still runs`() {
    assertEquals(
      "source_0() | data_0(formula:outer,filter) | column_domain(aggregate)",
      steps(
        """{$rows,"transform":[{"calculate":"1","as":"outer"}],
           "facet":{"column":{"field":"g","type":"nominal"}},
           "spec":{"mark":"point",$enc}}"""
      ),
    )
  }

  /** And the cell's alone, which is the ordinary faceted chart. */
  @Test
  fun `a cell's transform alone still runs`() {
    assertEquals(
      "source_0() | data_0(formula:inner,filter) | column_domain(aggregate)",
      steps(
        """{$rows,"facet":{"column":{"field":"g","type":"nominal"}},
           "spec":{"transform":[{"calculate":"2","as":"inner"}],"mark":"point",$enc}}"""
      ),
    )
  }

  /**
   * A grid whose cells are grids: every level writes its own, outermost first.
   *
   * The **order** is asserted and not which dataset each step lands in. Upstream puts the innermost
   * cell's below the inner partition — `data_0(l0,l1) | column_domain | data_3(l2,filter)` — where
   * this compiler still writes it above, the peel folding every level into one model and leaving
   * nothing to tell one level's transforms from another's. That is a gap of its own, and what this
   * rule is about is that all three run at all, in the order their models' passes run them.
   */
  @Test
  fun `every level of a nested grid writes its own`() {
    assertEquals(
      "formula:l0,formula:l1,formula:l2",
      Regex("""formula:\w+""")
        .findAll(
          steps(
            """{$rows,"transform":[{"calculate":"1","as":"l0"}],
             "facet":{"column":{"field":"g","type":"nominal"}},
             "spec":{"transform":[{"calculate":"2","as":"l1"}],
                     "facet":{"row":{"field":"h","type":"nominal"}},
                     "spec":{"transform":[{"calculate":"3","as":"l2"}],"mark":"point",$enc}}}"""
          )
        )
        .joinToString(",") { it.value },
    )
  }
}
