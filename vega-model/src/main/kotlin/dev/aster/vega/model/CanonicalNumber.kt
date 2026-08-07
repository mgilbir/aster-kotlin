package dev.aster.vega.model

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
  val text = PlatformDecimals.trimmed(normalized, precision)
  // stripTrailingZeros can leave "0E-6"-style values; toPlainString already expands those, but a
  // rounded-to-zero negative still needs the sign removed.
  return if (text == "-0") "0" else text
}

/**
 * U+2212 MINUS SIGN, which is what d3-format writes and what upstream therefore draws.
 *
 * Not the ASCII hyphen. The distinction is not cosmetic and it is not everywhere: d3 formats the
 * absolute value and prefixes this, so anything that goes through a *format string* — every
 * continuous axis label, every gradient legend label, the `format` expression function — gets it,
 * while anything that goes through JavaScript's own `String(n)` — a discrete axis label, a text
 * mark bound to a numeric field — keeps the hyphen. Two different glyphs of two different widths,
 * so the two are not interchangeable even before a reader notices which one is on the page.
 */
public const val MINUS_SIGN: String = "−"

/**
 * Replaces a leading ASCII hyphen with [MINUS_SIGN].
 *
 * Only the leading one: d3 signs the formatted magnitude, so `-5.00e-3` becomes `−5.00e-3` with the
 * exponent's own hyphen left alone.
 */
public fun withTypographicMinus(text: String): String =
  if (text.startsWith("-")) MINUS_SIGN + text.substring(1) else text

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
