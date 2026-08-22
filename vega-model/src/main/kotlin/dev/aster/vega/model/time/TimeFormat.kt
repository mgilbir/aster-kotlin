package dev.aster.vega.model.time

import dev.aster.vega.model.locale.DateName
import dev.aster.vega.model.locale.DateNameContext
import dev.aster.vega.model.locale.VegaLocale
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
 * **Formatting takes a [VegaLocale]** and defaults to d3's own `en-US`, so a chart drawn without
 * one is byte-for-byte what upstream draws. The names a *format* writes come from that locale;
 * [MONTHS] and [WEEKDAYS] below are the English ones **parsing** reads, and the two are
 * deliberately different things — see the note on each.
 *
 * A specification asking for a directive that is not here gets it back verbatim rather than a wrong
 * substitution, so a reader can see what was not understood.
 */
public object TimeFormat {

  /**
   * The month names **parsing** reads, which are English and stay English.
   *
   * d3's parsing is part of the wire format: a specification writing `"Jan 5 2026"` in its own data
   * means January whatever language the chart is drawn in, so `TimeParse` and `DateValues` read
   * this list and never the locale's. What a *label* says is [VegaLocale.months]; replacing these
   * with a locale's names would break the reading of every specification that writes a month by
   * name.
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

  /**
   * The weekday names **parsing** reads, Sunday first, which is the week d3 labels against.
   *
   * English, and for the same reason [MONTHS] is; a label's weekday comes from [VegaLocale.days].
   */
  public val WEEKDAYS: List<String> =
    listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

  /** The local date and time at [millis] in [zone]. */
  public fun at(millis: Double, zone: TimeZone): LocalDateTime =
    Instant.fromEpochMilliseconds(millis.toLong()).toLocalDateTime(zone)

  public fun format(
    millis: Double,
    pattern: String,
    zone: TimeZone,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): String = render(pattern, at(millis, zone), millis, zone, locale)

  /**
   * Formats without an instant, for a caller that only has a local time.
   *
   * `%Q`, `%s` and `%Z` need the instant and the zone — they are milliseconds since the epoch,
   * seconds since the epoch, and the offset — so this reconstructs one in UTC. Every other
   * directive reads the local fields and is unaffected.
   */
  public fun format(
    at: LocalDateTime,
    pattern: String,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): String =
    render(
      pattern,
      at,
      at.toInstant(TimeZone.UTC).toEpochMilliseconds().toDouble(),
      TimeZone.UTC,
      locale,
    )

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
  private fun render(
    pattern: String,
    at: LocalDateTime,
    millis: Double,
    zone: TimeZone,
    locale: VegaLocale,
  ): String {
    val out = StringBuilder(pattern.length + 8)
    // Sunday-first, because that is the week d3 labels against.
    val weekday = at.date.dayOfWeek.isoDayNumber % 7
    // Lazily, and only where a rule exists to read it: parsing the pattern a second time for every
    // label a chart draws would be a cost paid by every chart that supplies no rules at all.
    var context: DateNameContext? = null
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
      // Each numeric piece goes through the host's numbering system, if it has one — **after**
      // padding, so a rule cannot change how wide a field is. That width is the specification's:
      // `%02d` said two digits and it gets two digits, in whatever digits the host writes.
      fun digits(text: String): String = locale.rules?.digits(text) ?: text

      fun number(value: Int, width: Int, default: Char = '0') {
        val text = value.toString()
        out.append(
          digits(
            when (padWith) {
              '-' -> text
              '_' -> text.padStart(width, ' ')
              '0' -> text.padStart(width, '0')
              else -> if (default == ' ') text.padStart(width, ' ') else text.padStart(width, '0')
            }
          )
        )
      }

      /**
       * A name the host may have a better answer for than the locale's list.
       *
       * The whole `pattern` goes with it, which is the point: a language whose month form depends
       * on a day number beside it cannot be tabulated, only asked.
       */
      fun named(field: DateName, index: Int, fallback: String): String {
        val rules = locale.rules ?: return fallback
        val known = context ?: DateNameContext(pattern, directivesIn(pattern)).also { context = it }
        return rules.name(field, index, known, locale) ?: fallback
      }
      when (directive) {
        'Y' -> out.append(digits(padSigned(at.year % 10000, 4)))
        'y' -> out.append(digits(padSigned(at.year % 100, 2)))
        'm' -> number(at.month.number, 2)
        'B' ->
          out.append(named(DateName.MONTH, at.month.number - 1, locale.months[at.month.number - 1]))
        'b' ->
          out.append(
            named(
              DateName.MONTH_SHORT,
              at.month.number - 1,
              locale.shortMonths[at.month.number - 1],
            )
          )
        'A' -> out.append(named(DateName.WEEKDAY, weekday, locale.days[weekday]))
        'a' -> out.append(named(DateName.WEEKDAY_SHORT, weekday, locale.shortDays[weekday]))
        'd' -> number(at.day, 2)
        'e' -> number(at.day, 2, default = ' ')
        'j' -> number(at.date.dayOfYear, 3)
        // Vega's own addition to d3's directives, and the only way to write a quarter.
        'q' -> out.append(digits(((at.month.number - 1) / 3 + 1).toString()))
        'U' -> number(sundayWeek(at), 2)
        'W' -> number(mondayWeek(at), 2)
        'V' -> number(isoWeek(at), 2)
        'G' -> out.append(digits(padSigned(isoWeekYear(at) % 10000, 4)))
        'g' -> out.append(digits(padSigned(isoWeekYear(at) % 100, 2)))
        'u' -> out.append(digits(at.date.dayOfWeek.isoDayNumber.toString()))
        'w' -> out.append(digits(weekday.toString()))
        'H' -> number(at.hour, 2)
        // Twelve-hour clock, where midnight and noon both read 12 rather than 0.
        'I' -> number((at.hour % 12).let { if (it == 0) 12 else it }, 2)
        'p' -> {
          val half = if (at.hour < 12) 0 else 1
          out.append(named(DateName.HALF_DAY, half, locale.periods[half]))
        }
        'M' -> number(at.minute, 2)
        'S' -> number(at.second, 2)
        'L' -> number(at.nanosecond / 1_000_000, 3)
        'f' -> number(at.nanosecond / 1_000, 6)
        'Q' -> out.append(digits(millis.toLong().toString()))
        's' -> out.append(digits((millis / 1000.0).toLong().toString()))
        'Z' -> out.append(digits(offset(millis, zone)))
        // The locale's own compositions. d3's en-US spells these three out as the defaults on
        // `VegaLocale`, and a locale that writes its dates the other way round says so there rather
        // than by rewriting every specification that uses `%x`.
        'c' -> out.append(render(locale.dateTime, at, millis, zone, locale))
        'x' -> out.append(render(locale.date, at, millis, zone, locale))
        'X' -> out.append(render(locale.time, at, millis, zone, locale))
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
   * Every directive letter in a pattern, pad modifiers stripped.
   *
   * For [DateNameContext], so a host asking "is a day number in this format" is answered rather
   * than left to read strftime itself — where `%-d` is exactly the case that catches a
   * `pattern.contains("%d")`, as the first rule written against an earlier draft of this seam
   * discovered.
   */
  private fun directivesIn(pattern: String): Set<Char> {
    val letters = mutableSetOf<Char>()
    var index = 0
    while (index < pattern.length) {
      if (pattern[index] != '%') {
        index += 1
        continue
      }
      var cursor = index + 1
      if (cursor < pattern.length && pattern[cursor] in "-_0") cursor += 1
      if (cursor < pattern.length) letters.add(pattern[cursor])
      index = cursor + 1
    }
    return letters
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
