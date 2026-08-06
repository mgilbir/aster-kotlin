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

  /** `%.Ne`: scientific notation with [decimals] places after the point. */
  public fun exponential(value: Double, decimals: Int): String =
    String.format(java.util.Locale.ROOT, "%.${decimals}e", value)

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
