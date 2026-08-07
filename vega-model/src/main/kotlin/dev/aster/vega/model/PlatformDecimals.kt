package dev.aster.vega.model

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Fixed-precision decimal formatting: the one place in the core that is not portable Kotlin.
 *
 * Everything else in the core is common Kotlin and moves to Kotlin Multiplatform unchanged. This
 * does not, and the reason is worth stating rather than working around.
 *
 * Rounding a decimal at *N* places has to round the double's **exact** binary value, not its
 * shortest printable form. The two disagree: `2.675` is stored as `2.67499999999999982…`, so `%.2f`
 * gives `2.67` while rounding the string `"2.675"` gives `2.68`. JavaScript's `toFixed` rounds the
 * exact value, so d3 does, so this engine must — and expanding a double exactly needs
 * arbitrary-precision arithmetic, which common Kotlin has none of.
 *
 * So this stays platform code. When the core becomes multiplatform this file is the `expect`, and
 * each target supplies an `actual`: `BigDecimal` on the JVM, `NSDecimalNumber` or a small decimal
 * expansion elsewhere. Confining it to one file is what makes that a mechanical change instead of a
 * hunt, and `NoAndroidTypesTest` names this file as the single permitted exception.
 */
public object PlatformDecimals {

  /** `%.Nf`: [decimals] places, half-up on the exact value, no thousands separators. */
  public fun fixed(value: Double, decimals: Int): String =
    BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).toPlainString()

  /**
   * JavaScript's `toExponential`: scientific notation with [decimals] places after the point.
   *
   * Java pads the exponent to two digits — `5.00e-03` — and JavaScript does not. d3 formats an `e`
   * by calling `toExponential` directly, so the padding has to come back off; the sign stays, since
   * JavaScript always writes one.
   */
  public fun exponential(value: Double, decimals: Int): String {
    val text = String.format(java.util.Locale.ROOT, "%.${decimals}e", value)
    val marker = text.indexOf('e')
    if (marker < 0) return text
    val digits = text.substring(marker + 2).trimStart('0').ifEmpty { "0" }
    return text.substring(0, marker + 2) + digits
  }

  /**
   * JavaScript's `toPrecision`: [digits] significant digits.
   *
   * The notation switch is the part worth writing down, because it is not "whichever is shorter":
   * JavaScript goes exponential when the decimal exponent is below -7 or at least [digits], and
   * fixed otherwise. That is what makes `1e-7` print as `1e-7` while `0.000001234` prints in full,
   * and what makes a fifteen-digit integer come out as `1.23456789012e+14` at twelve digits.
   */
  public fun significant(value: Double, digits: Int): String {
    if (value == 0.0 || !value.isFinite()) return fixed(value, digits - 1)
    val rounded = BigDecimal(value).round(java.math.MathContext(digits, RoundingMode.HALF_UP))
    // BigDecimal's unscaled digit count minus its scale is the decimal exponent, taken after
    // rounding because rounding 9.99 to two digits moves it.
    val exponent = rounded.precision() - rounded.scale() - 1
    return if (exponent < -6 || exponent >= digits) {
      exponential(value, digits - 1)
    } else {
      fixed(value, digits - 1 - exponent)
    }
  }

  /**
   * [fixed] with trailing zeros and a trailing point removed, so `1.0` and `1` agree.
   *
   * This is what canonical snapshots and SVG attributes are written with, where two runs that mean
   * the same number have to produce the same bytes.
   */
  public fun trimmed(value: Double, decimals: Int): String {
    val text = BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros()
    return text.toPlainString()
  }
}
