package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A theme may take the heading off every grid in a document.
 *
 * ```js
 * const headerSpecificConfig =
 *   channel === 'row' ? config.headerRow : channel === 'column' ? config.headerColumn : config.headerFacet;
 *
 * return getFirstDefined((header || {})[prop], headerSpecificConfig[prop], config.header[prop]);
 * ```
 *
 * `title` is read through `getHeaderProperty` like every other header property, so `{"config":
 * {"header": {"title": null}}}` says once what a chart whose cells caption themselves would
 * otherwise say on each of its grids. The heading goes, and so does the room the layout was keeping
 * for it.
 *
 * This engine read the definition's own block alone, so such a chart came out with a heading over
 * every grid and a `columnTitle` offset holding space for it. Six specifications in the wild corpus
 * theme it that way.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ThemedHeaderTitleTest {

  /** The layout's title offsets and the names of the marks that are headings. */
  private fun headings(config: String, header: String = ""): String {
    val spec =
      """{"data":{"values":[{"a":1,"b":2,"r":"p","c":"q"}]},$config"mark":"bar",
         "encoding":{"x":{"field":"a","type":"quantitative"},
                     "y":{"field":"b","type":"quantitative"},
                     "column":{"field":"c","type":"nominal"$header}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val offset = (compiled.fields["layout"] as VegaValue.Obj).fields["offset"]
    val titles =
      (compiled.fields["marks"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .filter { it.string("role")?.endsWith("-title") == true }
        .mapNotNull { it.string("name") }
    val written = VegaJson.write(offset ?: VegaValue.Null).replace(Regex("""\n\s*"""), "")
    return "offset=$written titles=$titles"
  }

  /** The reported shape: a theme saying no grid in this document is to be titled. */
  @Test
  fun `a themed header title of null takes the heading off`() {
    assertEquals("offset=null titles=[]", headings(""""config":{"header":{"title":null}},"""))
  }

  /** The family for one direction says it for that direction. */
  @Test
  fun `a themed column header title of null takes it off too`() {
    assertEquals("offset=null titles=[]", headings(""""config":{"headerColumn":{"title":null}},"""))
  }

  /** A themed caption is a caption, and the room for it is kept. */
  @Test
  fun `a themed header title is written`() {
    assertEquals(
      """offset={"columnTitle": 10} titles=[column-title]""",
      headings(""""config":{"header":{"title":"Themed"}},"""),
    )
  }

  /** With nothing themed, the column's own name is the heading, as it always was. */
  @Test
  fun `an unthemed grid is titled by its column`() {
    assertEquals("""offset={"columnTitle": 10} titles=[column-title]""", headings(""))
  }

  /** And the grid's own header outranks the theme, being the more specific of the two. */
  @Test
  fun `a stated null outranks a themed caption`() {
    assertEquals(
      "offset=null titles=[]",
      headings(
        """"config":{"header":{"title":"Themed"}},""",
        header = ""","header":{"title":null}""",
      ),
    )
  }
}
