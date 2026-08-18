package dev.aster.vega.runtime.compile

import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.model.time.TimeFormat
import dev.aster.vega.runtime.scale.Ticks
import dev.aster.vega.runtime.scale.TimeTicks
import kotlinx.datetime.TimeZone

/**
 * The two pieces of tick machinery an axis and a legend both need.
 *
 * Shared rather than duplicated because both are places where a plausible reimplementation is wrong
 * in a way no picture reveals: a legend that labels instants as numbers looks like a legend, and a
 * legend that ignores a minimum step looks like a legend with more entries.
 */
internal object GuideFormat {

  /**
   * A formatter for a guide whose `formatType` says its values are **instants**.
   *
   * Null when the type says nothing of the sort, so the caller falls back to whatever its scale
   * would have done. `formatType` decides the grammar *before* the scale gets a say — upstream's
   * order in `vega-scale`'s `tickFormat` — which is how a band of instants reads as dates with no
   * temporal scale anywhere to infer it from.
   *
   * With no specifier the label is upstream's **multi**-format: each value is written at its own
   * granularity, so a January tick carries the year and the ones after it do not.
   */
  fun timeLabeller(
    format: String?,
    formatType: String?,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): ((Double) -> String)? {
    val zone =
      when (formatType) {
        "time" -> TimeZone.currentSystemDefault()
        "utc" -> TimeZone.UTC
        else -> return null
      }
    return { instant ->
      when {
        instant.isNaN() -> ""
        format == null -> TimeTicks.label(instant, zone, locale)
        else -> TimeFormat.format(instant, format, zone, locale)
      }
    }
  }

  /**
   * `tickMinStep`: a floor on the gap between ticks, applied by *reducing the count*.
   *
   * Upstream's `tickCount`, and every part of it matters. The count is first capped at what the
   * domain can hold at that step — with `|| 1` guarding a step wider than the whole domain, which
   * in JavaScript turns a zero into a one and so leaves two ticks rather than none. Then, because
   * d3's step sizes grow monotonically as the count shrinks, the count is walked down one at a time
   * until the step d3 would actually choose reaches the minimum. That walk is **skipped** for log
   * and time scales, whose steps are not linear in the count at all; only the cap applies there.
   */
  fun countWithMinStep(
    count: Int,
    minStep: Double?,
    domain: List<Double>,
    /** True for a plain linear scale, where d3's `tickStep` describes the gap. */
    linear: Boolean,
  ): Int {
    if (minStep == null || !minStep.isFinite() || minStep <= 0.0 || domain.size < 2) return count
    val lo = minOf(domain.first(), domain.last())
    val hi = maxOf(domain.first(), domain.last())
    val span = ((hi - lo) / minStep).let { if (it.isFinite()) kotlin.math.floor(it) else 0.0 }
    var reduced = minOf(count, (if (span == 0.0) 1.0 else span).toInt() + 1)
    if (linear && lo < hi) {
      while (reduced > 1 && Ticks.stepFrom(Ticks.tickIncrement(lo, hi, reduced)) < minStep) {
        reduced--
      }
    }
    return reduced
  }
}
