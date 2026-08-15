package dev.aster.vega.model.time

/**
 * The calendar units a specification can bucket dates into, and the format each one is read with.
 *
 * Upstream's `vega-time/src/units.js`. The table is not a convenience: `timeunit` produces buckets
 * whose *value* is an instant, so the only thing that says a bucket labelled `1325372400000` is a
 * Sunday rather than a January is the specifier chosen for the units it was built from.
 */
public object TimeUnits {

  /**
   * The units in the order upstream sorts them, coarsest first.
   *
   * The order is what makes a compound specifier readable — `["date", "year"]` has to come out as
   * `%Y-%m-%d` and not as the two written backwards — and it is why the lookup below can be greedy.
   */
  public val ALL: List<String> =
    listOf(
      "year",
      "quarter",
      "month",
      "week",
      "date",
      "day",
      "dayofyear",
      "hours",
      "minutes",
      "seconds",
      "milliseconds",
    )

  /**
   * How each unit, or each recognised *run* of units, is written.
   *
   * The trailing spaces are load-bearing: the pieces are concatenated and the result trimmed, so
   * `["month", "date"]` reads `%b %d` while `["hours", "minutes"]` — matched as one key — reads
   * `%H:%M` with no gap.
   */
  private val SPECIFIERS =
    mapOf(
      "year" to "%Y ",
      "quarter" to "Q%q ",
      "month" to "%b ",
      "date" to "%d ",
      "week" to "W%U ",
      "day" to "%a ",
      "dayofyear" to "%j ",
      "hours" to "%H:00",
      "minutes" to "00:%M",
      "seconds" to ":%S",
      "milliseconds" to ".%L",
      "year-month" to "%Y-%m ",
      "year-month-date" to "%Y-%m-%d ",
      "hours-minutes" to "%H:%M",
    )

  /**
   * `timeUnitSpecifier(units, specifiers)` — the time format a set of units is labelled with.
   *
   * The match is greedy over the *longest* run of units that has an entry, which is what collapses
   * `["year", "month", "date"]` into one `%Y-%m-%d` instead of three separate fields. [overrides]
   * replaces an entry rather than extending the table, and a chart uses it to shorten one unit
   * without restating the rest — `{hours: '%H'}` drops the `:00`.
   *
   * Unrecognised units are dropped rather than rejected, so a specification naming one gets a
   * shorter label rather than no chart.
   */
  public fun specifier(units: List<String>, overrides: Map<String, String?> = emptyMap()): String {
    // Nullable on purpose. Upstream builds the table with `extend({}, defaults, specifiers)` and
    // then
    // tests `s[key] != null`, so an override set to **null** does not fall back to the default — it
    // *removes* the entry, and the search drops to a shorter run of units. That is the only way to
    // say "do not combine these two", and `timeUnitSpecifier(['hours','minutes'], {'hours-minutes':
    // null})` is `%H %Mmin` upstream where taking the built-in combination gives `%H:%M`. The
    // signature used to be `Map<String, String>`, which cannot express it at all; upstream's own
    // test vectors are what caught it.
    val table: Map<String, String?> = SPECIFIERS + overrides
    val ordered = ALL.filter { it in units }
    val out = StringBuilder()
    var start = 0
    while (start < ordered.size) {
      var end = ordered.size
      while (end > start) {
        val entry = table[ordered.subList(start, end).joinToString("-")]
        if (entry != null) {
          out.append(entry)
          break
        }
        end--
      }
      // No run beginning here is named, so nothing this unit could add is known; skip past it.
      start = if (end > start) end else start + 1
    }
    return out.toString().trim()
  }
}
