package dev.aster.vega.model.locale

import dev.aster.vega.model.time.TimeUnits
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `VegaLocale.EnglishUS` answers what upstream answers, and it is the only locale *told* to.
 *
 * This is the pin the whole locale divergence rests on. Every other locale now derives its date
 * order from its own `%x`, which is a deliberate departure — upstream's `timeUnitSpecifier` takes
 * no locale at all. `EnglishUS` states both tables empty instead, so it reproduces upstream
 * exactly, and it is the locale the differential fixtures and the recorded upstream vectors compare
 * against.
 *
 * Without this test the pin is a fact about a companion object that nothing reads. With it,
 * removing the pin fails here — before it fails in 283 fixture comparisons whose diff would be four
 * hundred moved labels and no explanation.
 *
 * The contradiction it is pinned *against* is worth writing down, because it looks like a mistake
 * and is upstream's: d3's `en-US` writes `%x` as `%-m/%-d/%Y`, and `vega-time`'s specifier table
 * writes a full date as `%Y-%m-%d`. Upstream disagrees with itself about the order, so a locale
 * that derived one from the other could not also reproduce it.
 */
class LocaleDefaultsTest {

  @Test
  fun `the upstream locale derives nothing`() {
    assertEquals(
      emptyMap<String, String?>(),
      VegaLocale.EnglishUS.timeUnitSpecifiers,
      "EnglishUS must state its specifier table, not derive one: it is what upstream answers",
    )
    assertEquals(
      emptyMap<String, String>(),
      VegaLocale.EnglishUS.timeTickFormats,
      "and the same for the tick cascade, which is d3's",
    )
    // The pinning is what does it rather than a coincidence about the pattern: `%x` here is
    // month-first and the table it must not derive from is ISO.
    assertEquals(
      listOf(DateField.MONTH, DateField.DATE, DateField.YEAR),
      VegaLocale.EnglishUS.dateFieldOrder,
    )
  }

  @Test
  fun `upstream's own specifiers come out of it`() {
    // Read off `vega-time/src/units.js`. `UpstreamTimeVectorsTest` replays upstream's own recorded
    // tests against the whole table; these are the entries a date order would have moved.
    fun of(vararg units: String) =
      TimeUnits.specifier(units.toList(), locale = VegaLocale.EnglishUS)

    assertEquals("%Y-%m-%d", of("year", "month", "date"))
    assertEquals("%Y-%m", of("year", "month"))
    assertEquals("%b %d", of("month", "date"))
    assertEquals("%H:%M", of("hours", "minutes"))
    assertEquals("%Y", of("year"))
  }

  @Test
  fun `a locale that states nothing derives, which is the divergence`() {
    // The counterpart, so this file says what the pin is a pin *against*. A host that copies d3's
    // `en-GB` locale JSON across gets its own order without having to ask for it.
    val britain =
      VegaLocale.EnglishUS.copy(
        date = "%d/%m/%Y",
        time = "%H:%M:%S",
        timeUnitSpecifierOverrides = null,
        timeTickFormatOverrides = null,
      )
    assertEquals("%d/%m/%Y", TimeUnits.specifier(listOf("year", "month", "date"), locale = britain))
    assertTrue(britain.timeTickFormats["hour"] == "%H:00", britain.timeTickFormats.toString())
  }
}
