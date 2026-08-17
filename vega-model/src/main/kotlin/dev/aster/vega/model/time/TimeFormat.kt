package dev.aster.vega.model.time

import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.offsetAt
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * The strftime subset d3-time-format's default formats use, and only that.
 *
 * Locales are not implemented: every name here is English, which is what upstream produces with its
 * own default locale. A specification asking for a directive that is not here gets it back verbatim
 * rather than a wrong substitution, so a reader can see what was not understood.
 */
public object TimeFormat {

  /**
   * The month names `%B` writes, shared rather than restated.
   *
   * `monthFormat()` needs the same list, and upstream gets it the same way — by formatting a date
   * it builds for the purpose — so two copies would be two things to keep in step.
   */
  public val MONTHS: List<String> =
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

  /** The weekday names `%A` writes, Sunday first, which is the week d3 labels against. */
  public val WEEKDAYS: List<String> =
    listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

  /** The local date and time at [millis] in [zone]. */
  public fun at(millis: Double, zone: TimeZone): LocalDateTime =
    Instant.fromEpochMilliseconds(millis.toLong()).toLocalDateTime(zone)

  public fun format(millis: Double, pattern: String, zone: TimeZone): String =
    render(pattern, at(millis, zone), millis, zone)

  /**
   * Formats without an instant, for a caller that only has a local time.
   *
   * `%Q`, `%s` and `%Z` need the instant and the zone — they are milliseconds since the epoch,
   * seconds since the epoch, and the offset — so this reconstructs one in UTC. Every other
   * directive reads the local fields and is unaffected.
   */
  public fun format(at: LocalDateTime, pattern: String): String =
    render(pattern, at, at.toInstant(TimeZone.UTC).toEpochMilliseconds().toDouble(), TimeZone.UTC)

  /**
   * d3's directive table, whole, including the **padding modifiers**.
   *
   * `%-S` drops the padding, `%_S` pads with a space and `%0S` pads with a zero, which is how a
   * specification writes "9am" rather than "09am" — and this engine used to emit the directive back
   * unchanged, so a label read `%-S` where upstream read `0`. Replaying d3-time-format's own corpus
   * named nine more that were missing outright: `%c`, `%x` and `%X` (the locale's date, time and
   * both), the ISO week trio `%G`, `%g` and `%V`, `%u` (Monday-based weekday), `%Q` and `%s` (the
   * instant itself), and `%Z` (the offset).
   */
  private fun render(pattern: String, at: LocalDateTime, millis: Double, zone: TimeZone): String {
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
      // An optional pad modifier sits between the percent and the directive.
      var cursor = i + 1
      var padWith: Char? = null
      if (cursor < pattern.lastIndex && pattern[cursor] in "-_0") {
        padWith = pattern[cursor]
        cursor++
      }
      val directive = pattern[cursor]
      fun number(value: Int, width: Int, default: Char = '0') {
        val text = value.toString()
        out.append(
          when (padWith) {
            '-' -> text
            '_' -> text.padStart(width, ' ')
            '0' -> text.padStart(width, '0')
            else -> if (default == ' ') text.padStart(width, ' ') else text.padStart(width, '0')
          }
        )
      }
      when (directive) {
        'Y' -> out.append(padSigned(at.year % 10000, 4))
        'y' -> out.append(padSigned(at.year % 100, 2))
        'm' -> number(at.month.number, 2)
        'B' -> out.append(MONTHS[at.month.number - 1])
        'b' -> out.append(MONTHS[at.month.number - 1].take(3))
        'A' -> out.append(WEEKDAYS[weekday])
        'a' -> out.append(WEEKDAYS[weekday].take(3))
        'd' -> number(at.day, 2)
        'e' -> number(at.day, 2, default = ' ')
        'j' -> number(at.date.dayOfYear, 3)
        // Vega's own addition to d3's directives, and the only way to write a quarter.
        'q' -> out.append((at.month.number - 1) / 3 + 1)
        'U' -> number(sundayWeek(at), 2)
        'W' -> number(mondayWeek(at), 2)
        'V' -> number(isoWeek(at), 2)
        'G' -> out.append(padSigned(isoWeekYear(at) % 10000, 4))
        'g' -> out.append(padSigned(isoWeekYear(at) % 100, 2))
        'u' -> out.append(at.date.dayOfWeek.isoDayNumber)
        'w' -> out.append(weekday)
        'H' -> number(at.hour, 2)
        // Twelve-hour clock, where midnight and noon both read 12 rather than 0.
        'I' -> number((at.hour % 12).let { if (it == 0) 12 else it }, 2)
        'p' -> out.append(if (at.hour < 12) "AM" else "PM")
        'M' -> number(at.minute, 2)
        'S' -> number(at.second, 2)
        'L' -> number(at.nanosecond / 1_000_000, 3)
        'f' -> number(at.nanosecond / 1_000, 6)
        'Q' -> out.append(millis.toLong())
        's' -> out.append((millis / 1000.0).toLong())
        'Z' -> out.append(offset(millis, zone))
        // The locale's own compositions, which d3's en-US locale spells out this way.
        'c' -> out.append(render("%x, %X", at, millis, zone))
        'x' -> out.append(render("%-m/%-d/%Y", at, millis, zone))
        'X' -> out.append(render("%-I:%M:%S %p", at, millis, zone))
        '%' -> out.append('%')
        else -> {
          // Unknown directive: emit it as written rather than guessing.
          out.append('%')
          if (padWith != null) out.append(padWith)
          out.append(directive)
        }
      }
      i = cursor + 1
    }
    return out.toString()
  }

  /**
   * d3's `pad`: the sign goes **outside** the width, and the year is taken modulo its own width.
   *
   * `%Y` is `year % 10000` padded to four and `%y` is `year % 100` padded to two — so the year
   * 10002 writes `0002` and the year -2 writes `-0002`, with the minus in front of the padding
   * rather than counted by it. Transcribed after replaying d3-time-format's corpus, which formats
   * both.
   */
  private fun padSigned(value: Int, width: Int, fill: Char = '0'): String {
    val sign = if (value < 0) "-" else ""
    val digits = kotlin.math.abs(value).toString()
    return sign +
      if (digits.length < width) fill.toString().repeat(width - digits.length) + digits else digits
  }

  /** `%Z`: the zone's offset from UTC at that instant, as `+hhmm`. */
  private fun offset(millis: Double, zone: TimeZone): String {
    val seconds = zone.offsetAt(Instant.fromEpochMilliseconds(millis.toLong())).totalSeconds
    val sign = if (seconds < 0) "-" else "+"
    val minutes = kotlin.math.abs(seconds) / 60
    return sign +
      (minutes / 60).toString().padStart(2, '0') +
      (minutes % 60).toString().padStart(2, '0')
  }

  /** `%V`: the ISO week, where a week belongs to the year holding its Thursday. */
  private fun isoWeek(at: LocalDateTime): Int {
    val thursday = at.date.plus(4 - at.date.dayOfWeek.isoDayNumber, DateTimeUnit.DAY)
    val firstOfYear = LocalDate(thursday.year, 1, 1)
    return ((thursday.dayOfYear - 1) / 7) + 1 + if (firstOfYear.dayOfWeek.isoDayNumber > 4) 0 else 0
  }

  /** `%G`: the year that ISO week belongs to, which is not always the calendar year. */
  private fun isoWeekYear(at: LocalDateTime): Int =
    at.date.plus(4 - at.date.dayOfWeek.isoDayNumber, DateTimeUnit.DAY).year

  /** `%W`: weeks counted from the first Monday, as `%U` counts from the first Sunday. */
  private fun mondayWeek(at: LocalDateTime): Int {
    val januaryFirst = at.date.minus(at.date.dayOfYear - 1, DateTimeUnit.DAY)
    val firstMonday = 1 + (8 - januaryFirst.dayOfWeek.isoDayNumber) % 7
    val day = at.date.dayOfYear
    return if (day < firstMonday) 0 else (day - firstMonday) / 7 + 1
  }

  /**
   * `%U` — how many Sundays this year has reached, which is d3's week number.
   *
   * d3 counts Sunday *boundaries* from the instant before January 1, so the days before the year's
   * first Sunday are week 0 and the first Sunday itself starts week 1. A year beginning on a Sunday
   * therefore has no week 0 at all.
   */
  private fun sundayWeek(at: LocalDateTime): Int {
    val januaryFirst = at.date.minus(at.date.dayOfYear - 1, DateTimeUnit.DAY)
    val firstSunday = 1 + (7 - januaryFirst.dayOfWeek.isoDayNumber % 7) % 7
    val day = at.date.dayOfYear
    return if (day < firstSunday) 0 else (day - firstSunday) / 7 + 1
  }

  private fun pad(value: Int, width: Int): String = value.toString().padStart(width, '0')
}
