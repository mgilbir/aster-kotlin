package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `resolve` on a plot that **grids** its cell speaks about the cells.
 *
 * Every model carries its own `resolve` and each speaks about its own children. A plot of a
 * concatenation that lays out a grid has the *cell* between it and the layers, so a channel it
 * resolves independently is scaled per cell: the scale is named for the cell, built inside the cell
 * group where the rows it measures are, and its axis is drawn in the cell rather than in a band
 * beside the grid — a band of labels cannot stand for several different extents.
 *
 * And a band with neither a caption nor an axis is not drawn at all, `assembleHeaderGroup` writing
 * one only `if (title || hasAxes)`. So the row header of such a grid disappears with the axis that
 * was its only content.
 *
 * This compiler asked the **chart's** `resolve` for all of it, which speaks about the plots beside
 * each other and not the cells within one. Such a plot came out with a single shared scale, one
 * band of labels standing for extents that differ cell by cell, and the scale itself written beside
 * the grid where nothing measures a cell's rows.
 *
 * Five specifications in the wild corpus grid a plot of a concatenation.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class GridResolveInAPlotTest {

  /** The chart's scales and signals, then every group with the scales and axes it carries. */
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
      val axes = (it.fields["axes"] as? VegaValue.Arr)?.values?.size ?: 0
      listOf("${it.string("name")} scales=[${names(it.fields["scales"])}] axes=$axes") +
        walk((it.fields["marks"] as? VegaValue.Arr)?.values.orEmpty())
    }
    return "scales=[${names(compiled.fields["scales"])}] " +
      "signals=[${names(compiled.fields["signals"])}] | " +
      walk((compiled.fields["marks"] as VegaValue.Arr).values).joinToString(" | ")
  }

  private val rows = """"data":{"values":[{"a":1,"b":2,"r":"x"}]}"""
  private val gridded =
    """"mark":"point","encoding":{
       "x":{"field":"a","type":"quantitative"},
       "y":{"field":"b","type":"nominal","axis":{"grid":true,"title":null}},
       "row":{"field":"r","type":"nominal","header":{"labels":false},"title":null}}"""

  /**
   * The reported shape: a plot of a concatenation whose grid scales `y` per cell. The scale is the
   * cell's, its axis is drawn there, and the band that held it is not drawn at all.
   */
  @Test
  fun `a plot's grid scales its cells`() {
    assertEquals(
      "scales=[concat_0_x] signals=[concat_0_child_width,concat_0_child_y_step] | " +
        "concat_0_group scales=[] axes=0 | " +
        "concat_0_column_footer scales=[] axes=1 | " +
        "concat_0_cell scales=[concat_0_child_y] axes=3 | " +
        "concat_0_child_marks scales=[] axes=0",
      shape("""{$rows,"hconcat":[{$gridded,"resolve":{"scale":{"y":"independent"}}}]}"""),
    )
  }

  /**
   * With nothing resolved the cells share one scale, its axis stands in the band beside them, and
   * the band is drawn because it has one — which is the shape that must not change.
   */
  @Test
  fun `a plot's grid that shares is unchanged`() {
    assertEquals(
      "scales=[concat_0_x,concat_0_y] " +
        "signals=[concat_0_child_width,concat_0_y_step,concat_0_child_height] | " +
        "concat_0_group scales=[] axes=0 | " +
        "concat_0_row_header scales=[] axes=1 | " +
        "concat_0_column_footer scales=[] axes=1 | " +
        "concat_0_cell scales=[] axes=2 | " +
        "concat_0_child_marks scales=[] axes=0",
      shape("""{$rows,"hconcat":[{$gridded}]}"""),
    )
  }

  /** The **chart's** own grid answers the same way, which is where this rule was already right. */
  @Test
  fun `a chart's own grid is unchanged`() {
    assertEquals(
      "scales=[x] signals=[child_width,child_y_step] | " +
        "column_footer scales=[] axes=1 | " +
        "cell scales=[child_y] axes=3 | " +
        "child_marks scales=[] axes=0",
      shape("""{$rows,$gridded,"resolve":{"scale":{"y":"independent"}}}"""),
    )
  }
}
