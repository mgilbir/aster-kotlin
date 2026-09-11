package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A caption turned to a stated angle is anchored through its **band's** own axis.
 *
 * ```js
 * export function defaultHeaderGuideBaseline(angle: number, channel: FacetChannel) {
 *   const baseline = defaultLabelBaseline(angle, channel === 'row' ? 'left' : 'top',
 *                                         channel === 'row' ? 'y' : 'x', true);
 *   return baseline ? {baseline} : {};
 * }
 * ```
 *
 * `defaultHeaderGuideAlign` and `defaultHeaderGuideBaseline` both open with "if the angle is
 * stated": a caption left at whatever angle the renderer chooses is left at whatever anchor it
 * chooses too. State one and the caption has to be turned to face its cell — and which way it turns
 * is the band's question, not the caption's own. A row's captions run down the side of the grid and
 * are anchored as a `y` axis's labels are; a column's run along the top and are anchored as an `x`
 * axis's.
 *
 * This compiler asked the question of **rows** alone and answered the baseline with a flat
 * `middle`, so a column's caption never had one at all — a column at no angle sits on its baseline,
 * which is `bottom` — and a row's turned a quarter of a turn was centred where upstream puts it on
 * `top`. Four specifications in the wild corpus state a column caption's angle.
 *
 * The anchor is read where every other header property is read — the header's own block, then the
 * family for its channel, then `config.header` — so a theme may state the angle for every grid in a
 * document.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class HeaderCaptionAnchorTest {

  /** Every band and cell that carries a caption, with what anchors it. */
  private fun captions(header: String, channel: String = "column", config: String = ""): String {
    val spec =
      """{"data":{"values":[{"a":1,"b":2,"g":"r"}]},$config
         "facet":{"$channel":{"field":"g","type":"nominal"$header}},
         "spec":{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
                                            "y":{"field":"b","type":"quantitative"}}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["marks"] as VegaValue.Arr)
      .values
      .map { it as VegaValue.Obj }
      .filter {
        it.string("role")?.endsWith("header") == true ||
          it.string("role")?.endsWith("footer") == true ||
          it.string("name")?.endsWith("cell") == true
      }
      .joinToString(" | ") { mark ->
        val title = mark.obj("title")
        val anchored =
          listOf("baseline", "align", "angle", "anchor", "orient").mapNotNull { key ->
            title?.fields?.get(key)?.let { "$key=${VegaJson.write(it).trim()}" }
          }
        "${mark.string("name")}:${if (title == null) "none" else anchored.joinToString(",")}"
      }
  }

  /** The reported shape: a column's caption at no angle sits on its baseline. */
  @Test
  fun `a column caption at no angle sits on its baseline`() {
    assertEquals(
      """row_header:none | column_header:baseline="bottom",angle=0 | column_footer:none | """ +
        "cell:none",
      captions(""","header":{"labelAngle":0}"""),
    )
  }

  /** With no angle stated there is nothing to anchor: the renderer is left to choose. */
  @Test
  fun `a caption at no stated angle is left unanchored`() {
    assertEquals("row_header:none | column_header: | column_footer:none | cell:none", captions(""))
  }

  /** A column's caption turned a quarter of a turn is centred and pulled to its right end. */
  @Test
  fun `a turned column caption is centred`() {
    assertEquals(
      """row_header:none | column_header:baseline="middle",align="right",angle=90 | """ +
        "column_footer:none | cell:none",
      captions(""","header":{"labelAngle":90}"""),
    )
  }

  /** A row's caption at no angle is centred on its cell and pushed against it. */
  @Test
  fun `a row caption at no angle is centred`() {
    assertEquals(
      """row_header:baseline="middle",align="right",angle=0,orient="left" | """ +
        "column_footer:none | cell:none",
      captions(""","header":{"labelAngle":0}""", channel = "row"),
    )
  }

  /** And a row's caption turned a quarter of a turn sits on its **top**, not centred. */
  @Test
  fun `a turned row caption sits on its top`() {
    assertEquals(
      """row_header:baseline="top",align="center",angle=90,orient="left" | """ +
        "column_footer:none | cell:none",
      captions(""","header":{"labelAngle":90}""", channel = "row"),
    )
  }

  /**
   * The angle **as written**, negatives and all: neither rule normalises a number — only the
   * expression form of one — so `-90` is anchored by the arm that reads `angle <= 45` and `270`
   * would be anchored by another. Two fixtures in this repository are a trellis turned to `-90`.
   */
  @Test
  fun `a caption turned backwards is anchored by the angle as written`() {
    assertEquals(
      """row_header:baseline="middle",align="center",angle=-90,orient="left" | """ +
        "column_footer:none | cell:none",
      captions(""","header":{"labelAngle":-90}""", channel = "row"),
    )
    assertEquals(
      """row_header:none | column_header:baseline="bottom",align="left",angle=-90 | """ +
        "column_footer:none | cell:none",
      captions(""","header":{"labelAngle":-90}"""),
    )
    assertEquals(
      """row_header:none | column_header:baseline="bottom",align="left",angle=-45 | """ +
        "column_footer:none | cell:none",
      captions(""","header":{"labelAngle":-45}"""),
    )
  }

  /** An anchored caption is pushed to that end of its band whatever angle it is at. */
  @Test
  fun `an anchored caption is pushed to its end`() {
    assertEquals(
      """row_header:none | column_header:baseline="bottom",align="left",angle=45,""" +
        """anchor="start" | column_footer:none | cell:none""",
      captions(""","header":{"labelAngle":45,"labelAnchor":"start"}"""),
    )
    assertEquals(
      """row_header:none | column_header:baseline="bottom",align="right",angle=45,""" +
        """anchor="end" | column_footer:none | cell:none""",
      captions(""","header":{"labelAngle":45,"labelAnchor":"end"}"""),
    )
  }

  /** The angle is read through the theme as every other header property is. */
  @Test
  fun `a themed angle anchors the caption too`() {
    assertEquals(
      """row_header:none | column_header:baseline="bottom",angle=0 | column_footer:none | """ +
        "cell:none",
      captions("", config = """"config":{"header":{"labelAngle":0}},"""),
    )
  }

  /** The **band** is what asks, so a caption moved across its band is anchored as a row's is. */
  @Test
  fun `a column caption moved to the side is anchored as a row's`() {
    assertEquals(
      """row_header:none | column_footer:none | """ +
        """cell:baseline="middle",align="right",angle=0,orient="left"""",
      captions(""","header":{"labelAngle":0,"labelOrient":"left"}"""),
    )
  }

  /** A caption in the trailing band is still the column's, and anchored as one. */
  @Test
  fun `a caption in the trailing band is anchored as its band is`() {
    assertEquals(
      """row_header:none | column_footer:baseline="bottom",angle=0,orient="bottom" | cell:none""",
      captions(""","header":{"labelAngle":0,"labelOrient":"bottom"}"""),
    )
  }
}
