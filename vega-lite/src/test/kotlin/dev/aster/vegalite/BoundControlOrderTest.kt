package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The controls a chart is driven by come out in **reverse** order of declaration.
 *
 * `inputBindings.topLevelSignals` walks a selection's projections and *unshifts* a signal for each:
 * ```js
 * proj.items.forEach((p, i) => {
 *   const sgname = varName(`${name}_${p.field}`);
 *   if (!signals.filter((s) => s.name === sgname).length) {
 *     signals.unshift({name: sgname, …});
 *   }
 * });
 * ```
 *
 * `assembleTopLevelSignals` walks the selections in declaration order and each control goes onto
 * the **front** of the list, so the last parameter's control is written first and a selection
 * projecting onto two fields writes the second one first. `bindLegend` unshifts the same way.
 *
 * It is not a detail of the emitted JSON: Vega renders bound inputs in the order it is given them,
 * so a reader looking down a column of drop-downs sees them in this order. Four specifications in
 * the wild corpus differed for it, each with two or three bound parameters.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class BoundControlOrderTest {

  private fun signals(params: String): List<String> {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":"x","d":"y"}]},
               "params":[$params],
               "mark":"point",
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["signals"] as VegaValue.Arr).values.mapNotNull {
      (it as? VegaValue.Obj)?.string("name")
    }
  }

  private fun bound(field: String, name: String) =
    """{"name":"$name","select":{"type":"point","fields":["$field"]},
        "bind":{"input":"select","options":["x"]}}"""

  /** The reported shape: two parameters, each with a control of its own. */
  @Test
  fun `two controls are written last one first`() {
    assertEquals(
      listOf("unit", "p2_d", "p1_c", "p1", "p2"),
      signals("${bound("c", "p1")},${bound("d", "p2")}").take(5),
    )
  }

  /** Three, so the order is a reversal rather than a swap. */
  @Test
  fun `three controls are written in reverse`() {
    assertEquals(
      listOf("unit", "p3_a", "p2_d", "p1_c"),
      signals("${bound("c", "p1")},${bound("d", "p2")},${bound("a", "p3")}").take(4),
    )
  }

  /** One selection projecting onto two fields reverses within itself for the same reason. */
  @Test
  fun `two fields of one selection are written second one first`() {
    assertEquals(
      listOf("unit", "p1_d", "p1_c", "p1"),
      signals(
          """{"name":"p1","select":{"type":"point","fields":["c","d"]},
              "bind":{"input":"select","options":["x"]}}"""
        )
        .take(4),
    )
  }

  /** And one control is one control, whichever way the list is walked. */
  @Test
  fun `a single control is unaffected`() {
    assertEquals(listOf("unit", "p1_c", "p1"), signals(bound("c", "p1")).take(3))
  }
}
