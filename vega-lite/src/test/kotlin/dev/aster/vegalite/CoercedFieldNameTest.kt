package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A `field` is read as **JavaScript** reads it.
 *
 * It is a string in the grammar and nothing upstream checks that: the name is spelled into a
 * template — `` `${expr}["${channelDef.field}"]` `` — and into `vgField`'s regular expressions,
 * both of which coerce whatever they are given. So `"field": ["2021"]` names the column `2021`, a
 * one-element array stringifying to its element; `"field": ["a", "b"]` names a column called `a,b`;
 * and a number or a boolean names itself.
 *
 * This read the property as a string and answered nothing for anything else — which makes the
 * definition not a field definition at all, so the channel had no scale and a map coloured by a
 * column written that way was drawn in one flat colour. Two specifications in the wild corpus write
 * the array form.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class CoercedFieldNameTest {

  private fun compiled(colour: String): VegaValue.Obj =
    VegaJson.parse(
      requireNotNull(
        VegaLiteCompiler()
          .compileJson(
            """
            {"data":{"values":[{"2021":1,"b":2}]},"mark":"point",
             "encoding":{"x":{"field":"b","type":"quantitative"},
                         "color":{"field":$colour,"type":"quantitative"}}}
            """
          )
          .toJson()
      ) {
        "did not compile"
      }
    ) as VegaValue.Obj

  /** The column the colour scale measures, which is the name as it was read. */
  private fun scaledColumn(colour: String): String? =
    (compiled(colour).fields["scales"] as? VegaValue.Arr)
      ?.values
      ?.mapNotNull { it as? VegaValue.Obj }
      ?.firstOrNull { it.string("name") == "color" }
      ?.obj("domain")
      ?.string("field")

  private fun legendTitle(colour: String): String? =
    (compiled(colour).fields["legends"] as? VegaValue.Arr)
      ?.values
      ?.mapNotNull { it as? VegaValue.Obj }
      ?.firstOrNull()
      ?.string("title")

  /** The reported shape: a column named in a one-element array. */
  @Test
  fun `a field written as a one-element array names its element`() {
    assertEquals("2021", scaledColumn("""["2021"]"""))
    assertEquals("2021", legendTitle("""["2021"]"""))
  }

  /** Two elements join with a comma, `Array.prototype.toString` doing the work. */
  @Test
  fun `a field written as a two-element array names a column with a comma in it`() {
    assertEquals("a,b", scaledColumn("""["a","b"]"""))
  }

  /** A number names itself, and without a trailing zero. */
  @Test
  fun `a field written as a number names itself`() {
    assertEquals("2021", scaledColumn("2021"))
    assertEquals("2021", legendTitle("2021"))
  }

  /** So does a boolean, absurd as the column would be. */
  @Test
  fun `a field written as a boolean names itself`() {
    assertEquals("true", scaledColumn("true"))
  }

  /** And a field written as text is unaffected by any of this. */
  @Test
  fun `a field written as text is read as it always was`() {
    assertEquals("2021", scaledColumn(""""2021""""))
  }

  /**
   * An object is refused rather than coerced: `[object Object]` is a column no table has, and a
   * chart naming one has a mistake in it worth reporting. The channel then names no column, which
   * is upstream's answer for a definition it cannot read as one either.
   */
  @Test
  fun `a field written as an object names no column`() {
    assertNull(scaledColumn("""{"a":1}"""))
  }
}
