package dev.aster.vega.runtime.scale

import kotlin.math.abs
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * Choosing and formatting the ticks of a time axis, ported from d3-time and d3-time-format.
 *
 * The two halves are separate decisions and both are upstream's. *Which* instants to tick comes
 * from a fixed table of sensible intervals — 1, 5, 15, 30 seconds, then minutes, then 1, 3, 6, 12
 * hours, and so on — picked by whichever lands closest to the requested count on a log scale. *How
 * to label* them then depends on each tick's own granularity, so a tick on a year boundary reads
 * "2026" while its neighbours read "February".
 */
public object TimeTicks {

  /** d3's table of tick intervals, coarsening from a second to a year. */
  private val INTERVALS: List<Pair<TimeInterval, Int>> =
    listOf(
      TimeInterval.SECOND to 1,
      TimeInterval.SECOND to 5,
      TimeInterval.SECOND to 15,
      TimeInterval.SECOND to 30,
      TimeInterval.MINUTE to 1,
      TimeInterval.MINUTE to 5,
      TimeInterval.MINUTE to 15,
      TimeInterval.MINUTE to 30,
      TimeInterval.HOUR to 1,
      TimeInterval.HOUR to 3,
      TimeInterval.HOUR to 6,
      TimeInterval.HOUR to 12,
      TimeInterval.DAY to 1,
      TimeInterval.DAY to 2,
      TimeInterval.WEEK to 1,
      TimeInterval.MONTH to 1,
      TimeInterval.MONTH to 3,
      TimeInterval.YEAR to 1,
    )

  private fun span(entry: Pair<TimeInterval, Int>) = entry.first.approximateMillis * entry.second

  /**
   * The interval a domain of this length should be ticked at to land near [count] ticks.
   *
   * The choice between two neighbouring candidates is made on the *ratio*, not the difference: an
   * interval twice too coarse and one twice too fine are equally wrong, which is why a domain of a
   * few months gets monthly ticks rather than weekly ones.
   */
  public fun tickStepper(start: Double, stop: Double, count: Int, zone: TimeZone): TimeStepper {
    val target = abs(stop - start) / count.coerceAtLeast(1)
    val index = INTERVALS.indexOfFirst { span(it) > target }

    if (index < 0) {
      // Coarser than a year: fall back to the numeric tick step, in years.
      val years =
        Ticks.stepFrom(
          Ticks.tickIncrement(
            start / TimeInterval.YEAR.approximateMillis,
            stop / TimeInterval.YEAR.approximateMillis,
            count,
          )
        )
      return TimeStepper(TimeInterval.YEAR, years.coerceAtLeast(1.0).toInt(), zone)
    }
    if (index == 0) {
      // Finer than a second: step in raw milliseconds.
      val step = Ticks.stepFrom(Ticks.tickIncrement(start, stop, count))
      return TimeStepper(TimeInterval.MILLISECOND, step.coerceAtLeast(1.0).toInt(), zone)
    }

    val finer = INTERVALS[index - 1]
    val coarser = INTERVALS[index]
    val entry = if (target / span(finer) < span(coarser) / target) finer else coarser
    return TimeStepper(entry.first, entry.second, zone)
  }

  /** Tick instants across `[start, stop]`, inclusive of a boundary that lands exactly on [stop]. */
  public fun ticks(start: Double, stop: Double, count: Int, zone: TimeZone): List<Double> {
    if (!start.isFinite() || !stop.isFinite()) return emptyList()
    val reversed = stop < start
    val lo = if (reversed) stop else start
    val hi = if (reversed) start else stop
    val values = tickStepper(lo, hi, count, zone).range(lo, hi + 1.0)
    return if (reversed) values.reversed() else values
  }

  /** Widens a domain outwards to the tick interval it would be ticked at. */
  public fun nice(start: Double, stop: Double, count: Int, zone: TimeZone): Pair<Double, Double> {
    if (!start.isFinite() || !stop.isFinite() || start == stop) return start to stop
    val reversed = stop < start
    val lo = if (reversed) stop else start
    val hi = if (reversed) start else stop
    val stepper = tickStepper(lo, hi, count, zone)
    val flooredLo = stepper.floor(lo)
    val flooredHi = stepper.floor(hi)
    val ceiledHi = if (flooredHi < hi) stepper.offset(flooredHi, 1) else flooredHi
    return if (reversed) ceiledHi to flooredLo else flooredLo to ceiledHi
  }

  // ---- labels -----------------------------------------------------------------

  /**
   * d3's multi-scale label for one instant.
   *
   * Each tick is labelled at its *own* granularity rather than the axis's, which is what makes a
   * daily axis read "Jan 01" once a month and "Mon 05" otherwise — the coarser boundary wins, so
   * the reader gets the context back exactly where it changes.
   */
  public fun label(millis: Double, zone: TimeZone): String {
    val at = localAt(millis, zone)
    return when {
      at.nanosecond != 0 -> format(at, ".%L")
      at.second != 0 -> format(at, ":%S")
      at.minute != 0 -> format(at, "%I:%M")
      at.hour != 0 -> format(at, "%I %p")
      at.day != 1 ->
        // A Sunday is a week boundary, so it gets the month back; any other day only needs its
        // name.
        if (at.date.dayOfWeek.isoDayNumber == 7) format(at, "%b %d") else format(at, "%a %d")
      at.month.number != 1 -> format(at, "%B")
      else -> format(at, "%Y")
    }
  }

  private fun localAt(millis: Double, zone: TimeZone): LocalDateTime =
    Instant.fromEpochMilliseconds(millis.toLong()).toLocalDateTime(zone)

  private val MONTHS =
    listOf(
      "January",
      "February",
      "March",
      "April",
      "May",
      "June",
      "July",
      "August",
      "September",
      "October",
      "November",
      "December",
    )

  private val WEEKDAYS =
    listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

  /**
   * The strftime directives d3's default time formats use, and only those.
   *
   * A specification asking for anything else gets a diagnostic from the caller rather than a
   * half-honoured format string, which is the same rule the numeric formatter follows.
   */
  public fun format(millis: Double, pattern: String, zone: TimeZone): String =
    format(localAt(millis, zone), pattern)

  private fun format(at: LocalDateTime, pattern: String): String {
    val out = StringBuilder(pattern.length + 8)
    var i = 0
    while (i < pattern.length) {
      val c = pattern[i]
      if (c != '%' || i == pattern.lastIndex) {
        out.append(c)
        i++
        continue
      }
      // Sunday-first, because that is the week d3 labels against.
      val weekday = at.date.dayOfWeek.isoDayNumber % 7
      when (pattern[i + 1]) {
        'Y' -> out.append(at.year)
        'y' -> out.append(pad(at.year % 100, 2))
        'm' -> out.append(pad(at.month.number, 2))
        'B' -> out.append(MONTHS[at.month.number - 1])
        'b' -> out.append(MONTHS[at.month.number - 1].take(3))
        'A' -> out.append(WEEKDAYS[weekday])
        'a' -> out.append(WEEKDAYS[weekday].take(3))
        'd' -> out.append(pad(at.day, 2))
        'e' -> out.append(at.day.toString().padStart(2, ' '))
        'H' -> out.append(pad(at.hour, 2))
        // Twelve-hour clock, where midnight and noon both read 12 rather than 0.
        'I' -> out.append(pad((at.hour % 12).let { if (it == 0) 12 else it }, 2))
        'p' -> out.append(if (at.hour < 12) "AM" else "PM")
        'M' -> out.append(pad(at.minute, 2))
        'S' -> out.append(pad(at.second, 2))
        'L' -> out.append(pad(at.nanosecond / 1_000_000, 3))
        '%' -> out.append('%')
        else -> {
          out.append('%')
          out.append(pattern[i + 1])
        }
      }
      i += 2
    }
    return out.toString()
  }

  private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')
}
