package dev.aster.vega.dataflow.transform

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field
import dev.aster.vega.model.time.DateValues
import dev.aster.vega.model.time.TimeFormat
import dev.aster.vega.model.time.TimeInterval
import dev.aster.vega.model.time.TimeStepper
import kotlin.math.pow
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
 * `timeunit`: buckets each row into the calendar period containing its date.
 *
 * Each row gains the start of its bucket and the start of the next — `unit0` and `unit1` unless
 * `as` renames them — which is what lets a rect span the period rather than sit at a point. It is
 * the date equivalent of `bin`, and it exists because grouping by a formatted string would sort
 * "April" before "January".
 *
 * The floor is **built** rather than rounded: upstream takes each listed unit from the date and
 * defaults every unit that is absent — month and day to their first, time to midnight, and the year
 * to
 * 2012. That last default is not a quirk to work around, it is the feature: `units: ["month"]` with
 *       no year deliberately collapses every January in the data onto one bucket, which is how a
 *       specification asks for a seasonal profile rather than a timeline.
 */
public object TimeUnitTransform : Transform {
  override val type: String = "timeunit"

  /** `{unit, units, step, start, stop}` — what a chart reads to label and size its buckets. */
  override val publishesSignal: Boolean = true

  /** Upstream's reference year for a bucketing that does not include one. */
  private const val CYCLE_YEAR = 2012

  private val KNOWN =
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

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val fieldPath = (params.fields["field"] as? VegaValue.Str)?.value
    if (fieldPath.isNullOrEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "timeunit needs a 'field'",
        operator = type,
      )
      return input
    }

    val declared = params.stringList("units")
    val inferred =
      if (declared.isNotEmpty()) null
      else {
        val instants = input.mapNotNull {
          (DateValues.parse(it.field(fieldPath)) as? VegaValue.Num)?.value?.takeIf { v ->
            v.isFinite()
          }
        }
        val stated = params.numberList("extent")
        val extent =
          if (stated.size >= 2) stated[0] to stated[1]
          else if (instants.isEmpty()) null else instants.min() to instants.max()
        extent?.let { timeBin(it.first, it.second, params.number("maxbins")?.toInt() ?: 40) }
      }
    val units = declared.ifEmpty { inferred?.first ?: emptyList() }
    if (units.isEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "timeunit has no 'units' and no dated rows to infer them from",
        operator = type,
      )
      return input
    }

    val unknown = units.filter { it !in KNOWN }
    if (unknown.isNotEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "timeunit does not know the unit(s) ${unknown.joinToString(", ")}",
        operator = type,
      )
      return input
    }
    // Upstream's own validity rule: a bucket cannot be both a week and a month, or either and a
    // day-of-year, because they slice the year three incompatible ways.
    val families =
      listOf(
        units.any { it == "week" || it == "day" },
        units.any { it == "quarter" || it == "month" || it == "date" },
        units.contains("dayofyear"),
      )
    if (families.count { it } > 1) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "timeunit units ${units.joinToString(", ")} slice the year in incompatible ways",
        operator = type,
      )
      return input
    }

    val zone =
      when ((params.fields["timezone"] as? VegaValue.Str)?.value?.lowercase()) {
        null,
        "local" -> TimeZone.currentSystemDefault()
        "utc" -> TimeZone.UTC
        else -> {
          context.diagnostics.warn(
            DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
            "timeunit knows only 'local' and 'utc' timezones; using local",
            operator = type,
          )
          TimeZone.currentSystemDefault()
        }
      }

    val present = units.toSet()
    // A declared `step` applies to whatever units were declared; an inferred one comes with the
    // units it was chosen for, and upstream does not let the two mix — `inferUnits` overrides a
    // step outright.
    val step = (inferred?.second ?: params.number("step")?.toInt() ?: 1).coerceAtLeast(1)
    val stepper = finestStepper(present, zone)
    val names = params.stringList("as")
    val startName = names.getOrNull(0) ?: "unit0"
    val endName = names.getOrNull(1) ?: "unit1"

    var lowest = Double.POSITIVE_INFINITY
    var highest = Double.NEGATIVE_INFINITY
    val bucketed = input.map { datum ->
      val instant = (DateValues.parse(datum.field(fieldPath)) as? VegaValue.Num)?.value
      if (instant == null || !instant.isFinite()) {
        datum.withFields(mapOf(startName to VegaValue.Null, endName to VegaValue.Null))
      } else {
        val start = floor(instant, present, zone, step)
        val end = stepper.offset(start, step)
        if (start < lowest) lowest = start
        if (end > highest) highest = end
        datum.withFields(mapOf(startName to VegaValue.Num(start), endName to VegaValue.Num(end)))
      }
    }

    // What upstream publishes: the units it was given, the finest of them, the step, and the span
    // the buckets actually cover. A chart reads `tbin.unit` to pick a label format and
    // `tbin.start`/`tbin.stop` to size the axis it draws them on.
    params.string("signal")?.let { signal ->
      context.setSignal(
        signal,
        VegaValue.Obj(
          linkedMapOf(
            "unit" to VegaValue.Str(finestOf(present)),
            "units" to VegaValue.Arr(units.map { VegaValue.Str(it) }),
            "step" to VegaValue.Num(step.toDouble()),
            "start" to VegaValue.Num(if (lowest.isFinite()) lowest else Double.NaN),
            "stop" to VegaValue.Num(if (highest.isFinite()) highest else Double.NaN),
          )
        ),
      )
    }
    return bucketed
  }

  /**
   * `timeBin`: the units and step a span of time falls into, when the specification names none.
   *
   * A table of seventeen intervals from a second to a year, chosen by which one's duration is
   * nearest the span divided by `maxbins` — *nearest in ratio*, not in difference, which is why the
   * choice between two neighbouring intervals compares `target / lower` against `upper / target`.
   * Off the ends of the table the step comes from d3's own tick step instead: years above it,
   * milliseconds below.
   *
   * Ported rather than approximated because the buckets are the data: a chart that inferred hours
   * where upstream inferred six-hour blocks is a different chart, not a rounder one.
   */
  private fun timeBin(low: Double, high: Double, maxbins: Int): Pair<List<String>, Int> {
    val target = kotlin.math.abs(high - low) / maxbins.coerceAtLeast(1)
    val index =
      BIN_INTERVALS.indexOfFirst { it.third > target }
        .let {
          if (it < 0) BIN_INTERVALS.size else it
        }
    return when {
      index == BIN_INTERVALS.size ->
        listOf("year") to
          maxOf(1, tickStep(low / DURATION_YEAR, high / DURATION_YEAR, maxbins).toInt())
      index > 0 -> {
        val lower = BIN_INTERVALS[index - 1]
        val upper = BIN_INTERVALS[index]
        val chosen = if (target / lower.third < upper.third / target) lower else upper
        chosen.first to chosen.second
      }
      else -> MILLI_UNITS to maxOf(1, tickStep(low, high, maxbins).toInt())
    }
  }

  /**
   * d3's `tickStep`, transcribed, because this module cannot reach the scale package.
   *
   * The three thresholds are `sqrt(50)`, `sqrt(10)` and `sqrt(2)`: the geometric midpoints between
   * 1, 2, 5 and 10, so a step is rounded to whichever of those it is nearest *in ratio*.
   */
  private fun tickStep(start: Double, stop: Double, count: Int): Double {
    val step0 = kotlin.math.abs(stop - start) / maxOf(1, count)
    if (!step0.isFinite() || step0 == 0.0) return 1.0
    var step1 = 10.0.pow(kotlin.math.floor(kotlin.math.log10(step0)))
    val error = step0 / step1
    if (error >= kotlin.math.sqrt(50.0)) step1 *= 10.0
    else if (error >= kotlin.math.sqrt(10.0)) step1 *= 5.0
    else if (error >= kotlin.math.sqrt(2.0)) step1 *= 2.0
    return step1
  }

  private const val DURATION_SECOND = 1000.0
  private const val DURATION_MINUTE = DURATION_SECOND * 60
  private const val DURATION_HOUR = DURATION_MINUTE * 60
  private const val DURATION_DAY = DURATION_HOUR * 24
  private const val DURATION_WEEK = DURATION_DAY * 7
  private const val DURATION_MONTH = DURATION_DAY * 30
  private const val DURATION_YEAR = DURATION_DAY * 365

  private val MILLI_UNITS =
    listOf("year", "month", "date", "hours", "minutes", "seconds", "milliseconds")
  private val SECOND_UNITS = MILLI_UNITS.dropLast(1)
  private val MINUTE_UNITS = SECOND_UNITS.dropLast(1)
  private val HOUR_UNITS = MINUTE_UNITS.dropLast(1)
  private val DAY_UNITS = HOUR_UNITS.dropLast(1)

  /** Upstream's table: the units, the step, and how long one bucket lasts. */
  private val BIN_INTERVALS: List<Triple<List<String>, Int, Double>> =
    listOf(
      Triple(SECOND_UNITS, 1, DURATION_SECOND),
      Triple(SECOND_UNITS, 5, 5 * DURATION_SECOND),
      Triple(SECOND_UNITS, 15, 15 * DURATION_SECOND),
      Triple(SECOND_UNITS, 30, 30 * DURATION_SECOND),
      Triple(MINUTE_UNITS, 1, DURATION_MINUTE),
      Triple(MINUTE_UNITS, 5, 5 * DURATION_MINUTE),
      Triple(MINUTE_UNITS, 15, 15 * DURATION_MINUTE),
      Triple(MINUTE_UNITS, 30, 30 * DURATION_MINUTE),
      Triple(HOUR_UNITS, 1, DURATION_HOUR),
      Triple(HOUR_UNITS, 3, 3 * DURATION_HOUR),
      Triple(HOUR_UNITS, 6, 6 * DURATION_HOUR),
      Triple(HOUR_UNITS, 12, 12 * DURATION_HOUR),
      Triple(DAY_UNITS, 1, DURATION_DAY),
      Triple(listOf("year", "week"), 1, DURATION_WEEK),
      Triple(listOf("year", "month"), 1, DURATION_MONTH),
      Triple(listOf("year", "month"), 3, 3 * DURATION_MONTH),
      Triple(listOf("year"), 1, DURATION_YEAR),
    )

  /** The finest unit named, which is the one a bucket is a bucket *of*. */
  private fun finestOf(units: Set<String>): String = KNOWN.last { it in units }

  /**
   * Rebuilds an instant from only the units the specification asked for.
   *
   * Everything absent falls back to its origin — month and day to the first, time to midnight, and
   * the year to [CYCLE_YEAR] — which is what turns a units list into a bucket.
   */
  /**
   * Applies `step` to the **finest** unit only, which is upstream's `getUnit`.
   *
   * `phase` is 1 for the units counted from one rather than from zero — a month, a date, a day of
   * the year — because `3 * floor(month / 3)` would put January in a bucket starting at month zero,
   * which is December of the year before. Only the finest unit is stepped: a `{year, month}` bucket
   * at step 3 is a quarter, not three years of quarters.
   */
  private fun stepped(value: Int, step: Int, phase: Int): Int {
    if (step <= 1) return value
    return phase + step * kotlin.math.floor((value - phase).toDouble() / step).toInt()
  }

  private fun floor(instant: Double, units: Set<String>, zone: TimeZone, step: Int = 1): Double {
    val at = TimeFormat.at(instant, zone)
    val finest = KNOWN.last { it in units }
    fun stepOf(unit: String) = if (unit == finest) step else 1
    val year = if ("year" in units) stepped(at.year, stepOf("year"), phase = 0) else CYCLE_YEAR
    val month =
      when {
        "month" in units -> stepped(at.month.number, stepOf("month"), phase = 1)
        // A quarter is the first month of the three it covers.
        "quarter" in units -> (at.month.number - 1) / 3 * 3 + 1
        else -> 1
      }
    // A week-based unit gives a **day of the year** rather than a day of the month, and the date is
    // rebuilt from January the 1st plus that many days — which is upstream's own arrangement, since
    // its `localDate(y, m, d, ...)` lets the day overflow the month and JavaScript normalises it.
    //
    // `weekday(week, day, firstDay) = day + week * 7 - (firstDay + 6) % 7` is the whole of it. With
    // `day` alone the week is 1, so a Monday in a year beginning on a Sunday lands on the 2nd; with
    // `week` alone the day is 0, so every date in a week lands on that week's first day.
    val firstDay = LocalDate(year, 1, 1).dayOfWeek.isoDayNumber % 7
    val dayOfYear =
      when {
        "week" in units && "day" in units ->
          weekday(weekNumber(at, zone), at.dayOfWeek.isoDayNumber % 7, firstDay)
        "week" in units -> weekday(weekNumber(at, zone), 0, firstDay)
        "day" in units -> weekday(1, at.dayOfWeek.isoDayNumber % 7, firstDay)
        "dayofyear" in units -> at.dayOfYear
        else -> null
      }
    if (dayOfYear != null) {
      val base = LocalDate(year, 1, 1).plus(dayOfYear - 1, DateTimeUnit.DAY)
      return LocalDateTime(
          base,
          LocalTime(
            if ("hours" in units) at.hour else 0,
            if ("minutes" in units) at.minute else 0,
            if ("seconds" in units) at.second else 0,
            if ("milliseconds" in units) at.nanosecond / 1_000_000 * 1_000_000 else 0,
          ),
        )
        .toInstant(zone)
        .toEpochMilliseconds()
        .toDouble()
    }
    val day = if ("date" in units) stepped(at.day, stepOf("date"), phase = 1) else 1
    val hour = if ("hours" in units) stepped(at.hour, stepOf("hours"), phase = 0) else 0
    val minute = if ("minutes" in units) stepped(at.minute, stepOf("minutes"), phase = 0) else 0
    val second = if ("seconds" in units) stepped(at.second, stepOf("seconds"), phase = 0) else 0
    val nanos =
      if ("milliseconds" in units) {
        stepped(at.nanosecond / 1_000_000, stepOf("milliseconds"), phase = 0) * 1_000_000
      } else {
        0
      }

    // A day-of-month carried onto a different month can be out of range — 31 January bucketed by
    // {year, date} into a 30-day month. Clamping keeps a valid date rather than throwing.
    val date = LocalDate(year, month, day.coerceAtMost(daysInMonth(year, month)))
    return LocalDateTime(date, LocalTime(hour, minute, second, nanos))
      .toInstant(zone)
      .toEpochMilliseconds()
      .toDouble()
  }

  /** Upstream's own helper, transcribed: which day of the year a week-and-weekday pair names. */
  private fun weekday(week: Int, day: Int, firstDay: Int): Int = day + week * 7 - (firstDay + 6) % 7

  /**
   * How many Sundays have passed since the 1st of January, counting one on the day itself.
   *
   * d3's `timeSunday.count(startOfYear, date)`, which is what upstream's week number is.
   */
  private fun weekNumber(at: LocalDateTime, zone: TimeZone): Int {
    val start = LocalDate(at.year, 1, 1)
    val firstSundayOffset = (7 - start.dayOfWeek.isoDayNumber % 7) % 7
    val dayOfYear = at.dayOfYear - 1
    return if (dayOfYear < firstSundayOffset) 0 else (dayOfYear - firstSundayOffset) / 7 + 1
  }

  private fun daysInMonth(year: Int, month: Int): Int =
    when (month) {
      1,
      3,
      5,
      7,
      8,
      10,
      12 -> 31
      4,
      6,
      9,
      11 -> 30
      else -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
    }

  /** The step from one bucket to the next: the finest unit listed. */
  private fun finestStepper(units: Set<String>, zone: TimeZone): TimeStepper =
    when {
      "milliseconds" in units -> TimeStepper(TimeInterval.MILLISECOND, 1, zone)
      "seconds" in units -> TimeStepper(TimeInterval.SECOND, 1, zone)
      "minutes" in units -> TimeStepper(TimeInterval.MINUTE, 1, zone)
      "hours" in units -> TimeStepper(TimeInterval.HOUR, 1, zone)
      // A day-of-week or day-of-year bucket is one day wide, the same as a day-of-month; a week
      // bucket is seven. Without these the finest unit fell through to a year, and a chart
      // bucketing
      // by weekday drew seven buckets a year apart.
      "date" in units -> TimeStepper(TimeInterval.DAY, 1, zone)
      "day" in units -> TimeStepper(TimeInterval.DAY, 1, zone)
      "dayofyear" in units -> TimeStepper(TimeInterval.DAY, 1, zone)
      "week" in units -> TimeStepper(TimeInterval.WEEK, 1, zone)
      "month" in units -> TimeStepper(TimeInterval.MONTH, 1, zone)
      "quarter" in units -> TimeStepper(TimeInterval.MONTH, 3, zone)
      else -> TimeStepper(TimeInterval.YEAR, 1, zone)
    }
}
