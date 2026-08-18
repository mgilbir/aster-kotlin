package dev.aster.vega.runtime.compile

import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.model.spec.ScaleType
import dev.aster.vega.model.time.TimeFormat
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.BinOrdinalScale
import dev.aster.vega.runtime.scale.BinnedScale
import dev.aster.vega.runtime.scale.IdentityScale
import dev.aster.vega.runtime.scale.LinearScale
import dev.aster.vega.runtime.scale.OrdinalScale
import dev.aster.vega.runtime.scale.PointScale
import dev.aster.vega.runtime.scale.QuantileScale
import dev.aster.vega.runtime.scale.SequentialColorScale
import dev.aster.vega.runtime.scale.Ticks
import dev.aster.vega.runtime.scale.TimeScale
import dev.aster.vega.runtime.scale.TransformedScale
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.runtime.scale.formatTickLabel
import kotlinx.datetime.TimeZone

/**
 * What a screen reader is told about an axis or a legend.
 *
 * A chart's marks are only meaningful against the guides that frame them. "Jan: 28" says nothing on
 * its own; "X-axis for a discrete scale with 8 values: Jan, Feb, ..." plus "Jan: 28" is a chart.
 * Listening to TalkBack made that obvious in a way reading the accessibility tree had not.
 *
 * The wording is upstream's, ported from `vega-scenegraph/src/util/aria.js` and
 * `vega-scale/src/caption.js` rather than invented. Two reasons: a reader who has met Vega charts
 * elsewhere hears the same phrasing, and the sentences have already been through the arguments
 * about how much of a long domain to read out — seven values, then the first five and the last.
 */
internal object GuideCaption {

  /** Upstream's `maxlen`: past this many values, only the first few and the last are read. */
  private const val MAX_VALUES = 7

  /** The tick count upstream formats a caption's numbers at, which is not the axis's own. */
  private const val CAPTION_TICK_COUNT = 5

  /**
   * A long-form date, because a screen reader should not have to expand `01/05`.
   *
   * Upstream's `%A, %d %B %Y, %X`; `%X` is a locale time, which here is the 12-hour clock d3's
   * en-US default produces.
   */
  private const val DATE_PATTERN = "%A, %d %B %Y, %I:%M:%S %p"

  /**
   * The same long form, with the locale's own time of day in it.
   *
   * Upstream's `%X` is the locale's time, which for d3's en-US default is the twelve-hour clock the
   * constant above spells out. A locale that writes a 24-hour clock says so in `VegaLocale.time`,
   * and a caption reading a whole timestamp out should use it rather than the American one.
   */
  private fun datePattern(locale: VegaLocale): String =
    if (locale == VegaLocale.EnglishUS) DATE_PATTERN else "%A, %d %B %Y, %X"

  /**
   * @param declaredType the scale's `type` as written, not its runtime class.
   *
   * `sqrt` and `pow` build the same object here, and a specification that wrote `sqrt` should hear
   * "sqrt". Every discrete type is read as "discrete", which is upstream's own flattening.
   */
  internal fun axis(
    orient: String,
    title: String?,
    scale: VegaScale?,
    declaredType: ScaleType?,
    /**
     * The axis's own label format, so a reader hears the domain the way the labels read it.
     *
     * Per axis and not per scale: two axes over one scale, one of them formatted as currency, are
     * described differently by upstream — the priced one says "from $1.20 to $3.40" and the other
     * says "from 1.2 to 3.4".
     */
    format: String? = null,
    /** The axis's `formatType`, which decides whether [format] is read as a date or a number. */
    formatType: String? = null,
    /** The language every name and number in the sentence is written in. */
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): String? {
    if (scale == null) return null
    return locale.captions.axis(
      vertical = orient == "left" || orient == "right",
      title = title,
      scaleType = typeName(scale, declaredType),
      domain = domain(scale, format, formatType, locale),
    )
  }

  /**
   * @param kind `"symbol"` or `"gradient"` — which of the two legend shapes this is.
   * @param channels the encoding channels the legend explains, e.g. `fill` or `size`.
   */
  internal fun legend(
    kind: String,
    title: String?,
    channels: List<String>,
    scale: VegaScale?,
    /** The legend's own label format, so a reader hears the domain the way the entries read it. */
    format: String? = null,
    /**
     * And its `formatType`, without which a legend over instants reads its domain as milliseconds.
     */
    formatType: String? = null,
    /** The language every name and number in the sentence is written in. */
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): String? {
    if (scale == null || channels.isEmpty()) return null
    return locale.captions.legend(
      kind = kind,
      title = title,
      channels = channels.map { locale.captions.channelName(it) },
      domain = domain(scale, format, formatType, locale),
    )
  }

  private fun typeName(scale: VegaScale, declaredType: ScaleType?): String =
    when (scale) {
      // `bin-ordinal` belongs here and not with the other three discretizing scales: upstream's
      // `isDiscrete` is true for it — its domain is a list of edges rather than an interval — so a
      // reader is told "a discrete scale" where a quantile scale is named by type.
      is BinOrdinalScale,
      is BandScale,
      is PointScale,
      is OrdinalScale -> "discrete"
      else -> declaredType?.name?.lowercase()?.replace('_', '-') ?: "linear"
    }

  /**
   * The domain, in the three shapes upstream distinguishes.
   *
   * A **discretizing** scale reads its boundaries rather than its values, because the values are
   * ranges and the boundaries are what a reader needs to place a mark. A **discrete** scale reads
   * its values, truncated. A **continuous** one reads its two ends.
   */
  private fun domain(
    scale: VegaScale,
    format: String? = null,
    formatType: String? = null,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): String =
    when (scale) {
      // An identity scale has no domain to describe: it maps the value itself, so a guide over one
      // is a guide over the data as it stands.
      is IdentityScale -> locale.captions.identityDomain()
      is BinnedScale -> {
        // The same formatter the bands themselves use: the precision comes from the narrowest
        // interval, not from the whole span, so a reader hears "2.1%" and not "0.021429".
        val write = threshold(format, scale, locale)
        locale.captions.boundaryDomain(scale.thresholds.map(write))
      }
      is BandScale -> discrete(scale.domain.map { spoken(it, format, formatType, locale) }, locale)
      is PointScale -> discrete(scale.domain.map { spoken(it, format, formatType, locale) }, locale)
      is OrdinalScale ->
        discrete(scale.domain.map { spoken(it, format, formatType, locale) }, locale)
      is TimeScale -> {
        // A named format wins over the full date, expanded the way a caption expands one: a `%b`
        // axis
        // is *described* as "January" while its labels say "Jan". Without a format the whole
        // timestamp is read out, and then it carries its zone — a caption that reads out a time
        // should say which clock it is on.
        val pattern = format?.replace("%a", "%A")?.replace("%b", "%B")
        val suffix = if (pattern == null && scale.zone == TimeZone.UTC) " UTC" else ""
        val write = { at: Double ->
          TimeFormat.format(at, pattern ?: datePattern(locale), scale.zone, locale) + suffix
        }
        locale.captions.continuousDomain(write(scale.domain.first()), write(scale.domain.last()))
      }
      is TransformedScale ->
        continuous(scale.domain.first(), scale.domain.last(), locale) { v, _ ->
          spokenInstant(v, format, formatType, locale)
            ?: spelled(format, scale.domain, locale)?.invoke(v)
            ?: scale.formatTick(v, CAPTION_TICK_COUNT, locale)
        }
      is SequentialColorScale ->
        continuous(scale.domain.first(), scale.domain.last(), locale) { v, _ ->
          spokenInstant(v, format, formatType, locale)
            ?: spelled(format, scale.domain, locale)?.invoke(v)
            ?: scale.formatTick(v, CAPTION_TICK_COUNT, locale)
        }
      is LinearScale ->
        continuous(scale.domain.first(), scale.domain.last(), locale) { v, _ ->
          spokenInstant(v, format, formatType, locale)
            ?: spelled(format, scale.domain, locale)?.invoke(v)
            ?: scale.formatTick(v, CAPTION_TICK_COUNT, locale)
        }
    }

  /**
   * One end of a continuous domain that a `formatType` says is an **instant**, spoken.
   *
   * Null when the type says nothing temporal, so the caller falls back to its scale's own
   * formatter. The rules are the same ones [spoken] applies to a discrete value — the abbreviating
   * directives are expanded, so a ramp labelled `%b` is described with "January" rather than "Jan"
   * — with one addition: where no format was given at all the full date carries its zone, because a
   * caption that reads out a whole timestamp should say which clock it is on.
   */
  private fun spokenInstant(
    value: Double,
    format: String?,
    formatType: String?,
    locale: VegaLocale,
  ): String? {
    val zone =
      when (formatType) {
        "time" -> TimeZone.currentSystemDefault()
        "utc" -> TimeZone.UTC
        else -> return null
      }
    val pattern = format?.replace("%a", "%A")?.replace("%b", "%B")
    val suffix = if (pattern == null && zone == TimeZone.UTC) " UTC" else ""
    return TimeFormat.format(value, pattern ?: datePattern(locale), zone, locale) + suffix
  }

  /**
   * Reads a long domain as its first few and its last.
   *
   * Past seven values the whole list stops being listenable, and the last one still matters — it is
   * where the axis ends. Upstream's rule, and the phrasing that goes with it.
   */
  private fun discrete(values: List<String>, locale: VegaLocale): String {
    val n = values.size
    return if (n > MAX_VALUES) {
      locale.captions.discreteDomain(n, values.take(MAX_VALUES - 2), endingWith = values.last())
    } else {
      locale.captions.discreteDomain(n, values, endingWith = null)
    }
  }

  /**
   * A boundary as a discretizing legend writes it, upstream's `thresholdFormat`.
   *
   * The reference span is the *smallest* gap between cut points — or, for a quantize scale, the
   * width of its declared domain — rather than the domain's whole extent, which is what makes a
   * scale of seven buckets over `[0, 0.15]` read to a tenth of a percent.
   */
  private fun threshold(
    format: String?,
    scale: BinnedScale,
    locale: VegaLocale,
  ): (Double) -> String {
    val reference =
      if (scale is QuantileScale) scale.thresholds
      else scale.legendExtent.let { listOf(it.first, it.second) }
    val step =
      when {
        reference.size > 1 -> (1 until reference.size).minOf { reference[it] - reference[it - 1] }
        reference.size == 1 -> reference[0]
        else -> 1.0
      }
    if (format == null) {
      // The precision the *step* needs, not the precision the values happen to have. Upstream
      // passes
      // the reference span through `formatSpan` whether or not a specifier was given, so a quantile
      // scale whose cut points are 19.333 and 48.667 is described as "19, 49" — a reader is being
      // told where the boundaries roughly are, and six decimals of a quantile is noise. Reading the
      // decimals off the values instead read them out in full.
      val increment = Ticks.stepFrom(Ticks.tickIncrement(0.0, step, THRESHOLD_FORMAT_COUNT))
      val decimals = if (increment.isFinite()) Ticks.precisionForStep(increment) else 0
      return { value -> formatTickLabel(value, decimals, locale) }
    }
    return Ticks.spanFormatter(format, 0.0, step, THRESHOLD_FORMAT_COUNT, locale)
  }

  /** Upstream's `3 * 10`: three ticks at ten times the resolution. */
  private const val THRESHOLD_FORMAT_COUNT = 30

  /**
   * A numeric format resolved against the span it describes, as upstream's caption resolves it.
   *
   * The same `formatSpan` an axis or a legend label goes through, at the caption's own tick count
   * of five: a specifier naming no precision takes as many decimals as the step needs, so a ramp
   * over fractions is read out as "−6% to 6%" and not "−0.060000% to 0.060000%".
   */
  private fun spelled(
    format: String?,
    domain: List<Double>,
    locale: VegaLocale,
  ): ((Double) -> String)? {
    if (format == null) return null
    return Ticks.spanFormatter(format, domain.first(), domain.last(), CAPTION_TICK_COUNT, locale)
  }

  /**
   * One discrete value, as a listener hears it.
   *
   * Upstream expands the abbreviating directives before reading a caption out — `%a` becomes `%A`
   * and `%b` becomes `%B` — so an axis whose labels say "Sun" is described as "Sunday". Without a
   * format type there is nothing temporal to expand and the value stands as it is written.
   */
  private fun spoken(
    value: String,
    format: String?,
    formatType: String?,
    locale: VegaLocale,
  ): String {
    val zone =
      when (formatType) {
        "time" -> TimeZone.currentSystemDefault()
        "utc" -> TimeZone.UTC
        else -> return value
      }
    val instant = value.toDoubleOrNull() ?: return value
    val pattern = format?.replace("%a", "%A")?.replace("%b", "%B") ?: datePattern(locale)
    return TimeFormat.format(instant, pattern, zone, locale)
  }

  private fun continuous(
    low: Double,
    high: Double,
    locale: VegaLocale,
    format: (Double, Int) -> String,
  ): String =
    locale.captions.continuousDomain(
      format(low, CAPTION_TICK_COUNT),
      format(high, CAPTION_TICK_COUNT),
    )

  /** As many decimals as the cut points need to stay distinct; see the banded legend. */
  private fun decimalsFor(values: List<Double>): Int {
    for (decimals in 0..6) {
      if (values.all { kotlin.math.abs(it - roundTo(it, decimals)) < 1e-9 }) return decimals
    }
    return 6
  }

  private fun roundTo(value: Double, decimals: Int): Double {
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    return kotlin.math.round(value * factor) / factor
  }
}
