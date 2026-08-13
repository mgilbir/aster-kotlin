package dev.aster.vega.model.time

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * Calendar intervals, ported from d3-time, which is what upstream Vega ticks and rounds dates with.
 *
 * Time is epoch milliseconds throughout — the same `Double` every other value in this engine is —
 * and a [TimeZone] decides what a "day" or a "month" means. The arithmetic goes through
 * `kotlinx-datetime` rather than `java.time` or `Calendar` so the core stays portable to Kotlin
 * Multiplatform.
 *
 * The distinction that matters: a calendar interval is *not* a fixed number of milliseconds. Two
 * days apart across a daylight-saving boundary is 23 or 25 hours, and a month is 28 to 31 days, so
 * flooring and stepping have to go through the calendar rather than through division.
 */
public enum class TimeInterval {
  MILLISECOND,
  SECOND,
  MINUTE,
  HOUR,
  DAY,
  /** Weeks start on Sunday, as d3's default `timeWeek` does. */
  WEEK,
  MONTH,
  YEAR;

  public companion object {
    /**
     * The interval a specification's **unit name** stands for, with the step it implies.
     *
     * Vega's names are not this enum's: they are the `timeunit` names — `"hours"`, `"minutes"`,
     * `"date"`, `"dayofyear"`, `"quarter"` — and a scale's `nice` or an axis's `tickCount` is
     * written in those. Matching on the enum's own names instead worked for `"month"` and `"year"`
     * and quietly failed for everything plural, which is most of them, and for `"quarter"`, which
     * is not an interval at all but three months.
     *
     * @return the interval and how many of it make one step, or null when nothing is named.
     */
    public fun forUnit(unit: String?): Pair<TimeInterval, Int>? =
      when (unit?.lowercase()) {
        "year" -> YEAR to 1
        // Not an interval of its own anywhere: d3 has no quarter, and upstream spells it
        // `timeMonth.every(3)`.
        "quarter" -> MONTH to 3
        "month" -> MONTH to 1
        "week" -> WEEK to 1
        "date",
        "day",
        "dayofyear" -> DAY to 1
        "hours" -> HOUR to 1
        "minutes" -> MINUTE to 1
        "seconds" -> SECOND to 1
        "milliseconds" -> MILLISECOND to 1
        else -> null
      }
  }

  /** Nominal length in milliseconds, used only to choose between intervals, never to step. */
  public val approximateMillis: Double
    get() =
      when (this) {
        MILLISECOND -> 1.0
        SECOND -> 1000.0
        MINUTE -> 60_000.0
        HOUR -> 3_600_000.0
        DAY -> 86_400_000.0
        WEEK -> 604_800_000.0
        MONTH -> 2_592_000_000.0
        YEAR -> 31_536_000_000.0
      }
}

/**
 * Floors, steps and enumerates instants on a calendar.
 *
 * @param step how many of [interval] make one increment. A step greater than one is only meaningful
 *   from an aligned origin, which is why [floor] snaps to the interval before applying it.
 */
public class TimeStepper(
  public val interval: TimeInterval,
  public val step: Int = 1,
  public val zone: TimeZone = TimeZone.UTC,
) {

  private fun instant(millis: Double) = Instant.fromEpochMilliseconds(millis.toLong())

  private fun millis(instant: Instant) = instant.toEpochMilliseconds().toDouble()

  private fun local(millis: Double): LocalDateTime = instant(millis).toLocalDateTime(zone)

  /** The start of the [interval] containing [millis], then snapped down to a multiple of [step]. */
  public fun floor(millis: Double): Double {
    val at = local(millis)
    return when (interval) {
      TimeInterval.MILLISECOND -> {
        val ms = at.nanosecond / 1_000_000
        millis - (ms - ms / step * step)
      }
      TimeInterval.SECOND -> atTime(at.date, at.hour, at.minute, snapDown(at.second))
      TimeInterval.MINUTE -> atTime(at.date, at.hour, snapDown(at.minute), 0)
      TimeInterval.HOUR -> atTime(at.date, snapDown(at.hour), 0, 0)
      TimeInterval.DAY -> millis(at.date.atStartOfDayIn(zone))
      // d3's weeks start on Sunday; kotlinx-datetime numbers Monday as 1, so Sunday is 7.
      TimeInterval.WEEK -> {
        val back = at.date.dayOfWeek.isoDayNumber % 7
        millis(at.date.minusDays(back).atStartOfDayIn(zone))
      }
      TimeInterval.MONTH -> {
        val month = at.date.month.number - 1
        millis(LocalDate(at.date.year, month - month % step + 1, 1).atStartOfDayIn(zone))
      }
      TimeInterval.YEAR -> {
        val year = at.date.year
        millis(LocalDate(year - year.mod(step), 1, 1).atStartOfDayIn(zone))
      }
    }
  }

  /**
   * [millis] advanced by [count] steps.
   *
   * Months and years **overflow** rather than clamping, which is d3's behaviour because it is
   * JavaScript's: `setMonth(month + 1)` on 31 January asks for "31 February" and the Date
   * normalises it to 2 or 3 March depending on the leap year. `kotlinx-datetime` clamps instead —
   * it would answer 29 February — so the two disagree by one to three days for any date past the
   * 28th, which is a quarter of the month. Tick generation never noticed: it only ever offsets from
   * a floored boundary, the first of a month, where nothing overflows. `timeOffset` in an
   * expression does.
   */
  public fun offset(millis: Double, count: Int): Double {
    val at = instant(millis)
    val amount = step.toLong() * count
    return when (interval) {
      TimeInterval.MILLISECOND -> millis(at.plus(amount, DateTimeUnit.MILLISECOND))
      TimeInterval.SECOND -> millis(at.plus(amount, DateTimeUnit.SECOND))
      TimeInterval.MINUTE -> millis(at.plus(amount, DateTimeUnit.MINUTE))
      TimeInterval.HOUR -> millis(at.plus(amount, DateTimeUnit.HOUR))
      TimeInterval.DAY -> millis(at.plus(amount, DateTimeUnit.DAY, zone))
      TimeInterval.WEEK -> millis(at.plus(amount, DateTimeUnit.WEEK, zone))
      TimeInterval.MONTH -> overflowing(millis, monthsFrom = amount, yearsFrom = 0L)
      TimeInterval.YEAR -> overflowing(millis, monthsFrom = 0L, yearsFrom = amount)
    }
  }

  /**
   * A month or year shift done the way `Date.setMonth` does it: keep the day number and let it
   * spill.
   *
   * Built by taking the first of the target month and adding `day - 1` days, which is exactly what
   * the overflow amounts to, and reattaching the original wall-clock time — a shift by months keeps
   * the local time of day across a daylight-saving change rather than the absolute instant.
   */
  private fun overflowing(millis: Double, monthsFrom: Long, yearsFrom: Long): Double {
    val at = local(millis)
    val zeroBased = (at.date.month.number - 1).toLong() + monthsFrom
    val year = at.date.year + yearsFrom + zeroBased.floorDiv(12)
    val month = zeroBased.mod(12L).toInt() + 1
    val first = LocalDate(year.toInt(), month, 1)
    val date = first.plus(at.date.day - 1, DateTimeUnit.DAY)
    return millis(LocalDateTime(date, at.time).toInstant(zone))
  }

  /**
   * Every step boundary in `[start, stop)`, starting from the first at or after [start].
   *
   * Stepping through the calendar rather than adding a fixed millisecond count is what keeps a
   * daily tick at midnight across a daylight-saving change.
   */
  public fun range(start: Double, stop: Double): List<Double> {
    if (!start.isFinite() || !stop.isFinite() || stop <= start) return emptyList()
    val result = mutableListOf<Double>()
    var at = floor(start)
    if (at < start) at = offset(at, 1)
    var guard = 0
    while (at < stop && guard < MAX_TICKS) {
      result.add(at)
      val next = offset(at, 1)
      // A zero-length step would spin forever. A calendar cannot produce one, but a degenerate step
      // could, and a chart that hangs is worse than a chart with no ticks.
      if (next <= at) break
      at = next
      guard++
    }
    return result
  }

  private fun snapDown(value: Int) = value - value % step

  private fun atTime(date: LocalDate, hour: Int, minute: Int, second: Int): Double =
    millis(LocalDateTime(date, kotlinx.datetime.LocalTime(hour, minute, second)).toInstant(zone))

  private fun LocalDate.minusDays(days: Int): LocalDate =
    if (days == 0) this else this.plus(-days, DateTimeUnit.DAY)

  private companion object {
    /** A ceiling on how many ticks one axis can produce, so a degenerate domain cannot hang. */
    const val MAX_TICKS = 10_000
  }
}
