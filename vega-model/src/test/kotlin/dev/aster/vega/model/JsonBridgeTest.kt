package dev.aster.vega.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JsonBridgeTest {

  @Test
  fun `parses scalars arrays and objects into the value model`() {
    val value =
      VegaJson.parse(
        """{"n": 1, "f": 2.5, "s": "text", "b": true, "nil": null, "arr": [1, "two"]}"""
      )
    assertEquals(VegaValue.Num(1.0), value.field("n"))
    assertEquals(VegaValue.Num(2.5), value.field("f"))
    assertEquals(VegaValue.Str("text"), value.field("s"))
    assertEquals(VegaValue.Bool(true), value.field("b"))
    assertEquals(VegaValue.Null, value.field("nil"))
    assertEquals(VegaValue.Num(1.0), value.field("arr[0]"))
    assertEquals(VegaValue.Str("two"), value.field("arr[1]"))
  }

  @Test
  fun `object key order is preserved`() {
    val value = VegaJson.parse("""{"z": 1, "a": 2, "m": 3}""")
    val obj = value as VegaValue.Obj
    assertEquals(listOf("z", "a", "m"), obj.fields.keys.toList())
  }

  @Test
  fun `integers stay numeric rather than becoming strings`() {
    val value = VegaJson.parse("""{"big": 9007199254740993}""")
    assertTrue(value.field("big") is VegaValue.Num)
  }

  /**
   * Every expectation is `JSON.stringify(x)` from node, read out rather than reasoned about. The
   * point of the two exponential rows is that the switch happens at 10^21 and 10^-7 and nowhere
   * else — Kotlin's own `toString` switches at 10^7 and would write `1.5E-6` for the fourth.
   */
  @Test
  fun `numbers are written the way JSON stringify writes them`() {
    fun written(value: Double) =
      VegaJson.write(VegaValue.Obj(linkedMapOf("v" to VegaValue.Num(value))))
        .substringAfter(": ")
        .substringBefore("\n")
        .trim()

    assertEquals("1", written(1.0))
    assertEquals("0", written(-0.0))
    assertEquals("1.5", written(1.5))
    assertEquals("0.0000015", written(1.5e-6))
    assertEquals("1e-7", written(1e-7))
    assertEquals("100000000000000000000", written(1e20))
    assertEquals("1e+21", written(1e21))
    assertEquals("0.30000000000000004", written(0.1 + 0.2))
    assertEquals("null", written(Double.NaN))
    assertEquals("null", written(Double.POSITIVE_INFINITY))
  }

  @Test
  fun `invalid json produces a fatal diagnostic`() {
    val failure = assertThrows<VegaSpecException> { VegaJson.parse("{not json") }
    assertEquals(DiagnosticCodes.PARSE_INVALID_JSON, failure.diagnostic.code)
    assertEquals(DiagnosticSeverity.FATAL, failure.diagnostic.severity)
  }

  @Test
  fun `parseOrNull records the diagnostic instead of throwing`() {
    val diagnostics = DiagnosticCollector()
    assertNull(VegaJson.parseOrNull("{not json", diagnostics))
    assertEquals(1, diagnostics.diagnostics.size)
    assertTrue(diagnostics.hasFatal)
  }
}
