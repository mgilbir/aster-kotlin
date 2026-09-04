package dev.aster.vega.runtime

import dev.aster.vega.runtime.compile.SpecCompiler
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What sits outside the implemented subset is **reported by name**, never approximated.
 *
 * Several rows of `SUPPORTED_FEATURES.md` say "Partial" and then describe a subset. That word is
 * only safe because of ADR 0011: nothing a specification asks for is dropped without saying so. A
 * partial engine that reported nothing would draw a plausible chart that is not the one the
 * specification describes, and a reader would have no way to know.
 *
 * So the rows' shared contract is what is pinned, one surface at a time. Each uses a name upstream
 * has never had rather than a real construct that happens to be missing — the same reason
 * `TransformReferenceTest` had to stop borrowing `wordcloud` once every transform was implemented:
 * a test about "outside the subset" cannot depend on the subset having a hole in it.
 */
class SubsetIsReportedTest {

  private fun diagnostics(json: String) = SpecCompiler().compileJson(json).diagnostics

  private fun reports(json: String, needle: String): Boolean =
    diagnostics(json).any { needle in it.message }

  @Test
  fun `a mark type outside the subset is reported by name`() {
    val json =
      """
      {"width": 80, "height": 60, "padding": 0,
       "data": [{"name": "t", "values": [{"v": 1}]}],
       "marks": [{"type": "aMarkTypeUpstreamHasNeverHad", "from": {"data": "t"},
                  "encode": {"enter": {}}}]}
      """
        .trimIndent()
    assertTrue(reports(json, "aMarkTypeUpstreamHasNeverHad"), "an unknown mark drew in silence")
  }

  @Test
  fun `a scale type outside the subset is reported by name`() {
    val json =
      """
      {"width": 80, "height": 60, "padding": 0,
       "data": [{"name": "t", "values": [{"v": 1}]}],
       "scales": [{"name": "s", "type": "aScaleTypeUpstreamHasNeverHad",
                   "domain": [0, 1], "range": "width"}],
       "marks": []}
      """
        .trimIndent()
    assertTrue(reports(json, "aScaleTypeUpstreamHasNeverHad"), "an unknown scale drew in silence")
  }

  /**
   * A `config` block outside the subset is reported, which is what the `config` row rests on.
   *
   * The named exceptions that row used to carry — `config.range`, `config.group` and
   * `config.projection` — are all honoured now, so the contract is the only thing left holding it
   * up: a theme setting something this engine does not implement is told, rather than quietly
   * getting the engine's own defaults.
   */
  @Test
  fun `a config block outside the subset is reported by name`() {
    val json =
      """
      {"width": 80, "height": 60, "padding": 0,
       "config": {"aBlockNobodyHasHeardOf": {"x": 1}},
       "data": [{"name": "t", "values": [{"v": 1}]}],
       "marks": [{"type": "rect", "from": {"data": "t"},
                  "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                       "width": {"value": 5}, "height": {"value": 5}}}}]}
      """
        .trimIndent()
    assertTrue(
      reports(json, "aBlockNobodyHasHeardOf"),
      "an unimplemented config block was ignored in silence, so a theme could set it and never know",
    )
  }

  /**
   * A guide `encode` channel that is not a constant is reported by name.
   *
   * This is the whole of what is left of that row's limitation. A **signal** is honoured now — a
   * grid's `strokeWidth` bound to a signal draws at the signal's value — so the constants-only
   * claim was too broad. A `field` or a conditional still needs the guide's own datum, which does
   * not exist until the guide is laid out, and each is reported.
   */
  @Test
  fun `a guide encode channel the builders cannot resolve is reported by name`() {
    fun axis(encode: String) =
      """
      {"width": 80, "height": 60, "padding": 0,
       "data": [{"name": "t", "values": [{"c": "a"}]}],
       "scales": [{"name": "x", "type": "band",
                   "domain": {"data": "t", "field": "c"}, "range": "width"}],
       "axes": [{"scale": "x", "orient": "bottom", "grid": true, "encode": $encode}],
       "marks": []}
      """
        .trimIndent()

    // A channel **no** builder reads per item. `labels.angle` is folded into `labelAngle` or it is
    // nothing: there is no per-tick path for it, so a conditional really is dropped and really is
    // reported. This used to be asserted of `grid.stroke`, which was wrong — `strokeFor` had been
    // resolving that per tick since it was written, and the warning told a reader their chart would
    // not work when it already did.
    assertTrue(
      reports(
        axis(
          """{"labels": {"update": {"angle": [{"test": "true", "value": 45}, {"value": 0}]}}}"""
        ),
        "'angle'",
      ),
      "a conditional on a channel with no per-item path was dropped in silence",
    )
    assertTrue(
      reports(axis("""{"labels": {"update": {"angle": {"field": "value"}}}}"""), "'angle'"),
      "a field-valued channel with no per-item path was dropped in silence",
    )
  }

  /**
   * And the channels the builders **do** resolve per item are not reported, because they work.
   *
   * The other half, and the one this class had backwards. `guide-encode-datum` proves each of them
   * against upstream — a label coloured from its tick's value, a gridline thickened at one tick, a
   * tick capped differently at the last — so a warning about them would be false. Kept beside the
   * assertion above so the two cannot drift into agreeing.
   */
  @Test
  fun `a guide encode channel the builders resolve per item is not reported`() {
    fun axis(encode: String) =
      """
      {"width": 80, "height": 60, "padding": 0,
       "data": [{"name": "t", "values": [{"c": "a"}]}],
       "scales": [{"name": "x", "type": "band",
                   "domain": {"data": "t", "field": "c"}, "range": "width"}],
       "axes": [{"scale": "x", "orient": "bottom", "grid": true, "encode": $encode}],
       "marks": []}
      """
        .trimIndent()

    for ((part, channel) in
      listOf(
        "labels" to "fill",
        "labels" to "fontWeight",
        "grid" to "stroke",
        "grid" to "strokeWidth",
        "ticks" to "strokeWidth",
      )) {
      val conditional =
        axis(
          """{"$part": {"update": {"$channel": [{"test": "datum.value > 0", "value": 2}, {"value": 1}]}}}"""
        )
      assertFalse(
        reports(conditional, "'$channel'"),
        "$part.$channel is resolved per item and was reported as ignored anyway",
      )
    }
  }

  /**
   * And a **signal** in a guide encode is *not* reported, because it is honoured.
   *
   * The other half, and the one that keeps the assertions above from reading as "guide encodes do
   * not work". Without it the row would go on claiming constants-only long after that stopped being
   * true — which is exactly what it was doing.
   */
  @Test
  fun `a signal in a guide encode is honoured rather than reported`() {
    val json =
      """
      {"width": 80, "height": 60, "padding": 0,
       "data": [{"name": "t", "values": [{"c": "a"}]}],
       "signals": [{"name": "w", "value": 7}],
       "scales": [{"name": "x", "type": "band",
                   "domain": {"data": "t", "field": "c"}, "range": "width"}],
       "axes": [{"scale": "x", "orient": "bottom", "grid": true,
                 "encode": {"grid": {"update": {"strokeWidth": {"signal": "w"}}}}}],
       "marks": []}
      """
        .trimIndent()
    assertTrue(
      diagnostics(json).none { "strokeWidth" in it.message },
      "a signal-valued guide encode was reported as unimplemented: " +
        diagnostics(json).map { it.message },
    )
    val widths = mutableListOf<Double>()
    fun walk(node: dev.aster.vega.scene.SceneNode, role: String?) {
      val here = node.metadata.role ?: role
      if (here == "axis-grid" && node is dev.aster.vega.scene.RuleNode) widths += node.stroke.width
      if (node is dev.aster.vega.scene.GroupNode) node.children.forEach { walk(it, here) }
    }
    SpecCompiler().compileJson(json).scene?.root?.let { walk(it, null) }
    assertTrue(widths.isNotEmpty() && widths.all { it == 7.0 }, "the grid was drawn at $widths")
  }
}
