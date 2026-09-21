package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A guide's `formatType` reaches further than its own labels.
 *
 * `getFormatMixins` reads the **guide's** pair for anything that is not a plain string definition —
 * `const guide = getGuide(fieldDef) ?? {}; const {format, formatType} = guide;` — so an `axis`
 * block settles it for the whole channel, and two rules turn on what it says.
 *
 * `addLineBreaksToTooltip` uses the array-aware form only for a discrete field with no time unit
 * and `!getFormatMixins(channelDef).format && !getFormatMixins(channelDef).formatType`, so a
 * category whose axis names a format type is spoken plainly rather than joined. This compiler
 * checked the format and not the type, and read out a list where upstream reads a word.
 *
 * `isFieldOrDatumDefForTimeFormat` is `formatType === 'time' || (!formatType &&
 * isTemporalFieldDef(fieldOrDatumDef))`, and it decides both whether a column is parsed into dates
 * at all and whether it is spoken as one. A temporal field whose axis says `number` is therefore
 * **not** parsed: upstream emits the source rows untouched, with no formula and no dataset derived
 * from one, and leaves a time scale standing over the raw strings.
 *
 * **The reason this was a test and not a fixture is gone, and both now exist.** Every chart these
 * rules need is degenerate — a number format over a category, a time format over a category, a time
 * scale over unparsed text — and upstream's rendering of them was eight pixels taller than this
 * runtime's for a reason recorded as unexplained. The reason was a **label over a value that is not
 * an instant**: `formatType: "time"` has the column parsed with `toDate`, `Date.parse` of a word is
 * `NaN` rather than nothing, and d3 prints `0NaN` for it where this engine printed one character
 * fewer — which on labels turned on their side is the height of the chart. With that fixed the
 * shapes are comparable as drawings, and `a-format-type-decides-the-parse.vl.json` and
 * `a-date-that-is-not-a-date.vl.json` arm both gates on them.
 *
 * This stays because it says which rule broke rather than that something did: the assertions below
 * name the description signal and the absent dataset, where a fixture compares a whole chart.
 *
 * Every expectation below was read off upstream rather than reasoned about.
 */
class GuideFormatTypeTest {

  private fun compiled(specification: String): VegaValue.Obj =
    VegaJson.parse(
      requireNotNull(VegaLiteCompiler().compileJson(specification).toJson()) {
        "the specification did not compile"
      }
    ) as VegaValue.Obj

  private fun description(spec: VegaValue.Obj): String {
    val marks = spec.fields["marks"] as VegaValue.Arr
    val update =
      ((marks.values.first() as VegaValue.Obj).fields["encode"] as VegaValue.Obj).fields["update"]
        as VegaValue.Obj
    return ((update.fields["description"] as VegaValue.Obj).fields["signal"] as VegaValue.Str).value
  }

  private fun category(axis: String) =
    """
    {"data":{"values":[{"c":"a","v":1},{"c":"b","v":2}]},
     "mark":"bar",
     "encoding":{"x":{"field":"c","type":"nominal"$axis},
                 "y":{"field":"v","type":"quantitative"}}}
    """

  @Test
  fun `a category with no format type is spoken as a joined list`() {
    assertTrue(
      description(compiled(category(""))).contains("isArray"),
      "a discrete field with no format or format type takes the array-aware form",
    )
  }

  @Test
  fun `a category whose axis names a format type is spoken plainly`() {
    val signal = description(compiled(category(""","axis":{"formatType":"number"}""")))
    assertEquals(
      "\"c: \" + (isValid(datum[\"c\"]) ? datum[\"c\"] : \"\"+datum[\"c\"]) + " +
        "\"; v: \" + (format(datum[\"v\"], \"\"))",
      signal,
    )
  }

  @Test
  fun `a temporal field whose axis names a format type that is not time is never parsed`() {
    val spec =
      compiled(
        """
        {"data":{"values":[{"t":"2024-01-07","v":1},{"t":"2024-03-19","v":2}]},
         "mark":"line",
         "encoding":{"x":{"field":"t","type":"temporal","axis":{"formatType":"number"}},
                     "y":{"field":"v","type":"quantitative"}}}
        """
      )
    val data = spec.fields["data"] as VegaValue.Arr
    assertEquals(1, data.values.size, "upstream derives no dataset for the parse it does not do")
    val only = data.values.single() as VegaValue.Obj
    assertEquals(null, only.fields["transform"], "and writes no transform on the source")
    assertTrue(
      !description(spec).contains("timeFormat"),
      "the same rule decides the description: an unparsed column is not spoken as a date",
    )
  }

  @Test
  fun `a category whose axis names the time format type is spoken as a date`() {
    assertTrue(
      description(compiled(category(""","axis":{"formatType":"time"}"""))).contains("timeFormat"),
      "`formatType === 'time'` pulls a field into the time branch whatever its own type says",
    )
  }
}
