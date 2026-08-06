package dev.aster.vega.runtime.scale

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
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

  /** Maps a data value into range space. Returns [VegaValue.Null] for an unmappable value. */
  public fun scale(value: VegaValue): VegaValue
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
) : PositionScale {

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

  override fun scale(value: VegaValue): VegaValue {
    val result = position(value)
    return if (result.isNaN()) VegaValue.Null else VegaValue.Num(result)
  }

  public fun apply(x: Double): Double {
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

  private fun interpolate(d0: Double, d1: Double, r0: Double, r1: Double, x: Double): Double {
    if (d0 == d1) return (r0 + r1) / 2.0
    val t = (x - d0) / (d1 - d0)
    return r0 + t * (r1 - r0)
  }

  /** The data value that maps to range position [y]. Only defined for a two-point domain. */
  public fun invert(y: Double): Double {
    if (domain.size != 2 || range.size != 2) return Double.NaN
    val r0 = range[0]
    val r1 = range[1]
    if (r0 == r1) return Double.NaN
    val t = (y - r0) / (r1 - r0)
    return domain[0] + t * (domain[1] - domain[0])
  }

  public fun ticks(count: Int = DEFAULT_TICK_COUNT): List<Double> =
    Ticks.ticks(domain.first(), domain.last(), count)

  /** Default label text for a tick, matching Vega's digits-from-step behaviour. */
  public fun formatTick(value: Double, count: Int = DEFAULT_TICK_COUNT): String {
    val step = Ticks.stepFrom(Ticks.tickIncrement(domain.first(), domain.last(), count))
    val precision = if (step.isFinite()) Ticks.precisionForStep(step) else 0
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
) : PositionScale {

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

  override fun scale(value: VegaValue): VegaValue {
    val result = position(value)
    return if (result.isNaN()) VegaValue.Null else VegaValue.Num(result)
  }

  public fun apply(x: Double): Double {
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

  public fun invert(y: Double): Double {
    val r0 = range.first()
    val r1 = range.last()
    if (r0 == r1) return Double.NaN
    val d0 = forward(domain.first())
    val d1 = forward(domain.last())
    return backward(d0 + ((y - r0) / (r1 - r0)) * (d1 - d0))
  }

  public open fun ticks(count: Int = LinearScale.DEFAULT_TICK_COUNT): List<Double> =
    Ticks.ticks(domain.first(), domain.last(), count)

  public open fun formatTick(value: Double, count: Int = LinearScale.DEFAULT_TICK_COUNT): String {
    val step = Ticks.stepFrom(Ticks.tickIncrement(domain.first(), domain.last(), count))
    return formatTickLabel(value, if (step.isFinite()) Ticks.precisionForStep(step) else 0)
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
) : TransformedScale(name, domain, range, clamp) {

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
      var mantissa = kotlin.math.abs(value) / base.pow(Math.round(logMagnitude(value)).toDouble())
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
) : TransformedScale(name, domain, range, clamp) {

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
) : TransformedScale(name, domain, range, clamp) {

  override fun forward(value: Double): Double {
    val scaled = value / constant
    return if (scaled < 0.0) -ln(1.0 - scaled) else ln(1.0 + scaled)
  }

  override fun backward(value: Double): Double =
    if (value < 0.0) -constant * (exp(-value) - 1.0) else constant * (exp(value) - 1.0)
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
) : VegaScale {

  private val indices: Map<String, Int> =
    domain.withIndex().associate { (index, value) -> value to index }

  override fun scale(value: VegaValue): VegaValue {
    if (rangeValues.isEmpty()) return unknown ?: VegaValue.Null
    val index = indices[value.asString()] ?: return unknown ?: VegaValue.Null
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
    return ColorSpaces.sample(colors, raw.coerceIn(0.0, 1.0), space)
  }

  override fun scale(value: VegaValue): VegaValue {
    val colour = colorAt(value.asDouble()) ?: return VegaValue.Null
    return VegaValue.Str(colour.toCssHex())
  }
}

/**
 * Formats an axis tick label the way Vega's default does: fixed decimals plus thousands separators.
 *
 * Grouping is not optional here. Verified against upstream: a linear axis over `[0, 1000000]`
 * labels `100,000`, not `100000`, and so does a log axis.
 */
public fun formatTickLabel(value: Double, decimals: Int): String =
  groupThousands(formatNumber(value, decimals))

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
  if (value.isInfinite()) return if (value > 0) "∞" else "-∞"
  val normalized = if (value == 0.0) 0.0 else value
  if (decimals <= 0) {
    val rounded = Math.round(normalized)
    return rounded.toString()
  }
  val text = String.format(java.util.Locale.ROOT, "%.${decimals}f", normalized)
  return if (text == "-0" || text.matches(Regex("-0\\.0+"))) text.substring(1) else text
}
