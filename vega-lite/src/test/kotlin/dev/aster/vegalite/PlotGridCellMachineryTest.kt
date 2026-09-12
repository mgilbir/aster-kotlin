package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A plot's grid is a grid: its **cell** is the unit, and what a unit carries is carried there.
 *
 * ```js
 * export function assembleFacetSignals(model: FacetModel, signals: Signal[]) {
 *   if (model.component.selection && keys(model.component.selection).length > 0) {
 *     const name = stringValue(model.getName('cell'));
 *     signals.unshift({name: 'facet', value: {}, on: [...]});
 *   }
 *   return assembleTopLevelSignals(model, signals);
 * }
 * ```
 *
 * `assembleUnitSelectionSignals` runs on the unit model, and inside a grid the unit is the cell:
 * the marks a selection watches are drawn there, the scales it reads are the cell's, and the
 * `facet` signal beside it says which cell the pointer is in. The cell's own name carries that too
 * — `unitName` of a cell is its name **and the value it holds** — so that a pick made in one cell
 * is told from the same pick made in another.
 *
 * This compiler wrote a gridded *plot's* machinery on the plot's group: one set of signals watched
 * every cell at once, the pointer over any of them wrote the same tuple, and nothing said which
 * cell it came from. Three more things were the chart's where they should have been the plot's —
 * the name of the partition a cell scale measures, the columns the cells count for themselves, and
 * the cell's own size signals.
 *
 * And what the cells count comes **first** in the partition's aggregate: `assembleFacet` starts
 * from `getCardinalityAggregateForChild` and pushes the sort's own onto it. This is the first grid
 * to ask for both.
 *
 * Three specifications in the wild corpus grid a plot and select inside it.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class PlotGridCellMachineryTest {

  /** The chart's signals, then every group with what it carries. */
  private fun shape(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun names(of: VegaValue?) =
      (of as? VegaValue.Arr)?.values.orEmpty().joinToString(",") {
        (it as VegaValue.Obj).string("name").orEmpty()
      }
    fun walk(marks: List<VegaValue>): List<String> = marks.flatMap {
      it as VegaValue.Obj
      val bits =
        listOfNotNull(
          it.fields["signals"]?.let { signals -> "sig=[${names(signals)}]" },
          it.obj("from")?.obj("facet")?.fields?.get("aggregate")?.let { aggregate ->
            "agg=${VegaJson.write(aggregate).replace(Regex("""\s+"""), "")}"
          },
          it.fields["scales"]?.let { scales -> "scales=[${names(scales)}]" },
        )
      listOf("${it.string("name")} ${bits.joinToString(" ")}".trim()) +
        walk((it.fields["marks"] as? VegaValue.Arr)?.values.orEmpty())
    }
    val unit = Regex("""unit: [^,]*""").find(VegaJson.write(compiled))?.value?.replace("\\\"", "\"")
    return "top=[${names(compiled.fields["signals"])}] | " +
      walk((compiled.fields["marks"] as VegaValue.Arr).values).joinToString(" | ") +
      " | $unit"
  }

  private val rows = """"data":{"values":[{"a":1,"b":"p","r":"x","g":2}]}"""
  private val plot =
    """"mark":"point",
       "encoding":{"x":{"field":"a","type":"quantitative"},
                   "y":{"field":"b","type":"nominal"},
                   "row":{"field":"r","type":"nominal","sort":{"field":"g"}}},
       "params":[{"name":"zoom","select":{"type":"interval","encodings":["x"]},
                  "bind":"scales"}],
       "resolve":{"scale":{"y":"independent"}}"""

  /** The reported shape: a plot of a concatenation that grids its cell and is zoomed inside. */
  @Test
  fun `a plot's cell carries the machinery`() {
    assertEquals(
      "top=[concat_0_child_width,concat_0_child_y_step,unit,zoom,zoom_a] | " +
        "concat_0_group | row-title | concat_0_row_header | concat_0_column_footer | " +
        "concat_0_cell sig=[facet,zoom_a,zoom_tuple,zoom_tuple_fields,zoom_translate_anchor," +
        "zoom_translate_delta,zoom_zoom_anchor,zoom_zoom_delta,zoom_modify] " +
        """agg={"fields":["b","g"],"ops":["distinct","min"],"as":["distinct_b","g_by_r"]} """ +
        "scales=[concat_0_child_y] | concat_0_child_marks | " +
        """unit: "concat_0_child" + '__facet_row_' + (facet["r"])""",
      shape("""{$rows,"hconcat":[{$plot}]}"""),
    )
  }

  /** The **chart's** own grid answers the same way, which is where this was already right. */
  @Test
  fun `a chart's own cell is unchanged`() {
    assertEquals(
      "top=[child_width,child_y_step,unit,zoom,zoom_a] | " +
        "row-title | row_header | column_footer | " +
        "cell sig=[facet,zoom_a,zoom_tuple,zoom_tuple_fields,zoom_translate_anchor," +
        "zoom_translate_delta,zoom_zoom_anchor,zoom_zoom_delta,zoom_modify] " +
        """agg={"fields":["b","g"],"ops":["distinct","min"],"as":["distinct_b","g_by_r"]} """ +
        """scales=[child_y] | child_marks | unit: "child" + '__facet_row_' + (facet["r"])""",
      shape("""{$rows,$plot}"""),
    )
  }

  /** The cell's own scale measures the partition **that grid** cut, which is named for the plot. */
  @Test
  fun `a cell scale measures its own grid's partition`() {
    val plain =
      """"mark":"point",
         "encoding":{"x":{"field":"a","type":"quantitative"},
                     "y":{"field":"b","type":"nominal"},
                     "row":{"field":"r","type":"nominal"}},
         "resolve":{"scale":{"y":"independent"}}"""
    assertEquals(
      """concat_0_cell/concat_0_child_y={"data":"concat_0_facet","field":"b","sort":true}""",
      domains("""{$rows,"hconcat":[{$plain}]}"""),
    )
    // The chart's own grid cuts a partition called `facet`, which is what it always measured.
    assertEquals(
      """cell/child_y={"data":"facet","field":"b","sort":true}""",
      domains("""{$rows,$plain}"""),
    )
  }

  /** Every cell scale, by the group it stands in, and the dataset its domain measures. */
  private fun domains(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun walk(marks: List<VegaValue>): List<String> = marks.flatMap {
      it as VegaValue.Obj
      (it.fields["scales"] as? VegaValue.Arr)?.values.orEmpty().map { scale ->
        scale as VegaValue.Obj
        "${it.string("name")}/${scale.string("name")}=" +
          VegaJson.write(scale.fields["domain"]!!).replace(Regex("""\s+"""), "")
      } + walk((it.fields["marks"] as? VegaValue.Arr)?.values.orEmpty())
    }
    return walk((compiled.fields["marks"] as VegaValue.Arr).values).joinToString(" | ")
  }
}
