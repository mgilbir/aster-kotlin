package dev.aster.vega.runtime.scale

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import kotlin.math.floor
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
  public val range: List<Double>,
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
    return formatNumber(value, precision)
  }

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
  public val range: List<Double>,
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
  public val range: List<Double>,
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
