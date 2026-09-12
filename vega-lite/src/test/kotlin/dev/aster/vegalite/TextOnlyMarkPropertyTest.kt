package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `align`, `baseline` and `theta` belong to a mark made of words.
 *
 * Every mark compiler hands `baseEncodeEntry` an `ignore` argument, and across all thirteen of them
 * these three are the only entries that differ: `text.ts` says `'include'` and the other twelve say
 * `'ignore'`.
 *
 * ```js
 * ...encode.baseEncodeEntry(model, {
 *   align: 'ignore',
 *   baseline: 'ignore',
 *   color: 'include',
 *   size: 'ignore',
 *   orient: 'ignore',
 *   theta: 'ignore',
 * }),
 * ```
 *
 * A mark that is not words has nothing to anchor. This engine applied only the arc's `theta`
 * exception and forwarded the rest, so `{"type": "line", "align": false}` — written by hand, and
 * meaningless whichever mark it is on — reached Vega as a channel a line has no use for.
 *
 * `radius` is *not* one of the three: no compiler ignores it, and it goes out on any mark. Checked
 * beside them, because a rule that swept it up would be the same mistake in the other direction.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class TextOnlyMarkPropertyTest {

  private fun update(mark: String): VegaValue.Obj {
    val spec =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2}]},"mark":$mark,
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val mark0 = (spec.fields["marks"] as VegaValue.Arr).values.first() as VegaValue.Obj
    return (mark0.fields["encode"] as VegaValue.Obj).fields["update"] as VegaValue.Obj
  }

  private fun value(update: VegaValue.Obj, key: String) =
    (update.fields[key] as? VegaValue.Obj)?.fields?.get("value")

  /** The reported shape: a nonsense `align` on a line, which upstream never looks at. */
  @Test
  fun `an align on a line does not reach Vega`() {
    assertNull(value(update("""{"type":"line","align":false}"""), "align"))
  }

  /** And a perfectly sensible one does not either — the rule is about the mark, not the value. */
  @Test
  fun `a valid align on a line does not reach Vega`() {
    assertNull(value(update("""{"type":"line","align":"left"}"""), "align"))
  }

  /** The same for the other two, on marks that have no use for them. */
  @Test
  fun `a baseline on a bar and a theta on a point do not reach Vega`() {
    assertNull(value(update("""{"type":"bar","baseline":"top"}"""), "baseline"))
    assertNull(value(update("""{"type":"point","theta":1}"""), "theta"))
  }

  /** On a **text** mark all three are the mark's own business and go out. */
  @Test
  fun `all three reach Vega on a text mark`() {
    assertEquals(
      VegaValue.Str("left"),
      value(update("""{"type":"text","align":"left"}"""), "align"),
    )
    assertEquals(
      VegaValue.Str("top"),
      value(update("""{"type":"text","baseline":"top"}"""), "baseline"),
    )
    assertEquals(VegaValue.Num(1.0), value(update("""{"type":"text","theta":1}"""), "theta"))
  }

  /**
   * A text mark left to itself is still anchored in the middle both ways, which is the default the
   * `'include'` exists for and not something this rule may take away.
   */
  @Test
  fun `a text mark keeps its default anchors`() {
    val update = update(""""text"""")
    assertEquals(VegaValue.Str("center"), value(update, "align"))
    assertEquals(VegaValue.Str("middle"), value(update, "baseline"))
  }

  /** `radius` is nobody's exception and reaches Vega on a line like any other mark property. */
  @Test
  fun `a radius on a line still reaches Vega`() {
    assertEquals(VegaValue.Num(4.0), value(update("""{"type":"line","radius":4}"""), "radius"))
  }
}
