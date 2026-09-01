package dev.aster.vega.expression

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asNumberOrNull
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import dev.aster.vega.model.isNullish
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.model.time.DateValues
import dev.aster.vega.model.time.JsDate
import dev.aster.vega.model.time.TimeFormat
import dev.aster.vega.model.time.TimeInterval
import dev.aster.vega.model.time.TimeParse
import dev.aster.vega.model.time.TimeStepper
import dev.aster.vega.model.time.TimeUnits
import dev.aster.vega.scene.ColorSpaces
import dev.aster.vega.scene.SceneColor
import io.github.mgilbir.ecma262.text.ecmaTrim
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
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
 * reproducible (ADR 0008); date and time functions, because time scales are not implemented; and
 * the selection and geo helpers, which belong to subsystems that do not exist yet.
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
   * Functions upstream provides that this engine deliberately does not — and there are none.
   *
   * The table stays because it is the right shape for the answer: a name here gets a reason instead
   * of a bare "unknown function". It emptied one entry at a time, and the last few are worth
   * knowing about because each excuse turned out to be softer than it read. "Needs a strptime"
   * became a strptime. "A date is indistinguishable from a number" became `VegaValue.Timestamp`,
   * and finding that out exposed `isNumber` lying about dates. "Selection helpers need the
   * selection subsystem" became the observation that a selection is an ordinary dataset. `screen`,
   * `windowSize`, `intersect` and `inScope` answer what upstream answers with no browser and no
   * running view, which is a compiled scene's permanent position rather than a gap. `pathShape`,
   * `geoShape` and `copy` return the one part of upstream's answer a value model can hold, and
   * upstream's own return value cannot be used from a specification at all — probed on every
   * channel that accepts one.
   *
   * If something is genuinely not implemented, it belongs here with a reason. An empty map is a
   * claim that nothing is, and `ExpressionReferenceTest` checks it against upstream's own function
   * table.
   */
  public val knownUnsupported: Map<String, String> = emptyMap()

  /** A runaway step cannot spin forever; no axis has this many boundaries. */
  private const val MAX_SEQUENCE: Int = 100_000

  /**
   * The calendar `new Date(year, ...)` can build in, and the time value it can hold.
   *
   * ECMA-262 clips a date to ±8.64e15 milliseconds, which is ±271,821 years around the epoch;
   * outside it the answer is an *Invalid Date*, meaning NaN. The year bounds are that range in
   * years, rounded outwards, and they exist so a year that cannot be a `LocalDate` never becomes
   * one.
   */
  private const val MAX_TIME_VALUE: Double = 8.64e15

  private const val MIN_YEAR: Double = -271_821.0

  private const val MAX_YEAR: Double = 275_760.0

  /** `TimeClip`: a time value outside ECMA-262's range is an Invalid Date. */
  private fun clipTimeValue(millis: Double): Double =
    if (!millis.isFinite() || abs(millis) > MAX_TIME_VALUE) Double.NaN else millis

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

  /** The function table with d3's own `en-US` locale and no container, as upstream produces. */
  public val functions: Map<String, ExpressionFunction> =
    buildFunctions(VegaLocale.EnglishUS, null, null, null)

  /**
   * The same table, with the seven locale-dependent functions bound to [locale].
   *
   * `monthFormat`, `monthAbbrevFormat`, `dayFormat`, `dayAbbrevFormat`, `timeFormat`, `utcFormat`
   * and `format` are the whole of it: everything else in here is arithmetic or string handling that
   * no language changes. A host reaches this through the compiler rather than directly —
   * `SpecCompiler` takes the locale and hands it down — and the default instance above is returned
   * unchanged for the default locale so nothing pays for a feature it does not use.
   */
  public fun functionsFor(
    locale: VegaLocale,
    /**
     * The size of the surface the chart is drawn in, which `containerSize()` answers with.
     *
     * Null, and therefore `[null, null]`, unless a host says otherwise — see the note on
     * `containerSize` below for why that is upstream's answer and not a shortcut.
     */
    containerWidth: Double? = null,
    containerHeight: Double? = null,
    /**
     * Which zone the **local** time functions work in, or null for the device's own.
     *
     * `datetime`, `time`, `hours`, `timeFormat`, `timeParse`, `timeOffset`, `timeSequence` and
     * `timezoneoffset` all answer in local time, and a host may say what local means — see
     * `VegaTimeZones`. Null keeps today's behaviour exactly, including that the zone is read at
     * each call rather than captured, so a long-lived process follows a change of the system zone.
     *
     * The `utc` twins are unaffected, and one detail of them is worth stating because it looks like
     * an oversight and is upstream's rule: `utchours("2026-05-20T00:30")` **parses** that naive
     * string in local time — `Date.parse` does — and only then reads the hour in UTC. So the zone
     * supplied here reaches the utc functions too, as the zone a bare string is read in.
     */
    timeZone: TimeZone? = null,
  ): Map<String, ExpressionFunction> =
    if (
      locale == VegaLocale.EnglishUS &&
        containerWidth == null &&
        containerHeight == null &&
        timeZone == null
    ) {
      functions
    } else {
      buildFunctions(locale, containerWidth, containerHeight, timeZone)
    }

  private fun buildFunctions(
    locale: VegaLocale,
    containerWidth: Double?,
    containerHeight: Double?,
    timeZone: TimeZone? = null,
  ): Map<String, ExpressionFunction> {
    val map = LinkedHashMap<String, ExpressionFunction>()

    // Where "now" is, for every function that reads a local calendar. A **function** rather than a
    // captured value because a null zone means "the device's, whenever you ask": capturing it would
    // freeze the zone of whichever thread built this table.
    fun localZone(): TimeZone = timeZone ?: TimeZone.currentSystemDefault()

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
     * `extent(array)` — `[min, max]`, skipping null and NaN.
     *
     * Not a numeric function: upstream compares the values with `<` and `>` as they are, so an
     * array of strings gives its lexicographic ends and an array of instants gives its earliest and
     * latest. Both ends are null for an array with nothing usable in it, which is what a scale
     * pointed at an empty dataset has to cope with.
     */
    map["extent"] = ExpressionFunction { args ->
      val values =
        (args.at(0) as? VegaValue.Arr)?.values ?: return@ExpressionFunction VegaValue.Null
      val usable = values.filterNot { it.isNullish || (it.asNumberOrNull()?.isNaN() == true) }
      if (usable.isEmpty()) {
        return@ExpressionFunction VegaValue.Arr(listOf(VegaValue.Null, VegaValue.Null))
      }
      var low = usable.first()
      var high = usable.first()
      for (value in usable) {
        if ((JsSemantics.compare(value, low) ?: 0) < 0) low = value
        if ((JsSemantics.compare(value, high) ?: 0) > 0) high = value
      }
      VegaValue.Arr(listOf(low, high))
    }

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
          // Scaled by the largest magnitude before squaring, which is what `Math.hypot` is for:
          // the naive sum overflows to Infinity for `hypot(1e200, 1e200)`, whose answer is a
          // perfectly ordinary 1.41e200, and underflows to zero at the other end.
          else -> {
            val largest = numbers.maxOfOrNull { abs(it) } ?: 0.0
            if (largest == 0.0) 0.0
            else
              largest *
                sqrt(
                  numbers.sumOf {
                    val q = it / largest
                    q * q
                  }
                )
          }
        }
      )
    }

    // JavaScript's Math.round rounds halves toward +Infinity: round(-2.5) === -2, not -3.
    //
    // `floor(x + 0.5)` is the idiom and it is wrong twice. It loses a **negative zero** —
    // `Math.round(-0.4)` is `-0`, which `1/x` tells apart from `0` and which a formatter can print
    // with a minus sign — and it rounds *up* at the half-ulp below a half, because `x + 0.5` is
    // itself rounded before the floor: `Math.round(0.49999999999999994)` is 0 and this answered 1.
    map.unary("round") { value ->
      when {
        value.isNaN() || value.isInfinite() -> value
        // Below the tie, and above -0.5, the answer is a zero with the sign of the input.
        value < 0.5 && value >= -0.5 -> if (value < 0 || 1.0 / value < 0) -0.0 else 0.0
        else -> {
          val down = floor(value)
          if (value - down >= 0.5) down + 1.0 else down
        }
      }
    }

    // Vega's min and max are JavaScript's Math.min/Math.max: a variadic spread, not an array
    // reducer.
    // `min([5,2,9])` is NaN upstream, not 2 — verified, and easy to get wrong in the helpful
    // direction.
    map["min"] = ExpressionFunction { args -> extreme(args, takeSmaller = true) }
    map["max"] = ExpressionFunction { args -> extreme(args, takeSmaller = false) }

    // `Math.max(min, Math.min(max, value))`, composed in that order and **not** corrected for
    // swapped bounds: `clamp(5, 10, 0)` is 10 upstream, because the inner `min` picks 0 and the
    // outer `max` then picks 10. Sorting the bounds first answered 5, which is defensible and is
    // not what a specification written against upstream gets.
    map["clamp"] = ExpressionFunction { args ->
      val value = args.number(0)
      val low = args.number(1)
      val high = args.number(2)
      VegaValue.Num(maxOf(low, minOf(high, value)))
    }

    // ---- type predicates ----------------------------------------------------
    map.predicate("isArray") { it is VegaValue.Arr }
    map.predicate("isBoolean") { it is VegaValue.Bool }
    // A **date is not a number**, on both sides: `typeof new Date()` is `"object"`, so upstream's
    // `isNumber(datetime(…))` is false even though the value adds and compares like one. This
    // engine
    // spells a date as a `Timestamp` for exactly that reason, and the two predicates below are the
    // only place the difference shows.
    map.predicate("isNumber") { it is VegaValue.Num }
    // Upstream tests `value instanceof Date`, so a *number* of milliseconds is not a date to it
    // either — which is answerable here only because an instant is its own type in this value
    // model. Every Vega-Lite chart over a temporal field filters with
    // `isDate(f) || (isValid(f) && isFinite(+f))`, so without this the whole scale collapses.
    map.predicate("isDate") { it is VegaValue.Timestamp }
    // A date is an **object** as well as a date, because `typeof new Date()` is `"object"`. Both
    // predicates answer true for one upstream, which is the other half of `isNumber` answering
    // false.
    map.predicate("isObject") { it is VegaValue.Obj || it is VegaValue.Timestamp }
    map.predicate("isString") { it is VegaValue.Str }
    map.predicate("isRegExp") { it is VegaValue.Pattern }
    // `_ !== undefined`, and the strictness is the whole function: a field that is **present and
    // null** is defined, and answering false for it — which is what one value for both did — makes
    // `isDefined` a slower `isValid`.
    map.predicate("isDefined") { it !is VegaValue.Undefined }
    // `isValid` is narrower than truthiness: it rejects null and NaN but accepts 0 and "".
    map.predicate("isValid") {
      // `_ != null && _ === _`: the loose comparison, so both kinds of nothing are invalid.
      !it.isNullish && !(it is VegaValue.Num && it.value.isNaN())
    }
    // `Number.isFinite`, not the global `isFinite` — `vega-expression` maps it to the former, so
    // there is **no coercion**: `isFinite('5')` is false, and so is `isFinite(null)` where the
    // global would have said true. The distinction is load-bearing for `bin`, whose out-of-extent
    // rows carry an infinity rather than a null, and which a specification filters out with this.
    map.predicate("isFinite") { it is VegaValue.Num && it.value.isFinite() }

    // ---- coercion -----------------------------------------------------------
    map["toNumber"] = ExpressionFunction { args ->
      val value = args.at(0)
      if (value.isNullish) VegaValue.Null else VegaValue.Num(JsSemantics.toNumber(value))
    }
    map["toString"] = ExpressionFunction { args ->
      val value = args.at(0)
      if (value.isNullish) VegaValue.Null else VegaValue.Str(JsSemantics.toStringValue(value))
    }
    // Upstream returns null rather than false for null and the empty string.
    map["toBoolean"] = ExpressionFunction { args ->
      val value = args.at(0)
      val empty = value.isNullish || (value is VegaValue.Str && value.value.isEmpty())
      if (empty) VegaValue.Null else VegaValue.Bool(JsSemantics.truthy(value))
    }
    map["parseFloat"] = ExpressionFunction { args ->
      VegaValue.Num(parseLeadingNumber(JsSemantics.toStringValue(args.at(0)), allowDecimal = true))
    }
    // The radix is **0 when it is not given**, not 10, and 0 means "work it out": a string starting
    // `0x` or `0X` is read as hexadecimal with the prefix stripped, which is why `parseInt('0xFF')`
    // is 255. Defaulting to 10 answered 0, because parsing stops at the `x`.
    map["parseInt"] = ExpressionFunction { args ->
      val radix = if (args.size > 1) args.number(1).let { if (it.isNaN()) 0 else it.toInt() } else 0
      VegaValue.Num(parseInteger(JsSemantics.toStringValue(args.at(0)), radix))
    }

    // ---- strings ------------------------------------------------------------
    map["upper"] = ExpressionFunction { args -> VegaValue.Str(args.string(0).uppercase()) }
    map["lower"] = ExpressionFunction { args -> VegaValue.Str(args.string(0).lowercase()) }
    /**
     * `trim(string)` — JavaScript's, which is **not** Kotlin's.
     *
     * The two whitespace sets differ at both ends. ECMA trims U+FEFF, the byte-order mark, and
     * Kotlin does not — so a label read from a file that begins with one kept it. Kotlin trims
     * U+0085 and the four information separators U+001C–U+001F, and ECMA does not. `ecmaTrim` is
     * the specification's set.
     */
    map["trim"] = ExpressionFunction { args -> VegaValue.Str(args.string(0).ecmaTrim()) }
    map["substring"] = ExpressionFunction { args ->
      val text = args.string(0)
      val from = if (args.size > 1) clampIndex(args.number(1), text.length) else 0
      val to = if (args.size > 2) clampIndex(args.number(2), text.length) else text.length
      VegaValue.Str(text.substring(minOf(from, to), maxOf(from, to)))
    }
    /*
     * `regexp(pattern, flags)` — upstream's `new RegExp(pattern, flags)`.
     *
     * The pair this and `test` make is how a specification filters by text: the job-voyager example
     * writes `test(regexp(query,'i'), datum.job)` against a signal a reader types into. Neither
     * existed here, so that filter threw for every row the moment the query was not empty and the
     * chart went blank — which is what a bound text field made visible.
     */
    map["regexp"] = ExpressionFunction { args ->
      compilePattern(args.string(0), if (args.size > 1) args.string(1) else "")
    }
    /*
     * `test(pattern, string)` — whether the pattern matches anywhere in the string.
     *
     * Upstream compiles it to `RegExp(a).test(b)`, and `RegExp` of a *string* compiles that string as
     * a pattern — so `test('far', 'Farmer')` is legal and false, where `test(regexp('far','i'), …)`
     * is true. Both spellings are accepted here for the same reason.
     */
    map["test"] = ExpressionFunction { args ->
      // `findAll` rather than `test`, deliberately. JavaScript's `RegExp.test` *advances*
      // `lastIndex` when the pattern carries `g`, so the same call on the same string alternates
      // between true and false — and upstream builds a fresh `RegExp` per evaluation, so its cursor
      // is always at zero. A `Pattern` here is built once and read per row, so calling the stateful
      // form would make the answer depend on how many rows came before it.
      VegaValue.Bool(args.pattern(0).regex.findAll(args.string(1)).isNotEmpty())
    }
    map["replace"] = ExpressionFunction { args ->
      val text = args.string(0)
      val pattern = args.getOrNull(1)
      // A **pattern** replaces by match rather than by literal text, and only `g` makes it replace
      // more than the first — `replace('a-b-c', regexp('-','g'), '+')` is `a+b+c` where the same
      // without the flag is `a+b-c`. That rule is the engine's own now, so there is no flag test
      // here; and the replacement's `$1`, `$&`, `` $` ``, `$'` and `$<name>` are expanded as
      // JavaScript expands them, which Kotlin's `Regex` spells differently. A string pattern stays
      // literal, which is what it was before.
      VegaValue.Str(
        if (pattern is VegaValue.Pattern) {
          pattern.regex.replace(text, args.string(2))
        } else {
          text.replaceFirst(args.string(1), args.string(2))
        }
      )
    }
    map["split"] = ExpressionFunction { args ->
      val text = args.string(0)
      val separator = args.getOrNull(1)
      // A **pattern** separator splits by match, and JavaScript puts each capture group into the
      // result between the pieces — `split('a1b', /(x)?(\d)/)` is `['a', undefined, '1', 'b']`, a
      // group that did not participate included as a hole. This had been stringifying the pattern
      // to
      // `/\d+/` and splitting on that literally, so it silently returned the whole string.
      val parts =
        if (separator is VegaValue.Pattern) {
          separator.regex.split(text).map { piece ->
            if (piece == null) VegaValue.Null else VegaValue.Str(piece)
          }
        } else {
          text.split(args.string(1)).map { VegaValue.Str(it) }
        }
      val limit = if (args.size > 2) args.number(2).toInt() else parts.size
      VegaValue.Arr(parts.take(limit.coerceAtLeast(0)))
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
    // `wrap('format')`, and the wrapper is `value === null ? 'null' : locale.format(spec)(value)`
    // — a **strict** test, so a null argument comes back as the literal string `"null"` before any
    // formatting happens and an undefined one goes through and formats as NaN. Coercing the null
    // to 0 instead printed a number the data does not contain, which is the shape of mistake this
    // engine exists to avoid. The same wrapper covers `timeFormat`, `utcFormat`, `timeParse` and
    // `utcParse`; the two parsers already had it.
    map["format"] = ExpressionFunction { args ->
      if (args.at(0) is VegaValue.Null) return@ExpressionFunction VegaValue.Str("null")
      VegaValue.Str(NumberFormat.format(args.number(0), args.string(1), locale))
    }

    // ---- the four in upstream's codegen table and not in its function context ----
    //
    // `vega-expression` has **two** tables: `functionContext`, which is what the reference test
    // reads, and the codegen whitelist, which passes a handful of names straight through to the
    // JavaScript runtime. These four live only in the second, so `knownUnsupported` could be empty
    // and a test could check that it was while all four were missing.

    // `Number.isNaN`, not the global `isNaN`: there is **no coercion**, so `isNaN('x')` is false
    // where the global would say true, and a `datetime()` — an object upstream — is not a number
    // either. The same distinction `isFinite` is already documented for, two lines down from here.
    map.predicate("isNaN") { it is VegaValue.Num && it.value.isNaN() }

    // `btoa` and `atob` are the browser's, and they work in **code units**, not characters: every
    // unit has to fit in a byte, and one that does not is an `InvalidCharacterError` upstream. A
    // diagnostic here, for the same reason every other refusal in this file is one.
    map["btoa"] = ExpressionFunction { args ->
      val text = args.string(0)
      val bytes = IntArray(text.length)
      for (index in text.indices) {
        val unit = text[index].code
        if (unit > 0xFF) {
          throw ExpressionEvaluationException(
            VegaDiagnostic(
              severity = DiagnosticSeverity.ERROR,
              code = DiagnosticCodes.EXPRESSION_UNSUPPORTED_FUNCTION,
              message =
                "btoa() encodes one byte per character and was given U+" +
                  unit.toString(16).uppercase().padStart(4, '0') +
                  ", which does not fit in one; encode the text first",
            )
          )
        }
        bytes[index] = unit
      }
      VegaValue.Str(base64Encode(bytes))
    }
    map["atob"] = ExpressionFunction { args ->
      val decoded =
        base64Decode(args.string(0))
          ?: throw ExpressionEvaluationException(
            VegaDiagnostic(
              severity = DiagnosticSeverity.ERROR,
              code = DiagnosticCodes.EXPRESSION_UNSUPPORTED_FUNCTION,
              message = "atob() was given text that is not base64",
            )
          )
      VegaValue.Str(decoded.map { it.toChar() }.joinToString(""))
    }

    // Percent-encoding over the **UTF-8** bytes, with upstream's unreserved set — which keeps
    // `!~*'()` as well as the usual `-_.` and is why this is not a general-purpose escaper.
    map["encodeURIComponent"] = ExpressionFunction { args ->
      val out = StringBuilder()
      for (byte in args.string(0).encodeToByteArray()) {
        val value = byte.toInt() and 0xFF
        val char = value.toChar()
        if (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in URI_UNRESERVED) {
          out.append(char)
        } else {
          out.append('%').append(value.toString(16).uppercase().padStart(2, '0'))
        }
      }
      VegaValue.Str(out.toString())
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
          if (it.isNullish) "" else JsSemantics.toStringValue(it)
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
      VegaValue.Arr(array.values.sortedWith(ASCENDING))
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
    offsetFunction(map, "timeOffset") { localZone() }
    // `utcOffset` is the twin, and the pair is not decoration: "one day later" is 23 or 25 hours on
    // the two days a local clock changes and always 24 in UTC, so a chart that steps a local axis
    // with the UTC function lands an hour off for half the year.
    offsetFunction(map, "utcOffset") { TimeZone.UTC }

    // `utcOffset` used to be registered a second time here, and the second registration won. The
    // two differed in their **return type** — this one answered a `Num` where `offsetFunction`
    // answers a `Timestamp` — so `isDate(utcOffset(...))` was false where `isDate(timeOffset(...))`
    // was true, for two functions upstream defines as twins. Upstream's `utcOffset` returns a Date.

    /**
     * `timeSequence(unit, start, stop[, step])` — every boundary in a span, `stop` exclusive.
     *
     * The sequence starts at `start` itself rather than at the unit boundary below it, which is
     * upstream's behaviour and worth knowing: a sequence from the middle of a day steps by days
     * from the middle of the day.
     */
    sequenceFunction(map, "timeSequence") { localZone() }
    sequenceFunction(map, "utcSequence") { TimeZone.UTC }

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
        // Converted directly rather than through a CSS string: writing the fractions out as
        // percentages and parsing them back loses precision exactly where it shows, in a colour so
        // dark that a channel is a single digit. Vega's platformer shades its terrain that way, and
        // 85 of its rects came out as pure black against upstream's `rgb(0, 0, 4)`.
        VegaValue.Str(ColorSpaces.fromHsl(ColorSpaces.Hsl(h, s, l)).toCssRgb())
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

    /**
     * `lab(l, a, b)` and `hcl(h, c, l)` — the same two-in-one shape, in the perceptual spaces.
     *
     * Their components are **not** fractions and not degrees-and-percentages, which the `hsl` above
     * invites: a Lab lightness runs 0 to 100, its `a` and `b` run either side of zero with no fixed
     * bound, and an HCL chroma is a radius in those same units. Reading them as fractions gives a
     * colour that is nearly black whatever the input.
     */
    map["lab"] = ExpressionFunction { args ->
      if (args.size >= 3) {
        val colour =
          ColorSpaces.fromLab(
            ColorSpaces.Lab(
              lightness = JsSemantics.toNumber(args.at(0)),
              a = JsSemantics.toNumber(args.at(1)),
              b = JsSemantics.toNumber(args.at(2)),
            )
          )
        VegaValue.Str(colour.toCssRgb())
      } else {
        val colour = SceneColor.parse(args.string(0)) ?: return@ExpressionFunction VegaValue.Null
        val lab = ColorSpaces.toLab(colour)
        VegaValue.Obj(
          linkedMapOf(
            "l" to VegaValue.Num(lab.lightness),
            "a" to VegaValue.Num(lab.a),
            "b" to VegaValue.Num(lab.b),
          )
        )
      }
    }

    map["hcl"] = ExpressionFunction { args ->
      if (args.size >= 3) {
        val colour =
          ColorSpaces.fromHcl(
            ColorSpaces.Hcl(
              hue = JsSemantics.toNumber(args.at(0)),
              chroma = JsSemantics.toNumber(args.at(1)),
              lightness = JsSemantics.toNumber(args.at(2)),
            )
          )
        VegaValue.Str(colour.toCssRgb())
      } else {
        val colour = SceneColor.parse(args.string(0)) ?: return@ExpressionFunction VegaValue.Null
        val hcl = ColorSpaces.toHcl(colour)
        VegaValue.Obj(
          linkedMapOf(
            "h" to VegaValue.Num(hcl.hue),
            "c" to VegaValue.Num(hcl.chroma),
            "l" to VegaValue.Num(hcl.lightness),
          )
        )
      }
    }

    /** `rgb(r, g, b)` — the same shape, and the form every colour prints in. */
    map["rgb"] = ExpressionFunction { args ->
      val colour =
        if (args.size >= 3) {
          SceneColor.parse(
            "rgb(${JsSemantics.toNumber(args.at(0))}, " +
              "${JsSemantics.toNumber(args.at(1))}, " +
              "${JsSemantics.toNumber(args.at(2))})"
          )
        } else {
          SceneColor.parse(args.string(0))
        }
      if (colour == null) VegaValue.Null else VegaValue.Str(colour.toCssRgb())
    }

    // ---- ranges -------------------------------------------------------------
    // `span(array)` is `(+array[array.length-1]) - (+array[0]) || 0` upstream, and the `|| 0` is
    // the whole of it: an empty array, a null, or anything that is not an array answers **0**, not
    // NaN. `span(domain('x'))` over a scale with no data is the common way to reach it, and a NaN
    // there poisons every layout signal computed from it.
    map["span"] = ExpressionFunction { args ->
      val array = args.at(0) as? VegaValue.Arr ?: return@ExpressionFunction VegaValue.Num(0.0)
      if (array.values.isEmpty()) return@ExpressionFunction VegaValue.Num(0.0)
      val width =
        JsSemantics.toNumber(array.values.last()) - JsSemantics.toNumber(array.values.first())
      VegaValue.Num(if (width.isNaN()) 0.0 else width)
    }
    /**
     * `inrange(value, range[, left, right])` — and the two flags are **inclusivity**, not
     * exclusivity, which is the sort of thing only the source settles.
     *
     * `left = left === undefined || left`, so an omitted flag means *inclusive* and `false` means
     * the end is open: `inrange(1, [1, 2], false)` is false. Both flags were being ignored here and
     * every comparison was inclusive, so a brush that deliberately excluded its upper end selected
     * the row sitting exactly on it.
     */
    map["inrange"] = ExpressionFunction { args ->
      val value = args.number(0)
      val range = args.at(1) as? VegaValue.Arr ?: return@ExpressionFunction VegaValue.Bool(false)
      if (range.values.size < 2) return@ExpressionFunction VegaValue.Bool(false)
      val a = JsSemantics.toNumber(range.values.first())
      val b = JsSemantics.toNumber(range.values.last())
      val lo = minOf(a, b)
      val hi = maxOf(a, b)
      fun inclusive(index: Int): Boolean =
        index >= args.size ||
          args.at(index) is VegaValue.Undefined ||
          JsSemantics.truthy(args.at(index))
      val left = if (inclusive(2)) lo <= value else lo < value
      val right = if (inclusive(3)) value <= hi else value < hi
      VegaValue.Bool(left && right)
    }
    /**
     * `merge(a, b, ...)` — one object with every key, later arguments winning.
     *
     * Upstream's is `extend({}, ...)`, which is a **shallow** merge: a nested object is replaced
     * whole rather than merged into. No arguments gives an empty object, which is what makes it
     * safe to fold a list of optional overrides through it.
     */
    map["merge"] = ExpressionFunction { args ->
      val fields = LinkedHashMap<String, VegaValue>()
      for (arg in args) (arg as? VegaValue.Obj)?.let { fields.putAll(it.fields) }
      VegaValue.Obj(fields)
    }

    /**
     * `flush(range, value, threshold, left, right, center)` — which end of a range a value is near.
     *
     * The axis builder has this rule already, for `labelFlush`; this is the same rule as a
     * function, which is how a specification writes its own version of it in an `encode` block. Two
     * parts are easy to get backwards: the ends are **sorted** before the comparison, so a
     * descending range still answers `left` for its low end, and the nearer end wins on `l < r`, so
     * a value exactly between the two ends of an equally short range takes the *far* one.
     */
    map["flush"] = ExpressionFunction { args ->
      val range = (args.at(0) as? VegaValue.Arr)?.values
      if (range == null || range.size < 2) return@ExpressionFunction VegaValue.Null
      val value = JsSemantics.toNumber(args.at(1))
      val threshold = args.number(2)
      val lo = minOf(JsSemantics.toNumber(range.first()), JsSemantics.toNumber(range.last()))
      val hi = maxOf(JsSemantics.toNumber(range.first()), JsSemantics.toNumber(range.last()))
      val fromLow = abs(value - lo)
      val fromHigh = abs(hi - value)
      when {
        fromLow < fromHigh && fromLow <= threshold -> args.at(3)
        fromHigh <= threshold -> args.at(4)
        else -> args.at(5)
      }
    }

    // ---- interval arithmetic ------------------------------------------------
    //
    // The pan and zoom family, which is what an interactive chart's `domainRaw` is written with: a
    // drag publishes `panLinear(domain('x'), delta)` and a wheel `zoomLinear(domain('x'), anchor,
    // factor)`. Each pair lifts the interval into a space where the gesture is *linear*, moves it
    // there and puts it back, which is why there is one per scale family rather than one function
    // taking a scale: panning a log axis by half its width has to multiply, not add.
    panFunction(map, "panLinear", { it }, { it })
    zoomFunction(map, "zoomLinear", { it }, { it })
    // The sign comes from the **first** end of the domain, so a wholly negative log domain pans and
    // zooms as its mirror image rather than producing NaN.
    signedFunction(map, "panLog", ::logLift, ::logGround, pan = true)
    signedFunction(map, "zoomLog", ::logLift, ::logGround, pan = false)
    exponentFunction(map, "panPow", pan = true)
    exponentFunction(map, "zoomPow", pan = false)
    constantFunction(map, "panSymlog", pan = true)
    constantFunction(map, "zoomSymlog", pan = false)

    map["clampRange"] = ExpressionFunction { args ->
      val range = args.at(0) as? VegaValue.Arr ?: return@ExpressionFunction VegaValue.Null
      if (range.values.size < 2) return@ExpressionFunction args.at(0)
      val min = args.number(1)
      val max = args.number(2)
      // Upstream reads the ends as `min` and `max` of the two, so a **descending** range is
      // normalized before anything is clamped. Taking them in written order left one untouched and
      // returned it unchanged, which is what a reversed y-domain feeding pan or zoom hands in.
      val ends =
        JsSemantics.toNumber(range.values.first()) to JsSemantics.toNumber(range.values.last())
      var lo = minOf(ends.first, ends.second)
      var hi = maxOf(ends.first, ends.second)
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
    // `datetime` builds a **date**; `utc` builds a *number*. That is upstream, not an oversight:
    // `datetime(...)` is `new Date(...)` while `utc(...)` is `Date.UTC(...)`, which returns
    // milliseconds — so `isDate(datetime(2020,0,1))` is true and `isDate(utc(2020,0,1))` is false.
    // Both are numbers for arithmetic either way; only the type test can tell them apart.
    /**
     * `datetime(...)` is `new Date(...)`, and the **one-argument** form is not the constructor.
     *
     * `new Date(v)` with a single argument is a *time value*: a number is epoch milliseconds and a
     * string is parsed. Only two or more arguments are year, month, day and the rest. Reading the
     * first argument as a year regardless made `datetime(datum.epochMillis)` — a documented
     * upstream idiom — the year 1.6 trillion, and `toInt()` saturated on the way there so
     * `LocalDate` threw an `IllegalArgumentException` out of a public compile.
     */
    map["datetime"] = ExpressionFunction { args ->
      if (args.size == 1) {
        val single = args.at(0)
        val instant =
          if (single is VegaValue.Str) {
            (DateValues.parse(single, localZone()) as? VegaValue.Num)?.value ?: Double.NaN
          } else {
            JsSemantics.toNumber(single)
          }
        return@ExpressionFunction VegaValue.Timestamp(clipTimeValue(instant))
      }
      VegaValue.Timestamp(construct(args, localZone()))
    }
    map["utc"] = ExpressionFunction { args -> VegaValue.Num(construct(args, TimeZone.UTC)) }
    map["toDate"] = ExpressionFunction { args ->
      DateValues.parse(args.at(0), localZone()) ?: VegaValue.Null
    }
    map["time"] = ExpressionFunction { args ->
      VegaValue.Num(instantOf(args.at(0), localZone()))
    }

    // Month and day-of-week are zero-based, as JavaScript's are; quarter and day-of-year are not.
    dateField(map, "year", { localZone() }) { it.year.toDouble() }
    dateField(map, "quarter", { localZone() }) { ((it.month.number - 1) / 3 + 1).toDouble() }
    dateField(map, "month", { localZone() }) { (it.month.number - 1).toDouble() }
    dateField(map, "date", { localZone() }) { it.day.toDouble() }
    dateField(map, "day", { localZone() }) { (it.date.dayOfWeek.isoDayNumber % 7).toDouble() }
    dateField(map, "dayofyear", { localZone() }) { it.date.dayOfYear.toDouble() }
    // `week` counts **Sundays since the start of the year**, not ISO weeks: `timeWeek.count(year -
    // 1
    // ms, d)`, so the days before the first Sunday are week 0 and a year beginning on a Sunday has
    // its first day in week 1. Reading it as an ISO week number puts the turn of the year one out.
    dateField(map, "week", { localZone() }) { sundaysBefore(it) }

    // The month and weekday **names**, which upstream produces by formatting a date it builds for
    // the purpose: `monthFormat(m)` is `%B` of 1 January 2000 with the month set to `m`, and
    // `dayFormat(d)` is `%A` of 2 January 2000 *plus* `d` days — the 2nd because it was a Sunday,
    // so
    // day 0 is Sunday. Both wrap, so month 12 is January again and day −1 is Saturday, and a
    // non-integer gives the empty string rather than a name for a day that does not exist.
    map["monthFormat"] = ExpressionFunction { args ->
      monthName(args.at(0), abbreviate = false, locale)
    }
    map["monthAbbrevFormat"] = ExpressionFunction { args ->
      monthName(args.at(0), abbreviate = true, locale)
    }
    map["dayFormat"] = ExpressionFunction { args ->
      weekdayName(args.at(0), abbreviate = false, locale)
    }
    map["dayAbbrevFormat"] = ExpressionFunction { args ->
      weekdayName(args.at(0), abbreviate = true, locale)
    }
    dateField(map, "hours", { localZone() }) { it.hour.toDouble() }
    dateField(map, "minutes", { localZone() }) { it.minute.toDouble() }
    dateField(map, "seconds", { localZone() }) { it.second.toDouble() }
    dateField(map, "milliseconds", { localZone() }) { (it.nanosecond / 1_000_000).toDouble() }

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
      // The locale, which this call had in scope for `monthFormat` and every other date function
      // and did not pass here — so the one part of a date a host could not move was its **order**.
      // Under the call's own second argument; see `TimeUnits.specifier`.
      VegaValue.Str(TimeUnits.specifier(units, overrides.orEmpty(), locale))
    }

    map["timeFormat"] = ExpressionFunction { args ->
      formatted(args, localZone(), localZone(), locale)
    }
    map["utcFormat"] = ExpressionFunction { args ->
      formatted(args, localZone(), TimeZone.UTC, locale)
    }

    /**
     * `timeParse(text, specifier)` and `utcParse` — a date read back out of a formatted string.
     *
     * The inverse of `timeFormat`, and the reason it is not simply `toDate`: a specification
     * reading a column of `15/03/2020` has no other way to say which number is the day. Null where
     * the string and the specifier do not match **exactly**, which is d3's rule and stricter than
     * it looks — see [TimeParse].
     */
    map["timeParse"] = ExpressionFunction { args -> parsed(args, localZone(), utc = false) }
    map["utcParse"] = ExpressionFunction { args -> parsed(args, TimeZone.UTC, utc = true) }

    map["timezoneoffset"] = ExpressionFunction { args ->
      // JavaScript reports the offset as minutes *behind* UTC, so a zone east of Greenwich is
      // negative. Reproducing the sign matters more than it looks: specifications subtract it.
      val instant = instantOf(args.at(0), localZone())
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
     * A specification that sizes itself from its container therefore gets nothing here **unless a
     * host says otherwise**, which is the same nothing it gets from upstream outside a browser.
     * Answering with the view's own size by default would be a different chart from the one
     * upstream draws, and the differential fixtures — `container-size` among them — depend on that.
     *
     * A host that *does* know its available width says so, and then `width: "container"` means what
     * it means in a browser. That is the seam an adopter asked for: a responsive width cannot come
     * from the specification, so it comes from the one party that knows it.
     */
    map["containerSize"] = ExpressionFunction {
      VegaValue.Arr(
        listOf(
          containerWidth?.let { VegaValue.Num(it) } ?: VegaValue.Null,
          containerHeight?.let { VegaValue.Num(it) } ?: VegaValue.Null,
        )
      )
    }

    /**
     * `screen()` and `windowSize()` — the same headless answers, for the same reason.
     *
     * `screen()` is `window.screen` where there is a window and `{}` where there is not;
     * `windowSize()` is `[innerWidth, innerHeight]` or `[undefined, undefined]`. Both verified in a
     * `renderer: 'none'` view, which is the configuration every fixture is rendered in. Answering
     * with the *view's* size instead would be a different chart from upstream's, and inventing a
     * screen would be worse: a specification that scales itself to the display would silently size
     * itself to something no reader is looking at.
     */
    map["screen"] = ExpressionFunction { VegaValue.EmptyObject }
    map["windowSize"] = ExpressionFunction {
      VegaValue.Arr(listOf(VegaValue.Null, VegaValue.Null))
    }

    // ---- gesture geometry ---------------------------------------------------
    //
    // Arithmetic over the two touches of a pinch, which is all upstream's are: the event object is
    // read for `touches[0]` and `touches[1]` and nothing else about the browser is involved. They
    // work here on any object shaped like one, which is what an event handler hands them.
    map["pinchDistance"] = ExpressionFunction { args ->
      val (first, second) =
        touchPair(args.at(0)) ?: return@ExpressionFunction VegaValue.Num(Double.NaN)
      VegaValue.Num(hypot(first.first - second.first, first.second - second.second))
    }
    map["pinchAngle"] = ExpressionFunction { args ->
      val (first, second) =
        touchPair(args.at(0)) ?: return@ExpressionFunction VegaValue.Num(Double.NaN)
      VegaValue.Num(atan2(first.second - second.second, first.first - second.first))
    }

    // ---- lasso selection ----------------------------------------------------

    /**
     * `lassoAppend(lasso, x, y[, minDist])` — the point added only if it is far enough from the
     * last.
     *
     * The comparison is strictly greater and the default distance is 5, so a drag that has not
     * moved six units yet returns the **same** array rather than a longer one. That is what keeps a
     * lasso from accumulating a point per mouse event.
     */
    map["lassoAppend"] = ExpressionFunction { args ->
      val lasso = (args.at(0) as? VegaValue.Arr)?.values ?: emptyList()
      val x = args.number(1)
      val y = args.number(2)
      val minimum = args.numberOr(3, 5.0).takeIf { it.isFinite() } ?: 5.0
      val last = (lasso.lastOrNull() as? VegaValue.Arr)?.values
      val point = VegaValue.Arr(listOf(VegaValue.Num(x), VegaValue.Num(y)))
      if (last == null || last.size < 2) return@ExpressionFunction VegaValue.Arr(lasso + point)
      val dx = JsSemantics.toNumber(last[0]) - x
      val dy = JsSemantics.toNumber(last[1]) - y
      if (hypot(dx, dy) > minimum) VegaValue.Arr(lasso + point) else VegaValue.Arr(lasso)
    }

    /**
     * `lassoPath(lasso)` — the outline as an SVG path.
     *
     * Transcribed rather than rewritten, spacing included: upstream builds `"M x,y "` for the first
     * point, `"L x,y "` for the middle ones and `" Z"` for the **last**, so a three-point lasso is
     * `"M 0,0 L 10,0 Z"` with two spaces before the Z and the last point never written. A one-point
     * lasso is `"M 1,2 "` — the first branch and the last both apply, and the first wins.
     */
    map["lassoPath"] = ExpressionFunction { args ->
      val lasso = (args.at(0) as? VegaValue.Arr)?.values ?: emptyList()
      val out = StringBuilder()
      lasso.forEachIndexed { index, entry ->
        val point = (entry as? VegaValue.Arr)?.values ?: return@forEachIndexed
        val x =
          JsSemantics.numberToString(JsSemantics.toNumber(point.getOrNull(0) ?: VegaValue.Null))
        val y =
          JsSemantics.numberToString(JsSemantics.toNumber(point.getOrNull(1) ?: VegaValue.Null))
        out.append(
          when {
            index == 0 -> "M $x,$y "
            index == lasso.size - 1 -> " Z"
            else -> "L $x,$y "
          }
        )
      }
      VegaValue.Str(out.toString())
    }

    /**
     * `pathShape(path)` — the path an SVG path string describes.
     *
     * Upstream returns a *function*: given a drawing context it strokes the parsed path, and given
     * none it hands the string straight back — verified by calling `vega-functions`' own
     * `pathShape('M0,0L10,10')()`, which answers `"M0,0L10,10"`. That string is what this engine
     * returns, because it is the only part of the function a value model can hold and it is what a
     * `shape` channel here takes.
     *
     * A **stated divergence**, and a narrow one: upstream's return value cannot be used from a
     * specification at all. A `symbol`'s `shape` calls `customSymbol(path)`, which does
     * `path.match(...)` and throws on a function; a `path` mark's `path` channel is written to an
     * SVG attribute and stringifies it; and a `shape` **mark** wants a generator carrying
     * `.context()`, which this closure has not got. All three were probed and all three throw. So
     * there is no chart upstream draws that this draws differently — only charts upstream refuses
     * that this draws.
     */
    /**
     * `copy(scale)` — a copy of a scale, which no expression can then do anything with.
     *
     * Upstream returns a scale *function*, and every route out of an expression rejects one:
     * `domain(copy('x'))` answers `[]` because `domain` wants a name, and `isValid(copy('x'))` is
     * **false**. That last one is the only observation a specification can make, and null gives the
     * same answer — so this is upstream's behaviour, not an approximation of it.
     */
    map["copy"] = ExpressionFunction { VegaValue.Null }

    map["pathShape"] = ExpressionFunction { args ->
      (args.at(0) as? VegaValue.Str) ?: VegaValue.Null
    }

    // No `if` here. It has to **short-circuit**, so the evaluator intercepts the call before any
    // argument is evaluated; an entry in this table would take three already-evaluated arguments
    // and could never be reached. One was registered anyway and read as though it were the
    // implementation.

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
    // Strictly, because upstream's `luminance` is a **d3-color call**. This engine's renderer
    // parser takes a superset — `rgb(120.5,30,50)` draws in a browser, so refusing it would lose a
    // mark over a decimal point — but d3 reads that string as nothing, and answering a plausible
    // number where upstream answers NaN is worse than answering nothing. Two acceptances, each
    // right where it is used.
    if (!SceneColor.acceptedByD3(text)) return Double.NaN
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
  /** The two touches of a pinch, as `(x, y)` pairs, or null when the event carries no pair. */
  private fun touchPair(event: VegaValue): Pair<Pair<Double, Double>, Pair<Double, Double>>? {
    val touches = ((event as? VegaValue.Obj)?.fields?.get("touches") as? VegaValue.Arr)?.values
    if (touches == null || touches.size < 2) return null
    fun at(index: Int): Pair<Double, Double> {
      val touch = touches[index] as? VegaValue.Obj ?: return Double.NaN to Double.NaN
      return JsSemantics.toNumber(touch.fields["clientX"] ?: VegaValue.Null) to
        JsSemantics.toNumber(touch.fields["clientY"] ?: VegaValue.Null)
    }
    return at(0) to at(1)
  }

  // ---- pan and zoom -------------------------------------------------------

  /**
   * The two ends of a domain, lifted into the space the gesture is linear in.
   *
   * Upstream reads the **first** and the **last** element rather than the first two, so a
   * three-stop domain pans by its outer ends. A domain of fewer than two elements is upstream's
   * `error('Domain array must not be empty')`; here it comes back untouched, because an expression
   * throwing takes the whole chart with it and a gesture over an empty domain has nothing to do.
   */
  private fun endsOf(value: VegaValue): Pair<Double, Double>? {
    val values = (value as? VegaValue.Arr)?.values ?: return null
    if (values.isEmpty()) return null
    return JsSemantics.toNumber(values.first()) to JsSemantics.toNumber(values.last())
  }

  private fun interval(lo: Double, hi: Double): VegaValue =
    VegaValue.Arr(listOf(VegaValue.Num(lo), VegaValue.Num(hi)))

  /**
   * `pan(domain, delta, lift, ground)`: shift both ends by `delta` spans of the lifted interval.
   */
  private fun panned(
    domain: VegaValue,
    delta: Double,
    lift: (Double) -> Double,
    ground: (Double) -> Double,
  ): VegaValue {
    val (d0, d1) = endsOf(domain) ?: return domain
    val lifted0 = lift(d0)
    val lifted1 = lift(d1)
    val by = (lifted1 - lifted0) * delta
    return interval(ground(lifted0 - by), ground(lifted1 - by))
  }

  /**
   * `zoom(domain, anchor, scale, lift, ground)`: scale both ends about the anchor.
   *
   * A null or absent anchor is the lifted **midpoint**, which is what makes a keyboard zoom with no
   * pointer position behave like one centred on the plot.
   */
  private fun zoomed(
    domain: VegaValue,
    anchor: VegaValue,
    scale: Double,
    lift: (Double) -> Double,
    ground: (Double) -> Double,
  ): VegaValue {
    val (d0, d1) = endsOf(domain) ?: return domain
    val lifted0 = lift(d0)
    val lifted1 = lift(d1)
    val at = if (anchor.isNullish) (lifted0 + lifted1) / 2.0 else lift(JsSemantics.toNumber(anchor))
    return interval(
      ground(at + (lifted0 - at) * scale),
      ground(at + (lifted1 - at) * scale),
    )
  }

  private fun panFunction(
    map: MutableMap<String, ExpressionFunction>,
    name: String,
    lift: (Double) -> Double,
    ground: (Double) -> Double,
  ) {
    map[name] = ExpressionFunction { args -> panned(args.at(0), args.number(1), lift, ground) }
  }

  private fun zoomFunction(
    map: MutableMap<String, ExpressionFunction>,
    name: String,
    lift: (Double) -> Double,
    ground: (Double) -> Double,
  ) {
    map[name] = ExpressionFunction { args ->
      zoomed(args.at(0), args.at(1), args.number(2), lift, ground)
    }
  }

  private fun logLift(sign: Double): (Double) -> Double = { ln(sign * it) }

  private fun logGround(sign: Double): (Double) -> Double = { sign * exp(it) }

  /** The log pair, whose lift depends on the sign of the domain's first end. */
  private fun signedFunction(
    map: MutableMap<String, ExpressionFunction>,
    name: String,
    lift: (Double) -> (Double) -> Double,
    ground: (Double) -> (Double) -> Double,
    pan: Boolean,
  ) {
    map[name] = ExpressionFunction { args ->
      val ends = endsOf(args.at(0)) ?: return@ExpressionFunction args.at(0)
      val sign = kotlin.math.sign(ends.first)
      if (pan) panned(args.at(0), args.number(1), lift(sign), ground(sign))
      else zoomed(args.at(0), args.at(1), args.number(2), lift(sign), ground(sign))
    }
  }

  /** `x < 0 ? -pow(-x, e) : pow(x, e)`, which is what makes a pow scale work across zero. */
  private fun powLift(exponent: Double): (Double) -> Double = {
    if (it < 0.0) -(-it).pow(exponent) else it.pow(exponent)
  }

  private fun exponentFunction(
    map: MutableMap<String, ExpressionFunction>,
    name: String,
    pan: Boolean,
  ) {
    map[name] = ExpressionFunction { args ->
      val exponent = if (pan) args.number(2) else args.number(3)
      val lift = powLift(exponent)
      val ground = powLift(1.0 / exponent)
      if (pan) panned(args.at(0), args.number(1), lift, ground)
      else zoomed(args.at(0), args.at(1), args.number(2), lift, ground)
    }
  }

  private fun constantFunction(
    map: MutableMap<String, ExpressionFunction>,
    name: String,
    pan: Boolean,
  ) {
    map[name] = ExpressionFunction { args ->
      val constant = if (pan) args.number(2) else args.number(3)
      val lift: (Double) -> Double = { kotlin.math.sign(it) * ln1p(abs(it / constant)) }
      val ground: (Double) -> Double = { kotlin.math.sign(it) * expm1(abs(it)) * constant }
      if (pan) panned(args.at(0), args.number(1), lift, ground)
      else zoomed(args.at(0), args.at(1), args.number(2), lift, ground)
    }
  }

  /**
   * `monthFormat`/`monthAbbrevFormat`, over a month index that wraps.
   *
   * The **locale's** names, not the parsing list: upstream gets these by formatting a date it
   * builds for the purpose, so they are whatever `%B` and `%b` would write.
   */
  private fun monthName(value: VegaValue, abbreviate: Boolean, locale: VegaLocale): VegaValue {
    val month = integerIndex(value) ?: return VegaValue.Str("")
    val index = ((month % 12) + 12) % 12
    return VegaValue.Str(if (abbreviate) locale.shortMonths[index] else locale.months[index])
  }

  /** `dayFormat`/`dayAbbrevFormat`, over a weekday index that wraps; 0 is Sunday. */
  private fun weekdayName(value: VegaValue, abbreviate: Boolean, locale: VegaLocale): VegaValue {
    val day = integerIndex(value) ?: return VegaValue.Str("")
    val index = ((day % 7) + 7) % 7
    return VegaValue.Str(if (abbreviate) locale.shortDays[index] else locale.days[index])
  }

  /**
   * An argument upstream tests with `Number.isInteger`, which rejects a string as well as a
   * fraction.
   *
   * `monthFormat("3")` is the empty string upstream, not March: the guard runs before any coercion.
   */
  private fun integerIndex(value: VegaValue): Int? {
    val number = (value as? VegaValue.Num)?.value ?: return null
    if (!number.isFinite() || floor(number) != number) return null
    return number.toInt()
  }

  /**
   * `week`: Sundays since the start of the year, as `d3.timeWeek.count(year - 1ms, d)` counts them.
   *
   * The reference instant is one millisecond *before* the year begins, so a year starting on a
   * Sunday counts that Sunday: 1 January 2017 was a Sunday and is week 1, while 1 January 2021 was
   * a Friday and is week 0.
   */
  private fun sundaysBefore(at: LocalDateTime): Double {
    val yearStart = LocalDate(at.year, 1, 1)
    val firstDay = yearStart.dayOfWeek.isoDayNumber % 7
    val firstSunday = if (firstDay == 0) 0 else 7 - firstDay
    val dayOfYear = at.date.dayOfYear - 1
    return if (dayOfYear < firstSunday) 0.0 else ((dayOfYear - firstSunday) / 7 + 1).toDouble()
  }

  /** `timeOffset` and its UTC twin, which differ only in the calendar they step through. */
  private fun offsetFunction(
    map: MutableMap<String, ExpressionFunction>,
    name: String,
    zone: () -> TimeZone,
  ) {
    map[name] = ExpressionFunction { args ->
      val stepper = stepperFor(args.string(0), zone()) ?: return@ExpressionFunction VegaValue.Null
      val at = JsSemantics.toNumber(args.at(1))
      if (!at.isFinite()) return@ExpressionFunction VegaValue.Null
      // The step defaults to **one**, and it has to be read as absent rather than coerced:
      // `Number()` of a missing argument is 0, which offsets by nothing and returns the date it was
      // handed. That is d3's rule — `step == null ? 1 : Math.floor(step)`.
      val by = args.numberOr(2, 1.0).takeIf { it.isFinite() } ?: 1.0
      VegaValue.Timestamp(stepper.offset(at, floor(by).toInt()))
    }
  }

  /**
   * `timeSequence` and its UTC twin: every unit **boundary** in `[start, stop)`.
   *
   * The boundaries, not the offsets. This walked from `start` itself and said in a comment that
   * doing so was upstream's rule; it is not. d3's `interval.range` *ceils* the start to the next
   * boundary and floors again after every step, so `timeSequence('day', Jan 1 at noon, Jan 4)`
   * gives the two midnights Jan 2 and Jan 3 — where stepping from noon gave three entries, each
   * half a day late. The re-floor matters for the uneven units: a month's step from 31 January
   * lands on 2 or 3 March, and flooring puts it back on the first.
   */
  private fun sequenceFunction(
    map: MutableMap<String, ExpressionFunction>,
    name: String,
    zone: () -> TimeZone,
  ) {
    map[name] = ExpressionFunction { args ->
      val stepper =
        stepperFor(args.string(0), zone()) ?: return@ExpressionFunction VegaValue.Arr(emptyList())
      val start = JsSemantics.toNumber(args.at(1))
      val stop = JsSemantics.toNumber(args.at(2))
      if (!start.isFinite() || !stop.isFinite()) {
        return@ExpressionFunction VegaValue.Arr(emptyList())
      }
      val by = JsSemantics.toNumber(args.at(3)).takeIf { it.isFinite() && it != 0.0 } ?: 1.0
      // d3's `interval.range` refuses a step that is not a positive whole number and answers an
      // empty array. A negative one walked *downwards* here, away from `stop`, and every iteration
      // stayed below it — a hundred thousand timestamps emitted before the guard stopped it, for
      // a call upstream answers `[]` to.
      if (floor(by) < 1) return@ExpressionFunction VegaValue.Arr(emptyList())
      val out = mutableListOf<VegaValue>()
      val floored = stepper.floor(start)
      var at = if (floored < start) stepper.floor(stepper.offset(floored, 1)) else floored
      var guard = 0
      while (at < stop && guard < MAX_SEQUENCE) {
        out.add(VegaValue.Timestamp(at))
        at = stepper.floor(stepper.offset(at, by.toInt()))
        guard++
      }
      VegaValue.Arr(out)
    }
  }

  /**
   * A calendar accessor and its `utc` twin, which differ in **one** of the two zones they use.
   *
   * A bare string argument is parsed in local time by both — `Date.parse` has no other mode — and
   * only the calendar the result is read in changes. Keeping the two apart is what makes
   * `utchours("2026-05-20T00:30")` agree with a browser in Amsterdam: parsed at 00:30 local, read
   * as 22:00 the day before in UTC.
   */
  private fun dateField(
    map: MutableMap<String, ExpressionFunction>,
    name: String,
    zone: () -> TimeZone,
    read: (LocalDateTime) -> Double,
  ) {
    map[name] = ExpressionFunction { args -> fieldOf(args.at(0), zone(), zone(), read) }
    map["utc$name"] = ExpressionFunction { args ->
      fieldOf(args.at(0), zone(), TimeZone.UTC, read)
    }
  }

  private fun fieldOf(
    value: VegaValue,
    parseIn: TimeZone,
    readIn: TimeZone,
    read: (LocalDateTime) -> Double,
  ): VegaValue {
    val instant = instantOf(value, parseIn)
    if (instant.isNaN()) return VegaValue.Num(Double.NaN)
    return VegaValue.Num(read(TimeFormat.at(instant, readIn)))
  }

  private fun parsed(args: List<VegaValue>, zone: TimeZone, utc: Boolean): VegaValue {
    val text = args.at(0)
    // Upstream's wrapper answers the *string* `"null"` for a null input, before any parsing
    // happens — and its test is `value === null`, so an **undefined** input is not caught by it and
    // goes on to the parser, which reads `"undefined"` and fails. `timeParse(datum.nul, '%Y')` is
    // therefore the string `"null"` and `timeParse(datum.missing, '%Y')` is null, which is the one
    // place in this file the two kinds of nothing must not be treated alike. Probed.
    if (text is VegaValue.Null) return VegaValue.Str("null")
    val specifier = args.string(1)
    val millis = TimeParse.parse(text.asString(), specifier, zone, utc) ?: return VegaValue.Null
    return VegaValue.Timestamp(millis)
  }

  private fun formatted(
    args: List<VegaValue>,
    parseIn: TimeZone,
    readIn: TimeZone,
    locale: VegaLocale,
  ): VegaValue {
    val instant = instantOf(args.at(0), parseIn)
    if (instant.isNaN()) return VegaValue.Str("Invalid Date")
    return VegaValue.Str(TimeFormat.format(instant, args.string(1), readIn, locale))
  }

  /**
   * Epoch milliseconds for a value that is already an instant, or that reads as an ISO date.
   *
   * [parseIn] is the zone a naive string is read in, which is local time in a browser and therefore
   * whatever the host said local means. A string carrying its own offset, and a value that is
   * already a number, ignore it.
   */
  private fun instantOf(value: VegaValue, parseIn: TimeZone): Double =
    (DateValues.parse(value, parseIn) as? VegaValue.Num)?.value ?: JsSemantics.toNumber(value)

  /**
   * `datetime(year, month, ...)`, where every component past the year is optional.
   *
   * Upstream is `new Date(...args)` and this is [JsDate.make], which is that constructor written
   * out: the rollover, the two-digit-year rule and the NaN-rather-than-throw are all its rules
   * rather than choices made here. It had been open-coded, and got the second of those wrong —
   * `datetime(99, 1, 1)` is February **1999** to every Vega renderer, and was the year 99 here.
   */
  private fun construct(args: List<VegaValue>, zone: TimeZone): Double =
    JsDate.make(
      year = args.number(0),
      month = args.numberOr(1, 0.0),
      date = args.numberOr(2, 1.0),
      hours = args.numberOr(3, 0.0),
      minutes = args.numberOr(4, 0.0),
      seconds = args.numberOr(5, 0.0),
      millis = args.numberOr(6, 0.0),
      zone = zone,
    )

  private fun List<VegaValue>.numberOr(index: Int, fallback: Double): Double =
    if (index < size && at(index) != VegaValue.Null) JsSemantics.toNumber(at(index)) else fallback

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

  /**
   * The argument as a **pattern**, whether it was written as one or as a string.
   *
   * Upstream's `test` compiles to `RegExp(a).test(b)`, and `RegExp` of a string compiles that
   * string as a pattern — so a bare `test('far', …)` works there and works here.
   */
  private fun List<VegaValue>.pattern(index: Int): VegaValue.Pattern =
    when (val value = at(index)) {
      is VegaValue.Pattern -> value
      else -> compilePattern(JsSemantics.toStringValue(value), "")
    }

  /**
   * A pattern, or a diagnostic saying what the syntax error was.
   *
   * `VegaValue.Pattern` compiles its regular expression in its **initializer**, and a pattern that
   * does not parse raises the engine's own syntax error there — which is not an
   * `ExpressionEvaluationException` and so escapes every catch site between here and the host. The
   * KDoc on that field explains that an ECMA-262 engine was adopted precisely so an ordinary
   * browser pattern would stop throwing on the JVM, and then `regexp('(')` threw anyway. A
   * specification is data, often pasted data, and `regexp(query, 'i')` over a text field a reader
   * types into reaches a half-typed pattern on nearly every keystroke.
   */
  private fun compilePattern(source: String, flags: String): VegaValue.Pattern =
    try {
      VegaValue.Pattern(source, flags)
    } catch (failure: Throwable) {
      throw ExpressionEvaluationException(
        VegaDiagnostic(
          severity = DiagnosticSeverity.ERROR,
          code = DiagnosticCodes.EXPRESSION_UNSUPPORTED_FUNCTION,
          message =
            "/$source/$flags is not a regular expression this engine can read" +
              (failure.message?.let { ": $it" } ?: ""),
          cause = failure,
        )
      )
    }

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
    // `parseFloat` skips *StrWhiteSpace*, the same set `trim` uses.
    val trimmed = text.ecmaTrim()
    // `Infinity` is a *StrDecimalLiteral*, so `parseFloat('Infinity')` is Infinity rather than NaN
    // — with an optional sign, and only as a leading token, so `Infinityx` is still Infinity and
    // `xInfinity` is NaN. `parseInt` has no such rule: its digits are digits.
    if (allowDecimal) {
      val signed = trimmed.startsWith("+") || trimmed.startsWith("-")
      val body = if (signed) trimmed.substring(1) else trimmed
      if (body.startsWith("Infinity")) {
        return if (trimmed.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
      }
    }
    val pattern = if (allowDecimal) LEADING_FLOAT else LEADING_INT
    val match = pattern.find(trimmed) ?: return Double.NaN
    return match.value.toDoubleOrNull() ?: Double.NaN
  }

  /**
   * A digit's value in base 36, or -1: `Character.digit` without the JVM.
   *
   * ASCII only, which is `parseInt`'s rule as well — JavaScript reads `٣` (Arabic-Indic three) as
   * nothing, and so does this. `Character.digit` would have accepted it, so the narrower answer is
   * also the more faithful one.
   */
  private fun digitValue(char: Char): Int =
    when (char) {
      in '0'..'9' -> char - '0'
      in 'a'..'z' -> char - 'a' + 10
      in 'A'..'Z' -> char - 'A' + 10
      else -> -1
    }

  /**
   * `parseInt(string, radix)`, whose radix rule is the part worth writing down.
   *
   * A radix of 0 — which is what an omitted one is — means the prefix decides: `0x`/`0X` is 16 and
   * anything else is 10. A radix of 16 also strips the prefix; every other radix does not. Outside
   * 2..36 the answer is NaN.
   */
  private fun parseInteger(text: String, radix: Int): Double {
    val trimmed = text.ecmaTrim()
    val signLength = if (trimmed.startsWith("+") || trimmed.startsWith("-")) 1 else 0
    val negative = trimmed.startsWith("-")
    val unsigned = trimmed.substring(signLength)
    val hexPrefixed = unsigned.startsWith("0x") || unsigned.startsWith("0X")
    val effective =
      when {
        radix == 0 -> if (hexPrefixed) 16 else 10
        radix in 2..36 -> radix
        else -> return Double.NaN
      }
    if (effective == 10) return parseLeadingNumber(trimmed, allowDecimal = false)
    val body = if (effective == 16 && hexPrefixed) unsigned.substring(2) else unsigned
    val digits = body.takeWhile { digitValue(it) in 0 until effective }
    if (digits.isEmpty()) return Double.NaN
    val magnitude = digits.toLongOrNull(effective)?.toDouble() ?: return Double.NaN
    return if (negative) -magnitude else magnitude
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

  /** `encodeURIComponent`'s unreserved punctuation, beyond the letters and digits. */
  private const val URI_UNRESERVED: String = "-_.!~*'()"

  private const val BASE64_ALPHABET: String =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

  /** `btoa`: three bytes to four characters, the last group padded with `=`. */
  private fun base64Encode(bytes: IntArray): String {
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var index = 0
    while (index < bytes.size) {
      val remaining = bytes.size - index
      val a = bytes[index]
      val b = if (remaining > 1) bytes[index + 1] else 0
      val c = if (remaining > 2) bytes[index + 2] else 0
      val group = (a shl 16) or (b shl 8) or c
      out.append(BASE64_ALPHABET[(group shr 18) and 0x3F])
      out.append(BASE64_ALPHABET[(group shr 12) and 0x3F])
      out.append(if (remaining > 1) BASE64_ALPHABET[(group shr 6) and 0x3F] else '=')
      out.append(if (remaining > 2) BASE64_ALPHABET[group and 0x3F] else '=')
      index += 3
    }
    return out.toString()
  }

  /**
   * `atob`: the inverse, and **null** rather than an exception when the text is not base64.
   *
   * The browser's own leniency, reproduced: ASCII whitespace is skipped anywhere, and padding is
   * optional as long as the remaining length is not one character past a group.
   */
  private fun base64Decode(text: String): IntArray? {
    val clean = text.filterNot {
      it == ' ' || it == '\t' || it == '\n' || it == '\r' || it == '\u000C'
    }
    val body = clean.trimEnd('=')
    if (clean.length - body.length > 2) return null
    if (body.length % 4 == 1) return null
    if (body.any { BASE64_ALPHABET.indexOf(it) < 0 }) return null
    val out = ArrayList<Int>(body.length / 4 * 3)
    var index = 0
    while (index < body.length) {
      val remaining = body.length - index
      var group = 0
      for (offset in 0 until 4) {
        val digit = if (offset < remaining) BASE64_ALPHABET.indexOf(body[index + offset]) else 0
        group = (group shl 6) or digit
      }
      out.add((group shr 16) and 0xFF)
      if (remaining > 2) out.add((group shr 8) and 0xFF)
      if (remaining > 3) out.add(group and 0xFF)
      index += 4
    }
    return out.toIntArray()
  }

  private val LEADING_FLOAT = Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
  private val LEADING_INT = Regex("^[+-]?\\d+")

  /**
   * `vega-util`'s `ascending`, transcribed, which is what `sort()` sorts with.
   *
   * ```js
   * (u < v || u == null) && v != null ? -1
   *   : (u > v || v == null) && u != null ? 1
   *   : (v = v instanceof Date ? +v : v, u = u instanceof Date ? +u : u) !== u && v === v ? -1
   *   : v !== v && u === u ? 1 : 0
   * ```
   *
   * Three things fall out of it that a hand-written "numbers, then strings" rule got wrong. The `<`
   * is **JavaScript's relational comparison**, so two strings compare lexicographically and
   * everything else compares numerically — `sort([10, 9, '10', '9'])` is `9,9,10,10`, and a
   * `datetime()` sorts by its instant rather than by the digits of its epoch, which is where an
   * array of dates spanning a digit-count boundary came out shuffled. Absent values sort **first**,
   * and NaN sorts after them and before everything else. And a pair the comparison cannot order — a
   * string against a number — compares equal, which leaves them in the order they arrived, because
   * `sortedWith` is stable and `Array.prototype.sort` is too.
   */
  private val ASCENDING =
    Comparator<VegaValue> { u, v ->
      val uAbsent = u.isNullish
      val vAbsent = v.isNullish
      val ordering = JsSemantics.compare(u, v)
      val uNaN = u.asNumberOrNull()?.isNaN() == true
      val vNaN = v.asNumberOrNull()?.isNaN() == true
      when {
        ((ordering != null && ordering < 0) || uAbsent) && !vAbsent -> -1
        ((ordering != null && ordering > 0) || vAbsent) && !uAbsent -> 1
        uNaN && !vNaN -> -1
        vNaN && !uNaN -> 1
        else -> 0
      }
    }
}
