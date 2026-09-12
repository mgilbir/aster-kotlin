package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A brush is drawn around the marks of the model that **assembles** it.
 *
 * ```js
 * public assembleMarks() {
 *   let marks = this.component.mark ?? [];
 *   if (!this.parent || !isLayerModel(this.parent)) {
 *     marks = assembleUnitSelectionMarks(this, marks);
 *   }
 * ```
 * ```js
 * export function assembleLayerSelectionMarks(model: LayerModel, marks: any[]): any[] {
 *   for (const child of model.children) {
 *     if (isUnitModel(child)) {
 *       marks = assembleUnitSelectionMarks(child, marks);
 *     }
 *   }
 * ```
 *
 * A unit inside a layer does not wrap its own marks; its layer does, around everything that layer
 * assembled — and only for the children that are *units*, a layer inside a layer having wrapped its
 * own already. So a brush declared in the inner layer of `layer[layer[a, b], c]` is drawn around
 * `a` and `b` and **under** `c`.
 *
 * This compiler wrapped the whole plot whatever declared the brush, so a layer drawn over a brushed
 * one came out beneath the brush instead of above it: a rule that should cross the highlighted
 * region was covered by it, and the region's own outline was drawn over marks upstream leaves
 * clear. One specification in the wild corpus layers a rule over a brushed pair that way, in five
 * plots.
 *
 * Each brush **wraps** the range rather than joining it, so a second selection's background lands
 * outside the first's and its outline outside that one's.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class BrushScopeTest {

  /** Every drawing mark of the chart, in the order it is drawn. */
  private fun marks(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun walk(marks: List<VegaValue>): List<String> = marks.flatMap {
      it as VegaValue.Obj
      if (it.string("type") == "group")
        walk((it.fields["marks"] as? VegaValue.Arr)?.values.orEmpty())
      else listOf(it.string("name").orEmpty())
    }
    return walk((compiled.fields["marks"] as VegaValue.Arr).values).joinToString(" ")
  }

  private val at =
    """"encoding":{"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"quantitative"}}"""

  private fun brush(name: String) = """"params":[{"name":"$name","select":"interval"}]"""

  private fun mark(type: String, declaring: String? = null) =
    """{"mark":"$type",$at${declaring?.let { ",${brush(it)}" }.orEmpty()}}"""

  /** The reported shape: the brush belongs to the inner layer, so the rule is drawn over it. */
  @Test
  fun `a brush in an inner layer is drawn under the layer beside it`() {
    assertEquals(
      "brush_brush_bg layer_0_layer_0_marks layer_0_layer_1_marks brush_brush layer_1_marks",
      marks(
        """{"layer":[{"layer":[${mark("point", "brush")},${mark("line")}]},${mark("rule")}]}"""
      ),
    )
  }

  /** Declared in the outer layer's own member, it wraps everything that layer assembled. */
  @Test
  fun `a brush in a layer's own member wraps the whole layer`() {
    assertEquals(
      "brush_brush_bg layer_0_layer_0_marks layer_0_layer_1_marks layer_1_marks brush_brush",
      marks(
        """{"layer":[{"layer":[${mark("point")},${mark("line")}]},${mark("rule", "brush")}]}"""
      ),
    )
  }

  /** A flat layer has one model to wrap, which is what already worked. */
  @Test
  fun `a brush in a flat layer wraps that layer`() {
    assertEquals(
      "brush_brush_bg layer_0_marks layer_1_marks brush_brush",
      marks("""{"layer":[${mark("point", "brush")},${mark("line")}]}"""),
    )
  }

  /** Two brushes over one layer nest, the later one outside the earlier. */
  @Test
  fun `two brushes over one layer nest in the order they were declared`() {
    assertEquals(
      "two_brush_bg one_brush_bg layer_0_marks layer_1_marks one_brush two_brush",
      marks("""{"layer":[${mark("point", "one")},${mark("line", "two")}]}"""),
    )
  }

  /** One in each: the inner wraps its own layer and the outer wraps that wrapping. */
  @Test
  fun `a brush in each layer nests around the other`() {
    assertEquals(
      "outer_brush_bg inner_brush_bg layer_0_layer_0_marks layer_0_layer_1_marks inner_brush " +
        "layer_1_marks outer_brush",
      marks(
        """{"layer":[{"layer":[${mark("point", "inner")},${mark("line")}]},
             ${mark("rule", "outer")}]}"""
      ),
    )
  }

  /** Two inner layers side by side: the brush wraps its own and leaves its sibling clear. */
  @Test
  fun `a brush wraps its own inner layer and not its sibling`() {
    assertEquals(
      "layer_0_layer_0_marks layer_0_layer_1_marks brush_brush_bg layer_1_layer_0_marks " +
        "layer_1_layer_1_marks brush_brush",
      marks(
        """{"layer":[{"layer":[${mark("point")},${mark("line")}]},
             {"layer":[${mark("rule", "brush")},${mark("tick")}]}]}"""
      ),
    )
  }

  /** A chart that is one view wraps that view, there being no layer above it. */
  @Test
  fun `a brush on a single view wraps it`() {
    assertEquals("brush_brush_bg marks brush_brush", marks(mark("point", "brush")))
  }

  /** And a plot of a concatenation wraps its own plot, not the chart. */
  @Test
  fun `a brush in one plot of a concatenation wraps that plot`() {
    assertEquals(
      "brush_brush_bg concat_0_marks brush_brush concat_1_marks",
      marks("""{"hconcat":[${mark("point", "brush")},${mark("line")}]}"""),
    )
  }

  /**
   * A brush declared **above** the chart is owned by no view and pushed into every unit below it,
   * so it is wrapped once per unit — and a layer of two draws one declaration as two brushes, each
   * hidden unless the store's row came from its own unit. It is what upstream emits.
   */
  @Test
  fun `a brush above a layer is drawn once for each member`() {
    assertEquals(
      "brush_brush_bg brush_brush_bg layer_0_marks layer_1_marks brush_brush brush_brush",
      marks("""{${brush("brush")},"layer":[${mark("point")},${mark("line")}]}"""),
    )
  }

  /** Each wrap is around its own unit's model, so a nested layer nests three deep. */
  @Test
  fun `a brush above a nested layer wraps each model in turn`() {
    assertEquals(
      "brush_brush_bg brush_brush_bg brush_brush_bg layer_0_layer_0_marks layer_0_layer_1_marks " +
        "brush_brush brush_brush layer_1_marks brush_brush",
      marks(
        """{${brush("brush")},"layer":[{"layer":[${mark("point")},${mark("line")}]},
             ${mark("rule")}]}"""
      ),
    )
  }

  /** Each wrap records the unit it is drawn for, which is what hides the others. */
  @Test
  fun `each wrap is hidden unless the store's row came from its own unit`() {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson("""{${brush("brush")},"layer":[${mark("point")},${mark("line")}]}""")
            .toJson()
        ) {
          "no output"
        }
      ) as VegaValue.Obj
    val tests =
      (compiled.fields["marks"] as VegaValue.Arr).values.mapNotNull {
        it as VegaValue.Obj
        ((it.obj("encode")?.obj("update")?.fields?.get("x") as? VegaValue.Arr)
            ?.values
            ?.firstOrNull() as? VegaValue.Obj)
          ?.string("test")
          ?.substringAfterLast("=== ")
      }
    assertEquals(
      listOf("\"layer_1\"", "\"layer_0\"", "\"layer_0\"", "\"layer_1\""),
      tests,
    )
  }

  /** A plot of a concatenation holds one unit, so a brush above the chart wraps it once. */
  @Test
  fun `a brush above a concatenation wraps each plot once`() {
    assertEquals(
      "brush_brush_bg concat_0_marks brush_brush brush_brush_bg concat_1_marks brush_brush",
      marks("""{${brush("brush")},"hconcat":[${mark("point")},${mark("line")}]}"""),
    )
  }
}
