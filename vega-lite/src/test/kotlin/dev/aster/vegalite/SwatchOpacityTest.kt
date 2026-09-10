package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A swatch takes the opacity the mark took.
 *
 * By the time a legend reads it, the reduced scatter opacity is just `markDef.opacity` —
 * `initMarkDef` has already settled it, and settles it from **both** opacities:
 * ```js
 * const specifiedOpacity = getMarkPropOrConfig('opacity', markDef, config);
 * const specifiedFillOpacity = getMarkPropOrConfig('fillOpacity', markDef, config);
 * if (specifiedOpacity === undefined && specifiedFillOpacity === undefined) {
 *   markDef.opacity = opacity(markDef.type, encoding);
 * }
 * ```
 *
 * A mark that says how solid its fill is has answered the question, so its **swatch** is not faded
 * either. This asked a narrower question in the legend than the mark asks — no `fillOpacity`, and
 * the mark alone rather than the mark, its styles and the configuration — so a swatch came out
 * fainter than the mark beside it. The two now ask through the same lookup rather than through two
 * copies of it, which is what let them drift apart.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class SwatchOpacityTest {

  private fun swatch(mark: String, config: String = ""): VegaValue.Obj? {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":$mark,$config
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"},
                           "color":{"field":"c","type":"nominal"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val legend = (compiled.fields["legends"] as VegaValue.Arr).values.first() as VegaValue.Obj
    val symbols = (legend.fields["encode"] as? VegaValue.Obj)?.fields?.get("symbols")
    return (symbols as? VegaValue.Obj)?.fields?.get("update") as? VegaValue.Obj
  }

  private fun opacityOf(update: VegaValue.Obj?) =
    (update?.fields?.get("opacity") as? VegaValue.Obj)?.fields?.get("value")

  /** A plain scatter's swatch is as faded as its marks. */
  @Test
  fun `a plain scatter's swatch takes the reduced opacity`() {
    assertEquals(VegaValue.Num(0.7), opacityOf(swatch(""""circle"""")))
  }

  /**
   * The reported shape: the mark states its fill opacity, so neither it nor its swatch is faded.
   */
  @Test
  fun `a stated fill opacity leaves the swatch unfaded`() {
    assertNull(opacityOf(swatch("""{"type":"circle","fillOpacity":1}""")))
  }

  /** A stated opacity is the swatch's opacity, as it always was. */
  @Test
  fun `a stated opacity is used for the swatch`() {
    assertEquals(VegaValue.Num(0.4), opacityOf(swatch("""{"type":"circle","opacity":0.4}""")))
  }

  /** And a mark that never took the reduction has nothing for the swatch to take. */
  @Test
  fun `a bar's swatch is not faded`() {
    assertNull(opacityOf(swatch(""""bar"""")))
  }
}
