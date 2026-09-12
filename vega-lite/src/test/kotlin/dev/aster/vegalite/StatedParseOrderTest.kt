package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The parse a specification **stated** is read before the one this compiler inferred.
 *
 * `ParseNode.makeExplicit` runs before the transforms and the implicit parse from the encoding
 * after them, so where the two meet the stated half is the one above — and `keys(this._parse)` is
 * insertion order, which is the order the formulas a parse writes come out in.
 *
 * This engine added the stated half last, so a table stating how to read one column and leaving
 * another to be inferred read them in the opposite order to upstream. It shows on a table written
 * **out** in the specification, where Vega has already ingested the rows and a parse is a formula
 * rather than an instruction to the loader: one specification in the wild corpus carries an inline
 * table with a stated parse beside an inferred one.
 *
 * The two halves settle the same column the way `Split(explicit, implicit)` does: the stated one
 * wins, and a stated `null` denies the inferred parse altogether — `ancestorParse` records the null
 * so nothing below adds one.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class StatedParseOrderTest {

  /** The transforms of the one derived dataset, as `type:expr`. */
  private fun transforms(spec: String): List<String> {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val derived =
      (compiled.fields["data"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first { it.has("source") }
    return (derived.fields["transform"] as? VegaValue.Arr)?.values.orEmpty().map {
      it as VegaValue.Obj
      "${it.string("type")}:${it.string("expr")}"
    }
  }

  private fun inline(parse: String) =
    """{"data":{"values":[{"line":"1979-01-01","Date":"01 Jan 1979"}],"format":{"parse":$parse}},
       "mark":"rule","encoding":{"x":{"field":"line","type":"temporal"}}}"""

  /** The reported shape: the stated column is read first, the inferred one after it. */
  @Test
  fun `a stated parse comes before an inferred one`() {
    assertEquals(
      listOf(
        """formula:utcParse(datum["Date"],'%d %b %Y')""",
        """formula:toDate(datum["line"])""",
        """filter:(isDate(datum["line"]) || (isValid(datum["line"]) && isFinite(+datum["line"])))""",
      ),
      transforms(inline("""{"Date":"utc:'%d %b %Y'"}""")),
    )
  }

  /** Of one column, the stated reading is the one that happens. */
  @Test
  fun `a stated parse wins over an inferred one`() {
    assertEquals(
      listOf(
        """formula:toNumber(datum["line"])""",
        """filter:(isDate(datum["line"]) || (isValid(datum["line"]) && isFinite(+datum["line"])))""",
      ),
      transforms(inline("""{"line":"number"}""")),
    )
  }

  /** And a stated `null` says the column is not to be read at all. */
  @Test
  fun `a stated null denies the inferred parse`() {
    assertEquals(
      listOf(
        """filter:(isDate(datum["line"]) || (isValid(datum["line"]) && isFinite(+datum["line"])))"""
      ),
      transforms(inline("""{"line":null}""")),
    )
  }
}
