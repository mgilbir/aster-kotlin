package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Axes come out **gridlines first and horizontals before verticals**, in both passes.
 *
 * ```js
 * export function assembleAxes(axisComponents, config) {
 *   const {x = [], y = []} = axisComponents;
 *   return [
 *     ...x.map((a) => assembleAxis(a, 'grid', config)),
 *     ...y.map((a) => assembleAxis(a, 'grid', config)),
 *     ...x.map((a) => assembleAxis(a, 'main', config)),
 *     ...y.map((a) => assembleAxis(a, 'main', config)),
 *   ].filter((a) => a);
 * }
 * ```
 *
 * `axisComponents` is a map keyed by channel and upstream reads the two keys in turn, so the order
 * an axis was *discovered* in never reaches the output. This compiler wrote them in that discovery
 * order, so a chart whose first layer draws only a baseline listed that layer's `y` before the `x`
 * the layer above it brought, and the two came out the other way round. Four specifications in the
 * wild corpus are layered that way.
 *
 * The order is what Vega paints in: the gridlines of both channels stand behind every axis, and an
 * axis behind the one after it.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class AxisOrderTest {

  /** Each axis, in the order it is written, gridlines marked. */
  private fun axes(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["axes"] as VegaValue.Arr).values.joinToString(",") {
      it as VegaValue.Obj
      it.string("scale").orEmpty() + if (it.fields["grid"] == VegaValue.Bool(true)) ":grid" else ""
    }
  }

  private val rows = """"data":{"values":[{"a":1,"b":2}]}"""
  private val xy =
    """"encoding":{"x":{"field":"a","type":"quantitative"},
                                  "y":{"field":"b","type":"quantitative"}}"""

  /** The reported shape: a baseline that brings a `y` of its own before any `x` is seen. */
  @Test
  fun `a baseline's axis does not come before the plot's own`() {
    assertEquals(
      "x:grid,x,y",
      axes(
        """{$rows,"layer":[{"mark":"rule","encoding":{"y":{"datum":0}}},
             {"mark":"line",$xy}]}"""
      ),
    )
  }

  /** A plain chart, where both channels have gridlines: both grids, then both axes. */
  @Test
  fun `both gridlines stand before both axes`() {
    assertEquals("x:grid,y:grid,x,y", axes("""{$rows,"mark":"line",$xy}"""))
  }

  /** Two layers measuring the same two channels are one axis each, in the same order. */
  @Test
  fun `two layers over the same channels keep the order`() {
    assertEquals(
      "x:grid,y:grid,x,y",
      axes("""{$rows,"layer":[{"mark":"line",$xy},{"mark":"point",$xy}]}"""),
    )
  }

  /** And a chart with only one position has only that one, grid first. */
  @Test
  fun `one channel is its own grid and its own axis`() {
    assertEquals(
      "y:grid,y",
      axes("""{$rows,"mark":"rule","encoding":{"y":{"field":"b","type":"quantitative"}}}"""),
    )
  }
}
