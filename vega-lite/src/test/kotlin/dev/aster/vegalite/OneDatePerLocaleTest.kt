package dev.aster.vegalite

import dev.aster.vega.model.locale.VegaLocale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A locale gets one date, whichever grammar the document was written in.
 *
 * The two paths derived separately and disagreed. Vega read the locale's pattern; Vega-Lite read
 * only the field *order* off it and rebuilt the entry from its own directives, so a Dutch host got
 * `%d-%m-%Y` on a Vega chart and `%d %b %Y` on a Vega-Lite one — a numeric month against a named
 * one, from the same `VegaLocale`. Neither the host nor the reader could see which grammar had run,
 * since both arrive as the same document from the same endpoint. Reported as #97.
 *
 * The assertions here are equality between the two paths rather than a table of expected strings,
 * on purpose: the claim is that they agree, and a literal would let them drift together into
 * agreeing on something wrong. What the derivation itself should produce is
 * `LocaleDatePatternTest`.
 */
class OneDatePerLocaleTest {

  private fun deriving(date: String) =
    VegaLocale.EnglishUS.copy(date = date, timeUnitSpecifierOverrides = null)

  /** Every shape of date pattern the derivation has to survive. */
  private val patterns =
    listOf(
      "%b %d, %Y", // a month name, with a comma
      "%d-%m-%Y", // day first, numeric
      "%d/%m/%Y",
      "%Y-%m-%d", // ISO order
      "%-m/%-d/%Y", // padding modifiers
      "%e de %B de %Y", // prose between the fields
      "%Y年%m月%d日", // markers attached to each field
      "%Y년 %m월 %d일",
    )

  @Test
  fun `both grammars derive the same table from the same locale`() {
    for (pattern in patterns) {
      val locale = deriving(pattern)
      val vega = locale.timeUnitSpecifiers
      val vegaLite = Fields.timeUnitSpecifier("yearmonthdate", locale)
      for ((key, value) in vega) {
        assertTrue(
          vegaLite.contains("\"$key\":\"$value\""),
          "$pattern: Vega says $key=$value, Vega-Lite emitted $vegaLite",
        )
      }
    }
  }

  @Test
  fun `the locale's own separators and directives survive`() {
    // The three things the order-only rebuild threw away, named one at a time so a regression says
    // which. Each is read out of the Vega-Lite table, which is the side that used to lose them.
    val comma = Fields.timeUnitSpecifier("yearmonthdate", deriving("%b %d, %Y"))
    assertTrue(comma.contains("%b %d, %Y"), "the comma should survive: $comma")

    val numeric = Fields.timeUnitSpecifier("yearmonthdate", deriving("%d-%m-%Y"))
    assertTrue(numeric.contains("%d-%m-%Y"), "a numeric month should stay numeric: $numeric")

    val prose = Fields.timeUnitSpecifier("yearmonthdate", deriving("%e de %B de %Y"))
    assertTrue(prose.contains("%e de %B de %Y"), "the prose should survive: $prose")
  }

  @Test
  fun `English is upstream's own table, unchanged`() {
    // The locale the differential fixtures compare against. It states a map, so nothing is derived
    // for it and upstream's answer survives comma and all — two keys, in upstream's order.
    assertEquals(
      "timeUnitSpecifier([\"year\",\"month\",\"date\"], " +
        "{\"year-month\":\"%b %Y \",\"year-month-date\":\"%b %d, %Y \"})",
      Fields.timeUnitSpecifier("yearmonthdate", VegaLocale.EnglishUS),
    )
  }

  @Test
  fun `a stated override is honoured for every key it names`() {
    // It used to be filtered to the keys the fallback table happened to hold, which were only
    // `year-month` and `year-month-date` — so a host stating `month-date` had it honoured on a Vega
    // chart and silently dropped on a Vega-Lite one.
    val stated =
      VegaLocale.EnglishUS.copy(timeUnitSpecifierOverrides = mapOf("month-date" to "%d %b "))
    val emitted = Fields.timeUnitSpecifier("monthdate", stated)
    assertTrue(emitted.contains("\"month-date\":\"%d %b \""), emitted)
  }

  @Test
  fun `a stated override still leaves the other entries Vega-Lite's own`() {
    // Stating one entry of the table means the rest to stay what Vega-Lite would have said, rather
    // than collapsing to the single entry the host named.
    val stated =
      VegaLocale.EnglishUS.copy(timeUnitSpecifierOverrides = mapOf("month-date" to "%d %b "))
    val emitted = Fields.timeUnitSpecifier("yearmonthdate", stated)
    assertTrue(emitted.contains("\"year-month-date\":\"%b %d, %Y \""), emitted)
  }

  @Test
  fun `a pattern that names no date field falls back rather than deriving nothing`() {
    // `%s` is not a date order and must not be treated as one. Vega falls through to
    // `TimeUnits.SPECIFIERS` and gets numerals; this table exists so Vega-Lite gets month names.
    val unreadable = deriving("%s")
    assertTrue(unreadable.timeUnitSpecifiers.isEmpty(), "nothing should derive from %s")
    assertTrue(
      Fields.timeUnitSpecifier("yearmonthdate", unreadable).contains("%b %d, %Y "),
      "should fall back to upstream's table",
    )
  }
}
