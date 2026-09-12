package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A filter's comparison says what its column holds, and a **falsy** comparison says nothing.
 *
 * ```js
 * if (val) {
 *   if (isDateTime(val)) implicit[filter.field] = 'date';
 *   else if (isNumber(val)) implicit[filter.field] = 'number';
 *   else if (isString(val)) implicit[filter.field] = 'string';
 * }
 * if (filter.timeUnit) {
 *   implicit[filter.field] = 'date';
 * }
 * ```
 *
 * `if (val)` is JavaScript truthiness, so `{"gt": 0}` — the commonest filter there is, *keep the
 * rows that have a value* — tells `getImplicitFromFilterTransform` nothing, and the column is
 * loaded as it was found. This engine asked what **kind** the comparison was and got an answer for
 * zero, for the empty string and for `false`, so it wrote a `parse` upstream does not. One
 * specification in the wild corpus filters that way.
 *
 * The `timeUnit` is asked outside the gate, and so is asked whatever the comparison was.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class FilterParseTest {

  private fun parse(filter: String): VegaValue? {
    val spec =
      """{"data":{"url":"http://example.test/x.csv"},"transform":[{"filter":$filter}],
         "mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
                                    "y":{"field":"b","type":"quantitative"}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val source = (compiled.fields["data"] as VegaValue.Arr).values.first() as VegaValue.Obj
    return (source.fields["format"] as? VegaValue.Obj)?.fields?.get("parse")
  }

  /** The reported shape: keep the rows with a value, and say nothing about the column. */
  @Test
  fun `a comparison against zero says nothing`() {
    assertNull(parse("""{"field":"c","gt":0}"""))
  }

  /** A comparison against any other number does say something. */
  @Test
  fun `a comparison against a number says the column holds numbers`() {
    assertEquals(json("""{"c":"number"}"""), parse("""{"field":"c","gt":5}"""))
  }

  @Test
  fun `a comparison against the empty string says nothing`() {
    assertNull(parse("""{"field":"c","equal":""}"""))
  }

  @Test
  fun `a comparison against a string says the column holds strings`() {
    assertEquals(json("""{"c":"string"}"""), parse("""{"field":"c","equal":"x"}"""))
  }

  /** Neither boolean says anything, one of them for being falsy and the other for being neither. */
  @Test
  fun `a comparison against false says nothing`() {
    assertNull(parse("""{"field":"c","equal":false}"""))
  }

  @Test
  fun `a comparison against true says nothing either`() {
    assertNull(parse("""{"field":"c","equal":true}"""))
  }

  /** A range and a list are read by their **first** entry, and it is gated the same way. */
  @Test
  fun `a range starting at zero says nothing`() {
    assertNull(parse("""{"field":"c","range":[0,10]}"""))
  }

  @Test
  fun `a list starting with the empty string says nothing`() {
    assertNull(parse("""{"field":"c","oneOf":["","y"]}"""))
  }

  /** A `timeUnit` is asked outside the gate: it settles the column whatever the comparison was. */
  @Test
  fun `a time unit says the column holds dates however it compares`() {
    assertEquals(json("""{"c":"date"}"""), parse("""{"field":"c","timeUnit":"year","equal":0}"""))
  }

  /** Every leaf of a compound predicate is asked, and each is gated for itself. */
  @Test
  fun `each leaf of a compound predicate is gated on its own`() {
    assertEquals(
      json("""{"d":"number"}"""),
      parse("""{"and":[{"field":"c","gt":0},{"field":"d","gt":2}]}"""),
    )
  }

  private fun json(text: String) = VegaJson.parse(text)
}
