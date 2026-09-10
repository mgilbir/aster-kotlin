package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A padding the **theme** sets to nothing is no padding; one the chart sets to nothing is a zero.
 *
 * `getTopLevelProperties` spreads the two sources in order — `extractTopLevelProperties(config,
 * false)` then `extractTopLevelProperties(inputSpec, true)` — and the specification's wins. The
 * asymmetry is upstream of that: a falsy `padding` does not survive the configuration merge, while
 * the specification's is copied on a plain `!== undefined` test.
 *
 * So `config: {"padding": 0}` reaches Vega as **no** padding — leaving Vega's own default of five —
 * and `"padding": 0` on the chart reaches it as a zero. This took the theme's as written, and put a
 * `padding: 0` into five of the wild corpus's charts that upstream leaves alone.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ChartPaddingTest {

  private fun padding(top: String = "", config: String = ""): VegaValue? =
    (VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2}]},"mark":"point",$top$config
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj)
      .fields["padding"]

  /** The reported shape: a theme that asks for no padding at all. */
  @Test
  fun `a padding of zero from the theme is not written`() {
    assertNull(padding(config = """"config":{"padding":0},"""))
  }

  /** A theme that asks for some is honoured, which is the ordinary case. */
  @Test
  fun `a padding from the theme is written`() {
    assertEquals(VegaValue.Num(12.0), padding(config = """"config":{"padding":12},"""))
  }

  /** The chart's own zero is a zero: it goes out on a plain presence test, not a truthy one. */
  @Test
  fun `a padding of zero on the chart is written`() {
    assertEquals(VegaValue.Num(0.0), padding(top = """"padding":0,"""))
  }

  /** And the chart's outranks the theme's, either way round. */
  @Test
  fun `the chart's padding outranks the theme's`() {
    assertEquals(
      VegaValue.Num(3.0),
      padding(top = """"padding":3,""", config = """"config":{"padding":12},"""),
    )
    assertEquals(
      VegaValue.Num(0.0),
      padding(top = """"padding":0,""", config = """"config":{"padding":12},"""),
      "including a zero, which is a padding the chart chose",
    )
  }

  /** With neither stated, the configured default stands. */
  @Test
  fun `with nothing stated the default padding stands`() {
    assertEquals(VegaValue.Num(5.0), padding())
  }
}
