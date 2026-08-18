package dev.aster.vega.runtime.scale

import dev.aster.vega.expression.NumberFormat
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.model.roundHalfUp
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Tick generation, ported from d3-array, which is what upstream Vega uses.
 *
 * These functions decide where every axis tick and gridline lands, so a deviation here is visible
 * in every chart. The algorithm is reproduced exactly rather than approximated, including the
 * negative `tickIncrement` convention: when the natural step is below 1 the function returns
 * `-1/step` so the caller can divide instead of multiply and avoid accumulating floating-point
 * error.
 *
 * Reference vectors in `TicksTest` were generated from the pinned d3-array in `oracle-js`.
 */
public object Ticks {

  private val E10 = sqrt(50.0)
  private val E5 = sqrt(10.0)
  private val E2 = sqrt(2.0)

  /**
   * The step size d3 would use between [start] and [stop] for about [count] ticks.
   *
   * A positive result is a multiplier; a negative result `-k` means the step is `1/k`.
   * `Double.NEGATIVE_INFINITY` means the range is degenerate.
   */
  /**
   * d3's `tickSpec`: the first and last tick **indices** and the increment between them.
   *
   * Transcribed from d3-array 3.2.4, which rewrote this — the older algorithm computed the
   * increment and let each caller rediscover the indices. Two things only exist in the new one:
   *
   * - the **retry**. When the interval is empty and the count is between a half and two, d3 asks
   *   again with twice the count, so `ticks(1, 364, 1)` is `[200]` rather than nothing. A count of
   *   one is something a specification writes, and this engine answered with an axis that had no
   *   ticks at all.
   * - a **fractional count**, which is why this takes a `Double`. The retry passes `count * 2` and
   *   the condition itself is fractional, so an `Int` cannot express the algorithm.
   */
  private fun tickSpec(start: Double, stop: Double, count: Double): TickSpec {
    val step = (stop - start) / maxOf(0.0, count)
    val power = floor(log10(step))
    val error = step / 10.0.pow(power)
    val factor =
      when {
        error >= E10 -> 10.0
        error >= E5 -> 5.0
        error >= E2 -> 2.0
        else -> 1.0
      }
    var i1: Double
    var i2: Double
    var inc: Double
    // `power < 0` is false for a NaN power, which is how a NaN bound reaches the else branch here
    // and comes back out as NaN rather than as an infinity — JavaScript's comparison, kept.
    if (power < 0) {
      inc = 10.0.pow(-power) / factor
      i1 = roundHalfUp(start * inc)
      i2 = roundHalfUp(stop * inc)
      if (i1 / inc < start) i1 += 1
      if (i2 / inc > stop) i2 -= 1
      inc = -inc
    } else {
      inc = 10.0.pow(power) * factor
      i1 = roundHalfUp(start / inc)
      i2 = roundHalfUp(stop / inc)
      if (i1 * inc < start) i1 += 1
      if (i2 * inc > stop) i2 -= 1
    }
    if (i2 < i1 && 0.5 <= count && count < 2) return tickSpec(start, stop, count * 2)
    return TickSpec(i1, i2, inc)
  }

  private class TickSpec(val i1: Double, val i2: Double, val inc: Double)

  /**
   * The increment between ticks: a step, or **`-k` meaning a step of `1/k`**.
   *
   * The negative form is d3's way of keeping a fractional step exact — a tenth is `-10`, not `0.1`
   * — so `0.1 + 0.2` never enters an axis.
   */
  public fun tickIncrement(start: Double, stop: Double, count: Int): Double =
    tickIncrement(start, stop, count.toDouble())

  public fun tickIncrement(start: Double, stop: Double, count: Double): Double =
    tickSpec(start, stop, count).inc

  /**
   * d3's `tickStep`: the increment as a **signed magnitude**, negative for a reversed span.
   *
   * Not `stepFrom(tickIncrement(...))`, which is what this engine used to compute for it: that
   * loses the sign of a reversed span, and answers NaN where d3 answers 0 for a span of nothing.
   */
  public fun step(start: Double, stop: Double, count: Double): Double {
    val reverse = stop < start
    val inc = if (reverse) tickIncrement(stop, start, count) else tickIncrement(start, stop, count)
    return (if (reverse) -1.0 else 1.0) * (if (inc < 0) 1.0 / -inc else inc)
  }

  /** Tick values between [start] and [stop], approximately [count] of them, ascending. */
  public fun ticks(start: Double, stop: Double, count: Int): List<Double> =
    ticks(start, stop, count.toDouble())

  public fun ticks(start: Double, stop: Double, count: Double): List<Double> {
    if (!(count > 0)) return emptyList()
    if (start == stop) return listOf(start)
    val reverse = stop < start
    val spec = if (reverse) tickSpec(stop, start, count) else tickSpec(start, stop, count)
    if (!(spec.i2 >= spec.i1)) return emptyList()
    val n = (spec.i2 - spec.i1 + 1).toInt()
    if (n <= 0) return emptyList()
    val inc = spec.inc
    return if (reverse) {
      if (inc < 0) List(n) { (spec.i2 - it) / -inc } else List(n) { (spec.i2 - it) * inc }
    } else {
      if (inc < 0) List(n) { (spec.i1 + it) / -inc } else List(n) { (spec.i1 + it) * inc }
    }
  }

  /**
   * Extends [domain] outward to the nearest round numbers, as `d3.scaleLinear().nice()` does.
   *
   * Iterates because widening the domain can change the step, and stops after a bounded number of
   * passes so a pathological domain cannot loop forever.
   */
  public fun nice(domain: List<Double>, count: Int = 10): List<Double> {
    if (domain.size < 2) return domain
    val result = domain.toMutableList()
    var i0 = 0
    var i1 = result.size - 1
    var start = result[i0]
    var stop = result[i1]

    if (stop < start) {
      // A reversed domain nices the same endpoints; swap the values and the indices they write back
      // to, so the reversal is preserved.
      val value = start
      start = stop
      stop = value
      val index = i0
      i0 = i1
      i1 = index
    }
    if (!start.isFinite() || !stop.isFinite() || start == stop) return domain

    var previousStep = Double.NaN
    var iterations = MAX_NICE_PASSES
    while (iterations-- > 0) {
      val step = tickIncrement(start, stop, count)
      // d3 stops on any step it cannot widen with: unchanged, zero, or not finite. Without the last
      // two, a count of zero or a NaN bound walked into the arithmetic below and returned NaN.
      if (step == previousStep || step == 0.0 || !step.isFinite()) break
      when {
        step > 0 -> {
          start = floor(start / step) * step
          stop = ceil(stop / step) * step
        }
        step < 0 -> {
          // `step` is the negated reciprocal, so multiplying flips the rounding direction.
          start = ceil(start * step) / step
          stop = floor(stop * step) / step
        }
        else -> break
      }
      previousStep = step
    }

    result[i0] = start
    result[i1] = stop
    return result.toList()
  }

  /**
   * Converts a [tickIncrement] result into an actual step size.
   *
   * [tickIncrement] returns `-k` to mean a step of `1/k`, so callers that want the step itself —
   * label formatting, for instance — have to undo that convention rather than using the raw value.
   */
  public fun stepFrom(increment: Double): Double =
    when {
      !increment.isFinite() -> Double.NaN
      increment > 0 -> increment
      increment < 0 -> -1.0 / increment
      else -> Double.NaN
    }

  /**
   * Decimal places needed to distinguish ticks [step] apart, for default label formatting.
   *
   * Takes an actual step, not a [tickIncrement] result; pass the latter through [stepFrom] first.
   */
  public fun precisionForStep(step: Double): Int =
    if (step <= 0.0) 0 else NumberFormat.precisionFixed(step)

  /**
   * A format specifier with the precision the span implies, when the specification left it out.
   *
   * This is upstream's `formatSpan`, and it is what makes `"format": "%"` on a legend read `−6%`
   * rather than `−6.000000%`: a specifier with no precision does not mean "no decimals", it means
   * "as many as the tick step needs", and the number of them depends on the domain being labelled.
   * A percent format takes **two fewer**, because the value is multiplied by a hundred first.
   *
   * A specifier that already names a precision is left exactly as written, and so is `d`, which has
   * no case in upstream's switch. `s` is not resolvable to a specifier string at all — see
   * [spanFormatter].
   */
  public fun spanSpecifier(specifier: String, start: Double, stop: Double, count: Int): String {
    if (specifier.contains('.')) return specifier
    val type = specifier.lastOrNull()?.takeIf { it.isLetter() || it == '%' }
    // `s` is resolved by [spanFormatter] instead: one SI prefix is fixed for the whole span, which
    // no specifier string can say. `d` has no case in upstream's switch.
    if (type == 's' || type == 'd') return specifier
    val step = stepFrom(tickIncrement(start, stop, count))
    if (!step.isFinite() || step <= 0.0) return specifier
    val magnitude = maxOf(abs(start), abs(stop))
    val precision =
      when (type) {
        'f',
        '%' -> precisionForStep(step) - (if (type == '%') 2 else 0)
        'e' -> precisionForRound(step, magnitude) - 1
        null,
        'g',
        'p',
        'r' -> precisionForRound(step, magnitude)
        else -> return specifier
      }
    // d3's `FormatSpecifier` clamps a precision into `[0, 20]` as it stores it, so a percent
    // format over a coarse step does not come out with a negative one.
    val clamped = precision.coerceIn(0, 20)
    return if (type == null) "$specifier.$clamped" else specifier.dropLast(1) + ".$clamped" + type
  }

  private fun precisionForRound(step: Double, magnitude: Double): Int =
    if (step <= 0.0 || magnitude <= 0.0) 0 else NumberFormat.precisionRound(step, magnitude)

  /**
   * The label formatter a span implies — [spanSpecifier], plus the one case a specifier cannot say.
   *
   * `s` is that case. Upstream resolves it with `formatPrefix`, which fixes **one** SI prefix for
   * the whole span from its largest magnitude, so an axis over two million reads `0.5M | 1.0M |
   * 1.5M | 2.0M`. Formatting each label on its own instead gives `500k | 1M | 1.5M | 2M` — mixed
   * units down one axis, which is the kind of wrong that looks like a data error.
   */
  public fun spanFormatter(
    specifier: String,
    start: Double,
    stop: Double,
    count: Int,
    locale: VegaLocale = VegaLocale.EnglishUS,
  ): (Double) -> String {
    val parsed = NumberFormat.parse(specifier)
    if (parsed != null && parsed.type == 's' && parsed.precision == null) {
      val step = stepFrom(tickIncrement(start, stop, count))
      val magnitude = maxOf(abs(start), abs(stop))
      if (step.isFinite() && step > 0.0) {
        val precision = NumberFormat.precisionPrefix(step, magnitude).coerceIn(0, 20)
        val prefixed = NumberFormat.prefixed(parsed.copy(precision = precision), magnitude, locale)
        return { value -> prefixed(value) }
      }
    }
    val resolved = spanSpecifier(specifier, start, stop, count)
    return { value -> NumberFormat.format(value, resolved, locale) }
  }

  /**
   * Tick values for a log scale, ported from `d3.scaleLog().ticks()`.
   *
   * Two behaviours are easy to miss. When the domain spans few enough octaves, the ticks are the
   * integer multiples `1..base-1` at each power — so base 10 over `[1, 1000]` gives 1…9, 10, 20…90,
   * 100…, not just the powers. And when that produces fewer than half the requested count, d3
   * *falls back to linear ticks*: base 2 over `[1, 8]` yields 1, 1.5, 2, 2.5… rather than 1, 2,
   * 4, 8.
   */
  public fun logTicks(start: Double, stop: Double, base: Double, count: Int = 10): List<Double> {
    if (start <= 0.0 && stop <= 0.0) {
      // A wholly negative domain mirrors the positive case.
      return logTicks(-stop, -start, base, count).map { -it }.reversed()
    }
    if (start <= 0.0 || stop <= 0.0 || base <= 1.0) return emptyList()

    val reverse = stop < start
    val lo = if (reverse) stop else start
    val hi = if (reverse) start else stop

    val logLo = log(lo, base)
    val logHi = log(hi, base)
    val integerBase = base == floor(base)

    val values = mutableListOf<Double>()
    if (integerBase && logHi - logLo < count) {
      var i = floor(logLo).toInt()
      val j = ceil(logHi).toInt()
      outer@ while (i <= j) {
        var k = 1
        while (k < base.toInt()) {
          val value = if (i < 0) k / base.pow(-i) else k * base.pow(i)
          if (value < lo) {
            k++
            continue
          }
          if (value > hi) break@outer
          values.add(value)
          k++
        }
        i++
      }
      // Too sparse to be useful, so d3 abandons the log spacing entirely.
      if (values.size * 2 < count) {
        val linear = ticks(lo, hi, count)
        return if (reverse) linear.reversed() else linear
      }
    } else {
      val span = ticks(logLo, logHi, minOf((logHi - logLo).toInt(), count).coerceAtLeast(1))
      values.addAll(span.map { base.pow(it) })
    }
    return if (reverse) values.reversed() else values
  }

  /**
   * Extends a log domain outward to the enclosing powers of [base], as `d3.scaleLog().nice()` does.
   *
   * `[3, 700]` at base 10 becomes `[1, 1000]`.
   */
  public fun niceLog(domain: List<Double>, base: Double): List<Double> {
    if (domain.size < 2 || base <= 1.0) return domain
    val start = domain.first()
    val stop = domain.last()
    if (start <= 0.0 || stop <= 0.0 || !start.isFinite() || !stop.isFinite()) return domain

    val reverse = stop < start
    val lo = if (reverse) stop else start
    val hi = if (reverse) start else stop
    val niceLo = base.pow(floor(log(lo, base)))
    val niceHi = base.pow(ceil(log(hi, base)))
    val result = domain.toMutableList()
    result[0] = if (reverse) niceHi else niceLo
    result[result.size - 1] = if (reverse) niceLo else niceHi
    return result
  }

  private fun log(value: Double, base: Double): Double = ln(value) / ln(base)

  private const val MAX_NICE_PASSES = 8
}
