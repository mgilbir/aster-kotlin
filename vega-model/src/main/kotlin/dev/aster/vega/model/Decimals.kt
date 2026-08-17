package dev.aster.vega.model

import io.github.mgilbir.ecma262.number.toEcmaExponential
import io.github.mgilbir.ecma262.number.toEcmaFixed
import io.github.mgilbir.ecma262.number.toEcmaPrecision
import io.github.mgilbir.ecma262.number.toEcmaString

/**
 * JavaScript's number-to-text conversions, delegated to **ktecma262**.
 *
 * This file used to implement them: `toFixed`, `toExponential`, `toPrecision`, `String(x)`, and the
 * exact decimal expansion underneath all four, together with a small big-integer type to carry it.
 * The reasoning was sound and is worth keeping in view, because it is why the answers are what they
 * are — a finite double is *exactly* `m × 2^e`, so `m × 2^-k` is `(m × 5^k) × 10^-k` and its
 * decimal expansion is **finite**, at most about 767 digits. Rounding that exact expansion is what
 * makes `(2.675).toFixed(2)` come out as `2.67`: the stored value is `2.67499999999999982…`, and
 * rounding the shortest *printed* form instead would give `2.68`.
 *
 * `ktecma262` implements the same specification directly — the ECMA-262 clauses rather than a port
 * of d3's use of them — with Grisu3 for the shortest round-trip and an exact fallback. Two
 * implementations of one specification is one too many, and the library's is the one with the
 * spec's own test suite behind it.
 *
 * What remains here is the vocabulary this engine speaks: [trimmed] for canonical output, and
 * [Shortest] as a pair of digits and a decimal exponent, which is how `NumberFormat` wants its
 * answer. `DecimalsTest` still checks every one of these against `java.math.BigDecimal` over a
 * hundred thousand random doubles — the oracle stayed when the implementation went, which is what
 * made swapping it a five-minute change rather than an act of faith.
 *
 * Non-finite values never arrive at the library; every caller spells `NaN` and `Infinity` itself,
 * because d3 writes them the way JavaScript does and that is a formatting decision rather than an
 * arithmetic one.
 */
public object Decimals {

  /** `%.Nf`: [decimals] places, half-up on the exact value, no thousands separators. */
  public fun fixed(value: Double, decimals: Int): String {
    nonFinite(value)?.let {
      return it
    }
    // `toFixed` keeps a negative zero's sign and `BigDecimal` has no negative zero; this engine
    // follows `BigDecimal`, so `-0.001` at two places is `0.00` rather than `-0.00`.
    val text = value.toEcmaFixed(decimals)
    return if (text.startsWith("-") && text.all { it == '-' || it == '0' || it == '.' }) {
      text.substring(1)
    } else {
      text
    }
  }

  /**
   * JavaScript's `toExponential`: scientific notation with [decimals] places after the point.
   *
   * The exponent carries a sign and no padding — `5e-3`, never `5e-03` — because that is what
   * JavaScript writes and d3 formats an `e` by calling `toExponential` directly.
   */
  public fun exponential(value: Double, decimals: Int): String {
    nonFinite(value)?.let {
      return it
    }
    return value.toEcmaExponential(decimals)
  }

  /**
   * JavaScript's `toPrecision`: [digits] significant digits.
   *
   * The notation switch is the part worth writing down, because it is not "whichever is shorter":
   * JavaScript goes exponential when the decimal exponent is below -7 or at least [digits], and
   * fixed otherwise. That is what makes `1e-7` print as `1e-7` while `0.000001234` prints in full.
   */
  public fun significant(value: Double, digits: Int): String {
    nonFinite(value)?.let {
      return it
    }
    return value.toEcmaPrecision(digits)
  }

  /**
   * [fixed] with trailing zeros and a trailing point removed, so `1.0` and `1` agree.
   *
   * This is what canonical snapshots and SVG attributes are written with, where two runs that mean
   * the same number have to produce the same bytes.
   */
  public fun trimmed(value: Double, decimals: Int): String {
    val text = fixed(value, decimals)
    if ('.' !in text) return text
    return text.trimEnd('0').trimEnd('.')
  }

  /**
   * The **fewest** digits that read back as this double, and where the point goes.
   *
   * `String(x)` is not told how many digits to write: JavaScript's answer is the shortest decimal
   * that no other double is nearer to, which is why `0.1 + 0.2` prints as `0.30000000000000004`
   * while `0.3` prints as `0.3`. The platform's own `toString` is not a substitute — it writes at
   * least two significant digits and switches to exponential notation at 10^7 rather than 10^21.
   */
  public fun shortest(value: Double): Shortest {
    // `toExponential` with **no argument** is exactly this question in the specification's own
    // vocabulary — the fewest digits that identify the double — so the digit count comes from the
    // library rather than from a round-trip search here.
    val text = kotlin.math.abs(value).toEcmaExponential(null)
    val marker = text.indexOf('e')
    val digits = text.substring(0, marker).replace(".", "").trimEnd('0').ifEmpty { "0" }
    val exponent = text.substring(marker + 1).removePrefix("+").toInt()
    return Shortest(digits, exponent + 1)
  }

  /** The value is `0.digits × 10^exponent`, with [digits] carrying no leading or trailing zero. */
  public class Shortest internal constructor(public val digits: String, public val exponent: Int)

  /** `String(x)`: [shortest]'s digits with ECMA-262's point placement. */
  public fun jsString(value: Double): String =
    when {
      value.isNaN() -> "NaN"
      value == Double.POSITIVE_INFINITY -> "Infinity"
      value == Double.NEGATIVE_INFINITY -> "-Infinity"
      else -> value.toEcmaString()
    }

  private fun nonFinite(value: Double): String? =
    when {
      value.isNaN() -> "NaN"
      value == Double.POSITIVE_INFINITY -> "Infinity"
      value == Double.NEGATIVE_INFINITY -> "-Infinity"
      else -> null
    }
}
