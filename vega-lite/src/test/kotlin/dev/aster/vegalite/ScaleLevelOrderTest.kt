package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A scale stands at the **level** that owns it, and a level stands before its children.
 *
 * `assembleScales` walks the model tree and writes each model's own components before it recurses,
 * so the order of a chart's scales is the order of its levels. A nested concatenation is a level of
 * its own and may own a scale — one its plots share while the chart's other children do not — and
 * ordering by the *plots* alone put such a scale among the scales of the first plot under it, which
 * is after the level that owns it rather than before.
 *
 * Which level owns a scale is read off its **name**, which is where the answer already is: a scale
 * a level owns is called after it, so the longest level name the scale's own begins with is the one
 * that owns it. A scale named for something the composition cannot see — a layer inside a plot —
 * keeps its place after the levels, in the order the scales were built.
 *
 * One specification in the wild corpus is a column whose second entry is a row of plots sharing a
 * colour scale.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ScaleLevelOrderTest {

  /** Every scale, by name, in the order the chart writes them. */
  private fun scales(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["scales"] as VegaValue.Arr).values.joinToString(",") {
      (it as VegaValue.Obj).string("name").orEmpty()
    }
  }

  private val rows = """"data":{"values":[{"a":1,"b":2,"k":"x"}]}"""
  private val plot =
    """{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
                                   "y":{"field":"b","type":"quantitative"},
                                   "color":{"field":"k","type":"nominal"}}}"""

  /** The reported shape: the row's own colour scale stands before the scales of its plots. */
  @Test
  fun `a nested level's scale stands before its children's`() {
    assertEquals(
      "concat_0_x,concat_0_y,concat_0_color,concat_1_color," +
        "concat_1_concat_0_x,concat_1_concat_0_y,concat_1_concat_1_x,concat_1_concat_1_y",
      scales(
        """{$rows,"resolve":{"scale":{"color":"independent"}},
           "vconcat":[$plot,{"hconcat":[$plot,$plot]}]}"""
      ),
    )
  }

  /** A flat concatenation is one level per plot, which is the order it always had. */
  @Test
  fun `a flat concatenation is one level per plot`() {
    assertEquals(
      "concat_0_x,concat_0_y,concat_0_color,concat_1_x,concat_1_y,concat_1_color",
      scales("""{$rows,"resolve":{"scale":{"color":"independent"}},"vconcat":[$plot,$plot]}"""),
    )
  }

  /**
   * A **layer** is no level of a concatenation: its plot owns the scales, and a scale named for one
   * of its members follows the levels rather than standing among them.
   */
  @Test
  fun `a layer's own scale follows the levels`() {
    assertEquals(
      "x,color,layer_0_y,layer_1_y",
      scales("""{$rows,"resolve":{"scale":{"y":"independent"}},"layer":[$plot,$plot]}"""),
    )
  }

  /** And a plot that layers keeps its scales where its plot stands. */
  @Test
  fun `a plot that layers keeps its place`() {
    assertEquals(
      "concat_0_x,concat_0_y,concat_0_color,concat_1_x,concat_1_y,concat_1_color",
      scales(
        """{$rows,"resolve":{"scale":{"color":"independent"}},
           "hconcat":[{"layer":[$plot,$plot]},$plot]}"""
      ),
    )
  }
}
