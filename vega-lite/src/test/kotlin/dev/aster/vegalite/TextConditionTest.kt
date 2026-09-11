package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `text` channel goes through `wrapCondition` like every other channel.
 *
 * ```js
 * export function text(model: UnitModel, channel: 'text' | 'href' | 'url' | 'description' = 'text') {
 *   const channelDef = model.encoding[channel];
 *   return wrapCondition({model, channelDef, vgChannel: channel, mainRefFn: (cDef) => textRef(cDef, model.config)});
 * }
 * ```
 *
 * Its conditions are built by the same reference builder as its unconditional part and become a
 * Vega **production rule** — an array whose entries are tried in order, the last of them untested.
 * This engine read the unconditional part alone, so a label written entirely as a condition — a
 * percentage shown on the first cell of a trellis and nowhere else — came out with no text at all,
 * and a label whose condition a selection drives came out showing its fallback whatever was picked.
 * Two specifications in the wild corpus label a chart that way.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class TextConditionTest {

  private fun text(spec: String): VegaValue? {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val marks = (compiled.fields["marks"] as VegaValue.Arr).values.map { it as VegaValue.Obj }
    val mark = marks.first { it.string("type") == "text" }
    return ((mark.fields["encode"] as VegaValue.Obj).fields["update"] as VegaValue.Obj)
      .fields["text"]
  }

  private val data = """"data":{"values":[{"a":1,"b":2}]}"""

  private fun labelled(text: String, params: String = "") =
    """{$data,$params"mark":"text","encoding":{"x":{"field":"a","type":"quantitative"},
       "text":$text}}"""

  private fun json(text: String) = VegaJson.parse(text)

  /** The reported shape: a label that is only ever a condition. */
  @Test
  fun `a text channel written only as a condition is a rule of one`() {
    assertEquals(
      json("""[{"test":"datum.a > 1","signal":"datum.b + '%'"}]"""),
      text(labelled("""{"condition":{"test":"datum.a > 1","value":{"expr":"datum.b + '%'"}}}""")),
    )
  }

  /** A plain value in the condition is a value, an `{"expr": …}` being the signal above. */
  @Test
  fun `a condition may hold a plain value`() {
    assertEquals(
      json("""[{"test":"datum.a > 1","value":"yes"}]"""),
      text(labelled("""{"condition":{"test":"datum.a > 1","value":"yes"}}""")),
    )
  }

  /** A condition naming a **column** says what that column says, formatted as text is. */
  @Test
  fun `a condition may name a column`() {
    assertEquals(
      json("""[{"test":"datum.a > 1","signal":"format(datum[\"b\"], \"\")"}]"""),
      text(labelled("""{"condition":{"test":"datum.a > 1","field":"b","type":"quantitative"}}""")),
    )
  }

  /** With an unconditional part behind it, that is the rule's last and untested entry. */
  @Test
  fun `an unconditional part ends the rule`() {
    assertEquals(
      json("""[{"test":"datum.a > 1","signal":"datum.b + '%'"},{"value":"no"}]"""),
      text(
        labelled(
          """{"condition":{"test":"datum.a > 1","value":{"expr":"datum.b + '%'"}},"value":"no"}"""
        )
      ),
    )
  }

  /** Several conditions are tried in the order they were written. */
  @Test
  fun `several conditions are tried in order`() {
    assertEquals(
      json(
        """[{"test":"datum.a > 1","value":"big"},{"test":"datum.a > 0","value":"small"},
           {"value":"none"}]"""
      ),
      text(
        labelled(
          """{"condition":[{"test":"datum.a > 1","value":"big"},
                           {"test":"datum.a > 0","value":"small"}],"value":"none"}"""
        )
      ),
    )
  }

  /** A condition's own `format` is read, the reference builder being the same one. */
  @Test
  fun `a condition carries its own format`() {
    assertEquals(
      json("""[{"test":"datum.a > 1","signal":"format(datum[\"b\"], \".2f\")"},{"value":"-"}]"""),
      text(
        labelled(
          """{"condition":{"test":"datum.a > 1","field":"b","type":"quantitative",
                           "format":".2f"},"value":"-"}"""
        )
      ),
    )
  }

  /** And a condition a **selection** drives, which is what a label on a picked row is. */
  @Test
  fun `a selection may drive the label`() {
    assertEquals(
      json(
        """[{"test":"!length(data(\"sel_store\")) || vlSelectionIdTest(\"sel_store\", datum)",
            "signal":"format(datum[\"b\"], \"\")"},{"value":" "}]"""
      ),
      text(
        labelled(
          """{"condition":{"param":"sel","field":"b","type":"quantitative"},"value":" "}""",
          params = """"params":[{"name":"sel","select":"point"}],""",
        )
      ),
    )
  }

  /** A condition that says nothing is still a rule: the rows it picks are left unlabelled. */
  @Test
  fun `a condition that says nothing keeps its test`() {
    assertEquals(
      json("""[{"test":"datum.a > 1"}]"""),
      text(labelled("""{"condition":{"test":"datum.a > 1"}}""")),
    )
  }

  /** A channel with no conditions at all is the value or the column itself, not a rule. */
  @Test
  fun `a text channel with no conditions is not a rule`() {
    assertEquals(
      json("""{"signal":"format(datum[\"b\"], \"\")"}"""),
      text(labelled("""{"field":"b","type":"quantitative"}""")),
    )
  }
}
