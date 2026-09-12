package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A channel whose children disagree is forced apart **at the level that found the disagreement**.
 *
 * ```js
 * if (scaleCompatible(explicitScaleType.value, childScaleType.value)) {
 *   scaleTypeWithExplicitIndex[channel] = mergeValuesWithExplicit(...);
 * } else {
 *   resolve.scale[channel] = 'independent';
 *   delete scaleTypeWithExplicitIndex[channel];
 * }
 * ```
 *
 * `parseNonUnitScaleCore` runs per model, bottom-up. A model whose children disagree marks the
 * channel independent and offers nothing upward, so the level above has nothing to merge from it
 * and leaves the names its children settled on — while the level above goes on merging the children
 * that *do* agree.
 *
 * Two layers inside one plot of a concatenation are where it tells: a colour ramp over counts
 * beside a pair of named colours cannot be one scale, the layer model says so, and each layer keeps
 * `concat_0_layer_0_color` — while the other plot's colour is still the chart's own. Asked of the
 * whole chart at once and answered with the plot, such a chart came out with one colour scale for
 * the plot: two layers measuring different things drawn from a scale that is neither, and a key
 * beside them explaining a scale nothing is drawn with.
 *
 * And a scale is **placed** where it is named. `assembleScales` writes a model's own components and
 * only then recurses, so a plot that resolves its `y` writes that before the layers below it write
 * the `x` they could not share.
 *
 * Five specifications in the wild corpus layer scales that cannot merge.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ForcedIndependenceTest {

  /** Every scale by name, in order, and which scale each mark reads. */
  private fun wiring(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val scales =
      (compiled.fields["scales"] as? VegaValue.Arr)?.values.orEmpty().joinToString(",") {
        (it as VegaValue.Obj).string("name").orEmpty()
      }
    fun walk(mark: VegaValue.Obj, path: String): List<String> =
      listOfNotNull(
        mark.obj("encode")?.obj("update")?.obj("stroke")?.string("scale")?.let { "$path:$it" }
      ) +
        (mark.fields["marks"] as? VegaValue.Arr)?.values.orEmpty().flatMapIndexed { index, child ->
          walk(child as VegaValue.Obj, "$path.$index")
        }
    val marks =
      (compiled.fields["marks"] as VegaValue.Arr)
        .values
        .flatMapIndexed { index, mark -> walk(mark as VegaValue.Obj, "$index") }
        .joinToString(",")
    return "[$scales] $marks"
  }

  private val rows = """"data":{"values":[{"a":1,"b":2,"k":"x","n":3}]}"""
  private val xy =
    """"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"quantitative"}"""
  private val ramp =
    """{"mark":"point","encoding":{$xy,"color":{"field":"n","type":"quantitative"}}}"""
  private val named = """{"mark":"point","encoding":{$xy,"color":{"field":"k","type":"nominal"}}}"""

  /** The reported shape: one plot's layers cannot share a colour; the other plot still can. */
  @Test
  fun `layers that disagree are split, and their neighbours are not`() {
    assertEquals(
      "[color,concat_0_x,concat_0_y,concat_0_layer_0_color,concat_0_layer_1_color," +
        "concat_1_x,concat_1_y] " +
        "0.0:concat_0_layer_0_color,0.1:concat_0_layer_1_color,1.0:color",
      wiring("""{$rows,"hconcat":[{"layer":[$ramp,$named]},$named]}"""),
    )
  }

  /** The same layer with no concatenation above it splits by layer, as it always did. */
  @Test
  fun `a plain layer splits by layer`() {
    assertEquals(
      "[x,y,layer_0_color,layer_1_color] 0:layer_0_color,1:layer_1_color",
      wiring("""{$rows,"layer":[$ramp,$named]}"""),
    )
  }

  /** Two **plots** that disagree split by plot, which is the shape that must not change. */
  @Test
  fun `plots that disagree split by plot`() {
    assertEquals(
      "[concat_0_x,concat_0_y,concat_0_color,concat_1_x,concat_1_y,concat_1_color] " +
        "0.0:concat_0_color,1.0:concat_1_color",
      wiring("""{$rows,"hconcat":[$ramp,$named]}"""),
    )
  }

  /** Where everything agrees there is one scale, however deeply it nests. */
  @Test
  fun `agreement leaves one scale`() {
    assertEquals(
      "[color,concat_0_x,concat_0_y,concat_1_x,concat_1_y] 0.0:color,0.1:color,1.0:color",
      wiring("""{$rows,"hconcat":[{"layer":[$named,$named]},$named]}"""),
    )
  }

  /** A level's **own** scale stands before one named for something inside it. */
  @Test
  fun `a level's own scale stands first`() {
    assertEquals(
      "[color,concat_0_x,concat_0_y,concat_1_y,concat_1_layer_0_x,concat_1_layer_1_x] 0.0:color",
      wiring(
        """{$rows,"hconcat":[$named,
           {"resolve":{"scale":{"x":"independent"}},
            "layer":[{"mark":"point","encoding":{$xy}},
                     {"mark":"point","encoding":{"x":{"field":"k","type":"nominal"},
                                                 "y":{"field":"b","type":"quantitative"}}}]}]}"""
      ),
    )
  }
}
