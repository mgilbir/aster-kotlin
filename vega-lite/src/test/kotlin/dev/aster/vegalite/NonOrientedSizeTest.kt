package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A `size` a rect-based mark cannot apply does not move it either.
 *
 * Upstream honours `size` on a rect-family mark only where `useVlSizeChannel` holds — always for a
 * `tick`, and otherwise where the mark's `orient` matches the channel: horizontal with `y`,
 * vertical with `x`. Anywhere else it logs `cannotApplySizeToNonOrientedMark` and uses the band.
 *
 * The size was already gated that way here. What was not was the **alignment**: a mark that merely
 * *mentioned* `size` was centred in its band, so a `rect` on two discrete scales came out on
 * `xc`/`yc` at half a band where upstream writes `x`/`y` across the band's width. Upstream's test
 * is `!hasSizeFromMarkOrEncoding`, and that flag is set only where the size was actually used.
 *
 * Six of the ten smallest disagreements in the wild-corpus sweep were this one specification shape.
 */
class NonOrientedSizeTest {

  private fun markEncode(json: String): VegaValue.Obj {
    val vega =
      requireNotNull(VegaLiteCompiler().compileJson(json).vega) {
        "the specification did not compile"
      }
    val marks = (VegaJson.parse(vega.let { VegaJson.write(it) }) as VegaValue.Obj)
    val first = (marks.fields["marks"] as VegaValue.Arr).values.first() as VegaValue.Obj
    return ((first.fields["encode"] as VegaValue.Obj).fields["update"]) as VegaValue.Obj
  }

  private val rectOnTwoDiscreteScales =
    """
    {"data":{"values":[{"o":"a","c":1}]},"mark":{"type":"rect"%s},
     "encoding":{"x":{"field":"o","type":"nominal"},"y":{"field":"c","type":"ordinal"}}}
    """

  /**
   * The reported shape: a rect with a size, on two discrete scales, has no orientation to apply it
   * along.
   */
  @Test
  fun `a rect with a size it cannot use spans its band`() {
    val encode = markEncode(rectOnTwoDiscreteScales.format(""", "size": 30"""))
    assertNotNull(encode.fields["x"], "the band's leading edge, as upstream writes it")
    assertNotNull(encode.fields["y"])
    assertNull(encode.fields["xc"], "a size that cannot be applied must not centre the mark")
    assertNull(encode.fields["yc"])
    assertNotNull(encode.fields["width"], "and the band's width is what sizes it")
    assertNotNull(encode.fields["height"])
  }

  /** And it draws the same as one that never mentioned a size, which is upstream's own test. */
  @Test
  fun `it draws the same as the same rect without a size`() {
    assertEquals(
      markEncode(rectOnTwoDiscreteScales.format("")),
      markEncode(rectOnTwoDiscreteScales.format(""", "size": 30""")),
    )
  }

  /**
   * A `tick` is the exception: it always uses the size channel, so a size does centre it.
   *
   * Here to keep the fix honest. Gating the alignment on the same condition as the size means the
   * marks that *can* use a size still behave as they did.
   */
  @Test
  fun `a tick still takes its size`() {
    val encode =
      markEncode(
        """
        {"data":{"values":[{"o":"a","c":1}]},"mark":{"type":"tick","size":30},
         "encoding":{"x":{"field":"o","type":"nominal"},"y":{"field":"c","type":"ordinal"}}}
        """
      )
    assertNotNull(encode.fields["xc"], "a tick is centred on its band")
  }

  /** A bar with an orientation uses the size along the thickness axis, as it always did. */
  @Test
  fun `an oriented bar still takes its size`() {
    val encode =
      markEncode(
        """
        {"data":{"values":[{"o":"a","c":1}]},"mark":{"type":"bar","size":30},
         "encoding":{"x":{"field":"o","type":"nominal"},"y":{"field":"c","type":"quantitative"}}}
        """
      )
    assertNotNull(encode.fields["xc"], "a vertical bar is centred on the x band and sized there")
    assertEquals(VegaValue.Num(30.0), (encode.fields["width"] as VegaValue.Obj).fields["value"])
  }
}
