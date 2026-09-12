package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A tooltip line reads the **column as written**, not the column the aggregate wrote.
 *
 * `addLineBreaksToTooltip` builds the discrete form from the definition's own field, spelled into
 * the expression:
 * ```js
 * const fieldString = `${expr}["${channelDef.field}"]`;
 * return {signal: `isValid(${fieldString}) ? isArray(${fieldString}) ? … : ""+${fieldString}`};
 * ```
 *
 * For an `order` channel that **counts** the rows there is no field to spell, and JavaScript prints
 * `undefined` for one — so the chart's spoken description reads `datum["undefined"]`, a column no
 * row has. This engine read the column the aggregate wrote instead, `datum["__count"]`, which is
 * the sensible thing and not what Vega is given.
 *
 * The reason the discrete form is reached at all is the second half of the same quirk:
 * `initFieldDef` gives an `order` definition no type, so `add` in `tooltip.ts` falls back to
 * `encoding[mainChannel].type` — and an `order` is held as an **array**, whose `type` is undefined.
 * A definition with a type takes the numeric form and reads the aggregate's column properly.
 *
 * Two specifications in the wild corpus write an order that way. Every expectation was compiled
 * with upstream rather than reasoned about.
 */
class OrderChannelDescriptionTest {

  private fun description(order: String): String {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"t":"x"}]},"mark":"bar",
               "encoding":{"x":{"field":"t","type":"ordinal"},
                           "y":{"field":"b","type":"quantitative"},
                           "color":{"field":"t","type":"nominal"},
                           "order":$order}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val marks = compiled.fields["marks"] as VegaValue.Arr
    return (marks.values.first() as VegaValue.Obj)
      .obj("encode")
      ?.obj("update")
      ?.obj("description")
      ?.string("signal")
      .orEmpty()
  }

  /** The reported shape: an order that counts the rows, with no type of its own. */
  @Test
  fun `a counting order with no type reads a column no row has`() {
    val signal = description("""{"aggregate":"count"}""")
    assertTrue(
      signal.endsWith(
        "\"; Count of Records: \" + (isValid(datum[\"undefined\"]) ? " +
          "isArray(datum[\"undefined\"]) ? join(datum[\"undefined\"], ' ') : " +
          "datum[\"undefined\"] : \"\"+datum[\"undefined\"])"
      ),
      "was: $signal",
    )
  }

  /** With a type stated, the numeric form reads the column the aggregate wrote. */
  @Test
  fun `a counting order with a type reads the aggregate's column`() {
    val signal = description("""{"aggregate":"count","type":"quantitative"}""")
    assertTrue(
      signal.endsWith("\"; Count of Records: \" + (format(datum[\"__count\"], \"\"))"),
      "was: $signal",
    )
  }

  /** An order naming a column is one of the position channels' own fields, and says nothing new. */
  @Test
  fun `an order naming a column adds nothing to the description`() {
    assertEquals(
      "\"t: \" + (isValid(datum[\"t\"]) ? isArray(datum[\"t\"]) ? join(datum[\"t\"], ' ') : " +
        "datum[\"t\"] : \"\"+datum[\"t\"]) + \"; b: \" + (format(datum[\"b\"], \"\"))",
      description("""{"field":"b","type":"quantitative"}"""),
    )
  }
}
