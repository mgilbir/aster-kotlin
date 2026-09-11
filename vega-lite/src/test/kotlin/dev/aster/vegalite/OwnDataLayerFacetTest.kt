package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A layer that reads a table of its **own** is no child of the grid it is drawn in.
 *
 * ```ts
 * } else {
 *   // If we don't have a source defined (overriding parent's data), use the parent's facet root or main.
 *   return model.parent.component.data.facetRoot
 *     ? model.parent.component.data.facetRoot
 *     : model.parent.component.data.main;
 * }
 * ```
 * ```ts
 * export function moveFacetDown(node: DataFlowNode) {
 *   if (node instanceof FacetNode) {
 *     if (node.numChildren() === 1 && !(node.children[0] instanceof OutputNode)) {
 *       // move down until we hit a fork or output node
 * ```
 *
 * Two rules meeting. `parseRoot` hands a child the partition only where the child states no `data`,
 * so a layer with its own table hangs from a root of its own — beside the grid, not below it. And
 * `moveFacetDown` counts the partition's *children* when it decides how far the cell's chain can be
 * hoisted: a cell of three layers, two of them reading their own tables, is **one** child, so its
 * chain hoists above the grid exactly as a single mark's would and the cell's marks read `facet`.
 *
 * This engine counted the layers as written. A trellis of maps — a choropleth in each cell, the
 * same outlines over every one of them — therefore came out with the outlines drawn from the rows
 * that cell was handed rather than from the table they were given, which is a different chart and
 * not a differently named one. Eight specifications in the wild corpus are that shape.
 *
 * The **order** of the tables is part of the same answer: a grid's own value lists are written the
 * moment the walk reaches the partition, and the hoist that moves handed-in tables to the front
 * runs afterwards — so a place counted during the walk is short by however many tables the hoist
 * carried past it, which only a chart with a second root behind the grid has.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class OwnDataLayerFacetTest {

  private fun line(d: VegaValue.Obj) =
    "${d.string("name")}<-${d.string("source") ?: "-"}[" +
      (d.fields["transform"] as? VegaValue.Arr)?.values.orEmpty().joinToString(",") {
        (it as VegaValue.Obj).string("type") ?: "?"
      } +
      "]"

  /** Every table the chart derives, in the order it writes them. */
  private fun tables(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr).values.joinToString(" | ") {
      line(it as VegaValue.Obj)
    }
  }

  /** What the cell computes for itself, and what each mark inside it reads. */
  private fun reads(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun walk(marks: List<VegaValue>): List<String> = marks.flatMap { mark ->
      mark as VegaValue.Obj
      val inside = (mark.fields["marks"] as? VegaValue.Arr)?.values
      (mark.fields["data"] as? VegaValue.Arr)?.values.orEmpty().map {
        "cell ${line(it as VegaValue.Obj)}"
      } +
        if (inside != null) walk(inside)
        else if (mark.string("name")?.endsWith("marks") == true)
          listOf("${mark.string("name")}<-${mark.obj("from")?.string("data")}")
        else emptyList()
    }
    return walk((compiled.fields["marks"] as VegaValue.Arr).values).joinToString(" | ")
  }

  /** One table as it is written out, named. */
  private fun dataset(spec: String, name: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val found =
      (compiled.fields["data"] as VegaValue.Arr).values.first {
        (it as VegaValue.Obj).string("name") == name
      }
    return VegaJson.write(found).replace(Regex("""\n\s*"""), "")
  }

  private val data = """"data":{"values":[{"a":1,"b":2,"r":"x","c":"y"}]}"""
  private val grid = """"facet":{"column":{"field":"c","type":"ordinal"}}"""
  private val point =
    """{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
         "y":{"field":"b","type":"quantitative"}}}"""

  private fun own(rows: String, mark: String) =
    """{"data":{"values":[$rows]},"mark":"$mark",
       "encoding":{"x":{"field":"a","type":"quantitative"},
                   "y":{"field":"b","type":"quantitative"}}}"""

  private fun facetted(vararg layers: String) =
    """{$data,$grid,"spec":{"layer":[${layers.joinToString(",")}]}}"""

  /** The reported shape: one layer drawn from the grid's rows, one from a table of its own. */
  @Test
  fun `an own-data layer stands beside the grid`() {
    val spec = facetted(point, own("""{"a":3,"b":4}""", "line"))
    assertEquals(
      "source_0<--[] | source_1<--[] | data_0<-source_0[filter] | " +
        "column_domain<-data_0[aggregate] | data_2<-source_1[formula]",
      tables(spec),
    )
    assertEquals(
      "child_layer_0_marks<-facet | child_layer_1_marks<-data_2",
      reads(spec),
    )
  }

  /** Two of them beside one: still one child, so the cell's chain still hoists. */
  @Test
  fun `two own-data layers leave one child below the grid`() {
    val spec = facetted(point, own("""{"a":3,"b":4}""", "line"), own("""{"a":5,"b":6}""", "rule"))
    assertEquals(
      "source_0<--[] | source_1<--[] | source_2<--[] | data_0<-source_0[filter] | " +
        "column_domain<-data_0[aggregate] | data_2<-source_1[formula] | data_3<-source_2[filter]",
      tables(spec),
    )
    assertEquals(
      "child_layer_0_marks<-facet | child_layer_1_marks<-data_2 | child_layer_2_marks<-data_3",
      reads(spec),
    )
  }

  /**
   * The members of such a layer read *its* table, so they stand beside the grid with it: the
   * partition is the parent's answer, and a layer that read a table of its own never asked for one.
   */
  @Test
  fun `a layer inside an own-data layer stands beside the grid too`() {
    val spec =
      facetted(
        point,
        """{"data":{"values":[{"a":3,"b":4}]},"layer":[
             {"mark":"line","encoding":{"x":{"field":"a","type":"quantitative"},
                                        "y":{"field":"b","type":"quantitative"}}},
             {"mark":"rule","encoding":{"x":{"field":"a","type":"quantitative"},
                                        "y":{"field":"b","type":"quantitative"}}}]}""",
      )
    assertEquals(
      "source_0<--[] | source_1<--[] | data_0<-source_0[filter] | " +
        "column_domain<-data_0[aggregate] | data_2<-source_1[formula] | data_3<-data_2[filter]",
      tables(spec),
    )
    assertEquals(
      "child_layer_0_marks<-facet | child_layer_1_layer_0_marks<-data_2 | " +
        "child_layer_1_layer_1_marks<-data_3",
      reads(spec),
    )
  }

  /** Two layers that **inherit** are two children, and a fork is where the hoisting stops. */
  @Test
  fun `two inheriting layers still split the flow`() {
    val spec =
      facetted(
        point,
        """{"mark":"line","encoding":{"x":{"field":"a","type":"quantitative"},
             "y":{"field":"b","type":"quantitative"}}}""",
      )
    assertEquals(
      "source_0<--[] | data_0<-source_0[formula] | column_domain<-data_0[aggregate] | " +
        "data_3<-data_0[filter]",
      tables(spec),
    )
    assertEquals(
      "cell data_0<-facet[filter] | child_layer_0_marks<-data_0 | child_layer_1_marks<-facet",
      reads(spec),
    )
  }

  /**
   * A named point the scales read stops the hoisting on its own, and the layer beside it is still
   * beside it: the pre-aggregation table a sorted domain asks for is that point.
   */
  @Test
  fun `a lone child that needs a raw table still splits`() {
    val spec =
      facetted(
        """{"mark":"bar","encoding":{
             "x":{"field":"r","type":"nominal","sort":{"op":"mean","field":"a"}},
             "y":{"aggregate":"mean","field":"a","type":"quantitative"}}}""",
        own("""{"a":3,"b":4}""", "line"),
      )
    assertEquals(
      "source_0<--[] | source_1<--[] | column_domain<-source_0[aggregate] | " +
        "data_2<-source_0[aggregate,filter] | data_3<-source_1[formula]",
      tables(spec),
    )
    assertEquals(
      "cell data_0<-facet[aggregate,filter] | child_layer_0_marks<-data_0 | " +
        "child_layer_1_marks<-data_3",
      reads(spec),
    )
  }

  /**
   * The grid's fields group nothing of such a layer's: its rows are the same in every cell, so
   * there are no cells for its aggregate to be taken within.
   */
  @Test
  fun `an own-data layer groups by none of the grid's fields`() {
    assertEquals(
      """{"name": "data_2","source": "source_1","transform": [{"type": "aggregate",""" +
        """"groupby": ["a"],"ops": ["mean"],"fields": ["b"],"as": ["mean_b"]},""" +
        """{"type": "filter","expr": "isValid(datum[\"a\"]) && isFinite(+datum[\"a\"]) && """ +
        """isValid(datum[\"mean_b\"]) && isFinite(+datum[\"mean_b\"])"}]}""",
      dataset(
        facetted(
          point,
          """{"data":{"values":[{"a":3,"b":4}]},"mark":"bar",
             "encoding":{"x":{"field":"a","type":"quantitative"},
                         "y":{"aggregate":"mean","field":"b","type":"quantitative"}}}""",
        ),
        "data_2",
      ),
    )
  }

  /** And the grid's own bucketing stands above the grid, where such a layer's chain never goes. */
  @Test
  fun `an own-data layer does not bucket the grid's field`() {
    assertEquals(
      """{"name": "data_2","source": "source_1","transform": [{"type": "formula",""" +
        """"expr": "toNumber(datum[\"a\"])","as": "a"}]}""",
      dataset(
        """{$data,"facet":{"column":{"field":"a","type":"quantitative","bin":true}},
           "spec":{"layer":[$point,${own("""{"a":3,"b":4}""", "line")}]}}""",
        "data_2",
      ),
    )
  }
}
