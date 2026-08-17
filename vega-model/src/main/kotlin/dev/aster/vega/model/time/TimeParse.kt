package dev.aster.vega.model.time

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant

/**
 * `timeParse` and `utcParse`: a date read back out of a formatted string.
 *
 * The inverse of [TimeFormat], and a transcription of `d3-time-format`'s `newParse` rather than a
 * reasonable-looking parser, because the two differ in ways a chart notices. Three rules carry most
 * of it:
 *
 * The **whole** string must be consumed. `parseSpecifier` walks the specifier and the input
 * together and the result is discarded unless the input ends exactly where the specifier does, so
 * `timeParse('2020-03-15 extra', '%Y-%m-%d')` is null rather than March. Literal characters in the
 * specifier have to match literally, which is what makes a wrong separator a failure instead of a
 * guess.
 *
 * A numeric field reads **at most** as many digits as its width — two for `%d`, four for `%Y` — and
 * `numberRe` skips leading whitespace first, so `%d` accepts `" 5"`. It reads *fewer* digits
 * happily, which is why `%Y-%j` parses `2020-75` as well as `2020-075`.
 *
 * What is not written defaults to **1900-01-01T00:00:00**, not to today: a specifier of `%I:%M %p`
 * gives a time on the first of January 1900, and that is the date a chart plots it on.
 *
 * Overflow normalises the way `new Date(y, m, d, …)` normalises it — month 13 is next January, day
 * 32 is the first of the month after — which is [TimeFormat]'s mirror image and the reason `%j` can
 * set day 366 and mean the last day of a leap year.
 */
public object TimeParse {

  /** Every field a specifier can set, before the defaults and the corrections are applied. */
  private class Fields {
    var year: Int? = null
    var month: Int? = null
    var quarter: Int? = null
    var day: Int? = null
    var dayOfYear: Int? = null
    var hour: Int? = null
    var minute: Int? = null
    var second: Int? = null
    var milli: Int? = null
    /** 0 for AM and 1 for PM, which is what makes `%I` a 24-hour field until this is applied. */
    var period: Int? = null
    /** Minutes-as-hhmm east of UTC, negated: d3 stores `-(hh × 100 + mm)`. */
    var zoneOffset: Int? = null
    var weekday: Int? = null
    var weekSunday: Int? = null
    var weekMonday: Int? = null
    var weekIso: Int? = null
    var unixMillis: Double? = null
    var unixSeconds: Double? = null
  }

  /**
   * Parses [text] against [specifier], in [zone].
   *
   * Returns epoch milliseconds, or null when the specifier and the string do not match exactly.
   * [utc] is the `utcParse` flag: with it, a string carrying no `%Z` is read as UTC rather than
   * local.
   */
  public fun parse(text: String, specifier: String, zone: TimeZone, utc: Boolean): Double? {
    val fields = Fields()
    val consumed = walk(fields, specifier, text, 0) ?: return null
    if (consumed != text.length) return null

    fields.unixMillis?.let {
      return it
    }
    fields.unixSeconds?.let {
      return it * 1000.0 + (fields.milli ?: 0)
    }

    // `utcParse` never falls back to the local zone: an absent offset *is* zero.
    if (utc && fields.zoneOffset == null) fields.zoneOffset = 0

    // The am/pm flag folds a 12-hour reading into a 24-hour one. `12 AM` is hour 0 and `12 PM` is
    // 12,
    // which `H % 12 + p * 12` gives without a special case.
    fields.period?.let { fields.hour = (fields.hour ?: 0) % 12 + it * 12 }

    // A month left unwritten inherits the quarter, so `%Y-Q%q` means the first month of it.
    if (fields.month == null) fields.month = fields.quarter ?: 0

    resolveWeek(fields) ?: return null

    val year = fields.year ?: 1900
    var hour = fields.hour ?: 0
    var minute = fields.minute ?: 0
    val offset = fields.zoneOffset
    if (offset != null) {
      // d3 adds the offset to the *fields* and then reads them as UTC, rather than converting after
      // the fact: `H += Z / 100 | 0` truncates toward zero and `M += Z % 100` keeps the sign, so a
      // half-hour zone lands on the right minute for both signs.
      hour += offset / 100
      minute += offset % 100
    }
    val millis =
      instant(
        year = year,
        month = fields.month ?: 0,
        day = fields.day ?: 1,
        hour = hour,
        minute = minute,
        second = fields.second ?: 0,
        milli = fields.milli ?: 0,
        zone = if (offset != null) TimeZone.UTC else zone,
      ) ?: return null
    return millis
  }

  /**
   * `%U`, `%W` and `%V` — a week number turned into a day of the year.
   *
   * Transcribed because each of the three counts differently: `%U` counts Sundays, `%W` counts
   * Mondays and `%V` is the ISO week, whose first week is the one holding the year's first
   * Thursday. Returns null when the ISO week is out of range, which d3 rejects rather than
   * clamping, and Unit when there was no week to resolve.
   */
  private fun resolveWeek(fields: Fields): Unit? {
    val year = fields.year ?: 1900
    val iso = fields.weekIso
    if (iso != null) {
      if (iso < 1 || iso > 53) return null
      val weekday = fields.weekday ?: 1
      val firstDay = LocalDate(year, 1, 1)
      val dayOfWeek = firstDay.dayOfWeek.isoDayNumber % 7
      // d3 takes the Monday of the week holding 4 January, expressed as: ceil to Monday when the
      // year starts on a Friday, Saturday, Sunday, and floor to Monday otherwise.
      val backToMonday = (dayOfWeek + 6) % 7
      val monday =
        if (dayOfWeek > 4 || dayOfWeek == 0) firstDay.plus(7 - backToMonday, DateTimeUnit.DAY)
        else firstDay.plus(-backToMonday, DateTimeUnit.DAY)
      val start: LocalDate = monday.plus((iso - 1) * 7, DateTimeUnit.DAY)
      fields.year = start.year
      fields.month = start.month.number - 1
      fields.day = start.day + (weekday + 6) % 7
      return Unit
    }
    val sunday = fields.weekSunday
    val monday = fields.weekMonday
    if (sunday == null && monday == null) return Unit
    val weekday = fields.weekday ?: if (monday != null) 1 else 0
    val firstDayOfWeek = LocalDate(year, 1, 1).dayOfWeek.isoDayNumber % 7
    fields.month = 0
    fields.day =
      if (monday != null) (weekday + 6) % 7 + monday * 7 - (firstDayOfWeek + 5) % 7
      else weekday + (sunday ?: 0) * 7 - (firstDayOfWeek + 6) % 7
    return Unit
  }

  /**
   * The specifier and the string walked together, returning where the string was left.
   *
   * `%` introduces a directive, a **pad** character between the `%` and the letter is skipped —
   * `%-d` and `%d` parse identically, since padding only means something when formatting — and
   * anything else has to match the input character for character.
   */
  private fun walk(fields: Fields, specifier: String, text: String, from: Int): Int? {
    var at = from
    var index = 0
    while (index < specifier.length) {
      if (at >= text.length) return null
      val c = specifier[index++]
      if (c != '%') {
        if (c != text[at]) return null
        at++
        continue
      }
      if (index >= specifier.length) return null
      var directive = specifier[index++]
      if (directive in PADS) {
        if (index >= specifier.length) return null
        directive = specifier[index++]
      }
      at = field(fields, directive, text, at) ?: return null
    }
    return at
  }

  /** One directive, returning where it left the string or null when it did not match. */
  @Suppress("CyclomaticComplexMethod")
  private fun field(fields: Fields, directive: Char, text: String, at: Int): Int? =
    when (directive) {
      'Y' -> number(text, at, 4)?.also { fields.year = it.value }?.next
      'G' -> number(text, at, 4)?.also { fields.year = it.value }?.next
      // Two digits, and the century d3 chooses: 69 and above is the twentieth century.
      'y' ->
        number(text, at, 2)
          ?.also { fields.year = it.value + if (it.value > 68) 1900 else 2000 }
          ?.next
      'g' ->
        number(text, at, 2)
          ?.also { fields.year = it.value + if (it.value > 68) 1900 else 2000 }
          ?.next
      'm' -> number(text, at, 2)?.also { fields.month = it.value - 1 }?.next
      'd',
      'e' -> number(text, at, 2)?.also { fields.day = it.value }?.next
      'j' ->
        number(text, at, 3)
          ?.also {
            fields.month = 0
            fields.day = it.value
          }
          ?.next
      'q' -> number(text, at, 1)?.also { fields.quarter = (it.value - 1) * 3 }?.next
      'H',
      'I' -> number(text, at, 2)?.also { fields.hour = it.value }?.next
      'M' -> number(text, at, 2)?.also { fields.minute = it.value }?.next
      'S' -> number(text, at, 2)?.also { fields.second = it.value }?.next
      'L' -> number(text, at, 3)?.also { fields.milli = it.value }?.next
      // Microseconds are read and **divided**, which is d3's `d.L = Math.floor(n / 1000)`: the
      // value
      // model has no unit below the millisecond and neither does a `Date`.
      'f' -> number(text, at, 6)?.also { fields.milli = it.value / 1000 }?.next
      'u' -> number(text, at, 1)?.also { fields.weekday = it.value % 7 }?.next
      'w' -> number(text, at, 1)?.also { fields.weekday = it.value }?.next
      'U' -> number(text, at, 2)?.also { fields.weekSunday = it.value }?.next
      'W' -> number(text, at, 2)?.also { fields.weekMonday = it.value }?.next
      'V' -> number(text, at, 2)?.also { fields.weekIso = it.value }?.next
      // Read as a **Long**, not an Int: an epoch in milliseconds is thirteen digits and `toInt()`
      // throws on it, so `parse("%Q")` failed for every real timestamp — including the one d3's own
      // test uses. The seconds form has the same reach once a date is past 2038.
      'Q' -> epoch(text, at)?.also { fields.unixMillis = it.value }?.next
      's' -> epoch(text, at)?.also { fields.unixSeconds = it.value }?.next
      'p' -> named(text, at, PERIODS)?.also { fields.period = it.index }?.next
      'a' ->
        named(text, at, TimeFormat.WEEKDAYS, abbreviated = true)
          ?.also { fields.weekday = it.index }
          ?.next
      'A' -> named(text, at, TimeFormat.WEEKDAYS)?.also { fields.weekday = it.index }?.next
      'b' ->
        named(text, at, TimeFormat.MONTHS, abbreviated = true)
          ?.also { fields.month = it.index }
          ?.next
      'B' -> named(text, at, TimeFormat.MONTHS)?.also { fields.month = it.index }?.next
      'Z' -> zone(text, at, fields)
      '%' -> if (at < text.length && text[at] == '%') at + 1 else null
      // `%c`, `%x` and `%X` are the locale's own composite formats, which recurse into the
      // specifier
      // the locale defines. This engine has one locale and its three are written out here.
      'c' -> walk(fields, LOCALE_DATE_TIME, text, at)
      'x' -> walk(fields, LOCALE_DATE, text, at)
      'X' -> walk(fields, LOCALE_TIME, text, at)
      else -> null
    }

  private class Read(val value: Int, val next: Int)

  private class Match(val index: Int, val next: Int)

  /**
   * A run of digits, at most [width] of them, after any leading whitespace.
   *
   * `numberRe` is `/^\s*\d+/` applied to a *slice* of the input, so the width bounds the slice
   * rather than the digits: `%d` over `" 5x"` reads the space and the 5 and stops.
   */
  /** An epoch count, which does not fit an `Int`. */
  private fun epoch(text: String, at: Int): ReadDouble? {
    var index = at
    val limit = minOf(text.length, at + 18)
    while (index < limit && text[index].isWhitespace()) index++
    val start = index
    if (index < limit && (text[index] == '-' || text[index] == '+')) index++
    while (index < limit && text[index].isDigit()) index++
    val digits = text.substring(start, index)
    val value = digits.toDoubleOrNull() ?: return null
    return ReadDouble(value, index)
  }

  private class ReadDouble(val value: Double, val next: Int)

  private fun number(text: String, at: Int, width: Int): Read? {
    var index = at
    val limit = minOf(text.length, at + width)
    while (index < limit && text[index].isWhitespace()) index++
    val start = index
    while (index < limit && text[index].isDigit()) index++
    if (index == start) return null
    return Read(text.substring(start, index).toInt(), index)
  }

  /** A name from a list, matched case-insensitively and longest-first so `March` beats `Mar`. */
  private fun named(
    text: String,
    at: Int,
    names: List<String>,
    abbreviated: Boolean = false,
  ): Match? {
    val candidates = names.mapIndexed { index, name ->
      index to if (abbreviated) name.take(3) else name
    }
    return candidates
      .sortedByDescending { it.second.length }
      .firstOrNull { text.regionMatches(at, it.second, 0, it.second.length, ignoreCase = true) }
      ?.let { Match(it.first, at + it.second.length) }
  }

  /** `%Z` — `Z`, or `+hh`, `+hhmm`, `+hh:mm`, stored the way d3 stores it. */
  private fun zone(text: String, at: Int, fields: Fields): Int? {
    if (at < text.length && text[at] == 'Z') {
      fields.zoneOffset = 0
      return at + 1
    }
    if (at + 3 > text.length) return null
    val sign = text[at]
    if (sign != '+' && sign != '-') return null
    if (!text[at + 1].isDigit() || !text[at + 2].isDigit()) return null
    var index = at + 3
    var minutes = 0
    if (index < text.length && text[index] == ':') index++
    if (index + 2 <= text.length && text[index].isDigit() && text[index + 1].isDigit()) {
      minutes = text.substring(index, index + 2).toInt()
      index += 2
    } else if (text.getOrNull(at + 3) == ':') {
      return null
    }
    val hours = text.substring(at + 1, at + 3).toInt()
    val magnitude = hours * 100 + minutes
    fields.zoneOffset = if (sign == '+') -magnitude else magnitude
    return index
  }

  /**
   * A date built from fields that may be out of range, normalised as `new Date(y, m, d, …)` is.
   *
   * The time of day is carried into the date rather than added as milliseconds, so a wall-clock
   * time stays a wall-clock time across a daylight-saving change — which is what makes `%H` mean
   * the hour a reader would have written down.
   */
  private fun instant(
    year: Int,
    month: Int,
    day: Int,
    hour: Int,
    minute: Int,
    second: Int,
    milli: Int,
    zone: TimeZone,
  ): Double? {
    var carry = 0L
    var value = milli.toLong()
    carry += value.floorDiv(1000L)
    val ms = value.mod(1000L).toInt()
    value = second.toLong() + carry
    carry = value.floorDiv(60L)
    val s = value.mod(60L).toInt()
    value = minute.toLong() + carry
    carry = value.floorDiv(60L)
    val m = value.mod(60L).toInt()
    value = hour.toLong() + carry
    val days = value.floorDiv(24L)
    val h = value.mod(24L).toInt()
    return try {
      val date =
        LocalDate(year, 1, 1)
          .plus(month.toLong(), DateTimeUnit.MONTH)
          .plus(day - 1L + days, DateTimeUnit.DAY)
      LocalDateTime(date, LocalTime(h, m, s, ms * 1_000_000))
        .toInstant(zone)
        .toEpochMilliseconds()
        .toDouble()
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  /** Pad flags, which only mean something when formatting and are skipped when parsing. */
  private val PADS = setOf('-', '_', '0')

  private val PERIODS = listOf("AM", "PM")

  /** The one locale this engine has, spelled out where d3 would look it up. */
  private const val LOCALE_DATE_TIME = "%x, %X"

  private const val LOCALE_DATE = "%-m/%-d/%Y"

  private const val LOCALE_TIME = "%-I:%M:%S %p"
}
