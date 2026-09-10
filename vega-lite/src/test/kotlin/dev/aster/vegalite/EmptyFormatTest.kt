package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An empty format is no format.
 *
 * `formatSignalRef` tests the format for **truth**, and `numberFormat` hands a stated `""` straight
 * back rather than replacing it:
 * ```js
 * if (isString(specifiedFormat)) {
 *   return specifiedFormat;
 * }
 * ```
 * ```js
 * } else if (format || channelDefType(fieldOrDatumDef) === 'quantitative') {
 *   return {signal: `${formatExpr(field, format)}`};
 * } else {
 *   return {signal: `isValid(${field}) ? ${field} : ""+${field}`};
 * }
 * ```
 *
 * So a column with **no type** and `"format": ""` is read as text rather than run through
 * `format()`. Writing `"format": ""` beside a real format on the entry that needs one is how a
 * document says "leave this one alone", and this engine tested only whether a format had been
 * written — so the column came back as `format(datum["Item"], "")`, which turns a word into `NaN`.
 *
 * A **quantitative** column still formats with an empty format, because the type carries that arm
 * on its own.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class EmptyFormatTest {

  private fun tooltip(entry: String): String {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"I":"x","V":1}]},"mark":"bar",
               "encoding":{"x":{"field":"I","type":"nominal"},
                           "y":{"field":"V","type":"quantitative"},
                           "tooltip":[$entry]}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val mark = (compiled.fields["marks"] as VegaValue.Arr).values.first() as VegaValue.Obj
    val update = (mark.fields["encode"] as VegaValue.Obj).fields["update"] as VegaValue.Obj
    return ((update.fields["tooltip"] as VegaValue.Obj).fields["signal"] as VegaValue.Str).value
  }

  private val asText =
    """isValid(datum["I"]) ? isArray(datum["I"]) ? join(datum["I"], '\n') : datum["I"] : """ +
      """""+datum["I"]"""

  /** The reported shape: an empty format on an untyped column. */
  @Test
  fun `an empty format on an untyped column reads as text`() {
    assertEquals("""{"Item": $asText}""", tooltip("""{"field":"I","title":"Item","format":""}"""))
  }

  /** Which is the same answer as writing no format at all. */
  @Test
  fun `no format at all reads as text too`() {
    assertEquals("""{"Item": $asText}""", tooltip("""{"field":"I","title":"Item"}"""))
  }

  /** And the same when the column says it is nominal. */
  @Test
  fun `an empty format on a nominal column reads as text`() {
    assertEquals(
      """{"Item": $asText}""",
      tooltip("""{"field":"I","title":"Item","type":"nominal","format":""}"""),
    )
  }

  /** A format that says something is used, which is the point of the entry beside it. */
  @Test
  fun `a real format is used`() {
    assertEquals(
      """{"V": format(datum["V"], "$,")}""",
      tooltip("""{"field":"V","title":"V","format":"$,"}"""),
    )
  }

  /** A quantitative column formats even with an empty format: its type carries that arm. */
  @Test
  fun `a quantitative column formats with an empty format`() {
    assertEquals(
      """{"V": format(datum["V"], "")}""",
      tooltip("""{"field":"V","title":"V","type":"quantitative","format":""}"""),
    )
  }
}
