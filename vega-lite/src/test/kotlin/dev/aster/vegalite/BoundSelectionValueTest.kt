package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A selection bound to a control opens at the value it was given.
 *
 * ```js
 * const init = selCmpt.init?.[0]; // Can only exist on single selections (one initial value).
 * ...
 * signals.unshift({
 *   name: sgname,
 *   ...(init ? {init: assembleInit(init[i])} : {value: null}),
 * ```
 *
 * The value is a **list** of tuples — `array(selDef.value)` in `parseSelectionProject` — of which a
 * bound control shows the first, and a lone tuple is a list of one. That is how a Vega-Lite 4
 * selection arrives: its `init` is a single object, and the compatibility pass hands it over as the
 * parameter's `value` unchanged. This engine read only the list form, so such a control started at
 * nothing — a chart that opens showing every row where the specification asked for one, which is a
 * different chart before anybody touches it. Six specifications in the wild corpus open that way.
 *
 * The tuple is read by **channel first and then by column**, and what comes out is an expression: a
 * string quoted, a number as it stands, an instant as the `datetime(…)` that builds it.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class BoundSelectionValueTest {

  /** The bound signals, as `name=init` or `name=value`. */
  private fun bound(spec: String): List<String> {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["signals"] as? VegaValue.Arr)
      ?.values
      .orEmpty()
      .map { it as VegaValue.Obj }
      .filter { it.has("bind") }
      .map {
        val init = it.string("init")
        "${it.string("name")}=" + (init?.let { text -> "init:$text" } ?: "value:null")
      }
  }

  private val data = """"data":{"values":[{"c":"x","a":1,"t":"2020-01-01"}]}"""
  private val bind = """"bind":{"input":"select","options":["x","y"]}"""
  private val plot = """"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"}}"""

  private fun params(select: String, value: String = "") =
    """{$data,"params":[{"name":"sel","select":$select,$bind$value}],$plot}"""

  /** The reported shape, in the spelling a Vega-Lite 4 chart uses. */
  @Test
  fun `a version 4 selection opens at its init`() {
    assertEquals(
      listOf("""sel_c=init:"x""""),
      bound(
        """{$data,"selection":{"sel":{"type":"single","fields":["c"],"init":{"c":"x"},
           $bind}},$plot}"""
      ),
    )
  }

  /** And in the parameter spelling, where the value is a list of tuples. */
  @Test
  fun `a parameter opens at the first of its values`() {
    assertEquals(
      listOf("""sel_c=init:"x""""),
      bound(params("""{"type":"point","fields":["c"]}""", ""","value":[{"c":"x"}]""")),
    )
  }

  /** Where there are several, the control shows the first: it has one value to show. */
  @Test
  fun `a control shows the first tuple`() {
    assertEquals(
      listOf("""sel_c=init:"x""""),
      bound(params("""{"type":"point","fields":["c"]}""", ""","value":[{"c":"x"},{"c":"y"}]""")),
    )
  }

  /** With nothing given, the control starts empty and says so. */
  @Test
  fun `a control given nothing starts at nothing`() {
    assertEquals(
      listOf("sel_c=value:null"),
      bound(params("""{"type":"point","fields":["c"]}""")),
    )
  }

  /** A number stands as it is written, being an expression already. */
  @Test
  fun `a number opens the control as it stands`() {
    assertEquals(
      listOf("sel_a=init:5"),
      bound(params("""{"type":"point","fields":["a"]}""", ""","value":[{"a":5}]""")),
    )
  }

  /** An instant is the expression that builds it, a control having no other way to hold one. */
  @Test
  fun `an instant opens the control as the expression that builds it`() {
    assertEquals(
      listOf("sel_t=init:datetime(2020, 0, 1, 0, 0, 0, 0)"),
      bound(
        params(
          """{"type":"point","fields":["t"]}""",
          ""","value":[{"t":{"year":2020,"month":1,"date":1}}]""",
        )
      ),
    )
  }

  /** One tuple settles every projection, each control taking its own column out of it. */
  @Test
  fun `one tuple opens every control`() {
    assertEquals(
      listOf("sel_a=init:5", """sel_c=init:"x""""),
      bound(params("""{"type":"point","fields":["c","a"]}""", ""","value":[{"c":"x","a":5}]""")),
    )
  }

  /** And a tuple may name the **channel** a projection is over rather than its column. */
  @Test
  fun `a tuple may name the channel`() {
    assertEquals(
      listOf("sel_a=init:5"),
      bound(params("""{"type":"point","encodings":["x"]}""", ""","value":[{"x":5}]""")),
    )
  }
}
