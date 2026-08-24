@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asBoolean
import dev.aster.vega.model.field
import dev.aster.vega.model.isMissing
import dev.aster.vega.model.roundHalfUp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow

/**
 * `bin`: assigns each tuple to a bin, writing the bin's bounds to `bin0` and `bin1`.
 *
 * Values outside the extent are **not** clamped into the end bins: they get an infinity on the side
 * they fell off, which is what upstream's binning function returns. Only a missing value bins to
 * null. Verified against upstream — `bin` over `[0, 10]` puts `0.2` at `-Infinity` and `11` at
 * `+Infinity` — and it matters because `datum.bin0 != null` then keeps the first and drops a
 * missing one.
 */
public object BinTransform : Transform {
  override val type: String = "bin"

  /**
   * The bin settings it chose, as `{start, stop, step, fields}`.
   *
   * A histogram reads these to size itself: `(bins.stop - bins.start) / bins.step` is the bin
   * count, and a bar's width follows from it. Upstream publishes the *binning function* with
   * `start`, `stop` and `step` hung off it as properties, plus the accessor's `fields`; nothing can
   * call a function from an expression here, and no specification does, so the properties alone are
   * published and the three a chart actually reads carry upstream's values exactly.
   */
  override val publishesSignal: Boolean = true

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val path = params.string("field")
    if (path == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "bin needs a 'field'",
        operator = type,
      )
      return input
    }

    val extentValues = params.numberList("extent")
    val extent =
      if (extentValues.size >= 2) {
        extentValues[0] to extentValues[1]
      } else {
        // Upstream requires an explicit extent; deriving it from the data is a documented
        // convenience,
        // and reported so the difference is visible.
        val numbers =
          input
            .map { it.field(path) }
            .filterNot { it.isMissing }
            .map { JsSemantics.toNumber(it) }
            .filter { it.isFinite() }
        if (numbers.isEmpty()) {
          context.diagnostics.error(
            DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
            "bin needs an 'extent' and the field '$path' has no finite values to derive one from",
            operator = type,
          )
          return input
        }
        context.diagnostics.warn(
          DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          "bin has no 'extent'; it was derived from the data, which upstream Vega does not do",
          operator = type,
        )
        numbers.min() to numbers.max()
      }

    val settings =
      binSettings(
        min = extent.first,
        max = extent.second,
        maxbins = params.number("maxbins")?.toInt() ?: DEFAULT_MAXBINS,
        base = params.number("base") ?: 10.0,
        step = params.number("step"),
        divide = params.numberList("divide").takeIf { it.isNotEmpty() } ?: listOf(5.0, 2.0),
        minstep = params.number("minstep") ?: 0.0,
        nice = params.boolean("nice") ?: true,
        anchor = params.number("anchor"),
      )

    params.string("signal")?.let { signal ->
      context.setSignal(
        signal,
        VegaValue.Obj(
          linkedMapOf(
            "start" to VegaValue.Num(settings.start),
            "stop" to VegaValue.Num(settings.stop),
            "step" to VegaValue.Num(settings.step),
            "fields" to VegaValue.Arr(listOf(VegaValue.Str(path))),
          )
        ),
      )
    }

    val names = params.stringList("as")
    val lowName = names.getOrNull(0) ?: "bin0"
    val highName = names.getOrNull(1) ?: "bin1"
    // `interval: false` asks for the bin's **start only**, which is what a specification writes
    // when
    // it wants a bin as a category rather than as a span — upstream's `band` flag, and its `!band`
    // branch writes `b0` and nothing else. This wrote `bin1` regardless, so such a field carried an
    // end nobody asked for and a downstream `groupby` on the pair grouped by something different
    // from what upstream groups by. Found by replaying upstream's own `bin` vectors.
    val interval = params.fields["interval"]?.let { it.asBoolean() } ?: true

    return input.map { datum ->
      val value = datum.field(path)
      val number = if (value.isMissing) Double.NaN else JsSemantics.toNumber(value)
      // A missing value bins to null; a value *outside* the extent bins to an infinity on the side
      // it fell off. That is upstream's own binning function — `v < start ? -Infinity : v > stop ?
      // +Infinity` — and the difference is not cosmetic: `datum.bin0 != null` keeps an out-of-range
      // row and drops a missing one, so a specification filtering on it gets different rows
      // depending on which this writes. Both bounds take the infinity, since the high one is the
      // low
      // one plus a step.
      if (value.isMissing || number.isNaN()) {
        datum.withFields(binFields(lowName, highName, VegaValue.Null, VegaValue.Null, interval))
      } else if (number < settings.start || number > settings.stop) {
        val edge =
          VegaValue.Num(
            if (number < settings.start) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
          )
        datum.withFields(binFields(lowName, highName, edge, edge, interval))
      } else {
        // Upstream's arithmetic, and the epsilon is the whole of it: a value that lands *exactly*
        // on a boundary divides to a whole number only in exact arithmetic. In doubles
        // `(9.1 - 1.95) / 0.65` is 10.999999999999998, and flooring that drops the value a bin
        // short — one row in the wrong column of a histogram, which is invisible until something
        // measures the tallest one. Vega adds 1e-14 inside the floor for exactly this.
        //
        // The clamp is upstream's too, and comes first: a value at the very top is pulled back to
        // the last bin's start rather than opening a bin past the end.
        val clamped =
          number
            .coerceIn(settings.start, settings.stop - settings.step)
            .coerceAtLeast(settings.start)
        val index = floor(BIN_EPSILON + (clamped - settings.start) / settings.step)
        val low = settings.start + index * settings.step
        datum.withFields(
          binFields(
            lowName,
            highName,
            VegaValue.Num(low),
            // Upstream's own arithmetic for the upper edge — `start + step * (1 + (v - start) /
            // step)` rather than `low + step` — which agrees to the last bit where adding does not.
            VegaValue.Num(
              settings.start + settings.step * (1 + (low - settings.start) / settings.step)
            ),
            interval,
          )
        )
      }
    }
  }

  /** The bin's fields: both edges, or only the start when `interval: false`. */
  private fun binFields(
    lowName: String,
    highName: String,
    low: VegaValue,
    high: VegaValue,
    interval: Boolean,
  ): Map<String, VegaValue> =
    if (interval) mapOf(lowName to low, highName to high) else mapOf(lowName to low)

  /** Vega's default; the transform's own default, not the 10 that `maxbins` suggests elsewhere. */
  public const val DEFAULT_MAXBINS: Int = 20

  /** Upstream's `EPSILON`, added inside the floor so a boundary value lands in the right bin. */
  private const val BIN_EPSILON: Double = 1e-14

  public data class BinSettings(val start: Double, val stop: Double, val step: Double)

  /**
   * Chooses bin boundaries the way `vega-statistics`' `bin` does.
   *
   * The algorithm is reproduced rather than approximated because bin edges are visible in every
   * histogram: pick a power of the base near the span, grow it until the bin count fits `maxbins`,
   * then try dividing it by 5 and by 2 to get a finer step that still fits. With `nice`, snap the
   * bounds outward to multiples of the step.
   *
   * Verified against upstream: extent `[0, 25]` gives step 2 at the default `maxbins`, step 5 at
   * `maxbins: 5`, and `nice: false` over `[1, 23]` starts the first bin at 1.
   */
  public fun binSettings(
    min: Double,
    max: Double,
    maxbins: Int,
    base: Double,
    step: Double?,
    divide: List<Double>,
    minstep: Double,
    nice: Boolean,
    /** A value a bin boundary must land on; slides the whole grid so that one does. */
    anchor: Double? = null,
  ): BinSettings {
    val logBase = ln(base)
    val span = (max - min).takeIf { it != 0.0 } ?: abs(min).takeIf { it != 0.0 } ?: 1.0

    var chosen: Double
    if (step != null && step > 0.0) {
      chosen = step
    } else {
      val level = ceil(ln(maxbins.toDouble()) / logBase)
      chosen = maxOf(minstep, base.pow(roundHalfUp(ln(span) / logBase) - level))
      // Grow until the bin count fits.
      while (ceil(span / chosen) > maxbins) chosen *= base
      // Then take the finest permitted subdivision that still fits.
      for (divisor in divide) {
        val candidate = chosen / divisor
        if (candidate >= minstep && span / candidate <= maxbins) chosen = candidate
      }
    }

    var start = min
    var stop = max
    if (nice) {
      val exponent = ln(chosen)
      val precision = if (exponent >= 0) 0 else (-exponent / logBase).toInt() + 1
      val eps = base.pow(-precision - 1.0)
      val snapped = floor(start / chosen + eps) * chosen
      start = if (start < snapped) snapped - chosen else snapped
      stop = ceil(stop / chosen) * chosen
    }
    if (stop == start) stop = start + chosen

    // Upstream splits this in two: `vega-statistics`' `bin` returns everything above, and the `bin`
    // *transform* then does these last two steps. Both belong to the settings, so they live here.
    //
    // The stop is realigned to a whole number of steps from the start. Under `nice` it already is,
    // which is why this was invisible — but with `nice: false` the extent is taken as given, and an
    // extent of `[1, 24]` at step 2 has eleven and a half bins in it. Upstream opens the twelfth
    // and
    // stops at 25; this stopped at 24, so a value of 24 was clamped back into `[21, 23]` where
    // upstream puts it in `[23, 25]`.
    stop = start + ceil((stop - start) / chosen) * chosen

    // `anchor` names a value a bin boundary must fall on, and slides the whole grid to put one
    // there: `anchor: 0.3` with step 1 bins from 0.3, so 0.2 falls outside the extent entirely.
    if (anchor != null) {
      val shift = anchor - (start + chosen * floor((anchor - start) / chosen))
      start += shift
      stop += shift
    }
    return BinSettings(start, stop, chosen)
  }
}
