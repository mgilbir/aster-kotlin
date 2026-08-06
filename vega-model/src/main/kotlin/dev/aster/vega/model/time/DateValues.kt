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
 * Only ISO 8601 is read, and deliberately: upstream falls back to the host's `Date` parser for
 * anything else, which is famously inconsistent between browsers, so a specification relying on it
 * would render differently in two places. An unreadable value is reported by the caller rather than
 * silently becoming an epoch-zero date at the far left of the chart.
 */
public object DateValues {

  /** Epoch milliseconds for [value], or `null` when it cannot be read as an instant. */
  public fun parse(
    value: VegaValue,
    local: TimeZone = TimeZone.currentSystemDefault(),
  ): VegaValue? =
    when (value) {
      is VegaValue.Num -> value
      is VegaValue.Str -> parseIso(value.value, local)?.let { VegaValue.Num(it) }
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

  private val ISO =
    Regex(
      "(\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?" +
        "(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d{1,9}))?)?)?" +
        "(Z|z|[+-]\\d{2}:?\\d{2})?"
    )
}
