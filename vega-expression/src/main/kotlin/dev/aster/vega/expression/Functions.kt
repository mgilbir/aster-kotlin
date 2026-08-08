package dev.aster.vega.expression

import dev.aster.vega.model.MINUS_SIGN
import dev.aster.vega.model.PlatformDecimals
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field
import dev.aster.vega.model.roundHalfUp
import dev.aster.vega.model.time.DateValues
import dev.aster.vega.model.time.TimeFormat
import dev.aster.vega.model.time.TimeInterval
import dev.aster.vega.model.time.TimeStepper
import dev.aster.vega.model.time.TimeUnits
import dev.aster.vega.model.withTypographicMinus
import dev.aster.vega.scene.SceneColor
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
      "timeParse" to
        "parsing a date against a format string needs a strptime the engine does not have; " +
          "an ISO 8601 string works through toDate",
      "utcParse" to
        "parsing a date against a format string needs a strptime the engine does not have; " +
          "an ISO 8601 string works through toDate",
      "gradient" to "gradients cannot be produced from an expression yet",
      "lab" to "colour helpers are not implemented",
      "hcl" to "colour helpers are not implemented",
      "geoArea" to "geographic functions are out of scope for the first release",
      "geoBounds" to "geographic functions are out of scope for the first release",
      "geoCentroid" to "geographic functions are out of scope for the first release",
      "vlSelectionTest" to "selection helpers require the signal and selection subsystems",
      "vlSelectionResolve" to "selection helpers require the signal and selection subsystems",
    )

  /** A runaway step cannot spin forever; no axis has this many boundaries. */
  private const val MAX_SEQUENCE: Int = 100_000

  /** The stepper a unit name asks for, or null when it names none. */
  private fun stepperFor(unit: String, zone: TimeZone): TimeStepper? =
    when (unit.lowercase()) {
      "millisecond",
      "milliseconds" -> TimeInterval.MILLISECOND
      "second",
      "seconds" -> TimeInterval.SECOND
      "minute",
      "minutes" -> TimeInterval.MINUTE
      "hour",
      "hours" -> TimeInterval.HOUR
      "day",
      "date" -> TimeInterval.DAY
      "week" -> TimeInterval.WEEK
      "month" -> TimeInterval.MONTH
      "year" -> TimeInterval.YEAR
      else -> null
    }?.let { TimeStepper(it, 1, zone) }

  public val functions: Map<String, ExpressionFunction> = buildFunctions()

  private fun buildFunctions(): Map<String, ExpressionFunction> {
    val map = LinkedHashMap<String, ExpressionFunction>()

    // ---- arrays and sequences -----------------------------------------------

    /**
     * `lerp(array, fraction)` — a point between an array's first and last values.
     *
     * Upstream short-circuits at 0 and 1 rather than computing `lo + f*(hi-lo)`, so those two
     * return the endpoints exactly rather than to within a rounding. Kept, because a specification
     * that asks for the end of a range means the end of it.
     */
    map["lerp"] = ExpressionFunction { args ->
      val array =
        (args.getOrNull(0) as? VegaValue.Arr)?.values ?: return@ExpressionFunction VegaValue.Null
      if (array.isEmpty()) return@ExpressionFunction VegaValue.Null
      val lo = JsSemantics.toNumber(array.first())
      val hi = JsSemantics.toNumber(array.last())
      val f = JsSemantics.toNumber(args.getOrNull(1) ?: VegaValue.Null)
      VegaValue.Num(
        when {
          array.size == 1 -> lo
          f == 0.0 || f.isNaN() -> lo
          f == 1.0 -> hi
          else -> lo + f * (hi - lo)
        }
      )
    }

    /**
     * `sequence([start,] stop[, step])` — the numbers a range covers, `stop` exclusive.
     *
     * Multiplied out from the start rather than accumulated, so a fractional step does not drift;
     * the `sequence` transform counts the same way for the same reason.
     */
    map["sequence"] = ExpressionFunction { args ->
      val numbers = args.map { JsSemantics.toNumber(it) }
      val (start, stop, step) =
        when (numbers.size) {
          0 -> return@ExpressionFunction VegaValue.Arr(emptyList())
          1 -> Triple(0.0, numbers[0], 1.0)
          2 -> Triple(numbers[0], numbers[1], 1.0)
          else -> Triple(numbers[0], numbers[1], numbers[2])
        }
      if (step == 0.0 || !step.isFinite() || !start.isFinite() || !stop.isFinite()) {
        return@ExpressionFunction VegaValue.Arr(emptyList())
      }
      val count = kotlin.math.ceil((stop - start) / step).toInt()
      VegaValue.Arr((0 until maxOf(0, count)).map { VegaValue.Num(start + step * it) })
    }

    /**
     * `bandspace(count, paddingInner, paddingOuter)` — how many band steps a domain needs.
     *
     * The `count ? ... : 0` and the `> 0` floor are upstream's: an empty domain takes no space, and
     * padding large enough to consume the whole band still leaves one step rather than a negative
     * one, which would invert the scale.
     */
    map["bandspace"] = ExpressionFunction { args ->
      val count = JsSemantics.toNumber(args.getOrNull(0) ?: VegaValue.Null)
      val inner =
        JsSemantics.toNumber(args.getOrNull(1) ?: VegaValue.Null).let {
          if (it.isNaN()) 0.0 else it
        }
      val outer =
        JsSemantics.toNumber(args.getOrNull(2) ?: VegaValue.Null).let {
          if (it.isNaN()) 0.0 else it
        }
      if (count.isNaN() || count == 0.0) {
        VegaValue.Num(0.0)
      } else {
        val space = count - inner + outer * 2
        VegaValue.Num(if (space > 0) space else 1.0)
      }
    }

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

    /**
     * `hypot(...)` — `Math.hypot`, which is **variadic** and not the two-argument function its name
     * suggests. A Monte Carlo estimate of pi is `hypot(datum.x, datum.y) <= 1` and nothing else.
     *
     * `Math.hypot()` with no arguments is 0, and any infinite argument makes the result infinite
     * even beside a NaN — JavaScript's own order of tests, and the reason this is not a plain
     * `sqrt` of a sum of squares.
     */
    map["hypot"] = ExpressionFunction { args ->
      val numbers = args.map { JsSemantics.toNumber(it) }
      VegaValue.Num(
        when {
          numbers.any { it.isInfinite() } -> Double.POSITIVE_INFINITY
          numbers.any { it.isNaN() } -> Double.NaN
          else -> sqrt(numbers.sumOf { it * it })
        }
      )
    }

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
    // `Number.isFinite`, not the global `isFinite` — `vega-expression` maps it to the former, so
    // there is **no coercion**: `isFinite('5')` is false, and so is `isFinite(null)` where the
    // global would have said true. The distinction is load-bearing for `bin`, whose out-of-extent
    // rows carry an infinity rather than a null, and which a specification filters out with this.
    map.predicate("isFinite") { it is VegaValue.Num && it.value.isFinite() }

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

    /**
     * `pluck(array, 'field')` — one column out of a list of objects.
     *
     * Upstream applies its ordinary field accessor to each element, so a dotted or bracketed path
     * works and a missing field gives null. A value that is not an array has the accessor applied
     * to it directly, which is upstream's own fallback rather than an error.
     */
    map["pluck"] = ExpressionFunction { args ->
      val path = args.string(1)
      when (val data = args.at(0)) {
        is VegaValue.Arr -> VegaValue.Arr(data.values.map { it.field(path) })
        else -> data.field(path)
      }
    }

    // ---- time arithmetic ----------------------------------------------------

    /**
     * `timeOffset(unit, date, step)` — a date moved by whole calendar units.
     *
     * Whole *units*, not milliseconds: a month later is the same day of the next month, and a day
     * later across a clock change is still the same wall-clock time.
     *
     * The step defaults to **one**, and it has to be read as absent rather than coerced: `Number()`
     * of a missing argument is 0, which offsets by nothing and returns the date it was handed. That
     * is d3's rule — `step == null ? 1 : Math.floor(step)` — and it matters because the
     * two-argument form is the one specifications actually write.
     */
    map["timeOffset"] = ExpressionFunction { args ->
      val stepper =
        stepperFor(args.string(0), TimeZone.currentSystemDefault())
          ?: return@ExpressionFunction VegaValue.Null
      val at = JsSemantics.toNumber(args.at(1))
      if (!at.isFinite()) return@ExpressionFunction VegaValue.Null
      val by = args.numberOr(2, 1.0).takeIf { it.isFinite() } ?: 1.0
      VegaValue.Num(stepper.offset(at, floor(by).toInt()))
    }

    /**
     * `timeSequence(unit, start, stop[, step])` — every boundary in a span, `stop` exclusive.
     *
     * The sequence starts at `start` itself rather than at the unit boundary below it, which is
     * upstream's behaviour and worth knowing: a sequence from the middle of a day steps by days
     * from the middle of the day.
     */
    map["timeSequence"] = ExpressionFunction { args ->
      val stepper =
        stepperFor(args.string(0), TimeZone.currentSystemDefault())
          ?: return@ExpressionFunction VegaValue.Arr(emptyList())
      val start = JsSemantics.toNumber(args.at(1))
      val stop = JsSemantics.toNumber(args.at(2))
      if (!start.isFinite() || !stop.isFinite()) {
        return@ExpressionFunction VegaValue.Arr(emptyList())
      }
      val by = JsSemantics.toNumber(args.at(3)).takeIf { it.isFinite() && it != 0.0 } ?: 1.0
      val out = mutableListOf<VegaValue>()
      var at = start
      var guard = 0
      while (at < stop && guard < MAX_SEQUENCE) {
        out.add(VegaValue.Num(at))
        at = stepper.offset(at, by.toInt())
        guard++
      }
      VegaValue.Arr(out)
    }

    // ---- colour -------------------------------------------------------------

    /**
     * `hsl(h, s, l)` builds a colour; `hsl(value)` reads one apart.
     *
     * Upstream returns one object that does both — d3's, whose `h`, `s` and `l` can be read and
     * whose string form is `rgb(r, g, b)`. A [VegaValue] is either an object or a string and cannot
     * be both, so the two uses are split by arity: three numbers give the CSS colour, one value
     * gives the components. That covers what a specification actually does with it — Vega's
     * platformer reads a colour apart, shifts its saturation and lightness, and puts it back
     * together — and anything relying on the object *also* printing as a colour is the difference.
     *
     * `s` and `l` are fractions here, not percentages, which is d3's convention and not CSS's.
     */
    map["hsl"] = ExpressionFunction { args ->
      if (args.size >= 3) {
        val h = JsSemantics.toNumber(args.at(0))
        val s = JsSemantics.toNumber(args.at(1))
        val l = JsSemantics.toNumber(args.at(2))
        val colour = SceneColor.parse("hsl(${'$'}h, ${'$'}{s * 100}%, ${'$'}{l * 100}%)")
        if (colour == null) VegaValue.Null else VegaValue.Str(colour.toCssRgb())
      } else {
        val colour = SceneColor.parse(args.string(0)) ?: return@ExpressionFunction VegaValue.Null
        val (h, s, l) = colour.toHsl()
        VegaValue.Obj(
          linkedMapOf(
            "h" to VegaValue.Num(h),
            "s" to VegaValue.Num(s),
            "l" to VegaValue.Num(l),
          )
        )
      }
    }

    /** `rgb(r, g, b)` — the same shape, and the form every colour prints in. */
    map["rgb"] = ExpressionFunction { args ->
      val colour =
        if (args.size >= 3) {
          SceneColor.parse(
            "rgb(${'$'}{JsSemantics.toNumber(args.at(0))}, " +
              "${'$'}{JsSemantics.toNumber(args.at(1))}, " +
              "${'$'}{JsSemantics.toNumber(args.at(2))})"
          )
        } else {
          SceneColor.parse(args.string(0))
        }
      if (colour == null) VegaValue.Null else VegaValue.Str(colour.toCssRgb())
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

    /**
     * `timeUnitSpecifier(units[, specifiers])` — the format the buckets of a `timeunit` read as.
     *
     * A chart that lets a control choose the granularity cannot write its axis format down, because
     * the format *is* the choice: `["day"]` wants `%a` and `["year", "month"]` wants `%Y-%m`. This
     * is how it asks for whichever one applies, and the second argument shortens a single unit
     * without restating the rest.
     */
    map["timeUnitSpecifier"] = ExpressionFunction { args ->
      val units =
        when (val given = args.at(0)) {
          is VegaValue.Arr -> given.values.map { JsSemantics.toStringValue(it) }
          VegaValue.Null -> emptyList()
          else -> listOf(JsSemantics.toStringValue(given))
        }
      val overrides =
        (args.at(1) as? VegaValue.Obj)?.fields?.mapValues { (_, v) -> JsSemantics.toStringValue(v) }
      VegaValue.Str(TimeUnits.specifier(units, overrides.orEmpty()))
    }

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

    // ---- colour -------------------------------------------------------------

    /**
     * `luminance(color)` — WCAG relative luminance, the quantity a contrast ratio is built from.
     *
     * Two constants make this easy to get plausibly wrong. The knee of the gamma expansion is
     * **0.03928**, WCAG's, not the 0.04045 that d3's CIE Lab conversion uses two modules away; and
     * the exponent is 2.4 applied to `(v + 0.055) / 1.055`, not a bare `v^2.2`. Either mistake
     * still returns a number between 0 and 1 that grows with brightness, so only a comparison
     * against upstream catches it.
     *
     * Upstream reads d3-color's **8-bit** channels and divides each by 255. [SceneColor] already
     * stores exactly that quotient, so the components go straight in; scaling them back to bytes
     * and dividing again would be a round trip that only introduces error. The channels are
     * deliberately not rounded — an `hsl()` colour has fractional ones, and rounding them shifts
     * the result.
     *
     * Nothing clamps, because upstream does not: `rgb(300, 0, 0)` is brighter than white there too.
     *
     * A colour that cannot be parsed gives NaN, which is what d3 gives it: `rgb('nonsense')` has
     * NaN channels. So does a fully transparent one — d3 blanks the channels when the opacity is
     * zero — so `luminance('transparent')` is NaN rather than the 0 that black would give.
     */
    map["luminance"] = ExpressionFunction { args ->
      VegaValue.Num(relativeLuminance(args.string(0)))
    }

    /**
     * `contrast(a, b)` — the WCAG contrast ratio between two colours.
     *
     * `(lighter + 0.05) / (darker + 0.05)`, where each side is [luminance]. The order of the
     * arguments does not matter: the brighter of the two always goes on top, so the result is at
     * least 1 whichever way round it is written. That is what lets the common idiom work —
     * `contrast('white', datum.fill) > contrast('black', datum.fill)` picks whichever of white or
     * black reads better on a bar, without knowing which is lighter.
     *
     * An unparseable or fully transparent colour makes its luminance NaN, and `max`/`min` of a NaN
     * are NaN, so the ratio is NaN and every comparison against it is false. Upstream behaves the
     * same way for the same reason.
     */
    map["contrast"] = ExpressionFunction { args ->
      val first = relativeLuminance(args.string(0))
      val second = relativeLuminance(args.string(1))
      VegaValue.Num((maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05))
    }

    // ---- probability distributions ------------------------------------------

    /**
     * The nine distribution functions, in upstream's naming: `{density,cumulative,quantile}` over
     * `{Normal,LogNormal,Uniform}`.
     *
     * `quantileNormal` is the one charts actually reach for — a quantile-quantile plot is that
     * function and nothing else, asking what value each rank *would* have under a normal
     * distribution so the data's own quantiles can be plotted against it.
     *
     * Every one is deterministic. The `sample*` functions beside them upstream are not, and stay
     * refused for the same reason `random()` is.
     */
    fun distribution(
      name: String,
      compute: (Double, Double, Double) -> Double,
      a: Double,
      b: Double,
    ) {
      map[name] = ExpressionFunction { args ->
        val x = JsSemantics.toNumber(args.at(0))
        val first = args.getOrNull(1)?.let { JsSemantics.toNumber(it) } ?: a
        val second = args.getOrNull(2)?.let { JsSemantics.toNumber(it) } ?: b
        VegaValue.Num(compute(x, first, second))
      }
    }

    distribution("densityNormal", Statistics::densityNormal, 0.0, 1.0)
    distribution("cumulativeNormal", Statistics::cumulativeNormal, 0.0, 1.0)
    distribution("quantileNormal", Statistics::quantileNormal, 0.0, 1.0)
    distribution("densityLogNormal", Statistics::densityLogNormal, 0.0, 1.0)
    distribution("cumulativeLogNormal", Statistics::cumulativeLogNormal, 0.0, 1.0)
    distribution("quantileLogNormal", Statistics::quantileLogNormal, 0.0, 1.0)
    // The uniform trio defaults to the unit interval rather than to a mean and a deviation.
    distribution("densityUniform", Statistics::densityUniform, 0.0, 1.0)
    distribution("cumulativeUniform", Statistics::cumulativeUniform, 0.0, 1.0)
    distribution("quantileUniform", Statistics::quantileUniform, 0.0, 1.0)

    // ---- the embedding page -------------------------------------------------

    /**
     * `containerSize()` — the size of the DOM element the view is embedded in.
     *
     * There is no DOM here and there never will be, so this is upstream's headless answer rather
     * than an invented one: with no container element, `containerSize()` is `[undefined,
     * undefined]` — a two-element array of absent values, not `[0, 0]` and not an empty array.
     * Verified against upstream in a `renderer: 'none'` view, which is exactly the configuration
     * the differential oracle renders every fixture in.
     *
     * A specification that sizes itself from its container therefore gets nothing here, the same
     * nothing it gets from upstream outside a browser. Reporting the view's own width and height
     * instead would be a different chart from the one upstream draws.
     */
    map["containerSize"] = ExpressionFunction {
      VegaValue.Arr(listOf(VegaValue.Null, VegaValue.Null))
    }

    // ---- control flow -------------------------------------------------------
    map["if"] = ExpressionFunction { args ->
      if (JsSemantics.truthy(args.at(0))) args.at(1) else args.at(2)
    }

    return map
  }

  /**
   * sRGB gamma expansion as WCAG defines it, on a channel already divided by 255.
   *
   * `ColorSpaces.linearize` looks identical and is not: its knee is at 0.04045, the sRGB
   * standard's, where this one is at 0.03928, the value WCAG 2.0 printed. For a whole 8-bit channel
   * the two never disagree — 10/255 is 0.0392 and 11/255 is 0.0431, so no byte lands between them —
   * but a fractional channel from an `hsl()` colour can, and then the two answers differ.
   */
  private fun expandGamma(channel: Double): Double =
    if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

  /** The WCAG relative luminance of a colour name, shared by `luminance` and `contrast`. */
  private fun relativeLuminance(text: String): Double {
    val color = SceneColor.parse(text)
    if (color == null || color.alpha <= 0.0) return Double.NaN
    return 0.2126 * expandGamma(color.red) +
      0.7152 * expandGamma(color.green) +
      0.0722 * expandGamma(color.blue)
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
    /** Strips insignificant trailing zeros, which is d3's `~` and is implied by a missing type. */
    val trim: Boolean = false,
    /**
     * d3's currency *symbol* flag, `$`, which is a slot of its own in the grammar and not a type.
     *
     * It sits before the zero-pad flag and the width, so `$,.2f` and `$0.2f` both mean "currency,
     * two decimals" — which is how every price axis in the gallery is written. The symbol itself
     * comes from the locale; this uses d3's default, `$` before the magnitude and inside the sign,
     * so a negative price reads `−$1.50`.
     */
    val currency: Boolean = false,
  )

  /**
   * A specifier naming **no type** is not "plain formatting": d3 aliases it to `.12~g` — twelve
   * significant digits, trailing zeros trimmed. That is why `format(x, "")` prints `5` rather than
   * `5.000000`, and why it switches to `1.23456789012e+14` once a number outgrows twelve digits.
   */
  public fun parse(specifier: String): Spec? {
    val match = PATTERN.matchEntire(specifier) ?: return null
    val currency = match.groupValues[1] == "$"
    val group = match.groupValues[3] == ","
    val precision =
      match.groupValues[4].takeIf { it.isNotEmpty() }?.removePrefix(".")?.toIntOrNull()
    val type = match.groupValues[5].firstOrNull()
    return if (type == null) {
      Spec(group, precision ?: DEFAULT_SIGNIFICANT_DIGITS, 'g', trim = true, currency = currency)
    } else {
      Spec(group, precision, type, currency = currency)
    }
  }

  public fun format(value: Double, specifier: String): String {
    val spec = parse(specifier) ?: return withTypographicMinus(JsSemantics.numberToString(value))
    if (value.isNaN()) return "NaN"
    // d3-format spells these the way JavaScript does, then signs them like any other number.
    if (value.isInfinite()) return if (value > 0) "Infinity" else MINUS_SIGN + "Infinity"

    val raw =
      when (spec.type) {
        'd' -> roundHalfUp(value).toLong().toString()
        'e' -> PlatformDecimals.exponential(value, spec.precision ?: 6)
        'g' ->
          PlatformDecimals.significant(
            value,
            (spec.precision ?: DEFAULT_SIGNIFICANT_DIGITS).coerceAtLeast(1),
          )
        '%' -> {
          val scaled = value * 100.0
          PlatformDecimals.fixed(scaled, spec.precision ?: 0) + "%"
        }
        else -> PlatformDecimals.fixed(value, spec.precision ?: 6)
      }
    val text = if (spec.trim) trimInsignificantZeros(raw) else raw
    val grouped = if (spec.group) groupThousands(text) else text
    // The currency symbol goes *inside* the sign, between it and the digits, so -1.5 reads `−$1.50`
    // rather than `$−1.50`. Applied before the minus substitution because it has to find the sign.
    val signed = if (spec.currency) prefixCurrency(grouped) else grouped
    // d3 formats the magnitude and prefixes the sign, so the substitution comes last and leaves an
    // exponent's own hyphen alone: `.2e` of -0.005 is `−5.00e-3`, with two different characters.
    return withTypographicMinus(signed)
  }

  /**
   * d3's `~`: drops trailing zeros from the fraction, and the point with them, leaving any exponent
   * suffix in place. `5.00000000000` becomes `5`; `1.00000000000e+21` becomes `1e+21`.
   */
  private fun trimInsignificantZeros(text: String): String {
    val dot = text.indexOf('.')
    if (dot < 0) return text
    val exponent = text.indexOf('e', dot)
    val suffix = if (exponent < 0) "" else text.substring(exponent)
    val body = if (exponent < 0) text else text.substring(0, exponent)
    val trimmed = body.trimEnd('0').trimEnd('.')
    return trimmed + suffix
  }

  /** d3's default currency symbol, placed between the sign and the digits. */
  private fun prefixCurrency(text: String): String =
    if (text.startsWith("-")) "-$" + text.substring(1) else "$$text"

  private fun groupThousands(text: String): String {
    val negative = text.startsWith("-")
    val body = if (negative) text.substring(1) else text
    val dot = body.indexOf('.')
    val integerPart = if (dot < 0) body else body.substring(0, dot)
    val rest = if (dot < 0) "" else body.substring(dot)
    val grouped = integerPart.reversed().chunked(3).joinToString(",").reversed()
    return (if (negative) "-" else "") + grouped + rest
  }

  /** d3's default precision for a specifier with no type: `.12~g`. */
  private const val DEFAULT_SIGNIFICANT_DIGITS = 12

  /**
   * The slice of d3's format grammar this subset reads.
   *
   * `[$][0][,][.precision][type]`, in that order, which is d3's own order and not negotiable — `$,`
   * parses and `,$` does not. The zero-pad flag is accepted and ignored: it only matters alongside
   * a width, which this subset does not implement, and refusing the whole specifier over it would
   * turn `$0.2f` into unformatted output.
   */
  private val PATTERN = Regex("^(\\$?)(0?)(,?)(\\.\\d+)?([dfe%]?)$")
}
