package dev.aster.vega.runtime.scale

import dev.aster.vega.model.PlatformDecimals
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.roundHalfUp
import dev.aster.vega.model.withTypographicMinus
import dev.aster.vega.scene.ColorSpaces
import dev.aster.vega.scene.SceneColor
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

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
    require(domain.size >= 2) { "A linear scale needs at least two domain values, got $domain" }
    require(range.size >= 2) { "A linear scale needs at least two range values, got $range" }
    require(domain.size == range.size || (domain.size == 2 && range.size == 2)) {
      "Piecewise domain and range must have equal length: $domain vs $range"
    }
  }

  override val bandwidth: Double
    get() = 0.0

  override fun position(value: VegaValue): Double = apply(value.asDouble())

  /**
   * A continuous scale of something that is not a number is **not a number**, not nothing.
   *
   * The distinction only shows in arithmetic, and there it is the whole answer: JavaScript reads
   * `null` as zero and propagates `NaN`, so `abs(scale(x, datum.missing) - scale(x, datum.lo))` is
   * a real zero on one reading and no answer at all on the other. Vega-Lite writes exactly that
   * expression to decide whether a bar is too thin to see, and a pre-binned column has no `_end` to
   * give it — so a bar came out a quarter of a unit narrow and shifted along.
   */
  override fun scale(value: VegaValue): VegaValue = VegaValue.Num(position(value))

  public fun apply(x: Double): Double = if (round) roundHalfUp(unrounded(x)) else unrounded(x)

  private fun unrounded(x: Double): Double {
    if (x.isNaN()) return Double.NaN
    // A zero-extent domain has no gradient; d3 returns the range midpoint rather than dividing by
    // 0.
    val d0 = domain.first()
    val d1 = domain.last()
    if (d0 == d1) return (range.first() + range.last()) / 2.0

    val input = if (clamp) x.coerceIn(minOf(d0, d1), maxOf(d0, d1)) else x
    if (domain.size == 2) return interpolate(d0, d1, range.first(), range.last(), input)

    // Piecewise: find the segment containing the input, then interpolate within it.
    val ascending = domain.last() > domain.first()
    var segment = 0
    while (segment < domain.size - 2) {
      val upper = domain[segment + 1]
      val past = if (ascending) input > upper else input < upper
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
    if (domain.size != 2 || range.size != 2) return Double.NaN
    val r0 = range[0]
    val r1 = range[1]
    if (r0 == r1) return Double.NaN
    val t = (position - r0) / (r1 - r0)
    return domain[0] + t * (domain[1] - domain[0])
  }

  public fun ticks(count: Int = DEFAULT_TICK_COUNT): List<Double> =
    Ticks.ticks(domain.first(), domain.last(), count)

  /** Default label text for a tick, matching Vega's digits-from-step behaviour. */
  public fun formatTick(value: Double, count: Int = DEFAULT_TICK_COUNT): String {
    val step = Ticks.stepFrom(Ticks.tickIncrement(domain.first(), domain.last(), count))
    val precision = if (step.isFinite()) Ticks.precisionForStep(step) else DEGENERATE_PRECISION
    return formatTickLabel(value, precision)
  }

  /** Labels aligned with [ticks], so a scale can suppress some of them. */
  public fun tickLabels(count: Int = DEFAULT_TICK_COUNT): List<String> =
    ticks(count).map { formatTick(it, count) }

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
 * Band scale: a discrete domain mapped to contiguous, equal-width bands.
 *
 * The step and padding arithmetic follows d3-scaleBand exactly, including `align` controlling where
 * leftover space goes. Positions are computed once at construction.
 */
public class BandScale(
  override val name: String,
  public val domain: List<String>,
  override val range: List<Double>,
  public val paddingInner: Double = 0.0,
  public val paddingOuter: Double = 0.0,
  public val align: Double = 0.5,
  public val round: Boolean = false,
) : PositionScale {

  init {
    require(range.size >= 2) { "A band scale needs a two-value range, got $range" }
  }

  private val positions: Map<String, Double>
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
    val map = LinkedHashMap<String, Double>(n)
    ordered.forEachIndexed { slot, domainIndex ->
      map[domain[domainIndex]] = computedStart + computedStep * slot
    }
    positions = map
  }

  override fun position(value: VegaValue): Double = positions[value.asString()] ?: Double.NaN

  override fun scale(value: VegaValue): VegaValue {
    val result = position(value)
    return if (result.isNaN()) VegaValue.Null else VegaValue.Num(result)
  }

  /** Band centres, the positions axis ticks and labels use. */
  public fun centers(): List<Double> = domain.map {
    (positions[it] ?: Double.NaN) + bandwidth / 2.0
  }

  public fun ticks(): List<String> = domain
}

/**
 * Point scale: a band scale with zero bandwidth, so values land on the band boundaries.
 *
 * d3 implements it as `band` with `paddingInner = 1`, and so does this.
 */
public class PointScale(
  override val name: String,
  public val domain: List<String>,
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

  public fun ticks(): List<String> = domain
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

  /** The monotonic transform this scale interpolates in. */
  protected abstract fun forward(value: Double): Double

  /** The inverse of [forward], for [invert]. */
  protected abstract fun backward(value: Double): Double

  override val bandwidth: Double
    get() = 0.0

  override fun position(value: VegaValue): Double = apply(value.asDouble())

  /**
   * A continuous scale of something that is not a number is **not a number**, not nothing.
   *
   * The distinction only shows in arithmetic, and there it is the whole answer: JavaScript reads
   * `null` as zero and propagates `NaN`, so `abs(scale(x, datum.missing) - scale(x, datum.lo))` is
   * a real zero on one reading and no answer at all on the other. Vega-Lite writes exactly that
   * expression to decide whether a bar is too thin to see, and a pre-binned column has no `_end` to
   * give it — so a bar came out a quarter of a unit narrow and shifted along.
   */
  override fun scale(value: VegaValue): VegaValue = VegaValue.Num(position(value))

  public fun apply(x: Double): Double = if (round) roundHalfUp(unrounded(x)) else unrounded(x)

  private fun unrounded(x: Double): Double {
    if (x.isNaN()) return Double.NaN
    val d0 = forward(domain.first())
    val d1 = forward(domain.last())
    if (!d0.isFinite() || !d1.isFinite()) return Double.NaN
    if (d0 == d1) return (range.first() + range.last()) / 2.0

    val low = minOf(domain.first(), domain.last())
    val high = maxOf(domain.first(), domain.last())
    val input = if (clamp) x.coerceIn(low, high) else x
    val t = forward(input)
    if (!t.isFinite()) return Double.NaN
    return range.first() + ((t - d0) / (d1 - d0)) * (range.last() - range.first())
  }

  override fun invert(position: Double): Double {
    val r0 = range.first()
    val r1 = range.last()
    if (r0 == r1) return Double.NaN
    val d0 = forward(domain.first())
    val d1 = forward(domain.last())
    return backward(d0 + ((position - r0) / (r1 - r0)) * (d1 - d0))
  }

  public open fun ticks(count: Int = LinearScale.DEFAULT_TICK_COUNT): List<Double> =
    Ticks.ticks(domain.first(), domain.last(), count)

  public open fun formatTick(value: Double, count: Int = LinearScale.DEFAULT_TICK_COUNT): String {
    val step = Ticks.stepFrom(Ticks.tickIncrement(domain.first(), domain.last(), count))
    return formatTickLabel(
      value,
      if (step.isFinite()) Ticks.precisionForStep(step) else DEGENERATE_PRECISION,
    )
  }

  /** Labels aligned with [ticks]. Overridden where a scale suppresses some of them. */
  public open fun tickLabels(count: Int = LinearScale.DEFAULT_TICK_COUNT): List<String> =
    ticks(count).map { formatTick(it, count) }
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

  private val logBase = ln(base)

  /** True when the domain lies entirely on one side of zero, i.e. the scale is usable. */
  public val isValid: Boolean =
    base > 1.0 &&
      domain.first() != 0.0 &&
      domain.last() != 0.0 &&
      (domain.first() > 0.0) == (domain.last() > 0.0)

  override fun forward(value: Double): Double {
    if (!isValid) return Double.NaN
    // A negative domain reflects: the log of the magnitude, negated, so ordering is preserved.
    return if (domain.first() < 0.0) {
      if (value >= 0.0) Double.NaN else -ln(-value) / logBase
    } else {
      if (value <= 0.0) Double.NaN else ln(value) / logBase
    }
  }

  override fun backward(value: Double): Double =
    if (domain.first() < 0.0) -base.pow(-value) else base.pow(value)

  override fun ticks(count: Int): List<Double> =
    Ticks.logTicks(domain.first(), domain.last(), base, count)

  override fun formatTick(value: Double, count: Int): String =
    // Log ticks are powers and their small multiples, so a fixed decimal count does not apply.
    formatTickLabel(value, if (value == floor(value)) 0 else 2)

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
  override fun tickLabels(count: Int): List<String> {
    val values = ticks(count)
    if (values.isEmpty()) return emptyList()
    val threshold = maxOf(1.0, base * count / values.size)
    return values.map { value ->
      var mantissa = kotlin.math.abs(value) / base.pow(roundHalfUp(logMagnitude(value)))
      // Guard the case where floating point leaves the mantissa just under 1.
      if (mantissa * base < base - 0.5) mantissa *= base
      if (mantissa <= threshold + MANTISSA_EPSILON) formatTick(value, count) else ""
    }
  }

  /** The log of the magnitude, which is what the mantissa is measured against. */
  private fun logMagnitude(value: Double): Double = ln(kotlin.math.abs(value)) / logBase

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

  override fun forward(value: Double): Double {
    val scaled = value / constant
    return if (scaled < 0.0) -ln(1.0 - scaled) else ln(1.0 + scaled)
  }

  override fun backward(value: Double): Double =
    if (value < 0.0) -constant * (exp(-value) - 1.0) else constant * (exp(value) - 1.0)
}

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

  public fun tickLabels(count: Int = LinearScale.DEFAULT_TICK_COUNT): List<String> =
    ticks(count).map { TimeTicks.label(it, zone) }
}

/**
 * Ordinal scale: a discrete domain mapped to a discrete range, cycling when the range is shorter.
 */
public class OrdinalScale(
  override val name: String,
  public val domain: List<String>,
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

  private val indices: MutableMap<String, Int> =
    domain.withIndex().associateTo(LinkedHashMap()) { (index, value) -> value to index }

  override fun scale(value: VegaValue): VegaValue {
    if (rangeValues.isEmpty()) return unknown ?: VegaValue.Null
    val key = value.asString()
    val index =
      indices[key]
        ?: if (implicit) indices.size.also { indices[key] = it }
        else return unknown ?: VegaValue.Null
    return rangeValues[index % rangeValues.size]
  }

  /** The domain as it now stands, which [implicit] may have grown past what was declared. */
  public val effectiveDomain: List<String>
    get() = indices.keys.toList()
}

/**
 * The four scales that map a continuous input onto a **discrete** output.
 *
 * They differ only in where the cut points come from, and that difference is the whole choice a
 * specification is making:
 * - `quantize` cuts the domain into equal **intervals**, so a skewed column puts most of its rows
 *   into one bucket;
 * - `quantile` cuts it into equal **counts**, so every bucket holds the same number of rows however
 *   skewed the column is, and the buckets are of unequal width;
 * - `threshold` takes the cut points literally from the domain, which is how a specification says
 *   "these are the boundaries that matter" rather than deriving them;
 * - `bin-ordinal` treats the domain as bin *edges* and looks the bin up in an ordinal range, which
 *   is what pairs a `bin` transform with a colour scheme.
 *
 * All four resolve with a **right bisection**: a value equal to a cut point falls into the bucket
 * *above* it. That is d3's rule and it matters at every boundary — the interval is `[low, high)`,
 * so a value of exactly 25 on a 0-100 quantize with four buckets is in the second, not the first.
 */
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

private fun bisectRight(values: List<Double>, x: Double, high: Int = values.size): Int {
  var low = 0
  var hi = high
  while (low < hi) {
    val mid = (low + hi) ushr 1
    if (x < values[mid]) hi = mid else low = mid + 1
  }
  return low
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

  override val legendExtent: Pair<Double, Double>
    get() = (domain.firstOrNull() ?: 0.0) to (domain.lastOrNull() ?: 1.0)

  override fun scale(value: VegaValue): VegaValue {
    if (rangeValues.isEmpty()) return VegaValue.Null
    val x = value.asDouble()
    if (x.isNaN()) return VegaValue.Null
    return rangeValues[bisectRight(thresholds, x)]
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

  override val thresholds: List<Double> =
    (1 until maxOf(1, rangeValues.size)).map { i ->
      quantileSorted(sorted, i.toDouble() / maxOf(1, rangeValues.size))
    }

  /** The sample's own extent: a quantile scale's domain is the whole column, sorted. */
  override val legendExtent: Pair<Double, Double>
    get() = (sorted.firstOrNull() ?: 0.0) to (sorted.lastOrNull() ?: 1.0)

  override fun scale(value: VegaValue): VegaValue {
    if (rangeValues.isEmpty()) return VegaValue.Null
    val x = value.asDouble()
    if (x.isNaN()) return VegaValue.Null
    return rangeValues[bisectRight(thresholds, x)]
  }

  private fun quantileSorted(values: List<Double>, p: Double): Double {
    if (values.isEmpty()) return Double.NaN
    if (values.size == 1) return values[0]
    val position = (values.size - 1) * p
    val lower = kotlin.math.floor(position).toInt()
    val upper = kotlin.math.ceil(position).toInt()
    if (lower == upper) return values[lower]
    val weight = position - lower
    return values[lower] * (1.0 - weight) + values[upper] * weight
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
   * Widened by one band at each end, because the outermost buckets have no stated edge.
   *
   * A threshold scale's domain stops at its last cut point, so measuring against it would give the
   * first and last bands no width at all. Upstream pads by one average band; with a single cut
   * point and no average to take, it pads by a tenth.
   */
  override val legendExtent: Pair<Double, Double>
    get() {
      val lo = thresholds.firstOrNull() ?: 0.0
      val hi = thresholds.lastOrNull() ?: 1.0
      val count = thresholds.size - 1
      val adjust = if (count > 0) (hi - lo) / count else 0.1
      return (lo - adjust) to (hi + adjust)
    }

  override fun scale(value: VegaValue): VegaValue {
    if (rangeValues.isEmpty()) return VegaValue.Null
    val x = value.asDouble()
    if (x.isNaN()) return VegaValue.Null
    // d3 clamps the search to one fewer than the range length, so extra domain values past the end
    // of the range are ignored rather than indexing off it.
    val limit = minOf(thresholds.size, rangeValues.size - 1)
    return rangeValues[bisectRight(thresholds, x, limit)]
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

  /** The interior edges: the first and last bound the outermost buckets and label nothing. */
  override val thresholds: List<Double>
    get() = domain.drop(1).dropLast(1)

  /** The bin edges are the labels, and the last one bounds rather than opens a bucket. */
  override val legendValues: List<Double>
    get() = domain.dropLast(1)

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
 * Formats a number with a fixed number of decimals, trimming a trailing `.0`.
 *
 * A deliberately small subset of d3-format: enough for default tick labels. An explicit `format`
 * string in a specification is not supported and must be reported as a diagnostic by the caller
 * rather than silently ignored.
 */
/**
 * A continuous scale whose range is a colour ramp.
 *
 * Covers Vega's `sequential` type and a `linear` scale given a colour range. The position along the
 * ramp comes from the same normalization a numeric scale uses, so a colour scale and a positional
 * one over the same domain stay in step.
 */
public class SequentialColorScale(
  override val name: String,
  public val domain: List<Double>,
  public val colors: List<SceneColor>,
  public val space: ColorSpaces.Interpolation = ColorSpaces.Interpolation.RGB,
  /** `interpolate: {"type": "rgb", "gamma": y}` — only the RGB space has one. */
  public val gamma: Double = 1.0,
  public val clamp: Boolean = true,
) : VegaScale {

  init {
    require(domain.size >= 2) { "$name needs at least two domain values, got $domain" }
    require(colors.isNotEmpty()) { "$name needs at least one colour" }
  }

  /** The colour at [x], or `null` when the input cannot be placed on the ramp. */
  public fun colorAt(x: Double): SceneColor? {
    if (x.isNaN()) return null
    val lo = domain.first()
    val hi = domain.last()
    if (lo == hi) return colors.last()
    val raw = (x - lo) / (hi - lo)
    // Sequential scales clamp by default, since a colour past the end of a ramp has no meaning.
    if (!clamp && (raw < 0.0 || raw > 1.0)) return null
    return ColorSpaces.sample(colors, raw.coerceIn(0.0, 1.0), space, gamma)
  }

  override fun scale(value: VegaValue): VegaValue {
    val colour = colorAt(value.asDouble()) ?: return VegaValue.Null
    return VegaValue.Str(colour.toCssHex())
  }

  /**
   * Where [x] sits along the ramp, in `0..1`.
   *
   * A gradient legend needs this twice over: once for each stop's offset, and once to place each
   * label against the swatch.
   */
  public fun fraction(x: Double): Double {
    val lo = domain.first()
    val hi = domain.last()
    if (lo == hi) return 0.0
    return ((x - lo) / (hi - lo)).coerceIn(0.0, 1.0)
  }

  /** Tick values across the domain, as a linear scale over the same domain would produce. */
  public fun ticks(count: Int = LinearScale.DEFAULT_TICK_COUNT): List<Double> =
    Ticks.ticks(domain.first(), domain.last(), count)

  /** Default label text for [value], with the decimals the tick step implies. */
  public fun formatTick(value: Double, count: Int = LinearScale.DEFAULT_TICK_COUNT): String {
    val step = Ticks.stepFrom(Ticks.tickIncrement(domain.first(), domain.last(), count))
    return formatTickLabel(
      value,
      if (step.isFinite()) Ticks.precisionForStep(step) else DEGENERATE_PRECISION,
    )
  }

  public fun tickLabels(count: Int = LinearScale.DEFAULT_TICK_COUNT): List<String> {
    val step = Ticks.stepFrom(Ticks.tickIncrement(domain.first(), domain.last(), count))
    val precision = if (step.isFinite()) Ticks.precisionForStep(step) else DEGENERATE_PRECISION
    return ticks(count).map { formatTickLabel(it, precision) }
  }
}

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

public fun formatTickLabel(value: Double, decimals: Int): String =
  withTypographicMinus(groupThousands(formatNumber(value, decimals)))

/** Inserts `,` every three digits of the integer part, leaving any fraction alone. */
public fun groupThousands(text: String): String {
  val negative = text.startsWith("-")
  val body = if (negative) text.substring(1) else text
  val dot = body.indexOf('.')
  val integerPart = if (dot < 0) body else body.substring(0, dot)
  if (integerPart.length <= 3 || integerPart.any { !it.isDigit() }) return text
  val fraction = if (dot < 0) "" else body.substring(dot)
  val grouped = integerPart.reversed().chunked(3).joinToString(",").reversed()
  return (if (negative) "-" else "") + grouped + fraction
}

public fun formatNumber(value: Double, decimals: Int): String {
  if (value.isNaN()) return "NaN"
  // d3-format spells these the way JavaScript does rather than with the mathematical symbol.
  if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
  val normalized = if (value == 0.0) 0.0 else value
  if (decimals <= 0) {
    val rounded = roundHalfUp(normalized).toLong()
    return rounded.toString()
  }
  val text = PlatformDecimals.fixed(normalized, decimals)
  return if (text == "-0" || text.matches(Regex("-0\\.0+"))) text.substring(1) else text
}
