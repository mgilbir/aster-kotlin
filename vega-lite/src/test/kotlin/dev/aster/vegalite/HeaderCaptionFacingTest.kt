package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A caption **anchored** to one end of its band is aligned to that end, angle or no angle.
 *
 * ```js
 * export function defaultHeaderGuideAlign(headerChannel, angle, anchor = 'middle') {
 *   switch (anchor) {
 *     case 'start': return {align: 'left'};
 *     case 'end': return {align: 'right'};
 *   }
 *   const align = defaultLabelAlign(angle, headerChannel === 'row' ? 'left' : 'top', ...);
 *   return align ? {align} : {};
 * }
 * ```
 *
 * The anchor is asked first and is asked whether or not an angle was stated; the angle settles only
 * an unanchored caption, and the baseline is the angle's alone — `defaultLabelAlign` and
 * `defaultLabelBaseline` both answer nothing without one. This compiler asked both inside a test
 * for the angle, so a header that anchored its captions and left them flat got no alignment and its
 * names came out centred.
 *
 * And `assembleLabelTitle` is the **same** function wherever the caption is drawn, so a *wrapped*
 * grid's cell caption faces the way a band's does. This compiler asked nothing at all there: a
 * wrapped trellis that anchored its names drew them centred, and one that turned them read them off
 * their own baseline.
 *
 * One specification in the wild corpus is a wrapped trellis that anchors its cell names to the
 * start and hangs them below each cell.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class HeaderCaptionFacingTest {

  /** Every group's title, by the group it is drawn on. */
  private fun titles(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun walk(marks: List<VegaValue>): List<String> = marks.flatMap {
      it as VegaValue.Obj
      listOfNotNull(
        it.fields["title"]?.let { title ->
          "${it.string("name")}:${VegaJson.write(title).replace(Regex("""\n\s*"""), "")}"
        }
      ) + walk((it.fields["marks"] as? VegaValue.Arr)?.values.orEmpty())
    }
    return walk((compiled.fields["marks"] as VegaValue.Arr).values)
      .filterNot { it.contains("guide-title") }
      .joinToString(" | ")
  }

  private val rows = """"data":{"values":[{"a":1,"b":2,"r":"x"}]}"""
  private val cell =
    """"spec":{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
                                          "y":{"field":"b","type":"quantitative"}}}"""
  private val text = """{"signal": "isValid(parent[\"r\"]) ? parent[\"r\"] : \"\"+parent[\"r\"]"}"""

  private fun wrapped(header: String) =
    """{$rows,"columns":2,"facet":{"field":"r","type":"nominal"$header},$cell}"""

  /**
   * The reported shape: a wrapped trellis anchoring its cell names to the start, below the cell.
   */
  @Test
  fun `a wrapped cell's caption is aligned by its anchor`() {
    assertEquals(
      """cell:{"text": $text,"style": "guide-label","frame": "group","align": "left",""" +
        """"anchor": "start","orient": "bottom","offset": 0}""",
      titles(
        wrapped(""","header":{"labelAnchor":"start","labelOrient":"bottom","labelPadding":0}""")
      ),
    )
  }

  /** The other end pushes the other way. */
  @Test
  fun `an end anchor aligns right`() {
    assertEquals(
      """cell:{"text": $text,"style": "guide-label","frame": "group","align": "right",""" +
        """"anchor": "end","offset": 10}""",
      titles(wrapped(""","header":{"labelAnchor":"end"}""")),
    )
  }

  /** With nothing said there is nothing to align, which is the shape that must not change. */
  @Test
  fun `an unanchored flat caption is left alone`() {
    assertEquals(
      """cell:{"text": $text,"style": "guide-label","frame": "group","offset": 10}""",
      titles(wrapped("")),
    )
  }

  /** A **turn** settles both the baseline and the alignment of an unanchored caption. */
  @Test
  fun `a turned caption faces its band`() {
    assertEquals(
      """cell:{"text": $text,"style": "guide-label","frame": "group","baseline": "bottom",""" +
        """"align": "right","angle": 45,"offset": 10}""",
      titles(wrapped(""","header":{"labelAngle":45}""")),
    )
  }

  /** And where both are stated the anchor wins the alignment, the turn keeping the baseline. */
  @Test
  fun `an anchor outranks the turn`() {
    assertEquals(
      """cell:{"text": $text,"style": "guide-label","frame": "group","baseline": "bottom",""" +
        """"align": "left","anchor": "start","angle": 45,"offset": 10}""",
      titles(wrapped(""","header":{"labelAnchor":"start","labelAngle":45}""")),
    )
  }

  /** A **crossed** grid captions its band, and the same rule settles that caption. */
  @Test
  fun `a band's caption is aligned by its anchor too`() {
    assertEquals(
      """column_header:{"text": $text,"style": "guide-label","frame": "group",""" +
        """"align": "left","anchor": "start","offset": 10}""",
      titles(
        """{$rows,"facet":{"column":{"field":"r","type":"nominal",
           "header":{"labelAnchor":"start"}}},$cell}"""
      ),
    )
  }

  /** And says nothing where the header says nothing. */
  @Test
  fun `an unanchored band caption is left alone`() {
    assertEquals(
      """column_header:{"text": $text,"style": "guide-label","frame": "group","offset": 10}""",
      titles("""{$rows,"facet":{"column":{"field":"r","type":"nominal"}},$cell}"""),
    )
  }
}
