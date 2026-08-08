package dev.aster.vega.runtime.compile

import dev.aster.vega.dataflow.transform.AggregateOp
import dev.aster.vega.dataflow.transform.aggregateOver
import dev.aster.vega.dataflow.transform.compareFieldValues
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import dev.aster.vega.model.isMissing
import dev.aster.vega.model.spec.DomainSort
import dev.aster.vega.model.spec.DomainSpec
import dev.aster.vega.model.spec.RangeSpec
import dev.aster.vega.model.spec.ScaleSpec
import dev.aster.vega.model.spec.ScaleType
import dev.aster.vega.model.spec.SchemeRef
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.BinOrdinalScale
import dev.aster.vega.runtime.scale.ColorSchemes
import dev.aster.vega.runtime.scale.LinearScale
import dev.aster.vega.runtime.scale.LogScale
import dev.aster.vega.runtime.scale.OrdinalScale
import dev.aster.vega.runtime.scale.PointScale
import dev.aster.vega.runtime.scale.PowScale
import dev.aster.vega.runtime.scale.QuantileScale
import dev.aster.vega.runtime.scale.QuantizeScale
import dev.aster.vega.runtime.scale.SequentialColorScale
import dev.aster.vega.runtime.scale.SymlogScale
import dev.aster.vega.runtime.scale.ThresholdScale
import dev.aster.vega.runtime.scale.Ticks
import dev.aster.vega.runtime.scale.TimeScale
import dev.aster.vega.runtime.scale.TimeTicks
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.ColorSpaces
import dev.aster.vega.scene.SceneColor
import kotlinx.datetime.TimeZone

/** The chart's plotting size, which named ranges like `"width"` resolve against. */
public data class PlotSize(val width: Double, val height: Double)

/** The field name the per-field sort summaries are wrapped in before being folded together. */
private const val MULTI_FIELD_COMBINE_FIELD = "value"

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
      // A linear scale with a colour range is a colour scale, not a positional one.
      ScaleType.LINEAR -> if (hasColorRange(spec)) buildSequentialColor(spec) else buildLinear(spec)
      ScaleType.SEQUENTIAL -> buildSequentialColor(spec)
      ScaleType.LOG -> buildLog(spec)
      ScaleType.POW -> buildPow(spec, defaultExponent = 1.0)
      ScaleType.SQRT -> buildPow(spec, defaultExponent = 0.5)
      ScaleType.SYMLOG -> buildSymlog(spec)
      ScaleType.TIME -> buildTime(spec, TimeZone.currentSystemDefault())
      ScaleType.UTC -> buildTime(spec, TimeZone.UTC)
      ScaleType.BAND -> buildBand(spec)
      ScaleType.POINT -> buildPoint(spec)
      ScaleType.ORDINAL -> buildOrdinal(spec)
      ScaleType.QUANTIZE -> buildQuantize(spec)
      ScaleType.QUANTILE -> buildQuantile(spec)
      ScaleType.THRESHOLD -> buildThreshold(spec)
      ScaleType.BIN_ORDINAL -> buildBinOrdinal(spec)
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
    // `zero` defaults to true for a linear scale whether or not the domain was written out —
    // upstream keys it off the scale type, so `domain: [10, 20]` still starts at 0.
    var domain =
      continuousDomain(spec, zeroDefault = true, fallback = listOf(0.0, 1.0)) ?: return null
    if (spec.nice) domain = niceOf(domain, spec)
    return LinearScale(spec.name, domain, oriented(range, spec.reverse), spec.clamp, spec.round)
  }

  /** True when the scale's range is colours rather than numbers. */
  private fun hasColorRange(spec: ScaleSpec): Boolean =
    when (val range = effectiveRange(spec)) {
      is RangeSpec.Scheme -> true
      is RangeSpec.Literal ->
        range.values.isNotEmpty() && range.values.all { SceneColor.parse(it.asString()) != null }
      else -> false
    }

  private fun buildSequentialColor(spec: ScaleSpec): SequentialColorScale? {
    val colors = colorRange(spec) ?: return null
    val domain =
      continuousDomain(spec, zeroDefault = false, fallback = listOf(0.0, 1.0)) ?: return null
    val space =
      spec.interpolate?.let { name ->
        ColorSpaces.Interpolation.fromName(name)
          ?: run {
            diagnostics.warn(
              DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
              "Colour interpolation space '$name' is not implemented; interpolating in RGB instead",
              operator = spec.name,
            )
            null
          }
      } ?: ColorSpaces.Interpolation.RGB

    return SequentialColorScale(
      name = spec.name,
      domain = domain,
      colors = if (spec.reverse) colors.reversed() else colors,
      space = space,
    )
  }

  /**
   * The colour list a scale's range describes.
   *
   * A named categorical scheme resolves to its palette. A ramp scheme is reported instead:
   * reproducing one needs d3's interpolator table and Vega's default scheme extent, and
   * approximating either would be wrong in a way that still looks like a chart.
   */
  /**
   * Vega's **named ranges**: `"range": "category"` and its siblings.
   *
   * These are not scheme names and not literal arrays — they are keys into `config.range`, which
   * ships defaults for each. `category` is how almost every real specification asks for a
   * categorical palette, including the stacked-bar example in Vega's own documentation, so a scale
   * that rejects it rejects most charts anyone will paste.
   *
   * The defaults are upstream's own (`vega-parser/src/config.js`). A specification that overrides
   * them through `config.range` is **not** honoured yet, and the config block is reported as unread
   * — so the substitution is visible rather than silent.
   */
  private fun namedRange(name: String): RangeSpec? =
    when (name.lowercase()) {
      "category" -> RangeSpec.Scheme(SchemeRef.Named("tableau10"))
      "ordinal" -> RangeSpec.Scheme(SchemeRef.Named("blues"))
      "ramp" -> RangeSpec.Scheme(SchemeRef.Named("blues"))
      "heatmap" -> RangeSpec.Scheme(SchemeRef.Named("yellowgreenblue"))
      // Upstream pairs this one with `extent: [1, 0]`, which reads the scheme backwards.
      "diverging" -> RangeSpec.Scheme(SchemeRef.Named("blueorange"))
      "symbol" ->
        RangeSpec.Literal(
          listOf(
              "circle",
              "square",
              "triangle-up",
              "cross",
              "diamond",
              "triangle-right",
              "triangle-down",
              "triangle-left",
            )
            .map { VegaValue.Str(it) }
        )
      else -> null
    }

  /** True for the named ranges that read their scheme backwards. */
  private fun isReversedNamedRange(spec: ScaleSpec): Boolean =
    (spec.range as? RangeSpec.Named)?.name?.lowercase() == "diverging"

  /**
   * The range to resolve against, with a named one already turned into what it stands for.
   *
   * Done once here rather than at each use, so every scale type gets named ranges for free and none
   * of them can forget.
   */
  private fun effectiveRange(spec: ScaleSpec): RangeSpec =
    when (val range = spec.range) {
      is RangeSpec.Named -> namedRange(range.name) ?: range
      is RangeSpec.Signal ->
        resolveRangeSignal(spec, range.expression).let {
          if (it is RangeSpec.Named) namedRange(it.name) ?: it else it
        }
      // A range array may hold signal references among its literals — `[{"signal": "height"}, 0]`
      // is how a chart reverses an axis whose extent is computed. Each element resolves on its own,
      // because the array as a whole is not a reference and only part of it may be one.
      is RangeSpec.Literal -> RangeSpec.Literal(range.values.map { resolveRangeElement(spec, it) })
      else -> range
    }

  private fun resolveRangeElement(spec: ScaleSpec, value: VegaValue): VegaValue {
    val reference = (value as? VegaValue.Obj)?.takeIf { it.fields.size == 1 }?.fields?.get("signal")
    if (reference !is VegaValue.Str) return value
    return numbers.resolveValue(reference.value, spec.name) ?: VegaValue.Null
  }

  /**
   * The stops a scheme supplies, whichever of the three ways it was named.
   *
   * Upstream lowercases a scheme name before looking it up (`vega-encode/src/scale.js`), so a
   * palette picker offering "Viridis" finds `viridis`. Stops written out inline are not looked up
   * at all — they are already the table every named scheme resolves to.
   */
  private fun schemeColors(spec: ScaleSpec, range: RangeSpec.Scheme): List<SceneColor>? {
    if (range.scheme is SchemeRef.Colors) {
      val values = (range.scheme as SchemeRef.Colors).values
      val colors = values.mapNotNull { SceneColor.parse(it.asString()) }
      if (colors.size < values.size) {
        diagnostics.error(
          DiagnosticCodes.SCALE_INVALID_DOMAIN,
          "Scale '${spec.name}' has a scheme containing unparseable colours",
          operator = spec.name,
        )
        return null
      }
      return colors.ifEmpty { null }
    }
    val name =
      when (val scheme = range.scheme) {
        is SchemeRef.Named -> scheme.name
        is SchemeRef.Signal ->
          numbers
            .resolveValue(scheme.expression, spec.name)
            ?.takeIf { it !is VegaValue.Null }
            ?.asString()
            ?: run {
              diagnostics.error(
                DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
                "Scheme signal '${scheme.expression}' produced no scheme name (scale " +
                  "'${spec.name}')",
                operator = spec.name,
              )
              return null
            }
        is SchemeRef.Colors -> return null // handled above
      }.lowercase()

    val palette = ColorSchemes.categoricalOrNull(name)
    // A ramp's stops are a colour list like any other; the scale interpolates between them.
    val ramp = ColorSchemes.rampOrNull(name)
    return when {
      // `count` truncates a palette for the discretizing scales, but **not** for an ordinal one:
      // upstream's `configureScheme` ends `type === Ordinal ? scheme : scheme.slice(0, count)`, so
      // an ordinal scale keeps the whole palette and cycles through it. Truncating there would
      // repeat the first colours instead of reaching the later ones.
      palette != null ->
        if (spec.type == ScaleType.ORDINAL) palette
        else range.count?.let { palette.take(it.coerceAtLeast(1)) } ?: palette
      ramp != null -> ramp
      else -> {
        diagnostics.error(
          DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
          "Unknown colour scheme '$name'",
          operator = spec.name,
        )
        null
      }
    }
  }

  private fun colorRange(spec: ScaleSpec): List<SceneColor>? =
    when (val range = effectiveRange(spec)) {
      is RangeSpec.Literal -> {
        val colors = range.values.mapNotNull { SceneColor.parse(it.asString()) }
        if (colors.size < range.values.size) {
          diagnostics.error(
            DiagnosticCodes.SCALE_INVALID_DOMAIN,
            "Scale '${spec.name}' has a range containing unparseable colours",
            operator = spec.name,
          )
          null
        } else {
          colors.ifEmpty { null }
        }
      }
      is RangeSpec.Scheme -> schemeColors(spec, range)
      else -> null
    }

  private fun buildLog(spec: ScaleSpec): LogScale? {
    val range = numericRange(spec) ?: return null
    val base = numbers.resolve(spec.base, spec.name) ?: 10.0
    // A log domain cannot include zero, and `zero: true` would force it to, so it never applies
    // here.
    var domain =
      continuousDomain(spec, zeroDefault = false, fallback = listOf(1.0, 10.0)) ?: return null
    if (spec.nice) domain = Ticks.niceLog(domain, base)

    val scale =
      LogScale(spec.name, domain, oriented(range, spec.reverse), base, spec.clamp, spec.round)
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
    var domain =
      continuousDomain(spec, zeroDefault = true, fallback = listOf(0.0, 1.0)) ?: return null
    if (spec.nice) domain = niceOf(domain, spec)
    val exponent = numbers.resolve(spec.exponent, spec.name) ?: defaultExponent
    return PowScale(
      spec.name,
      domain,
      oriented(range, spec.reverse),
      exponent,
      spec.clamp,
      spec.round,
    )
  }

  private fun buildSymlog(spec: ScaleSpec): SymlogScale? {
    val range = numericRange(spec) ?: return null
    // Symlog is not in upstream's zero list: its domain reaches both signs happily.
    var domain =
      continuousDomain(spec, zeroDefault = false, fallback = listOf(0.0, 1.0)) ?: return null
    if (spec.nice) domain = niceOf(domain, spec)
    val constant = numbers.resolve(spec.constant, spec.name) ?: 1.0
    return SymlogScale(
      spec.name,
      domain,
      oriented(range, spec.reverse),
      constant,
      spec.clamp,
      spec.round,
    )
  }

  /**
   * A continuous scale's domain, in upstream's order: the values, then `zero`, then the explicit
   * limits. `nice` is the caller's, because each scale type rounds differently.
   *
   * Two things here are visible only in upstream's `configureDomain`, and a reasonable reading gets
   * both wrong:
   * - `zero` keys off the **scale type**, not off whether the domain was written out. A linear
   *   scale handed `[10, 20]` still starts at 0, which is not what "explicit domain" suggests. It
   *   applies to linear, pow and sqrt and to nothing else — not log, and not symlog, whose domain
   *   reaches both signs happily.
   * - `domainMin` and `domainMax` **replace** an end rather than clamping it, and they run *after*
   *   `zero`, so `domainMin: 30` beats the zero that would otherwise have pulled the domain down.
   *   Upstream does not correct a minimum placed above the maximum either; it leaves the domain
   *   running backwards.
   *
   * @param zeroDefault whether this scale type includes zero when the specification is silent.
   * @param fallback the domain to use when nothing resolved; a log-family scale cannot take `[0,
   *   1]`.
   */
  private fun continuousDomain(
    spec: ScaleSpec,
    zeroDefault: Boolean,
    fallback: List<Double>,
  ): List<Double>? {
    val resolved =
      literalNumbers(spec.domain)?.also {
        if (it.size < 2) {
          diagnostics.error(
            DiagnosticCodes.SCALE_INVALID_DOMAIN,
            "Scale '${spec.name}' needs at least two domain values",
            operator = spec.name,
          )
          return null
        }
      }
        ?: numericExtent(spec.domain, spec.name)?.let { listOf(it.start, it.endInclusive) }
        ?: return fallback

    val domain = resolved.toMutableList()
    val last = domain.size - 1
    if (spec.zero ?: zeroDefault) {
      // Per end, as upstream writes it, rather than a symmetric min/max: only a positive low end
      // and a negative high end move.
      if (domain[0] > 0.0) domain[0] = 0.0
      if (domain[last] < 0.0) domain[last] = 0.0
    }
    numbers.resolve(spec.domainMin, spec.name)?.let { domain[0] = it }
    numbers.resolve(spec.domainMax, spec.name)?.let { domain[last] = it }
    numbers.resolve(spec.domainMid, spec.name)?.let { mid ->
      // Upstream inserts before the last value, and warns rather than clamping when the midpoint
      // falls outside the domain it is meant to divide.
      val at =
        when {
          mid > domain[last] -> last + 1
          mid < domain[0] -> 0
          else -> last
        }
      if (at != last) {
        diagnostics.warn(
          DiagnosticCodes.SCALE_INVALID_DOMAIN,
          "Scale '${spec.name}' has a domainMid of $mid outside its domain $domain",
          operator = spec.name,
        )
      }
      domain.add(at, mid)
    }
    return domain
  }

  /**
   * A time or UTC scale.
   *
   * The only difference between the two is the zone, and it is not cosmetic: it decides where a day
   * starts, so the same specification ticks differently in Sydney and in Reykjavik. That is
   * upstream's behaviour and the reason `utc` exists as a separate type.
   */
  private fun buildTime(spec: ScaleSpec, zone: TimeZone): TimeScale? {
    val range = numericRange(spec) ?: return null
    val explicit = literalNumbers(spec.domain)
    val domain =
      if (explicit != null && explicit.size >= 2) explicit
      else {
        val extent = numericExtent(spec.domain, spec.name) ?: return null
        listOf(extent.start, extent.endInclusive)
      }
    val niced =
      if (spec.nice) {
        val (lo, hi) =
          TimeTicks.nice(
            domain.first(),
            domain.last(),
            spec.niceCount ?: LinearScale.DEFAULT_TICK_COUNT,
            zone,
          )
        listOf(lo, hi)
      } else {
        domain
      }
    return TimeScale(spec.name, niced, oriented(range, spec.reverse), zone, spec.clamp, spec.round)
  }

  private fun buildBand(spec: ScaleSpec): BandScale? {
    val range = numericRange(spec) ?: return null
    val domain = discreteDomain(spec.domain, spec.name) ?: return null
    // `padding` is shorthand for both inner and outer; explicit values win.
    val padding = numbers.resolve(spec.padding, spec.name)
    return BandScale(
      name = spec.name,
      domain = domain,
      range = oriented(range, spec.reverse),
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
      domain = domain,
      range = oriented(range, spec.reverse),
      padding =
        numbers.resolve(spec.paddingOuter, spec.name)
          ?: numbers.resolve(spec.padding, spec.name)
          ?: 0.0,
      align = numbers.resolve(spec.align, spec.name) ?: 0.5,
      round = spec.round,
    )
  }

  /**
   * The distinct values of a column, for a range that names a dataset instead of listing values.
   *
   * First-seen order and de-duplicated, matching how a discrete *domain* is read from a column —
   * the two are paired in practice, one scale mapping a dataset's key column onto its label column,
   * and they have to agree on order or every entry is off by one.
   */
  private fun rangeColumn(spec: ScaleSpec, range: RangeSpec.FromField): List<VegaValue>? {
    val rows = datasets[range.data]
    if (rows == null) {
      diagnostics.error(
        DiagnosticCodes.SCALE_INVALID_DOMAIN,
        "Scale '${spec.name}' takes its range from unknown dataset '${range.data}'",
        operator = spec.name,
      )
      return null
    }
    return rows.map { it.field(range.field) }.distinct()
  }

  private fun buildOrdinal(spec: ScaleSpec): OrdinalScale? {
    val domain = discreteDomain(spec.domain, spec.name) ?: return null
    val range =
      when (val r = effectiveRange(spec)) {
        is RangeSpec.Literal -> r.values
        // A categorical scheme is exactly an ordinal range, so resolve it to one.
        is RangeSpec.Scheme -> colorRange(spec)?.map { VegaValue.Str(it.toCssHex()) } ?: return null
        // A column of the data, read the way a data-driven *domain* is: the scale becomes a lookup
        // table the rows themselves define — `id` in, `name` out. Distinct values in first-seen
        // order, so it lines up with a domain read the same way from the same rows.
        is RangeSpec.FromField -> rangeColumn(spec, r) ?: return null
        else -> {
          diagnostics.error(
            DiagnosticCodes.SCALE_INVALID_DOMAIN,
            "An ordinal scale needs an explicit range array or a scheme (scale '${spec.name}')",
            operator = spec.name,
          )
          return null
        }
      }
    return OrdinalScale(spec.name, domain, range)
  }

  /**
   * The discrete range these four share: an explicit array, or a scheme resolved to one.
   *
   * A scheme here is *sampled* rather than interpolated, because the output is a fixed number of
   * buckets and not a continuum — the count comes from the range, so `{"scheme": "blues"}` on a
   * four-bucket quantize gives four blues and not a ramp.
   */
  private fun binnedRange(spec: ScaleSpec, buckets: Int?): List<VegaValue>? =
    when (val r = effectiveRange(spec)) {
      is RangeSpec.Literal -> if (spec.reverse) r.values.reversed() else r.values
      is RangeSpec.Scheme -> {
        val colors = colorRange(spec) ?: return null
        val wanted = r.count ?: buckets ?: colors.size
        val taken = if (colors.size > wanted) sampleEvenly(colors, wanted) else colors
        (if (spec.reverse) taken.reversed() else taken).map { VegaValue.Str(it.toCssHex()) }
      }
      else -> {
        diagnostics.error(
          DiagnosticCodes.SCALE_INVALID_DOMAIN,
          "A '${spec.type.name.lowercase().replace('_', '-')}' scale needs an explicit range " +
            "array or a scheme (scale '${spec.name}')",
          operator = spec.name,
        )
        null
      }
    }

  /** Takes [count] colours spread across a ramp, so a bucketed scheme uses its whole range. */
  private fun sampleEvenly(colors: List<SceneColor>, count: Int): List<SceneColor> {
    if (count <= 1) return listOf(colors.first())
    return (0 until count).map { i ->
      colors[((i.toDouble() / (count - 1)) * (colors.size - 1)).toInt()]
    }
  }

  private fun buildQuantize(spec: ScaleSpec): QuantizeScale? {
    val range = binnedRange(spec, buckets = null) ?: return null
    val domain =
      continuousDomain(spec, zeroDefault = false, fallback = listOf(0.0, 1.0)) ?: return null
    return QuantizeScale(spec.name, domain, range)
  }

  private fun buildQuantile(spec: ScaleSpec): QuantileScale? {
    val range = binnedRange(spec, buckets = null) ?: return null
    // Every value, not the extent: a quantile scale cuts by count, so it needs the whole column.
    val domain = fullNumericDomain(spec) ?: return null
    return QuantileScale(spec.name, domain, range)
  }

  private fun buildThreshold(spec: ScaleSpec): ThresholdScale? {
    val cuts = fullNumericDomain(spec) ?: return null
    val range = binnedRange(spec, buckets = cuts.size + 1) ?: return null
    if (range.size != cuts.size + 1) {
      diagnostics.warn(
        DiagnosticCodes.SCALE_INVALID_DOMAIN,
        "A threshold scale's domain is its cut points, so it needs one more range value than " +
          "domain value; scale '${spec.name}' has ${cuts.size} cut point(s) and ${range.size} " +
          "range value(s), and the surplus on either side will not be used",
        operator = spec.name,
      )
    }
    return ThresholdScale(spec.name, cuts, range)
  }

  private fun buildBinOrdinal(spec: ScaleSpec): BinOrdinalScale? {
    val edges = fullNumericDomain(spec) ?: return null
    val range = binnedRange(spec, buckets = maxOf(1, edges.size - 1)) ?: return null
    return BinOrdinalScale(spec.name, edges, range)
  }

  /**
   * Every numeric value of the domain, in order, rather than its extent.
   *
   * Duplicates are kept, which matters: `quantile` cuts by count, so dropping a repeated value
   * would move every quartile.
   */
  private fun fullNumericDomain(spec: ScaleSpec): List<Double>? {
    val values =
      when (val domain = spec.domain) {
        is DomainSpec.Literal -> domain.values
        is DomainSpec.FromField -> fieldValues(domain.data, listOf(domain.field), spec.name)
        is DomainSpec.FromFields -> fieldValues(domain.data, domain.fields, spec.name)
        is DomainSpec.Union -> unionValues(domain.parts, spec.name)
        is DomainSpec.FromSignal -> signalDomain(domain, spec.name) ?: return null
        DomainSpec.Unset -> {
          diagnostics.error(
            DiagnosticCodes.SCALE_INVALID_DOMAIN,
            "Scale '${spec.name}' has no domain",
            operator = spec.name,
          )
          return null
        }
      }
    val numbers = values.map { it.asDouble() }.filterNot { it.isNaN() }
    if (numbers.isEmpty()) {
      diagnostics.error(
        DiagnosticCodes.SCALE_INVALID_DOMAIN,
        "Scale '${spec.name}' has no numeric values in its domain",
        operator = spec.name,
      )
      return null
    }
    return numbers
  }

  // ---- domains --------------------------------------------------------------

  private fun literalNumbers(domain: DomainSpec): List<Double>? {
    val literal = domain as? DomainSpec.Literal ?: return null
    val numbers = literal.values.map { it.asDouble() }
    return if (numbers.any { it.isNaN() }) null else numbers
  }

  /**
   * Applies `reverse`, which flips the **range** and leaves the domain alone.
   *
   * Reversing the domain instead maps every value to the same place, so a chart looks identical and
   * everything derived from the domain is backwards: the ticks come out in descending order, so the
   * axis labels are reversed and the domain line runs the wrong way.
   */
  private fun oriented(range: List<Double>, reverse: Boolean): List<Double> =
    if (reverse) range.reversed() else range

  private fun niceOf(domain: List<Double>, spec: ScaleSpec): List<Double> =
    dev.aster.vega.runtime.scale.Ticks.nice(
      domain,
      spec.niceCount ?: LinearScale.DEFAULT_TICK_COUNT,
    )

  /**
   * Every value a union's parts contribute, in the order they were written.
   *
   * The parts are read recursively rather than assumed to be `{data, field}` pairs, so a literal
   * array mixed in among them widens the domain exactly as upstream does.
   */
  private fun unionValues(parts: List<DomainSpec>, scaleName: String): List<VegaValue> =
    parts.flatMap { part ->
      when (part) {
        is DomainSpec.Literal -> part.values
        is DomainSpec.FromField -> fieldValues(part.data, listOf(part.field), scaleName)
        is DomainSpec.FromFields -> fieldValues(part.data, part.fields, scaleName)
        is DomainSpec.Union -> unionValues(part.parts, scaleName)
        is DomainSpec.FromSignal -> signalDomain(part, scaleName) ?: emptyList()
        DomainSpec.Unset -> emptyList()
      }
    }

  private fun numericExtent(
    domain: DomainSpec,
    scaleName: String,
  ): ClosedFloatingPointRange<Double>? {
    val values =
      when (domain) {
        is DomainSpec.FromField -> fieldValues(domain.data, listOf(domain.field), scaleName)
        is DomainSpec.FromFields -> fieldValues(domain.data, domain.fields, scaleName)
        is DomainSpec.Union -> unionValues(domain.parts, scaleName)
        is DomainSpec.Literal -> domain.values
        is DomainSpec.FromSignal -> signalDomain(domain, scaleName) ?: return null
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
        // An explicit domain is never sorted, whatever `sort` says: upstream reads the array
        // straight through and only the data-driven branches ever see a sort.
        is DomainSpec.Literal -> domain.values
        is DomainSpec.FromField ->
          orderedDomain(domain.data, listOf(domain.field), domain.sort, scaleName) ?: return null
        is DomainSpec.FromFields ->
          orderedDomain(domain.data, domain.fields, domain.sort, scaleName) ?: return null
        is DomainSpec.FromSignal -> signalDomain(domain, scaleName) ?: return null
        // A discrete union keeps first-appearance order across every part, which is the same rule
        // a single dataset's domain follows — the parts simply extend the sequence.
        is DomainSpec.Union -> unionValues(domain.parts, scaleName)
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

  /**
   * The values of a data-driven discrete domain, in the order upstream produces them.
   *
   * Upstream does not collect a domain's values; it *groups* the dataset on the domain field and
   * takes the group keys. Two things follow, and neither is visible from the specification:
   * - the default order is first appearance, deduplicated — the group order;
   * - `sort` can order by an aggregate of a field the domain never mentions, because the grouping
   *   it sorts is already there and the aggregate rides along on it.
   *
   * With several fields each is grouped separately and the results concatenated, so the order is
   * field by field rather than row by row. Those two agree until two fields interleave, at which
   * point every entry after the first is in a different place.
   */
  private fun orderedDomain(
    dataName: String,
    fields: List<String>,
    sort: DomainSort?,
    scaleName: String,
  ): List<VegaValue>? {
    val dataset = dataset(dataName, scaleName) ?: return null
    val keys =
      fields
        .flatMap { path -> dataset.map { it.field(path) }.filterNot { it.isMissing } }
        .distinctBy { it.asString() }
    return when (sort) {
      null -> keys
      is DomainSort.ByValue -> keys.sortedWith(domainOrder(sort.descending) { it })
      is DomainSort.ByAggregate -> {
        val summaries = aggregateSortKeys(dataset, fields, sort, scaleName) ?: return keys
        keys.sortedWith(domainOrder(sort.descending) { summaries[it.asString()] ?: VegaValue.Null })
      }
    }
  }

  /**
   * Vega's ascending comparator, negated for a descending sort.
   *
   * Negating rather than reversing is what upstream does — it multiplies the comparison by -1 — and
   * it is why a missing value leads an ascending domain and trails a descending one. Kotlin's sort
   * is stable, as is the one upstream uses, so values whose keys tie keep their group order.
   */
  private fun domainOrder(
    descending: Boolean,
    key: (VegaValue) -> VegaValue,
  ): Comparator<VegaValue> = Comparator { left, right ->
    val comparison = compareFieldValues(key(left), key(right))
    if (descending) -comparison else comparison
  }

  /**
   * The aggregate each distinct domain value sorts by, keyed by that value's string form.
   *
   * With one domain field this is a plain group-and-summarize. With several, upstream summarizes
   * each field separately and then folds the per-field results together — a count of counts is
   * their sum, a min of mins is a min — which is why it accepts only those three operations there
   * and rejects, say, a mean of means. The specification parser has already turned anything else
   * away; a null here means the operation is one this engine does not implement at all.
   */
  private fun aggregateSortKeys(
    dataset: List<VegaValue>,
    fields: List<String>,
    sort: DomainSort.ByAggregate,
    scaleName: String,
  ): Map<String, VegaValue>? {
    val op = AggregateOp.fromName(sort.op)
    if (op == null) {
      diagnostics.error(
        DiagnosticCodes.SCALE_INVALID_DOMAIN,
        "Scale '$scaleName' sorts its domain by aggregate '${sort.op}', which is not " +
          "implemented; leaving the domain in first-appearance order",
        operator = scaleName,
      )
      return null
    }
    if (op.needsField && sort.field == null) {
      diagnostics.error(
        DiagnosticCodes.SCALE_INVALID_DOMAIN,
        "Scale '$scaleName' sorts its domain by '${sort.op}' with no field to read; " +
          "leaving the domain in first-appearance order",
        operator = scaleName,
      )
      return null
    }

    val perField = fields.map { path ->
      val groups = LinkedHashMap<String, MutableList<VegaValue>>()
      for (datum in dataset) {
        val value = datum.field(path)
        if (value.isMissing) continue
        groups.getOrPut(value.asString()) { mutableListOf() }.add(datum)
      }
      groups.mapValues { (_, tuples) -> aggregateOver(op, sort.field, tuples) }
    }
    if (perField.size == 1) return perField[0]

    val combine =
      when (op) {
        AggregateOp.COUNT -> AggregateOp.SUM
        AggregateOp.MIN -> AggregateOp.MIN
        AggregateOp.MAX -> AggregateOp.MAX
        // Unreachable: the parser rejects every other operation on a multi-field domain.
        else -> return null
      }
    val gathered = LinkedHashMap<String, MutableList<VegaValue>>()
    for (summaries in perField) {
      for ((key, value) in summaries) gathered.getOrPut(key) { mutableListOf() }.add(value)
    }
    return gathered.mapValues { (_, values) ->
      aggregateOver(combine, MULTI_FIELD_COMBINE_FIELD, values.map { it.asCombineTuple() })
    }
  }

  private fun VegaValue.asCombineTuple(): VegaValue =
    VegaValue.Obj(mapOf(MULTI_FIELD_COMBINE_FIELD to this))

  /**
   * A domain supplied by a signal.
   *
   * Reported rather than defaulted when the signal is missing or empty, because a scale silently
   * falling back to `[0, 1]` produces a chart that looks plausible and is not.
   */
  private fun signalDomain(domain: DomainSpec.FromSignal, scaleName: String): List<VegaValue>? {
    val values = numbers.resolveList(domain.expression, scaleName)
    if (values.isNullOrEmpty()) {
      diagnostics.error(
        DiagnosticCodes.SCALE_INVALID_DOMAIN,
        "Scale '$scaleName' takes its domain from signal expression " +
          "'${domain.expression}', which produced nothing",
        operator = scaleName,
      )
      return null
    }
    return values
  }

  private fun dataset(dataName: String, scaleName: String): List<VegaValue>? {
    val dataset = datasets[dataName]
    if (dataset == null) {
      diagnostics.error(
        DiagnosticCodes.SCALE_INVALID_DOMAIN,
        "Scale '$scaleName' refers to unknown dataset '$dataName'",
        operator = scaleName,
      )
      return null
    }
    return dataset
  }

  /** Field by field rather than row by row, which is the order upstream's grouping produces. */
  private fun fieldValues(
    dataName: String,
    fields: List<String>,
    scaleName: String,
  ): List<VegaValue> {
    val dataset = dataset(dataName, scaleName) ?: return emptyList()
    return fields.flatMap { path -> dataset.map { it.field(path) }.filterNot { it.isMissing } }
  }

  // ---- ranges ---------------------------------------------------------------

  /**
   * A signal-valued range, evaluated now rather than at parse time.
   *
   * A signal may be derived from the very data the scale is over, so this cannot happen earlier.
   * The result is read back through the ordinary range forms, so `{"signal": "..."}` yielding an
   * array, a scheme name or the word `width` all behave exactly as if they had been written out.
   */
  private fun resolveRangeSignal(spec: ScaleSpec, expression: String): RangeSpec {
    val value = numbers.resolveValue(expression, spec.name)
    return when (value) {
      null,
      is VegaValue.Null -> {
        diagnostics.error(
          DiagnosticCodes.SCALE_INVALID_DOMAIN,
          "Scale '${spec.name}' takes its range from a signal that produced nothing",
          operator = spec.name,
        )
        RangeSpec.Unset
      }
      is VegaValue.Arr -> RangeSpec.Literal(value.values)
      is VegaValue.Str -> RangeSpec.Named(value.value)
      is VegaValue.Obj ->
        value.fields["scheme"]?.asString()?.let { RangeSpec.Scheme(SchemeRef.Named(it)) }
          ?: RangeSpec.Unset
      else -> RangeSpec.Unset
    }
  }

  /**
   * `{"step": n}`: a band scale sized by its band rather than by the space it was given.
   *
   * The extent follows from the number of categories, so the range is `[0, step × steps]`, where
   * the step count includes the padding a band scale adds at each end. A chart written this way
   * grows with its data instead of squeezing it.
   */
  private fun stepRange(spec: ScaleSpec, step: Double): List<Double>? {
    val domain = discreteDomain(spec.domain, spec.name) ?: return null
    val inner =
      numbers.resolve(spec.paddingInner, spec.name)
        ?: numbers.resolve(spec.padding, spec.name)
        ?: 0.0
    val outer =
      numbers.resolve(spec.paddingOuter, spec.name)
        ?: numbers.resolve(spec.padding, spec.name)
        ?: 0.0
    val count = domain.size
    val span = if (count == 0) 0.0 else step * (count - inner + 2 * outer)
    return listOf(0.0, span)
  }

  private fun numericRange(spec: ScaleSpec): List<Double>? =
    when (val range = effectiveRange(spec)) {
      is RangeSpec.Named ->
        when (range.name.lowercase()) {
          "width" -> listOf(0.0, size.width)
          // `"height"` descends for a continuous scale, so larger values sit higher on screen, but
          // ascends for a discrete one, so the first category is at the top. Upstream keys this off
          // the scale type, and getting it wrong flips a row-faceted trellis top to bottom.
          "height" ->
            if (spec.type.isDiscrete) listOf(0.0, size.height) else listOf(size.height, 0.0)
          else -> {
            diagnostics.error(
              DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
              "Named range '${range.name}' is not implemented (scale '${spec.name}')",
              operator = spec.name,
            )
            null
          }
        }
      is RangeSpec.Signal -> {
        val resolved = resolveRangeSignal(spec, range.expression)
        if (resolved is RangeSpec.Signal) null else numericRange(spec.copy(range = resolved))
      }
      is RangeSpec.Step -> stepRange(spec, numbers.resolve(range.step, spec.name) ?: 0.0)
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
          "Scale '${spec.name}' needs a numeric range but was given a colour scheme",
          operator = spec.name,
        )
        null
      }
      // A column can supply a numeric range too, though it is the discrete form that is common.
      is RangeSpec.FromField -> {
        val numbers = rangeColumn(spec, range)?.map { it.asDouble() }
        if (numbers == null || numbers.size < 2 || numbers.any { it.isNaN() }) {
          if (numbers != null) {
            diagnostics.error(
              DiagnosticCodes.SCALE_INVALID_DOMAIN,
              "Scale '${spec.name}' takes its range from '${range.data}.${range.field}', which is " +
                "not two or more numbers",
              operator = spec.name,
            )
          }
          null
        } else {
          numbers
        }
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
