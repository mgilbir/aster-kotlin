package dev.aster.vega.expression

import dev.aster.vega.model.PlatformDecimals
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.roundHalfUp
import dev.aster.vega.model.time.DateValues
import dev.aster.vega.model.time.TimeFormat
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
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

/** A callable expression function. Arguments arrive already evaluated. */
public fun interface ExpressionFunction {
  public fun invoke(arguments: List<VegaValue>): VegaValue
}

/**
 * Vega's standard expression functions.
 *
 * Upstream exposes 119; this implements the subset the runtime can honour, listed in
 * SUPPORTED_FEATURES.md. Calling anything else is an error the evaluator reports — never a silent
 * `null`, which would turn a missing function into a mysteriously blank chart.
 *
 * Semantics follow upstream exactly where they differ from the obvious reading, and each such case
 * is pinned by a reference vector in `FunctionsTest`:
 * - `round` rounds halves toward positive infinity, so `round(-2.5)` is `-2`
 * - `toBoolean("")` is `null`, not `false`
 * - `indexof` works on both strings and arrays
 * - `isValid` means "not null and not NaN", which is narrower than truthiness
 *
 * Deliberately excluded: `random` and the other stochastic functions, because a scene must be
 * reproducible (PROJECT_BRIEF.md 18.2); date and time functions, because time scales are not
 * implemented; and the selection and geo helpers, which belong to subsystems that do not exist yet.
 */
public object Functions {

  /** Named constants Vega exposes alongside the functions. */
  public val constants: Map<String, VegaValue> =
    mapOf(
      "PI" to VegaValue.Num(kotlin.math.PI),
      "E" to VegaValue.Num(kotlin.math.E),
      "LN2" to VegaValue.Num(ln(2.0)),
      "LN10" to VegaValue.Num(ln(10.0)),
      "LOG2E" to VegaValue.Num(1.0 / ln(2.0)),
      "LOG10E" to VegaValue.Num(1.0 / ln(10.0)),
      "SQRT1_2" to VegaValue.Num(sqrt(0.5)),
      "SQRT2" to VegaValue.Num(sqrt(2.0)),
      "MAX_VALUE" to VegaValue.Num(Double.MAX_VALUE),
      "MIN_VALUE" to VegaValue.Num(Double.MIN_VALUE),
      "NaN" to VegaValue.Num(Double.NaN),
      "Infinity" to VegaValue.Num(Double.POSITIVE_INFINITY),
    )

  /**
   * Functions upstream provides that this engine deliberately does not.
   *
   * Listed by name with a reason so the evaluator can say *why* rather than just "unknown
   * function".
   */
  public val knownUnsupported: Map<String, String> =
    mapOf(
      "random" to "produces a non-reproducible scene",
      "sampleNormal" to "produces a non-reproducible scene",
      "sampleLogNormal" to "produces a non-reproducible scene",
      "sampleUniform" to "produces a non-reproducible scene",
      "now" to "produces a non-reproducible scene",
      "timeParse" to
        "parsing a date against a format string needs a strptime the engine does not have; " +
          "an ISO 8601 string works through toDate",
      "utcParse" to
        "parsing a date against a format string needs a strptime the engine does not have; " +
          "an ISO 8601 string works through toDate",
      "scale" to "requires the scale registry, which the evaluator has no access to yet",
      "invert" to "requires the scale registry, which the evaluator has no access to yet",
      "gradient" to "gradients cannot be produced from an expression yet",
      "rgb" to "colour helpers are not implemented",
      "hsl" to "colour helpers are not implemented",
      "lab" to "colour helpers are not implemented",
      "hcl" to "colour helpers are not implemented",
      "geoArea" to "geographic functions are out of scope for the first release",
      "geoBounds" to "geographic functions are out of scope for the first release",
      "geoCentroid" to "geographic functions are out of scope for the first release",
      "vlSelectionTest" to "selection helpers require the signal and selection subsystems",
      "vlSelectionResolve" to "selection helpers require the signal and selection subsystems",
    )

  public val functions: Map<String, ExpressionFunction> = buildFunctions()

  private fun buildFunctions(): Map<String, ExpressionFunction> {
    val map = LinkedHashMap<String, ExpressionFunction>()

    // ---- math ---------------------------------------------------------------
    map.unary("abs") { abs(it) }
    map.unary("acos") { acos(it) }
    map.unary("asin") { asin(it) }
    map.unary("atan") { atan(it) }
    map.unary("ceil") { ceil(it) }
    map.unary("cos") { cos(it) }
    map.unary("exp") { exp(it) }
    map.unary("floor") { floor(it) }
    map.unary("log") { ln(it) }
    map.unary("sin") { sin(it) }
    map.unary("sqrt") { sqrt(it) }
    map.unary("tan") { tan(it) }

    map["atan2"] = ExpressionFunction { args ->
      VegaValue.Num(atan2(args.number(0), args.number(1)))
    }
    map["pow"] = ExpressionFunction { args -> VegaValue.Num(args.number(0).pow(args.number(1))) }

    // JavaScript's Math.round rounds halves toward +Infinity: round(-2.5) === -2, not -3.
    map.unary("round") { value ->
      if (value.isNaN() || value.isInfinite()) value else floor(value + 0.5)
    }

    // Vega's min and max are JavaScript's Math.min/Math.max: a variadic spread, not an array
    // reducer.
    // `min([5,2,9])` is NaN upstream, not 2 — verified, and easy to get wrong in the helpful
    // direction.
    map["min"] = ExpressionFunction { args -> extreme(args, takeSmaller = true) }
    map["max"] = ExpressionFunction { args -> extreme(args, takeSmaller = false) }

    map["clamp"] = ExpressionFunction { args ->
      val value = args.number(0)
      val low = args.number(1)
      val high = args.number(2)
      VegaValue.Num(
        if (value.isNaN()) Double.NaN else value.coerceIn(minOf(low, high), maxOf(low, high))
      )
    }

    // ---- type predicates ----------------------------------------------------
    map.predicate("isArray") { it is VegaValue.Arr }
    map.predicate("isBoolean") { it is VegaValue.Bool }
    map.predicate("isNumber") { it is VegaValue.Num || it is VegaValue.Timestamp }
    map.predicate("isObject") { it is VegaValue.Obj }
    map.predicate("isString") { it is VegaValue.Str }
    map.predicate("isDefined") { it !is VegaValue.Null }
    // `isValid` is narrower than truthiness: it rejects null and NaN but accepts 0 and "".
    map.predicate("isValid") {
      it !is VegaValue.Null && !(it is VegaValue.Num && it.value.isNaN())
    }

    // ---- coercion -----------------------------------------------------------
    map["toNumber"] = ExpressionFunction { args ->
      val value = args.at(0)
      if (value is VegaValue.Null) VegaValue.Null else VegaValue.Num(JsSemantics.toNumber(value))
    }
    map["toString"] = ExpressionFunction { args ->
      val value = args.at(0)
      if (value is VegaValue.Null) VegaValue.Null
      else VegaValue.Str(JsSemantics.toStringValue(value))
    }
    // Upstream returns null rather than false for null and the empty string.
    map["toBoolean"] = ExpressionFunction { args ->
      val value = args.at(0)
      val empty = value is VegaValue.Null || (value is VegaValue.Str && value.value.isEmpty())
      if (empty) VegaValue.Null else VegaValue.Bool(JsSemantics.truthy(value))
    }
    map["parseFloat"] = ExpressionFunction { args ->
      VegaValue.Num(parseLeadingNumber(JsSemantics.toStringValue(args.at(0)), allowDecimal = true))
    }
    map["parseInt"] = ExpressionFunction { args ->
      val radix = if (args.size > 1) args.number(1).toInt() else 10
      VegaValue.Num(parseInteger(JsSemantics.toStringValue(args.at(0)), radix))
    }

    // ---- strings ------------------------------------------------------------
    map["upper"] = ExpressionFunction { args -> VegaValue.Str(args.string(0).uppercase()) }
    map["lower"] = ExpressionFunction { args -> VegaValue.Str(args.string(0).lowercase()) }
    map["trim"] = ExpressionFunction { args -> VegaValue.Str(args.string(0).trim()) }
    map["substring"] = ExpressionFunction { args ->
      val text = args.string(0)
      val from = if (args.size > 1) clampIndex(args.number(1), text.length) else 0
      val to = if (args.size > 2) clampIndex(args.number(2), text.length) else text.length
      VegaValue.Str(text.substring(minOf(from, to), maxOf(from, to)))
    }
    map["replace"] = ExpressionFunction { args ->
      // Vega's `replace` takes a string pattern and replaces the first occurrence only.
      VegaValue.Str(args.string(0).replaceFirst(args.string(1), args.string(2)))
    }
    map["split"] = ExpressionFunction { args ->
      val parts = args.string(0).split(args.string(1))
      val limit = if (args.size > 2) args.number(2).toInt() else parts.size
      VegaValue.Arr(parts.take(limit.coerceAtLeast(0)).map { VegaValue.Str(it) })
    }
    map["truncate"] = ExpressionFunction { args ->
      val text = args.string(0)
      val limit = args.number(1).toInt()
      val position = if (args.size > 2) args.string(2) else "right"
      val ellipsis = if (args.size > 3) args.string(3) else "…"
      VegaValue.Str(truncateText(text, limit, position, ellipsis))
    }
    map["pad"] = ExpressionFunction { args ->
      val text = args.string(0)
      val length = args.number(1).toInt()
      val character = if (args.size > 2) args.string(2) else " "
      val align = if (args.size > 3) args.string(3) else "right"
      VegaValue.Str(padText(text, length, character, align))
    }
    map["format"] = ExpressionFunction { args ->
      VegaValue.Str(NumberFormatSubset.format(args.number(0), args.string(1)))
    }

    // ---- strings and arrays -------------------------------------------------
    map["length"] = ExpressionFunction { args ->
      when (val value = args.at(0)) {
        is VegaValue.Arr -> VegaValue.Num(value.values.size.toDouble())
        is VegaValue.Str -> VegaValue.Num(value.value.length.toDouble())
        else -> VegaValue.Null
      }
    }
    map["indexof"] = ExpressionFunction { args ->
      when (val value = args.at(0)) {
        is VegaValue.Arr ->
          VegaValue.Num(
            value.values.indexOfFirst { JsSemantics.strictEquals(it, args.at(1)) }.toDouble()
          )
        is VegaValue.Str -> VegaValue.Num(value.value.indexOf(args.string(1)).toDouble())
        else -> VegaValue.Num(-1.0)
      }
    }
    map["lastindexof"] = ExpressionFunction { args ->
      when (val value = args.at(0)) {
        is VegaValue.Arr ->
          VegaValue.Num(
            value.values.indexOfLast { JsSemantics.strictEquals(it, args.at(1)) }.toDouble()
          )
        is VegaValue.Str -> VegaValue.Num(value.value.lastIndexOf(args.string(1)).toDouble())
        else -> VegaValue.Num(-1.0)
      }
    }
    map["slice"] = ExpressionFunction { args ->
      when (val value = args.at(0)) {
        is VegaValue.Arr -> {
          val size = value.values.size
          val from = if (args.size > 1) relativeIndex(args.number(1), size) else 0
          val to = if (args.size > 2) relativeIndex(args.number(2), size) else size
          VegaValue.Arr(if (from >= to) emptyList() else value.values.subList(from, to).toList())
        }
        is VegaValue.Str -> {
          val text = value.value
          val from = if (args.size > 1) relativeIndex(args.number(1), text.length) else 0
          val to = if (args.size > 2) relativeIndex(args.number(2), text.length) else text.length
          VegaValue.Str(if (from >= to) "" else text.substring(from, to))
        }
        else -> VegaValue.Null
      }
    }
    map["join"] = ExpressionFunction { args ->
      val array = args.at(0) as? VegaValue.Arr ?: return@ExpressionFunction VegaValue.Null
      val separator = if (args.size > 1) args.string(1) else ","
      VegaValue.Str(
        array.values.joinToString(separator) {
          if (it is VegaValue.Null) "" else JsSemantics.toStringValue(it)
        }
      )
    }
    map["reverse"] = ExpressionFunction { args ->
      val array = args.at(0) as? VegaValue.Arr ?: return@ExpressionFunction VegaValue.Null
      VegaValue.Arr(array.values.reversed())
    }
    map["peek"] = ExpressionFunction { args ->
      val array = args.at(0) as? VegaValue.Arr ?: return@ExpressionFunction VegaValue.Null
      array.values.lastOrNull() ?: VegaValue.Null
    }
    map["sort"] = ExpressionFunction { args ->
      val array = args.at(0) as? VegaValue.Arr ?: return@ExpressionFunction VegaValue.Null
      // Upstream sorts in natural ascending order; a comparator argument is not supported.
      VegaValue.Arr(array.values.sortedWith(NATURAL_ORDER))
    }

    // ---- ranges -------------------------------------------------------------
    map["span"] = ExpressionFunction { args ->
      val array =
        args.at(0) as? VegaValue.Arr ?: return@ExpressionFunction VegaValue.Num(Double.NaN)
      if (array.values.isEmpty()) return@ExpressionFunction VegaValue.Num(Double.NaN)
      VegaValue.Num(
        JsSemantics.toNumber(array.values.last()) - JsSemantics.toNumber(array.values.first())
      )
    }
    map["inrange"] = ExpressionFunction { args ->
      val value = args.number(0)
      val range = args.at(1) as? VegaValue.Arr ?: return@ExpressionFunction VegaValue.Bool(false)
      if (range.values.size < 2) return@ExpressionFunction VegaValue.Bool(false)
      val a = JsSemantics.toNumber(range.values.first())
      val b = JsSemantics.toNumber(range.values.last())
      VegaValue.Bool(value >= minOf(a, b) && value <= maxOf(a, b))
    }
    map["clampRange"] = ExpressionFunction { args ->
      val range = args.at(0) as? VegaValue.Arr ?: return@ExpressionFunction VegaValue.Null
      if (range.values.size < 2) return@ExpressionFunction args.at(0)
      val min = args.number(1)
      val max = args.number(2)
      var lo = JsSemantics.toNumber(range.values.first())
      var hi = JsSemantics.toNumber(range.values.last())
      val span = hi - lo
      if (span > max - min) {
        lo = min
        hi = max
      } else {
        // Slide the window into range rather than clipping it, preserving its width.
        if (lo < min) {
          lo = min
          hi = min + span
        }
        if (hi > max) {
          hi = max
          lo = max - span
        }
      }
      VegaValue.Arr(listOf(VegaValue.Num(lo), VegaValue.Num(hi)))
    }

    // ---- dates --------------------------------------------------------------
    //
    // A date is epoch milliseconds here, where upstream has a `Date` object. Arithmetic, comparison
    // and every accessor below behave the same on both, and it saves the value model a type it
    // would
    // otherwise carry everywhere. What it does cost is `typeof`: `isDate` cannot tell a date from a
    // number, and reports rather than guessing.
    map["datetime"] = ExpressionFunction { args -> VegaValue.Num(construct(args, localZone())) }
    map["utc"] = ExpressionFunction { args -> VegaValue.Num(construct(args, TimeZone.UTC)) }
    map["toDate"] = ExpressionFunction { args -> DateValues.parse(args.at(0)) ?: VegaValue.Null }
    map["time"] = ExpressionFunction { args -> VegaValue.Num(instantOf(args.at(0))) }

    // Month and day-of-week are zero-based, as JavaScript's are; quarter and day-of-year are not.
    dateField(map, "year") { it.year.toDouble() }
    dateField(map, "quarter") { ((it.month.number - 1) / 3 + 1).toDouble() }
    dateField(map, "month") { (it.month.number - 1).toDouble() }
    dateField(map, "date") { it.day.toDouble() }
    dateField(map, "day") { (it.date.dayOfWeek.isoDayNumber % 7).toDouble() }
    dateField(map, "dayofyear") { it.date.dayOfYear.toDouble() }
    dateField(map, "hours") { it.hour.toDouble() }
    dateField(map, "minutes") { it.minute.toDouble() }
    dateField(map, "seconds") { it.second.toDouble() }
    dateField(map, "milliseconds") { (it.nanosecond / 1_000_000).toDouble() }

    map["timeFormat"] = ExpressionFunction { args -> formatted(args, localZone()) }
    map["utcFormat"] = ExpressionFunction { args -> formatted(args, TimeZone.UTC) }

    map["timezoneoffset"] = ExpressionFunction { args ->
      // JavaScript reports the offset as minutes *behind* UTC, so a zone east of Greenwich is
      // negative. Reproducing the sign matters more than it looks: specifications subtract it.
      val instant = instantOf(args.at(0))
      if (instant.isNaN()) VegaValue.Num(Double.NaN)
      else {
        val seconds =
          localZone().offsetAt(Instant.fromEpochMilliseconds(instant.toLong())).totalSeconds
        VegaValue.Num(-seconds / 60.0)
      }
    }

    // ---- control flow -------------------------------------------------------
    map["if"] = ExpressionFunction { args ->
      if (JsSemantics.truthy(args.at(0))) args.at(1) else args.at(2)
    }

    return map
  }

  /**
   * Registers a date accessor and its `utc` twin.
   *
   * The pair is the whole reason `utc` exists in the language: `month` reads the calendar where the
   * reader is, `utcmonth` reads it where the data was recorded, and a chart that mixes them is
   * wrong in a way nothing complains about.
   */
  private fun dateField(
    map: MutableMap<String, ExpressionFunction>,
    name: String,
    read: (LocalDateTime) -> Double,
  ) {
    map[name] = ExpressionFunction { args -> fieldOf(args.at(0), localZone(), read) }
    map["utc$name"] = ExpressionFunction { args -> fieldOf(args.at(0), TimeZone.UTC, read) }
  }

  private fun fieldOf(
    value: VegaValue,
    zone: TimeZone,
    read: (LocalDateTime) -> Double,
  ): VegaValue {
    val instant = instantOf(value)
    if (instant.isNaN()) return VegaValue.Num(Double.NaN)
    return VegaValue.Num(read(TimeFormat.at(instant, zone)))
  }

  private fun formatted(args: List<VegaValue>, zone: TimeZone): VegaValue {
    val instant = instantOf(args.at(0))
    if (instant.isNaN()) return VegaValue.Str("Invalid Date")
    return VegaValue.Str(TimeFormat.format(instant, args.string(1), zone))
  }

  /** Epoch milliseconds for a value that is already an instant, or that reads as an ISO date. */
  private fun instantOf(value: VegaValue): Double =
    (DateValues.parse(value) as? VegaValue.Num)?.value ?: JsSemantics.toNumber(value)

  /**
   * `datetime(year, month, ...)`, where every component past the year is optional and out-of-range
   * values roll over — `datetime(2026, 12, 1)` is January 2027, as in JavaScript.
   *
   * Building from the first of January and adding is what gives the rollover for free; constructing
   * the date directly would have to reject month 12 instead.
   */
  private fun construct(args: List<VegaValue>, zone: TimeZone): Double {
    val year = args.number(0)
    if (year.isNaN()) return Double.NaN
    val start = LocalDate(year.toInt(), 1, 1).atStartOfDayIn(zone)
    val months = args.numberOr(1, 0.0)
    val days = args.numberOr(2, 1.0) - 1.0
    val hours = args.numberOr(3, 0.0)
    val minutes = args.numberOr(4, 0.0)
    val seconds = args.numberOr(5, 0.0)
    val millis = args.numberOr(6, 0.0)
    if (months.isNaN() || days.isNaN()) return Double.NaN

    // Months and days step through the calendar; the rest is a fixed duration, which is what makes
    // a
    // daylight-saving day 23 hours long rather than silently 24.
    val shifted =
      start
        .plus(months.toLong(), DateTimeUnit.MONTH, zone)
        .plus(days.toLong(), DateTimeUnit.DAY, zone)
    return shifted.toEpochMilliseconds() +
      hours * 3_600_000.0 +
      minutes * 60_000.0 +
      seconds * 1000.0 +
      millis
  }

  private fun List<VegaValue>.numberOr(index: Int, fallback: Double): Double =
    if (index < size && at(index) != VegaValue.Null) JsSemantics.toNumber(at(index)) else fallback

  /** Where "now" is, for the accessors that read a local calendar. */
  private fun localZone(): TimeZone = TimeZone.currentSystemDefault()

  // ---- helpers --------------------------------------------------------------

  private fun MutableMap<String, ExpressionFunction>.unary(
    name: String,
    body: (Double) -> Double,
  ) {
    this[name] = ExpressionFunction { args -> VegaValue.Num(body(args.number(0))) }
  }

  private fun MutableMap<String, ExpressionFunction>.predicate(
    name: String,
    body: (VegaValue) -> Boolean,
  ) {
    this[name] = ExpressionFunction { args -> VegaValue.Bool(body(args.at(0))) }
  }

  private fun List<VegaValue>.at(index: Int): VegaValue = getOrElse(index) { VegaValue.Null }

  private fun List<VegaValue>.number(index: Int): Double = JsSemantics.toNumber(at(index))

  private fun List<VegaValue>.string(index: Int): String = JsSemantics.toStringValue(at(index))

  /** `Math.min`/`Math.max`: variadic, and NaN if any argument does not coerce to a number. */
  private fun extreme(arguments: List<VegaValue>, takeSmaller: Boolean): VegaValue {
    val numbers = arguments.map { JsSemantics.toNumber(it) }
    if (numbers.isEmpty()) {
      return VegaValue.Num(if (takeSmaller) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY)
    }
    if (numbers.any { it.isNaN() }) return VegaValue.Num(Double.NaN)
    return VegaValue.Num(if (takeSmaller) numbers.min() else numbers.max())
  }

  private fun clampIndex(value: Double, length: Int): Int =
    if (value.isNaN()) 0 else value.toInt().coerceIn(0, length)

  /** Negative indices count back from the end, as JavaScript's `slice` does. */
  private fun relativeIndex(value: Double, length: Int): Int {
    if (value.isNaN()) return 0
    val index = value.toInt()
    return (if (index < 0) length + index else index).coerceIn(0, length)
  }

  private fun parseLeadingNumber(text: String, allowDecimal: Boolean): Double {
    val trimmed = text.trim()
    val pattern = if (allowDecimal) LEADING_FLOAT else LEADING_INT
    val match = pattern.find(trimmed) ?: return Double.NaN
    return match.value.toDoubleOrNull() ?: Double.NaN
  }

  private fun parseInteger(text: String, radix: Int): Double {
    val trimmed = text.trim()
    if (radix == 10) return parseLeadingNumber(trimmed, allowDecimal = false)
    val effective = radix.takeIf { it in 2..36 } ?: return Double.NaN
    val body = if (effective == 16) trimmed.removePrefix("0x").removePrefix("0X") else trimmed
    val digits = body.takeWhile { Character.digit(it, effective) >= 0 }
    if (digits.isEmpty()) return Double.NaN
    return digits.toLongOrNull(effective)?.toDouble() ?: Double.NaN
  }

  private fun truncateText(text: String, limit: Int, position: String, ellipsis: String): String {
    if (limit <= 0 || text.length <= limit) return text
    val keep = (limit - ellipsis.length).coerceAtLeast(0)
    return when (position.lowercase()) {
      "left" -> ellipsis + text.substring(text.length - keep)
      "center",
      "middle" -> {
        val head = keep / 2
        val tail = keep - head
        text.substring(0, head) + ellipsis + text.substring(text.length - tail)
      }
      else -> text.substring(0, keep) + ellipsis
    }
  }

  private fun padText(text: String, length: Int, character: String, align: String): String {
    if (text.length >= length || character.isEmpty()) return text
    val fill = character[0]
    val missing = length - text.length
    return when (align.lowercase()) {
      "left" -> fill.toString().repeat(missing) + text
      "center",
      "middle" -> {
        val head = missing / 2
        fill.toString().repeat(head) + text + fill.toString().repeat(missing - head)
      }
      else -> text + fill.toString().repeat(missing)
    }
  }

  private val LEADING_FLOAT = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
  private val LEADING_INT = Regex("^[+-]?\\d+")

  /** Numbers before strings, each ascending — the ordering upstream's `sort` produces. */
  private val NATURAL_ORDER =
    Comparator<VegaValue> { a, b ->
      val numeric = a is VegaValue.Num && b is VegaValue.Num
      if (numeric) {
        JsSemantics.toNumber(a).compareTo(JsSemantics.toNumber(b))
      } else {
        JsSemantics.toStringValue(a).compareTo(JsSemantics.toStringValue(b))
      }
    }
}

/**
 * The slice of d3-format that default and common Vega format strings need.
 *
 * Supports `.Nf`, `.Ne`, `.N%`, `d`, `,d`, `,.Nf` and a bare `%`. Anything else falls back to plain
 * number formatting and is reported by the caller, rather than silently producing a differently
 * formatted label.
 */
public object NumberFormatSubset {

  /** Returns `null` from [parse] for a specifier this subset cannot honour. */
  public data class Spec(
    val group: Boolean,
    val precision: Int?,
    val type: Char,
  )

  public fun parse(specifier: String): Spec? {
    val match = PATTERN.matchEntire(specifier) ?: return null
    val group = match.groupValues[1] == ","
    val precision =
      match.groupValues[2].takeIf { it.isNotEmpty() }?.removePrefix(".")?.toIntOrNull()
    val type = match.groupValues[3].firstOrNull() ?: 'f'
    return Spec(group, precision, type)
  }

  public fun format(value: Double, specifier: String): String {
    val spec = parse(specifier) ?: return JsSemantics.numberToString(value)
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "∞" else "-∞"

    val text =
      when (spec.type) {
        'd' -> roundHalfUp(value).toLong().toString()
        'e' -> PlatformDecimals.exponential(value, spec.precision ?: 6)
        '%' -> {
          val scaled = value * 100.0
          PlatformDecimals.fixed(scaled, spec.precision ?: 0) + "%"
        }
        else -> PlatformDecimals.fixed(value, spec.precision ?: 6)
      }
    return if (spec.group) groupThousands(text) else text
  }

  private fun groupThousands(text: String): String {
    val negative = text.startsWith("-")
    val body = if (negative) text.substring(1) else text
    val dot = body.indexOf('.')
    val integerPart = if (dot < 0) body else body.substring(0, dot)
    val rest = if (dot < 0) "" else body.substring(dot)
    val grouped = integerPart.reversed().chunked(3).joinToString(",").reversed()
    return (if (negative) "-" else "") + grouped + rest
  }

  private val PATTERN = Regex("^(,?)(\\.\\d+)?([dfe%]?)$")
}
