package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The side a header hangs off decides which **band** of the grid it is in.
 *
 * ```js
 * export function getHeaderChannel(channel: FacetChannel, orient: Orient): HeaderChannel {
 *   if (contains(['top', 'bottom'], orient)) return 'column';
 *   else if (contains(['left', 'right'], orient)) return 'row';
 *   return channel === 'row' ? 'row' : 'column';
 * }
 * ```
 *
 * A band is horizontal or vertical, and moving a header to the right of a column-faceted chart puts
 * it in the *row* band: it runs down the side of the grid, and its heading is roled, turned and
 * anchored as a row's. This engine read the channel instead, so such a heading was laid out across
 * a grid it runs down.
 *
 * Two consequences of the same reading. A caption whose side points **across** its own band is not
 * drawn in that band at all — there is one caption per band and nowhere along a band running the
 * other way to put it — and `assembleLabelTitle` moves it onto the **cell** instead. And a heading
 * moved to a trailing side is anchored at the end of the band it is now in, which is asked of the
 * *heading's* own side: a header may move its captions and leave its heading where it was.
 *
 * `header.orient` is the shortcut that sets both sides at once, and `normalizeFieldDef` expands it
 * into `labelOrient` and `titleOrient` before anything reads it — which is also what carries the
 * side onto the caption, the property rename being what a reader of the header sees.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class HeaderBandTest {

  private fun marks(spec: String): List<VegaValue.Obj> {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["marks"] as? VegaValue.Arr)?.values.orEmpty().map {
      it as VegaValue.Obj
    }
  }

  private fun layout(spec: String): VegaValue.Obj {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return compiled.fields["layout"] as VegaValue.Obj
  }

  /** Each mark as `name|role`, which is how the two titles of a grid tell themselves apart. */
  private fun roles(spec: String) = marks(spec).map { "${it.string("name")}|${it.string("role")}" }

  /** The mark of that name, and what its `title` says — the caption or heading it draws. */
  private fun title(spec: String, name: String): VegaValue? =
    marks(spec).first { it.string("name") == name }.fields["title"]

  private val data = """"data":{"values":[{"c":"x","b":1,"d":"q"}]}"""

  private fun faceted(channel: String, header: String, second: String = "") =
    """{$data,"mark":"bar","encoding":{"x":{"field":"c","type":"nominal"},
       "y":{"field":"b","type":"quantitative"},
       "$channel":{"field":"d","type":"nominal","header":$header}$second}}"""

  // -- Which band the heading is in -------------------------------------------------------------

  /** The reported shape: a column's header moved to the right is a row header. */
  @Test
  fun `a column header moved to the right heads the row band`() {
    assertEquals(
      listOf(
        "column-title|row-title",
        "row_header|row-header",
        "column_footer|column-footer",
        "cell|null",
      ),
      roles(faceted("column", """{"orient":"right"}""")),
    )
  }

  /** Moved to the bottom it is still a column's: the band runs the same way. */
  @Test
  fun `a column header moved to the bottom heads the column band`() {
    assertEquals(
      listOf(
        "column-title|column-title",
        "row_header|row-header",
        "column_footer|column-footer",
        "cell|null",
      ),
      roles(faceted("column", """{"orient":"bottom"}""")),
    )
  }

  /** And a row's header moved to the top heads the column band. */
  @Test
  fun `a row header moved to the top heads the column band`() {
    assertEquals(
      listOf(
        "row-title|column-title",
        "row_header|row-header",
        "column_footer|column-footer",
        "cell|null",
      ),
      roles(faceted("row", """{"orient":"top"}""")),
    )
  }

  /** A header that states no side is in the band its channel implies. */
  @Test
  fun `a row header that states no side heads the row band`() {
    assertEquals(
      listOf(
        "row-title|row-title",
        "row_header|row-header",
        "column_footer|column-footer",
        "cell|null",
      ),
      roles(faceted("row", """{}""")),
    )
  }

  /** A wrapped facet captions its cells above them, so its heading is a column's by default. */
  @Test
  fun `a wrapped facet heading moved to the right heads the row band`() {
    val spec =
      """{$data,"columns":2,"facet":{"field":"d","type":"nominal","header":{"orient":"right"}},
         "spec":{"mark":"bar","encoding":{"x":{"field":"c","type":"nominal"},
                                          "y":{"field":"b","type":"quantitative"}}}}"""
    assertEquals(
      "row-title",
      marks(spec).first { it.string("name") == "facet-title" }.string("role"),
    )
    assertEquals(json("""{"row":"end"}"""), layout(spec).fields["titleAnchor"])
  }

  // -- Where the caption is drawn ---------------------------------------------------------------

  /** A caption pointing across its own band is drawn on the cell instead of in the band. */
  @Test
  fun `a caption whose side points across its band moves onto the cell`() {
    val spec = faceted("column", """{"orient":"right"}""")
    assertNull(title(spec, "column_footer"))
    assertEquals(
      json(
        """{"text":{"signal":"isValid(parent[\"d\"]) ? parent[\"d\"] : \"\"+parent[\"d\"]"},
           "style":"guide-label","frame":"group","orient":"right","offset":10}"""
      ),
      title(spec, "cell"),
    )
  }

  /** A caption pointing along it stays in the band, and the cell keeps none. */
  @Test
  fun `a caption whose side points along its band stays in the band`() {
    val spec = faceted("column", """{"orient":"bottom"}""")
    assertNull(title(spec, "cell"))
    assertEquals(
      json(
        """{"text":{"signal":"isValid(parent[\"d\"]) ? parent[\"d\"] : \"\"+parent[\"d\"]"},
           "style":"guide-label","frame":"group","orient":"bottom","offset":10}"""
      ),
      title(spec, "column_footer"),
    )
  }

  /** A row's caption moved to the top moves onto the cell, and takes the side with it. */
  @Test
  fun `a row caption moved to the top moves onto the cell`() {
    val spec = faceted("row", """{"orient":"top"}""")
    assertNull(title(spec, "row_header"))
    assertEquals(
      json(
        """{"text":{"signal":"isValid(parent[\"d\"]) ? parent[\"d\"] : \"\"+parent[\"d\"]"},
           "orient":"top","style":"guide-label","frame":"group","offset":10}"""
      ),
      title(spec, "cell"),
    )
  }

  /** The two sides are separate properties, and the shortcut only fills in what neither said. */
  @Test
  fun `a header may move its captions and leave its heading`() {
    val spec = faceted("column", """{"labelOrient":"right","titleOrient":"bottom"}""")
    assertEquals(
      "column-title",
      marks(spec).first { it.string("name") == "column-title" }.string("role"),
    )
    assertEquals(json("""{"column":"end"}"""), layout(spec).fields["titleAnchor"])
    assertEquals("right", (title(spec, "cell") as VegaValue.Obj).string("orient"))
  }

  // -- Where the heading is anchored ------------------------------------------------------------

  /** A heading on a trailing side is anchored at the end of the band it is now in. */
  @Test
  fun `a heading moved to the right anchors at the end of the row band`() {
    assertEquals(
      json("""{"row":"end"}"""),
      layout(faceted("column", """{"orient":"right"}""")).fields["titleAnchor"],
    )
  }

  /** A leading side needs no anchor, the start being where a heading goes anyway. */
  @Test
  fun `a heading moved to the top is not anchored`() {
    assertNull(layout(faceted("row", """{"orient":"top"}""")).fields["titleAnchor"])
  }

  /** Captions moved to a trailing side do not anchor the heading, which did not move. */
  @Test
  fun `captions moved to the bottom leave the heading unanchored`() {
    val spec = faceted("column", """{"labelOrient":"bottom","titleOrient":"top"}""")
    assertNull(layout(spec).fields["titleAnchor"])
    assertEquals("bottom", (title(spec, "column_footer") as VegaValue.Obj).string("orient"))
  }

  /** A row's heading moved to the bottom is anchored in the *column* band it moved into. */
  @Test
  fun `a row heading moved to the bottom anchors the column band`() {
    assertEquals(
      json("""{"column":"end"}"""),
      layout(
          faceted("row", """{"orient":"bottom"}""", ""","column":{"field":"c","type":"nominal"}""")
        )
        .fields["titleAnchor"],
    )
  }

  private fun json(text: String) = VegaJson.parse(text)
}
