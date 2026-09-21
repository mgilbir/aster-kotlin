package dev.aster.vega.model.time

import dev.aster.vega.model.Decimals
import dev.aster.vega.model.locale.DateName
import dev.aster.vega.model.locale.DateNameContext
import dev.aster.vega.model.locale.VegaLocale
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
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

  /**
   * Formats [millis], which **need not be a number**.
   *
   * A value that is not an instant reaches here, and upstream prints something for it rather than
   * nothing: `new Date(NaN)` is an Invalid Date, every one of its getters answers `NaN`, and d3
   * formats those `NaN`s through the same padding as any other field — so `%Y` is `0NaN` and `%d`
   * is `NaN`. It is garbage, and it is *upstream's* garbage, which matters because the width of the
   * label it produces is the height of a chart whose x labels are turned on their side. An
   * eight-pixel difference nobody could explain was this and nothing else.
   *
   * Reaching it takes no contrivance: `formatType: "time"` on a column of words is enough, because
   * Vega-Lite then writes `toDate(datum[...])` over it and `Date.parse` answers `NaN`. Before this,
   * `at(NaN, zone)` truncated to zero and the label read **1970**.
   *
   * `test-fixtures/upstream-vectors/d3-invalid-date.json` holds d3's own answer for every directive
   * and every pad modifier; `InvalidDateFormatTest` replays it.
   */
  public fun format(
    millis: Double,
    pattern: String,
    zone: TimeZone,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): String =
    render(pattern, if (millis.isFinite()) at(millis, zone) else null, millis, zone, locale)

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
    /** Null when the value being formatted is not an instant; every field is then `NaN`. */
    at: LocalDateTime?,
    millis: Double,
    zone: TimeZone,
    locale: VegaLocale,
  ): String {
    val out = StringBuilder(pattern.length + 8)
    // Sunday-first, because that is the week d3 labels against.
    val weekday = at?.date?.dayOfWeek?.isoDayNumber?.rem(7)
    // Lazily, and only where a rule exists to read it: parsing the pattern a second time for every
    // label a chart draws would be a cost paid by every chart that supplies no rules at all.
    var context: DateNameContext? = null
    var i = 0
    while (i < pattern.length) {
      val c = pattern[i]
      if (c != '%') {
        out.append(c)
        i++
        continue
      }
      // An optional pad modifier sits between the percent and the directive.
      var cursor = i + 1
      var padWith: Char? = null
      if (cursor <= pattern.lastIndex && pattern[cursor] in "-_0") {
        padWith = pattern[cursor]
        cursor++
      }
      // **A percent consumes what follows it, and a percent with nothing after it consumes
      // itself.** d3 reads the character after the percent, looks it up, and pushes whatever the
      // table gave back — which is the character itself where there is no entry, and the empty
      // string where the pattern ended first, `charAt` past the end being `""`. So `%~` is `~`,
      // `%-~` is `~` with the modifier swallowed too, and a dangling percent disappears:
      // `timeFormat(".0%")` reads `.0` and `timeFormat("%")` reads nothing at all.
      //
      // This appended the percent as written in both cases, which is the one shape upstream never
      // produces. It surfaces wherever a *number* specifier reaches a time scale — a normalized
      // stack on a temporal axis is asked for `.0%` — and there the whole label differed by the
      // trailing character.
      if (cursor > pattern.lastIndex) {
        i = cursor
        continue
      }
      val directive = pattern[cursor]
      // Each numeric piece goes through the host's numbering system, if it has one — **after**
      // padding, so a rule cannot change how wide a field is. That width is the specification's:
      // `%02d` said two digits and it gets two digits, in whatever digits the host writes.
      fun digits(text: String): String = locale.rules?.digits(text) ?: text

      /**
       * d3's `pad`, whole — and it is the **only** place a field's width is decided, which is what
       * carries a value that is not a number without a second table to keep in step:
       * ```js
       * function pad(value, fill, width) {
       *   var sign = value < 0 ? "-" : "",
       *       string = (sign ? -value : value) + "",
       *       length = string.length;
       *   return sign + (length < width ? new Array(width - length + 1).join(fill) + string : string);
       * }
       * ```
       *
       * A null [value] is `NaN`: `NaN < 0` is false so there is no sign, and `NaN + ''` is the
       * three characters `NaN`, which then pad exactly as digits would. That is the whole of why
       * `%Y` prints `0NaN` — three characters padded to four — while `%d`, being two wide already,
       * prints `NaN` unpadded and `%-Y` prints it unpadded too.
       *
       * The sign goes **outside** the width, and a year is taken modulo its own width by the arm
       * that reads it: `%Y` is `year % 10000` padded to four and `%y` is `year % 100` padded to
       * two, so the year 10002 writes `0002` and the year -2 writes `-0002` with the minus in front
       * of the padding rather than counted by it. That was a second function until the pad modifier
       * had to reach it — `%-Y` of the year 24 is `24` upstream and was `0024` here, because the
       * year arms did not consult the modifier at all.
       */
      fun number(value: Int?, width: Int, default: Char = '0') {
        val sign = if (value != null && value < 0) "-" else ""
        val text = if (value == null) "NaN" else kotlin.math.abs(value).toString()
        val fill =
          when (padWith) {
            '-' -> null
            '_' -> ' '
            '0' -> '0'
            else -> default
          }
        out.append(
          digits(
            sign +
              if (fill != null && text.length < width) {
                fill.toString().repeat(width - text.length) + text
              } else {
                text
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

      /**
       * A name for a value that is not an instant: **nothing**, not the word "undefined".
       *
       * d3 indexes its locale's array with `NaN`, gets `undefined`, and pushes that into the array
       * it joins — and `[undefined].join('')` is the empty string. So `%B` of an Invalid Date is
       * `""` where every numeric directive beside it says `NaN`, which reads like an oversight and
       * is the recorded answer.
       */
      fun nameOf(field: DateName, index: Int?, name: (Int) -> String): String =
        if (index == null) "" else named(field, index, name(index))
      when (directive) {
        'Y' -> number(at?.year?.rem(10000), 4)
        'y' -> number(at?.year?.rem(100), 2)
        'm' -> number(at?.month?.number, 2)
        'B' -> out.append(nameOf(DateName.MONTH, at?.month?.number?.minus(1), locale.months::get))
        'b' ->
          out.append(
            nameOf(DateName.MONTH_SHORT, at?.month?.number?.minus(1), locale.shortMonths::get)
          )
        'A' -> out.append(nameOf(DateName.WEEKDAY, weekday, locale.days::get))
        'a' -> out.append(nameOf(DateName.WEEKDAY_SHORT, weekday, locale.shortDays::get))
        'd' -> number(at?.day, 2)
        'e' -> number(at?.day, 2, default = ' ')
        'j' -> number(at?.date?.dayOfYear, 3)
        // Vega's own addition to d3's directives, and the only way to write a quarter. `1 +
        // ~~(d.getMonth() / 3)`, and `~~NaN` is **zero** — so a value that is not an instant is in
        // the first quarter rather than in none.
        'q' -> out.append(digits((at?.month?.number?.let { (it - 1) / 3 + 1 } ?: 1).toString()))
        'U' -> number(at?.let(::sundayWeek), 2)
        'W' -> number(at?.let(::mondayWeek), 2)
        'V' -> number(at?.let(::isoWeek), 2)
        'G' -> number(at?.let(::isoWeekYear)?.rem(10000), 4)
        'g' -> number(at?.let(::isoWeekYear)?.rem(100), 2)
        // Neither of these is padded upstream: they answer a number, not a field.
        'u' -> out.append(digits(at?.date?.dayOfWeek?.isoDayNumber?.toString() ?: "NaN"))
        'w' -> out.append(digits(weekday?.toString() ?: "NaN"))
        'H' -> number(at?.hour, 2)
        // Twelve-hour clock, where midnight and noon both read 12 rather than 0 — `pad(d.getHours()
        // % 12 || 12, p, 2)`, and since `NaN` is **falsy** a value that is not an instant reads
        // `12` rather than `NaN`.
        'I' ->
          number(at?.hour?.let { (it % 12).let { hour -> if (hour == 0) 12 else hour } } ?: 12, 2)
        'p' -> {
          // `locale_periods[+(d.getHours() >= 12)]`: `NaN >= 12` is false, so a value that is not
          // an instant is in the **morning** rather than nowhere.
          val half = if (at != null && at.hour >= 12) 1 else 0
          out.append(named(DateName.HALF_DAY, half, locale.periods[half]))
        }
        'M' -> number(at?.minute, 2)
        'S' -> number(at?.second, 2)
        'L' -> number(at?.nanosecond?.div(1_000_000), 3)
        // `formatMilliseconds(d, p) + "000"`, so the three zeroes are appended to whatever the
        // milliseconds printed — `NaN000` included.
        // **The milliseconds padded to three, with `"000"` appended** — not a six-wide field:
        // `formatMicroseconds(d, p) = formatMilliseconds(d, p) + "000"`. The two agree for every
        // real instant, which is why a six-wide field went unnoticed, and part company the moment
        // the field is not a number: upstream writes `NaN000` and a six-wide pad writes `000NaN`.
        'f' -> {
          number(at?.nanosecond?.div(1_000_000), 3)
          out.append(digits("000"))
        }
        'Q' -> out.append(digits(Decimals.jsString(millis)))
        // `Math.floor(+d / 1000)`, and the floor is the whole of it: a division that truncates
        // toward zero is a second late for every instant before 1970. d3 writes `-2` for
        // `-1500`; truncation writes `-1`, which is the same second twice and a missing one.
        's' -> out.append(digits(Decimals.jsString(kotlin.math.floor(millis / 1000.0))))
        // `%Z` is the one directive upstream implements **twice**, and the second one does not
        // read the date at all:
        //
        //     function formatUTCZone() { return "+0000"; }
        //
        // against `formatZone`, which reads `getTimezoneOffset()` — `NaN` for an Invalid Date, so
        // `NaN > 0` is false and the sign is `+`, `NaN / 60 | 0` is `0` so the hours are `00`, and
        // `NaN % 60` stays `NaN`. A zone is a parameter here rather than a choice of formatter, so
        // UTC stands in for the second: it is the only zone d3's UTC formatter can mean, and for
        // every real instant the arithmetic below already answers `+0000` for it.
        'Z' ->
          out.append(
            digits(
              when {
                at != null -> offset(millis, zone)
                zone == TimeZone.UTC -> "+0000"
                else -> "+00NaN"
              }
            )
          )
        // The locale's own compositions. d3's en-US spells these three out as the defaults on
        // `VegaLocale`, and a locale that writes its dates the other way round says so there rather
        // than by rewriting every specification that uses `%x`.
        'c' -> out.append(render(locale.dateTime, at, millis, zone, locale))
        'x' -> out.append(render(locale.date, at, millis, zone, locale))
        'X' -> out.append(render(locale.time, at, millis, zone, locale))
        '%' -> out.append('%')
        // Unknown directive: the character survives and the percent does not, which is what
        // `formats[c]` being absent leaves `c` as.
        else -> out.append(directive)
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
    // The week is the Thursday's day of the year divided by seven, and nothing else: this used to
    // add `if (firstOfYear.dayOfWeek > 4) 0 else 0`, which is zero either way and read as though a
    // correction were being applied.
    return ((thursday.dayOfYear - 1) / 7) + 1
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
}
