package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `assembleTitle`: which model the title belongs to, and what the theme already said about it.
 *
 * ```js
 * const title = {
 *   ...extractTitleConfig(this.config.title).nonMarkTitleProperties,
 *   ...titleNoEncoding,
 *   ...(encoding ? {encode: {update: encoding}} : {}),
 * };
 * if (title.text) {
 *   if (contains(['unit', 'layer'], this.type)) {
 *     if (contains(['middle', undefined], title.anchor)) title.frame ??= 'group';
 *   } else {
 *     title.anchor ??= 'start';
 *   }
 * }
 * ```
 *
 * A **unit or layer** anchors its title to the plotting group, which keeps it over the drawing when
 * an axis widens the surface to its left. A **composition** cannot — its groups are laid out and
 * there is no one plotting area to sit over — so it takes `anchor: "start"`, upstream's note being
 * that a centred title "does not look nice" over a grid.
 *
 * Two things were wrong. A chart faceted by the **`facet` channel** was read as a unit, where `row`
 * and `column` were already read as compositions, so its title was framed to a plotting area the
 * chart does not have. And `config.title`'s six non-mark properties were dropped on the floor:
 * [Config] rightly keeps them out of the `group-title` style, because they belong on the title
 * directive, and nothing then wrote them there — so a theme whose `config.title.anchor` is `start`
 * got a centred title with a group frame.
 *
 * Every expectation here was compiled with upstream rather than reasoned about.
 */
class ChartTitleTest {

  private fun title(spec: String): VegaValue.Obj? =
    (VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "did not compile" }
      ) as VegaValue.Obj)
      .fields["title"]
      as? VegaValue.Obj

  private fun chart(title: String, extra: String = "", config: String = "") =
    title(
      """
      {"data":{"values":[{"a":"A","b":28,"c":"x"}]},
       "mark":"point","title":$title,$config
       "encoding":{"x":{"field":"a","type":"nominal"},
                   "y":{"field":"b","type":"quantitative"}$extra}}
      """
    )

  private fun string(title: VegaValue.Obj?, key: String) =
    (title?.fields?.get(key) as? VegaValue.Str)?.value

  private val facetChannel = ""","facet":{"field":"c","type":"nominal"}"""

  /** A unit's title is framed to its group, there being a plotting area to sit over. */
  @Test
  fun `a unit's title is framed to its group`() {
    val title = chart(""""T"""")
    assertEquals("group", string(title, "frame"))
    assertNull(title?.fields?.get("anchor"))
  }

  /**
   * Unless it is anchored somewhere other than the middle, in which case framing it makes no sense.
   */
  @Test
  fun `a title anchored away from the middle is not framed`() {
    assertNull(chart("""{"text":"T","anchor":"start"}""")?.fields?.get("frame"))
    assertNull(chart("""{"text":"T","anchor":"end"}""")?.fields?.get("frame"))
    assertEquals(
      "group",
      string(chart("""{"text":"T","anchor":"middle"}"""), "frame"),
      "`middle` is the one anchor a frame goes with",
    )
  }

  /** The reported shape: a chart faceted by the `facet` channel is a composition. */
  @Test
  fun `a chart faceted by the facet channel takes a composition's title`() {
    val title = chart(""""T"""", extra = facetChannel)
    assertEquals("start", string(title, "anchor"), "a grid has no plotting area to centre over")
    assertNull(title?.fields?.get("frame"))
  }

  /** And a composition that states its anchor keeps it, without gaining a frame. */
  @Test
  fun `a faceted chart that states its anchor keeps it and is still unframed`() {
    val title = chart("""{"text":"T","anchor":"middle"}""", extra = facetChannel)
    assertEquals("middle", string(title, "anchor"))
    assertNull(title?.fields?.get("frame"), "a composition is never framed, whatever its anchor")
  }

  /** The theme's anchor is as explicit as the title's own, and settles the frame with it. */
  @Test
  fun `an anchor from the theme reaches the title and suppresses the frame`() {
    val title = chart(""""T"""", config = """"config":{"title":{"anchor":"start"}},""")
    assertEquals("start", string(title, "anchor"))
    assertNull(title?.fields?.get("frame"))
  }

  /** The other five come through too, and are not a frame's business. */
  @Test
  fun `the theme's other non-mark title properties reach the title`() {
    val title = chart(""""T"""", config = """"config":{"title":{"offset":10}},""")
    assertEquals(VegaValue.Num(10.0), title?.fields?.get("offset"))
    assertEquals("group", string(title, "frame"), "an offset says nothing about the anchor")
  }

  /** What the title states outranks what the theme did. */
  @Test
  fun `a stated anchor outranks the theme's`() {
    val title =
      chart(
        """{"text":"T","anchor":"middle"}""",
        config = """"config":{"title":{"anchor":"start"}},""",
      )
    assertEquals("middle", string(title, "anchor"))
    assertEquals("group", string(title, "frame"), "and it is middle, so it is framed after all")
  }

  /**
   * A title's `encoding` is a Vega `encode` block, the one key upstream takes out before spreading.
   */
  @Test
  fun `a title's encoding becomes an encode block`() {
    val title = chart("""{"text":"T","encoding":{"fill":{"value":"red"}}}""")
    assertNull(title?.fields?.get("encoding"), "Vega has no `encoding` on a title")
    val update = (title?.fields?.get("encode") as? VegaValue.Obj)?.fields?.get("update")
    assertEquals(
      VegaValue.Str("red"),
      ((update as? VegaValue.Obj)?.fields?.get("fill") as? VegaValue.Obj)?.fields?.get("value"),
    )
  }
}
