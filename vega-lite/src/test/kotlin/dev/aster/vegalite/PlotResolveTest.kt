package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `resolve` written on a **plot** of a concatenation speaks about the layers inside that plot.
 *
 * Every model in upstream's hierarchy carries a `resolve` of its own and each speaks about its own
 * children: the chart's is about the concatenation's plots, and a plot's is about the layers within
 * it. The innermost level to ask for independence is the one that settles the name, because it
 * divides what the level above it had already divided — `concat_0_layer_0_y` beside
 * `concat_0_layer_1_y` rather than one `concat_0_y`.
 *
 * This compiler read the chart's `resolve` alone, so a plot that measures its two lines apart
 * shared one scale between them: one axis where the specification had asked for two, and both
 * series drawn against an extent that is neither's.
 *
 * Two levels bound the rule. A plot that **grids** its cell has the cell between it and its layers,
 * and a `resolve` there speaks about the cells: the layers inside one are a single model to the
 * grid, and two scales there would be two axes over the same picture. And a plot that is a single
 * view has no children to divide at all, so a `resolve` written over it resolves nothing — such a
 * chart's scales are still called `x` and `y`.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class PlotResolveTest {

  /** Every scale the chart ends up with, the ones inside a group marked as such. */
  private fun scales(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun named(marks: VegaValue?): List<String> =
      (marks as? VegaValue.Arr)?.values.orEmpty().flatMap { mark ->
        mark as VegaValue.Obj
        (mark.fields["scales"] as? VegaValue.Arr)?.values.orEmpty().map {
          "group:${(it as VegaValue.Obj).string("name")}"
        } + named(mark.fields["marks"])
      }
    val top =
      (compiled.fields["scales"] as? VegaValue.Arr)?.values.orEmpty().map {
        (it as VegaValue.Obj).string("name").orEmpty()
      }
    return (top + named(compiled.fields["marks"])).joinToString(",")
  }

  private val rows = """"data":{"values":[{"a":1,"b":2,"c":3,"g":"x"}]}"""

  private fun line(y: String) =
    """{"mark":"line","encoding":{"x":{"field":"a","type":"quantitative"},
         "y":{"field":"$y","type":"quantitative"}}}"""

  /** The reported shape: a plot of a concatenation measuring its two lines apart. */
  @Test
  fun `a plot's own resolve divides its layers`() {
    assertEquals(
      "concat_0_x,concat_0_layer_0_y,concat_0_layer_1_y",
      scales(
        """{$rows,"hconcat":[{"resolve":{"scale":{"y":"independent"}},
           "layer":[${line("b")},${line("c")}]}]}"""
      ),
    )
  }

  /** Without it the plot's layers share one scale, which is every other concatenation. */
  @Test
  fun `a plot with no resolve of its own shares one scale`() {
    assertEquals(
      "concat_0_x,concat_0_y",
      scales("""{$rows,"hconcat":[{"layer":[${line("b")},${line("c")}]}]}"""),
    )
  }

  /** A channel that is **not** already divided per plot is divided the same way. */
  @Test
  fun `a plot's own resolve divides a colour scale too`() {
    val coloured = { y: String ->
      """{"mark":"line","encoding":{"x":{"field":"a","type":"quantitative"},
           "y":{"field":"$y","type":"quantitative"},
           "color":{"field":"g","type":"nominal"}}}"""
    }
    assertEquals(
      "concat_0_x,concat_0_y,concat_0_layer_0_color,concat_0_layer_1_color",
      scales(
        """{$rows,"hconcat":[{"resolve":{"scale":{"color":"independent"}},
           "layer":[${coloured("b")},${coloured("c")}]}]}"""
      ),
    )
  }

  /**
   * A plot that **grids** its cell has the cell between it and its layers: the resolve is about the
   * cells, so the cell has one scale and the layers inside it share it.
   */
  @Test
  fun `a grid's resolve divides its cells and not their layers`() {
    assertEquals(
      "x,group:child_y",
      scales(
        """{$rows,"resolve":{"scale":{"y":"independent"}},
           "facet":{"column":{"field":"g","type":"nominal"}},
           "spec":{"layer":[${line("b")},${line("c")}]}}"""
      ),
    )
  }

  /** A single view has no children to divide, so a `resolve` over it resolves nothing. */
  @Test
  fun `a resolve over a single view names nothing`() {
    assertEquals(
      "x,y",
      scales(
        """{$rows,"resolve":{"scale":{"y":"independent","x":"independent"}},
           "mark":"line","encoding":{"x":{"field":"a","type":"quantitative"},
                                     "y":{"field":"b","type":"quantitative"}}}"""
      ),
    )
  }

  /** A layer at the top of a chart is divided by the chart's own resolve, as it always was. */
  @Test
  fun `a chart's resolve divides its own layers`() {
    assertEquals(
      "x,layer_0_y,layer_1_y",
      scales(
        """{$rows,"resolve":{"scale":{"y":"independent"}},
           "layer":[${line("b")},${line("c")}]}"""
      ),
    )
  }
}
