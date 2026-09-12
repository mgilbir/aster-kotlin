package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A mark is clipped because the **scale** under it is driven by a selection.
 *
 * ```js
 * export function scaleClip(model: UnitModel) {
 *   const xScale = model.getScaleComponent('x');
 *   const yScale = model.getScaleComponent('y');
 *   return xScale?.get('selectionExtent') || yScale?.get('selectionExtent') ? true : undefined;
 * }
 * ```
 *
 * A pan that moves the domain past the data would otherwise draw the rows that fell outside the
 * plot. The question is asked of the scale, and of the scale that was **actually driven**: binding
 * a selection to the scales moves only what can move, and a categorical position has no halfway
 * between two of its values, so upstream warns and passes over that channel. This engine asked the
 * *selection* instead, so a chart whose brush was refused was clipped for a pan that cannot happen.
 * One specification in the wild corpus is that chart, a heatmap of two categorical positions with a
 * `"bind": "scales"` interval over it.
 *
 * `getScaleComponent` walks **up** the model tree, so the driven scale is the plot's rather than
 * the view's: a member that encodes no position of its own is measured by its layer's, and a text
 * label beside a panned scatter is clipped along with it.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ScaleClipTest {

  private fun compiled(spec: String) =
    VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
      as VegaValue.Obj

  /** Each mark as `name=clip`, nested groups walked into. */
  private fun clips(spec: String): List<String> {
    fun walk(marks: List<VegaValue>): List<String> = marks.flatMap { mark ->
      mark as VegaValue.Obj
      val nested = (mark.fields["marks"] as? VegaValue.Arr)?.values
      if (nested != null) walk(nested)
      else
        listOf(
          "${mark.string("name")}=" +
            ((mark.fields["clip"] as? VegaValue.Bool)?.value?.toString() ?: "-")
        )
    }
    return walk((compiled(spec).fields["marks"] as? VegaValue.Arr)?.values.orEmpty())
  }

  /** The scales that carry a `domainRaw`, which is what a driven scale carries. */
  private fun driven(spec: String): List<String?> =
    (compiled(spec).fields["scales"] as? VegaValue.Arr)
      ?.values
      .orEmpty()
      .map { it as VegaValue.Obj }
      .filter { it.has("domainRaw") }
      .map { it.string("name") }

  private val data = """"data":{"values":[{"c":"x","b":1,"n":2}]}"""
  private val grid = """"params":[{"name":"grid","select":{"type":"interval"},"bind":"scales"}],"""

  private fun bound(x: String, y: String) =
    """{$data,$grid"mark":"point","encoding":{"x":{"field":"$x","type":"${typeOf(x)}"},
       "y":{"field":"$y","type":"${typeOf(y)}"}}}"""

  private fun typeOf(field: String) = if (field == "c") "nominal" else "quantitative"

  /** The reported shape: a brush bound to two categorical scales moves nothing. */
  @Test
  fun `a chart whose bound scales cannot be panned is not clipped`() {
    val spec = bound("c", "c")
    assertEquals(listOf("marks=-"), clips(spec))
    assertEquals(emptyList<String>(), driven(spec))
  }

  /** Where they can be, both are driven and the marks are clipped. */
  @Test
  fun `a chart whose bound scales can be panned is clipped`() {
    val spec = bound("b", "n")
    assertEquals(listOf("marks=true"), clips(spec))
    assertEquals(listOf("x", "y"), driven(spec))
  }

  /** One of each: the continuous half is driven, and one driven scale is enough to clip. */
  @Test
  fun `a chart with one pannable position is clipped by it`() {
    val spec = bound("b", "c")
    assertEquals(listOf("marks=true"), clips(spec))
    assertEquals(listOf("x"), driven(spec))
  }

  /** A member with no position of its own is measured by its layer's, and clipped with it. */
  @Test
  fun `a layer member without a position is clipped with its layer`() {
    val spec =
      """{$data,"layer":[
         {$grid"mark":"point","encoding":{"x":{"field":"b","type":"quantitative"},
                                          "y":{"field":"n","type":"quantitative"}}},
         {"mark":"text","encoding":{"text":{"field":"c","type":"nominal"}}}]}"""
    assertEquals(listOf("layer_0_marks=true", "layer_1_marks=true"), clips(spec))
  }

  /**
   * A scale whose domain **names** a selection is driven too, and only the plot that named it is
   * clipped: the question is asked of each plot's own scales.
   */
  @Test
  fun `only the plot whose scale names a selection is clipped`() {
    val spec =
      """{$data,"vconcat":[
         {"params":[{"name":"br","select":{"type":"interval","encodings":["x"]}}],
          "mark":"point","encoding":{"x":{"field":"b","type":"quantitative"},
                                     "y":{"field":"n","type":"quantitative"}}},
         {"mark":"point","encoding":{"x":{"field":"b","type":"quantitative",
                                          "scale":{"domain":{"param":"br"}}},
                                     "y":{"field":"n","type":"quantitative"}}}]}"""
    assertEquals(
      listOf("br_brush_bg=true", "concat_0_marks=-", "br_brush=true", "concat_1_marks=true"),
      clips(spec),
    )
    assertEquals(listOf("concat_1_x"), driven(spec))
  }
}
