package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A header may ask for its bands without their captions.
 *
 * ```js
 * const labels =
 *   fieldDef.header !== null ? getFirstDefined(fieldDef.header?.labels, config.header.labels, true) : false;
 * ```
 *
 * A grid whose cells name themselves — a small-multiples chart whose colours already say which cell
 * is which — writes `"header": {"labels": false}` and keeps the heading over the grid. The band
 * itself stays: it is also where a shared axis is drawn, and `if (title || hasAxes)` is what
 * decides whether there is a band at all.
 *
 * This engine read `"header": null`, which takes the caption *and* the heading off, and nothing
 * else — so a grid asking only for the captions to go was captioned anyway. Two specifications in
 * the wild corpus ask for it, both of them faceted bar charts whose colour legend already names the
 * rows.
 *
 * Note the two places the flag is read from: the header's own and the theme's `config.header`, and
 * **not** `config.headerRow`/`headerColumn` — this one property does not go through
 * `getHeaderProperty`, and a theme that turns the captions off for one direction alone turns them
 * off for neither.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class HeaderLabelsTest {

  private fun marks(spec: String): List<VegaValue.Obj> {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["marks"] as? VegaValue.Arr)?.values.orEmpty().map {
      it as VegaValue.Obj
    }
  }

  private fun title(spec: String, name: String): VegaValue? =
    marks(spec).first { it.string("name") == name }.fields["title"]

  private val data = """"data":{"values":[{"c":"x","b":1,"d":"q"}]}"""

  private fun faceted(channel: String, header: String, config: String = "") =
    """{$data,$config"mark":"bar","encoding":{"x":{"field":"c","type":"nominal"},
       "y":{"field":"b","type":"quantitative"},
       "$channel":{"field":"d","type":"nominal","header":$header}}}"""

  /** The reported shape: the captions go and the heading over the grid stays. */
  @Test
  fun `a row header may ask for no captions`() {
    val spec = faceted("row", """{"labels":false}""")
    assertNull(title(spec, "row_header"))
    assertNotNull(title(spec, "row-title"))
  }

  /** Which is not what `"header": null` asks for: that takes the heading too. */
  @Test
  fun `a null header takes the heading as well`() {
    val spec = faceted("row", "null")
    assertNull(title(spec, "row_header"))
    assertEquals(
      emptyList<String>(),
      marks(spec)
        .mapNotNull { it.string("name") }
        .filter {
          it == "row-title"
        },
    )
  }

  /** The heading over the grid is then the only thing in the chart that is titled. */
  @Test
  fun `a column header may ask for no captions`() {
    val spec = faceted("column", """{"labels":false}""")
    assertEquals(
      listOf("column-title"),
      marks(spec).filter { it.has("title") }.map { it.string("name") },
    )
  }

  /** A theme may ask for it, for every grid in a document at once. */
  @Test
  fun `a theme may ask for no captions`() {
    val spec = faceted("row", "{}", """"config":{"header":{"labels":false}},""")
    assertNull(title(spec, "row_header"))
  }

  /** And a header that asks for them outranks the theme. */
  @Test
  fun `a header asking for captions outranks the theme`() {
    val spec = faceted("row", """{"labels":true}""", """"config":{"header":{"labels":false}},""")
    assertNotNull(title(spec, "row_header"))
  }

  /**
   * Two shapes where the caption survives the flag, because the flag gates the **band** and these
   * captions are not drawn in one. `assembleLabelTitle` is reached from the cell without it.
   */
  @Test
  fun `a wrapped facet captions its cells whatever the flag says`() {
    val spec =
      """{$data,"columns":2,"facet":{"field":"d","type":"nominal","header":{"labels":false}},
         "spec":{"mark":"bar","encoding":{"x":{"field":"c","type":"nominal"},
                                          "y":{"field":"b","type":"quantitative"}}}}"""
    assertNotNull(title(spec, "cell"))
  }

  @Test
  fun `a caption moved onto the cell survives the flag`() {
    val spec = faceted("column", """{"labels":false,"orient":"right"}""")
    assertNotNull(title(spec, "cell"))
  }
}
