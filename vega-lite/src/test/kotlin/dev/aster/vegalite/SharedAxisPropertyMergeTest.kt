package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An axis property one layer **states** settles it for the shared axis, whichever layer states it.
 *
 * `mergeAxisComponent` folds a shared axis property by property with `mergeValuesWithExplicit`, and
 * an explicit value beats a derived one. Filling only the gaps — taking whatever the first layer
 * said and passing over the rest — is right for two *derived* values and wrong the moment one of
 * them was asked for.
 *
 * A layer writing `"axis": {"grid": false}` lost to an earlier layer that never mentioned
 * gridlines: a quantitative position has them by default, so the earlier layer's **silence** became
 * a decision and the chart kept gridlines the specification had turned off.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class SharedAxisPropertyMergeTest {

  private fun axes(first: String, second: String): List<String> {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2}]},
               "layer":[{"mark":"point","encoding":{"x":$first,
                          "y":{"field":"b","type":"quantitative"}}},
                        {"mark":"point","encoding":{"x":$second,
                          "y":{"field":"b","type":"quantitative"}}}]}
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
      .map { axis ->
        val scale = (axis.fields["scale"] as VegaValue.Str).value
        val grid = (axis.fields["grid"] as? VegaValue.Bool)?.value == true
        val title = (axis.fields["title"] as? VegaValue.Str)?.value
        "$scale${if (grid) "/grid" else ""}${title?.let { ":$it" } ?: ""}"
      }
  }

  private val bare = """{"field":"a","type":"quantitative"}"""
  private val noGrid = """{"field":"a","type":"quantitative","axis":{"grid":false}}"""

  /** The reported shape: the second layer turns the gridlines off. */
  @Test
  fun `a stated grid false in the second layer wins over a derived true`() {
    assertEquals(listOf("y/grid", "x:a", "y:b"), axes(bare, noGrid))
  }

  /** And in the first layer, which worked before by accident of ordering. */
  @Test
  fun `a stated grid false in the first layer wins too`() {
    assertEquals(listOf("y/grid", "x:a", "y:b"), axes(noGrid, bare))
  }

  /** Both layers agreeing is the same answer by an easier route. */
  @Test
  fun `two layers that both turn the gridlines off keep them off`() {
    assertEquals(listOf("y/grid", "x:a", "y:b"), axes(noGrid, noGrid))
  }

  /**
   * Between two layers that both state the property, the **first** wins — `mergeValuesWithExplicit`
   * falls to its tie-breaker there, and the tie-breaker keeps the value it already had. This is
   * what makes recording the first layer's statement matter rather than only honouring the
   * second's.
   */
  @Test
  fun `between two stated values the first layer wins`() {
    val gridOn = """{"field":"a","type":"quantitative","axis":{"grid":true}}"""
    assertEquals(
      listOf("y/grid", "x:a", "y:b"),
      axes(noGrid, gridOn),
      "off first, so off",
    )
    assertEquals(
      listOf("x/grid", "y/grid", "x:a", "y:b"),
      axes(gridOn, noGrid),
      "on first, so on",
    )
  }

  /** A stated property that is not about gridlines leaves them alone. */
  @Test
  fun `a stated title does not disturb the gridlines`() {
    assertEquals(
      listOf("x/grid", "y/grid", "x:T", "y:b"),
      axes(bare, """{"field":"a","type":"quantitative","axis":{"title":"T"}}"""),
    )
  }

  /** Any stated property reaches the merged axis, not only the ones with defaults. */
  @Test
  fun `a stated tick count reaches the merged axis`() {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2}]},
               "layer":[{"mark":"point","encoding":{"x":$bare,
                          "y":{"field":"b","type":"quantitative"}}},
                        {"mark":"point","encoding":{
                          "x":{"field":"a","type":"quantitative","axis":{"tickCount":3}},
                          "y":{"field":"b","type":"quantitative"}}}]}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val x =
      (compiled.fields["axes"] as VegaValue.Arr)
        .values
        .mapNotNull { it as? VegaValue.Obj }
        .first {
          (it.fields["scale"] as? VegaValue.Str)?.value == "x" &&
            (it.fields["grid"] as? VegaValue.Bool)?.value != true
        }
    assertEquals(VegaValue.Num(3.0), x.fields["tickCount"])
  }

  /** With neither layer stating anything, both axes keep their derived gridlines. */
  @Test
  fun `two silent layers keep their derived gridlines`() {
    assertEquals(listOf("x/grid", "y/grid", "x:a", "y:b"), axes(bare, bare))
  }
}
