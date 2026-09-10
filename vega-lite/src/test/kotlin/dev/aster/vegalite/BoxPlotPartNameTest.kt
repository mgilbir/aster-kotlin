package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A composite mark's parts are named by their **position among the parts actually drawn**.
 *
 * Upstream builds the layer array from the enabled parts and every name follows the array — the
 * normalizer has no notion of a part's "own" slot. So a box plot with its box switched off has its
 * median at `layer_1_layer_0`, taking the box's place rather than keeping its own.
 *
 * This engine wrote the names out by hand, so a part switched off left a hole: the median stayed
 * `layer_1_layer_1` and every dataset, signal and mark name derived from it was one index too high.
 * Two specifications in the wild corpus switch a part off that way.
 *
 * The grid of groups is unaffected: a box plot is a layer of two layers whatever is drawn in them —
 * the outliers and whiskers first, the box and median second — so the *group* indices stay 0 and 1
 * even where a group comes out empty. `outliers: false` is the one exception, and it is upstream's:
 * with no outlier layer the whiskers **are** the first group, so every name below them loses a
 * level.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class BoxPlotPartNameTest {

  private fun markNames(mark: String): List<String> {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2}]},"mark":$mark,
               "encoding":{"x":{"field":"a","type":"quantitative"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["marks"] as VegaValue.Arr).values.mapNotNull {
      (it as? VegaValue.Obj)?.string("name")
    }
  }

  /** Every part drawn: two groups, the second holding the box and the median. */
  @Test
  fun `a plain box plot names its five parts in order`() {
    assertEquals(
      listOf(
        "layer_0_layer_0_marks",
        "layer_0_layer_1_layer_0_marks",
        "layer_0_layer_1_layer_1_marks",
        "layer_1_layer_0_marks",
        "layer_1_layer_1_marks",
      ),
      markNames(""""boxplot""""),
    )
  }

  /** The reported shape: the box switched off, and the median taking its place. */
  @Test
  fun `a box plot with no box names its median first in the group`() {
    assertEquals(
      listOf(
        "layer_0_layer_0_marks",
        "layer_0_layer_1_layer_0_marks",
        "layer_0_layer_1_layer_1_marks",
        "layer_1_layer_0_marks",
      ),
      markNames("""{"type":"boxplot","box":false,"extent":0.5}"""),
    )
  }

  /** With the **median** off instead, the box is first — which it was already. */
  @Test
  fun `a box plot with no median is unchanged`() {
    assertEquals(
      listOf(
        "layer_0_layer_0_marks",
        "layer_0_layer_1_layer_0_marks",
        "layer_0_layer_1_layer_1_marks",
        "layer_1_layer_0_marks",
      ),
      markNames("""{"type":"boxplot","median":false}"""),
    )
  }

  /** Both off, and the second group is empty rather than renumbered away. */
  @Test
  fun `a box plot with neither box nor median keeps its first group`() {
    assertEquals(
      listOf(
        "layer_0_layer_0_marks",
        "layer_0_layer_1_layer_0_marks",
        "layer_0_layer_1_layer_1_marks",
      ),
      markNames("""{"type":"boxplot","box":false,"median":false}"""),
    )
  }

  /** The whiskers' own group compacts the same way: the ticks are off by default. */
  @Test
  fun `the whiskers are numbered among themselves`() {
    assertEquals(
      listOf(
        "layer_0_layer_0_marks",
        "layer_0_layer_1_layer_0_marks",
        "layer_0_layer_1_layer_1_marks",
        "layer_0_layer_1_layer_2_marks",
        "layer_0_layer_1_layer_3_marks",
        "layer_1_layer_0_marks",
        "layer_1_layer_1_marks",
      ),
      markNames("""{"type":"boxplot","ticks":true}"""),
      "with the ticks on there are four whisker parts",
    )
    assertEquals(
      listOf("layer_0_layer_0_marks", "layer_1_layer_0_marks", "layer_1_layer_1_marks"),
      markNames("""{"type":"boxplot","rule":false}"""),
      "and with the rules off the group is empty, the outliers keeping their place",
    )
  }

  /** No outliers, and the whiskers *are* the first group — upstream's own asymmetry. */
  @Test
  fun `no outliers takes a level off every name below`() {
    assertEquals(
      listOf(
        "layer_0_layer_0_marks",
        "layer_0_layer_1_marks",
        "layer_1_layer_0_marks",
        "layer_1_layer_1_marks",
      ),
      markNames("""{"type":"boxplot","outliers":false}"""),
    )
    assertEquals(
      listOf("layer_0_layer_0_marks", "layer_0_layer_1_marks", "layer_1_layer_0_marks"),
      markNames("""{"type":"boxplot","box":false,"outliers":false}"""),
      "and the median still takes the box's place inside its own group",
    )
  }
}
