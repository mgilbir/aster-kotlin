package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A wrapped trellis's **heading** is styled by its header's `title…` properties.
 *
 * `assembleTitleGroup` ends with
 *
 * ```js
 * ...assembleHeaderProperties(config, facetFieldDef, channel, HEADER_TITLE_PROPERTIES, HEADER_TITLE_PROPERTIES_MAP),
 * ```
 *
 * exactly as the caption on each cell ends with the `label…` half of the same table. This engine
 * wrote the heading's text and offset and nothing else, so a trellis sizing or colouring its
 * heading — or a document setting `config.header.titleFontSize` to size every heading at once — was
 * drawn with the default. Three specifications in the wild corpus differed for it.
 *
 * The two maps are the same thirteen properties under two prefixes, which is why they are one
 * table. The cell caption's half was missing `labelOrient` and `labelPadding`, and those are the
 * two that *move* a caption rather than restyle it.
 *
 * `Padding` is the one whose new name is not its old one with the prefix taken off: a caption's
 * padding is the title's `offset`, and it replaces the default rather than adding to it.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class WrappedFacetHeaderTest {

  private class Captions(val grid: VegaValue.Obj?, val cell: VegaValue.Obj?)

  private fun captions(header: String? = null, extra: String = ""): Captions {
    val block = header?.let { ""","header":$it""" } ?: ""
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":"point"$extra,
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"},
                           "facet":{"field":"c","type":"nominal","columns":2,"title":"H"$block}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val marks =
      (compiled.fields["marks"] as VegaValue.Arr).values.mapNotNull { it as? VegaValue.Obj }
    return Captions(
      grid = marks.firstOrNull { it.string("role") == "column-title" }?.obj("title"),
      cell =
        marks.firstOrNull { it.obj("from")?.fields?.containsKey("facet") == true }?.obj("title"),
    )
  }

  private fun keys(caption: VegaValue.Obj?) = caption?.fields?.keys?.toList().orEmpty()

  /** The reported shape: a heading given a size and a colour. */
  @Test
  fun `the heading takes the header's title properties`() {
    val grid = captions("""{"titleFontSize":5,"titleColor":"white"}""").grid
    assertEquals(listOf("text", "style", "color", "fontSize", "offset"), keys(grid))
    assertEquals(VegaValue.Num(5.0), grid?.fields?.get("fontSize"))
    assertEquals(VegaValue.Str("white"), grid?.fields?.get("color"))
  }

  /** The rest of the map reaches it too, renamed the same way. */
  @Test
  fun `a stated font and limit reach the heading`() {
    val grid = captions("""{"titleFont":"serif","titleLimit":80}""").grid
    assertEquals(VegaValue.Str("serif"), grid?.fields?.get("font"))
    assertEquals(VegaValue.Num(80.0), grid?.fields?.get("limit"))
  }

  /**
   * `titlePadding` is the heading's `offset`, and replaces the default rather than adding to it.
   */
  @Test
  fun `a stated padding replaces the heading's offset`() {
    val grid = captions("""{"titlePadding":7}""").grid
    assertEquals(VegaValue.Num(7.0), grid?.fields?.get("offset"))
    assertEquals(listOf("text", "style", "offset"), keys(grid))
  }

  /** The theme's, which is how a document sizes every heading in it at once. */
  @Test
  fun `config header reaches the heading and the cell caption`() {
    val both = captions(extra = ""","config":{"header":{"titleFontSize":18,"labelFontSize":24}}""")
    assertEquals(VegaValue.Num(18.0), both.grid?.fields?.get("fontSize"))
    assertEquals(VegaValue.Num(24.0), both.cell?.fields?.get("fontSize"))
  }

  /** And the header's own outranks the theme's, `getHeaderProperty` asking it first. */
  @Test
  fun `the header's own property outranks the theme's`() {
    val grid =
      captions(
          """{"titleFontSize":5}""",
          ""","config":{"headerFacet":{"titleFontSize":18}}""",
        )
        .grid
    assertEquals(VegaValue.Num(5.0), grid?.fields?.get("fontSize"))
  }

  /**
   * The label half of the table, whose two missing entries are the ones that *move* a caption: an
   * orientation and a padding.
   */
  @Test
  fun `the cell caption takes its orient and padding`() {
    val cell = captions("""{"labelPadding":9,"labelOrient":"bottom"}""").cell
    assertEquals(VegaValue.Str("bottom"), cell?.fields?.get("orient"))
    assertEquals(VegaValue.Num(9.0), cell?.fields?.get("offset"))
  }

  /** With nothing said, the heading is what it always was. */
  @Test
  fun `a heading with nothing said keeps its default offset`() {
    val grid = captions().grid
    assertEquals(listOf("text", "style", "offset"), keys(grid))
    assertEquals(VegaValue.Num(10.0), grid?.fields?.get("offset"))
  }
}
