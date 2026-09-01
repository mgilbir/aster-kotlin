package dev.aster.vega.runtime.scale

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.locale.VegaCaptions
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.model.time.DateValues
import dev.aster.vega.model.time.TimeInterval
import dev.aster.vega.model.time.TimeStepper
import kotlinx.datetime.TimeZone
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * Time scales: reading dates, choosing calendar ticks, and labelling them.
 *
 * Every expected value was read off d3 — which is what upstream Vega uses — running the same input,
 * so these are reference vectors rather than restatements of the implementation.
 */
class TimeScaleTest {

  private val utc: TimeZone = TimeZone.UTC

  private fun at(iso: String): Double = requireNotNull(DateValues.parseIso(iso)) { iso }

  private fun isoOf(millis: Double): String = TimeTicks.format(millis, "%Y-%m-%dT%H:%M:%S.%L", utc)

  // ---- reading dates ------------------------------------------------------------

  @ParameterizedTest
  @CsvSource(
    "2026,2026-01-01T00:00:00.000",
    "2026-03,2026-03-01T00:00:00.000",
    "2026-03-14,2026-03-14T00:00:00.000",
    "2026-03-14T09:30:00Z,2026-03-14T09:30:00.000",
    "2026-03-14T09:30:20.25Z,2026-03-14T09:30:20.250",
    "2026-03-14T09:30:00+02:00,2026-03-14T07:30:00.000",
    "2026-03-14T09:30:00-0500,2026-03-14T14:30:00.000",
  )
  fun `ISO dates are read into epoch milliseconds`(input: String, expected: String) {
    assertEquals(expected, isoOf(at(input)))
  }

  @Test
  fun `a date with no time is UTC and one with a wall clock is local`() {
    // JavaScript's rule, which upstream inherits: a bare date is UTC midnight, but the moment a
    // time
    // is present with no zone it is read as local. The difference is a whole day at the extremes,
    // so
    // it decides which month a bar lands in.
    assertEquals(at("2026-03-14T00:00:00Z"), at("2026-03-14"))
    // Reading the same wall clock in a zone two hours east lands two hours earlier in absolute
    // time.
    val east = TimeZone.of("UTC+02:00")
    assertEquals(
      at("2026-03-14T00:00:00Z") - 2 * 3_600_000.0,
      requireNotNull(DateValues.parseIso("2026-03-14T00:00:00", east)),
      1e-9,
    )
  }

  @Test
  fun `an unreadable date is refused rather than guessed at`() {
    assertNull(DateValues.parseIso("last Tuesday"))
    assertNull(DateValues.parseIso("14/03/2026"))
    assertNull(DateValues.parse(VegaValue.Str("not a date")))
    // A number is already an instant.
    assertEquals(VegaValue.Num(1234.0), DateValues.parse(VegaValue.Num(1234.0)))
  }

  // ---- ticks --------------------------------------------------------------------

  /**
   * Which calendar boundaries a domain is ticked at, read off d3.
   *
   * The interval is chosen from a fixed table by whichever lands closest to the requested count *on
   * a ratio*, which is why seven months of data ticks monthly rather than weekly: monthly is twice
   * too coarse where weekly would be four times too fine.
   */
  @ParameterizedTest
  @CsvSource(
    "2026-01-01T00:00:00Z,2026-01-01T00:00:10Z,5,11,2026-01-01T00:00:00.000,2026-01-01T00:00:01.000",
    "2026-01-01T00:00:00Z,2026-01-01T00:10:00Z,5,11,2026-01-01T00:00:00.000,2026-01-01T00:01:00.000",
    "2026-01-01T00:00:00Z,2026-01-02T00:00:00Z,4,5,2026-01-01T00:00:00.000,2026-01-01T06:00:00.000",
    "2026-01-01T00:00:00Z,2026-01-15T00:00:00Z,5,8,2026-01-01T00:00:00.000,2026-01-03T00:00:00.000",
    "2026-01-15T00:00:00Z,2026-08-05T00:00:00Z,10,7,2026-02-01T00:00:00.000,2026-03-01T00:00:00.000",
    "2020-01-01T00:00:00Z,2026-01-01T00:00:00Z,5,7,2020-01-01T00:00:00.000,2021-01-01T00:00:00.000",
    "1990-01-01T00:00:00Z,2026-01-01T00:00:00Z,4,4,1990-01-01T00:00:00.000,2000-01-01T00:00:00.000",
  )
  fun `ticks land on the calendar boundaries d3 chooses`(
    from: String,
    to: String,
    count: Int,
    size: Int,
    first: String,
    second: String,
  ) {
    val ticks = TimeTicks.ticks(at(from), at(to), count, utc)
    assertEquals(size, ticks.size, "$from..$to at $count")
    assertEquals(first, isoOf(ticks.first()))
    assertEquals(second, isoOf(ticks[1]))
  }

  @Test
  fun `nice widens the domain to the interval it will be ticked at`() {
    val (lo, hi) = TimeTicks.nice(at("2026-01-15T09:20:00Z"), at("2026-08-05T17:40:00Z"), 10, utc)
    assertEquals("2026-01-01T00:00:00.000", isoOf(lo))
    assertEquals("2026-09-01T00:00:00.000", isoOf(hi))
  }

  // ---- labels -------------------------------------------------------------------

  /**
   * Each tick is labelled at its own granularity, not the axis's.
   *
   * That is what puts the year back on a monthly axis at every January and the date back on an
   * hourly one at every midnight: the reader gets the context exactly where it changes.
   */
  @ParameterizedTest
  @CsvSource(
    "2026-01-01T00:00:00Z,2026",
    "2026-02-01T00:00:00Z,February",
    "2026-02-08T00:00:00Z,Feb 08",
    "2026-02-10T00:00:00Z,Tue 10",
    "2026-02-10T13:00:00Z,01 PM",
    "2026-02-10T13:45:00Z,01:45",
    "2026-02-10T13:45:20Z,':20'",
    "2026-02-10T13:45:20.250Z,.250",
  )
  fun `a tick is labelled at its own granularity`(instant: String, expected: String) {
    assertEquals(expected, TimeTicks.label(at(instant), utc))
  }

  /**
   * A locale writing an unpadded day gets an unpadded day **on the drawn tick**.
   *
   * The model half of this is `LocaleDatePatternTest`; this is the half a reader sees. The cascade
   * reads `locale.timeTickFormats[step]` and falls back to d3's padded default, so a derivation
   * that dropped the width was invisible until it reached a label. `3 mei` was asked for by the
   * locale and `03 mei` was drawn. See #151.
   */
  @Test
  fun `a tick keeps the day width the locale's pattern states`() {
    // Sunday-first, which is what `%a` indexes and what `TimeInterval.WEEK` floors to.
    val dutch =
      VegaLocale(
        months =
          listOf(
            "januari",
            "februari",
            "maart",
            "april",
            "mei",
            "juni",
            "juli",
            "augustus",
            "september",
            "oktober",
            "november",
            "december",
          ),
        shortMonths =
          listOf(
            "jan",
            "feb",
            "mrt",
            "apr",
            "mei",
            "jun",
            "jul",
            "aug",
            "sep",
            "okt",
            "nov",
            "dec",
          ),
        days =
          listOf("zondag", "maandag", "dinsdag", "woensdag", "donderdag", "vrijdag", "zaterdag"),
        shortDays = listOf("zo", "ma", "di", "wo", "do", "vr", "za"),
        periods = listOf("AM", "PM"),
        date = "%-d %b %Y",
        time = "%H:%M:%S",
        captions = VegaCaptions.English,
      )
    // A Sunday, so the cascade takes the week entry; and a Tuesday, so it takes the day entry.
    assertEquals("3 mei", TimeTicks.label(at("2026-05-03T00:00:00Z"), utc, dutch))
    // Day-first, so the weekday trails the number the way the locale writes a date, which is the
    // order this derivation has always produced — the width is what changed.
    assertEquals("5 di", TimeTicks.label(at("2026-05-05T00:00:00Z"), utc, dutch))

    // And the padded default still applies to a locale that states one, so this is a locale being
    // honoured rather than padding being dropped everywhere.
    assertEquals("Feb 08", TimeTicks.label(at("2026-02-08T00:00:00Z"), utc))
  }

  // ---- the scale ----------------------------------------------------------------

  @Test
  fun `a time scale positions instants linearly`() {
    val scale =
      TimeScale(
        "t",
        listOf(at("2026-01-01T00:00:00Z"), at("2026-01-03T00:00:00Z")),
        listOf(0.0, 100.0),
        utc,
      )
    assertEquals(0.0, scale.apply(at("2026-01-01T00:00:00Z")), 1e-9)
    assertEquals(50.0, scale.apply(at("2026-01-02T00:00:00Z")), 1e-9)
    assertEquals(100.0, scale.apply(at("2026-01-03T00:00:00Z")), 1e-9)
    assertEquals(at("2026-01-02T00:00:00Z"), scale.invert(50.0), 1e-6)
  }

  @Test
  fun `a day is a calendar day, not a fixed number of milliseconds`() {
    // The reason this goes through a calendar at all. In a zone that observes daylight saving, one
    // of
    // these days is 23 hours long, so stepping by 86,400,000 milliseconds would drift the ticks off
    // midnight and leave an axis labelled 01 AM.
    val berlin = TimeZone.of("Europe/Berlin")
    val stepper = TimeStepper(TimeInterval.DAY, 1, berlin)
    val springForward = requireNotNull(DateValues.parseIso("2026-03-28T12:00:00Z"))
    val ticks = stepper.range(springForward, springForward + 4 * 86_400_000.0)
    assertTrue(ticks.size >= 3)
    for (tick in ticks) {
      assertEquals("00:00", TimeTicks.format(tick, "%H:%M", berlin), "every tick is local midnight")
    }
    // And at least one gap is not 24 hours, which is what makes the calendar necessary.
    val gaps = ticks.zipWithNext { a, b -> b - a }
    assertTrue(gaps.any { it != 86_400_000.0 }, "expected a short or long day, got $gaps")
  }

  @Test
  fun `a stepped interval snaps to its natural origin`() {
    // Three-month ticks land on January, April, July and October rather than wherever the domain
    // starts, which is what makes two charts of different date ranges comparable.
    val ticks = TimeTicks.ticks(at("2026-02-10T00:00:00Z"), at("2027-06-01T00:00:00Z"), 5, utc)
    assertEquals(
      listOf("2026-04-01", "2026-07-01", "2026-10-01", "2027-01-01", "2027-04-01"),
      ticks.map { TimeTicks.format(it, "%Y-%m-%d", utc) },
    )
  }
}
