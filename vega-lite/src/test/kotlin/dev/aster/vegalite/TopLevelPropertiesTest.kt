package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the top level of a specification says, and what happens to the parts of it nobody reads.
 *
 * `reportUnsupportedTopLevel` was a `= Unit` stub while `Diagnostics.kt` promised that nothing is
 * silently ignored, so an unknown top-level property was dropped without a word — a typo, a
 * `selection` block from Vega-Lite 4, a property a later version adds. The chart drew confidently
 * having discarded part of what it was asked for, which is the one failure mode this engine is
 * supposed not to have.
 */
class TopLevelPropertiesTest {

  private fun compile(json: String): VegaLiteCompilation =
    VegaLiteCompiler().compile(VegaJson.parse(json))

  private val chart =
    """
    "data": {"values": [{"a": 1, "b": 2}]},
    "mark": "point",
    "encoding": {
      "x": {"field": "a", "type": "quantitative"},
      "y": {"field": "b", "type": "quantitative"}
    }
    """
      .trimIndent()

  @Test
  fun `a top-level property nothing reads is reported by name`() {
    val compiled = compile("""{"selection": {"brush": {"type": "interval"}}, $chart}""")

    val reported =
      compiled.diagnostics.filter { it.code == VegaLiteDiagnostics.UNSUPPORTED_TOP_LEVEL_PROPERTY }
    assertEquals(1, reported.size, "one property, one diagnostic: ${compiled.diagnostics}")
    assertTrue(reported.single().message.contains("`selection`"), reported.single().message)
    assertEquals("$.selection", reported.single().jsonPath)
    // Reported, not refused: the rest of the specification is still a chart.
    assertNotNull(
      compiled.toJson(),
      "the chart the rest of the specification describes still draws",
    )
  }

  @Test
  fun `every property the compiler honours passes without a word`() {
    val compiled =
      compile(
        """
        {
          "${'$'}schema": "https://vega.github.io/schema/vega-lite/v6.json",
          "name": "chart",
          "description": "a scatter plot",
          "background": "white",
          "padding": 5,
          "autosize": "pad",
          "width": 200,
          "height": 150,
          "view": {"stroke": null},
          "config": {"axis": {"grid": false}},
          "usermeta": {"host": "test"},
          "params": [{"name": "size", "value": 40}],
          "transform": [{"filter": "datum.a > 0"}],
          "datasets": {"aside": [{"a": 9}]},
          "resolve": {"scale": {"color": "independent"}},
          $chart
        }
        """
          .trimIndent()
      )

    assertEquals(
      emptyList<String>(),
      compiled.diagnostics
        .filter { it.code == VegaLiteDiagnostics.UNSUPPORTED_TOP_LEVEL_PROPERTY }
        .map { it.message },
    )
  }

  /**
   * `usermeta` is the one top-level property whose whole purpose is to survive compilation, and
   * upstream copies it onto the Vega it emits. Verified against the pinned `vega-lite@6.4.3` rather
   * than read off its documentation: `compile({usermeta: {a: 1}, …}).spec` carries `usermeta` last
   * and drops `name`, which is what this asserts.
   */
  @Test
  fun `usermeta is carried through to the Vega output and name is not`() {
    val compiled = compile("""{"name": "chart", "usermeta": {"host": "test"}, $chart}""")
    val vega = VegaJson.parse(compiled.toJson()!!) as VegaValue.Obj

    assertEquals(
      VegaValue.Obj(mapOf("host" to VegaValue.Str("test"))),
      vega.fields["usermeta"],
      "a host writes into `usermeta` to read it back off the output",
    )
    assertTrue("name" !in vega.fields, "upstream drops a chart's `name`; so does this")
  }
}
