package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which Vega-Lite version the specification says it is, against the one these rules implement.
 *
 * `isVegaLite` accepts any `$schema` URL with "vega-lite" in it, which is deliberate — it is also
 * what lets a specification with no schema at all be recognised by its shape, and most captured
 * payloads have none. What was missing is the other half: a payload declaring a version these rules
 * are not was compiled with version 6 rules and said nothing about it.
 */
class SchemaVersionTest {

  private fun compile(schema: String?): VegaLiteCompilation {
    val declared = schema?.let { """"${'$'}schema": "$it",""" } ?: ""
    return VegaLiteCompiler()
      .compile(
        VegaJson.parse(
          """
          {
            $declared
            "data": {"values": [{"a": 1, "b": 2}]},
            "mark": "point",
            "encoding": {
              "x": {"field": "a", "type": "quantitative"},
              "y": {"field": "b", "type": "quantitative"}
            }
          }
          """
            .trimIndent()
        )
      )
  }

  private fun versionDiagnostics(compiled: VegaLiteCompilation) =
    compiled.diagnostics.filter { it.code == VegaLiteDiagnostics.SCHEMA_VERSION }

  @Test
  fun `the version these rules implement passes without a word`() {
    val compiled = compile("https://vega.github.io/schema/vega-lite/v6.json")
    assertEquals(emptyList<String>(), versionDiagnostics(compiled).map { it.message })
  }

  @Test
  fun `no schema at all is not a version claim, so there is nothing to report`() {
    assertEquals(emptyList<String>(), versionDiagnostics(compile(null)).map { it.message })
  }

  @Test
  fun `a newer major version is reported, and still compiles`() {
    val compiled = compile("https://vega.github.io/schema/vega-lite/v7.json")
    val reported = versionDiagnostics(compiled)

    assertEquals(1, reported.size, "one claim, one diagnostic: ${compiled.diagnostics}")
    assertTrue(reported.single().message.contains("Vega-Lite 7"), reported.single().message)
    assertTrue(reported.single().message.contains("newer"), reported.single().message)
    assertEquals("$.\$schema", reported.single().jsonPath)
    assertNotNull(compiled.toJson(), "reported rather than refused; the chart still draws")
  }

  @Test
  fun `an older major version is reported as older`() {
    val reported = versionDiagnostics(compile("https://vega.github.io/schema/vega-lite/v5.json"))
    assertEquals(1, reported.size)
    assertTrue(reported.single().message.contains("Vega-Lite 5"), reported.single().message)
    assertTrue(reported.single().message.contains("older"), reported.single().message)
  }

  /**
   * A patched or unusual URL still routes as Vega-Lite and reports nothing: there is a difference
   * between "declares a version this is not" and "declares a version this cannot read", and only
   * the first is worth a reader's attention.
   */
  @Test
  fun `a schema URL with no version in it says nothing about a version`() {
    assertEquals(
      emptyList<String>(),
      versionDiagnostics(compile("https://example.test/vega-lite.json")).map { it.message },
    )
  }
}
