package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
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
 * Values outside the extent get `null` bounds rather than being clamped into the end bins —
 * verified against upstream, and the opposite of what a clamping implementation would produce.
 */
public object BinTransform : Transform {
  override val type: String = "bin"

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
      )

    val names = params.stringList("as")
    val lowName = names.getOrNull(0) ?: "bin0"
    val highName = names.getOrNull(1) ?: "bin1"

    return input.map { datum ->
      val value = datum.field(path)
      val number = if (value.isMissing) Double.NaN else JsSemantics.toNumber(value)
      if (!number.isFinite() || number < settings.start || number > settings.stop) {
        datum.withFields(mapOf(lowName to VegaValue.Null, highName to VegaValue.Null))
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
          mapOf(
            lowName to VegaValue.Num(low),
            highName to VegaValue.Num(low + settings.step),
          )
        )
      }
    }
  }

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
    return BinSettings(start, if (stop == start) start + chosen else stop, chosen)
  }
}
