package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which grammar a host was handed, decided from the text.
 *
 * A user pasting a chart has pasted a chart, not a dialect, so something has to decide — and the
 * decision has to be right in both directions. Reading Vega as Vega-Lite refuses a working
 * specification; reading Vega-Lite as Vega fails with a message about a missing `marks` array,
 * which reads like nonsense to whoever pasted a perfectly good chart.
 */
class VegaLiteInputTest {

  private val vegaLite =
    """
    {
      "${'$'}schema": "https://vega.github.io/schema/vega-lite/v6.json",
      "data": {"values": [{"a": "A", "b": 28}]},
      "mark": "bar",
      "encoding": {
        "x": {"field": "a", "type": "nominal"},
        "y": {"field": "b", "type": "quantitative"}
      }
    }
    """
      .trimIndent()

  private val vega =
    """
    {
      "${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
      "width": 100, "height": 100,
      "data": [{"name": "t", "values": [{"a": 1}]}],
      "marks": [{"type": "rect", "from": {"data": "t"}}]
    }
    """
      .trimIndent()

  @Test
  fun `the schema routes a Vega-Lite specification through the compiler`() {
    val converted = VegaLiteInput.toVega(vegaLite)
    assertTrue(converted.wasVegaLite, "the schema says Vega-Lite")
    val json = converted.vegaJson
    assertNotNull(json, "a Vega-Lite specification should compile to something")
    val compiled = VegaJson.parse(json!!)
    assertTrue((compiled as VegaValue.Obj).fields.containsKey("marks"), "it should be Vega now")
    assertEquals(
      "https://vega.github.io/schema/vega/v6.json",
      (compiled.fields["\$schema"] as VegaValue.Str).value,
    )
  }

  @Test
  fun `the schema routes a Vega specification straight through, untouched`() {
    val converted = VegaLiteInput.toVega(vega)
    assertFalse(converted.wasVegaLite)
    assertEquals(vega, converted.vegaJson, "Vega must not be rewritten on the way past")
    assertTrue(converted.diagnostics.isEmpty())
  }

  /** No schema is ordinary in a hand-written specification, so the shape has to answer instead. */
  @Test
  fun `without a schema the shape decides`() {
    val liteByShape = """{"data": {"values": []}, "mark": "bar", "encoding": {}}"""
    assertTrue(VegaLiteInput.toVega(liteByShape).wasVegaLite)

    val vegaByShape = """{"data": [], "marks": [{"type": "rect"}]}"""
    assertFalse(VegaLiteInput.toVega(vegaByShape).wasVegaLite)
  }

  /**
   * Text that is not JSON at all passes through unchanged, so the Vega parser produces the one
   * diagnostic a reader needs rather than this stage producing a second saying the same thing.
   */
  @Test
  fun `text that is not JSON is left for the Vega parser to report`() {
    val converted = VegaLiteInput.toVega("not a specification")
    assertFalse(converted.wasVegaLite)
    assertEquals("not a specification", converted.vegaJson)
    assertTrue(converted.diagnostics.isEmpty())
  }

  /** A Vega-Lite specification the compiler cannot honour still reports, by name. */
  @Test
  fun `an unimplemented Vega-Lite construct is reported through the conversion`() {
    // Grids nest, and nest inside a concatenation; a **repetition inside a grid** does not, and is
    // reported by name rather than half-compiled.
    val nested =
      """
      {
        "${'$'}schema": "https://vega.github.io/schema/vega-lite/v6.json",
        "data": {"values": [{"a": 1, "b": 2}]},
        "facet": {"column": {"field": "a"}},
        "spec": {
          "repeat": {"column": ["a", "b"]},
          "spec": {
            "mark": "bar",
            "encoding": {"x": {"field": {"repeat": "column"}, "type": "quantitative"}}
          }
        }
      }
      """
        .trimIndent()
    val converted = VegaLiteInput.toVega(nested)
    assertTrue(converted.wasVegaLite)
    assertTrue(
      converted.diagnostics.any { it.code == VegaLiteDiagnostics.UNSUPPORTED_COMPOSITION },
      "a repetition inside a grid should be reported: ${converted.diagnostics}",
    )
  }
}
