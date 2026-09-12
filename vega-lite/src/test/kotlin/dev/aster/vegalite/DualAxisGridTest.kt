package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Only a **derived** grid comes off the second of two independent axes.
 *
 * ```js
 * // Show gridlines for first axis only for dual-axis chart
 * if (resolve.axis[channel] === 'independent' && axes[channel] && axes[channel].length > 1) {
 *   for (const [index, axisCmpt] of (axes[channel] || []).entries()) {
 *     if (index > 0 && !!axisCmpt.get('grid') && !axisCmpt.explicit.grid) {
 *       axisCmpt.implicit.grid = false;
 *     }
 *   }
 * }
 * ```
 *
 * Two sets of gridlines across one plot measure different things and say neither, so upstream keeps
 * the first — but only where the second's were nobody's idea. `!axisCmpt.explicit.grid` is the
 * whole of it: a layer that writes `"axis": {"grid": true}` has *asked* for gridlines, and gets
 * them however busy the result reads. `isExplicit` ends in `value === axis[property]`, so a
 * `config.axis.grid` that happens to produce the same answer is not asking.
 *
 * This took them off regardless, so a dual-axis chart whose three layers each asked for gridlines
 * was drawn with one layer's.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class DualAxisGridTest {

  private fun gridScales(vararg layers: String): List<String> {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":3}]},
               "resolve":{"scale":{"y":"independent"}},
               "layer":[${layers.joinToString(",")}]}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["axes"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .filter { (it.fields["grid"] as? VegaValue.Bool)?.value == true }
      .mapNotNull { (it.fields["scale"] as? VegaValue.Str)?.value }
  }

  private fun layer(field: String, axis: String? = null) =
    """{"mark":"line","encoding":{"x":{"field":"a","type":"quantitative"},
       "y":{"field":"$field","type":"quantitative"${axis?.let { ""","axis":$it""" } ?: ""}}}}"""

  /** The reported shape: both layers ask for gridlines and both get them. */
  @Test
  fun `two axes that each ask for gridlines keep them`() {
    assertEquals(
      listOf("x", "layer_0_y", "layer_1_y"),
      gridScales(layer("b", """{"grid":true}"""), layer("c", """{"grid":true}""")),
    )
  }

  /** Neither asking is the case the rule exists for: the second's are taken away. */
  @Test
  fun `two axes that merely have gridlines keep one set`() {
    assertEquals(listOf("x", "layer_0_y"), gridScales(layer("b"), layer("c")))
  }

  /** It is the **second** axis's own statement that decides, not the first's. */
  @Test
  fun `the second axis asking is what keeps its gridlines`() {
    assertEquals(
      listOf("x", "layer_0_y", "layer_1_y"),
      gridScales(layer("b"), layer("c", """{"grid":true}""")),
      "the second asked",
    )
    assertEquals(
      listOf("x", "layer_0_y"),
      gridScales(layer("b", """{"grid":true}"""), layer("c")),
      "the first asking says nothing about the second",
    )
  }

  /** A second axis that asks for *no* gridlines has none to take away. */
  @Test
  fun `an axis that turns its gridlines off keeps them off`() {
    assertEquals(listOf("x", "layer_0_y"), gridScales(layer("b"), layer("c", """{"grid":false}""")))
  }

  /** Three of them, so the rule is not about the second alone. */
  @Test
  fun `three axes that each ask keep all three`() {
    assertEquals(
      listOf("x", "layer_0_y", "layer_1_y", "layer_2_y"),
      gridScales(
        layer("b", """{"grid":true}"""),
        layer("c", """{"grid":true}"""),
        layer("a", """{"grid":true}"""),
      ),
    )
  }

  /** A theme turning gridlines on is not the specification asking for them. */
  @Test
  fun `a grid from the theme is still taken off the second axis`() {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":3}]},"config":{"axis":{"grid":true}},
               "resolve":{"scale":{"y":"independent"}},
               "layer":[${layer("b")},${layer("c")}]}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val grids =
      (compiled.fields["axes"] as VegaValue.Arr)
        .values
        .mapNotNull { it as? VegaValue.Obj }
        .filter { (it.fields["grid"] as? VegaValue.Bool)?.value == true }
        .mapNotNull { (it.fields["scale"] as? VegaValue.Str)?.value }
    assertEquals(listOf("x", "layer_0_y"), grids)
  }
}
