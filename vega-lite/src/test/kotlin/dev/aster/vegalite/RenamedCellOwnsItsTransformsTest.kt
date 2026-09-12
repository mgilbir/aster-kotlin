package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A model that is **renamed** still owns what it owned.
 *
 * A transform belongs to the model it was written on, and a grid's own stand above the partition
 * whatever the cell below is called. But a cell is *renamed* as it is built — `layer_1` becomes
 * `child_layer_1`, the name belonging to the model the cell hangs from — and a transform the view's
 * own expansion wrote was still credited to the name it had before.
 *
 * The renamed view then did not recognise its own step as its own and left it to an ancestor that
 * had never heard of it, so it was written **nowhere at all**. An error bar inside a grid is where
 * it tells: its bounds are two `calculate`s written once above the parts it expands into, and
 * without them the layer filtered on columns no step computes — which is every row, so no interval
 * was drawn. Two specifications in the wild corpus draw their error bars inside a grid.
 *
 * Only where the view **has** a name of its own: a chart written with the `column` shorthand is one
 * view and the chart at once, and `owning` credits its transforms to the empty name — the name the
 * cell was renamed from. Renaming those would make the grid's own steps the cell's.
 *
 * The shapes below all layer, because a grid whose `spec` is a **single** composite mark loses its
 * grid entirely in this compiler — no cell, no headers, no domain — which is a gap of its own and
 * older than this one.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class RenamedCellOwnsItsTransformsTest {

  /** Every dataset, in the group it belongs to, with what it hangs from and the steps it runs. */
  private fun flow(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun steps(node: VegaValue.Obj) =
      (node.fields["transform"] as? VegaValue.Arr)?.values.orEmpty().joinToString(",") { step ->
        step as VegaValue.Obj
        when (step.string("type")) {
          "aggregate" ->
            "aggregate(${VegaJson.write(step.fields["groupby"]!!).replace(Regex("\\s+"), "")})"
          "formula" -> "formula(${(step.fields["as"] as VegaValue.Str).value})"
          "stack" -> "stack"
          else -> step.string("type").orEmpty()
        }
      }
    fun sets(list: List<VegaValue>) =
      list.joinToString(", ") {
        it as VegaValue.Obj
        "${it.string("name")}<-${it.string("source")}[${steps(it)}]"
      }
    fun walk(mark: VegaValue.Obj): List<String> =
      listOfNotNull(
        (mark.fields["data"] as? VegaValue.Arr)
          ?.values
          ?.takeIf { it.isNotEmpty() }
          ?.let {
            "${mark.string("name")}: ${sets(it)}"
          }
      ) +
        (mark.fields["marks"] as? VegaValue.Arr)?.values.orEmpty().flatMap {
          walk(it as VegaValue.Obj)
        }
    return (listOf("TOP: ${sets((compiled.fields["data"] as VegaValue.Arr).values)}") +
        (compiled.fields["marks"] as VegaValue.Arr).values.flatMap { walk(it as VegaValue.Obj) })
      .joinToString(" | ")
  }

  private val rows = """"data":{"values":[{"m":1,"s":0.5,"k":"a","r":"x"}]}"""
  private val bar =
    """{"mark":"bar","encoding":{"x":{"field":"m","type":"quantitative"},
                                 "y":{"field":"k","type":"nominal"}}}"""
  private val errorbar =
    """{"mark":{"type":"errorbar"},
       "encoding":{"x":{"field":"m","type":"quantitative"},"xError":{"field":"s"},
                   "y":{"field":"k","type":"nominal"}}}"""

  /** The reported shape: an error bar layered inside a grid still computes its own bounds. */
  @Test
  fun `a layer inside a grid keeps its own steps`() {
    assertEquals(
      """TOP: source_0<-null[], row_domain<-source_0[aggregate(["r"])], """ +
        "data_2<-source_0[stack,filter], " +
        "data_3<-source_0[formula(upper_m),formula(lower_m),filter] | " +
        "cell: data_0<-facet[stack,filter], " +
        "data_1<-facet[formula(upper_m),formula(lower_m),filter]",
      flow(
        """{$rows,"facet":{"row":{"field":"r","type":"ordinal"}},"spec":{"layer":[$bar,$errorbar]}}"""
      ),
    )
  }

  /** The same layer outside a grid, which is the shape that already worked. */
  @Test
  fun `a layer outside a grid is unchanged`() {
    assertEquals(
      "TOP: source_0<-null[], data_1<-source_0[stack,filter], " +
        "data_2<-source_0[formula(upper_m),formula(lower_m),filter]",
      flow("""{$rows,"layer":[$bar,$errorbar]}"""),
    )
  }

  /**
   * A grid's own transform still stands above the cell's, which is what the guard protects: a
   * chart's own are credited to the empty name, the name the cell was renamed *from*.
   */
  @Test
  fun `a chart's own transform is still the grid's`() {
    assertEquals(
      "TOP: source_0<-null[], data_0<-source_0[formula(one)], " +
        """row_domain<-data_0[aggregate(["r"])], data_2<-data_0[stack,filter], """ +
        "data_3<-data_0[formula(upper_m),formula(lower_m),filter] | " +
        "cell: data_0<-facet[stack,filter], " +
        "data_1<-facet[formula(upper_m),formula(lower_m),filter]",
      flow(
        """{$rows,"transform":[{"calculate":"1","as":"one"}],
           "facet":{"row":{"field":"r","type":"ordinal"}},"spec":{"layer":[$bar,$errorbar]}}"""
      ),
    )
  }

  /** And with no grid at all the same chart's transform stands above the same two chains. */
  @Test
  fun `a chart's own transform stands above an ungridded layer too`() {
    assertEquals(
      "TOP: source_0<-null[], data_0<-source_0[formula(one)], data_1<-data_0[stack,filter], " +
        "data_2<-data_0[formula(upper_m),formula(lower_m),filter]",
      flow("""{$rows,"transform":[{"calculate":"1","as":"one"}],"layer":[$bar,$errorbar]}"""),
    )
  }

  /**
   * An error bar that **summarises** shows the other half of the neighbouring rule: the copy beside
   * the grid groups by the grid's own column and the copy inside each cell does not, each cell
   * holding one value of it already. Carried in the mark's encoding instead, both copies grouped by
   * it — and the cell's summary was then a different question from the bar's beside it, so the
   * grouping was computed once per layer where upstream computes it once.
   */
  @Test
  fun `a summary groups by the grid beside it and not within it`() {
    assertEquals(
      """TOP: source_0<-null[], row_domain<-source_0[aggregate(["r"])], """ +
        "data_2<-source_0[stack,filter], " +
        """data_3<-source_0[aggregate(["k","r"]),formula(upper_m),formula(lower_m),filter] | """ +
        "cell: data_0<-facet[stack,filter], " +
        """data_1<-facet[aggregate(["k"]),formula(upper_m),formula(lower_m),filter]""",
      flow(
        """{$rows,"facet":{"row":{"field":"r","type":"ordinal"}},
           "spec":{"layer":[$bar,{"mark":{"type":"errorbar"},
             "encoding":{"x":{"field":"m","type":"quantitative"},
                         "y":{"field":"k","type":"nominal"}}}]}}"""
      ),
    )
  }
}
