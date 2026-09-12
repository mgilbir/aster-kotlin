package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A binding to the scales binds only what can be **panned**.
 *
 * ```js
 * if (!scale || !hasContinuousDomain(scaleType)) {
 *   log.warn(log.message.SCALE_BINDINGS_CONTINUOUS);
 *   continue;
 * }
 * ```
 *
 * `scaleBindings.parse` keeps a projection only where its scale has a continuous domain — there is
 * no halfway between two categories to drag to. A channel it leaves out is still projected, the
 * selection remembering what was picked along it, but it publishes no signal at the top of the
 * chart and pushes nothing outward:
 * ```js
 * for (const proj of selCmpt.scales) {
 *   const signal = signals.find((s) => s.name === proj.signals.data);
 *   signal.push = 'outer';
 * }
 * ```
 *
 * This compiler bound every projected channel. A chart of several views that bound a categorical
 * axis declared a signal at the top that nothing ever wrote, and pushed the view's own out to meet
 * it — so the view's own value was discarded on every pan and the axis it belonged to never moved.
 *
 * Three specifications in the wild corpus bind a categorical axis that way.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ScaleBindingContinuityTest {

  /** The chart's signals, then each group's, marking the ones that push outward. */
  private fun signals(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun names(of: VegaValue?) =
      (of as? VegaValue.Arr)?.values.orEmpty().joinToString(",") {
        it as VegaValue.Obj
        "${it.string("name")}${if (it.string("push") == "outer") "!push" else ""}"
      }
    fun walk(marks: List<VegaValue>): List<String> = marks.flatMap {
      it as VegaValue.Obj
      listOfNotNull(it.fields["signals"]?.let { own -> "${it.string("name")}:[${names(own)}]" }) +
        walk((it.fields["marks"] as? VegaValue.Arr)?.values.orEmpty())
    }
    return "top=[${names(compiled.fields["signals"])}] | " +
      walk((compiled.fields["marks"] as VegaValue.Arr).values).joinToString(" | ")
  }

  private val rows = """"data":{"values":[{"a":1,"b":"p","r":"x"}]}"""
  private val zoom =
    """"params":[{"name":"zoom","select":{"type":"interval","encodings":["x","y"]},
                  "bind":"scales"}]"""

  private fun plot(yType: String, bound: Boolean) =
    """{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
                                   "y":{"field":"b","type":"$yType"}}${if (bound) ",$zoom" else ""}}"""

  /** The reported shape: a categorical `y` is projected but not bound, so it stays put. */
  @Test
  fun `a categorical axis is projected but not bound`() {
    assertEquals(
      "top=[childWidth,concat_0_y_step,concat_0_height,concat_1_y_step,concat_1_height,unit," +
        "zoom,zoom_a] | " +
        "concat_0_group:[zoom_a!push,zoom_b,zoom_tuple,zoom_tuple_fields,zoom_translate_anchor," +
        "zoom_translate_delta,zoom_zoom_anchor,zoom_zoom_delta,zoom_modify]",
      signals(
        """{$rows,"hconcat":[${plot("nominal", bound = true)},${plot("nominal", bound = false)}]}"""
      ),
    )
  }

  /** Two continuous axes are both bound, which is the shape that must not change. */
  @Test
  fun `two continuous axes are both bound`() {
    assertEquals(
      "top=[childWidth,unit,zoom,zoom_a,zoom_b] | " +
        "concat_0_group:[zoom_a!push,zoom_b!push,zoom_tuple,zoom_tuple_fields," +
        "zoom_translate_anchor,zoom_translate_delta,zoom_zoom_anchor,zoom_zoom_delta,zoom_modify]",
      signals(
        """{$rows,"hconcat":[${plot("quantitative", bound = true)},""" +
          """${plot("quantitative", bound = false)}]}"""
      ),
    )
  }

  /**
   * With one view there is nothing above to push into, and everything stays where it is written.
   */
  @Test
  fun `one view pushes nothing`() {
    assertEquals(
      "top=[y_step,height,unit,zoom,zoom_a,zoom_b,zoom_tuple,zoom_tuple_fields," +
        "zoom_translate_anchor,zoom_translate_delta,zoom_zoom_anchor,zoom_zoom_delta,zoom_modify] | ",
      signals(
        """{$rows,"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},""" +
          """"y":{"field":"b","type":"nominal"}},$zoom}"""
      ),
    )
  }

  /**
   * The reported shape itself: a plot of a concatenation that grids its cell, binding a continuous
   * `x` and a categorical `y`. Only the `x` is declared at the top and only the `x` pushes.
   */
  @Test
  fun `a gridded cell binds only its continuous axis`() {
    assertEquals(
      "top=[concat_0_child_width,concat_0_child_y_step,unit,zoom,zoom_a] | " +
        "concat_0_cell:[facet,zoom_a!push,zoom_b,zoom_tuple,zoom_tuple_fields," +
        "zoom_translate_anchor,zoom_translate_delta,zoom_zoom_anchor,zoom_zoom_delta,zoom_modify]",
      signals(
        """{$rows,"hconcat":[{"mark":"point",
           "encoding":{"x":{"field":"a","type":"quantitative"},
                       "y":{"field":"b","type":"nominal"},
                       "row":{"field":"r","type":"nominal"}},
           $zoom,"resolve":{"scale":{"y":"independent"}}}]}"""
      ),
    )
  }
}
