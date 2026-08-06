package dev.aster.vega.model.time

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * The strftime subset d3-time-format's default formats use, and only that.
 *
 * Locales are not implemented: every name here is English, which is what upstream produces with its
 * own default locale. A specification asking for a directive that is not here gets it back verbatim
 * rather than a wrong substitution, so a reader can see what was not understood.
 */
public object TimeFormat {

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

  /** The local date and time at [millis] in [zone]. */
  public fun at(millis: Double, zone: TimeZone): LocalDateTime =
    Instant.fromEpochMilliseconds(millis.toLong()).toLocalDateTime(zone)

  public fun format(millis: Double, pattern: String, zone: TimeZone): String =
    format(at(millis, zone), pattern)

  public fun format(at: LocalDateTime, pattern: String): String {
    val out = StringBuilder(pattern.length + 8)
    // Sunday-first, because that is the week d3 labels against.
    val weekday = at.date.dayOfWeek.isoDayNumber % 7
    var i = 0
    while (i < pattern.length) {
      val c = pattern[i]
      if (c != '%' || i == pattern.lastIndex) {
        out.append(c)
        i++
        continue
      }
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
        'j' -> out.append(pad(at.date.dayOfYear, 3))
        'H' -> out.append(pad(at.hour, 2))
        // Twelve-hour clock, where midnight and noon both read 12 rather than 0.
        'I' -> out.append(pad((at.hour % 12).let { if (it == 0) 12 else it }, 2))
        'p' -> out.append(if (at.hour < 12) "AM" else "PM")
        'M' -> out.append(pad(at.minute, 2))
        'S' -> out.append(pad(at.second, 2))
        'L' -> out.append(pad(at.nanosecond / 1_000_000, 3))
        'f' -> out.append(pad(at.nanosecond / 1_000, 6))
        '%' -> out.append('%')
        else -> {
          // Unknown directive: emit it as written rather than guessing.
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
