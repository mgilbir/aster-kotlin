package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the Vega-Lite compiler does not implement is **refused by name**, never approximated.
 *
 * Several Vega-Lite rows in `SUPPORTED_FEATURES.md` say `Partial` and then list a subset. As on the
 * Vega side, that word is only safe because of ADR 0011 — nothing a specification asks for is
 * dropped without saying so — and a compiler that approximated silently would emit a chart that is
 * not the one the specification describes.
 *
 * So the shared contract is what is pinned. Invented names are used where possible, for the same
 * reason the Vega-side test does: a test about "outside the subset" must not depend on the subset
 * having a particular hole in it today.
 */
class SubsetIsRefusedTest {

  private fun compile(json: String): Pair<VegaValue?, List<String>> {
    val result = VegaLiteCompiler().compileJson(json)
    return result.vega to result.diagnostics.map { it.message }
  }

  private fun refuses(json: String, needle: String): Boolean =
    compile(json).second.any { needle in it }

  private fun view(body: String) =
    """
    {"data": {"values": [{"a": 1, "b": 2}]},
     "mark": "point",
     $body}
    """
      .trimIndent()

  @Test
  fun `a transform outside the subset is refused by name`() {
    val json =
      view(
        """"transform": [{"aTransformUpstreamHasNeverHad": {"field": "a"}}],
              "encoding": {"x": {"field": "a", "type": "quantitative"}}"""
      )
    assertTrue(
      refuses(json, "aTransformUpstreamHasNeverHad") || compile(json).second.isNotEmpty(),
      "an unknown Vega-Lite transform compiled in silence: ${compile(json).second}",
    )
  }

  @Test
  fun `a mark outside the subset is refused by name`() {
    val json =
      """
      {"data": {"values": [{"a": 1}]},
       "mark": "aMarkUpstreamHasNeverHad",
       "encoding": {"x": {"field": "a", "type": "quantitative"}}}
      """
        .trimIndent()
    assertTrue(
      refuses(json, "aMarkUpstreamHasNeverHad"),
      "an unknown Vega-Lite mark compiled in silence: ${compile(json).second}",
    )
  }

  /**
   * A condition naming a `param` compiles, which the `condition` row said it did not.
   *
   * The row read: "A condition naming a `param` is refused *by itself*, leaving the rest of the
   * definition standing." It is not refused. It becomes the production rule upstream emits, testing
   * the selection's own store — `!length(data("picked_store")) || vlSelectionIdTest("picked_store",
   * datum)` — with the unconditional arm last, which is the same shape the `test` form produces and
   * the same one the neighbouring `Selection parameters` row has called supported all along.
   *
   * Asserted on the emitted Vega rather than on a picture, because that is where a compiler's work
   * is: the row's claim was about what comes out of it.
   */
  @Test
  fun `a condition naming a param compiles to upstream's production rule`() {
    val json =
      """
      {"data": {"values": [{"a": 1, "b": 2}]},
       "params": [{"name": "picked", "select": "point"}],
       "mark": "point",
       "encoding": {
         "x": {"field": "a", "type": "quantitative"},
         "color": {"condition": {"param": "picked", "value": "red"}, "value": "steelblue"}}}
      """
        .trimIndent()
    val (spec, diagnostics) = compile(json)
    assertTrue(
      diagnostics.isEmpty(),
      "a param-valued condition was reported as something this compiler cannot do: $diagnostics",
    )
    val emitted = VegaJson.write(requireNotNull(spec))
    assertTrue(
      "vlSelectionIdTest" in emitted && "picked_store" in emitted,
      "the condition did not become a rule testing the selection's store",
    )
    // The unconditional arm survives as the last entry, which is what makes it a *production rule*
    // rather than a replacement: a mark the condition does not catch still takes the chart's own
    // colour rather than Vega's default.
    assertTrue("steelblue" in emitted, "the unconditional arm was dropped")
  }

  /**
   * A facet `sort` **object naming no `field`** is refused, and it is the only sort shape that is.
   *
   * `STATUS.md` claimed two others were refused with it — a written-out list, and an aggregate on a
   * facet gridded both ways — and both had been implemented since: `reportUnsupportedSort` returns
   * early for an array, whose place is computed onto every row as a column of its own. The prose
   * said "refuses" while the code said otherwise, in the direction that costs a reader most (#231).
   *
   * So the claim lives here now, where the capability row can cite it and it cannot drift.
   */
  @Test
  fun `a facet sort naming no field to aggregate is refused by name`() {
    val json =
      """
      {"data": {"values": [{"a": 1, "b": 2, "g": "x"}]},
       "facet": {"column": {"field": "g", "sort": {"op": "sum"}}},
       "spec": {"mark": "point", "encoding": {"x": {"field": "a", "type": "quantitative"}}}}
      """
        .trimIndent()
    assertTrue(
      refuses(json, "names no `field` to aggregate"),
      "a facet sort with nothing to aggregate was accepted: ${compile(json).second}",
    )
  }

  /** And a **written-out list** is honoured, which the same prose said it was not. */
  @Test
  fun `a facet sort naming a written-out list is honoured`() {
    val json =
      """
      {"data": {"values": [{"a": 1, "g": "x"}, {"a": 2, "g": "y"}]},
       "facet": {"column": {"field": "g", "sort": ["y", "x"]}},
       "spec": {"mark": "point", "encoding": {"x": {"field": "a", "type": "quantitative"}}}}
      """
        .trimIndent()
    val (vega, diagnostics) = compile(json)
    assertTrue(vega != null, "a facet sorted by a stated list did not compile: $diagnostics")
    assertTrue(
      diagnostics.none { "is not implemented" in it },
      "a facet sorted by a stated list was reported as unimplemented: $diagnostics",
    )
  }
}
