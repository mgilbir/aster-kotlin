package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A transform climbs above a grid's partition only as far as the **fork** below it.
 *
 * ```js
 * export function moveFacetDown(node: DataFlowNode) {
 *   if (isFacetNode(node)) {
 *     …
 *     if (node.numChildren() === 1 && !isOutputNode(node.children[0])) {
 *       const child = node.children[0];
 *       if (child instanceof AggregateNode || …) {
 *         child.addDimensions(node.fields);
 *       }
 *       child.swapWithParent();
 *       moveFacetDown(node);
 * ```
 *
 * The partition walks down one node at a time and stops where the flow forks, so what climbs past
 * it is what the models **at or above the cell** wrote — one chain, no fork in it. A layer *inside*
 * the cell wrote its transforms below that fork, and they stay there, computed once per cell.
 *
 * This compiler asked instead whether the transform belonged to the view that carried a copy of it.
 * A member's copy of its own layer's step is that layer's, so it counted as the grid's and was
 * written above the cut — where the chain is built from one view of the cell and knows nothing of
 * it, so it was written **nowhere at all**. A layer that filters itself down to one series had that
 * filter dropped, and every mark in the cell drew every row.
 *
 * One specification in the wild corpus layers a filtered line inside a gridded cell.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class CellTransformCutTest {

  /** Every dataset the chart derives, then the cell's own, then what each mark reads. */
  private fun flow(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun datasets(of: List<VegaValue>) =
      of.joinToString(" ") {
        it as VegaValue.Obj
        val steps =
          it.array("transform").orEmpty().joinToString(",") { step ->
            (step as VegaValue.Obj).string("type").orEmpty()
          }
        "${it.string("name")}<-${it.string("source")}[$steps]"
      }
    val cells = mutableListOf<String>()
    val marks = mutableListOf<String>()
    fun walk(of: List<VegaValue>) {
      of.forEach {
        it as VegaValue.Obj
        it.array("data")?.let { own -> cells += datasets(own) }
        if (it.string("type") != "group") {
          marks += "${it.string("name")}<-${it.obj("from")?.string("data")}"
        }
        walk((it.fields["marks"] as? VegaValue.Arr)?.values.orEmpty())
      }
    }
    walk((compiled.fields["marks"] as VegaValue.Arr).values)
    return datasets((compiled.fields["data"] as VegaValue.Arr).values) +
      " | ${cells.joinToString(" ")} | ${marks.joinToString(" ")}"
  }

  private val rows = """"data":{"values":[{"v":1,"p":2,"s":"x","h":"a"}]}"""
  private val at =
    """"encoding":{"x":{"field":"v","type":"quantitative"},
                   "y":{"field":"p","type":"quantitative"}}"""
  private val keep = """"transform":[{"filter":"datum.s == 'k'"}]"""
  private val row = """{"row":{"field":"h","type":"nominal"}}"""
  private val rule = """{"mark":"rule",$at}"""
  private val point = """{"mark":"point",$at}"""

  /**
   * The **cell model's** own transform climbs: nothing forks between the partition and it, so it is
   * computed once for the whole grid.
   */
  @Test
  fun `a transform the cell wrote climbs above the partition`() {
    assertEquals(
      "source_0<-null[] data_0<-source_0[filter] row_domain<-data_0[aggregate] " +
        "data_2<-data_0[filter] | data_0<-facet[filter] | " +
        "child_layer_0_marks<-data_0 child_layer_1_marks<-data_0",
      flow("""{$rows,"facet":$row,"spec":{$keep,"layer":[$rule,$point]}}"""),
    )
  }

  /** The reported shape: a layer **inside** the cell wrote it, below the fork, so it stays. */
  @Test
  fun `a transform a layer inside the cell wrote stays below the partition`() {
    assertEquals(
      "source_0<-null[] data_0<-source_0[formula] row_domain<-data_0[aggregate] " +
        "data_2<-data_0[filter] data_3<-data_0[filter] data_4<-data_3[filter] | " +
        "data_0<-facet[filter] data_1<-facet[filter] data_2<-data_1[filter] | " +
        "child_layer_0_marks<-data_0 child_layer_1_layer_0_marks<-data_1 " +
        "child_layer_1_layer_1_marks<-data_2",
      flow(
        """{$rows,"facet":$row,"spec":{"layer":[$rule,
            {"layer":[{"mark":"line",$at},$point],$keep}]}}"""
      ),
    )
  }

  /** The same when that layer is the cell's **first** member, which is where the chain is built. */
  @Test
  fun `a transform the cell's first member's layer wrote stays below the partition`() {
    assertEquals(
      "source_0<-null[] data_0<-source_0[formula] row_domain<-data_0[aggregate] " +
        "data_2<-data_0[filter] data_3<-data_2[filter] data_4<-data_0[filter] | " +
        "data_0<-facet[filter] data_1<-data_0[filter] data_2<-facet[filter] | " +
        "child_layer_0_layer_0_marks<-data_0 child_layer_0_layer_1_marks<-data_1 " +
        "child_layer_1_marks<-data_2",
      flow(
        """{$rows,"facet":$row,"spec":{"layer":[
            {"layer":[{"mark":"line",$at},$point],$keep},$rule]}}"""
      ),
    )
  }

  /** A transform the **plot** wrote stands above the partition of the grid inside that plot. */
  @Test
  fun `a transform a plot wrote climbs above its own cell's partition`() {
    assertEquals(
      "source_0<-null[] data_0<-source_0[filter] concat_0_row_domain<-data_0[aggregate] " +
        "data_2<-data_0[filter] | data_0<-concat_0_facet[filter] | " +
        "concat_0_child_layer_0_marks<-data_0 concat_0_child_layer_1_marks<-data_0",
      flow("""{$rows,"hconcat":[{$keep,"facet":$row,"spec":{"layer":[$rule,$point]}}]}"""),
    )
  }

  /** A transform the **chart** wrote stands above every partition in it, however deep the grid. */
  @Test
  fun `a transform the chart wrote climbs past a grid inside a plot`() {
    assertEquals(
      "source_0<-null[] data_0<-source_0[filter] concat_0_row_domain<-data_0[aggregate] " +
        "data_2<-data_0[filter] | data_0<-concat_0_facet[filter] | " +
        "concat_0_child_layer_0_marks<-data_0 concat_0_child_layer_1_marks<-data_0",
      flow("""{$rows,$keep,"hconcat":[{"facet":$row,"spec":{"layer":[$rule,$point]}}]}"""),
    )
  }

  /** And one the **grid** wrote, beside the facet, climbs as it always did. */
  @Test
  fun `a transform the grid wrote climbs above its own partition`() {
    assertEquals(
      "source_0<-null[] data_0<-source_0[filter] row_domain<-data_0[aggregate] " +
        "data_2<-data_0[filter] | data_0<-facet[filter] | " +
        "child_layer_0_marks<-data_0 child_layer_1_marks<-data_0",
      flow("""{$rows,"facet":$row,$keep,"spec":{"layer":[$rule,$point]}}"""),
    )
  }
}
