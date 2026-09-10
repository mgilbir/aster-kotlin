package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * An offset of nothing is no offset.
 *
 * ```js
 * const markDefOffsetValue = markDef[channel];
 * if (markDefOffsetValue) {
 *   return {offsetType: 'visual', offset: markDefOffsetValue};
 * }
 * return {};
 * ```
 *
 * Truthy, so a stated zero moves nothing and is not written. `"thetaOffset": 0` is what a chart
 * written by a tool that always emits the key leaves behind — three specifications in the wild
 * corpus have one — and an `offset: 0` on a position says the same thing at more length.
 *
 * An offset written as an expression is an object, and objects are truthy, so it still applies.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class MarkOffsetTest {

  private fun update(mark: String, encoding: String): VegaValue.Obj {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """{"data":{"values":[{"a":1,"b":2}]},"mark":$mark,"encoding":$encoding}"""
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val mark0 = (compiled.fields["marks"] as VegaValue.Arr).values.first() as VegaValue.Obj
    return (mark0.fields["encode"] as VegaValue.Obj).fields["update"] as VegaValue.Obj
  }

  private val cartesian =
    """{"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"quantitative"}}"""
  private val polar = """{"theta":{"field":"a","type":"quantitative"}}"""

  private fun offsetOf(update: VegaValue.Obj, channel: String) =
    (update.fields[channel] as? VegaValue.Obj)?.fields?.get("offset")

  /** The reported shape: a zero offset on a polar position. */
  @Test
  fun `a polar offset of zero is not written`() {
    assertNull(offsetOf(update("""{"type":"arc","thetaOffset":0}""", polar), "startAngle"))
  }

  /** And on a cartesian one, which is the same function. */
  @Test
  fun `a cartesian offset of zero is not written`() {
    assertNull(offsetOf(update("""{"type":"point","xOffset":0}""", cartesian), "x"))
  }

  /** An offset that moves the mark is still written, both ways round. */
  @Test
  fun `an offset that moves the mark is written`() {
    assertEquals(
      VegaValue.Num(5.0),
      offsetOf(update("""{"type":"point","xOffset":5}""", cartesian), "x"),
    )
    assertEquals(
      VegaValue.Num(5.0),
      offsetOf(update("""{"type":"arc","thetaOffset":5}""", polar), "startAngle"),
    )
  }

  /**
   * An expression is an object, and objects are truthy — so it applies whatever it evaluates to.
   */
  @Test
  fun `an offset written as an expression still applies`() {
    assertEquals(
      VegaValue.Obj(linkedMapOf("signal" to VegaValue.Str("2+3"))),
      offsetOf(update("""{"type":"point","xOffset":{"expr":"2+3"}}""", cartesian), "x"),
    )
  }
}
