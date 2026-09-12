package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A grid's own bucketing of an instant stands **above** its cells' transforms.
 *
 * ```js
 * head = TimeUnitNode.makeFromEncoding(head, model) ?? head;
 * ```
 *
 * `parseData` runs per model, top-down: a facet model writes its own steps and then its child
 * writes its. The column a grid is cut by is one of the grid's, so it is computed before any step
 * the cell asked for — where a chart written with the `column` shorthand has no cell of its own to
 * ask, and its transforms are the grid's, standing above the bucketing instead.
 *
 * This compiler wrote the two models' bucketings together at the foot of the chain, so a trellis of
 * years listed its `timeunit` after a `calculate` the cell asked for. One specification in the wild
 * corpus is such a trellis, its cells ordering a stack by a stated colour domain.
 *
 * A grid written the **long** way — `"facet": {...}, "spec": {...}` — still lists a transform of
 * the cell's above the bucketing, because this compiler credits `spec.transform` to the grid rather
 * than to the cell that carries it. That is a gap of its own, and not this rule's; it is why the
 * shapes below reach the cell through the shorthand instead.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class FacetTimeUnitOrderTest {

  /** The steps the main table runs, each formula and bucketing named by what it writes. */
  private fun steps(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .map { it as VegaValue.Obj }
      .first { it.string("name") == "data_0" }
      .let { (it.fields["transform"] as VegaValue.Arr).values }
      .joinToString(",") {
        it as VegaValue.Obj
        when (val type = it.string("type")) {
          "formula" -> "formula(${it.string("as")})"
          "timeunit" ->
            "timeunit(${(it.fields["as"] as VegaValue.Arr).values[0].let { as0 ->
            (as0 as VegaValue.Str).value
          }})"
          else -> type.orEmpty()
        }
      }
  }

  private val rows = """"data":{"values":[{"d":"2020-01-01","k":"a","v":1}]}"""
  private val bars =
    """"mark":"bar","encoding":{"x":{"field":"k","type":"nominal"},
                                "y":{"field":"v","type":"quantitative"}}"""

  /**
   * The reported shape: a trellis of years whose cells order a stack by a stated colour domain.
   *
   * `alignStackOrderWithColorDomain` pushes that `calculate` onto the **unit** model, so it is a
   * step of the cell's and stands below the grid's bucketing.
   */
  @Test
  fun `a grid buckets before its cell calculates`() {
    assertEquals(
      "formula(d),timeunit(year_d),formula(_k_sort_index),stack,filter",
      steps(
        """{$rows,"mark":"bar",
           "encoding":{"column":{"field":"d","type":"temporal","timeUnit":"year"},
                       "x":{"field":"k","type":"nominal"},
                       "y":{"field":"v","type":"quantitative"},
                       "color":{"field":"k","type":"nominal","scale":{"domain":["a","b"]}}}}"""
      ),
    )
  }

  /** With nothing for the cell to do, the bucketing is the whole of the grid's pass. */
  @Test
  fun `a grid with no cell transform buckets alone`() {
    assertEquals(
      "formula(d),timeunit(year_d),stack,filter",
      steps(
        """{$rows,"facet":{"column":{"field":"d","type":"temporal","timeUnit":"year"}},
           "spec":{$bars}}"""
      ),
    )
  }

  /**
   * A chart written with the `column` **shorthand** has no cell to ask: its transforms are the
   * grid's own, and those stand above the bucketing rather than below it.
   */
  @Test
  fun `a chart's own transform stands above the bucketing`() {
    assertEquals(
      "formula(d),formula(one),timeunit(year_d),stack,filter",
      steps(
        """{$rows,"transform":[{"calculate":"1","as":"one"}],"mark":"bar",
           "encoding":{"column":{"field":"d","type":"temporal","timeUnit":"year"},
                       "x":{"field":"k","type":"nominal"},
                       "y":{"field":"v","type":"quantitative"}}}"""
      ),
    )
  }

  /** Where both models bucket, the grid's comes first — the two passes in order. */
  @Test
  fun `the grid's bucketing precedes the cell's`() {
    assertEquals(
      "formula(d),timeunit(year_d),timeunit(month_d),stack,filter",
      steps(
        """{$rows,"mark":"bar",
           "encoding":{"column":{"field":"d","type":"temporal","timeUnit":"year"},
                       "x":{"field":"d","type":"temporal","timeUnit":"month"},
                       "y":{"field":"v","type":"quantitative"}}}"""
      ),
    )
  }

  /** With no grid at all, a transform still stands above the bucketing it does not feed. */
  @Test
  fun `a plain chart buckets after its transforms`() {
    assertEquals(
      "formula(d),formula(one),timeunit(year_d),stack,filter",
      steps(
        """{$rows,"transform":[{"calculate":"1","as":"one"}],"mark":"bar",
           "encoding":{"x":{"field":"d","type":"temporal","timeUnit":"year"},
                       "y":{"field":"v","type":"quantitative"}}}"""
      ),
    )
  }
}
