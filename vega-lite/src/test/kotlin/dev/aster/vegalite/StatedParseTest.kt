package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `"parse": {"«field»": null}` says **do not** parse a column, and takes the implicit parse with
 * it.
 *
 * The stated `parse` belongs to the parse node rather than to the source, whatever the table is:
 * ```js
 * format = data.format ? {...omit(data.format, ['parse'])} : ({} as DataFormat);
 * ```
 *
 * and the node decides where its work lands — back onto `format.parse` where it sits directly under
 * the source, into a formula where a transform stands between. This copied a url's `format` across
 * whole, so a `null` entry reached Vega as an instruction to parse a column *as null*, which it
 * reported and then ignored.
 *
 * The null is not merely dropped. It is kept in the ancestor's record so that nothing below adds a
 * parse for that field, and only then left out of the node:
 * ```js
 * // copy only non-null parses
 * for (const key of keys(parse.combine())) {
 *   const val = parse.get(key);
 *   if (val !== null) { p[key] = val; }
 * }
 * ```
 *
 * So a temporal column told not to be parsed is not parsed at all, where an implicit `date` would
 * otherwise have been inferred from its type. Three specifications in the wild corpus write one.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class StatedParseTest {

  private fun sourceFormat(data: String, extra: String = ""): VegaValue.Obj? {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":$data$extra,"mark":"point",
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"t","type":"temporal"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .first { it.fields.containsKey("url") || it.fields.containsKey("values") }
      .obj("format")
  }

  /** A table read from a url, with whatever format block the case needs. */
  private fun urlData(format: String = "") = """{"url":"http://example.test/y.csv"$format}"""

  private fun parse(format: VegaValue.Obj?) =
    format?.obj("parse")?.fields?.mapValues { (_, v) -> (v as? VegaValue.Str)?.value }

  /** The reported shape: one column parsed, another told not to be. */
  @Test
  fun `a null entry is left out of the loader's parse`() {
    assertEquals(
      mapOf("t" to "date", "a" to "number"),
      parse(sourceFormat(urlData(""","format":{"type":"csv","parse":{"a":"number","g":null}}"""))),
      "the null goes and the implicit `date` for the temporal column stays",
    )
  }

  /**
   * And it takes the **implicit** parse for that column with it, which is the point of the null.
   */
  @Test
  fun `a null entry suppresses the parse its type would have implied`() {
    val format = sourceFormat(urlData(""","format":{"parse":{"t":null}}"""))
    assertNull(format?.fields?.get("parse"), "nothing left to parse, so no parse block at all")
    assertEquals(VegaValue.Str("csv"), format?.fields?.get("type"), "the rest of the format stands")
  }

  /** With no null, a url's parse is the stated one and the implied one together. */
  @Test
  fun `a stated parse and an implied one reach the loader together`() {
    assertEquals(
      mapOf("t" to "date", "a" to "number"),
      parse(sourceFormat(urlData(""","format":{"parse":{"a":"number"}}"""))),
    )
  }

  /** With nothing stated, the implied parse is what it always was. */
  @Test
  fun `a url with no format still gets the implied parse`() {
    assertEquals(mapOf("t" to "date"), parse(sourceFormat(urlData())))
  }

  /**
   * A table written **out** in the specification has already been ingested by Vega, so its parse is
   * a formula rather than the loader's work — and a null entry leaves neither.
   */
  @Test
  fun `a null entry on an inline table leaves no format and no formula`() {
    assertNull(
      sourceFormat("""{"values":[{"a":1,"t":"2001-01-01"}],"format":{"parse":{"t":null}}}""")
    )
  }

  /** A transform between the source and the parse puts the parse in a formula, null or not. */
  @Test
  fun `a null entry survives a transform standing between`() {
    val format =
      sourceFormat(
        urlData(""","format":{"parse":{"t":null}}"""),
        ""","transform":[{"filter":"datum.a > 0"}]""",
      )
    assertNull(format?.fields?.get("parse"))
    assertEquals(VegaValue.Str("csv"), format?.fields?.get("type"))
  }
}
