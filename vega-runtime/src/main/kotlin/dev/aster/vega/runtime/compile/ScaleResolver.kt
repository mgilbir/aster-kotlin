package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import dev.aster.vega.model.isMissing
import dev.aster.vega.model.spec.DomainSpec
import dev.aster.vega.model.spec.RangeSpec
import dev.aster.vega.model.spec.ScaleSpec
import dev.aster.vega.model.spec.ScaleType
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.LinearScale
import dev.aster.vega.runtime.scale.LogScale
import dev.aster.vega.runtime.scale.OrdinalScale
import dev.aster.vega.runtime.scale.PointScale
import dev.aster.vega.runtime.scale.PowScale
import dev.aster.vega.runtime.scale.SymlogScale
import dev.aster.vega.runtime.scale.Ticks
import dev.aster.vega.runtime.scale.VegaScale

/** The chart's plotting size, which named ranges like `"width"` resolve against. */
public data class PlotSize(val width: Double, val height: Double)

/**
 * Builds runtime scales from parsed [ScaleSpec]s and resolved data.
 *
 * Resolution order matters and follows upstream: the domain comes from the data, then `zero` widens
 * it, then `nice` rounds it. Unsupported scale types produce `VEGA_SCALE_UNSUPPORTED_TYPE` and are
 * omitted, so a mark referencing one reports a second diagnostic rather than rendering at the
 * origin.
 */
public class ScaleResolver(
  private val datasets: Map<String, List<VegaValue>>,
  private val size: PlotSize,
  private val diagnostics: DiagnosticCollector,
  /** Resolves scale properties that a specification supplied as signals. */
  private val numbers: NumberResolver,
) {

  public fun resolve(specs: List<ScaleSpec>): Map<String, VegaScale> {
    val result = LinkedHashMap<String, VegaScale>(specs.size)
    for (spec in specs) {
      val scale = build(spec)
      if (scale != null) result[spec.name] = scale
    }
    return result
  }

  private fun build(spec: ScaleSpec): VegaScale? =
    when (spec.type) {
      ScaleType.LINEAR -> buildLinear(spec)
      ScaleType.LOG -> buildLog(spec)
      ScaleType.POW -> buildPow(spec, defaultExponent = 1.0)
      ScaleType.SQRT -> buildPow(spec, defaultExponent = 0.5)
      ScaleType.SYMLOG -> buildSymlog(spec)
      ScaleType.BAND -> buildBand(spec)
      ScaleType.POINT -> buildPoint(spec)
      ScaleType.ORDINAL -> buildOrdinal(spec)
      else -> {
        diagnostics.error(
          DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
          "Scale type '${spec.type.name.lowercase()}' is not implemented yet " +
            "(scale '${spec.name}'); marks using it will not be positioned",
          operator = spec.name,
        )
        null
      }
    }

  private fun buildLinear(spec: ScaleSpec): LinearScale? {
    val range = numericRange(spec) ?: return null
    val explicit = literalNumbers(spec.domain)
    if (explicit != null) {
      if (explicit.size < 2) {
        diagnostics.error(
          DiagnosticCodes.SCALE_INVALID_DOMAIN,
          "A linear scale needs at least two domain values (scale '${spec.name}')",
          operator = spec.name,
        )
        return null
      }
      val domain = if (spec.nice) niceOf(explicit, spec) else explicit
      return LinearScale(spec.name, orient(domain, spec.reverse), range, spec.clamp)
    }

    val extent = numericExtent(spec.domain, spec.name)
    // Vega defaults `zero` to true for a data-driven quantitative domain; verified against
    // upstream.
    val zero = spec.zero ?: true
    val scale =
      LinearScale.fromExtent(
        name = spec.name,
        extent = extent,
        range = range,
        zero = zero,
        nice = spec.nice,
        niceCount = spec.niceCount ?: LinearScale.DEFAULT_TICK_COUNT,
        clamp = spec.clamp,
      )
    return if (spec.reverse) {
      LinearScale(spec.name, scale.domain.reversed(), range, spec.clamp)
    } else {
      scale
    }
  }

  private fun buildLog(spec: ScaleSpec): LogScale? {
    val range = numericRange(spec) ?: return null
    val base = numbers.resolve(spec.base, spec.name) ?: 10.0
    // A log domain cannot include zero, and `zero: true` would force it to, so it never applies
    // here.
    var domain = explicitOrExtent(spec, zeroDefault = false) ?: return null
    if (spec.nice) domain = Ticks.niceLog(domain, base)

    val scale = LogScale(spec.name, orient(domain, spec.reverse), range, base, spec.clamp)
    if (!scale.isValid) {
      diagnostics.error(
        DiagnosticCodes.SCALE_INVALID_DOMAIN,
        "Log scale '${spec.name}' has a domain of $domain, which spans or touches zero; " +
          "marks using it cannot be positioned",
        operator = spec.name,
      )
      return null
    }
    return scale
  }

  private fun buildPow(spec: ScaleSpec, defaultExponent: Double): PowScale? {
    val range = numericRange(spec) ?: return null
    var domain = explicitOrExtent(spec, zeroDefault = true) ?: return null
    if (spec.nice) domain = niceOf(domain, spec)
    val exponent = numbers.resolve(spec.exponent, spec.name) ?: defaultExponent
    return PowScale(spec.name, orient(domain, spec.reverse), range, exponent, spec.clamp)
  }

  private fun buildSymlog(spec: ScaleSpec): SymlogScale? {
    val range = numericRange(spec) ?: return null
    var domain = explicitOrExtent(spec, zeroDefault = true) ?: return null
    if (spec.nice) domain = niceOf(domain, spec)
    val constant = numbers.resolve(spec.constant, spec.name) ?: 1.0
    return SymlogScale(spec.name, orient(domain, spec.reverse), range, constant, spec.clamp)
  }

  /**
   * The domain for a continuous scale: the literal if there is one, otherwise the data extent.
   *
   * [zeroDefault] is whether this scale type includes zero by default when the domain comes from
   * data. Log scales never do, because a domain containing zero is unusable.
   */
  private fun explicitOrExtent(spec: ScaleSpec, zeroDefault: Boolean): List<Double>? {
    literalNumbers(spec.domain)?.let { explicit ->
      if (explicit.size >= 2) return explicit
      diagnostics.error(
        DiagnosticCodes.SCALE_INVALID_DOMAIN,
        "Scale '${spec.name}' needs at least two domain values",
        operator = spec.name,
      )
      return null
    }
    val extent = numericExtent(spec.domain, spec.name) ?: return listOf(1.0, 10.0)
    var lo = extent.start
    var hi = extent.endInclusive
    if (spec.zero ?: zeroDefault) {
      lo = minOf(lo, 0.0)
      hi = maxOf(hi, 0.0)
    }
    return listOf(lo, hi)
  }

  private fun buildBand(spec: ScaleSpec): BandScale? {
    val range = numericRange(spec) ?: return null
    val domain = discreteDomain(spec.domain, spec.name) ?: return null
    // `padding` is shorthand for both inner and outer; explicit values win.
    val padding = numbers.resolve(spec.padding, spec.name)
    return BandScale(
      name = spec.name,
      domain = if (spec.reverse) domain.reversed() else domain,
      range = range,
      paddingInner = numbers.resolve(spec.paddingInner, spec.name) ?: padding ?: 0.0,
      paddingOuter = numbers.resolve(spec.paddingOuter, spec.name) ?: padding ?: 0.0,
      align = numbers.resolve(spec.align, spec.name) ?: 0.5,
      round = spec.round,
    )
  }

  private fun buildPoint(spec: ScaleSpec): PointScale? {
    val range = numericRange(spec) ?: return null
    val domain = discreteDomain(spec.domain, spec.name) ?: return null
    return PointScale(
      name = spec.name,
      domain = if (spec.reverse) domain.reversed() else domain,
      range = range,
      padding =
        numbers.resolve(spec.paddingOuter, spec.name)
          ?: numbers.resolve(spec.padding, spec.name)
          ?: 0.0,
      align = numbers.resolve(spec.align, spec.name) ?: 0.5,
      round = spec.round,
    )
  }

  private fun buildOrdinal(spec: ScaleSpec): OrdinalScale? {
    val domain = discreteDomain(spec.domain, spec.name) ?: return null
    val range =
      when (val r = spec.range) {
        is RangeSpec.Literal -> r.values
        is RangeSpec.Scheme -> {
          diagnostics.error(
            DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
            "Colour schemes are not implemented; scale '${spec.name}' has no range",
            operator = spec.name,
          )
          return null
        }
        else -> {
          diagnostics.error(
            DiagnosticCodes.SCALE_INVALID_DOMAIN,
            "An ordinal scale needs an explicit range array (scale '${spec.name}')",
            operator = spec.name,
          )
          return null
        }
      }
    return OrdinalScale(spec.name, domain, range)
  }

  // ---- domains --------------------------------------------------------------

  private fun literalNumbers(domain: DomainSpec): List<Double>? {
    val literal = domain as? DomainSpec.Literal ?: return null
    val numbers = literal.values.map { it.asDouble() }
    return if (numbers.any { it.isNaN() }) null else numbers
  }

  private fun orient(domain: List<Double>, reverse: Boolean): List<Double> =
    if (reverse) domain.reversed() else domain

  private fun niceOf(domain: List<Double>, spec: ScaleSpec): List<Double> =
    dev.aster.vega.runtime.scale.Ticks.nice(
      domain,
      spec.niceCount ?: LinearScale.DEFAULT_TICK_COUNT,
    )

  private fun numericExtent(
    domain: DomainSpec,
    scaleName: String,
  ): ClosedFloatingPointRange<Double>? {
    val values =
      when (domain) {
        is DomainSpec.FromField -> fieldValues(domain.data, listOf(domain.field), scaleName)
        is DomainSpec.FromFields -> fieldValues(domain.data, domain.fields, scaleName)
        is DomainSpec.Literal -> domain.values
        DomainSpec.Unset -> {
          diagnostics.error(
            DiagnosticCodes.SCALE_INVALID_DOMAIN,
            "Scale '$scaleName' has no domain; using [0, 1]",
            operator = scaleName,
          )
          return null
        }
      }
    val numbers = values.map { it.asDouble() }.filter { it.isFinite() }
    if (numbers.isEmpty()) {
      diagnostics.warn(
        DiagnosticCodes.SCALE_INVALID_DOMAIN,
        "Scale '$scaleName' has no finite numeric values in its domain; using [0, 1]",
        operator = scaleName,
      )
      return null
    }
    return numbers.min()..numbers.max()
  }

  private fun discreteDomain(domain: DomainSpec, scaleName: String): List<String>? {
    val values =
      when (domain) {
        is DomainSpec.Literal -> domain.values
        is DomainSpec.FromField -> {
          val raw = fieldValues(domain.data, listOf(domain.field), scaleName)
          if (domain.sort) raw.sortedBy { it.asString() } else raw
        }
        is DomainSpec.FromFields -> fieldValues(domain.data, domain.fields, scaleName)
        DomainSpec.Unset -> {
          diagnostics.error(
            DiagnosticCodes.SCALE_INVALID_DOMAIN,
            "Scale '$scaleName' has no domain",
            operator = scaleName,
          )
          return null
        }
      }
    // Vega's discrete domains keep first-seen order and drop duplicates.
    return values.map { it.asString() }.distinct()
  }

  private fun fieldValues(
    dataName: String,
    fields: List<String>,
    scaleName: String,
  ): List<VegaValue> {
    val dataset = datasets[dataName]
    if (dataset == null) {
      diagnostics.error(
        DiagnosticCodes.SCALE_INVALID_DOMAIN,
        "Scale '$scaleName' refers to unknown dataset '$dataName'",
        operator = scaleName,
      )
      return emptyList()
    }
    return dataset.flatMap { datum ->
      fields.map { datum.field(it) }.filterNot { it.isMissing }
    }
  }

  // ---- ranges ---------------------------------------------------------------

  private fun numericRange(spec: ScaleSpec): List<Double>? =
    when (val range = spec.range) {
      is RangeSpec.Named ->
        when (range.name.lowercase()) {
          // Vega's "height" range is descending, so larger values sit higher on screen.
          "width" -> listOf(0.0, size.width)
          "height" -> listOf(size.height, 0.0)
          else -> {
            diagnostics.error(
              DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
              "Named range '${range.name}' is not implemented (scale '${spec.name}')",
              operator = spec.name,
            )
            null
          }
        }
      is RangeSpec.Literal -> {
        val numbers = range.values.map { it.asDouble() }
        if (numbers.size < 2 || numbers.any { it.isNaN() }) {
          diagnostics.error(
            DiagnosticCodes.SCALE_INVALID_DOMAIN,
            "Scale '${spec.name}' needs a numeric two-value range",
            operator = spec.name,
          )
          null
        } else {
          numbers
        }
      }
      is RangeSpec.Scheme -> {
        diagnostics.error(
          DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
          "Colour schemes are not implemented (scale '${spec.name}')",
          operator = spec.name,
        )
        null
      }
      RangeSpec.Unset -> {
        diagnostics.error(
          DiagnosticCodes.SCALE_INVALID_DOMAIN,
          "Scale '${spec.name}' has no range",
          operator = spec.name,
        )
        null
      }
    }
}
