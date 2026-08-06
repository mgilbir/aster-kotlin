package dev.aster.vega.runtime.scale

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
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
        var i1 = Math.round(lo / step).toDouble()
        var i2 = Math.round(hi / step).toDouble()
        if (i1 * step < lo) i1 += 1
        if (i2 * step > hi) i2 -= 1
        val n = (i2 - i1 + 1).toInt()
        if (n <= 0) emptyList() else List(n) { (i1 + it) * step }
      } else {
        val inverse = -step
        var i1 = Math.round(lo * inverse).toDouble()
        var i2 = Math.round(hi * inverse).toDouble()
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

  private const val MAX_NICE_PASSES = 8
}
