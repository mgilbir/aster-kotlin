package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A binding declared **above** a composition drives every plot in it.
 *
 * ```js
 * const scale = model.getScaleComponent(channel);
 * …
 * scale.set('selectionExtent', {param: selCmpt.name, field: proj.field}, true);
 * ```
 *
 * `scaleBindings.parse` runs once per unit, and a parameter written at the top of a chart is pushed
 * into every unit below it. So every plot's position scale carries the extent, every plot reads it
 * back as `domainRaw`, and `scaleClip` then clips every plot's marks — a pan moves the whole
 * dashboard at once. The top-level signals are assembled the same way: `topLevelSignals` is called
 * per unit and appends what it does not already have, "no single selCmpt has a global view".
 *
 * This compiler answered for the **first** plot alone. One plot panned and the rest stood still,
 * their marks unclipped and spilling past their own edges, and a field only the second plot is
 * scaled by had no signal at the top to be dragged by at all.
 *
 * A binding a plot declares for itself still drives that plot alone, which is the other half of the
 * rule.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class BindingAboveAConcatTest {

  private fun compiled(spec: String) =
    VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
      as VegaValue.Obj

  /** Each scale, marked `!raw` where a binding drives it. */
  private fun scales(spec: String): String =
    (compiled(spec).fields["scales"] as VegaValue.Arr).values.joinToString(" ") {
      it as VegaValue.Obj
      "${it.string("name")}${if (it.has("domainRaw")) "!raw" else ""}"
    }

  /** The chart's own signals, by name. */
  private fun signals(spec: String): String =
    (compiled(spec).fields["signals"] as VegaValue.Arr).values.joinToString(" ") {
      (it as VegaValue.Obj).string("name").orEmpty()
    }

  /** Each drawing mark, marked `!clip` where it is clipped. */
  private fun marks(spec: String): String {
    fun walk(marks: List<VegaValue>): List<String> = marks.flatMap {
      it as VegaValue.Obj
      val clipped = if (it.fields["clip"] == VegaValue.Bool(true)) "!clip" else ""
      (if (it.string("type") == "group") emptyList() else listOf("${it.string("name")}$clipped")) +
        walk((it.fields["marks"] as? VegaValue.Arr)?.values.orEmpty())
    }
    return walk((compiled(spec).fields["marks"] as VegaValue.Arr).values).joinToString(" ")
  }

  private val grid = """{"name":"grid","select":"interval","bind":"scales"}"""

  private fun plot(y: String) =
    """{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},"y":$y}}"""

  private val measured = """{"field":"b","type":"quantitative"}"""
  private val other = """{"field":"c","type":"quantitative"}"""
  private val categories = """{"field":"k","type":"nominal"}"""

  /** The reported shape: declared over the chart, so both plots are driven. */
  private val above = """{"params":[$grid],"hconcat":[${plot(measured)},${plot(other)}]}"""

  @Test
  fun `a binding above a concatenation drives every plot's scales`() {
    assertEquals("concat_0_x!raw concat_0_y!raw concat_1_x!raw concat_1_y!raw", scales(above))
  }

  @Test
  fun `and publishes a signal for every field any plot is scaled by`() {
    assertEquals("childWidth unit grid grid_a grid_b grid_c", signals(above))
  }

  @Test
  fun `and clips every plot, so a pan draws nothing past its own edge`() {
    assertEquals("concat_0_marks!clip concat_1_marks!clip", marks(above))
  }

  /** A plot that scales a category along `y` is not panned there: the channel is passed over. */
  @Test
  fun `a categorical axis is left unbound in the plot that has one`() {
    val mixed = """{"params":[$grid],"hconcat":[${plot(measured)},${plot(categories)}]}"""
    assertEquals("concat_0_x!raw concat_0_y!raw concat_1_x!raw concat_1_y", scales(mixed))
    assertEquals(
      "childWidth concat_0_height concat_1_y_step concat_1_height unit grid grid_a grid_b",
      signals(mixed),
    )
  }

  /** A binding one plot declares drives that plot and no other, which is what must not change. */
  @Test
  fun `a binding inside one plot drives that plot alone`() {
    val inside =
      """{"hconcat":[{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
           "y":$measured},"params":[$grid]},${plot(other)}]}"""
    assertEquals("concat_0_x!raw concat_0_y!raw concat_1_x concat_1_y", scales(inside))
    assertEquals("childWidth unit grid grid_a grid_b", signals(inside))
    assertEquals("concat_0_marks!clip concat_1_marks", marks(inside))
  }
}
