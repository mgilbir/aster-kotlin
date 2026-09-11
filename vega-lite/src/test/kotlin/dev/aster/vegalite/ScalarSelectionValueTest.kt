package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A selection's value may be a **scalar**, and then it settles every projection.
 *
 * ```js
 * selCmpt.init = init.map((v) => {
 *   // Selections can be initialized either with a full object that maps projections to values
 *   // or scalar values to smoothen the abstraction gradient from variable params to point selections.
 *   return proj.items.map((p) =>
 *     isObject(v) ? (v[p.geoChannel || p.channel] !== undefined ? v[p.geoChannel || p.channel] : v[p.field]) : v,
 *   );
 * });
 * ```
 *
 * A tuple names the channel a projection is over or the column it reads. A scalar names neither —
 * `{"value": "US"}` beside `"fields": ["cont"]` — and there is only one thing it could mean.
 *
 * This engine read every value as a tuple, so a scalar one found nothing in it: the chart opened
 * with nothing picked and, where a control was bound to the selection, with the control empty. Five
 * specifications in the wild corpus open that way, all of them a picker over one column.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ScalarSelectionValueTest {

  private fun compiled(spec: String) =
    VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
      as VegaValue.Obj

  /** The store's opening rows. */
  private fun store(spec: String): VegaValue? =
    (compiled(spec).fields["data"] as VegaValue.Arr)
      .values
      .map { it as VegaValue.Obj }
      .first { it.string("name")?.endsWith("_store") == true }
      .fields["values"]

  /** The bound controls, as `name=init`. */
  private fun bound(spec: String): List<String> =
    (compiled(spec).fields["signals"] as? VegaValue.Arr)
      ?.values
      .orEmpty()
      .map { it as VegaValue.Obj }
      .filter { it.has("bind") }
      .map { "${it.string("name")}=${it.string("init") ?: "value:null"}" }

  private val data = """"data":{"values":[{"a":1,"c":"US","t":"x"}]}"""
  private val plot = """"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"}}"""

  private fun chart(param: String) = """{$data,"params":[$param],$plot}"""

  private fun json(text: String) = VegaJson.parse(text)

  /** The reported shape: a picker over one column, opening at one of its values. */
  @Test
  fun `a scalar value opens the control and the store`() {
    val spec =
      chart(
        """{"name":"View","select":{"type":"point","fields":["c"]},
           "bind":{"input":"select","options":["UK","US"]},"value":"US"}"""
      )
    assertEquals(
      json("""[{"unit":"","fields":[{"type":"E","field":"c"}],"values":["US"]}]"""),
      store(spec),
    )
    assertEquals(listOf("""View_c="US""""), bound(spec))
  }

  /** With no control it is still the row the chart opens with. */
  @Test
  fun `a scalar value opens the store on its own`() {
    assertEquals(
      json("""[{"unit":"","fields":[{"type":"E","field":"c"}],"values":["US"]}]"""),
      store(chart("""{"name":"View","select":{"type":"point","fields":["c"]},"value":"US"}""")),
    )
  }

  /** It settles **every** projection, there being nothing in it to tell them apart. */
  @Test
  fun `a scalar value settles both projections`() {
    assertEquals(
      json(
        """[{"unit":"","fields":[{"type":"E","field":"c"},{"type":"E","field":"t"}],
            "values":["US","US"]}]"""
      ),
      store(chart("""{"name":"View","select":{"type":"point","fields":["c","t"]},"value":"US"}""")),
    )
  }

  /** A scalar in a list is a list of one scalar, as any other value is. */
  @Test
  fun `a scalar value in a list is read the same way`() {
    assertEquals(
      json("""[{"unit":"","fields":[{"type":"E","field":"c"}],"values":["US"]}]"""),
      store(chart("""{"name":"View","select":{"type":"point","fields":["c"]},"value":["US"]}""")),
    )
  }

  /** A tuple still names its column, which is the shape a selection over two columns needs. */
  @Test
  fun `a tuple still names its column`() {
    assertEquals(
      json(
        """[{"unit":"","fields":[{"type":"E","field":"c"},{"type":"E","field":"t"}],
            "values":["US","x"]}]"""
      ),
      store(
        chart(
          """{"name":"View","select":{"type":"point","fields":["c","t"]},
             "value":[{"c":"US","t":"x"}]}"""
        )
      ),
    )
  }
}
