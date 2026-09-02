package dev.aster.vega.model.time

import io.github.mgilbir.ecma262.date.EcmaTimeZone
import io.github.mgilbir.ecma262.date.makeDate
import io.github.mgilbir.ecma262.date.makeDay
import io.github.mgilbir.ecma262.date.makeFullYear
import io.github.mgilbir.ecma262.date.makeTime
import io.github.mgilbir.ecma262.date.timeClip
import kotlin.math.abs
import kotlin.math.truncate
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

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
    // ECMA-262's own decomposition, from `ktecma262`. All of it was written out here — the
    // two-digit year rule, `MakeDay`'s reduction of a month into a year and a month-of-year,
    // `MakeTime` kept in milliseconds so an out-of-range hour becomes a day offset, `MakeDate`,
    // and `TimeClip` — and each carries NaN and range rules that are easy to get subtly wrong.
    // Asked for as ktecma262#7 and answered in 0.3.0.
    //
    // What stays here is what the library deliberately leaves to a caller: turning a *local* time
    // value into an instant needs a time-zone database, and a specification-compliant regular
    // expression engine has no business carrying one. That is `LocalTZA`, below.
    val localTimeValue =
      makeDate(
        makeDay(makeFullYear(truncate(year)), month, date),
        makeTime(hours, minutes, seconds, millis),
      )
    // `UTC(t) = t - LocalTZA(t)`. The offset is the one in force at that **local wall clock**,
    // which is why it cannot be read at the resulting instant: the two differ by an hour on one
    // side of every daylight-saving transition.
    val offsetMinutes = KotlinxZone(zone).offsetMinutesAtLocalTime(localTimeValue)
    return timeClip(localTimeValue - offsetMinutes * 60_000.0)
  }

  /**
   * `LocalTZA` over a `kotlinx-datetime` zone.
   *
   * `ktecma262` takes an [EcmaTimeZone] rather than depending on a time-zone database, which is the
   * right split: the calendar arithmetic is the specification's and the zone data is the
   * platform's.
   *
   * The argument is a **local** time value — milliseconds read as a wall clock rather than as an
   * instant — so it is decomposed as UTC fields first and those fields are then asked what they
   * mean in [zone].
   */
  private class KotlinxZone(private val zone: TimeZone) : EcmaTimeZone {
    override fun offsetMinutesAtLocalTime(localTimeValue: Double): Int {
      if (!localTimeValue.isFinite() || abs(localTimeValue) > MAX_TIME_VALUE) return 0
      val wallClock =
        Instant.fromEpochMilliseconds(localTimeValue.toLong()).toLocalDateTime(TimeZone.UTC)
      val actual = wallClock.toInstant(zone).toEpochMilliseconds()
      return ((localTimeValue.toLong() - actual) / 60_000L).toInt()
    }
  }

  /**
   * `TimeClip`: a time value outside ECMA-262's range, or one that is not a number, is an *Invalid
   * Date* — which as a number is NaN.
   */
  public fun clip(millis: Double): Double =
    if (!millis.isFinite() || abs(millis) > MAX_TIME_VALUE) Double.NaN else millis
}
