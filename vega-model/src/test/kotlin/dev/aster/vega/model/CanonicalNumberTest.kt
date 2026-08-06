package dev.aster.vega.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class CanonicalNumberTest {

  @Test
  fun `negative zero normalizes to zero`() {
    assertEquals("0", canonicalNumberString(-0.0))
    assertEquals(0.0, normalizeZero(-0.0))
    // Kotlin's == says -0.0 == 0.0, so compare the raw bits to prove the sign is gone.
    assertEquals(0L, normalizeZero(-0.0).toRawBits())
  }

  @Test
  fun `non-finite values serialize to explicit tokens`() {
    assertEquals("NaN", canonicalNumberString(Double.NaN))
    assertEquals("Infinity", canonicalNumberString(Double.POSITIVE_INFINITY))
    assertEquals("-Infinity", canonicalNumberString(Double.NEGATIVE_INFINITY))
  }

  @ParameterizedTest
  @CsvSource(
    "1.0, 1",
    "1.5, 1.5",
    "0.1, 0.1",
    "-2.25, -2.25",
    "100, 100",
    "0.0000001, 0",
    "1234567.891, 1234567.891",
  )
  fun `formats without exponents or trailing zeros`(input: Double, expected: String) {
    assertEquals(expected, canonicalNumberString(input))
  }

  @Test
  fun `large magnitudes stay in plain decimal form`() {
    val text = canonicalNumberString(1e21)
    assertTrue(text.none { it == 'e' || it == 'E' }, "expected plain decimal, got $text")
  }

  @Test
  fun `precision is configurable and rounds half up`() {
    assertEquals("0.13", canonicalNumberString(0.125, precision = 2))
    assertEquals("0.125", canonicalNumberString(0.125, precision = 3))
    assertEquals("0", canonicalNumberString(0.4, precision = 0))
  }

  @Test
  fun `rounding a small negative value does not leave a negative zero`() {
    assertEquals("0", canonicalNumberString(-1e-9))
  }

  @Test
  fun `finiteOr replaces non-finite values`() {
    assertEquals(0.0, finiteOr(Double.NaN))
    assertEquals(7.0, finiteOr(Double.POSITIVE_INFINITY, fallback = 7.0))
    assertEquals(3.5, finiteOr(3.5))
    assertEquals(0L, finiteOr(-0.0).toRawBits())
  }
}
