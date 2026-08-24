package dev.aster.vega.model.time

import dev.aster.vega.model.locale.VegaCaptions
import dev.aster.vega.model.locale.VegaLocale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Reading a date's **order** off a locale's own `%x`.
 *
 * The fact that was going unread. `VegaLocale.date` is d3's `%x` — "the date order this language
 * writes" — and nothing that decided a label ever looked at it, so `TimeUnits`'s `%Y-%m-%d` and
 * Vega-Lite's `%b %d, %Y` were the order for every language. A Dutch chart said `mei 21, 2026`.
 *
 * The compound specifiers are the locale's pattern with fields **dropped** rather than a table
 * rebuilt out of directives, and these tests are mostly about that: which separator goes with a
 * dropped field is the whole of the arithmetic, and getting it wrong produces `%b, %Y`.
 */
class LocaleDatePatternTest {

  private fun localeWith(date: String, time: String = "%H:%M:%S") =
    VegaLocale(
      months = List(12) { "m$it" },
      shortMonths = List(12) { "m$it" },
      days = List(7) { "d$it" },
      shortDays = List(7) { "d$it" },
      periods = listOf("AM", "PM"),
      date = date,
      time = time,
      captions = VegaCaptions.English,
    )

  private fun specifiers(date: String) = localeWith(date).timeUnitSpecifiers

  @Test
  fun `a full date is the locale's own pattern`() {
    // d3's `en-GB`, `nl-NL` and an ISO-ordered locale, which are the three orders that exist.
    assertEquals("%d/%m/%Y ", specifiers("%d/%m/%Y")["year-month-date"])
    assertEquals("%d-%m-%Y ", specifiers("%d-%m-%Y")["year-month-date"])
    assertEquals("%Y-%m-%d ", specifiers("%Y-%m-%d")["year-month-date"])
    assertEquals("%-m/%-d/%Y ", specifiers("%-m/%-d/%Y")["year-month-date"])
  }

  @Test
  fun `dropping a field takes one separator with it`() {
    // A middle field takes the separator that **follows** it, so this is `%b %Y` and not `%b, %Y` —
    // which is the one way this could plausibly have been written and be wrong.
    assertEquals("%b %Y ", specifiers("%b %d, %Y")["year-month"])
    assertEquals("%b %d ", specifiers("%b %d, %Y")["month-date"])

    // Day first: dropping the leading date takes the separator after it.
    assertEquals("%m-%Y ", specifiers("%d-%m-%Y")["year-month"])
    assertEquals("%d-%m ", specifiers("%d-%m-%Y")["month-date"])

    // ISO: dropping the leading year, and dropping the trailing date.
    assertEquals("%m-%d ", specifiers("%Y-%m-%d")["month-date"])
    assertEquals("%Y-%m ", specifiers("%Y-%m-%d")["year-month"])
  }

  @Test
  fun `a suffix marker belongs to its field and is not dropped with the next one`() {
    // `zh-CN` writes the date with a marker after every number: the character is what makes the
    // number a month rather than text sitting between two fields. Dropping the day therefore has to
    // leave the month's marker behind. Taking it produced `%Y\u5e74%m`, which reads "2026\u5e7408"
    // and is
    // not a date in any language — and nothing caught it, because no bundled locale is written this
    // way and English cannot express the difference.
    val chinese = specifiers("%Y\u5e74%m\u6708%d\u65e5")
    assertEquals("%Y\u5e74%m\u6708%d\u65e5 ", chinese["year-month-date"])
    assertEquals("%Y\u5e74%m\u6708 ", chinese["year-month"])
    assertEquals("%m\u6708%d\u65e5 ", chinese["month-date"])

    // Korean puts a space after each marker. The marker stays; the space it carried has nothing
    // left to separate, so it goes.
    val korean = specifiers("%Y\ub144 %m\uc6d4 %d\uc77c")
    assertEquals("%Y\ub144 %m\uc6d4 %d\uc77c ", korean["year-month-date"])
    assertEquals("%Y\ub144 %m\uc6d4 ", korean["year-month"])
    assertEquals("%m\uc6d4 %d\uc77c ", korean["month-date"])
  }

  @Test
  fun `a connector is not a marker, whatever it is made of`() {
    // The two ways the marker rule could over-reach, and both are why it takes *two* conditions.
    //
    // Punctuation attached to a field is still a connector — it has no letter in it — so a trailing
    // field takes the separator before it and `%d-%m-%Y` without its year is `%d-%m`, not `%d-%m-`.
    assertEquals("%d-%m ", specifiers("%d-%m-%Y")["month-date"])
    assertEquals("%d/%m ", specifiers("%d/%m/%Y")["month-date"])

    // And a word *is* a letter but stands between the fields when a space precedes it, which is
    // Spanish: `%e de %B de %Y` without its year is `%e de %B`, not `%e de %B de `.
    assertEquals("%e de %B ", specifiers("%e de %B de %Y")["month-date"])
  }

  @Test
  fun `keeping every field reproduces the pattern`() {
    // The property that makes the rest of this trustworthy: whatever the separators are for, a
    // derivation that drops nothing must give the pattern back unchanged.
    for (pattern in
      listOf(
        "%b %d, %Y",
        "%d-%m-%Y",
        "%d/%m/%Y",
        "%Y-%m-%d",
        "%e de %B de %Y",
        "%Y\u5e74%m\u6708%d\u65e5",
        "%Y\ub144 %m\uc6d4 %d\uc77c",
      )) {
      assertEquals(pattern + " ", specifiers(pattern)["year-month-date"], pattern)
    }
  }

  @Test
  fun `prose between the fields survives`() {
    // `es-ES` writes `%e de %B de %Y`. A table of directives could not have produced this; the
    // pattern-with-fields-dropped rule gets it for nothing, which is why the rule is that.
    val spanish = specifiers("%e de %B de %Y")
    assertEquals("%e de %B de %Y ", spanish["year-month-date"])
    assertEquals("%B de %Y ", spanish["year-month"])
    assertEquals("%e de %B ", spanish["month-date"])
  }

  @Test
  fun `a directive that names no date field is text`() {
    // `%A` is a weekday name: it is not a field an order can drop, so it moves with whatever it
    // sits
    // beside. Dropping the year here has to leave the weekday attached to the month.
    val withWeekday = specifiers("%A, %-d %B %Y")
    assertEquals("%A, %-d %B %Y ", withWeekday["year-month-date"])
    assertEquals("%A, %-d %B ", withWeekday["month-date"])
    // A trailing literal is kept, since nothing follows the field it belongs to.
    assertEquals("%Y年%m月%d日 ", specifiers("%Y年%m月%d日")["year-month-date"])
    assertEquals("%m月%d日 ", specifiers("%Y年%m月%d日")["month-date"])
  }

  @Test
  fun `a pattern naming no date field derives nothing`() {
    // Not a date order, and must not be read as one: the caller keeps `TimeUnits.SPECIFIERS`.
    assertEquals(emptyMap<String, String?>(), specifiers("%s"))
    assertEquals(emptyMap<String, String?>(), specifiers(""))
  }

  @Test
  fun `the clock comes from the time pattern and the day order from the date`() {
    // `%I %p` is an afternoon in a place that writes 14:00, so a 24-hour `time` replaces the two
    // steps that show one.
    val dutch = localeWith(date = "%d-%m-%Y", time = "%H:%M:%S").timeTickFormats
    assertEquals("%H:00", dutch["hour"])
    assertEquals("%H:%M", dutch["minute"])
    // And a day-first language reads `21 mei` rather than `mei 21`.
    assertEquals("%d %b", dutch["week"])
    assertEquals("%d %a", dutch["day"])

    // A twelve-hour, month-first language agrees with d3's cascade, so it derives nothing at all
    // and
    // the map is empty — which is what keeps the default a default.
    assertEquals(
      emptyMap<String, String>(),
      localeWith(date = "%b %d, %Y", time = "%-I:%M:%S %p").timeTickFormats,
    )
  }

  @Test
  fun `a stated override replaces derivation entirely`() {
    val stated =
      localeWith(date = "%d-%m-%Y")
        .copy(
          timeUnitSpecifierOverrides = mapOf("year-month-date" to "%d/%m/%y "),
          timeTickFormatOverrides = mapOf("hour" to "%Hh"),
        )
    assertEquals(
      mapOf<String, String?>("year-month-date" to "%d/%m/%y "),
      stated.timeUnitSpecifiers,
    )
    assertEquals(mapOf("hour" to "%Hh"), stated.timeTickFormats)
  }
}
