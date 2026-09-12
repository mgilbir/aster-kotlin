package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A size a row of plots merges on may be `"container"`, and then it is a **signal**.
 *
 * ```js
 * layoutSignals = layoutSignals.filter((signal) => {
 *   if ((signal.name === 'width' || signal.name === 'height') && signal.value !== undefined) {
 *     topLevelProperties[signal.name] = +signal.value;
 *     return false;
 *   }
 *   return true;
 * });
 * ```
 *
 * The hoist upstream does at the end is for a signal **carrying a value**: a plain number named
 * `width` is the chart's width and is written as one. A `"container"` size has no number to hoist —
 * the page has to be measured first — so it stays a signal, measured at first render and again on
 * every resize.
 *
 * `parseUnitLayoutSize` keeps the string as the layout size, which is what a level above compares
 * when it merges its children: two plots asking the page for their width **agree**, and what they
 * agree on is to ask the page. This engine answered with the view's own default instead, merged
 * them on that number and wrote it out as the chart's width — so such a chart had a width of its
 * own and never measured the element it was drawn in. Four specifications in the wild corpus are a
 * column of plots each asking the page for its width.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class MergedContainerSizeTest {

  private fun compiled(spec: String) =
    VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
      as VegaValue.Obj

  /** The chart's own width and height, and every signal that is one of the two. */
  private fun sizes(spec: String): String {
    val chart = compiled(spec)
    val signals =
      (chart.fields["signals"] as? VegaValue.Arr)
        ?.values
        .orEmpty()
        .map { it as VegaValue.Obj }
        .filter {
          it.string("name")?.contains("width", ignoreCase = true) == true ||
            it.string("name")?.contains("height", ignoreCase = true) == true
        }
    return "width=${VegaJson.write(chart.fields["width"] ?: VegaValue.Null)}" +
      " height=${VegaJson.write(chart.fields["height"] ?: VegaValue.Null)}" +
      " signals=" +
      signals.joinToString(",") { VegaJson.write(it).replace(Regex("""\n\s*"""), "") }
  }

  private fun plot(width: String, height: String) =
    """{"width":$width,"height":$height,"mark":"point",
       "encoding":{"x":{"field":"a","type":"quantitative"},
                   "y":{"field":"b","type":"quantitative"}}}"""

  private val data = """"data":{"values":[{"a":1,"b":2}]}"""

  /** The reported shape: a column of plots, each asking the page for its width. */
  @Test
  fun `a column of container widths is measured by a signal`() {
    assertEquals(
      "width=null height=null signals=" +
        """{"name": "width","init": "isFinite(containerSize()[0]) ? containerSize()[0] : 300",""" +
        """"on": [{"events": "window:resize","update": "isFinite(containerSize()[0]) ? """ +
        """containerSize()[0] : 300"}]},""" +
        """{"name": "concat_0_height","value": 250},{"name": "concat_1_height","value": 60}""",
      sizes(
        """{$data,"vconcat":[${plot("\"container\"", "250")},${plot("\"container\"", "60")}]}"""
      ),
    )
  }

  /** A row of plots asking for their height is the same shape along the other direction. */
  @Test
  fun `a row of container heights is measured by a signal`() {
    assertEquals(
      "width=null height=null signals=" +
        """{"name": "height","init": "isFinite(containerSize()[1]) ? containerSize()[1] : 300",""" +
        """"on": [{"events": "window:resize","update": "isFinite(containerSize()[1]) ? """ +
        """containerSize()[1] : 300"}]},""" +
        """{"name": "concat_0_width","value": 100},{"name": "concat_1_width","value": 60}""",
      sizes(
        """{$data,"hconcat":[${plot("100", "\"container\"")},${plot("60", "\"container\"")}]}"""
      ),
    )
  }

  /** A merged **number** is still the chart's own width, hoisted out of the signals. */
  @Test
  fun `a column of equal widths is the chart's own width`() {
    assertEquals(
      "width=300 height=null signals=" +
        """{"name": "concat_0_height","value": 250},{"name": "concat_1_height","value": 60}""",
      sizes("""{$data,"vconcat":[${plot("300", "250")},${plot("300", "60")}]}"""),
    )
  }

  /** And plots that disagree keep a size each, as they did. */
  @Test
  fun `a column of different widths keeps a size each`() {
    assertEquals(
      "width=null height=null signals=" +
        """{"name": "concat_0_width","value": 300},{"name": "concat_0_height","value": 250},""" +
        """{"name": "concat_1_width","value": 200},{"name": "concat_1_height","value": 60}""",
      sizes("""{$data,"vconcat":[${plot("300", "250")},${plot("200", "60")}]}"""),
    )
  }
}
