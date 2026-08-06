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

  /** [millis] advanced by [count] steps. */
  public fun offset(millis: Double, count: Int): Double {
    val at = instant(millis)
    val amount = step.toLong() * count
    return millis(
      when (interval) {
        TimeInterval.MILLISECOND -> at.plus(amount, DateTimeUnit.MILLISECOND)
        TimeInterval.SECOND -> at.plus(amount, DateTimeUnit.SECOND)
        TimeInterval.MINUTE -> at.plus(amount, DateTimeUnit.MINUTE)
        TimeInterval.HOUR -> at.plus(amount, DateTimeUnit.HOUR)
        TimeInterval.DAY -> at.plus(amount, DateTimeUnit.DAY, zone)
        TimeInterval.WEEK -> at.plus(amount, DateTimeUnit.WEEK, zone)
        TimeInterval.MONTH -> at.plus(amount, DateTimeUnit.MONTH, zone)
        TimeInterval.YEAR -> at.plus(amount, DateTimeUnit.YEAR, zone)
      }
    )
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
