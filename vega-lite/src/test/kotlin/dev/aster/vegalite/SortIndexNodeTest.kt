package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The column a stated order writes is a **node of its own**, one per channel.
 *
 * ```ts
 * } else if (isArray(sort)) {
 *   const sortField = sortArrayIndexField(fieldDef, channel);
 *   parsedFieldDefs.push(sortField);
 * ```
 *
 * Each is its own `CalculateNode`, and a node is what the fold works on. Two members of a cell that
 * order their marks by the same list write the same calculate, so those two fold into one — and
 * whatever else either of them writes stays where it was, below the fold. A single node carrying
 * both columns cannot do that: it is equal to neither of the others, so nothing folds and the
 * member that wrote only one of the two columns is handed both.
 *
 * The four specifications in the wild corpus this tells on are a trellis of two layers, one
 * ordering its bars and its colours by two stated lists and the other ordering only its bars.
 *
 * Written as **one dataset** wherever nothing stands between them, which is how a lone view's two
 * columns come out: the split is in the flow, not in the writing.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class SortIndexNodeTest {

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
    return "top: " +
      (compiled.fields["data"] as VegaValue.Arr).values.joinToString(" | ") {
        line(it as VegaValue.Obj)
      } +
      "\ncell: " +
      (cell?.fields?.get("data") as? VegaValue.Arr)?.values.orEmpty().joinToString(" | ") {
        line(it as VegaValue.Obj)
      } +
      "\nmarks: " +
      (cell?.fields?.get("marks") as? VegaValue.Arr)?.values.orEmpty().joinToString(" | ") { mark ->
        (mark as VegaValue.Obj).let { "${it.string("name")}<-${it.obj("from")?.string("data")}" }
      }
  }

  private val data = """"data":{"values":[{"a":1,"b":2,"c":"x","g":"r","d":"q"}]}"""
  private val bars =
    """"x":{"field":"d","type":"nominal","sort":["q","p"]},
       "y":{"field":"b","type":"quantitative"}"""

  /** The reported shape: one member orders two channels and the other only one. */
  @Test
  fun `each column a stated order writes is its own node`() {
    assertEquals(
      "top: source_0<--[] | column_domain<-source_0[aggregate] | " +
        "data_2<-source_0[formula:x_d_sort_index] | " +
        "data_3<-data_2[formula:color_c_sort_index] | " +
        "data_4<-data_3[stack:b_start,b_end,filter] | data_5<-data_2[filter]\n" +
        "cell: data_0<-facet[formula:x_d_sort_index] | " +
        "data_1<-data_0[formula:color_c_sort_index] | " +
        "data_2<-data_1[stack:b_start,b_end,filter] | data_3<-data_0[filter]\n" +
        "marks: child_layer_0_marks<-data_2 | child_layer_1_marks<-data_3",
      flow(
        """{$data,"facet":{"column":{"field":"g","type":"nominal"}},"spec":{"layer":[
           {"mark":"bar","encoding":{$bars,
             "color":{"field":"c","type":"nominal","sort":["x","y"]}}},
           {"mark":"point","encoding":{$bars}}]}}"""
      ),
    )
  }

  /**
   * Two nodes with nothing between them are one dataset: the split is in the flow, not the text.
   */
  @Test
  fun `a lone view's two columns are written in one table`() {
    assertEquals(
      "top: source_0<--[] | " +
        "data_0<-source_0[formula:x_d_sort_index,formula:color_c_sort_index] | " +
        "column_domain<-data_0[aggregate] | data_3<-data_0[stack:b_start,b_end,filter]\n" +
        "cell: data_0<-facet[stack:b_start,b_end,filter]\n" +
        "marks: child_marks<-data_0",
      flow(
        """{$data,"facet":{"column":{"field":"g","type":"nominal"}},
           "spec":{"mark":"bar","encoding":{$bars,
             "color":{"field":"c","type":"nominal","sort":["x","y"]}}}}"""
      ),
    )
  }
}
