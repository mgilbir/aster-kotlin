package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * What a mark is read out as, in the four arms `description()` actually has.
 *
 * ```js
 * if (channelDef) return wrapCondition({model, channelDef, vgChannel: 'description', …});
 * const descriptionValue = getMarkPropOrConfig('description', markDef, config);
 * if (descriptionValue != null) return {description: signalOrValueRef(descriptionValue)};
 * if (config.aria === false) return {};
 * const data = tooltipData(encoding, stack, config);
 * ```
 *
 * Only the **last** was implemented here. A `description` channel — the whole point of which is to
 * say what a mark should be read out as — was ignored, and the summary assembled from every encoded
 * field was spoken in its place; a `description` on the mark itself was dropped outright, being
 * kept out of the mark's own properties precisely because it belongs here.
 *
 * The two stated arms come **before** the `config.aria` test, which this had at the top of the
 * whole function. A chart that switches the accessibility tree off still gets a description it
 * asked for by name; only the derived summary goes.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class MarkDescriptionTest {

  private fun description(spec: String): VegaValue? {
    val compiled =
      VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "did not compile" }
      ) as VegaValue.Obj
    val symbol =
      (compiled.fields["marks"] as VegaValue.Arr)
        .values
        .mapNotNull { it as? VegaValue.Obj }
        .first { (it.fields["type"] as? VegaValue.Str)?.value == "symbol" }
    return ((symbol.fields["encode"] as VegaValue.Obj).fields["update"] as VegaValue.Obj)
      .fields["description"]
  }

  private val base =
    """"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"quantitative"}"""

  private fun chart(mark: String = """"point"""", extra: String = "", config: String = "") =
    description(
      """
      {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":$mark,$config
       "encoding":{$base$extra}}
      """
    )

  private fun signal(value: VegaValue?) = (value as? VegaValue.Obj)?.fields?.get("signal")

  private fun value(value: VegaValue?) = (value as? VegaValue.Obj)?.fields?.get("value")

  /** The reported shape: the channel says what to read out, and it is one field's value. */
  @Test
  fun `a description channel is what the mark is read out as`() {
    assertEquals(
      VegaValue.Str("""isValid(datum["c"]) ? datum["c"] : ""+datum["c"]"""),
      signal(chart(extra = ""","description":{"field":"c"}""")),
      "the single-value form, not the tooltip's joined one",
    )
  }

  /** A channel may name a literal instead of a column. */
  @Test
  fun `a description channel may be a plain value`() {
    assertEquals(
      VegaValue.Str("a point"),
      value(chart(extra = ""","description":{"value":"a point"}""")),
    )
  }

  /** The mark's own, which was dropped on the floor entirely. */
  @Test
  fun `a description on the mark is used`() {
    assertEquals(
      VegaValue.Str("a point"),
      value(chart(mark = """{"type":"point","description":"a point"}""")),
    )
  }

  /** And the theme's, `getMarkPropOrConfig` reading both. */
  @Test
  fun `a description from the theme is used`() {
    assertEquals(
      VegaValue.Str("themed"),
      value(chart(config = """"config":{"mark":{"description":"themed"}},""")),
    )
  }

  /** With nothing stated, the summary assembled from the encoded fields — the arm that worked. */
  @Test
  fun `with nothing stated the encoded fields are summarised`() {
    assertEquals(
      VegaValue.Str(
        "\"a: \" + (format(datum[\"a\"], \"\")) + \"; b: \" + (format(datum[\"b\"], \"\"))"
      ),
      signal(chart()),
    )
  }

  /**
   * `config.aria: false` takes the *derived* summary away and leaves a stated description standing.
   * Upstream tests it between the second and third arms, not at the top.
   */
  @Test
  fun `switching the accessibility tree off keeps a description that was asked for`() {
    val ariaOff = """"config":{"aria":false},"""
    assertNull(chart(config = ariaOff), "the derived summary goes")
    assertEquals(
      VegaValue.Str("""isValid(datum["c"]) ? datum["c"] : ""+datum["c"]"""),
      signal(chart(extra = ""","description":{"field":"c"}""", config = ariaOff)),
      "a channel that names one is still spoken",
    )
    assertEquals(
      VegaValue.Str("kept"),
      value(chart(mark = """{"type":"point","description":"kept"}""", config = ariaOff)),
      "and so is one written on the mark",
    )
  }

  /** A channel with conditions becomes a production rule, as every other channel's does. */
  @Test
  fun `a conditional description becomes a production rule`() {
    val rule =
      description(
        """
        {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":"point",
         "params":[{"name":"p","select":"point"}],
         "encoding":{$base,
           "description":{"condition":{"param":"p","field":"c"},"value":"none"}}}
        """
      )
        as VegaValue.Arr
    assertEquals(2, rule.values.size)
    val first = rule.values.first() as VegaValue.Obj
    assertEquals(
      VegaValue.Str("""isValid(datum["c"]) ? datum["c"] : ""+datum["c"]"""),
      first.fields["signal"],
    )
    assertEquals(VegaValue.Str("none"), value(rule.values.last()))
  }
}
