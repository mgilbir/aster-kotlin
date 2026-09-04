package dev.aster.vega.model.time

import kotlin.math.abs
import kotlin.time.Instant
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.number
import kotlinx.datetime.offsetAt
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

  public companion object {
    /**
     * The interval a specification's **unit name** stands for, with the step it implies.
     *
     * Vega's names are not this enum's: they are the `timeunit` names — `"hours"`, `"minutes"`,
     * `"date"`, `"dayofyear"`, `"quarter"` — and a scale's `nice` or an axis's `tickCount` is
     * written in those. Matching on the enum's own names instead worked for `"month"` and `"year"`
     * and quietly failed for everything plural, which is most of them, and for `"quarter"`, which
     * is not an interval at all but three months.
     *
     * @return the interval and how many of it make one step, or null when nothing is named.
     */
    public fun forUnit(unit: String?): Pair<TimeInterval, Int>? =
      when (unit?.lowercase()) {
        "year" -> YEAR to 1
        // Not an interval of its own anywhere: d3 has no quarter, and upstream spells it
        // `timeMonth.every(3)`.
        "quarter" -> MONTH to 3
        "month" -> MONTH to 1
        "week" -> WEEK to 1
        "date",
        "day",
        "dayofyear" -> DAY to 1
        "hours" -> HOUR to 1
        "minutes" -> MINUTE to 1
        "seconds" -> SECOND to 1
        "milliseconds" -> MILLISECOND to 1
        else -> null
      }
  }

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
  /**
   * Whether a stepped day counts from the **epoch** rather than from the first of the month.
   *
   * d3 has two day intervals and uses both: `timeDay`, whose `every(n)` keeps days where the day of
   * the month minus one divides by `n`, and `unixDay`, which keeps days where the count of days
   * since 1970 does. Its local tick table is built on the first and its UTC table on the second, so
   * the *same* two-day step lands on the 1st, 3rd, 5th of each month on a local axis and on even
   * epoch days on a UTC one. Ignoring the difference put every label of a two-day UTC axis a day
   * out.
   */
  private val epochDays: Boolean = false,
) {

  private fun instant(millis: Double) = Instant.fromEpochMilliseconds(millis.toLong())

  private fun millis(instant: Instant) = instant.toEpochMilliseconds().toDouble()

  private fun local(millis: Double): LocalDateTime = instant(millis).toLocalDateTime(zone)

  /** The start of the [interval] containing [millis], then snapped down to a multiple of [step]. */
  /** The millisecond field of a local time, which is the part below a second. */
  private fun subMillis(at: LocalDateTime): Double = (at.nanosecond / 1_000_000).toDouble()

  public fun floor(millis: Double): Double {
    val at = local(millis)
    return when (interval) {
      TimeInterval.MILLISECOND -> {
        val ms = at.nanosecond / 1_000_000
        millis - (ms - ms / step * step)
      }
      // The sub-day intervals **subtract** rather than rebuild, which is d3's own arithmetic —
      // `date.setTime(date - ms - seconds*1e3 - minutes*durationMinute)` — and the difference shows
      // up exactly once a year. Rebuilding a local time is ambiguous across a daylight-saving
      // fall-back: 01:30 happens twice in Los Angeles on 6 November 2011, and reconstructing it
      // resolves to the *first* occurrence, so flooring an instant in the second hour moved it an
      // hour backwards into the first. Subtracting keeps the instant's own offset, so each of the
      // two 01:00s floors to itself. d3-time's own vectors are what caught it.
      //
      // A step greater than one still snaps the field, which is `every(step)` behaviour and is what
      // Vega's own tests expect; that path keeps the old reconstruction.
      TimeInterval.SECOND ->
        if (step == 1) millis - subMillis(at)
        else atTime(at.date, at.hour, at.minute, snapDown(at.second))
      TimeInterval.MINUTE ->
        if (step == 1) millis - (at.second * 1000.0 + subMillis(at))
        else atTime(at.date, at.hour, snapDown(at.minute), 0)
      TimeInterval.HOUR ->
        if (step == 1) millis - (at.minute * 60_000.0 + at.second * 1000.0 + subMillis(at))
        else atTime(at.date, snapDown(at.hour), 0, 0)
      // A stepped day snaps on the **day of the month minus one**, which is d3's `field` for days,
      // so `every(2)` lands on the 1st, 3rd, 5th and so on rather than wherever the domain began.
      // This ignored `step` altogether and let `range` anchor the grid to its own start, which put
      // a two-day axis on the even days of the month — every label wrong by a day.
      TimeInterval.DAY ->
        if (step == 1) millis(at.date.atStartOfDayIn(zone))
        else millis(at.date.minusDays(dayPhase(at.date)).atStartOfDayIn(zone))
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

  /**
   * [millis] advanced by [count] steps.
   *
   * Months and years **overflow** rather than clamping, which is d3's behaviour because it is
   * JavaScript's: `setMonth(month + 1)` on 31 January asks for "31 February" and the Date
   * normalises it to 2 or 3 March depending on the leap year. `kotlinx-datetime` clamps instead —
   * it would answer 29 February — so the two disagree by one to three days for any date past the
   * 28th, which is a quarter of the month. Tick generation never noticed: it only ever offsets from
   * a floored boundary, the first of a month, where nothing overflows. `timeOffset` in an
   * expression does.
   */
  public fun offset(millis: Double, count: Int): Double {
    val at = instant(millis)
    val amount = step.toLong() * count
    return when (interval) {
      TimeInterval.MILLISECOND -> millis(at.plus(amount, DateTimeUnit.MILLISECOND))
      TimeInterval.SECOND ->
        if (step == 1) millis(at.plus(amount, DateTimeUnit.SECOND))
        else steppedSubDay(millis, count, DateTimeUnit.SECOND) { it.second }
      TimeInterval.MINUTE ->
        if (step == 1) millis(at.plus(amount, DateTimeUnit.MINUTE))
        else steppedSubDay(millis, count, DateTimeUnit.MINUTE) { it.minute }
      TimeInterval.HOUR ->
        if (step == 1) millis(at.plus(amount, DateTimeUnit.HOUR))
        else steppedSubDay(millis, count, DateTimeUnit.HOUR) { it.hour }
      TimeInterval.DAY ->
        if (step == 1) millis(at.plus(amount, DateTimeUnit.DAY, zone))
        else steppedDays(millis, count)
      TimeInterval.WEEK -> millis(at.plus(amount, DateTimeUnit.WEEK, zone))
      TimeInterval.MONTH -> overflowing(millis, monthsFrom = amount, yearsFrom = 0L)
      TimeInterval.YEAR -> overflowing(millis, monthsFrom = 0L, yearsFrom = amount)
    }
  }

  /**
   * A month or year shift done the way `Date.setMonth` does it: keep the day number and let it
   * spill.
   *
   * Built by taking the first of the target month and adding `day - 1` days, which is exactly what
   * the overflow amounts to, and reattaching the original wall-clock time — a shift by months keeps
   * the local time of day across a daylight-saving change rather than the absolute instant.
   */
  private fun overflowing(millis: Double, monthsFrom: Long, yearsFrom: Long): Double {
    val at = local(millis)
    val zeroBased = (at.date.month.number - 1).toLong() + monthsFrom
    val year = at.date.year + yearsFrom + zeroBased.floorDiv(12)
    val month = zeroBased.mod(12L).toInt() + 1
    val first = LocalDate(year.toInt(), month, 1)
    val date = first.plus(at.date.day - 1, DateTimeUnit.DAY)
    return millis(LocalDateTime(date, at.time).toInstant(zone))
  }

  /**
   * The first boundary at or after [millis] — d3's `interval.ceil`.
   *
   * Written as d3 writes it, `floor(offset(floor(t - 1), 1))`, rather than as the obvious "floor,
   * and step once if that moved". The two agree for a plain interval and part company for a stepped
   * one, where the extra floor re-snaps to the step grid after the offset.
   */
  public fun ceil(millis: Double): Double = floor(offset(floor(millis - 1), 1))

  /** The nearer boundary, with a tie going upwards — d3's `interval.round`. */
  public fun round(millis: Double): Double {
    val down = floor(millis)
    val up = ceil(millis)
    return if (millis - down < up - millis) down else up
  }

  /**
   * How many boundaries lie in `[start, end)` — d3's `interval.count`.
   *
   * Both ends are floored first, so this counts *boundaries crossed* rather than elapsed time: from
   * 23:59 to 00:01 is one day, not none. The answer is floored, so a partial interval does not
   * count.
   *
   * The day and week arithmetic carries a **daylight-saving correction**, and it is not optional. A
   * local day is not always 86,400,000 milliseconds: the day a clock springs forward is an hour
   * short, so dividing elapsed milliseconds would report 30 days in a 31-day March. d3 corrects by
   * the change in UTC offset between the two ends, which is exactly the hour the clock skipped.
   */
  public fun count(start: Double, end: Double): Double {
    if (!start.isFinite() || !end.isFinite()) return Double.NaN
    val from = floor(start)
    val to = floor(end)
    val elapsed = to - from
    val shift = offsetMillis(to) - offsetMillis(from)
    val raw =
      when (interval) {
        TimeInterval.MILLISECOND -> elapsed
        TimeInterval.SECOND -> elapsed / 1_000.0
        TimeInterval.MINUTE -> elapsed / 60_000.0
        TimeInterval.HOUR -> elapsed / 3_600_000.0
        TimeInterval.DAY -> (elapsed + shift) / 86_400_000.0
        TimeInterval.WEEK -> (elapsed + shift) / 604_800_000.0
        TimeInterval.MONTH -> {
          val a = local(from)
          val b = local(to)
          (b.date.month.number - a.date.month.number + (b.date.year - a.date.year) * 12).toDouble()
        }
        TimeInterval.YEAR -> (local(to).date.year - local(from).date.year).toDouble()
      }
    return kotlin.math.floor(raw)
  }

  /**
   * The zone's offset at an instant, in milliseconds.
   *
   * d3 reads `getTimezoneOffset()`, which counts **minutes west** of UTC — the negative of this —
   * so its correction subtracts where this one adds.
   */
  private fun offsetMillis(millis: Double): Double =
    zone.offsetAt(instant(millis)).totalSeconds * 1000.0

  /**
   * Every step boundary in `[start, stop)`, starting from the first at or after [start].
   *
   * Stepping through the calendar rather than adding a fixed millisecond count is what keeps a
   * daily tick at midnight across a daylight-saving change.
   */
  public fun range(start: Double, stop: Double): List<Double> {
    if (!start.isFinite() || !stop.isFinite() || stop <= start) return emptyList()
    // d3 floors the step and gives up on anything that is not positive — `if (!(step > 0)) return
    // []`
    // — so a `{"interval": "day", "step": 0}` enumerates nothing rather than one boundary forever.
    // Found by replaying d3-time's own vectors, where a step of 0, of -1 and of null all expect [].
    if (step <= 0) return emptyList()
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

  /**
   * The next day that passes the step's test, walked one day at a time — d3's filtered offset.
   *
   * Adding `step` days would be wrong at the end of a month, and wrong in a way that shows: the
   * days a step of two selects are the 1st, 3rd … 31st, so after the 31st comes the **1st**, two
   * consecutive selected days. That is d3's own behaviour, quirk included, because the test is on
   * the day of the month and the month resets it. Sub-day steps do not need this — d3's tick table
   * only ever uses hour steps that divide 24 and minute steps that divide 60 — but a day step of
   * two divides no month, which is exactly why this one bites.
   */
  private fun steppedDays(millis: Double, count: Int): Double {
    if (count == 0) return millis
    val at = local(millis)
    val direction = if (count > 0) 1 else -1
    var date = at.date
    repeat(abs(count)) {
      do {
        date = date.plus(direction, DateTimeUnit.DAY)
      } while (dayPhase(date) != 0)
    }
    return millis(LocalDateTime(date, at.time).toInstant(zone))
  }

  /**
   * The next sub-day boundary that passes the step's test, walked one unit at a time.
   *
   * This comment used to say sub-day steps did not need d3's filtered offset, because "the tick
   * table only ever uses hour steps that divide 24 and minute steps that divide 60" — so adding
   * `step` units always lands back on the grid. That holds in **UTC**, where the grid repeats every
   * day, and fails in a zone with daylight saving: twelve absolute hours after local midnight is
   * local 13:00 on the day the clocks go forward, which is not a multiple of twelve, and every tick
   * after it is off the grid.
   *
   * d3 gets this right by construction — `interval.every(n)` is a *filter* on the local field, and
   * its `range` walks the base unit and keeps what passes — so a twelve-hour local axis steps
   * eleven real hours across the change. Which is what upstream does and this did not: the
   * `local-time-dst` fixture produced eight ticks where upstream produces nine, and nothing caught
   * it because a time scale's ticks were never compared.
   */
  private fun steppedSubDay(
    millis: Double,
    count: Int,
    unit: DateTimeUnit.TimeBased,
    field: (LocalDateTime) -> Int,
  ): Double {
    if (count == 0) return millis
    val direction = if (count > 0) 1L else -1L
    var at = instant(millis)
    repeat(abs(count)) {
      // Bounded: a step that divides its field's range finds the next boundary within `step` units,
      // and one that does not still wraps at the field's end. The cap is a hang guard, not a rule.
      var walked = 0
      do {
        at = at.plus(direction, unit)
        walked++
      } while (field(at.toLocalDateTime(zone)) % step != 0 && walked <= MAX_SUB_DAY_WALK)
    }
    return millis(at)
  }

  /** How far a date sits past the step's grid: zero exactly on a selected day. */
  private fun dayPhase(date: LocalDate): Int =
    if (epochDays) (date.toEpochDays().mod(step.toLong())).toInt() else (date.day - 1) % step

  private fun snapDown(value: Int) = value - value % step

  private fun atTime(date: LocalDate, hour: Int, minute: Int, second: Int): Double =
    millis(LocalDateTime(date, kotlinx.datetime.LocalTime(hour, minute, second)).toInstant(zone))

  private fun LocalDate.minusDays(days: Int): LocalDate =
    if (days == 0) this else this.plus(-days, DateTimeUnit.DAY)

  private companion object {
    /**
     * How far [steppedSubDay] will walk looking for the next boundary on the grid.
     *
     * Sixty covers every field: an hour step wraps within 24, a minute or second step within 60. A
     * hang guard rather than a rule — reaching it means the step divides nothing, and stopping with
     * a tick slightly off the grid beats an axis that never returns.
     */
    private const val MAX_SUB_DAY_WALK: Int = 60

    /** A ceiling on how many ticks one axis can produce, so a degenerate domain cannot hang. */
    const val MAX_TICKS = 10_000
  }
}
