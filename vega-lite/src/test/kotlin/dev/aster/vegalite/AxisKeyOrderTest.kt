package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An assembled axis writes **everything the chart stated, then everything derived**, each in
 * `AXIS_COMPONENT_PROPERTIES` order.
 *
 * Two rules compose to give that. `parseAxis` fills the component by walking the property list
 * rather than in whatever order its rules fire —
 *
 * ```js
 * for (const property of AXIS_COMPONENT_PROPERTIES) {
 *   const value = property in axisRules ? axisRules[property](ruleParams) : …;
 *   …
 *   if (hasValue && explicit) { axisComponent.set(property, value, explicit); } else { … }
 * }
 * ```
 *
 * — and `Split.combine` then puts the two halves in a stated order, with a comment saying so:
 * ```js
 * public combine(): Partial<T> {
 *   return {
 *     ...this.explicit, // Explicit properties comes first
 *     ...this.implicit,
 *   };
 * }
 * ```
 *
 * This compiler walked its own insertion order, which agrees wherever the two coincide and not
 * otherwise. The clearest case is an axis that states a `labelAngle`: the angle is written before
 * the `labelAlign` it *derives*, where upstream writes every stated property first and the derived
 * alignment much later, among the implicit ones.
 *
 * **This is checked here and not by a fixture on purpose.** `SpecDiff` ignores object key order, by
 * design and for good reason: two specifications differing only in key order are the same
 * specification to Vega. So the fixture gate, the scene comparison and the 21251-case schema sweep
 * all agree either way and none of them can hold this rule. `ScaleKeyOrderTest`,
 * `RoundedStackGroupOrderTest` and `JavaScriptKeyOrderTest` exist for the same reason.
 *
 * The expectation below is upstream's own output for the same specification, pasted whole rather
 * than summarised, so that a future reader can diff it against a fresh compile without trusting a
 * paraphrase.
 */
class AxisKeyOrderTest {

  private fun axisKeys(specification: String, predicate: (VegaValue.Obj) -> Boolean): List<String> {
    val compiled =
      VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(specification).toJson()) {
          "the specification did not compile"
        }
      ) as VegaValue.Obj
    val axes = compiled.fields["axes"] as VegaValue.Arr
    return axes.values.map { it as VegaValue.Obj }.first(predicate).fields.keys.toList()
  }

  /** The chart upstream was asked for, stating seven axis properties and deriving the rest. */
  private val stated =
    """
    {"data":{"values":[{"c":"a","v":1},{"c":"b","v":2}]},
     "mark":"point",
     "encoding":{"x":{"field":"c","type":"nominal",
                      "axis":{"labelAngle":45,"labelAlign":"left","title":"T","grid":true,
                              "tickCount":3,"format":"s","values":["a"],"zindex":1}},
                 "y":{"field":"v","type":"quantitative"}},
     "config":{"aria":false}}
    """

  @Test
  fun `a stated property is written before every derived one`() {
    val keys = axisKeys(stated) { it.fields["title"] != null }
    // Upstream's order for this axis, read off a compile of the same specification.
    val upstream =
      ("scale orient grid title format labelAlign labelAngle tickCount values aria bandPosition " +
          "description domain domainCap domainColor domainDash domainDashOffset domainOpacity " +
          "domainWidth formatType labelBaseline labelBound labelColor labelFlush labelFlushOffset " +
          "labelFont labelFontSize labelFontStyle labelFontWeight labelLimit labelLineHeight " +
          "labelOffset labelOpacity labelOverlap labelPadding labels labelSeparation maxExtent " +
          "minExtent offset position tickBand tickCap tickColor tickDash tickDashOffset tickExtra " +
          "tickMinStep tickOffset tickOpacity tickRound ticks tickSize tickWidth titleAlign " +
          "titleAnchor titleAngle titleBaseline titleColor titleFont titleFontSize titleFontStyle " +
          "titleFontWeight titleLimit titleLineHeight titleOpacity titlePadding titleX titleY " +
          "translate encode zindex")
        .split(" ")
    // Only the names this compiler writes: it does not emit every guide default upstream does, and
    // that is a different question from the order they come in. What it *does* write must appear in
    // upstream's relative order, with nothing out of place.
    assertEquals(upstream.filter { it in keys }, keys)
  }

  /**
   * The stated `labelAngle` ahead of the derived `labelAlign`, named on its own.
   *
   * This is the pair the rule turns on and the one that was wrong: an angle the chart wrote and an
   * alignment the angle implies. Asserting it by name says what broke, where the whole-list check
   * above only says that something did.
   */
  @Test
  fun `a stated angle precedes the alignment it derives`() {
    val keys = axisKeys(stated) { it.fields["title"] != null }
    assertEquals(true, keys.indexOf("labelAngle") < keys.indexOf("labelBaseline"))
    assertEquals(true, keys.indexOf("labelAlign") < keys.indexOf("aria"))
  }
}
