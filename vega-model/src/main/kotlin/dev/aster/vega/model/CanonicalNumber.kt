package dev.aster.vega.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Canonical numeric formatting shared by snapshot serialization, SVG output and label text.
 *
 * Rules (PROJECT_BRIEF.md 4.4 and 18.2):
 * - negative zero normalizes to `0`
 * - non-finite values serialize to explicit tokens instead of platform-specific text
 * - trailing zeros and a trailing decimal point are removed so `1.0` and `1` agree
 * - the exponent form is never emitted, because SVG attribute parsers and golden diffs both
 *   tolerate plain decimals better
 */
public const val DEFAULT_DECIMAL_PRECISION: Int = 6

public fun canonicalNumberString(
  value: Double,
  precision: Int = DEFAULT_DECIMAL_PRECISION,
): String {
  require(precision in 0..17) { "precision must be in 0..17, was $precision" }
  if (value.isNaN()) return "NaN"
  if (value == Double.POSITIVE_INFINITY) return "Infinity"
  if (value == Double.NEGATIVE_INFINITY) return "-Infinity"

  val normalized = normalizeZero(value)
  val rounded =
    BigDecimal(normalized).setScale(precision, RoundingMode.HALF_UP).stripTrailingZeros()
  val text = rounded.toPlainString()
  // stripTrailingZeros can leave "0E-6"-style values; toPlainString already expands those, but a
  // rounded-to-zero negative still needs the sign removed.
  return if (text == "-0") "0" else text
}

/**
 * JavaScript's `Math.round`: halves go to positive infinity, so `-2.5` rounds to `-2`.
 *
 * Not Kotlin's `round`, which rounds halves away from zero in both directions, and not Java's
 * `Math.round`, which is unavailable off the JVM. Every rounding in this engine has to agree with
 * d3's, and d3 rounds the way its host language does.
 */
public fun roundHalfUp(value: Double): Double =
  if (value.isNaN() || value.isInfinite()) value else kotlin.math.floor(value + 0.5)

/** Maps `-0.0` to `0.0` and leaves every other value untouched. */
public fun normalizeZero(value: Double): Double = if (value == 0.0) 0.0 else value

/**
 * Replaces non-finite values with [fallback] so geometry never leaks `NaN` into a scene. Callers
 * that must preserve `NaN` (expression results, datum fields) should not use this.
 */
public fun finiteOr(value: Double, fallback: Double = 0.0): Double =
  if (value.isFinite()) normalizeZero(value) else fallback
