package dev.aster.vega.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DiagnosticCollectorTest {

  @Test
  fun `severity predicates distinguish warnings from errors`() {
    val collector = DiagnosticCollector()
    collector.warn(DiagnosticCodes.SCALE_INVALID_DOMAIN, "domain has zero extent")
    assertFalse(collector.hasErrors)
    assertFalse(collector.hasFatal)

    collector.error(DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED, "geoshape is not implemented")
    assertTrue(collector.hasErrors)
    assertFalse(collector.hasFatal)

    collector.fatal(DiagnosticCodes.PARSE_INVALID_JSON, "unparseable")
    assertTrue(collector.hasFatal)
  }

  @Test
  fun `diagnostics preserve insertion order and are defensively copied`() {
    val collector = DiagnosticCollector()
    collector.info("A", "first")
    collector.info("B", "second")

    val snapshot = collector.diagnostics
    collector.info("C", "third")

    assertEquals(listOf("A", "B"), snapshot.map { it.code })
    assertEquals(listOf("A", "B", "C"), collector.diagnostics.map { it.code })
  }

  @Test
  fun `toString includes code path and operator for debugging`() {
    val diagnostic =
      VegaDiagnostic(
        severity = DiagnosticSeverity.ERROR,
        code = DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
        message = "not implemented",
        jsonPath = "$.data[0].transform[2]",
        operator = "geojson",
      )
    val text = diagnostic.toString()
    assertTrue(text.contains(DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED))
    assertTrue(text.contains("$.data[0].transform[2]"))
    assertTrue(text.contains("geojson"))
  }
}
