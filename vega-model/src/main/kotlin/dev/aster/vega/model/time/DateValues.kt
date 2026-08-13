package dev.aster.vega.model.time

import dev.aster.vega.model.VegaValue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant

/**
 * Reading dates out of a specification.
 *
 * A date reaches this engine as a number already in epoch milliseconds, or as a string, because
 * JSON has neither a date type nor a convention for one. Everything past this point is
 * milliseconds, which is what lets a time scale be an ordinary continuous scale with an unusual way
 * of choosing ticks.
 *
 * ISO 8601 is read, and so is the **month-name** form that JavaScript's own `Date` accepts — `Jan 1
 * 2000`, `January 1, 2000`, `1 Jan 2000`, optionally with a time. Upstream's `format.parse` hands
 * the string to `new Date()`, and two of Vega's own examples ship a CSV whose dates are written
 * that way; refusing them left those charts with no time axis at all.
 *
 * The two forms are read in **different zones**, which is JavaScript's rule rather than a choice
 * made here: a bare ISO date is UTC, and a month-name date is local — `new Date('Jan 1 2000')` is
 * local midnight. Anything neither form describes is refused rather than guessed at, because the
 * host parsers disagree past that point and a specification relying on one would render differently
 * in two places. An unreadable value is reported by the caller rather than silently becoming an
 * epoch-zero date at the far left of the chart.
 */
public object DateValues {

  /** Epoch milliseconds for [value], or `null` when it cannot be read as an instant. */
  public fun parse(
    value: VegaValue,
    local: TimeZone = TimeZone.currentSystemDefault(),
  ): VegaValue? =
    when (value) {
      is VegaValue.Num -> value
      // A **date** is already parsed: `datetime()` and `timeParse()` answer one, and a transform
      // reading a column a formula wrote gets it back unchanged. Falling through to `null` here
      // dropped every such row, which is a calendar with a fiftieth of its labels.
      is VegaValue.Timestamp -> value
      is VegaValue.Str ->
        (parseIso(value.value, local)
            ?: parseSlashes(value.value, local)
            ?: parseTextual(value.value, local))
          ?.let { VegaValue.Num(it) }
      else -> null
    }

  /**
   * ISO 8601: `YYYY`, `YYYY-MM`, `YYYY-MM-DD`, optionally `THH:MM[:SS[.sss]]`, optionally `Z` or a
   * `±HH:MM` offset.
   *
   * A date with no time and no zone is midnight UTC, matching how JavaScript reads a date-only
   * string — and *not* how it reads one that carries a time, which is local. Reproducing that split
   * matters: it is the difference between a bar landing on the first of the month and the last of
   * the previous one.
   */
  public fun parseIso(text: String, local: TimeZone = TimeZone.currentSystemDefault()): Double? {
    val match = ISO.matchEntire(text.trim()) ?: return null
    val g = match.groupValues
    val year = g[1].toIntOrNull() ?: return null
    val month = (g[2].ifEmpty { "1" }).toIntOrNull() ?: return null
    val day = (g[3].ifEmpty { "1" }).toIntOrNull() ?: return null
    val hasTime = g[4].isNotEmpty()

    val date =
      try {
        LocalDate(year, month, day)
      } catch (_: IllegalArgumentException) {
        return null
      }
    val zone = zoneFor(g[8], hasTime, local)
    if (!hasTime) return date.atStartOfDayIn(zone).toEpochMilliseconds().toDouble()

    val time =
      try {
        LocalTime(
          hour = g[4].toInt(),
          minute = g[5].toInt(),
          second = g[6].ifEmpty { "0" }.toInt(),
          nanosecond = millisFrom(g[7]) * 1_000_000,
        )
      } catch (_: IllegalArgumentException) {
        return null
      }
    return LocalDateTime(date, time).toInstant(zone).toEpochMilliseconds().toDouble()
  }

  /**
   * Which zone an ISO string is read in.
   *
   * A date-only string is UTC; one carrying a wall-clock time with no zone is local. That is
   * JavaScript's rule, which upstream inherits, and it is not symmetric by accident — a bare date
   * is a calendar day and a bare time is a moment someone was standing somewhere.
   */
  private fun zoneFor(zone: String, hasTime: Boolean, local: TimeZone): TimeZone =
    when {
      zone.isEmpty() -> if (hasTime) local else TimeZone.UTC
      zone == "Z" || zone == "z" -> TimeZone.UTC
      else -> TimeZone.of("UTC$zone")
    }

  /** `.5` is 500 milliseconds, not 5; pad rather than truncate. */
  private fun millisFrom(fraction: String): Int =
    if (fraction.isEmpty()) 0 else fraction.padEnd(3, '0').take(3).toInt()

  /**
   * `Jan 1 2000`, `January 1, 2000`, `1 Jan 2000`, `Jan 2000`, any of them with a trailing time.
   *
   * Read in the **local** zone, as JavaScript reads them. The parse is deliberately loose about
   * order — a month name, a year and an optional day in any arrangement — because that is what the
   * host parsers agree on, and strict about everything else: no month name, no year, or a token
   * that is neither means the value is refused rather than guessed at.
   */
  public fun parseTextual(
    text: String,
    local: TimeZone = TimeZone.currentSystemDefault(),
  ): Double? {
    val tokens = text.trim().split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null

    var month: Int? = null
    var year: Int? = null
    var day: Int? = null
    var time: LocalTime? = null

    for (token in tokens) {
      val lower = token.lowercase()
      val named = MONTHS.indexOfFirst { it.startsWith(lower) && lower.length >= 3 }
      when {
        named >= 0 && month == null -> month = named + 1
        token.contains(':') -> time = parseClock(token) ?: return null
        token.all { it.isDigit() } ->
          when {
            token.length == 4 && year == null -> year = token.toInt()
            token.length <= 2 && day == null -> day = token.toInt()
            else -> return null
          }
        // A trailing zone name or offset is more than this reads; refuse rather than drop it.
        else -> return null
      }
    }
    if (month == null || year == null) return null

    val date =
      try {
        LocalDate(year, month, day ?: 1)
      } catch (_: IllegalArgumentException) {
        return null
      }
    return if (time == null) {
      date.atStartOfDayIn(local).toEpochMilliseconds().toDouble()
    } else {
      LocalDateTime(date, time).toInstant(local).toEpochMilliseconds().toDouble()
    }
  }

  private fun parseClock(token: String): LocalTime? {
    val parts = token.split(':')
    if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all { c -> c.isDigit() } })
      return null
    return try {
      LocalTime(parts[0].toInt(), parts[1].toInt(), parts.getOrNull(2)?.toInt() ?: 0)
    } catch (_: IllegalArgumentException) {
      null
    }
  }

  private val MONTHS =
    listOf(
      "january",
      "february",
      "march",
      "april",
      "may",
      "june",
      "july",
      "august",
      "september",
      "october",
      "november",
      "december",
    )

  /**
   * `2001/01/01`, optionally with a time — the form a flight log writes.
   *
   * Read in the **local** zone whether or not it carries a time, which is where it differs from the
   * ISO form: a bare `2001-01-01` is UTC and a bare `2001/01/01` is local. That is JavaScript's
   * split, not a choice made here, and it is a whole hour of difference on a chart bucketing by
   * day.
   */
  public fun parseSlashes(
    text: String,
    local: TimeZone = TimeZone.currentSystemDefault(),
  ): Double? {
    val match = SLASHES.matchEntire(text.trim()) ?: return null
    val g = match.groupValues
    val date =
      try {
        LocalDate(g[1].toInt(), g[2].toInt(), g[3].toInt())
      } catch (_: IllegalArgumentException) {
        return null
      }
    val time =
      try {
        LocalTime(
          g[4].ifEmpty { "0" }.toInt(),
          g[5].ifEmpty { "0" }.toInt(),
          g[6].ifEmpty { "0" }.toInt(),
        )
      } catch (_: IllegalArgumentException) {
        return null
      }
    return LocalDateTime(date, time).toInstant(local).toEpochMilliseconds().toDouble()
  }

  private val SLASHES =
    Regex("(\\d{4})/(\\d{1,2})/(\\d{1,2})(?:[T ](\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?")

  private val ISO =
    Regex(
      "(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?" +
        "(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?)?" +
        "(Z|z|[+-]\\d{2}:?\\d{2})?"
    )
}
