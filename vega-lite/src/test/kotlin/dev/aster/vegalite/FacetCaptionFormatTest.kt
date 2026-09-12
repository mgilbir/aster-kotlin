package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A trellis's caption is written the way the column is **typed**, and the way its header says.
 *
 * `assembleLabelTitle` asks the header for a format before it writes anything —
 *
 * ```js
 * const {format, formatType, …} = getHeaderProperties(
 *   ['format', 'formatType', 'labelAngle', 'labelAnchor', 'labelOrient', 'labelExpr'],
 *   facetFieldDef.header, config, channel,
 * );
 * const titleTextExpr = formatSignalRef({fieldOrDatumDef: facetFieldDef, format, formatType, expr: 'parent', config}).signal;
 * ```
 *
 * — and `formatSignalRef` is the same function a tooltip line goes through: a bucketed column reads
 * as its span, an instant as a date, and `format || channelDefType === 'quantitative'` as a
 * **number**. This engine had the first two arms and not the third, so a grid split by a *measured*
 * column captioned its cells with the raw value where upstream writes `format(…, "")` — three
 * specifications in the wild corpus.
 *
 * A stated format pulls any column into the number arm, however it is typed, and a **custom**
 * format type is the first arm of all: `if (isCustomFormatType(formatType)) return
 * formatCustomType(…)`, which calls the function the page registered rather than `format`, and
 * passes a specifier only where there is one to pass.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class FacetCaptionFormatTest {

  private fun caption(facet: String, config: String = ""): String {
    val theme = if (config.isEmpty()) "" else ""","config":$config"""
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"f":3,"t":"x"}]},"mark":"point"$theme,
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"},"facet":$facet}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["marks"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .firstOrNull { it.obj("from")?.fields?.containsKey("facet") == true }
      ?.obj("title")
      ?.obj("text")
      ?.string("signal")
      .orEmpty()
  }

  private fun measured(header: String = "") =
    """{"field":"f","type":"quantitative","columns":2$header}"""

  /** The reported shape: a grid split by a measured column. */
  @Test
  fun `a measured column is captioned as a number`() {
    assertEquals("""format(parent["f"], "")""", caption(measured()))
  }

  /** A category is not, which is the arm that already worked. */
  @Test
  fun `a category is captioned as it stands`() {
    assertEquals(
      """isValid(parent["t"]) ? parent["t"] : ""+parent["t"]""",
      caption("""{"field":"t","type":"nominal","columns":2}"""),
    )
    assertEquals(
      """isValid(parent["f"]) ? parent["f"] : ""+parent["f"]""",
      caption("""{"field":"f","type":"ordinal","columns":2}"""),
      "an ordinal column is a category however numeric its values are",
    )
  }

  /** A stated format pulls any column into the number arm. */
  @Test
  fun `a stated format is used, whatever the type`() {
    assertEquals(
      """format(parent["f"], ".2f")""",
      caption(measured(""","header":{"format":".2f"}""")),
    )
    assertEquals(
      """format(parent["t"], ".2f")""",
      caption("""{"field":"t","type":"nominal","columns":2,"header":{"format":".2f"}}"""),
    )
  }

  /** The theme's `config.header.format`, and the number format behind it. */
  @Test
  fun `the theme's format reaches the caption`() {
    assertEquals(
      """format(parent["f"], ".1f")""",
      caption(measured(), """{"header":{"format":".1f"}}"""),
    )
    assertEquals(
      """format(parent["f"], ".3f")""",
      caption(measured(), """{"numberFormat":".3f"}"""),
      "the configured number format is the specifier where the header states none",
    )
  }

  /**
   * A **custom** format type calls the function the page registered, and takes a specifier only
   * where there is one.
   */
  @Test
  fun `a custom format type is called instead of format`() {
    assertEquals(
      """myFmt(parent["f"])""",
      caption(measured(), """{"numberFormatType":"myFmt","customFormatTypes":true}"""),
    )
    assertEquals(
      """myFmt(parent["f"])""",
      caption(
        measured(""","header":{"formatType":"myFmt"}"""),
        """{"customFormatTypes":true}""",
      ),
      "stated on the header, it needs no configured type beside it",
    )
    assertEquals(
      """myFmt(parent["f"], ".2f")""",
      caption(
        measured(),
        """{"numberFormatType":"myFmt","numberFormat":".2f","customFormatTypes":true}""",
      ),
    )
  }
}
