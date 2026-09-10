package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A swatch takes the mark's opacity **as it stands**, and only where that opacity is truthy.
 *
 * ```js
 * const opacity = getMaxValue(encoding.opacity) ?? markDef.opacity;
 * if (opacity) {
 *   out.opacity = {value: opacity};
 * }
 * ```
 *
 * Two things follow, and this engine had neither. The value is written **through**, so a mark
 * saying `"opacity": "1"` — a string, which a hand-written specification may well hold — gives a
 * swatch drawn at that string; reading it as a number answered nothing and left the swatch undrawn.
 * And the test is for *truth*, so a mark drawn at **zero** has no swatch opacity written at all
 * rather than a swatch drawn at nothing: `point: "transparent"` on a line is exactly that, the
 * overlay being `{opacity: 0}` and its legend the line's own key.
 *
 * Two specifications in the wild corpus are each one of those two.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class SwatchOpacityValueTest {

  private fun swatchOpacity(mark: String, extra: String = ""): VegaValue? {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"t":"x"}]},"mark":$mark,
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"},
                           "color":{"field":"t","type":"nominal"}$extra}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val legend =
      (compiled.fields["legends"] as? VegaValue.Arr)?.values?.firstOrNull() as? VegaValue.Obj
    return legend
      ?.obj("encode")
      ?.obj("symbols")
      ?.obj("update")
      ?.obj("opacity")
      ?.fields
      ?.get("value")
  }

  /** The reported shape: an opacity written as text. */
  @Test
  fun `an opacity written as text reaches the swatch as text`() {
    assertEquals(VegaValue.Str("1"), swatchOpacity("""{"type":"circle","opacity":"1"}"""))
  }

  /** A number still reaches it as a number. */
  @Test
  fun `an opacity written as a number is unchanged`() {
    assertEquals(VegaValue.Num(0.5), swatchOpacity("""{"type":"circle","opacity":0.5}"""))
    assertEquals(
      VegaValue.Num(0.7),
      swatchOpacity(""""circle""""),
      "and a point-like mark's faded default is what it always was",
    )
  }

  /** **Zero** is not an opacity a swatch is drawn at: the property is left off. */
  @Test
  fun `an opacity of zero leaves the swatch alone`() {
    assertNull(swatchOpacity("""{"type":"circle","opacity":0}"""))
    assertNull(
      swatchOpacity(""""circle"""", ""","opacity":{"value":0}"""),
      "stated on the channel, it goes the same way",
    )
  }

  /** `point: "transparent"` is an overlay at zero opacity, and its key is the line's. */
  @Test
  fun `a transparent point overlay draws no swatch opacity`() {
    assertNull(swatchOpacity("""{"type":"line","point":"transparent"}"""))
  }

  /** A stated opacity that is not zero is written as before. */
  @Test
  fun `a stated opacity on the channel reaches the swatch`() {
    assertEquals(VegaValue.Num(0.3), swatchOpacity(""""circle"""", ""","opacity":{"value":0.3}"""))
  }
}
