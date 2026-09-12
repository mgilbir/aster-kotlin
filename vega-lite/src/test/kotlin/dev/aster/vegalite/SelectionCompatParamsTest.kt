package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A unit that writes both spellings keeps only what its `selection` block converts to.
 *
 * ```js
 * const {selection, ...rest} = spec as any;
 * if (selection) {
 *   return {
 *     ...rest,
 *     params: entries(selection).map(([name, selDef]) => {
 * ```
 *
 * The spread carries the unit's old `params` into the object and the `params:` written after it
 * **overwrites** them. So a chart that mixes the version 4 `selection` block with a version 5
 * parameter is drawn with the converted selections and nothing else — the parameter it wrote beside
 * them is not built at all.
 *
 * This engine appended instead, so such a chart came out with machinery upstream never builds: a
 * store, the tuple and modify signals that follow it, and the cell signals beside them. Three
 * specifications in the wild corpus mix the two spellings on one view.
 *
 * The **chart's own** parameters are the exception, and only those that select nothing:
 * `extractTopLevelProperties(inputSpec, true)` reads them off the specification as written, before
 * any normalizer runs, so a slider declared beside a `selection` at the top of a chart is still a
 * slider. One that selects is not — the unit is where a selection is built, and the unit's list is
 * the one being replaced.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class SelectionCompatParamsTest {

  /** Every store, every signal the chart declares, and every signal inside a group. */
  private fun machinery(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun names(value: VegaValue?) =
      (value as? VegaValue.Arr)?.values.orEmpty().map { (it as VegaValue.Obj).string("name") }
    val inside =
      (compiled.fields["marks"] as VegaValue.Arr).values.flatMap {
        names((it as VegaValue.Obj).fields["signals"])
      }
    return "data=${names(compiled.fields["data"])} " +
      "signals=${names(compiled.fields["signals"])} inside=$inside"
  }

  private val data = """"data":{"values":[{"a":1,"b":2,"c":"x"}]}"""
  private val encoding =
    """"encoding":{"x":{"field":"a","type":"quantitative"},
       "y":{"field":"b","type":"quantitative"}}"""
  private val hover =
    """{"name":"hover","select":{"type":"point","fields":["b"],"on":"mouseover"}}"""
  private val slider = """{"name":"w","value":5,"bind":{"input":"range","min":1,"max":10}}"""
  private val selection = """"selection":{"pick":{"type":"single","fields":["a"]}}"""
  private val plain = """{"mark":"point",$encoding}"""

  /** The reported shape: a parameter written beside a version 4 selection is not built. */
  @Test
  fun `a selection block replaces the unit's own parameters`() {
    assertEquals(
      "data=[pick_store, source_0, data_0] " +
        "signals=[unit, pick, pick_tuple, pick_tuple_fields, pick_modify] inside=[]",
      machinery("""{$data,"mark":"point",$encoding,"params":[$hover],$selection}"""),
    )
  }

  /** The same parameter on its own is built, which is the whole of what must not change. */
  @Test
  fun `a parameter with no selection beside it is built`() {
    assertEquals(
      "data=[hover_store, source_0, data_0] " +
        "signals=[unit, hover, hover_tuple, hover_tuple_fields, hover_toggle, hover_modify] " +
        "inside=[]",
      machinery("""{$data,"mark":"point",$encoding,"params":[$hover]}"""),
    )
  }

  /** A slider at the **top** of a chart is read off the specification and survives. */
  @Test
  fun `a chart's own slider survives a selection block`() {
    assertEquals(
      "data=[pick_store, source_0, data_0] " +
        "signals=[unit, pick, w, pick_tuple, pick_tuple_fields, pick_modify] inside=[]",
      machinery("""{$data,"mark":"point",$encoding,"params":[$slider],$selection}"""),
    )
  }

  /** A slider on a **plot** of a concatenation is the plot's own list, and goes with it. */
  @Test
  fun `a plot's slider goes with the rest of its parameters`() {
    assertEquals(
      "data=[pick_store, source_0, data_0] signals=[childWidth, unit, pick] " +
        "inside=[pick_tuple, pick_tuple_fields, pick_modify]",
      machinery(
        """{$data,"hconcat":[{"mark":"point",$encoding,"params":[$slider],$selection},$plain]}"""
      ),
    )
  }

  /** And a selecting parameter on such a plot goes the same way. */
  @Test
  fun `a plot's own selection parameter goes too`() {
    assertEquals(
      "data=[pick_store, source_0, data_0] signals=[childWidth, unit, pick] " +
        "inside=[pick_tuple, pick_tuple_fields, pick_modify]",
      machinery(
        """{$data,"hconcat":[{"mark":"point",$encoding,"params":[$hover],$selection},$plain]}"""
      ),
    )
  }

  /** A **sibling**'s parameters are untouched: the replacement is the unit's own list. */
  @Test
  fun `a sibling plot keeps its parameters`() {
    assertEquals(
      "data=[pick_store, hover_store, source_0, data_0] " +
        "signals=[childWidth, unit, hover, pick] " +
        "inside=[hover_tuple, hover_tuple_fields, hover_toggle, hover_modify, pick_tuple, " +
        "pick_tuple_fields, pick_modify]",
      machinery(
        """{$data,"hconcat":[{"mark":"point",$encoding,"params":[$hover]},
           {"mark":"point",$encoding,$selection}]}"""
      ),
    )
  }

  /** A member of a layer is a unit like any other. */
  @Test
  fun `a layer member's parameters are replaced too`() {
    assertEquals(
      "data=[pick_store, source_0, data_0] " +
        "signals=[unit, pick, pick_tuple, pick_tuple_fields, pick_modify] inside=[]",
      machinery("""{$data,"layer":[{"mark":"point",$encoding,"params":[$hover],$selection}]}"""),
    )
  }
}
