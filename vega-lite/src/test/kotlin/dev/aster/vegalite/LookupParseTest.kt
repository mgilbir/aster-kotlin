package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A lookup **produces** the columns it brings in, so nothing loads them.
 *
 * ```js
 * public producedFields() {
 *   return new Set(this.transform.as ? array(this.transform.as) : this.transform.from.fields);
 * }
 * ```
 *
 * `ancestorParse` records what each transform produces as it walks the list, and a produced column
 * is dropped from the implicit parse below it: it is not in the table being loaded, so asking the
 * loader to read it as a date names a column that source has never had.
 *
 * This engine looked for the brought-in columns under the transform's own `lookup` property, which
 * is the column of *this* table the lookup matches on and is a **string** — so it found none, and a
 * date column arriving through a lookup had the loader asked to parse it in a table it is not in.
 * One specification in the wild corpus joins a table that way.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class LookupParseTest {

  /** What each loaded dataset asks the loader to parse, by name. */
  private fun formats(spec: String): Map<String?, VegaValue?> {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .map { it as VegaValue.Obj }
      .filter { it.has("url") }
      .associate {
        it.string("name") to (it.fields["format"] as? VegaValue.Obj)?.fields?.get("parse")
      }
  }

  private val secondary = """{"url":"http://example.test/y.csv"}"""
  private val encoding =
    """"encoding":{"x":{"field":"birthday","type":"temporal"},
                   "y":{"field":"party","type":"nominal"}}"""

  private fun chart(transform: String) =
    """{"data":{"url":"http://example.test/x.csv"},"transform":[$transform],
       "mark":"point",$encoding}"""

  /** The reported shape: the column the encoding reads as a date came from the other table. */
  @Test
  fun `a column a lookup brings in is not parsed`() {
    assertEquals(
      mapOf("source_1" to null, "source_0" to null),
      formats(
        chart(
          """{"lookup":"id","from":{"data":$secondary,"key":"key",
                        "fields":["birthday"]}}"""
        )
      ),
    )
  }

  /** Renamed on the way in, the new name is what the lookup produces. */
  @Test
  fun `a column a lookup renames is not parsed`() {
    assertEquals(
      mapOf("source_1" to null, "source_0" to null),
      formats(
        chart(
          """{"lookup":"id","from":{"data":$secondary,"key":"key","fields":["b"]},
                        "as":["birthday"]}"""
        )
      ),
    )
  }

  /** And a lookup that brings in the whole row names it with a single `as`. */
  @Test
  fun `a whole row brought in under one name is not parsed`() {
    assertEquals(
      mapOf("source_1" to null, "source_0" to null),
      formats(chart("""{"lookup":"id","from":{"data":$secondary,"key":"key"},"as":"birthday"}""")),
    )
  }

  /**
   * The `as` is read **instead of** the secondary's own names, not beside them: a column the lookup
   * renames on the way in leaves the name it had free, and a column of that name in the source
   * table is the source table's own and is parsed.
   */
  @Test
  fun `a name the lookup renames away is the source table's own`() {
    assertEquals(
      mapOf("source_1" to null, "source_0" to VegaJson.parse("""{"birthday":"date"}""")),
      formats(
        chart(
          """{"lookup":"id","from":{"data":$secondary,"key":"key","fields":["birthday"]},
             "as":["moved"]}"""
        )
      ),
    )
  }

  /** Without the lookup the column is the source table's own, and is parsed as always. */
  @Test
  fun `a column of the table itself is parsed`() {
    assertEquals(
      mapOf("source_0" to VegaJson.parse("""{"birthday":"date"}""")),
      formats(
        """{"data":{"url":"http://example.test/x.csv"},"mark":"point",$encoding}"""
          .replace("\n", "")
      ),
    )
  }
}
