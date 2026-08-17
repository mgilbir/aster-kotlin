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
