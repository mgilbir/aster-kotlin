package dev.aster.vega.compose.mp

import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vegalite.VegaLiteInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Vega-Lite through the Compose renderer, on every target this module claims.
 *
 * A reader who pastes a chart has pasted a chart, not a dialect, so a host that accepts text has to
 * accept **either grammar**. This test exists because one host could and the others could not:
 * `:vega-lite` was declared a JVM module, so a specification pasted into the Android demo drew and
 * the same text on iOS could not be read at all. Nothing in the compiler touches the JVM — it emits
 * a specification, it does not execute one — so that was a build accident and not a host
 * restriction, which is the only reason a difference between hosts is allowed to stand.
 *
 * It lives in `commonTest` deliberately, and that placement is the assertion: the file is
 * **compiled** for Android, both iOS targets and the JVM, so the compiler being unreachable from a
 * Kotlin/Native target fails here rather than in somebody's app. It is *run* on the JVM by
 * `scripts/check.sh`, which is the same gap `check.sh` already records for the core's native
 * suites.
 */
class VegaLiteTest {

  /** A bar chart in Vega-Lite: the shortest thing that is unmistakably not Vega. */
  private val vegaLite =
    """
    {"${'$'}schema": "https://vega.github.io/schema/vega-lite/v6.json",
     "width": 120, "height": 60,
     "data": {"values": [{"c": "a", "v": 30}, {"c": "b", "v": 80}]},
     "mark": "bar",
     "encoding": {
       "x": {"field": "c", "type": "nominal"},
       "y": {"field": "v", "type": "quantitative"}}}
    """
      .trimIndent()

  private fun record(json: String): List<String> {
    val compiled =
      SpecCompiler(textEngine = MetricTextEngine(), loader = DenyLoader).compileJson(json)
    val complaints =
      compiled.diagnostics.filter {
        it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.FATAL
      }
    assertTrue(complaints.isEmpty(), "compiled with errors: ${complaints.map { it.message }}")
    val target = RecordingTarget()
    SceneWalk().draw(requireNotNull(compiled.scene) { "no scene" }, target)
    return target.calls
  }

  @Test
  fun `a Vega-Lite specification is recognised and compiled`() {
    val converted = VegaLiteInput.toVega(vegaLite)
    assertTrue(converted.wasVegaLite, "the schema says Vega-Lite, so it should be read as such")
    val vega = assertNotNull(converted.vegaJson, "compilation produced nothing")
    assertTrue(vega.contains("\"marks\""), "what comes back is Vega, which has `marks`")
    assertFalse(vega.contains("\"encoding\""), "and not Vega-Lite, which has `encoding`")
  }

  @Test
  fun `a Vega-Lite specification draws through the Compose renderer`() {
    val vega = assertNotNull(VegaLiteInput.toVega(vegaLite).vegaJson)
    val calls = record(vega)
    val drawn = calls.joinToString("\n")
    // The **bars**, by the colour Vega-Lite paints them and by their heights: the two values are 30
    // and 80 against a domain reaching 80 over 60 units, so the bars are 22.5 and 60 tall.
    // Asserting
    // the geometry rather than a count is the difference between "something was drawn" and "the
    // chart the specification asked for was drawn" — and the encoding is the whole of what
    // Vega-Lite contributes here, so it is the thing worth checking.
    val bars = calls.map { it.trim() }.filter { it.startsWith("rect") && it.contains("#4c78a8") }
    assertEquals(2, bars.size, "one bar per row:\n$drawn")
    assertTrue(bars.any { it.contains("54x22.5") }, "the 30 bar:\n$drawn")
    assertTrue(bars.any { it.contains("54x60") }, "the 80 bar:\n$drawn")
    // And the axes around them, whose labels come from the fields the encoding named.
    val labels = calls.map { it.trim() }.filter { it.startsWith("text") }
    assertTrue(
      labels.any { it.contains("\"a\"") } && labels.any { it.contains("\"b\"") },
      "the category labels:\n$drawn",
    )
    assertTrue(labels.any { it.contains("\"c\"") }, "and the axis title:\n$drawn")
  }

  @Test
  fun `either grammar ends at the same drawing`() {
    val viaVegaLite = assertNotNull(VegaLiteInput.toVega(vegaLite).vegaJson)
    // The same chart handed over as Vega. A host may be given either and neither is a special case.
    val asVega = VegaLiteInput.toVega(viaVegaLite)
    assertFalse(asVega.wasVegaLite, "Vega in, Vega out, and no compilation attempted")
    assertEquals(viaVegaLite, asVega.vegaJson, "and unchanged")
    assertEquals(record(viaVegaLite), record(assertNotNull(asVega.vegaJson)))
  }

  @Test
  fun `Vega-Lite this compiler cannot honour is reported rather than drawn`() {
    val converted =
      VegaLiteInput.toVega(
        """
        {"${'$'}schema": "https://vega.github.io/schema/vega-lite/v6.json",
         "data": {"values": [{"a": 1}]},
         "layer": [{"hconcat": [{"mark": "bar",
           "encoding": {"x": {"field": "a", "type": "quantitative"}}}]}]}
        """
          .trimIndent()
      )
    assertTrue(converted.wasVegaLite, "it was read as Vega-Lite")
    assertEquals(null, converted.vegaJson, "and produced nothing, not a chart nobody asked for")
    assertTrue(converted.diagnostics.isNotEmpty(), "a host has to be able to say why")
  }
}
