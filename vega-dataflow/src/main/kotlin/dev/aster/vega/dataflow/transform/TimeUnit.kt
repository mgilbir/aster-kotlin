package dev.aster.vega.dataflow.transform

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field
import dev.aster.vega.model.time.DateValues
import dev.aster.vega.model.time.TimeFormat
import dev.aster.vega.model.time.TimeInterval
import dev.aster.vega.model.time.TimeStepper
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
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

  /** Units whose flooring needs week numbering, which this engine does not implement. */
  private val WEEK_BASED = setOf("week", "day", "dayofyear")

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

    val units = params.stringList("units")
    if (units.isEmpty()) {
      // Upstream infers units from the data extent and `maxbins`. Inferring differently would
      // bucket
      // the same data differently, so this asks rather than guessing.
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
        "timeunit needs explicit 'units'; inferring them from the data extent is not implemented",
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
    val weekBased = units.filter { it in WEEK_BASED }
    if (weekBased.isNotEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
        "timeunit unit(s) ${weekBased.joinToString(", ")} need week numbering, which is not " +
          "implemented; bucket by 'date' instead",
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
    val stepper = finestStepper(present, zone)
    val names = params.stringList("as")
    val startName = names.getOrNull(0) ?: "unit0"
    val endName = names.getOrNull(1) ?: "unit1"

    return input.map { datum ->
      val instant = (DateValues.parse(datum.field(fieldPath)) as? VegaValue.Num)?.value
      if (instant == null || !instant.isFinite()) {
        datum.withFields(mapOf(startName to VegaValue.Null, endName to VegaValue.Null))
      } else {
        val start = floor(instant, present, zone)
        datum.withFields(
          mapOf(
            startName to VegaValue.Num(start),
            endName to VegaValue.Num(stepper.offset(start, 1)),
          )
        )
      }
    }
  }

  /**
   * Rebuilds an instant from only the units the specification asked for.
   *
   * Everything absent falls back to its origin — month and day to the first, time to midnight, and
   * the year to [CYCLE_YEAR] — which is what turns a units list into a bucket.
   */
  private fun floor(instant: Double, units: Set<String>, zone: TimeZone): Double {
    val at = TimeFormat.at(instant, zone)
    val year = if ("year" in units) at.year else CYCLE_YEAR
    val month =
      when {
        "month" in units -> at.month.number
        // A quarter is the first month of the three it covers.
        "quarter" in units -> (at.month.number - 1) / 3 * 3 + 1
        else -> 1
      }
    val day = if ("date" in units) at.day else 1
    val hour = if ("hours" in units) at.hour else 0
    val minute = if ("minutes" in units) at.minute else 0
    val second = if ("seconds" in units) at.second else 0
    val nanos = if ("milliseconds" in units) at.nanosecond / 1_000_000 * 1_000_000 else 0

    // A day-of-month carried onto a different month can be out of range — 31 January bucketed by
    // {year, date} into a 30-day month. Clamping keeps a valid date rather than throwing.
    val date = LocalDate(year, month, day.coerceAtMost(daysInMonth(year, month)))
    return LocalDateTime(date, LocalTime(hour, minute, second, nanos))
      .toInstant(zone)
      .toEpochMilliseconds()
      .toDouble()
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
      "date" in units -> TimeStepper(TimeInterval.DAY, 1, zone)
      "month" in units -> TimeStepper(TimeInterval.MONTH, 1, zone)
      "quarter" in units -> TimeStepper(TimeInterval.MONTH, 3, zone)
      else -> TimeStepper(TimeInterval.YEAR, 1, zone)
    }
}
