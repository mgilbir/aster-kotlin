package dev.aster.vega.runtime.scale

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.expression.NumberFormat
import dev.aster.vega.model.Decimals
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.model.roundHalfUp
import dev.aster.vega.model.withTypographicMinus
import dev.aster.vega.scene.ColorSpaces
import dev.aster.vega.scene.SceneColor
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.expm1
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Scale implementations, ported from d3-scale, which is what upstream Vega uses.
 *
 * The arithmetic is reproduced exactly rather than approximated: a band scale's step and padding
 * calculation, in particular, decides the position and width of every bar in every chart, so an
 * "obviously equivalent" rearrangement of the formula shows up as a differential-test failure.
 *
 * Scales are immutable and pure. Constructing one resolves its domain and range up front, so
 * applying it allocates nothing.
 */
public sealed interface VegaScale {
  public val name: String

  /**
   * The boundaries the specification's `bins` named, or null when it named none.
   *
   * They are the scale's tick values wherever they exist — upstream's `tickValues` short-circuits
   * to them — and their presence is also what drops the `zero` a linear scale would otherwise
   * include.
   */
  public val bins: List<Double>?
    get() = null

  /** Maps a data value into range space. Returns [VegaValue.Null] for an unmappable value. */
  public fun scale(value: VegaValue): VegaValue
}

/**
 * A scale that can be read backwards, from a position to a data value.
 *
 * Only the continuous ones can: a band or ordinal scale maps many positions to one value and an
 * inverse would have to pick. `invert()` in an expression is how a brush turns pixels into a domain
 * range.
 */
public sealed interface InvertibleScale : VegaScale {
  public fun invert(position: Double): Double
}

/** A scale with a numeric range, usable for positional encoding. */
public sealed interface PositionScale : VegaScale {
  /** Range-space position for [value], or `NaN` when it cannot be mapped. */
  public fun position(value: VegaValue): Double

  /** Width of a band, or 0 for a continuous scale. */
  public val bandwidth: Double

  /**
   * The range in range space, low index first as written in the specification.
   *
   * Exposed because an axis draws its domain line between the range endpoints rather than across
   * the plotting area — the two coincide at the top level but not inside a group.
   */
  public val range: List<Double>
}

/**
 * `identity`: the value itself, coerced to a number.
 *
 * The one scale that maps nothing. It exists so a specification can name a scale where a channel
 * expects one and still hand over coordinates it has already worked out — a chart drawing a legend
 * by hand, or a layout computed in a `formula`. Upstream registers it beside the others, which is
 * why a `"scale": "identity"` reference has to resolve rather than report.
 *
 * `domain` and `range` are both `[0, 1]` and are never consulted, which is d3's answer as well: the
 * pair exists so `domain('name')` returns *something*, not because the scale uses them. A value
 * that is not a number maps to **nothing** rather than to `NaN` — d3's `unknown`, which for a mark
 * means a channel left unset and a mark that does not draw.
 */
public class IdentityScale(
  override val name: String,
  /**
   * The declared domain, which an identity scale **does** have and which is also its range.
   *
   * ```js
   * scale.domain = scale.range = function(_) { … };
   * …
   * return linearish(scale);
   * ```
   *
   * d3 gives the two the same array and then makes the scale `linearish`, so an identity scale
   * generates ticks over its domain exactly as a linear one does — and an axis drawn against one is
   * ticked and labelled like any other. This carried `[0, 1]` whatever the specification said,
   * which was harmless while nothing asked and wrong the moment an axis did: no domain, no ticks,
   * no labels, an empty guide beside a chart upstream draws fourteen labels on.
   */
  public val domain: List<Double> = listOf(0.0, 1.0),
) : VegaScale {
  /** The same numbers as [domain]: d3 assigns the one accessor to both names. */
  public val range: List<Double>
    get() = domain

  /**
   * A tick's label, by the same rule a linear scale uses, because `linearish` is what d3 makes it.
   *
   * The precision comes from the step between ticks rather than from the values, so a domain walked
   * in fives is labelled in whole numbers.
   */
  public fun formatTick(
    value: Double,
    count: Int = LinearScale.DEFAULT_TICK_COUNT,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): String {
    val step = Ticks.stepFrom(Ticks.tickIncrement(domain.first(), domain.last(), count))
    val precision = if (step.isFinite()) Ticks.precisionForStep(step) else 0
    return formatTickLabel(value, precision, locale)
  }

  override fun scale(value: VegaValue): VegaValue {
    val number = scaleNumber(value)
    return if (number.isNaN()) VegaValue.Undefined else VegaValue.Num(number)
  }
}

/**
 * Continuous linear scale.
 *
 * @param domain at least two values; more than two makes it piecewise.
 * @param clamp when true, out-of-domain inputs clamp to the range ends instead of extrapolating.
 */
public class LinearScale(
  override val name: String,
  public val domain: List<Double>,
  override val range: List<Double>,
  public val clamp: Boolean = false,
  /**
   * Rounds every *output* to a whole unit, so a bar's edge lands on a pixel.
   *
   * Not the same `round` a band scale has, which rounds the step and the band width and so changes
   * where every band starts. Here nothing about the scale changes: upstream cannot call `round` on
   * a continuous d3 scale — there is no such method — so it swaps the range interpolator for
   * `interpolateRound` and the arithmetic is untouched right up to the last step. `invert` is
   * unaffected for the same reason, and reads back the unrounded value.
   */
  public val round: Boolean = false,
  /** See [VegaScale.bins]; only a continuous scale is given them in practice. */
  override val bins: List<Double>? = null,
) : PositionScale, InvertibleScale {

  init {
    // **A domain of fewer than two values is still a scale**, which this used to refuse. d3 builds
    // one, places nothing through it and answers `NaN`: `domain([])` scales 7 to `NaN`, and so does
    // `domain([5])`. So upstream has a scale for a chart to name and every `scale()` naming it
    // answers `NaN`, where refusing to build one made the scale **absent** — the expression then
    // reported an undefined scale, and a chart lost its marks to a diagnostic about something it
    // had
    // not got wrong. The `NaN` itself comes from [unrounded].
    require(range.size >= 2) { "A linear scale needs at least two range values, got $range" }
  }

  /**
   * How many stops actually take part: `min(domain, range)`, which is d3's rule.
   *
   * A domain and a range of different lengths used to **throw** here, and upstream simply uses the
   * shorter — `domain([-10, 0]).range([0, 1, 2])` maps -5 to 0.5, ignoring the third stop. Refusing
   * it took the whole chart down for a specification upstream draws, and a range with a spare stop
   * is an ordinary thing to write while editing one.
   */
  private val stops: Int = minOf(domain.size, range.size)

  override val bandwidth: Double
    get() = 0.0

  override fun position(value: VegaValue): Double = apply(scaleNumber(value))

  /**
   * A continuous scale of something that is not a number is **not a number**, not nothing.
   *
   * The distinction only shows in arithmetic, and there it is the whole answer: JavaScript reads
   * `null` as zero and propagates `NaN`, so `abs(scale(x, datum.missing) - scale(x, datum.lo))` is
   * a real zero on one reading and no answer at all on the other. Vega-Lite writes exactly that
   * expression to decide whether a bar is too thin to see, and a pre-binned column has no `_end` to
   * give it — so a bar came out a quarter of a unit narrow and shifted along.
   */
  override fun scale(value: VegaValue): VegaValue {
    // **The guard is on the input, not on the answer.** d3 tests `x == null || isNaN(x = +x)` and
    // then does the arithmetic whatever it comes to — so a log scale asked for `-5` answers `NaN`,
    // a perfectly good number having no logarithm, while the same scale asked for a *word* answers
    // `undefined`. Reading the answer instead conflates the two and reports nothing for both.
    //
    // `""` separates them: it coerces to `0`, passes the guard, and its logarithm is `NaN`. Probed.
    val x = scaleNumber(value)
    return if (x.isNaN()) VegaValue.Undefined else VegaValue.Num(apply(x))
  }

  public fun apply(x: Double): Double = if (round) roundHalfUp(unrounded(x)) else unrounded(x)

  private fun unrounded(x: Double): Double {
    if (x.isNaN()) return Double.NaN
    // Fewer than two stops is d3's `NaN`, and there it falls out of the arithmetic rather than
    // being tested for: `normalize` reads `domain[0]` and `domain[1]`, and an absent end is
    // `undefined`, which poisons the subtraction. Written as a test here because Kotlin throws on
    // the index instead of answering `undefined`.
    if (stops < 2) return Double.NaN
    // A zero-extent domain has no gradient; d3 returns the range midpoint rather than dividing by
    // 0.
    val d0 = domain[0]
    val d1 = domain[stops - 1]
    if (d0 == d1) return (range[0] + range[stops - 1]) / 2.0

    val input = if (clamp) x.coerceIn(minOf(d0, d1), maxOf(d0, d1)) else x
    if (stops == 2) return interpolate(d0, d1, range[0], range[stops - 1], input)

    // Piecewise: find the segment containing the input, then interpolate within it.
    // The segment is upstream's `bisect(domain, x, 1, j) - 1`, which is a **right** bisection: a
    // value sitting exactly on an interior stop belongs to the segment that *starts* there, not to
    // the one that ends there. The two agree everywhere except where a stop is repeated — and that
    // is not a curiosity, it is what `domainMid` builds whenever the middle lands on an end of the
    // data: a diverging bar chart over values that are all positive has the domain `[0, 0, 95.4]`,
    // and zero belongs to the second segment. Read off d3, which answers 42 there where the first
    // segment's midpoint is 21.
    val ascending = d1 > d0
    var segment = 0
    while (segment < stops - 2) {
      val upper = domain[segment + 1]
      val past = if (ascending) input >= upper else input <= upper
      if (!past) break
      segment++
    }
    return interpolate(
      domain[segment],
      domain[segment + 1],
      range[segment],
      range[segment + 1],
      input,
    )
  }

  /**
   * d3's interpolation, in d3's arithmetic.
   *
   * `r0 * (1 - t) + r1 * t`, not the algebraically equal `r0 + t * (r1 - r0)`. The two differ in
   * the last bits of a double, and that is not academic here: value 33 of a `[0, 100]` domain over
   * a 150-unit range is 100.5 written the second way and 100.49999999999999 written d3's, and an
   * axis rounds its ticks to whole pixels — so the tick lands a pixel away, along with its
   * gridline. Found by a fixture whose explicit tick values happened to fall on the boundary; every
   * generated tick before it had landed clear of one.
   */
  private fun interpolate(d0: Double, d1: Double, r0: Double, r1: Double, x: Double): Double {
    if (d0 == d1) return (r0 + r1) / 2.0
    val t = (x - d0) / (d1 - d0)
    return r0 * (1.0 - t) + r1 * t
  }

  /** The data value that maps to range position [y]. Only defined for a two-point domain. */
  override fun invert(position: Double): Double {
    // ```js
    // scale.invert = function(y) {
    //   return clamp(untransform((input || (input = piecewise(range, domain.map(transform),
    // interpolateNumber)))(y)));
    // };
    // ```
    //
    // The same piecewise machinery with the two swapped, so a scale of **more than two stops**
    // inverts as readily as it maps — which is what a brush or a tooltip over a diverging axis
    // needs. This answered `NaN` for every such scale, so a pointer over one read nothing at all.
    if (stops > 2) return invertPiecewise(position)
    if (stops != 2) return Double.NaN
    val r0 = range[0]
    val r1 = range[1]
    if (r0 == r1) return Double.NaN
    // `clamp` works **both ways** in d3, and this only clamped one of them. Inverting a position
    // outside the range — which is every pointer event past the end of an axis — returned a value
    // outside the domain: with domain [0, 1] and range [10, 20], `invert(30)` read 2 where upstream
    // reads 1. A brush or a tooltip built on that selects data the scale says is not there.
    val clamped = if (clamp) position.coerceIn(minOf(r0, r1), maxOf(r0, r1)) else position
    val t = (clamped - r0) / (r1 - r0)
    if (stops < 2) return Double.NaN
    return domain[0] + t * (domain[1] - domain[0])
  }

  /** [invert] over a domain of more than two stops; see there for why it is the forward walk. */
  private fun invertPiecewise(position: Double): Double {
    val r0 = range[0]
    val rn = range[stops - 1]
    if (r0 == rn) return Double.NaN
    val input = if (clamp) position.coerceIn(minOf(r0, rn), maxOf(r0, rn)) else position
    // A **right** bisection over the range, as the forward walk does over the domain: a position
    // exactly on an interior stop belongs to the segment that starts there.
    val ascending = rn > r0
    var segment = 0
    while (segment < stops - 2) {
      val upper = range[segment + 1]
      val past = if (ascending) input >= upper else input <= upper
      if (!past) break
      segment++
    }
    val from = range[segment]
    val to = range[segment + 1]
    if (from == to) return domain[segment]
    val t = (input - from) / (to - from)
    return domain[segment] * (1 - t) + domain[segment + 1] * t
  }

  /**
   * The domain's ends as d3 reads them, which is **`undefined` for an end that is not there**.
   *
   * `domain[0]` on an empty list is `undefined` in JavaScript and an exception in Kotlin, and every
   * tick rule downstream is written to take the `NaN` that `undefined` coerces to: `ticks(NaN, NaN,
   * n)` is `[]`, which is exactly what upstream draws over an empty domain. A single-valued domain
   * needs no special case either — `start == stop` gives `[start]`, and upstream draws that one
   * tick.
   */
  private val domainStart: Double
    get() = domain.firstOrNull() ?: Double.NaN

  private val domainEnd: Double
    get() = domain.lastOrNull() ?: Double.NaN

  public fun ticks(count: Int = DEFAULT_TICK_COUNT): List<Double> =
    Ticks.ticks(domainStart, domainEnd, count)

  /** Default label text for a tick, matching Vega's digits-from-step behaviour. */
  public fun formatTick(
    value: Double,
    count: Int = DEFAULT_TICK_COUNT,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): String {
    val step = Ticks.stepFrom(Ticks.tickIncrement(domainStart, domainEnd, count))
    val precision = if (step.isFinite()) Ticks.precisionForStep(step) else DEGENERATE_PRECISION
    return formatTickLabel(value, precision, locale)
  }

  /** Labels aligned with [ticks], so a scale can suppress some of them. */
  public fun tickLabels(
    count: Int = DEFAULT_TICK_COUNT,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): List<String> = ticks(count).map { formatTick(it, count, locale) }

  public companion object {
    public const val DEFAULT_TICK_COUNT: Int = 10

    /**
     * Builds a linear scale from a data extent, applying Vega's `zero` and `nice` in that order.
     *
     * The order matters and is not obvious: `zero` widens the domain first, then `nice` rounds the
     * widened domain. Verified against upstream — `[19, 91]` with both flags gives `[0, 100]`,
     * while nice-then-zero would give `[0, 100]` here but differs on other inputs.
     */
    public fun fromExtent(
      name: String,
      extent: ClosedFloatingPointRange<Double>?,
      range: List<Double>,
      zero: Boolean = true,
      nice: Boolean = false,
      niceCount: Int = DEFAULT_TICK_COUNT,
      clamp: Boolean = false,
    ): LinearScale {
      var lo = extent?.start ?: 0.0
      var hi = extent?.endInclusive ?: 1.0
      if (!lo.isFinite() || !hi.isFinite()) {
        lo = 0.0
        hi = 1.0
      }
      if (zero) {
        lo = minOf(lo, 0.0)
        hi = maxOf(hi, 0.0)
      }
      var domain = listOf(lo, hi)
      if (nice) domain = Ticks.nice(domain, niceCount)
      return LinearScale(name, domain, range, clamp)
    }
  }
}

/**
 * What a scale reads a value as, which is **one line of d3** and two rules in it:
 * ```js
 * function scale(x) {
 *   return x == null || isNaN(x = +x) ? unknown : …;
 * }
 * ```
 *
 * Nothing is caught **before** the coercion — `x == null` is the loose test, so a null and an
 * undefined never reach `+` at all and answer `unknown` — and everything else goes **through** `+`,
 * which is `Number(x)` and not a parse. So the empty string is `0`, an empty array is `0`, a flag
 * is `1`, and only something that genuinely has no number in it reaches `NaN`.
 *
 * The empty cell is the one that matters, a column read from a CSV being full of them: upstream
 * places it at zero. This engine read strings with `toDoubleOrNull`, a *parse*, which rejects the
 * empty string — so the row was dropped from the chart instead.
 *
 * Returns `NaN` for the cases d3 answers `unknown` for, which the callers turn back into nothing.
 *
 * **`quantize` and `threshold` do not share this line**: theirs is `x != null && x <= x ? … :
 * unknown`, which never coerces at all and bisects with whatever it was handed. That is a different
 * rule and it is recorded as its own question rather than assumed to be this one.
 */
internal fun scaleNumber(value: VegaValue): Double =
  when (value) {
    // `x == null` is loose, so it is both of these and nothing else.
    is VegaValue.Null,
    is VegaValue.Undefined -> Double.NaN
    else -> JsSemantics.toNumber(value)
  }

/**
 * How a discrete scale's index keys a domain value, which is d3's `InternMap` and not equality.
 *
 * ```js
 * function keyof(value) {
 *   return value !== null && typeof value === "object" ? value.valueOf() : value;
 * }
 * ```
 *
 * A `Map` then holds those keys, so the rule is **SameValueZero**: two `NaN`s are one key, and `+0`
 * and `-0` are one key — where a `Double`'s own `equals` agrees about the first and disagrees about
 * the second. `keyof` is the other half: an object is interned by its `valueOf`, so a date and the
 * number of milliseconds it stands for are the *same* key and a scale looked up with either finds
 * the same band.
 *
 * This is deliberately not `asString`. A domain of text is what this engine used to hold, and it
 * answers a different question: `scale("1001")` finds the band of the **number** `1001` under a
 * text key and finds nothing upstream, because the index holds the number and the word is not it.
 */
internal fun internKey(value: VegaValue): VegaValue =
  when (value) {
    is VegaValue.Timestamp -> VegaValue.Num(value.epochMillis)
    // `+0` and `-0` are one key in a `Map`, and two in Kotlin: `(-0.0).equals(0.0)` is false.
    is VegaValue.Num -> if (value.value == 0.0) VegaValue.Num(0.0) else value
    else -> value
  }

/**
 * Band scale: a discrete domain mapped to contiguous, equal-width bands.
 *
 * The step and padding arithmetic follows d3-scaleBand exactly, including `align` controlling where
 * leftover space goes. Positions are computed once at construction.
 */
public class BandScale(
  override val name: String,
  public val domain: List<VegaValue>,
  override val range: List<Double>,
  public val paddingInner: Double = 0.0,
  public val paddingOuter: Double = 0.0,
  public val align: Double = 0.5,
  public val round: Boolean = false,
) : PositionScale {

  init {
    require(range.size >= 2) { "A band scale needs a two-value range, got $range" }
  }

  private val positions: Map<VegaValue, Double>
  override val bandwidth: Double
  public val step: Double
  /** Range start after outer padding and alignment, i.e. the first band's position. */
  public val start: Double

  init {
    val reverse = range.last() < range.first()
    val lo = if (reverse) range.last() else range.first()
    val hi = if (reverse) range.first() else range.last()
    val n = domain.size

    val inner = paddingInner.coerceIn(0.0, 1.0)
    val outer = paddingOuter.coerceAtLeast(0.0)
    var computedStep = (hi - lo) / maxOf(1.0, n - inner + outer * 2.0)
    if (round) computedStep = floor(computedStep)

    var computedStart = lo + (hi - lo - computedStep * (n - inner)) * align.coerceIn(0.0, 1.0)
    var computedBand = computedStep * (1.0 - inner)
    if (round) {
      computedStart = computedStart.roundToInt().toDouble()
      computedBand = computedBand.roundToInt().toDouble()
    }

    step = computedStep
    start = computedStart
    bandwidth = computedBand

    val ordered = if (reverse) domain.indices.reversed().toList() else domain.indices.toList()
    val map = LinkedHashMap<VegaValue, Double>(n)
    ordered.forEachIndexed { slot, domainIndex ->
      map[internKey(domain[domainIndex])] = computedStart + computedStep * slot
    }
    positions = map
  }

  override fun position(value: VegaValue): Double = positions[internKey(value)] ?: Double.NaN

  override fun scale(value: VegaValue): VegaValue {
    val result = position(value)
    // **Nothing, and not a null**, for a value the index does not hold. d3's band scale is a `Map`
    // lookup and nothing more — `index.get(d)` — so a miss answers `undefined`, which is what an
    // expression asking `'' + scale('x', v)` prints and what a mark encoding a property from it
    // leaves absent. A null reads as the word `null` in the first case and as a written property in
    // the second, and neither is what upstream draws.
    return if (result.isNaN()) VegaValue.Undefined else VegaValue.Num(result)
  }

  /**
   * Which of the bands a stretch of the range covers — `scaleBand.invertRange`.
   *
   * A band scale has no continuous inverse, but it does have an answer: the domain values whose
   * bands the given pixels fall in. A position in the **gap** between two bands belongs to neither,
   * which is what the bandwidth check drops, and a stretch outside the range answers with nothing.
   */
  public fun invertRange(from: Double, to: Double): List<VegaValue>? {
    if (from.isNaN() || to.isNaN() || domain.isEmpty()) return null
    val reverse = range.last() < range.first()
    val starts = domain.map { positions[it] ?: Double.NaN }
    val values = if (reverse) starts.reversed() else starts
    var low = minOf(from, to)
    val high = maxOf(from, to)
    if (high < values.first() || low > maxOf(range.first(), range.last())) return null
    val last = values.size - 1
    var a = maxOf(0, bisectRight(values, low) - 1)
    var b = if (low == high) a else bisectRight(values, high) - 1
    // A position in the gap *after* a band belongs to no band at all, so the index moves on.
    if (low - values[a] > bandwidth + 1e-10) a++
    if (reverse) {
      val swap = a
      a = last - b
      b = last - swap
    }
    if (a > b || a > last || b < 0) return null
    return domain.subList(maxOf(0, a), minOf(domain.size, b + 1))
  }

  /** The one band a position falls in, or null where it falls in a gap or outside the range. */
  public fun invert(position: Double): VegaValue? = invertRange(position, position)?.firstOrNull()

  private fun bisectRight(values: List<Double>, at: Double): Int {
    var low = 0
    var high = values.size
    while (low < high) {
      val middle = (low + high) / 2
      if (at < values[middle]) high = middle else low = middle + 1
    }
    return low
  }

  /** Band centres, the positions axis ticks and labels use. */
  public fun centers(): List<Double> = domain.map {
    (positions[internKey(it)] ?: Double.NaN) + bandwidth / 2.0
  }

  public fun ticks(): List<VegaValue> = domain
}

/**
 * Point scale: a band scale with zero bandwidth, so values land on the band boundaries.
 *
 * d3 implements it as `band` with `paddingInner = 1`, and so does this.
 */
public class PointScale(
  override val name: String,
  public val domain: List<VegaValue>,
  override val range: List<Double>,
  public val padding: Double = 0.0,
  public val align: Double = 0.5,
  public val round: Boolean = false,
) : PositionScale {

  private val band =
    BandScale(
      name = name,
      domain = domain,
      range = range,
      paddingInner = 1.0,
      paddingOuter = padding,
      align = align,
      round = round,
    )

  override val bandwidth: Double
    get() = 0.0

  public val step: Double
    get() = band.step

  override fun position(value: VegaValue): Double = band.position(value)

  override fun scale(value: VegaValue): VegaValue = band.scale(value)

  /** The one point a position falls nearest, through the band this scale is built on. */
  public fun invert(position: Double): VegaValue? = band.invert(position)

  /** Which points a stretch of the range covers — see [BandScale.invertRange]. */
  public fun invertRange(from: Double, to: Double): List<VegaValue>? = band.invertRange(from, to)

  public fun ticks(): List<VegaValue> = domain
}

/**
 * A continuous scale that interpolates in transformed space.
 *
 * Log, power and symlog scales all work the same way: map the domain through a monotonic transform,
 * interpolate linearly there, and invert by going back. Sharing that structure keeps the difference
 * between them to the transform itself, which is the only part worth reading carefully.
 */
public abstract class TransformedScale(
  override val name: String,
  public val domain: List<Double>,
  override val range: List<Double>,
  public val clamp: Boolean,
  /** Rounds every output to a whole unit. See [LinearScale.round]. */
  public val round: Boolean = false,
) : PositionScale, InvertibleScale {

  init {
    require(domain.size >= 2) { "$name needs at least two domain values, got $domain" }
    require(range.size >= 2) { "$name needs at least two range values, got $range" }
  }

  /** How many stops take part: `min(domain, range)`, as in [LinearScale]. */
  private val stops: Int = minOf(domain.size, range.size)

  /** The monotonic transform this scale interpolates in. */
  protected abstract fun forward(value: Double): Double

  /** The inverse of [forward], for [invert]. */
  protected abstract fun backward(value: Double): Double

  override val bandwidth: Double
    get() = 0.0

  override fun position(value: VegaValue): Double = apply(scaleNumber(value))

  /**
   * A continuous scale of something that is not a number is **not a number**, not nothing.
   *
   * The distinction only shows in arithmetic, and there it is the whole answer: JavaScript reads
   * `null` as zero and propagates `NaN`, so `abs(scale(x, datum.missing) - scale(x, datum.lo))` is
   * a real zero on one reading and no answer at all on the other. Vega-Lite writes exactly that
   * expression to decide whether a bar is too thin to see, and a pre-binned column has no `_end` to
   * give it — so a bar came out a quarter of a unit narrow and shifted along.
   */
  override fun scale(value: VegaValue): VegaValue {
    // **The guard is on the input, not on the answer.** d3 tests `x == null || isNaN(x = +x)` and
    // then does the arithmetic whatever it comes to — so a log scale asked for `-5` answers `NaN`,
    // a perfectly good number having no logarithm, while the same scale asked for a *word* answers
    // `undefined`. Reading the answer instead conflates the two and reports nothing for both.
    //
    // `""` separates them: it coerces to `0`, passes the guard, and its logarithm is `NaN`. Probed.
    val x = scaleNumber(value)
    return if (x.isNaN()) VegaValue.Undefined else VegaValue.Num(apply(x))
  }

  public fun apply(x: Double): Double = if (round) roundHalfUp(unrounded(x)) else unrounded(x)

  private fun unrounded(x: Double): Double {
    if (x.isNaN()) return Double.NaN
    val d0 = forward(domain[0])
    val dn = forward(domain[stops - 1])
    if (!d0.isFinite() || !dn.isFinite()) return Double.NaN
    if (d0 == dn) return (range[0] + range[stops - 1]) / 2.0

    val low = minOf(domain[0], domain[stops - 1])
    val high = maxOf(domain[0], domain[stops - 1])
    val input = if (clamp) x.coerceIn(low, high) else x
    val t = forward(input)
    if (!t.isFinite()) return Double.NaN
    if (stops == 2) return mix(d0, dn, range[0], range[stops - 1], t)

    // **Piecewise, in the transformed space.** A log, power or symlog scale is upstream's
    // `continuous()` wearing a transform, so it takes a domain of more than two stops exactly as a
    // linear one does. This read only the first and last, which is not a rounding difference: a
    // three-stop power scale over `[4, 2, 1] -> [1, 2, 4]` answered 3.5 for 1.5 where upstream
    // answers 3, because it interpolated straight across both segments.
    // A right bisection, for the reason [LinearScale] gives: a value on an interior stop belongs to
    // the segment that starts there.
    val ascending = dn > d0
    var segment = 0
    while (segment < stops - 2) {
      val upper = forward(domain[segment + 1])
      val past = if (ascending) t >= upper else t <= upper
      if (!past) break
      segment++
    }
    return mix(
      forward(domain[segment]),
      forward(domain[segment + 1]),
      range[segment],
      range[segment + 1],
      t,
    )
  }

  /** d3's `interpolateNumber`, in d3's arithmetic — see `LinearScale`. */
  private fun mix(d0: Double, d1: Double, r0: Double, r1: Double, t: Double): Double {
    if (d0 == d1) return (r0 + r1) / 2.0
    val u = (t - d0) / (d1 - d0)
    return r0 * (1.0 - u) + r1 * u
  }

  override fun invert(position: Double): Double {
    val r0 = range.first()
    val r1 = range.last()
    if (r0 == r1) return Double.NaN
    // Clamped both ways, as in `LinearScale` and for the same reason: d3's `clamp` bounds the input
    // to `invert` as well as to the scale, so a pointer past the end of a log or power axis reads
    // the end of the domain rather than a value beyond it.
    val clamped = if (clamp) position.coerceIn(minOf(r0, r1), maxOf(r0, r1)) else position

    // **Piecewise, exactly as [apply] is.** This read only the first and last stop, so on a domain
    // of more than two it was not the inverse of the function above: `apply` interpolated within a
    // segment and `invert` straight across all of them, and the two disagreed by however much the
    // segments differ. That is not a rounding difference. It is what an interactive brush reads —
    // `invert('s', x())` is how a specification turns a pointer into a data value — so a brush on a
    // three-stop log or power axis selected a range with the wrong numbers in it, silently, and the
    // wrongness grew with how unevenly the stops were spread.
    val stops = minOf(domain.size, range.size)
    if (stops < 2) return Double.NaN
    val ascendingRange = range[stops - 1] > range[0]
    var segment = 0
    while (segment < stops - 2) {
      val upper = range[segment + 1]
      val past = if (ascendingRange) clamped > upper else clamped < upper
      if (!past) break
      segment++
    }
    val lowRange = range[segment]
    val highRange = range[segment + 1]
    val low = forward(domain[segment])
    val high = forward(domain[segment + 1])
    // A zero-width range segment has no inverse; answer its own domain stop rather than a NaN that
    // travels. `apply` answers the midpoint for the mirror case, which is d3's own rule there.
    if (lowRange == highRange) return backward(low)
    return backward(low + ((clamped - lowRange) / (highRange - lowRange)) * (high - low))
  }

  public open fun ticks(count: Int = LinearScale.DEFAULT_TICK_COUNT): List<Double> =
    Ticks.ticks(domain.first(), domain.last(), count)

  public open fun formatTick(
    value: Double,
    count: Int = LinearScale.DEFAULT_TICK_COUNT,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): String {
    val step = Ticks.stepFrom(Ticks.tickIncrement(domain.first(), domain.last(), count))
    return formatTickLabel(
      value,
      if (step.isFinite()) Ticks.precisionForStep(step) else DEGENERATE_PRECISION,
      locale,
    )
  }

  /** Labels aligned with [ticks]. Overridden where a scale suppresses some of them. */
  public open fun tickLabels(
    count: Int = LinearScale.DEFAULT_TICK_COUNT,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): List<String> = ticks(count).map { formatTick(it, count, locale) }
}

/**
 * Logarithmic scale.
 *
 * The domain must not span or touch zero, since the transform is undefined there. [isValid] reports
 * that, and the caller turns it into a diagnostic rather than silently clamping the domain into
 * something usable.
 */
public class LogScale(
  name: String,
  domain: List<Double>,
  range: List<Double>,
  public val base: Double = 10.0,
  clamp: Boolean = false,
  round: Boolean = false,
) : TransformedScale(name, domain, range, clamp, round) {

  /**
   * True when the domain lies entirely on one side of zero, i.e. the scale is usable.
   *
   * The **base has no say in this**. A log scale's transform is the natural log whatever base was
   * asked for, so a base of 0, 1, a half or a negative number leaves the geometry perfectly well
   * defined and changes only which ticks are generated — probed against upstream, which places the
   * same marks for all of them. Requiring `base > 1` here turned every position into a NaN and took
   * the marks, the axis and the chart's own size with it.
   *
   * A domain touching or straddling zero is a different matter, and upstream agrees: `zero: true`
   * on a log scale gives a domain of `[0, 900]`, and every `scale(x)` on it answers null.
   */
  public val isValid: Boolean =
    domain.first() != 0.0 && domain.last() != 0.0 && (domain.first() > 0.0) == (domain.last() > 0.0)

  /**
   * The **natural** log, whatever the base is.
   *
   * d3's transform is `Math.log` and its inverse `Math.exp`; `base` reaches only the ticks, the
   * labels and `nice`. That is not an approximation of dividing by `ln(base)` — it is the same
   * answer, because a continuous scale normalises between the transformed ends and a constant
   * divisor cancels. It stops being the same answer exactly where the constant stops being one: a
   * base of 0 makes `ln(base)` negative infinity and every position `-0`, a base of 1 makes it zero
   * and every position infinite, a negative base makes it NaN. Dividing here therefore threw away
   * the whole geometry of a chart upstream draws perfectly well — probed, upstream maps 3 to 120
   * and 900 to 0 for bases 10, 0, 0.5, -4 and 1 alike, and only the *ticks* differ between them.
   */
  override fun forward(value: Double): Double {
    if (!isValid) return Double.NaN
    // A negative domain reflects: the log of the magnitude, negated, so ordering is preserved.
    return if (domain.first() < 0.0) {
      if (value >= 0.0) Double.NaN else -ln(-value)
    } else {
      if (value <= 0.0) Double.NaN else ln(value)
    }
  }

  override fun backward(value: Double): Double =
    if (domain.first() < 0.0) -exp(-value) else exp(value)

  override fun ticks(count: Int): List<Double> =
    Ticks.logTicks(domain.first(), domain.last(), base, count)

  override fun formatTick(value: Double, count: Int, locale: VegaLocale): String =
    // **`formatFloat`, which is `,` at twelve significant digits.** A log axis is the one family
    // `tickFormat` sends down its own branch:
    //
    // ```js
    // else if (isLogarithmic(type)) {
    //   const varfmt = locale.formatFloat(specifier);
    //   …
    // }
    // ```
    //
    // and `formatFloat` fills in `precision = 12` when the specifier names none. Twelve is where
    // the exponent form begins, so `10,000,000,000` is written out and `1e+12` is not — which is
    // the whole visible difference, and it changes the *width* of the axis and so of the chart.
    //
    // A fixed decimal count cannot express that: it says how many places follow the point, not how
    // many digits are worth showing, so every power past a million came out in full.
    NumberFormat.format(value, ",.12", locale)

  /**
   * Log tick labels, with the crowded ones blanked as d3 and Vega do.
   *
   * A log axis generates every integer multiple at each power — 1…9, 10…90, 100… — which is far
   * more labels than an axis can show. d3 keeps a label only where the tick's mantissa is at most
   * `base * count / tickCount` and blanks the rest, so the axis reads 1, 2, 3 then gaps up to 10.
   * Verified against upstream: `[1, 100]` labels mantissas up to 5, and `[1, 1000000]` only the
   * powers.
   *
   * A blank label is not a missing tick: the tick mark is still drawn.
   */
  override fun tickLabels(count: Int, locale: VegaLocale): List<String> {
    val values = ticks(count)
    if (values.isEmpty()) return emptyList()
    val threshold = maxOf(1.0, base * count / values.size)
    return values.map { value ->
      var mantissa = kotlin.math.abs(value) / base.pow(roundHalfUp(logMagnitude(value)))
      // Guard the case where floating point leaves the mantissa just under 1.
      if (mantissa * base < base - 0.5) mantissa *= base
      if (mantissa <= threshold + MANTISSA_EPSILON) formatTick(value, count, locale) else ""
    }
  }

  /**
   * The log of the magnitude, which is what the mantissa is measured against.
   *
   * **In the scale's own base**, unlike the transform: which labels are blank is a question about
   * powers of the base, and this is the one place in the scale where that matters.
   */
  private fun logMagnitude(value: Double): Double = ln(kotlin.math.abs(value)) / ln(base)

  private companion object {
    /** d3 compares the mantissa against a fractional threshold; tolerate representation error. */
    const val MANTISSA_EPSILON = 1e-9
  }
}

/**
 * Power scale, and by extension `sqrt`.
 *
 * The default exponent is 1, which makes an unparameterized `pow` scale linear — worth knowing,
 * since a specification that omits `exponent` is not actually doing anything.
 */
public class PowScale(
  name: String,
  domain: List<Double>,
  range: List<Double>,
  public val exponent: Double = 1.0,
  clamp: Boolean = false,
  round: Boolean = false,
) : TransformedScale(name, domain, range, clamp, round) {

  override fun forward(value: Double): Double = signedPow(value, exponent)

  override fun backward(value: Double): Double = signedPow(value, 1.0 / exponent)

  /** Raising a negative value to a fractional power needs the sign handled separately. */
  private fun signedPow(value: Double, power: Double): Double =
    if (value < 0.0) -((-value).pow(power)) else value.pow(power)
}

/**
 * Symmetric log scale, which unlike [LogScale] handles zero and both signs.
 *
 * The transform is `sign(x) * ln(1 + |x| / constant)`. Verified against upstream: over `[-100,
 * 100]` with the default constant of 1, `-1` lands at 42.49% of the range and `0` exactly at the
 * midpoint.
 */
public class SymlogScale(
  name: String,
  domain: List<Double>,
  range: List<Double>,
  public val constant: Double = 1.0,
  clamp: Boolean = false,
  round: Boolean = false,
) : TransformedScale(name, domain, range, clamp, round) {

  override fun forward(value: Double): Double = symlogForward(value, constant)

  override fun backward(value: Double): Double = symlogBackward(value, constant)
}

/**
 * d3's `transformSymlog`: `Math.sign(x) * Math.log1p(Math.abs(x / c))`.
 *
 * Every part of that one line is load-bearing, and this engine had written it out three times with
 * three different readings.
 *
 * **`log1p`, not `ln(1 + t)`.** They are the same function and not the same arithmetic: adding one
 * to a small number throws away the low bits before the logarithm ever sees them. A symlog scale
 * over `[0.1, 0.30000000000000004]` placed a value at `…4051715` where upstream has `…4051665` —
 * the last two digits, which is exactly as much as this costs and exactly enough to fail a
 * comparison.
 *
 * **`Math.abs(x / c)`, and not `abs(x) / c`.** They agree for a positive constant and differ for a
 * negative one, where the first is still a logarithm of something positive and the second is `NaN`.
 *
 * **`Math.sign(x)`, which is zero at zero**, so `symlog(-0)` is `-0` rather than `0`.
 */
internal fun symlogForward(value: Double, constant: Double): Double =
  sign(value) * ln1p(abs(value / constant))

/** Its inverse, d3's `transformSymexp`: `Math.sign(x) * Math.expm1(Math.abs(x)) * c`. */
internal fun symlogBackward(value: Double, constant: Double): Double =
  sign(value) * expm1(abs(value)) * constant

/**
 * A continuous scale over instants, in epoch milliseconds.
 *
 * Positionally this is a linear scale and nothing more — upstream's is too. What makes it a time
 * scale is everything derived from it: ticks land on calendar boundaries rather than round numbers,
 * `nice` widens to one of those boundaries, and each label is written at its own granularity.
 *
 * @param zone what a day and a month mean. UTC for a `utc` scale, the platform default for `time`,
 *   which is why the same specification can draw differently in two places and is supposed to.
 */
public class TimeScale(
  override val name: String,
  public val domain: List<Double>,
  override val range: List<Double>,
  public val zone: kotlinx.datetime.TimeZone,
  public val clamp: Boolean = false,
  /** Rounds every output to a whole unit. See [LinearScale.round]. */
  public val round: Boolean = false,
) : PositionScale, InvertibleScale {

  init {
    require(domain.size >= 2) { "$name needs at least two domain values, got $domain" }
    require(range.size >= 2) { "$name needs at least two range values, got $range" }
  }

  private val linear = LinearScale(name, domain, range, clamp, round)

  override val bandwidth: Double
    get() = 0.0

  public fun apply(instant: Double): Double = linear.apply(instant)

  override fun invert(position: Double): Double = linear.invert(position)

  override fun position(value: VegaValue): Double = linear.position(value)

  override fun scale(value: VegaValue): VegaValue = linear.scale(value)

  public fun ticks(count: Int = LinearScale.DEFAULT_TICK_COUNT): List<Double> =
    TimeTicks.ticks(domain.first(), domain.last(), count, zone)

  public fun tickLabels(
    count: Int = LinearScale.DEFAULT_TICK_COUNT,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): List<String> = ticks(count).map { TimeTicks.label(it, zone, locale) }
}

/**
 * Ordinal scale: a discrete domain mapped to a discrete range, cycling when the range is shorter.
 */
public class OrdinalScale(
  override val name: String,
  public val domain: List<VegaValue>,
  public val rangeValues: List<VegaValue>,
  /** Returned for a value outside the domain; `null` means [VegaValue.Null]. */
  public val unknown: VegaValue? = null,
  /**
   * `domainImplicit`: an unseen value **joins** the domain rather than being unknown.
   *
   * d3 spells this by setting the scale's `unknown` to its `implicit` sentinel, and the effect is
   * that the domain grows as the scale is used: the first unseen value takes the range entry after
   * the last one already claimed. Order of use therefore decides which colour a value gets, which
   * is why it is off by default — a chart that reorders its rows would repaint itself. It is for a
   * scale whose domain nobody can write down in advance.
   */
  private val implicit: Boolean = false,
) : VegaScale {

  private val indices: MutableMap<VegaValue, Int> =
    domain.withIndex().associateTo(LinkedHashMap()) { (index, value) -> internKey(value) to index }

  override fun scale(value: VegaValue): VegaValue {
    // `unknown`, which is **`undefined`** when the specification names none — the same answer every
    // other scale family gives for a value it cannot place. An ordinal scale is the clearest
    // statement of it, having no coercion at all: its index is keyed by the value, so a miss is a
    // miss and no arithmetic stands in the way. This answered a null, which a mark encoding writes
    // where an undefined leaves the property absent.
    if (rangeValues.isEmpty()) return unknown ?: VegaValue.Undefined
    val key = internKey(value)
    val index =
      indices[key]
        ?: if (implicit) indices.size.also { indices[key] = it }
        else return unknown ?: VegaValue.Undefined
    return rangeValues[index % rangeValues.size]
  }

  /** The domain as it now stands, which [implicit] may have grown past what was declared. */
  public val effectiveDomain: List<VegaValue>
    get() = indices.keys.toList()
}

/**
 * The shape the four discrete-output scales share, so a legend can draw any of them once.
 *
 * A banded legend is one swatch per range value, labelled by the cut point at its *lower* edge —
 * which is why the first swatch carries no label at all: nothing bounds it from below.
 */
public sealed interface BinnedScale : VegaScale {
  /** The cut points between buckets; one fewer than there are range values. */
  public val thresholds: List<Double>

  public val rangeValues: List<VegaValue>

  /**
   * The stretch of domain that maps to [value] — d3's `invertExtent`, and what `invert()` means for
   * a scale that has buckets rather than a gradient.
   *
   * This is how a chart turns a *clicked legend swatch* back into a range of data: a quantize scale
   * says which values are coloured red, and a selection built on it filters to exactly those.
   * `invert()` used to report an error for any scale it could not run backwards continuously, which
   * refused a question upstream answers.
   *
   * Null when the value is not one of the range's, which upstream writes as `[NaN, NaN]`. The ends
   * may be null in their own right: a **threshold** scale's outermost buckets are unbounded,
   * because a cut point at 10 says nothing about how far below it the first bucket reaches.
   */
  public fun invertExtent(value: VegaValue): Pair<Double?, Double?>?

  /**
   * The shared middle of [invertExtent]: the bucket's own cut points, with the ends left to the
   * scale, since only it knows whether they are bounded and by what.
   */
  public fun extentAt(index: Int, low: Double?, high: Double?): Pair<Double?, Double?> =
    (if (index > 0) thresholds[index - 1] else low) to
      (if (index < thresholds.size) thresholds[index] else high)

  /**
   * One input value per bucket, for a legend to colour its swatches with.
   *
   * Taken through [scale] rather than indexing the range directly, so the legend cannot drift out
   * of step with what the marks are actually painted.
   */
  public val bucketRepresentatives: List<Double>
    get() =
      rangeValues.indices.map { i ->
        // Any value strictly below the first cut point lands in bucket 0, and subtracting one
        // always is. Later buckets take their own lower edge, since an equal value bisects right.
        if (i == 0) (thresholds.firstOrNull() ?: 0.0) - 1.0 else thresholds[i - 1]
      }

  /**
   * The values a **banded** legend labels: one per bucket, at the bucket's lower edge.
   *
   * The first is negative infinity, which is not a placeholder — the lowest bucket really does
   * extend to it, and upstream marks the fact by leaving that entry's label empty rather than
   * printing a number nothing bounds.
   */
  public val legendValues: List<Double>
    get() = listOf(Double.NEGATIVE_INFINITY) + thresholds

  /**
   * What bounds the **last** bucket from above, upstream's `values.max`.
   *
   * Infinite for three of the four, because nothing bounds them: a quantize, quantile or threshold
   * scale's topmost bucket runs on for ever, and a legend that labels its swatches by range says "≥
   * 75" rather than naming an end. A bin scale is the exception — its bins have a last edge.
   */
  public val legendMax: Double
    get() = Double.POSITIVE_INFINITY

  /**
   * Where a value sits along the legend's bar, in `0..1`.
   *
   * Measured against the scale's **input** extent rather than its cut points, so the bands are as
   * wide as the ranges they stand for: a quantile scale's bands are uneven, which is the whole
   * point of one.
   */
  public fun legendFraction(value: Double): Double {
    val (lo, hi) = legendExtent
    val span = hi - lo
    return if (span == 0.0) 0.0 else (value - lo) / span
  }

  /** The extent [legendFraction] measures against; see each scale for what its domain means. */
  public val legendExtent: Pair<Double, Double>
}

/**
 * d3's `bisectRight`: the index after the last value not greater than [x].
 *
 * A band scale inverts a brush with this — `bisectRight(bandStarts, position) - 1` is the band the
 * pointer is over — and a crossfilter narrows a range with its sibling below, so both are load
 * bearing rather than helpers.
 */
internal fun bisectRight(
  values: List<Double>,
  x: Double,
  low: Int = 0,
  high: Int = values.size,
): Int {
  if (x.isNaN()) return high
  var lo = low
  var hi = high
  while (lo < hi) {
    val mid = (lo + hi) ushr 1
    // **`compare(a[mid], x) <= 0`, which is d3's own direction and not its negation.** The two
    // agree on every pair of numbers and differ when the *pivot* is `NaN`: `ascending` answers
    // `NaN` then, `NaN <= 0` is false, and the search moves **left**. Asking `x < values[mid]`
    // instead sends it right, because a comparison against a NaN is false whichever way round it
    // is written.
    //
    // A pivot is `NaN` when a quantile scale has no samples to cut on — its thresholds are the
    // quantiles of an empty column — and upstream then answers the **first** range entry for every
    // value. This walked to the last one.
    if (values[mid] <= x) lo = mid + 1 else hi = mid
  }
  return lo
}

/**
 * d3's `bisectLeft`: the index of the first value not less than [x].
 *
 * A **NaN** needle answers `high` rather than a position, and d3 checks for it up front rather than
 * leaving it to the comparisons: every comparison against a NaN is false, so the search would
 * otherwise converge on `low` and claim the value belongs at the front. For a band scale inverting
 * a pointer position that is not a number, the difference is selecting the first band instead of
 * none.
 */
internal fun bisectLeft(
  values: List<Double>,
  x: Double,
  low: Int = 0,
  high: Int = values.size,
): Int {
  if (x.isNaN()) return high
  var lo = low
  var hi = high
  while (lo < hi) {
    val mid = (lo + hi) ushr 1
    if (values[mid] < x) lo = mid + 1 else hi = mid
  }
  return lo
}

/**
 * `quantize`: equal-width intervals of the domain, one per range value.
 *
 * Cut points are computed rather than stored, in d3's own form — `((i + 1)·x1 - (i - n)·x0) / (n +
 * 1)` — which spaces them evenly without accumulating a rounding error across the domain.
 */
public class QuantizeScale(
  override val name: String,
  public val domain: List<Double>,
  override val rangeValues: List<VegaValue>,
) : BinnedScale {

  /** One fewer cut point than there are buckets. */
  override val thresholds: List<Double> =
    if (rangeValues.size < 2) {
      emptyList()
    } else {
      val n = rangeValues.size - 1
      val x0 = domain.firstOrNull() ?: 0.0
      val x1 = domain.lastOrNull() ?: 1.0
      (0 until n).map { i -> ((i + 1) * x1 - (i - n) * x0) / (n + 1) }
    }

  /**
   * Widened by one band at each end, because the outermost buckets have no stated edge.
   *
   * A threshold scale's domain stops at its last cut point, so measuring against it would give the
   * first and last bands no width at all. Upstream pads by one average band; with a single cut
   * point and no average to take, it pads by a tenth.
   */
  override val legendExtent: Pair<Double, Double>
    get() = (domain.firstOrNull() ?: 0.0) to (domain.lastOrNull() ?: 1.0)

  /** Bounded at both ends by the declared domain, which is what makes `quantize` quantize. */
  override fun invertExtent(value: VegaValue): Pair<Double?, Double?>? {
    val index = rangeValues.indexOf(value)
    if (index < 0) return null
    return extentAt(index, domain.firstOrNull() ?: 0.0, domain.lastOrNull() ?: 1.0)
  }

  /**
   * `quantize` and `threshold` read a value **without coercing it**, which is a different line from
   * every other scale's:
   * ```js
   * function scale(x) {
   *   return x != null && x <= x ? range[bisect(domain, x, 0, n)] : unknown;
   * }
   * ```
   *
   * `x <= x` is a NaN test that works on any type, and it lets a **word** through: `"abc" <= "abc"`
   * is true, string comparison being perfectly happy. So a word reaches the bisect, where every
   * comparison against a number is false — `"abc" < 10` is a NaN comparison — and the search lands
   * on the **first** range entry rather than answering `unknown`. Probed: `scale("abc")` is `lo`
   * where a linear scale over the same value answers nothing at all.
   *
   * Everything with a number in it behaves as though it had been coerced, because JavaScript's `<`
   * coerces: `"" < 10` is `0 < 10`. So the only case that separates this from [scaleNumber] is the
   * one that has no number in it, and that case is the reason this is written out.
   */
  override fun scale(value: VegaValue): VegaValue {
    if (rangeValues.isEmpty()) return VegaValue.Undefined
    if (value is VegaValue.Null || value is VegaValue.Undefined) return VegaValue.Undefined
    val x = JsSemantics.toNumber(value)
    return rangeValues[if (x.isNaN()) 0 else bisectRight(thresholds, x)]
  }
}

/**
 * `quantile`: equal-count buckets, so each holds the same share of the data.
 *
 * The cut points are the quantiles of the **domain itself**, which is why a quantile scale's domain
 * is the whole column rather than its extent. A skewed column gets narrow buckets where it is dense
 * and wide ones where it is sparse — the opposite of `quantize`, and the reason to reach for it.
 */
public class QuantileScale(
  override val name: String,
  domain: List<Double>,
  override val rangeValues: List<VegaValue>,
) : BinnedScale {

  private val sorted: List<Double> = domain.filterNot { it.isNaN() }.sorted()

  /**
   * The scale's domain as d3 keeps it: the samples, sorted, with the unreadable ones dropped.
   *
   * A quantile scale's domain really is the whole column — that is what distinguishes it from
   * `quantize` — so this is also what an axis on one ticks at, one tick per sample.
   */
  public val sampleDomain: List<Double>
    get() = sorted

  override val thresholds: List<Double> =
    (1 until maxOf(1, rangeValues.size)).map { i ->
      quantileSorted(sorted, i.toDouble() / maxOf(1, rangeValues.size))
    }

  /** The sample's own extent: a quantile scale's domain is the whole column, sorted. */
  override val legendExtent: Pair<Double, Double>
    get() = (sorted.firstOrNull() ?: 0.0) to (sorted.lastOrNull() ?: 1.0)

  /** Bounded by the **samples**, since a quantile scale's domain is the column itself. */
  override fun invertExtent(value: VegaValue): Pair<Double?, Double?>? {
    val index = rangeValues.indexOf(value)
    if (index < 0) return null
    return extentAt(index, sorted.firstOrNull(), sorted.lastOrNull())
  }

  override fun scale(value: VegaValue): VegaValue {
    if (rangeValues.isEmpty()) return VegaValue.Undefined
    val x = scaleNumber(value)
    if (x.isNaN()) return VegaValue.Undefined
    return rangeValues[bisectRight(thresholds, x)]
  }

  /**
   * d3-array's `quantileSorted`, transcribed rather than rearranged.
   *
   * ```js
   * if (!(n = values.length) || isNaN(p = +p)) return;
   * if (p <= 0 || n < 2) return +values[0];
   * if (p >= 1) return +values[n - 1];
   * var i = (n - 1) * p, i0 = Math.floor(i),
   *     value0 = +values[i0], value1 = +values[i0 + 1];
   * return value0 + (value1 - value0) * (i - i0);
   * ```
   *
   * Three things here were written more sensibly and were therefore wrong.
   *
   * **No short circuit when the position lands on a sample.** `return values[lower]` looks like an
   * obvious saving, and for finite numbers it is exact — but d3 still evaluates `(value1 -
   * value0) * 0`, and when the next sample is an infinity that product is `NaN`, not zero. A
   * quantile scale over a column holding `Infinity` has `[1, NaN]` for its thresholds upstream and
   * had `[1, 2]` here, which moved two of four marks into the wrong colour bucket.
   *
   * **The second sample is `values[i0 + 1]`, not `values[ceil(i)]`.** They differ exactly when the
   * position is a whole number, which is the case the short circuit used to hide: `ceil` names the
   * same sample twice and d3 names the one after it.
   *
   * **`value0 + (value1 - value0) * w`, not `value0 * (1 - w) + value1 * w`.** The same line in
   * algebra and not in floating point.
   */
  private fun quantileSorted(values: List<Double>, p: Double): Double {
    if (values.isEmpty() || p.isNaN()) return Double.NaN
    if (p <= 0.0 || values.size < 2) return values[0]
    if (p >= 1.0) return values[values.size - 1]
    val position = (values.size - 1) * p
    val lower = kotlin.math.floor(position).toInt()
    val value0 = values[lower]
    val value1 = values[lower + 1]
    return value0 + (value1 - value0) * (position - lower)
  }
}

/**
 * `threshold`: the domain *is* the list of cut points.
 *
 * So a threshold scale has one more range value than domain value, and the specification is stating
 * the boundaries rather than asking for them to be derived — which is what a chart wants when the
 * boundaries mean something outside the data, like a target or a regulatory limit.
 */
public class ThresholdScale(
  override val name: String,
  override val thresholds: List<Double>,
  override val rangeValues: List<VegaValue>,
) : BinnedScale {

  /** The domain of a threshold scale *is* its cut points, which is what distinguishes it. */
  public val domain: List<Double>
    get() = thresholds

  /**
   * Unbounded at both ends, and deliberately so: a cut point at 10 says the first bucket holds
   * everything below 10 and nothing about how far below. Upstream answers `undefined` there.
   */
  override fun invertExtent(value: VegaValue): Pair<Double?, Double?>? {
    val index = rangeValues.indexOf(value)
    if (index < 0) return null
    return extentAt(index, null, null)
  }

  override val legendExtent: Pair<Double, Double>
    get() {
      val lo = thresholds.firstOrNull() ?: 0.0
      val hi = thresholds.lastOrNull() ?: 1.0
      val count = thresholds.size - 1
      val adjust = if (count > 0) (hi - lo) / count else 0.1
      return (lo - adjust) to (hi + adjust)
    }

  /** Read without coercing, as `quantize` is; see the note on [QuantizeScale.scale]. */
  override fun scale(value: VegaValue): VegaValue {
    if (rangeValues.isEmpty()) return VegaValue.Undefined
    if (value is VegaValue.Null || value is VegaValue.Undefined) return VegaValue.Undefined
    val x = JsSemantics.toNumber(value)
    // d3 clamps the search to one fewer than the range length, so extra domain values past the end
    // of the range are ignored rather than indexing off it.
    val limit = minOf(thresholds.size, rangeValues.size - 1)
    return rangeValues[if (x.isNaN()) 0 else bisectRight(thresholds, x, high = limit)]
  }
}

/**
 * `bin-ordinal`: the domain is a list of bin edges, and the bucket indexes an ordinal range.
 *
 * The pairing this exists for is a `bin` transform feeding a colour scheme. Two consequences follow
 * from it being ordinal rather than continuous, and both are visible on a chart: the range
 * **wraps** when there are more bins than colours, so a fourth bin reuses the first colour rather
 * than running out; and a value below the first edge maps to nothing at all rather than to the
 * first bucket.
 */
public class BinOrdinalScale(
  override val name: String,
  public val domain: List<Double>,
  override val rangeValues: List<VegaValue>,
) : BinnedScale {

  /**
   * The bin edges are the scale's `bins`, which is what upstream ticks an axis on one at.
   *
   * The last edge bounds the topmost bucket rather than opening one, so it maps to nothing and
   * drops out when the ticks are filtered to those that land inside the range.
   */
  override val bins: List<Double>
    get() = domain

  /** The interior edges: the first and last bound the outermost buckets and label nothing. */
  override val thresholds: List<Double>
    get() = domain.drop(1).dropLast(1)

  /**
   * Bounded by the outermost bin edges, which a `bin-ordinal` scale has and a threshold scale does
   * not: the domain *is* the edges, so the first and last are real bounds rather than absences.
   */
  override fun invertExtent(value: VegaValue): Pair<Double?, Double?>? {
    val index = rangeValues.indexOf(value)
    if (index < 0) return null
    return extentAt(index, domain.firstOrNull(), domain.lastOrNull())
  }

  /** The bin edges are the labels, and the last one bounds rather than opens a bucket. */
  override val legendValues: List<Double>
    get() = domain.dropLast(1)

  /** That last edge, which is what a range label ends with instead of running to infinity. */
  override val legendMax: Double
    get() = domain.lastOrNull() ?: Double.POSITIVE_INFINITY

  override val legendExtent: Pair<Double, Double>
    get() = (domain.firstOrNull() ?: 0.0) to (domain.lastOrNull() ?: 1.0)

  // A bucket's lower edge is a domain entry, not a threshold, so the shared default is wrong here.
  override val bucketRepresentatives: List<Double>
    get() = rangeValues.indices.map { i -> domain.getOrElse(i) { domain.lastOrNull() ?: 0.0 } }

  override fun scale(value: VegaValue): VegaValue {
    if (rangeValues.isEmpty()) return VegaValue.Null
    val x = value.asDouble()
    if (x.isNaN()) return VegaValue.Null
    val index = bisectRight(domain, x) - 1
    if (index < 0) return VegaValue.Null
    return rangeValues[index % rangeValues.size]
  }
}

/**
 * The monotonic space a continuous scale measures in, for a colour ramp built on one.
 *
 * A positional scale carries its transform in its own class — [PowScale], [LogScale], [SymlogScale]
 * — because the transform *is* most of what such a scale does. A colour scale over the same domain
 * does the same arithmetic and then reads a ramp rather than a range of numbers, so it takes the
 * transform as a value instead of as a subclass.
 */
public sealed interface ScaleTransform {
  public fun forward(value: Double): Double

  /** The numbers as they are, which is `linear`, `time`, `utc` and `sequential`. */
  public data object Linear : ScaleTransform {
    override fun forward(value: Double): Double = value
  }

  /** `pow` and `sqrt`, the latter being an exponent of a half. Negative values keep their sign. */
  public data class Pow(public val exponent: Double) : ScaleTransform {
    override fun forward(value: Double): Double =
      if (value < 0.0) -((-value).pow(exponent)) else value.pow(exponent)
  }

  /** `log`, which is only defined for a domain that stays on one side of zero. */
  public data class Log(public val base: Double = 10.0) : ScaleTransform {
    private val logBase = ln(base)

    override fun forward(value: Double): Double = ln(abs(value)) / logBase
  }

  /** `symlog`, which does handle zero and both signs. See [symlogForward]. */
  public data class Symlog(public val constant: Double = 1.0) : ScaleTransform {
    override fun forward(value: Double): Double = symlogForward(value, constant)
  }
}

/**
 * A continuous scale whose range is a colour ramp.
 *
 * Covers Vega's `sequential` type, and any continuous scale given a colour range: `linear`, `time`,
 * `utc`, and the transformed ones through [ScaleTransform]. The position along the ramp comes from
 * the same normalization a numeric scale of that type uses, so a colour scale and a positional one
 * over the same domain stay in step.
 */
public class SequentialColorScale(
  override val name: String,
  public val domain: List<Double>,
  public val colors: List<SceneColor>,
  public val space: ColorSpaces.Interpolation = ColorSpaces.Interpolation.RGB,
  /** `interpolate: {"type": "rgb", "gamma": y}` — only the RGB space has one. */
  public val gamma: Double = 1.0,
  /**
   * Whether a position outside `0..1` is pinned to the ramp's ends. **False**, as d3's is.
   *
   * See [colorAt]: this defaulted to true, so every continuous colour scale in this engine painted
   * an out-of-domain value with the ramp's own first or last colour instead of extrapolating past
   * it.
   */
  public val clamp: Boolean = false,
  /**
   * The space the ramp is walked in, for a colour scale built on a **transformed** scale type.
   *
   * A `pow`, `sqrt`, `log` or `symlog` scale whose range is colours rather than numbers is a colour
   * scale like any other, and d3 interpolates it in the scale's own space: `scaleSqrt` over `[0,
   * 100]` paints 25 the exact midpoint colour, because `√25 / √100` is a half. Read off a live
   * view, which answers `rgb(128, 0, 128)` there.
   *
   * [ScaleTransform.Linear] for every other kind, which is what a `linear`, `time` or `sequential`
   * colour scale wants and is why this is last with a default.
   */
  public val transform: ScaleTransform = ScaleTransform.Linear,
  /**
   * Which slice of the ramp the scale uses, as two fractions of it — upstream's `schemeExtent`.
   *
   * ```js
   * return (isFunction(scheme) && (extent || reverse))
   *   ? interpolateRange(scheme, flip(extent || [0, 1], reverse))
   *   : scheme;
   * ```
   *
   * `[0, 1]` is the whole ramp and is what a range written out as colours always gets. Written
   * backwards it reads the ramp backwards, which is how `reverse` acts on a scheme — and how the
   * named range `diverging` differs from the `blueorange` scheme it is made of.
   */
  public val rampExtent: List<Double> = listOf(0.0, 1.0),
) : VegaScale {

  init {
    require(domain.size >= 2) { "$name needs at least two domain values, got $domain" }
    require(colors.isNotEmpty()) { "$name needs at least one colour" }
  }

  /**
   * Where [x] sits along the ramp before clamping, in `0..1` for a value inside the domain.
   *
   * A **three-point domain makes this a diverging scale**, and Vega composes that itself: a
   * continuous colour scale with three domain values becomes `diverging-linear` in its registry,
   * without the specification ever naming it. The middle value then takes the ramp's midpoint
   * whatever its arithmetic position — the point of a blue-white-red chart is that white sits at
   * zero, not halfway between the extremes. Reading only the first and last put the neutral colour
   * wherever the domain's midpoint happened to fall: for `[-10, 0, 20]`, zero came out a third of
   * the way along and still blue.
   *
   * The two halves are scaled independently, `0.5 / (mid - low)` below and `0.5 / (high - mid)`
   * above, which is d3's `scaleDiverging`.
   */
  internal fun position(x: Double): Double {
    if (domain.size < 3) {
      // In the scale's **own** space, which for everything but a transformed colour scale is the
      // one the numbers are already in; see [transform].
      val x = transform.forward(x)
      val lo = transform.forward(domain.first())
      val hi = transform.forward(domain.last())
      // A **zero-width domain sits in the middle of the ramp**, not at its start. d3's `normalize`
      // answers `constant(0.5)` when the ends coincide, so a colour scale over a column that turns
      // out to be constant paints the middle colour — the one that says "nothing to compare" —
      // rather than the extreme. This returned 0 here and the *last* colour in `colorAt`, three
      // answers between them and none of upstream's.
      val delta = hi - lo
      if (delta == 0.0) return 0.5
      if (delta.isNaN()) return Double.NaN
      return (x - lo) / delta
    }
    val low = domain[0]
    val mid = domain[1]
    val high = domain[2]
    val below = if (low == mid) 0.0 else 0.5 / (mid - low)
    val above = if (mid == high) 0.0 else 0.5 / (high - mid)
    // Which half a value belongs to is decided in the domain's own direction, so a descending
    // domain still puts its middle value at the middle of the ramp.
    val sign = if (mid < low) -1.0 else 1.0
    return 0.5 + (x - mid) * (if (sign * x < sign * mid) below else above)
  }

  /** The colour at [x], or `null` when the input cannot be placed on the ramp. */
  public fun colorAt(x: Double): SceneColor? {
    if (x.isNaN()) return null
    val raw = position(x)
    if (raw.isNaN()) return null
    // **Only when the specification asks for it.** d3's continuous scale is
    //
    //     clamp ? Math.max(0, Math.min(1, x * k10)) : x * k10
    //
    // with `clamp` false until someone sets it, and a colour range does not change that. A value
    // below the domain therefore takes a position below zero, the ramp's first segment is
    // *extrapolated* through it, and the channels saturate — upstream's blues ramp one third of a
    // domain below its start is `rgb(255, 255, 255)`, which is no colour in the scheme.
    //
    // This class defaulted to clamping and, worse, answered **nothing at all** when told not to: a
    // value outside the domain of a `clamp: false` scale came back with no colour, where upstream
    // has a colour for every finite number. Both halves were wrong in the same direction, which is
    // why it looked consistent. Probed across six scale shapes; a range written out as two colours
    // behaves the same way and only *looks* clamped, because extrapolating past pure black or pure
    // white saturates back to itself.
    val along = if (clamp) raw.coerceIn(0.0, 1.0) else raw
    val from = rampExtent.firstOrNull() ?: 0.0
    val to = rampExtent.getOrNull(1) ?: 1.0
    return ColorSpaces.sample(colors, from + along * (to - from), space, gamma)
  }

  override fun scale(value: VegaValue): VegaValue {
    val colour = colorAt(scaleNumber(value)) ?: return VegaValue.Undefined
    return VegaValue.Str(colour.toCssHex())
  }

  /**
   * Where [x] sits along the ramp, in `0..1`.
   *
   * A gradient legend needs this twice over: once for each stop's offset, and once to place each
   * label against the swatch.
   */
  public fun fraction(x: Double): Double {
    // **Linear over the extent, even when the ramp is diverging.** Upstream's `scaleFraction`
    // strips the `diverging-` prefix and places labels with a plain scale of the base type over
    // `[first, last]`: the gradient itself carries the asymmetry in its colour stops, so placing
    // the labels by the diverging position too would bend them a second time.
    val lo = domain.first()
    val hi = domain.last()
    // **The middle, for the same reason [position] answers the middle.** `scaleFraction` places
    // labels with a plain linear scale over `[first, last]`, and d3's `normalize` answers
    // `constant(0.5)` when the ends coincide:
    //
    //     return (b -= (a = +a)) ? function(x) { return (x - a) / b; } : constant(isNaN(b) ? NaN :
    // 0.5);
    //
    // This answered 0 and put the one label a constant column earns at the *start* of the ramp
    // rather than beside its middle — two readings of the same degenerate domain in one class, of
    // which only [position] was upstream's.
    if (lo == hi) return 0.5
    return ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0)
  }

  /** Tick values across the domain, as a linear scale over the same domain would produce. */
  public fun ticks(count: Int = LinearScale.DEFAULT_TICK_COUNT): List<Double> =
    Ticks.ticks(domain.first(), domain.last(), count)

  /** Default label text for [value], with the decimals the tick step implies. */
  public fun formatTick(
    value: Double,
    count: Int = LinearScale.DEFAULT_TICK_COUNT,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): String {
    val step = Ticks.stepFrom(Ticks.tickIncrement(domain.first(), domain.last(), count))
    return formatTickLabel(
      value,
      if (step.isFinite()) Ticks.precisionForStep(step) else DEGENERATE_PRECISION,
      locale,
    )
  }

  public fun tickLabels(
    count: Int = LinearScale.DEFAULT_TICK_COUNT,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): List<String> {
    val step = Ticks.stepFrom(Ticks.tickIncrement(domain.first(), domain.last(), count))
    val precision = if (step.isFinite()) Ticks.precisionForStep(step) else DEGENERATE_PRECISION
    return ticks(count).map { formatTickLabel(it, precision, locale) }
  }
}

/**
 * The decimals a label keeps when the tick step says nothing.
 *
 * Six, and it is d3's rather than a choice: upstream formats a label with `,f` and fills the
 * precision in from `precisionFixed(tickStep(...))`. A degenerate span — a domain of `[NaN, 100]`,
 * or one whose two ends are equal — makes that `NaN`, so the specifier keeps **no** precision and
 * d3's default for `f` applies. A chart that switches views by emptying a dataset has exactly such
 * a scale, and its axis reads `100.000000`.
 */
private const val DEGENERATE_PRECISION = 6

/**
 * Formats an axis tick label the way Vega's default does: fixed decimals, thousands separators and
 * a typographic minus.
 *
 * Neither of the last two is optional. Verified against upstream: a linear axis over `[0, 1000000]`
 * labels `100,000`, not `100000`, and so does a log axis; and a negative tick is signed with U+2212
 * rather than a hyphen, because the label goes through d3-format. A *discrete* axis does not — its
 * labels are the domain's own strings — which is why the substitution lives here and not in
 * [formatNumber].
 */
public fun formatTickLabel(
  value: Double,
  decimals: Int,
  locale: VegaLocale = VegaLocale.EnglishUS,
): String = withTypographicMinus(groupThousands(formatNumber(value, decimals), locale), locale)

/**
 * Inserts the locale's group separator through the integer part, leaving any fraction alone, and
 * writes the fraction behind the locale's decimal separator.
 *
 * The grouping is the locale's too: `[3]` for most of the world, `[3, 2]` for the Indian system
 * where a lakh is `1,00,000`. A locale whose separator is the empty string groups with nothing,
 * which is how several languages write a four-digit year without a comma in it.
 */
public fun groupThousands(text: String, locale: VegaLocale = VegaLocale.EnglishUS): String {
  val negative = text.startsWith("-")
  val body = if (negative) text.substring(1) else text
  val dot = body.indexOf('.')
  val integerPart = if (dot < 0) body else body.substring(0, dot)
  if (integerPart.any { !it.isDigit() }) return text
  val fraction = if (dot < 0) "" else locale.decimal + body.substring(dot + 1)
  val grouped = grouped(integerPart, locale)
  return (if (negative) "-" else "") + grouped + fraction
}

/**
 * The digits of a whole number with the locale's separator between its groups.
 *
 * d3's own walk: the group sizes are taken from the front of `grouping` and the **last** one
 * repeats, so `[3]` never changes and `[3, 2]` gives thousands, then hundreds of thousands, then
 * lakhs.
 */
private fun grouped(digits: String, locale: VegaLocale): String {
  if (locale.thousands.isEmpty()) return digits
  val pieces = mutableListOf<String>()
  var index = digits.length
  var group = 0
  while (index > 0) {
    val size = locale.grouping[minOf(group, locale.grouping.size - 1)]
    val from = maxOf(0, index - size)
    pieces += digits.substring(from, index)
    index = from
    group++
  }
  return pieces.reversed().joinToString(locale.thousands)
}

/**
 * The largest magnitude a `Double` can carry into a `Long` without saturating. See [formatNumber].
 */
private const val LONG_EXACT_LIMIT: Double = 9.223372036854775E18

/**
 * Formats a number with a fixed number of decimals, trimming a trailing `.0`.
 *
 * A deliberately small subset of d3-format: enough for default tick labels. An explicit `format`
 * string in a specification is not supported and must be reported as a diagnostic by the caller
 * rather than silently ignored.
 */
public fun formatNumber(value: Double, decimals: Int): String {
  if (value.isNaN()) return "NaN"
  // d3-format spells these the way JavaScript does rather than with the mathematical symbol.
  if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
  val normalized = if (value == 0.0) 0.0 else value
  if (decimals <= 0) {
    val rounded = roundHalfUp(normalized)
    // **`Long` cannot hold it past 2^63**, and `Double.toLong()` does not overflow — it
    // *saturates*,
    // so every value above about 9.2e18 printed the same `9223372036854775807`. A linear axis over
    // a domain of that size is unusual and entirely legal, and its labels were all the identical
    // wrong number with nothing to say so. `Decimals.fixed(x, 0)` is the same rounding without the
    // range, which is what the branch below already uses for every other decimal count.
    return if (abs(rounded) < LONG_EXACT_LIMIT) rounded.toLong().toString()
    else Decimals.fixed(rounded, 0)
  }
  val text = Decimals.fixed(normalized, decimals)
  return if (text == "-0" || text.matches(Regex("-0\\.0+"))) text.substring(1) else text
}
