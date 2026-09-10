package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `timeUnit` transform reads its input as a date, and reads it **before** it runs.
 *
 * ```js
 * } else if (isTimeUnit(t)) {
 *   derivedType = 'date';
 *   const parsedAs = ancestorParse.getWithExplicit(t.field);
 *   // Create parse node because the input to time unit is always date.
 *   if (parsedAs.value === undefined) {
 *     head = new ParseNode(head, {[t.field]: derivedType});
 *     ancestorParse.set(t.field, derivedType, false);
 *   }
 *   transformNode = head = TimeUnitNode.makeFromTransform(head, t);
 * }
 * ```
 *
 * The **order** is the whole of it, and two things followed from getting it wrong.
 *
 * `{"field": "ts", "timeUnit": …, "as": "ts"}` reads a column and writes it back under its own
 * name. Taking the transform's output as derived — which it is — took the parse with it, so the
 * bucketing ran over text. And the parse this engine did emit was written *below* the transform,
 * where it parses the transform's own output rather than its input.
 *
 * Once the parse is above the transform, `ancestorParse` has settled that column and the encoding
 * does not ask for it again: a temporal `x` over the same field adds nothing.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class TimeUnitTransformParseTest {

  private fun transformTypes(spec: String): List<Pair<String?, List<String>>> {
    val compiled =
      VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "did not compile" }
      ) as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .map { data ->
        (data.fields["name"] as? VegaValue.Str)?.value to
          (data.fields["transform"] as? VegaValue.Arr)?.values.orEmpty().mapNotNull {
            ((it as? VegaValue.Obj)?.fields?.get("type") as? VegaValue.Str)?.value
          }
      }
  }

  private fun chart(transform: String, xField: String) =
    transformTypes(
      """
      {"data":{"values":[{"ts":"2020-01-01","b":2}]},"transform":[$transform],"mark":"point",
       "encoding":{"x":{"field":"$xField","type":"temporal"},
                   "y":{"field":"b","type":"quantitative"}}}
      """
    )

  private fun of(name: String, rows: List<Pair<String?, List<String>>>) =
    rows.first { it.first == name }.second

  /** The reported shape: the transform writes its input back under its own name. */
  @Test
  fun `a time unit that writes back its own field still parses it first`() {
    val rows = chart("""{"field":"ts","timeUnit":"utcyearmonthdate","as":"ts"}""", "ts")
    assertEquals(
      listOf("formula", "timeunit"),
      of("data_0", rows).filter { it != "filter" },
      "the parse is above the bucketing, and the encoding does not ask for it again below",
    )
  }

  /** Writing to a new name is the same rule: the *input* is what gets parsed. */
  @Test
  fun `a time unit that writes a new field parses its input`() {
    val rows = chart("""{"field":"ts","timeUnit":"utcyearmonthdate","as":"ts2"}""", "ts2")
    assertEquals(listOf("formula", "timeunit"), of("data_0", rows).filter { it != "filter" })
  }

  /**
   * And the encoding naming the transform's **input**, which is the case that shows the parse is
   * recorded rather than merely emitted: `ancestorParse` has settled `ts` above the transform, so
   * the temporal `x` over it asks for nothing more and there is one formula, not two.
   */
  @Test
  fun `an encoding over the transform's input does not parse it twice`() {
    val rows = chart("""{"field":"ts","timeUnit":"utcyearmonthdate","as":"ts2"}""", "ts")
    assertEquals(listOf("formula", "timeunit"), of("data_0", rows).filter { it != "filter" })
  }

  /** With no transform at all, the encoding's own parse stands where it always did. */
  @Test
  fun `a temporal encoding alone still parses its field`() {
    val rows =
      transformTypes(
        """
        {"data":{"values":[{"ts":"2020-01-01","b":2}]},"mark":"point",
         "encoding":{"x":{"field":"ts","type":"temporal"},
                     "y":{"field":"b","type":"quantitative"}}}
        """
      )
    assertEquals(listOf("formula"), of("data_0", rows).filter { it != "filter" })
  }
}
