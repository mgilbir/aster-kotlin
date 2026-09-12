package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A **datum** is placed the way a column is: the same value ref, band and nudge included.
 *
 * ```js
 * return valueRefForFieldOrDatumDef(channelDef, scaleName, hasDiscreteDomain(scaleType) ? ... : {}, {
 *   offset,
 *   // For band, to get mid point, need to offset by half of the band
 *   band: scaleType === 'band' ? (bandPosition ?? channelDef.bandPosition ?? 0.5) : undefined,
 * });
 * ```
 *
 * `valueRefForFieldOrDatumDef` writes a `value` where the definition is a literal and a `field`
 * where it names a column, and everything after that — the half-band that puts a mark in the middle
 * of its band rather than on its edge, and the nudge — is written the same way for both. This
 * compiler answered a datum from a branch of its own and stopped there, so a rule drawn at a named
 * category sat on the boundary between two bands instead of through the middle of one. (The mark's
 * own `xOffset` did reach it, `positionRef` adding that to whatever came back.)
 *
 * The bucketing branches are for a column and not for a literal — `isTypedFieldDef` gates them on
 * the definition naming one — so a datum written with a `timeUnit` or a `bin` is placed at the
 * literal, which is what it says.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class DatumPositionTest {

  /** The mark's `x`, and the scale it is placed against. */
  private fun placed(mark: String, x: String): String {
    val spec =
      """{"data":{"values":[{"a":"p","b":2}]},"mark":$mark,
         "encoding":{"x":$x,"y":{"field":"b","type":"quantitative"}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val update =
      ((compiled.fields["marks"] as VegaValue.Arr).values[0] as VegaValue.Obj)
        .obj("encode")!!
        .obj("update")!!
    val scale =
      (compiled.fields["scales"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first { it.string("name") == "x" }
    return VegaJson.write(update.fields["x"] ?: VegaValue.Null).replace(Regex("""\n\s*"""), "") +
      " on ${scale.string("type")}"
  }

  /** The reported shape: a rule drawn at a named category, which stands on a band. */
  @Test
  fun `a datum on a band sits in the middle of it`() {
    assertEquals(
      """{"scale": "x","value": "p","band": 0.5} on band""",
      placed("\"rule\"", """{"datum":"p","type":"nominal"}"""),
    )
  }

  /** A mark that is placed at a **point** has no band to sit in the middle of. */
  @Test
  fun `a datum on a point scale takes no band`() {
    assertEquals(
      """{"scale": "x","value": "p"} on point""",
      placed("\"point\"", """{"datum":"p","type":"nominal"}"""),
    )
  }

  /** Nor does a measured one, which is where this already agreed. */
  @Test
  fun `a datum on a continuous scale takes no band`() {
    assertEquals(
      """{"scale": "x","value": 5} on linear""",
      placed("\"point\"", """{"datum":5,"type":"quantitative"}"""),
    )
  }

  /** The mark's own nudge reaches a datum, `offset` being written for both alike. */
  @Test
  fun `a datum takes the mark's own nudge`() {
    assertEquals(
      """{"scale": "x","value": "p","offset": 7} on point""",
      placed("""{"type":"point","xOffset":7}""", """{"datum":"p","type":"nominal"}"""),
    )
  }

  /** Both at once, on a mark that stands on a band and is nudged off its middle. */
  @Test
  fun `a datum takes the nudge and the band together`() {
    assertEquals(
      """{"scale": "x","value": "p","band": 0.5,"offset": 7} on band""",
      placed("""{"type":"rule","xOffset":7}""", """{"datum":"p","type":"nominal"}"""),
    )
  }

  /**
   * A datum is never a **bucket**: `isTypedFieldDef` gates that branch on the definition naming a
   * column, so a literal written with a time unit is placed at the literal and not at the middle of
   * the month it falls in.
   */
  @Test
  fun `a datum with a time unit is still placed at the literal`() {
    assertEquals(
      """{"scale": "x","value": "2006-02-03"} on time""",
      placed(
        "\"text\"",
        """{"datum":"2006-02-03","type":"temporal","timeUnit":"month","bandPosition":0.5}""",
      ),
    )
  }

  /** And a literal written with a `bin` is placed at the literal too, for the same reason. */
  @Test
  fun `a datum with a bin is still placed at the literal`() {
    assertEquals(
      """{"scale": "x","value": 5} on linear""",
      placed("\"point\"", """{"datum":5,"type":"quantitative","bin":true}"""),
    )
  }

  /** A datum written as a **date** is still the expression that builds the instant. */
  @Test
  fun `a datum written as a date is an expression`() {
    assertEquals(
      """{"scale": "x","signal": "datetime(2006, 0, 1, 0, 0, 0, 0)"} on time""",
      placed("\"point\"", """{"datum":{"year":2006},"type":"temporal"}"""),
    )
  }
}
