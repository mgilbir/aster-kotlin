package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A summary of the continuous axis is of that axis's **kind**.
 *
 * ```js
 * return {
 *   field: fieldPrefix + continuousAxisChannelDef.field,
 *   type: continuousAxisChannelDef.type,
 *   title: …,
 * };
 * ```
 *
 * `getCompositeMarkTooltip` types every entry of a composite mark's tooltip from the axis it
 * summarises, so a box plot of instants reads its quartiles back as dates and a whisker of instants
 * reads its ends as dates. Written as quantities, the tooltip showed five epoch integers — and,
 * because what the encoding says a column is decides how it is parsed, nothing asked for the
 * summary to be read as a date at all, so the box was drawn and compared as numbers too.
 *
 * One specification in the wild corpus box-plots an instant.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class SummaryTooltipKindTest {

  /** Each mark's tooltip, as the expression Vega is given. */
  private fun tooltips(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun walk(marks: List<VegaValue>): List<String> = marks.flatMap {
      it as VegaValue.Obj
      val own = it.obj("encode")?.obj("update")?.obj("tooltip")?.string("signal")
      listOfNotNull(
        own?.let { text -> "${it.string("name")}: ${text.substringBefore(", \"g\"")}" }
      ) + walk((it.fields["marks"] as? VegaValue.Arr)?.values.orEmpty())
    }
    return walk((compiled.fields["marks"] as VegaValue.Arr).values).joinToString("\n")
  }

  private val rows = """"data":{"values":[{"t":"2020-01-01","g":"a","n":1}]}"""

  private fun summarised(mark: String, field: String, type: String) =
    """{$rows,"mark":"$mark",
        "encoding":{"x":{"field":"$field","type":"$type"},
                    "y":{"field":"g","type":"nominal"}}}"""

  /** The reported shape: a box plot of instants reads its five-number summary back as dates. */
  @Test
  fun `a box plot of instants reads its summary as dates`() {
    assertEquals(
      """layer_0_layer_1_layer_0_marks: {"Upper Whisker of t": timeFormat(datum["upper_whisker_t"], "%b %d, %Y"), "Lower Whisker of t": timeFormat(datum["lower_whisker_t"], "%b %d, %Y")
layer_0_layer_1_layer_1_marks: {"Upper Whisker of t": timeFormat(datum["upper_whisker_t"], "%b %d, %Y"), "Lower Whisker of t": timeFormat(datum["lower_whisker_t"], "%b %d, %Y")
layer_1_layer_0_marks: {"Max of t": timeFormat(datum["max_t"], "%b %d, %Y"), "Q3 of t": timeFormat(datum["upper_box_t"], "%b %d, %Y"), "Median of t": timeFormat(datum["mid_box_t"], "%b %d, %Y"), "Q1 of t": timeFormat(datum["lower_box_t"], "%b %d, %Y"), "Min of t": timeFormat(datum["min_t"], "%b %d, %Y")
layer_1_layer_1_marks: {"Max of t": timeFormat(datum["max_t"], "%b %d, %Y"), "Q3 of t": timeFormat(datum["upper_box_t"], "%b %d, %Y"), "Median of t": timeFormat(datum["mid_box_t"], "%b %d, %Y"), "Q1 of t": timeFormat(datum["lower_box_t"], "%b %d, %Y"), "Min of t": timeFormat(datum["min_t"], "%b %d, %Y")""",
      tooltips(summarised("boxplot", "t", "temporal")),
    )
  }

  /** An error bar reads its centre and its ends the same way. */
  @Test
  fun `an error bar of instants reads its ends as dates`() {
    assertEquals(
      """marks: {"Mean of t": timeFormat(datum["center_t"], "%b %d, %Y"), """ +
        """"Mean + stderr of t": timeFormat(datum["upper_t"], "%b %d, %Y"), """ +
        """"Mean - stderr of t": timeFormat(datum["lower_t"], "%b %d, %Y")""",
      tooltips(summarised("errorbar", "t", "temporal")),
    )
  }

  /** A box plot of numbers reads its summary as numbers, which is what already worked. */
  @Test
  fun `a box plot of numbers reads its summary as numbers`() {
    assertEquals(
      """layer_0_layer_1_layer_0_marks: {"Upper Whisker of n": format(datum["upper_whisker_n"], ""), "Lower Whisker of n": format(datum["lower_whisker_n"], "")
layer_0_layer_1_layer_1_marks: {"Upper Whisker of n": format(datum["upper_whisker_n"], ""), "Lower Whisker of n": format(datum["lower_whisker_n"], "")
layer_1_layer_0_marks: {"Max of n": format(datum["max_n"], ""), "Q3 of n": format(datum["upper_box_n"], ""), "Median of n": format(datum["mid_box_n"], ""), "Q1 of n": format(datum["lower_box_n"], ""), "Min of n": format(datum["min_n"], "")
layer_1_layer_1_marks: {"Max of n": format(datum["max_n"], ""), "Q3 of n": format(datum["upper_box_n"], ""), "Median of n": format(datum["mid_box_n"], ""), "Q1 of n": format(datum["lower_box_n"], ""), "Min of n": format(datum["min_n"], "")""",
      tooltips(summarised("boxplot", "n", "quantitative")),
    )
  }
}
