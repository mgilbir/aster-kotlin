package dev.aster.vega.model.time

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.truncate
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/**
 * `new Date(year, month, …)`, as ECMA-262 defines it.
 *
 * Two places in this repository build an instant out of separate calendar fields, and both are
 * transcribing the *same* JavaScript expression: `vega-expression`'s `datetime()`/`utc()`, which
 * upstream implements as `new Date(...args)`, and `vega-lite`'s `dateTimeToTimestamp`, which
 * upstream implements as `+new Date(...dateTimeParts(d))`. They had drifted apart in three ways
 * each, so this is the one implementation both call.
 *
 * What makes it worth writing out rather than reaching for a calendar library is that a JavaScript
 * date constructor is not a calendar. Three of its rules have no equivalent in `kotlinx-datetime`:
 *
 * - **Every field rolls over.** `new Date(2012, 12, 1)` is January 2013; `new Date(2012, 1, 30)` is
 *   1 March; `new Date(2012, 0, 1, 24)` is 2 January; `new Date(2012, 0, 0)` is 31 December 2011.
 *   `LocalDateTime(2012, 13, 1)` throws, and that was the defect: a pasted specification writing
 *   `{"month": 13}` — legal input, and what a rollover *means* — took the host down through a
 *   module with no `try` in it.
 * - **A two-digit year is nineteen-hundreds.** `new Date(99, 1, 1)` is February 1999, and `new
 *   Date(0, 0, 1)` is January 1900. A specification that writes `{"year": 99}` means 1999 to every
 *   Vega renderer in existence, and meant the year 99 here.
 * - **Out of range is a value, not an error.** ECMA-262 clips to ±8.64e15 ms and calls the rest an
 *   *Invalid Date*, whose time value is NaN. So `{"month": 1000000000}` is NaN, not a throw.
 *
 * Everything below is the specification's own decomposition — `MakeDay`, `MakeTime`, `MakeDate`,
 * `TimeClip` — because reproducing the rollover rule in aggregate is exactly the kind of arithmetic
 * that is right for the cases someone thought of.
 */
public object JsDate {

  /**
   * ECMA-262's `TimeClip` bound: ±100,000,000 days either side of the epoch, in milliseconds.
   *
   * Which is ±271,821 years, and the reason the year and day guards below are the numbers they are.
   */
  public const val MAX_TIME_VALUE: Double = 8.64e15

  private const val MS_PER_DAY: Double = 86_400_000.0

  /** The widest year `MakeDay` can be asked for; outside it the answer is an Invalid Date. */
  private const val MIN_YEAR: Double = -271_822.0

  private const val MAX_YEAR: Double = 275_761.0

  /**
   * The widest day offset worth adding, past which the result cannot be in range anyway.
   *
   * A guard rather than a rule: `LocalDate.plus` throws on an offset a `Long` of days can hold but
   * a calendar cannot, and reaching that throw is the failure this whole file exists to avoid.
   */
  private const val MAX_DAY_OFFSET: Double = 200_000_000.0

  /**
   * The instant `new Date(year, month, date, hours, minutes, seconds, millis)` names, in [zone].
   *
   * NaN — an *Invalid Date* — for any argument that is not finite and for any result outside
   * ECMA-262's range, which is what every caller of this already has to handle, since a JavaScript
   * date arithmetic can produce one from ordinary-looking input.
   *
   * Pass `TimeZone.UTC` for `Date.UTC(…)`, which is the same function read in a different zone:
   * upstream's `utc()` and a Vega-Lite `{"utc": true}` are both spelled that way.
   */
  public fun make(
    year: Double,
    month: Double = 0.0,
    date: Double = 1.0,
    hours: Double = 0.0,
    minutes: Double = 0.0,
    seconds: Double = 0.0,
    millis: Double = 0.0,
    zone: TimeZone,
  ): Double {
    if (
      !year.isFinite() ||
        !month.isFinite() ||
        !date.isFinite() ||
        !hours.isFinite() ||
        !minutes.isFinite() ||
        !seconds.isFinite() ||
        !millis.isFinite()
    ) {
      return Double.NaN
    }

    // Step 8 of the constructor: `0 ≤ ToIntegerOrInfinity(y) ≤ 99` means 1900 + y. Note it is the
    // *truncated* year that is tested, so `new Date(99.9, …)` is 1999 as well.
    val truncatedYear = truncate(year)
    val calendarYear =
      if (truncatedYear >= 0.0 && truncatedYear <= 99.0) 1900.0 + truncatedYear else truncatedYear

    // `MakeDay`: the month is reduced into a year and a month-of-year, both of which may have
    // travelled a long way from what was written.
    val wholeMonths = truncate(month)
    val yearOfMonth = calendarYear + floor(wholeMonths / 12.0)
    if (yearOfMonth < MIN_YEAR || yearOfMonth > MAX_YEAR) return Double.NaN
    val monthOfYear = wholeMonths - floor(wholeMonths / 12.0) * 12.0

    // `MakeTime`, kept in milliseconds so an out-of-range hour becomes a day offset rather than an
    // illegal `LocalTime` — which is the whole rollover rule for the time half.
    val timeOfDay =
      truncate(hours) * 3_600_000.0 +
        truncate(minutes) * 60_000.0 +
        truncate(seconds) * 1000.0 +
        truncate(millis)
    if (!timeOfDay.isFinite()) return Double.NaN

    // A date is one-based, so `date - 1` is the offset from the first of the month; the time's own
    // overflow is added to it, and what is left is a time inside one day.
    val dayOffset = truncate(date) - 1.0 + floor(timeOfDay / MS_PER_DAY)
    if (abs(dayOffset) > MAX_DAY_OFFSET) return Double.NaN
    val millisOfDay = timeOfDay - floor(timeOfDay / MS_PER_DAY) * MS_PER_DAY

    val firstOfMonth =
      try {
        LocalDate(yearOfMonth.toInt(), monthOfYear.toInt() + 1, 1)
      } catch (_: IllegalArgumentException) {
        return Double.NaN
      }
    val day =
      try {
        firstOfMonth.plus(dayOffset.toLong(), DateTimeUnit.DAY)
      } catch (_: IllegalArgumentException) {
        return Double.NaN
      } catch (_: ArithmeticException) {
        return Double.NaN
      }

    val clock = LocalDateTime(day, LocalTime.fromMillisecondOfDay(millisOfDay.toInt()))
    val instant =
      try {
        clock.toInstant(zone)
      } catch (_: IllegalArgumentException) {
        return Double.NaN
      }
    return clip(instant.toEpochMilliseconds().toDouble())
  }

  /**
   * `TimeClip`: a time value outside ECMA-262's range, or one that is not a number, is an *Invalid
   * Date* — which as a number is NaN.
   */
  public fun clip(millis: Double): Double =
    if (!millis.isFinite() || abs(millis) > MAX_TIME_VALUE) Double.NaN else millis
}
