package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A trellis's captions take their styling from the theme, not only from the facet's own `header`.
 *
 * ```js
 * const headerSpecificConfig =
 *   channel === 'row' ? config.headerRow : channel === 'column' ? config.headerColumn : config.headerFacet;
 * return getFirstDefined((header || {})[prop], headerSpecificConfig[prop], config.header[prop]);
 * ```
 *
 * Three places, most specific first, and a facet that writes no `header` block at all still takes
 * the theme's. This read the facet's own block and gave up when there was none — so
 * `config.header.titleFontSize`, which is how a document sets the type size of every trellis
 * caption at once, had nothing to apply to.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class HeaderConfigTest {

  private fun titles(spec: String): List<Pair<String?, VegaValue?>> {
    val compiled =
      VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "did not compile" }
      ) as VegaValue.Obj
    return (compiled.fields["marks"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .mapNotNull { mark ->
        val title = mark.fields["title"] as? VegaValue.Obj ?: return@mapNotNull null
        (mark.fields["role"] as? VegaValue.Str)?.value to title.fields["fontSize"]
      }
  }

  private val inner =
    """{"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"},
       "y":{"field":"b","type":"quantitative"}}}"""

  private fun rowFacet(config: String, header: String = "") =
    titles(
      """
      {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"config":$config,
       "facet":{"row":{"field":"c","type":"nominal"$header}},"spec":$inner}
      """
    )

  private fun sizeOf(titles: List<Pair<String?, VegaValue?>>, role: String) =
    titles.firstOrNull { it.first == role }?.second

  /** The reported shape: the theme sets the heading's type size for the whole chart. */
  @Test
  fun `a title size from the theme reaches the heading`() {
    val titles = rowFacet("""{"header":{"titleFontSize":36}}""")
    assertEquals(VegaValue.Num(36.0), sizeOf(titles, "row-title"))
    assertNull(sizeOf(titles, "row-header"), "and says nothing about the labels")
  }

  /** And the label half of the same map, which lands on the band rather than the heading. */
  @Test
  fun `a label size from the theme reaches the captions`() {
    val titles = rowFacet("""{"header":{"labelFontSize":24}}""")
    assertEquals(VegaValue.Num(24.0), sizeOf(titles, "row-header"))
    assertNull(sizeOf(titles, "row-title"))
  }

  /** `config.headerRow` is more specific than `config.header` and outranks it. */
  @Test
  fun `the channel's own theme block outranks the general one`() {
    assertEquals(
      VegaValue.Num(11.0),
      sizeOf(
        rowFacet("""{"headerRow":{"titleFontSize":11},"header":{"titleFontSize":36}}"""),
        "row-title",
      ),
    )
  }

  /** And a block for the *other* channel says nothing about this one. */
  @Test
  fun `the other channel's theme block is not read`() {
    assertEquals(
      VegaValue.Num(36.0),
      sizeOf(
        rowFacet("""{"headerColumn":{"titleFontSize":11},"header":{"titleFontSize":36}}"""),
        "row-title",
      ),
      "a column's block is not a row's",
    )
  }

  /** What the facet itself writes outranks both, being the most specific of the three. */
  @Test
  fun `the facet's own header outranks the theme`() {
    assertEquals(
      VegaValue.Num(9.0),
      sizeOf(
        rowFacet("""{"header":{"titleFontSize":36}}""", ""","header":{"titleFontSize":9}"""),
        "row-title",
      ),
    )
  }

  /** A **wrapped** facet captions its cells, and those take the theme's label styling too. */
  @Test
  fun `a wrapped facet's cell captions read the theme`() {
    val titles =
      titles(
        """
        {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"config":{"header":{"labelFontSize":24}},
         "mark":"point",
         "encoding":{"x":{"field":"a","type":"quantitative"},
                     "y":{"field":"b","type":"quantitative"},
                     "facet":{"field":"c","type":"nominal","columns":2}}}
        """
      )
    assertEquals(
      VegaValue.Num(24.0),
      titles.firstOrNull { it.first == null }?.second,
      "the cell's own caption, which has no role of its own",
    )
  }
}
