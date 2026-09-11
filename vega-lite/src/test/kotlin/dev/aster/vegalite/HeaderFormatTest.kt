package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A trellis caption is written the way the **header** says to write it.
 *
 * ```js
 * if (isCustomFormatType(formatType)) { return formatCustomType({...}); }
 * ...
 * if (isFieldOrDatumDefForTimeFormat(fieldOrDatumDef)) {
 *   const {unit: timeUnit, utc: isUTCUnit} = getTimeDef(fieldOrDatumDef);
 *   const signal = timeFormatExpression({field, timeUnit, format, ...});
 *   return signal ? {signal} : undefined;
 * }
 * format = numberFormat({type, specifiedFormat: format, config, normalizeStack});
 * ```
 * ```js
 * if (!timeUnit || format) {
 *   // If there is no time unit, or if user explicitly specifies format for axis/legend/text.
 *   ...
 *   format = isString(format) ? format : rawTimeFormat;
 *   return `${isUTCScale ? 'utc' : 'time'}Format(${field}, ${stringify(format)})`;
 * }
 * ```
 *
 * `assembleHeaderTitle` reads `format` and `formatType` off the header and hands them to the same
 * `formatSignalRef` a mark's text goes through. This compiler read them too, but only after it had
 * already answered — a date was captioned by the clock and a bucket by its two edges before the
 * question was asked, so a trellis of months written `{"format": "%b %y", "formatType": "time"}`
 * was captioned `%b %d, %Y` and a trellis of buckets written `{"format": ".2f"}` was captioned with
 * no specifier at all.
 *
 * The order is upstream's: a **custom** format type first, being the name of a function the page
 * registered rather than a specifier; then the theme's own writer, and only where the header asked
 * for neither of its own; then the clock, where a stated specifier beats the bucketing; then the
 * number, where the theme's format applies to a measured column alone.
 *
 * One specification in the wild corpus captions a trellis of months that way. Every expectation was
 * compiled with upstream rather than reasoned about.
 */
class HeaderFormatTest {

  /** The caption expression, wherever the header's group sits. */
  private fun caption(column: String, config: String = ""): String {
    val spec =
      """{"data":{"values":[{"m":"2020-05-20","g":"a","v":8}]},"mark":"bar",$config
         "encoding":{"column":$column,
                     "x":{"field":"g","type":"nominal"},
                     "y":{"field":"v","type":"quantitative"}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun titles(marks: VegaValue?): List<String> =
      (marks as? VegaValue.Arr)?.values.orEmpty().flatMap { mark ->
        mark as VegaValue.Obj
        listOfNotNull(mark.obj("title")?.obj("text")?.string("signal")) +
          titles(mark.fields["marks"])
      }
    return titles(compiled.fields["marks"]).joinToString(" | ")
  }

  /** The reported shape: a trellis of months captioned the way the header asks. */
  @Test
  fun `a stated time format writes the caption`() {
    assertEquals(
      """timeFormat(parent["m"], "%b %y")""",
      caption(
        """{"field":"m","type":"temporal","header":{"format":"%b %y","formatType":"time"}}"""
      ),
    )
  }

  /** The specifier alone, `"time"` being what a date is written with anyway. */
  @Test
  fun `a stated format without a type writes the caption too`() {
    assertEquals(
      """timeFormat(parent["m"], "%b %y")""",
      caption("""{"field":"m","type":"temporal","header":{"format":"%b %y"}}"""),
    )
  }

  /** Where the header states none, the theme's full date — which is what must not change. */
  @Test
  fun `a date with no stated format keeps the full date`() {
    assertEquals(
      """timeFormat(parent["m"], "%b %d, %Y")""",
      caption("""{"field":"m","type":"temporal"}"""),
    )
  }

  /** A **bucketed** date is captioned by its bucket, the specifier resolved at render time. */
  @Test
  fun `a bucketed date keeps the bucket's own specifier`() {
    assertEquals(
      """timeFormat(parent["month_m"], timeUnitSpecifier(["month"], """ +
        """{"year-month":"%b %Y ","year-month-date":"%b %d, %Y "}))""",
      caption("""{"field":"m","type":"temporal","timeUnit":"month"}"""),
    )
  }

  /** And a stated specifier beats the bucketing: "or if user explicitly specifies format". */
  @Test
  fun `a stated format beats the bucket's specifier`() {
    assertEquals(
      """timeFormat(parent["month_m"], "%b %y")""",
      caption("""{"field":"m","type":"temporal","timeUnit":"month","header":{"format":"%b %y"}}"""),
    )
  }

  /** A **custom** type names a function the page registered, and it is answered first of all. */
  @Test
  fun `a custom format type writes a date too`() {
    assertEquals(
      """myFn(parent["m"], "x")""",
      caption("""{"field":"m","type":"temporal","header":{"format":"x","formatType":"myFn"}}"""),
    )
  }

  /** Called without a specifier where the header states none. */
  @Test
  fun `a custom format type with no specifier is called with none`() {
    assertEquals(
      """myFn(parent["m"])""",
      caption("""{"field":"m","type":"temporal","header":{"formatType":"myFn"}}"""),
    )
  }

  /** `"number"` is not a custom type: a category with a stated specifier is written as a number. */
  @Test
  fun `a number format type over a category writes a number`() {
    assertEquals(
      """format(parent["g"], ".2f")""",
      caption("""{"field":"g","type":"nominal","header":{"format":".2f","formatType":"number"}}"""),
    )
  }

  /** A custom type over a category, which is the same first arm. */
  @Test
  fun `a custom format type over a category`() {
    assertEquals(
      """myFn(parent["g"], "x")""",
      caption("""{"field":"g","type":"nominal","header":{"format":"x","formatType":"myFn"}}"""),
    )
  }

  /** A measured column with nothing stated anywhere: `format` with no specifier. */
  @Test
  fun `a measured column with no format`() {
    assertEquals("""format(parent["v"], "")""", caption("""{"field":"v","type":"quantitative"}"""))
  }

  /** The theme's own number format, which a measured column takes. */
  @Test
  fun `the themed number format writes a measured caption`() {
    assertEquals(
      """format(parent["v"], ".3f")""",
      caption("""{"field":"v","type":"quantitative"}""", """"config":{"numberFormat":".3f"},"""),
    )
  }

  /** A **bucket** with nothing stated: both edges, an en dash between them. */
  @Test
  fun `a bucket with no format`() {
    assertEquals(
      """!isValid(parent["bin_maxbins_6_v"]) || !isFinite(+parent["bin_maxbins_6_v"]) ? "null" : """ +
        """format(parent["bin_maxbins_6_v"], "") + " – " + """ +
        """format(parent["bin_maxbins_6_v_end"], "")""",
      caption("""{"field":"v","type":"quantitative","bin":true}"""),
    )
  }

  /** A bucket the header states a specifier for, which was written with none. */
  @Test
  fun `a stated format writes both edges of a bucket`() {
    assertEquals(
      """!isValid(parent["bin_maxbins_6_v"]) || !isFinite(+parent["bin_maxbins_6_v"]) ? "null" : """ +
        """format(parent["bin_maxbins_6_v"], ".2f") + " – " + """ +
        """format(parent["bin_maxbins_6_v_end"], ".2f")""",
      caption("""{"field":"v","type":"quantitative","bin":true,"header":{"format":".2f"}}"""),
    )
  }

  /** And a custom type writes both edges with the registered function. */
  @Test
  fun `a custom format type writes both edges of a bucket`() {
    assertEquals(
      """!isValid(parent["bin_maxbins_6_v"]) || !isFinite(+parent["bin_maxbins_6_v"]) ? "null" : """ +
        """myFn(parent["bin_maxbins_6_v"], ".2f") + " – " + """ +
        """myFn(parent["bin_maxbins_6_v_end"], ".2f")""",
      caption(
        """{"field":"v","type":"quantitative","bin":true,""" +
          """"header":{"format":".2f","formatType":"myFn"}}"""
      ),
    )
  }

  /** The theme's number format reaches a bucket's edges, which it did not. */
  @Test
  fun `the themed number format writes both edges of a bucket`() {
    assertEquals(
      """!isValid(parent["bin_maxbins_6_v"]) || !isFinite(+parent["bin_maxbins_6_v"]) ? "null" : """ +
        """format(parent["bin_maxbins_6_v"], ".3f") + " – " + """ +
        """format(parent["bin_maxbins_6_v_end"], ".3f")""",
      caption(
        """{"field":"v","type":"quantitative","bin":true}""",
        """"config":{"numberFormat":".3f"},""",
      ),
    )
  }

  /**
   * A theme naming a writer without asking for custom types to be honoured writes a plain number:
   * `customFormatTypes` is the safety catch, an unregistered name being a runtime error on every
   * caption.
   */
  @Test
  fun `a themed writer is not honoured unless the theme asks for it`() {
    assertEquals(
      """!isValid(parent["bin_maxbins_6_v"]) || !isFinite(+parent["bin_maxbins_6_v"]) ? "null" : """ +
        """format(parent["bin_maxbins_6_v"], ".3f") + " – " + """ +
        """format(parent["bin_maxbins_6_v_end"], ".3f")""",
      caption(
        """{"field":"v","type":"quantitative","bin":true}""",
        """"config":{"numberFormat":".3f","numberFormatType":"myN"},""",
      ),
    )
  }

  /**
   * A grid split on a bucket and **not** told what the column measures: `defaultType` answers
   * `nominal` for a facet channel however the field is measured, so the theme's own writer is asked
   * for by the bucket itself rather than by the arm that asks the column's type.
   */
  @Test
  fun `a themed writer reaches an untyped bucket`() {
    assertEquals(
      """!isValid(parent["bin_maxbins_4_v"]) || !isFinite(+parent["bin_maxbins_4_v"]) ? "null" : """ +
        """pow(parent["bin_maxbins_4_v"], "1.0") + " – " + """ +
        """pow(parent["bin_maxbins_4_v_end"], "1.0")""",
      caption(
        """{"field":"v","bin":{"maxbins":4}}""",
        """"config":{"numberFormat":"1.0","numberFormatType":"pow","customFormatTypes":true},""",
      ),
    )
    assertEquals(
      """!isValid(parent["bin_maxbins_4_v"]) || !isFinite(+parent["bin_maxbins_4_v"]) ? "null" : """ +
        """format(parent["bin_maxbins_4_v"], "") + " – " + """ +
        """format(parent["bin_maxbins_4_v_end"], "")""",
      caption("""{"field":"v","bin":{"maxbins":4}}"""),
    )
  }

  /**
   * And the theme's plain number format reaches the same untyped bucket, `binNumberFormatExpr`
   * falling back to it a second time where the column's own type would not have asked for it.
   */
  @Test
  fun `the themed number format reaches an untyped bucket`() {
    assertEquals(
      """!isValid(parent["bin_maxbins_4_v"]) || !isFinite(+parent["bin_maxbins_4_v"]) ? "null" : """ +
        """format(parent["bin_maxbins_4_v"], ".3f") + " – " + """ +
        """format(parent["bin_maxbins_4_v_end"], ".3f")""",
      caption("""{"field":"v","bin":{"maxbins":4}}""", """"config":{"numberFormat":".3f"},"""),
    )
  }

  /**
   * A specifier the header **states** is a statement that `format` is to write it, so the theme's
   * own writer stands aside: `if (format === undefined && formatType === undefined && …)`.
   */
  @Test
  fun `a stated format stands the themed writer down`() {
    val themed =
      """"config":{"numberFormat":".3f","numberFormatType":"myN","customFormatTypes":true},"""
    assertEquals(
      """format(parent["v"], ".2f")""",
      caption("""{"field":"v","type":"quantitative","header":{"format":".2f"}}""", themed),
    )
    assertEquals(
      """!isValid(parent["bin_maxbins_6_v"]) || !isFinite(+parent["bin_maxbins_6_v"]) ? "null" : """ +
        """format(parent["bin_maxbins_6_v"], ".2f") + " – " + """ +
        """format(parent["bin_maxbins_6_v_end"], ".2f")""",
      caption(
        """{"field":"v","type":"quantitative","bin":true,"header":{"format":".2f"}}""",
        themed,
      ),
    )
  }

  /** And is honoured where it does. */
  @Test
  fun `a themed writer the theme asks for writes both edges`() {
    assertEquals(
      """!isValid(parent["bin_maxbins_6_v"]) || !isFinite(+parent["bin_maxbins_6_v"]) ? "null" : """ +
        """myN(parent["bin_maxbins_6_v"], ".3f") + " – " + """ +
        """myN(parent["bin_maxbins_6_v_end"], ".3f")""",
      caption(
        """{"field":"v","type":"quantitative","bin":true}""",
        """"config":{"numberFormat":".3f","numberFormatType":"myN","customFormatTypes":true},""",
      ),
    )
  }
}
