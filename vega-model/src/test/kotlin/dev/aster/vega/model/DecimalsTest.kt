package dev.aster.vega.model

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.random.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The exact-expansion arithmetic, checked against the `BigDecimal` it replaced.
 *
 * `Decimals` used to be the core's one non-portable file, and the argument for it was that rounding
 * a double at N places needs arbitrary-precision arithmetic. It needs *exactness*, which is a
 * different requirement: a finite double is `m × 2^e`, so its decimal expansion is finite and
 * eighty lines of multiply-and-shift produce it. That is the sort of claim that is either exactly
 * right or quietly wrong in the last digit of one value in a million, so it is not argued — it is
 * compared.
 *
 * [Reference] below is the previous implementation, unchanged. It stays here because a JVM test may
 * use JVM APIs, and having the oracle in the test rather than in the engine is the whole point of
 * the change. Every case is checked against it: the awkward values people have written down, the
 * ends of the double range, and a hundred thousand random bit patterns.
 */
class DecimalsTest {

  /** Exactly the implementation this replaced, kept as the oracle. */
  private object Reference {
    fun fixed(value: Double, decimals: Int): String =
      BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).toPlainString()

    /**
     * Assembled from `BigDecimal` rather than taken from `String.format("%.Ne")`, which is what the
     * old implementation used and what this test **caught**.
     *
     * Java's `%e` rounds the double's shortest printable representation, not its exact value: it
     * writes `2.68e+00` for `2.675`, whose exact value is `2.674999999…`. JavaScript's
     * `toExponential` rounds the exact value and answers `2.67e+0`, d3 formats an `e` by calling
     * it, and `oracle-js` confirms `format('.2e')(2.675)` is `2.67e+0` upstream. So the engine had
     * been wrong here for as long as it had a formatter, and only for values sitting on a tie that
     * the shortest form rounds the other way.
     *
     * `BigDecimal(double)` is exact, so rounding *it* to N+1 significant digits is the right
     * oracle.
     */
    fun exponential(value: Double, decimals: Int): String {
      if (!value.isFinite())
        return if (value.isNaN()) "NaN" else if (value > 0) "Infinity" else "-Infinity"
      val sign = if (1.0 / value < 0 || value < 0) "-" else ""
      if (value == 0.0) {
        val mantissa = if (decimals > 0) "0." + "0".repeat(decimals) else "0"
        return "$sign${mantissa}e+0"
      }
      val rounded = BigDecimal(value).abs().round(MathContext(decimals + 1, RoundingMode.HALF_UP))
      val exponent = rounded.precision() - rounded.scale() - 1
      val mantissa = rounded.movePointLeft(exponent).setScale(decimals, RoundingMode.HALF_UP)
      val exponentSign = if (exponent < 0) "-" else "+"
      return "$sign${mantissa.toPlainString()}e$exponentSign${kotlin.math.abs(exponent)}"
    }

    fun significant(value: Double, digits: Int): String {
      if (value == 0.0 || !value.isFinite()) return fixed(value, digits - 1)
      val rounded = BigDecimal(value).round(MathContext(digits, RoundingMode.HALF_UP))
      val exponent = rounded.precision() - rounded.scale() - 1
      return if (exponent < -6 || exponent >= digits) {
        exponential(value, digits - 1)
      } else {
        fixed(value, digits - 1 - exponent)
      }
    }

    fun trimmed(value: Double, decimals: Int): String =
      BigDecimal(value)
        .setScale(decimals, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
  }

  private fun compare(value: Double, decimals: Int) {
    val where = "value=$value decimals=$decimals"
    assertEquals(Reference.fixed(value, decimals), Decimals.fixed(value, decimals), "fixed $where")
    assertEquals(
      Reference.trimmed(value, decimals),
      Decimals.trimmed(value, decimals),
      "trimmed $where",
    )
    assertEquals(
      Reference.exponential(value, decimals),
      Decimals.exponential(value, decimals),
      "exponential $where",
    )
    val digits = decimals + 1
    assertEquals(
      Reference.significant(value, digits),
      Decimals.significant(value, digits),
      "significant value=$value digits=$digits",
    )
  }

  /**
   * The cases that separate rounding the exact value from rounding what a number looks like.
   *
   * `2.675` is the canonical one — stored just below the tie, so half-up gives `2.67` — and the
   * rest are the ends of the range and the places a carry moves the exponent.
   */
  @Test
  fun `the awkward values agree`() {
    val values =
      listOf(
        0.0,
        -0.0,
        1.0,
        -1.0,
        0.5,
        -0.5,
        2.675,
        -2.675,
        1.005,
        1.0049999999999999,
        0.1,
        0.2,
        0.3,
        1.0 / 3.0,
        2.0 / 3.0,
        9.995,
        9.999999999,
        99.99999,
        1e-7,
        1.234e-7,
        0.000001234,
        1e21,
        1.7976931348623157e308, // Double.MAX_VALUE
        4.9e-324, // Double.MIN_VALUE, a subnormal with the widest expansion of any double
        1.1125369292536007e-308, // the largest subnormal's neighbourhood
        2.2250738585072014e-308, // the smallest normal
        1234567890123456.0,
        12345678901234567890.0,
        123456.789,
        -123456.789,
        1e-300,
        1e300,
        Double.MIN_VALUE * 3,
      )
    for (value in values) {
      for (decimals in 0..17) compare(value, decimals)
      // Precisions a format string can legitimately ask for beyond the usual handful.
      for (decimals in listOf(20, 25, 40)) compare(value, decimals)
    }
  }

  /**
   * A hundred thousand random doubles, drawn as **bit patterns** rather than from a range.
   *
   * Sampling `nextDouble()` would only ever exercise numbers between 0 and 1, which share an
   * exponent range and hide everything interesting; random bits cover subnormals, the huge end, and
   * the mantissa patterns where a tie is exact.
   */
  @Test
  fun `random doubles agree with the reference`() {
    val random = Random(20260814)
    var checked = 0
    while (checked < 100_000) {
      val value = Double.fromBits(random.nextLong())
      if (!value.isFinite()) continue
      compare(value, random.nextInt(0, 18))
      checked++
    }
  }

  /**
   * Values shaped like the ones a chart actually formats, at the precisions it actually asks for.
   *
   * Random bits are mostly enormous or minuscule; an axis label is a small number with a few
   * decimals, which is a different part of the space and the one that would be noticed.
   */
  @Test
  fun `chart-shaped values agree with the reference`() {
    val random = Random(19700101)
    repeat(50_000) {
      val magnitude = random.nextInt(-9, 10)
      val value = random.nextDouble(-1000.0, 1000.0) * TEN.pow(magnitude)
      compare(value, random.nextInt(0, 10))
    }
  }

  /** Non-finite values are answered rather than thrown on; the reference threw. */
  @Test
  fun `non-finite values are spelled out`() {
    assertEquals("NaN", Decimals.fixed(Double.NaN, 2))
    assertEquals("Infinity", Decimals.fixed(Double.POSITIVE_INFINITY, 2))
    assertEquals("-Infinity", Decimals.trimmed(Double.NEGATIVE_INFINITY, 6))
    assertEquals("NaN", Decimals.exponential(Double.NaN, 3))
    assertEquals("NaN", Decimals.significant(Double.NaN, 3))
  }

  private fun Double.pow(exponent: Int): Double {
    var result = 1.0
    repeat(if (exponent < 0) -exponent else exponent) { result *= this }
    return if (exponent < 0) 1.0 / result else result
  }

  private companion object {
    const val TEN = 10.0
  }
}
