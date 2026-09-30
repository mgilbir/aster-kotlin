package dev.aster.vega.model.time

import dev.aster.vega.model.locale.VegaLocale
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * The calendar units a specification can bucket dates into, and the format each one is read with.
 *
 * Upstream's `vega-time/src/units.js`. The table is not a convenience: `timeunit` produces buckets
 * whose *value* is an instant, so the only thing that says a bucket labelled `1325372400000` is a
 * Sunday rather than a January is the specifier chosen for the units it was built from.
 */
public object TimeUnits {

  /**
   * The units in the order upstream sorts them, coarsest first.
   *
   * The order is what makes a compound specifier readable — `["date", "year"]` has to come out as
   * `%Y-%m-%d` and not as the two written backwards — and it is why the lookup below can be greedy.
   */
  public val ALL: List<String> =
    listOf(
      "year",
      "quarter",
      "month",
      "week",
      // ISO 8601's Monday-based week, beside the Sunday-based one. Vega 6.4.0 (vega/vega#4320).
      "isoweek",
      "date",
      "day",
      "dayofyear",
      "hours",
      "minutes",
      "seconds",
      "milliseconds",
    )

  /**
   * How each unit, or each recognised *run* of units, is written.
   *
   * The trailing spaces are load-bearing: the pieces are concatenated and the result trimmed, so
   * `["month", "date"]` reads `%b %d` while `["hours", "minutes"]` — matched as one key — reads
   * `%H:%M` with no gap.
   */
  private val SPECIFIERS =
    mapOf(
      "year" to "%Y ",
      "quarter" to "Q%q ",
      "month" to "%b ",
      "date" to "%d ",
      "week" to "W%U ",
      "isoweek" to "W%V ",
      "day" to "%a ",
      "dayofyear" to "%j ",
      "hours" to "%H:00",
      "minutes" to "00:%M",
      "seconds" to ":%S",
      "milliseconds" to ".%L",
      // `%G`, not `%Y`: the year an ISO week belongs to is the one holding its Thursday, so 29
      // December 2014 is week 1 of 2015 and has to be labelled so.
      "year-isoweek" to "%G W%V ",
      "year-month" to "%Y-%m ",
      "year-month-date" to "%Y-%m-%d ",
      "hours-minutes" to "%H:%M",
    )

  /**
   * `timeUnitSpecifier(units, specifiers)` — the time format a set of units is labelled with.
   *
   * The match is greedy over the *longest* run of units that has an entry, which is what collapses
   * `["year", "month", "date"]` into one `%Y-%m-%d` instead of three separate fields. [overrides]
   * replaces an entry rather than extending the table, and a chart uses it to shorten one unit
   * without restating the rest — `{hours: '%H'}` drops the `:00`.
   *
   * Unrecognised units are dropped rather than rejected, so a specification naming one gets a
   * shorter label rather than no chart.
   */
  /**
   * `inferUnits`: the finest granularity every instant in the data is **aligned to**.
   *
   * A transcription of upstream's `detectTimeUnits`, table and all, because the answer is a *label
   * format* — infer `date` for a series of month starts and every tick reads "Jan 1, Feb 1" where
   * upstream reads "January, February". This engine had no inference at all: it fell through to the
   * extent-binning path, which chooses by span rather than by alignment and answered `date` for
   * monthly data.
   *
   * The table is read in order and the *last aligned* grain wins, with one wrinkle worth keeping:
   * the weekly grain is **skippable**, so a run of dates that is not weekly does not stop the
   * search — `required > mismatch + 1` lets the scan step over it and still reach the monthly grain
   * below.
   */
  public fun detect(instants: List<Double>, zone: TimeZone): Pair<List<String>, Int> {
    if (instants.isEmpty()) return listOf("year") to 1
    val dates = instants.map { Instant.fromEpochMilliseconds(it.toLong()).toLocalDateTime(zone) }
    // JavaScript's `getDay` is 0 for Sunday; `isoDayNumber` is 7 for it.
    fun weekday(at: LocalDateTime) = at.date.dayOfWeek.isoDayNumber % 7
    fun millisecond(at: LocalDateTime) = at.nanosecond / 1_000_000

    val grains: List<Grain> =
      listOf(
        Grain(listOf("year", "month", "date", "hours", "minutes", "seconds", "milliseconds"), 1) {
          true
        },
        Grain(listOf("year", "month", "date", "hours", "minutes", "seconds"), 1) { all ->
          all.all { millisecond(it) == 0 }
        },
        Grain(listOf("year", "month", "date", "hours", "minutes"), 1) { all ->
          all.all { it.second == 0 }
        },
        Grain(listOf("year", "month", "date", "hours", "minutes"), 5) { all ->
          all.all { it.minute % 5 == 0 }
        },
        Grain(listOf("year", "month", "date", "hours", "minutes"), 10) { all ->
          all.all { it.minute % 10 == 0 }
        },
        Grain(listOf("year", "month", "date", "hours"), 1) { all -> all.all { it.minute == 0 } },
        Grain(listOf("year", "month", "date"), 1) { all -> all.all { it.hour == 0 } },
        Grain(listOf("year", "week"), 1, skippable = true) { all ->
          all.map { weekday(it) }.distinct().size == 1
        },
        Grain(listOf("year", "month"), 1) { all -> all.all { it.date.day == 1 } },
        Grain(listOf("year", "month"), 3) { all ->
          all.all { (it.date.month.number - 1) % 3 == 0 }
        },
        Grain(listOf("year"), 1) { all -> all.all { it.date.month.number == 1 } },
        Grain(listOf("year"), 10) { all -> all.all { it.date.year % 10 == 0 } },
        Grain(emptyList(), 1) { false },
      )

    val mismatch = grains.indexOfFirst { !it.aligned(dates) }
    val required = grains.indexOfFirst { !it.skippable && !it.aligned(dates) }
    val index = if (required > mismatch + 1) required else mismatch
    val chosen = grains[(index - 1).coerceAtLeast(0)]
    return chosen.units to chosen.step
  }

  private class Grain(
    val units: List<String>,
    val step: Int,
    val skippable: Boolean = false,
    private val test: (List<LocalDateTime>) -> Boolean,
  ) {
    fun aligned(dates: List<LocalDateTime>): Boolean = test(dates)
  }

  public fun specifier(
    units: List<String>,
    overrides: Map<String, String?> = emptyMap(),
    /**
     * The host's own table, under [overrides] and over the defaults.
     *
     * The lever this table had none of. `%b` has always resolved to the locale's month
     * abbreviation, so a Dutch axis said `mei`; the *order* of the fields did not move, because
     * upstream's `timeUnitSpecifier` takes no locale and this is a transcription of it. Null keeps
     * that exactly, which is what the differential fixtures compare against.
     *
     * Under a specification's own [overrides] on purpose: a document writing
     * `timeUnitSpecifier(units, {...})` asked for that by name, and a host's language preference is
     * a default beneath it rather than an override of it. Vega-Lite's generated table is the one
     * exception, and it is not an exception in this function — `Fields.timeUnitSpecifier` builds it
     * *from* the locale, so the two agree by construction rather than by precedence.
     */
    locale: VegaLocale? = null,
  ): String {
    // Nullable on purpose. Upstream builds the table with `extend({}, defaults, specifiers)` and
    // then
    // tests `s[key] != null`, so an override set to **null** does not fall back to the default — it
    // *removes* the entry, and the search drops to a shorter run of units. That is the only way to
    // say "do not combine these two", and `timeUnitSpecifier(['hours','minutes'], {'hours-minutes':
    // null})` is `%H %Mmin` upstream where taking the built-in combination gives `%H:%M`. The
    // signature used to be `Map<String, String>`, which cannot express it at all; upstream's own
    // test vectors are what caught it.
    val table: Map<String, String?> = SPECIFIERS + locale?.timeUnitSpecifiers.orEmpty() + overrides
    val ordered = ALL.filter { it in units }
    val out = StringBuilder()
    var start = 0
    while (start < ordered.size) {
      var end = ordered.size
      while (end > start) {
        val entry = table[ordered.subList(start, end).joinToString("-")]
        if (entry != null) {
          out.append(entry)
          break
        }
        end--
      }
      // No run beginning here is named, so nothing this unit could add is known; skip past it.
      start = if (end > start) end else start + 1
    }
    return out.toString().trim()
  }

  /**
   * The ISO 8601 week [date] falls in: weeks start on Monday, and week 1 is the one holding the
   * year's first Thursday.
   *
   * One implementation for every place that asks — `%V`, the `isoweek` time unit and the
   * `isoweek()` expression function — because upstream's three are one rule, and two spellings of a
   * calendar rule drift. Upstream counts Mondays from the Monday of week 1 (`localISOWeekNum`);
   * counting from the week's Thursday instead is the same number without a DST-sensitive count.
   */
  public fun isoWeek(date: LocalDate): Int {
    val thursday = date.plus(4 - date.dayOfWeek.isoDayNumber, DateTimeUnit.DAY)
    return (thursday.dayOfYear - 1) / 7 + 1
  }

  /** The year an ISO week belongs to, `%G`: the year of that week's Thursday. */
  public fun isoWeekYear(date: LocalDate): Int =
    date.plus(4 - date.dayOfWeek.isoDayNumber, DateTimeUnit.DAY).year

  /**
   * The day of January on which week 1 of the ISO week-numbering [year] begins — upstream's
   * `localISOWeekOneDate`.
   *
   * Zero or less names a day of the December before: 2015's week 1 begins on 29 December 2014, so
   * this answers -2, and a date rebuilt as "January the 1st plus that many days, less one" lands on
   * it. Upstream relies on `Date` rolling the day over; this answers the same number for the same
   * reason.
   */
  public fun isoWeekOneDate(year: Int): Int {
    val fourth = LocalDate(year, 1, 4)
    val monday = fourth.minus(fourth.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)
    return if (monday.year < year) monday.day - 31 else monday.day
  }
}
