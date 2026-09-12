package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A grid's heading is named where any grid's is — and an **empty** heading is no heading.
 *
 * ```js
 * const titleConfig = getHeaderProperty('title', null, config, channel);
 * let title = fieldDefTitle(fieldDef, config, {
 *   allowDisabling: true,
 *   includeDefault: titleConfig === undefined || !!titleConfig,
 * });
 * …
 * title: fieldDef.header !== null ? title : null,
 * ```
 * ```js
 * for (const channel of FACET_CHANNELS) {
 *   if (layoutHeaders[channel].title) {
 *     headerMarks.push(assembleTitleGroup(this, channel));
 *   }
 * }
 * ```
 *
 * `parseFacetHeaders` settles it once for every facet channel, and `assembleHeaderMarks` then asks
 * whether there *is* a heading, `""` not being one. The crossed form — `row` and `column` — read it
 * this way; the wrapped form read only the definition's derived name, so it
 * - ignored a `header` block naming the heading,
 * - ignored a theme that emptied one, and
 * - drew a band of blank space over a trellis that wrote `"title": ""` to ask for none, with every
 *   cell pushed down by it.
 *
 * The theme's own `title` is not a heading, in either form: it decides only whether the column's
 * derived name is used at all. A document that says `{"header": {"title": null}}` takes the heading
 * off every grid in it; one that names a title does not put that name over a grid.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class EmptyHeadingTest {

  /** The grid's groups, each with its caption where it has one. */
  private fun groups(facet: String, config: String = ""): String {
    val themed = if (config.isEmpty()) "" else """"config":$config,"""
    val spec =
      """{$themed"facet":$facet,"spec":{
          "mark":"point","encoding":{"x":{"field":"a","type":"quantitative"}}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["marks"] as VegaValue.Arr).values.joinToString(" ") {
      it as VegaValue.Obj
      val caption = (it.fields["title"] as? VegaValue.Obj)?.fields?.get("text")
      val text = (caption as? VegaValue.Str)?.value ?: caption?.let { "…" }
      "${it.string("name")}${text?.let { written -> "($written)" }.orEmpty()}"
    }
  }

  private val wrapped = """{"field":"r","type":"nominal""""
  private val crossed = """{"row":{"field":"r","type":"nominal""""

  /** A trellis that names its field is captioned with it, which is what must not change. */
  @Test
  fun `a named field captions the grid`() {
    assertEquals("facet-title(r) column_footer cell(…)", groups("$wrapped}"))
  }

  /** The reported shape: an empty title asks for no heading at all. */
  @Test
  fun `an empty title is no heading`() {
    assertEquals("column_footer cell(…)", groups("""$wrapped,"title":""}"""))
  }

  /** A stated `null` is refused outright: `getFirstDefined` stops at what was written. */
  @Test
  fun `a null title is no heading`() {
    assertEquals("column_footer cell(…)", groups("""$wrapped,"title":null}"""))
  }

  /** The heading a **header** block names is the one drawn. */
  @Test
  fun `a wrapped grid takes the heading its header names`() {
    assertEquals(
      "facet-title(Region) column_footer cell(…)",
      groups("""$wrapped,"header":{"title":"Region"}}"""),
    )
  }

  /** And a header that empties it takes the heading off, as the definition's own does. */
  @Test
  fun `a wrapped grid drops a heading its header emptied`() {
    assertEquals("column_footer cell(…)", groups("""$wrapped,"header":{"title":""}}"""))
  }

  /** `"header": null` takes the whole header off; the captions naming the cells stay. */
  @Test
  fun `a wrapped grid with no header has no heading`() {
    assertEquals("column_footer cell(…)", groups("""$wrapped,"header":null}"""))
  }

  /** A theme that empties the title takes the heading off every grid in the document. */
  @Test
  fun `a theme that empties the title takes the wrapped heading off`() {
    assertEquals(
      "column_footer cell(…)",
      groups("$wrapped}", config = """{"header":{"title":null}}"""),
    )
  }

  /** A theme that **names** one names nothing: it only says whether the default is used. */
  @Test
  fun `a theme that names a title does not name the heading`() {
    assertEquals(
      "facet-title(r) column_footer cell(…)",
      groups("$wrapped}", config = """{"header":{"title":"Z"}}"""),
    )
  }

  /** The crossed form reads all of it the same way, which is what it always did. */
  @Test
  fun `a crossed grid reads an empty title the same way`() {
    assertEquals("row_header(…) column_footer cell", groups("""$crossed,"title":""}}"""))
  }

  /** Its header names its heading too, and outranks the definition's own title. */
  @Test
  fun `a crossed grid prefers the header's title to its own`() {
    assertEquals(
      "row-title(H) row_header(…) column_footer cell",
      groups("""$crossed,"title":"T","header":{"title":"H"}}}"""),
    )
  }

  /** And a theme that names a title leaves the crossed heading the column's name. */
  @Test
  fun `a theme that names a title leaves a crossed heading alone`() {
    assertEquals(
      "row-title(r) row_header(…) column_footer cell",
      groups("$crossed}}", config = """{"header":{"title":"Z"}}"""),
    )
  }
}
