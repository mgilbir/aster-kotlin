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
 * **Checked here rather than by a fixture, and the reason is worth stating.** Every chart these
 * rules need is degenerate — a number format over a category, a time format over a category, a time
 * scale over unparsed text — and upstream's own rendering of them lays out eight pixels differently
 * from this runtime's. That difference is real and unexplained; it is **not** what these rules are
 * about, and a fixture carrying it would fail the scene comparison for a reason that has nothing to
 * do with them. The rules are about the specification that is emitted, so that is what is asserted.
 * The scene difference is recorded in the changelog as its own open question.
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
