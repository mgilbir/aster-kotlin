package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Independence is asked of **every level**, and a level below may still share what the chart split.
 *
 * ```js
 * resolve.scale[channel] ??= defaultScaleResolve(channel, model);
 * if (resolve.scale[channel] === 'shared') { ... merge ... }
 * ```
 *
 * `parseScaleCore` runs per model down the tree, so a concatenation of concatenations is two
 * questions. Colour defaults to shared everywhere: a chart that states `"resolve": {"scale":
 * {"color": "independent"}}` over a column whose second entry is a *row* of plots gives that row
 * **one** colour scale, named for the row and drawn with one legend. Positions default to
 * independent at every level and so do go all the way down.
 *
 * Named from the innermost plot regardless, such a chart came out with a colour scale — and a
 * legend — for every plot in the row, where the specification asked for one for the row.
 *
 * Independence a **disagreement** forces is not this rule: there the owner is the child of the
 * level whose children disagreed, whatever any `resolve` says, and the neighbouring test below
 * holds it.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class DeclaredIndependenceTest {

  /** Every scale by name, sorted, and which scale each mark and legend reads. */
  private fun wiring(spec: String, keys: Boolean = true): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val scales =
      (compiled.fields["scales"] as? VegaValue.Arr)
        ?.values
        .orEmpty()
        .map { (it as VegaValue.Obj).string("name").orEmpty() }
        .sorted()
        .joinToString(",")
    fun walk(mark: VegaValue.Obj, path: String): List<String> =
      listOfNotNull(
        mark.obj("encode")?.obj("update")?.obj("stroke")?.string("scale")?.let { "$path:$it" }
      ) +
        (mark.fields["marks"] as? VegaValue.Arr)?.values.orEmpty().flatMapIndexed { index, child ->
          walk(child as VegaValue.Obj, "$path.$index")
        } +
        (if (!keys) emptyList()
        else
          (mark.fields["legends"] as? VegaValue.Arr)?.values.orEmpty().map {
            it as VegaValue.Obj
            "$path!legend(${it.string("stroke") ?: it.string("fill")})"
          })
    val marks =
      (compiled.fields["marks"] as VegaValue.Arr)
        .values
        .flatMapIndexed { index, mark -> walk(mark as VegaValue.Obj, "$index") }
        .joinToString(",")
    val top =
      (if (!keys) emptyList() else (compiled.fields["legends"] as? VegaValue.Arr)?.values.orEmpty())
        .joinToString(",") {
          it as VegaValue.Obj
          "!legend(${it.string("stroke") ?: it.string("fill")})"
        }
    return "[$scales] $marks $top"
  }

  private val rows = """"data":{"values":[{"a":1,"b":2,"k":"x"}]}"""
  private val plot =
    """{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
                                   "y":{"field":"b","type":"quantitative"},
                                   "color":{"field":"k","type":"nominal"}}}"""

  /**
   * The reported shape: the row below the chart shares one colour scale, and both its plots read
   * it.
   *
   * The keys are left out of this one. Upstream draws the row's key on the **row's** group, where
   * this compiler has nowhere to put a legend but a plot and draws it on the row's first — a gap of
   * its own, in guide assembly rather than in this rule, and the last thing between one
   * specification in the wild corpus and agreement.
   */
  @Test
  fun `a nested row shares what the chart split`() {
    assertEquals(
      "[concat_0_color,concat_0_x,concat_0_y,concat_1_color,concat_1_concat_0_x," +
        "concat_1_concat_0_y,concat_1_concat_1_x,concat_1_concat_1_y] " +
        "0.0:concat_0_color,1.0.0:concat_1_color,1.1.0:concat_1_color ",
      wiring(
        """{$rows,"resolve":{"scale":{"color":"independent"}},
           "vconcat":[$plot,{"hconcat":[$plot,$plot]}]}""",
        keys = false,
      ),
    )
  }

  /** Where the row says so **too**, the split goes all the way down. */
  @Test
  fun `a row that splits again splits again`() {
    assertEquals(
      "[concat_0_color,concat_0_x,concat_0_y,concat_1_concat_0_color,concat_1_concat_0_x," +
        "concat_1_concat_0_y,concat_1_concat_1_color,concat_1_concat_1_x,concat_1_concat_1_y] " +
        "0.0:concat_0_color,0!legend(concat_0_color),1.0.0:concat_1_concat_0_color," +
        "1.0!legend(concat_1_concat_0_color),1.1.0:concat_1_concat_1_color," +
        "1.1!legend(concat_1_concat_1_color) ",
      wiring(
        """{$rows,"resolve":{"scale":{"color":"independent"}},
           "vconcat":[$plot,{"resolve":{"scale":{"color":"independent"}},
                             "hconcat":[$plot,$plot]}]}"""
      ),
    )
  }

  /** A flat concatenation has one level to ask, which is the shape that must not change. */
  @Test
  fun `a flat concatenation splits per plot`() {
    assertEquals(
      "[concat_0_color,concat_0_x,concat_0_y,concat_1_color,concat_1_x,concat_1_y] " +
        "0.0:concat_0_color,0!legend(concat_0_color),1.0:concat_1_color," +
        "1!legend(concat_1_color) ",
      wiring("""{$rows,"resolve":{"scale":{"color":"independent"}},"vconcat":[$plot,$plot]}"""),
    )
  }

  /** With nothing declared, colour is shared by the whole chart however deeply it nests. */
  @Test
  fun `an undeclared colour is the chart's own`() {
    assertEquals(
      "[color,concat_0_x,concat_0_y,concat_1_concat_0_x,concat_1_concat_0_y," +
        "concat_1_concat_1_x,concat_1_concat_1_y] " +
        "0.0:color,1.0.0:color,1.1.0:color !legend(color)",
      wiring("""{$rows,"vconcat":[$plot,{"hconcat":[$plot,$plot]}]}"""),
    )
  }
}
