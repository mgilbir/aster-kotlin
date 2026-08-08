package dev.aster.vega.runtime.compile

import dev.aster.vega.expression.NumberFormatSubset
import dev.aster.vega.model.spec.ScaleType
import dev.aster.vega.model.time.TimeFormat
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.BinnedScale
import dev.aster.vega.runtime.scale.LinearScale
import dev.aster.vega.runtime.scale.OrdinalScale
import dev.aster.vega.runtime.scale.PointScale
import dev.aster.vega.runtime.scale.SequentialColorScale
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
  ): String? {
    if (scale == null) return null
    val axis = if (orient == "left" || orient == "right") "Y-axis" else "X-axis"
    return buildString {
      append(axis)
      if (!title.isNullOrBlank()) append(" titled '$title'")
      append(" for a ${typeName(scale, declaredType)} scale")
      append(" with ${domain(scale, format)}")
    }
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
  ): String? {
    if (scale == null || channels.isEmpty()) return null
    return buildString {
      append("$kind legend".trim().replaceFirstChar { it.uppercase() })
      if (!title.isNullOrBlank()) append(" titled '$title'")
      append(" for ${channelNames(channels)}")
      append(" with ${domain(scale)}")
    }
  }

  /** `fill` and `stroke` are read as colours; everything else keeps its own name. */
  private fun channelNames(channels: List<String>): String {
    val named = channels.map { if (it == "fill" || it == "stroke") "$it color" else it }
    return if (named.size < 2) named.first()
    else named.dropLast(1).joinToString(", ") + " and " + named.last()
  }

  private fun typeName(scale: VegaScale, declaredType: ScaleType?): String =
    when (scale) {
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
  private fun domain(scale: VegaScale, format: String? = null): String =
    when (scale) {
      is BinnedScale -> {
        val cuts = scale.thresholds.map { formatTickLabel(it, decimalsFor(scale.thresholds)) }
        "${cuts.size} boundar${if (cuts.size == 1) "y" else "ies"}: ${cuts.joinToString(", ")}"
      }
      is BandScale -> discrete(scale.domain)
      is PointScale -> discrete(scale.domain)
      is OrdinalScale -> discrete(scale.domain)
      is TimeScale -> {
        val suffix = if (scale.zone == TimeZone.UTC) " UTC" else ""
        val from = TimeFormat.format(scale.domain.first(), DATE_PATTERN, scale.zone)
        val to = TimeFormat.format(scale.domain.last(), DATE_PATTERN, scale.zone)
        "values from $from$suffix to $to$suffix"
      }
      is TransformedScale ->
        continuous(scale.domain.first(), scale.domain.last()) { v, _ ->
          if (format != null) NumberFormatSubset.format(v, format)
          else scale.formatTick(v, CAPTION_TICK_COUNT)
        }
      is SequentialColorScale ->
        continuous(scale.domain.first(), scale.domain.last()) { v, _ ->
          if (format != null) NumberFormatSubset.format(v, format)
          else scale.formatTick(v, CAPTION_TICK_COUNT)
        }
      is LinearScale ->
        continuous(scale.domain.first(), scale.domain.last()) { v, _ ->
          if (format != null) NumberFormatSubset.format(v, format)
          else scale.formatTick(v, CAPTION_TICK_COUNT)
        }
    }

  /**
   * Reads a long domain as its first few and its last.
   *
   * Past seven values the whole list stops being listenable, and the last one still matters — it is
   * where the axis ends. Upstream's rule, and the phrasing that goes with it.
   */
  private fun discrete(values: List<String>): String {
    val n = values.size
    val body =
      if (n > MAX_VALUES) {
        values.take(MAX_VALUES - 2).joinToString(", ") + ", ending with " + values.last()
      } else {
        values.joinToString(", ")
      }
    return "$n value${if (n == 1) "" else "s"}: $body"
  }

  private fun continuous(low: Double, high: Double, format: (Double, Int) -> String): String =
    "values from ${format(low, CAPTION_TICK_COUNT)} to ${format(high, CAPTION_TICK_COUNT)}"

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
