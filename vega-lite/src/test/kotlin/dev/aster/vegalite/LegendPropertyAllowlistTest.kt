package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A legend is read by asking it for the properties legends have.
 *
 * `parseLegendForChannel` walks `LEGEND_COMPONENT_PROPERTIES` and asks the `legend` block for each
 * entry:
 * ```js
 * for (const property of LEGEND_COMPONENT_PROPERTIES) {
 *   const value = property in legendRules ? legendRules[property](ruleParams) : (legend as any)[property];
 *   …
 * }
 * ```
 *
 * so a block holding anything else is never looked at. This copied the block's own keys instead,
 * and forwarded them — a `labxelExpr` written for `labelExpr` reached Vega, which reported
 * `PARSE_UNKNOWN_PROPERTY` two stages downstream and drew the labels untruncated. Three
 * specifications in the wild corpus contain that exact misspelling.
 *
 * It is the same rule as the mark's, and it fails the same way: a denylist cannot be finished,
 * because what it has to exclude is every word nobody has written yet.
 *
 * `disable`, `selections` and `labelExpr` are component-internal — `assembleLegend` destructures
 * them away — but `disable` is still *honoured* on the way past, which is checked below.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class LegendPropertyAllowlistTest {

  private fun legend(block: String): VegaValue.Obj? {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":"point",
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"},
                           "color":{"field":"c","type":"nominal","legend":$block}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["legends"] as? VegaValue.Arr)?.values?.firstOrNull() as? VegaValue.Obj
  }

  /** The reported shape, and the reason the rule cannot be a denylist. */
  @Test
  fun `a misspelled legend property does not reach Vega`() {
    val legend = legend("""{"labxelExpr":"datum.label"}""")
    assertNull(legend?.fields?.get("labxelExpr"), "Vega has no such legend property")
  }

  /** A word from some later version goes the same way, and needs listing nowhere. */
  @Test
  fun `a property this version has never heard of does not reach Vega`() {
    assertNull(legend("""{"someLaterVegaLiteWord":5}""")?.fields?.get("someLaterVegaLiteWord"))
  }

  /** The property it was a misspelling of is applied, not forwarded under its own name. */
  @Test
  fun `labelExpr is applied rather than written out`() {
    val legend = legend("""{"labelExpr":"datum.label"}""")
    assertNull(legend?.fields?.get("labelExpr"), "it is not a Vega legend property")
    assertTrue(legend?.fields?.containsKey("encode") == true, "it lands on the labels' encode")
  }

  /** An ordinary legend property still goes out, the filter being narrow. */
  @Test
  fun `a known legend property reaches Vega`() {
    assertEquals(VegaValue.Str("left"), legend("""{"orient":"left"}""")?.fields?.get("orient"))
  }

  /** `selections` is the component's own bookkeeping and never Vega's. */
  @Test
  fun `the component's own bookkeeping does not reach Vega`() {
    assertNull(legend("""{"selections":["p"]}""")?.fields?.get("selections"))
  }

  /**
   * `disable` is component-internal too, and stripped on the way out — but it is *honoured* first:
   * `assembleLegend` answers nothing for a disabled component, so the key goes and the legend with
   * it.
   */
  @Test
  fun `a legend block that disables the legend has no legend`() {
    val legend = legend("""{"disable":true}""")
    assertNull(legend, "there is no legend to write the property on")
  }

  /** And a legend with nothing said about it is unaffected by any of this. */
  @Test
  fun `an ordinary legend is unchanged`() {
    val legend = legend("""{}""")
    assertTrue(legend != null)
    assertFalse(legend!!.fields.containsKey("disable"))
    assertEquals(VegaValue.Str("c"), legend.fields["title"])
  }
}
