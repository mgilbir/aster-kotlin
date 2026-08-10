package dev.aster.vega.runtime.scale

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
  public fun tickIncrement(start: Double, stop: Double, count: Int): Double {
    val step = (stop - start) / count.coerceAtLeast(0)
    if (!step.isFinite() || step <= 0.0) return Double.NEGATIVE_INFINITY
    val power = floor(log10(step))
    val error = step / 10.0.pow(power)
    val factor =
      when {
        error >= E10 -> 10.0
        error >= E5 -> 5.0
        error >= E2 -> 2.0
        else -> 1.0
      }
    return if (power >= 0) factor * 10.0.pow(power) else -(10.0.pow(-power)) / factor
  }

  /** Tick values between [start] and [stop], approximately [count] of them, ascending. */
  public fun ticks(start: Double, stop: Double, count: Int): List<Double> {
    if (count <= 0) return emptyList()
    if (start == stop) return if (start.isFinite()) listOf(start) else emptyList()

    val reverse = stop < start
    val lo = if (reverse) stop else start
    val hi = if (reverse) start else stop
    val step = tickIncrement(lo, hi, count)
    if (!step.isFinite()) return emptyList()

    val values =
      if (step > 0) {
        // Nudge the bounds by one part in 1e12 so a bound that should be a tick is not lost to
        // floating-point representation, matching d3's `Math.round` on the scaled bounds.
        var i1 = roundHalfUp(lo / step)
        var i2 = roundHalfUp(hi / step)
        if (i1 * step < lo) i1 += 1
        if (i2 * step > hi) i2 -= 1
        val n = (i2 - i1 + 1).toInt()
        if (n <= 0) emptyList() else List(n) { (i1 + it) * step }
      } else {
        val inverse = -step
        var i1 = roundHalfUp(lo * inverse)
        var i2 = roundHalfUp(hi * inverse)
        if (i1 / inverse < lo) i1 += 1
        if (i2 / inverse > hi) i2 -= 1
        val n = (i2 - i1 + 1).toInt()
        if (n <= 0) emptyList() else List(n) { (i1 + it) / inverse }
      }
    return if (reverse) values.reversed() else values
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
      if (step == previousStep) break
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
  public fun precisionForStep(step: Double): Int {
    if (step <= 0.0 || !step.isFinite()) return 0
    val exponent = floor(log10(abs(step)))
    return if (exponent >= 0) 0 else (-exponent).toInt()
  }

  /**
   * A format specifier with the precision the span implies, when the specification left it out.
   *
   * This is upstream's `formatSpan`, and it is what makes `"format": "%"` on a legend read `−6%`
   * rather than `−6.000000%`: a specifier with no precision does not mean "no decimals", it means
   * "as many as the tick step needs", and the number of them depends on the domain being labelled.
   * A percent format takes **two fewer**, because the value is multiplied by a hundred first.
   *
   * A specifier that already names a precision is left exactly as written, and so is `s`, whose
   * precision is decided by an SI prefix this engine does not implement.
   */
  public fun spanSpecifier(specifier: String, start: Double, stop: Double, count: Int): String {
    if (specifier.contains('.')) return specifier
    val type = specifier.lastOrNull()?.takeIf { it.isLetter() || it == '%' }
    if (type == 'd') return specifier
    val step = stepFrom(tickIncrement(start, stop, count))
    if (!step.isFinite() || step <= 0.0) return specifier
    val magnitude = maxOf(abs(start), abs(stop))
    val precision =
      when (type) {
        'f',
        '%' -> precisionForStep(step) - (if (type == '%') 2 else 0)
        'e' -> precisionForRound(step, magnitude) - 1
        // d3's `precisionPrefix`: how many decimals are needed once the value has been divided by
        // the SI prefix the *whole axis* shares. A step of 500 under a 1,000 magnitude leaves one,
        // which is what makes the labels read `−1.0k` rather than `−1k`.
        's' -> precisionPrefix(step, magnitude)
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

  /**
   * d3's `precisionPrefix`: decimals needed after dividing by the SI prefix chosen for [magnitude].
   */
  private fun precisionPrefix(step: Double, magnitude: Double): Int {
    if (magnitude <= 0.0 || !magnitude.isFinite() || step <= 0.0 || !step.isFinite()) return 0
    val prefixExponent = siExponent(magnitude)
    return maxOf(0, prefixExponent - floor(log10(abs(step))).toInt())
  }

  /**
   * The exponent of the SI prefix d3 picks for a magnitude: a multiple of three, clamped to ±24.
   */
  public fun siExponent(magnitude: Double): Int {
    if (magnitude <= 0.0 || !magnitude.isFinite()) return 0
    return floor(log10(magnitude)).toInt().floorDiv(3).coerceIn(-8, 8) * 3
  }

  /**
   * d3's `precisionRound`: significant digits enough to tell values [step] apart at [magnitude].
   */
  private fun precisionForRound(step: Double, magnitude: Double): Int {
    if (step <= 0.0 || !step.isFinite() || magnitude <= 0.0 || !magnitude.isFinite()) return 0
    return maxOf(0.0, floor(log10(magnitude)) - floor(log10(abs(step)))).toInt() + 1
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
