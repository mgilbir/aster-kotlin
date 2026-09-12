package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A mark may ask not to be sorted, and says so on the mark rather than on a channel.
 *
 * ```js
 * if (
 *   (!isArray(order) && isValueDef(order) && isNullOrFalse(order.value)) ||
 *   (!order && isNullOrFalse(getMarkPropOrConfig('order', markDef, config)))
 * ) {
 *   return undefined;
 * }
 * ```
 *
 * A path is drawn along its own dimension by default, or nothing would keep it from doubling back.
 * A chart whose path is a *route* — a trail whose width tells a story about a journey — has to be
 * drawn in the order its table holds, and `{"mark": {"type": "trail", "order": false}}` is how it
 * says so.
 *
 * This engine read half of the other way to say it: a `null` written on the `order` **channel**. A
 * `false` there says the same thing, and the mark's own property was not read at all — so such a
 * chart was sorted left to right and its route came out re-drawn. One specification in the wild
 * corpus is that chart. `getMarkPropOrConfig` reads the mark, then its styles, then the mark
 * configuration, so a theme may ask for it too.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class MarkOrderTest {

  private fun sort(spec: String): VegaValue? {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val marks = (compiled.fields["marks"] as? VegaValue.Arr)?.values.orEmpty()
    return (marks.first() as VegaValue.Obj).fields["sort"]
  }

  private val data = """"data":{"values":[{"c":1,"b":1}]}"""
  private val positions =
    """"encoding":{"x":{"field":"c","type":"quantitative"},
                   "y":{"field":"b","type":"quantitative"}}"""

  private fun chart(mark: String, config: String = "") =
    """{$data,$config"mark":$mark,$positions}"""

  /** The reported shape: a trail that asks for the rows as they come. */
  @Test
  fun `a mark that asks not to be sorted is not sorted`() {
    assertNull(sort(chart("""{"type":"trail","order":false}""")))
  }

  /** A `null` on the mark says the same thing. */
  @Test
  fun `a null order on the mark is not sorted either`() {
    assertNull(sort(chart("""{"type":"line","order":null}""")))
  }

  /** An `order` that is neither is not a refusal, and the path is drawn along its dimension. */
  @Test
  fun `a mark that states a true order is still sorted`() {
    assertEquals(json("""{"field":"x"}"""), sort(chart("""{"type":"line","order":true}""")))
  }

  @Test
  fun `a path that says nothing is sorted along its dimension`() {
    assertEquals(json("""{"field":"x"}"""), sort(chart("\"line\"")))
  }

  /** A theme may ask for it, for every mark or for one kind of mark. */
  @Test
  fun `a themed order of false is read`() {
    assertNull(sort(chart("\"line\"", """"config":{"mark":{"order":false}},""")))
  }

  @Test
  fun `a themed order on the kind of mark is read`() {
    assertNull(sort(chart("\"line\"", """"config":{"line":{"order":false}},""")))
  }

  /** And so may a style the mark names, which is the middle of the three places asked. */
  @Test
  fun `a styled order of false is read`() {
    assertNull(
      sort(
        chart("""{"type":"line","style":"s1"}""", """"config":{"style":{"s1":{"order":false}}},""")
      )
    )
  }

  // -- The channel, whose other half was missing -------------------------------------------------

  @Test
  fun `an order channel valued false is not sorted`() {
    assertNull(
      sort(
        """{$data,"mark":"line","encoding":{"x":{"field":"c","type":"quantitative"},
           "y":{"field":"b","type":"quantitative"},"order":{"value":false}}}"""
      )
    )
  }

  @Test
  fun `an order channel valued null is not sorted`() {
    assertNull(
      sort(
        """{$data,"mark":"line","encoding":{"x":{"field":"c","type":"quantitative"},
           "y":{"field":"b","type":"quantitative"},"order":{"value":null}}}"""
      )
    )
  }

  /** An `order` channel naming a **column** is a sort by that column, as it always was. */
  @Test
  fun `an order channel naming a column sorts by it`() {
    assertEquals(
      json("""{"field":["datum[\"b\"]"],"order":["ascending"]}"""),
      sort(
        """{$data,"mark":"line","encoding":{"x":{"field":"c","type":"quantitative"},
           "y":{"field":"b","type":"quantitative"},
           "order":{"field":"b","type":"quantitative"}}}"""
      ),
    )
  }

  private fun json(text: String) = VegaJson.parse(text)
}
