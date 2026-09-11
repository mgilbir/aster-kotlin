package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a **member** of a trellis's cell writes for itself is written below the partition.
 *
 * ```ts
 * export function moveFacetDown(node: DataFlowNode) {
 *   if (node instanceof FacetNode) {
 *     if (node.numChildren() === 1 && !(node.children[0] instanceof OutputNode)) {
 *       // move down until we hit a fork or output node
 * ```
 *
 * The walk that hoists a cell's chain above the partition runs while the partition has a **single**
 * child. A cell of one view is that, and its own steps climb until they meet the named point the
 * scales read. A cell of several is not: the walk stops at the fork, so every member's own steps
 * stay below, computed over the rows that cell was handed. The fold that makes two members'
 * identical steps one node runs *after* the walk — it is in the second pass, with the facet moved
 * between the two — so a step both members write is folded below the partition, not hoisted above
 * it.
 *
 * This engine built the whole of the **first** member's chain above the partition and every later
 * member's below it, so one member's sort index — or its buckets, or its instants — stood above a
 * partition the others' stayed below. `parseData` runs per model, and the facet's own pass is the
 * only one that ends above the cut.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class CellOwnStepsTest {

  /** Every table the chart derives and every one its cells derive, with what each step writes. */
  private fun flow(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun step(transform: VegaValue.Obj): String {
      val named =
        transform.fields["as"]?.let { ":" + VegaJson.write(it).replace(Regex("""[\[\]"\s]"""), "") }
      return (transform.string("type") ?: "?") + named.orEmpty()
    }
    fun line(table: VegaValue.Obj) =
      "${table.string("name")}<-${table.string("source") ?: "-"}[" +
        (table.fields["transform"] as? VegaValue.Arr)?.values.orEmpty().joinToString(",") {
          step(it as VegaValue.Obj)
        } +
        "]"
    val cell =
      (compiled.fields["marks"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .firstOrNull { it.string("name")?.endsWith("cell") == true }
    val top =
      (compiled.fields["data"] as VegaValue.Arr).values.joinToString(" | ") {
        line(it as VegaValue.Obj)
      }
    val inside =
      (cell?.fields?.get("data") as? VegaValue.Arr)?.values.orEmpty().joinToString(" | ") {
        line(it as VegaValue.Obj)
      }
    val marks =
      (cell?.fields?.get("marks") as? VegaValue.Arr)?.values.orEmpty().joinToString(" | ") { mark ->
        (mark as VegaValue.Obj).let { "${it.string("name")}<-${it.obj("from")?.string("data")}" }
      }
    return "top: $top\ncell: $inside\nmarks: $marks"
  }

  private val data = """"data":{"values":[{"a":1,"b":2,"c":"x","g":"r","d":"q"}]}"""
  private val sorted =
    """{"mark":"bar","encoding":{"x":{"field":"d","type":"nominal","sort":["q","p"]},
       "y":{"field":"b","type":"quantitative"}}}"""
  private val plain =
    """{"mark":"point","encoding":{"x":{"field":"d","type":"nominal"},
       "y":{"field":"b","type":"quantitative"}}}"""

  private fun trellis(grid: String, vararg layers: String) =
    """{$data,"facet":{"column":{"field":"g","type":"nominal"$grid}},
       "spec":{"layer":[${layers.joinToString(",")}]}}"""

  /** The reported shape: one member of the cell orders its marks and the other does not. */
  @Test
  fun `a member's own sort index stays below the partition`() {
    assertEquals(
      "top: source_0<--[] | column_domain<-source_0[aggregate] | " +
        "data_2<-source_0[formula:x_d_sort_index] | " +
        "data_3<-data_2[stack:b_start,b_end,filter] | data_4<-source_0[filter]\n" +
        "cell: data_0<-facet[formula:x_d_sort_index] | " +
        "data_1<-data_0[stack:b_start,b_end,filter] | data_2<-facet[filter]\n" +
        "marks: child_layer_0_marks<-data_1 | child_layer_1_marks<-data_2",
      flow(trellis("", sorted, plain)),
    )
  }

  /** A step both members write is **one** node, and it is one below the partition. */
  @Test
  fun `a step both members write is folded below the partition`() {
    assertEquals(
      "top: source_0<--[] | column_domain<-source_0[aggregate] | " +
        "data_2<-source_0[formula:x_d_sort_index] | " +
        "data_4<-data_2[stack:b_start,b_end,filter] | data_5<-data_2[filter]\n" +
        "cell: data_0<-facet[formula:x_d_sort_index] | " +
        "data_2<-data_0[stack:b_start,b_end,filter] | data_3<-data_0[filter]\n" +
        "marks: child_layer_0_marks<-data_2 | child_layer_1_marks<-data_3",
      flow(
        trellis(
          "",
          sorted,
          """{"mark":"point","encoding":{"x":{"field":"d","type":"nominal","sort":["q","p"]},
             "y":{"field":"b","type":"quantitative"}}}""",
        )
      ),
    )
  }

  /** The **grid's** own index is the facet model's, and that one does stand above the partition. */
  @Test
  fun `the grid's own sort index stays above the partition`() {
    assertEquals(
      "top: source_0<--[] | data_0<-source_0[formula:column_g_sort_index] | " +
        "column_domain<-data_0[aggregate:column_g_sort_index] | " +
        "data_2<-data_0[formula:x_d_sort_index] | " +
        "data_3<-data_2[stack:b_start,b_end,filter] | data_4<-data_0[filter]\n" +
        "cell: data_0<-facet[formula:x_d_sort_index] | " +
        "data_1<-data_0[stack:b_start,b_end,filter] | data_2<-facet[filter]\n" +
        "marks: child_layer_0_marks<-data_1 | child_layer_1_marks<-data_2",
      flow(trellis(""","sort":["r","s"]""", sorted, plain)),
    )
  }

  /**
   * A cell of **one** view is no fork, so its own steps climb above the partition as they always
   * did: this is the walk running, not being stopped.
   */
  @Test
  fun `a lone view's own steps climb above the partition`() {
    assertEquals(
      "top: source_0<--[] | column_domain<-source_0[aggregate] | " +
        "data_2<-source_0[aggregate:mean_a,filter]\n" +
        "cell: data_0<-facet[aggregate:mean_a,filter]\n" +
        "marks: child_marks<-data_0",
      flow(
        """{$data,"facet":{"column":{"field":"g","type":"nominal"}},
           "spec":{"mark":"bar","encoding":{
             "x":{"field":"d","type":"nominal","sort":{"op":"mean","field":"a"}},
             "y":{"aggregate":"mean","field":"a","type":"quantitative"}}}}"""
      ),
    )
  }

  /**
   * And a lone view's own **sort index** climbs with the rest: the walk is not stopped, so the
   * column its marks are ordered by is written once above the grid rather than in every cell.
   */
  @Test
  fun `a lone view's own sort index climbs above the partition`() {
    assertEquals(
      "top: source_0<--[] | data_0<-source_0[formula:color_c_sort_index] | " +
        "column_domain<-data_0[aggregate] | " +
        "data_3<-data_0[aggregate:mean_a,stack:mean_a_start,mean_a_end,filter]\n" +
        "cell: data_0<-facet[aggregate:mean_a,stack:mean_a_start,mean_a_end,filter]\n" +
        "marks: child_marks<-data_0",
      flow(
        """{$data,"facet":{"column":{"field":"g","type":"nominal"}},
           "spec":{"mark":"bar","encoding":{
             "x":{"field":"d","type":"nominal","sort":{"op":"mean","field":"a"}},
             "y":{"aggregate":"mean","field":"a","type":"quantitative"},
             "color":{"field":"c","type":"nominal","sort":["x","y"]}}}}"""
      ),
    )
  }

  /** And a **bucket** of the grid's own still stands above it, being the facet model's step. */
  @Test
  fun `the grid's own bucketing stays above the partition`() {
    assertEquals(
      "top: source_0<--[] | data_0<-source_0[formula:a,extent,bin:bin_maxbins_6_a," +
        "bin_maxbins_6_a_end] | column_domain<-data_0[aggregate] | data_3<-data_0[filter]\n" +
        "cell: data_0<-facet[filter]\n" +
        "marks: child_layer_0_marks<-data_0 | child_layer_1_marks<-facet",
      flow(
        """{$data,"facet":{"column":{"field":"a","type":"quantitative","bin":true}},
           "spec":{"layer":[
             {"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
                                         "y":{"field":"b","type":"quantitative"}}},
             {"mark":"line","encoding":{"x":{"field":"a","type":"quantitative"},
                                        "y":{"field":"b","type":"quantitative"}}}]}}"""
      ),
    )
  }
}
