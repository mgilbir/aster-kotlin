package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `unit` is unshifted after one view's selections, so a later view's controls go in front of it.
 *
 * ```js
 * for (const selCmpt of vals(model.component.selection ?? {})) {
 *   ...
 *   for (const c of selectionCompilers) {
 *     if (c.defined(selCmpt) && c.topLevelSignals) {
 *       signals = c.topLevelSignals(model, selCmpt, signals);   // legend and input bindings unshift
 *     }
 *   }
 * }
 * if (hasSelections) {
 *   const hasUnit = signals.filter((s) => s.name === 'unit');
 *   if (hasUnit.length === 0) { signals.unshift({name: 'unit', value: {}, on: [...]}); }
 * }
 * ```
 *
 * `assembleTopLevelSignals` runs once per **view**, on one accumulating array. A view's controls
 * are unshifted onto the front as its selections are walked, and `unit` is unshifted after that
 * loop — but only where it is not there already. So `unit` lands in front of the first
 * selection-bearing view's controls and behind every later view's, which go on being unshifted past
 * it.
 *
 * This compiler wrote `unit` first always, so a concatenation whose *second* plot binds a legend
 * listed the two the other way about. One specification in the wild corpus binds a legend that way.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ControlSignalOrderTest {

  /** The chart's signals, by name. */
  private fun signals(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["signals"] as? VegaValue.Arr)?.values.orEmpty().joinToString(",") {
      (it as VegaValue.Obj).string("name").orEmpty()
    }
  }

  private val rows = """"data":{"values":[{"a":1,"b":2,"c":"x"}]}"""
  private val enc =
    """"encoding":{"x":{"field":"a","type":"quantitative"},
                   "y":{"field":"b","type":"quantitative"},
                   "color":{"field":"c","type":"nominal"}}"""
  private val bound = """{"name":"p","select":{"type":"point","fields":["c"]},"bind":"legend"}"""
  private val plain = """{"name":"q","select":{"type":"point"}}"""

  /** One view: `unit` is unshifted last and so stands first. */
  @Test
  fun `a chart's own control stands behind unit`() {
    assertEquals(
      "unit,p_c_legend,p,p_tuple,p_tuple_fields,p_toggle,p_modify",
      signals("""{$rows,"mark":"point",$enc,"params":[$bound]}"""),
    )
  }

  /** The reported shape: the **second** plot binds the legend, so its control goes in front. */
  @Test
  fun `a later plot's control stands ahead of unit`() {
    assertEquals(
      "childWidth,p_c_legend,unit,q,p",
      signals(
        """{$rows,"hconcat":[{"mark":"point",$enc,"params":[$plain]},
             {"mark":"point",$enc,"params":[$bound]}]}"""
      ),
    )
  }

  /** And the **first** plot's stands behind it, `unit` having been unshifted after its loop. */
  @Test
  fun `the first plot's control stands behind unit`() {
    assertEquals(
      "childWidth,unit,p_c_legend,p,q",
      signals(
        """{$rows,"hconcat":[{"mark":"point",$enc,"params":[$bound]},
             {"mark":"point",$enc,"params":[$plain]}]}"""
      ),
    )
  }
}
