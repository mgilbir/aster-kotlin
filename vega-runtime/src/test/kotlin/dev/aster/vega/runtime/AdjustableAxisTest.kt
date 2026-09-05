@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.runtime

import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.locale.VegaCaptions
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.runtime.scale.InvertibleScale
import dev.aster.vega.runtime.scale.PositionScale
import dev.aster.vega.scene.AccessibilityTree
import dev.aster.vega.scene.ChartActionKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A reader can adjust the interval an axis draws its data against, which they could not.
 *
 * The row read: the accessibility actions move the **viewport** — the same visual transform a pinch
 * applies — and leave every scale exactly as the specification built it. So a reader exploring a
 * crowded region got bigger pixels and the same labels, because a zoom does not change what the
 * axis says.
 *
 * **An adjustable element, not a pair of actions.** Both platforms have a primitive for "a thing
 * whose value you change in place" — `UIAccessibilityTraitAdjustable` with its increment and
 * decrement, `AccessibilityNodeInfo`'s scroll actions — and an axis is where it belongs. Offering
 * `Narrow x` and `Widen x` as chart actions instead would put one pair per axis in a flat list that
 * a reader rotors through on every chart: eight entries on a two-axis chart, against three today.
 * Only the way *back* is a chart action, because a reader who has adjusted two axes is not standing
 * on either of them any more.
 *
 * The interval reaches the scale through **`domainRaw`**, which is upstream's own door for a
 * control choosing an exact interval: it short-circuits `zero`, `nice` and the three `domain*`
 * limits, so what the reader picked is what the scale gets and nothing rounds it outwards. A
 * specification's own `domainRaw` still wins — a chart whose detail panel is driven by a brush has
 * already said what decides that scale.
 */
class AdjustableAxisTest {

  private val controller = VegaChartController()

  private val twoAxes =
    """
    {"width": 200, "height": 100, "padding": 0, "autosize": "none",
     "data": [{"name": "t", "values": [{"c": "a", "v": 10}, {"c": "b", "v": 90}]}],
     "scales": [
       {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
       {"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"}, "range": "height"}
     ],
     "axes": [{"orient": "bottom", "scale": "x"}, {"orient": "left", "scale": "y"}],
     "marks": [{"type": "rect", "from": {"data": "t"},
                "encode": {"enter": {"x": {"scale": "x", "field": "c"},
                                     "width": {"scale": "x", "band": 1},
                                     "y": {"scale": "y", "field": "v"}, "y2": {"value": 0}}}}]}
    """
      .trimIndent()

  private fun elements() = AccessibilityTree.elements(controller.state.value.snapshot.scene)

  /** What the range endpoints invert to: the interval the scale is currently drawing. */
  private fun domain(scale: String): Pair<Double, Double>? {
    val built = controller.lastCompiled!!.scales[scale] ?: return null
    if (built !is InvertibleScale || built !is PositionScale) return null
    return built.invert(built.range.first()) to built.invert(built.range.last())
  }

  private fun width(scale: String) = domain(scale)!!.let { kotlin.math.abs(it.second - it.first) }

  private fun tickLabels(): List<String> {
    val out = mutableListOf<String>()
    fun walk(node: dev.aster.vega.scene.SceneNode) {
      if (node is dev.aster.vega.scene.TextNode) out += node.text
      if (node is dev.aster.vega.scene.GroupNode) node.children.forEach { walk(it) }
    }
    walk(controller.state.value.snapshot.scene.root)
    return out
  }

  /** A continuous axis says which scale it lets a reader adjust; a band axis says nothing. */
  @Test
  fun `only a continuous axis is adjustable`() {
    controller.setSpec(twoAxes)
    val adjustable = elements().mapNotNull { it.adjustableScale }
    assertEquals(
      listOf("y"),
      adjustable,
      "the wrong axes are adjustable: a band axis frames a list of values, with nothing between " +
        "them to narrow towards",
    )
  }

  /** Narrowing shrinks the interval the data is drawn against. */
  @Test
  fun `narrowing an axis shrinks its domain`() {
    controller.setSpec(twoAxes)
    val before = width("y")
    assertTrue(controller.adjustScaleDomain("y", narrow = true), "the adjustment did nothing")
    assertTrue(
      width("y") < before,
      "the domain did not shrink: ${width("y")} against $before",
    )
  }

  /** And widening grows it, so a reader can get back out the way they came. */
  @Test
  fun `widening an axis grows its domain`() {
    controller.setSpec(twoAxes)
    controller.adjustScaleDomain("y", narrow = true)
    val narrowed = width("y")
    assertTrue(controller.adjustScaleDomain("y", narrow = false), "widening did nothing")
    assertTrue(width("y") > narrowed, "the domain did not grow back")
  }

  /**
   * The **axis labels change**, which is the whole difference from a zoom.
   *
   * A viewport zoom magnifies the drawing: the ticks a reader hears are the ones the specification
   * computed, however far in they have gone. This recomputes them, so a reader exploring a crowded
   * region is told what is actually there.
   */
  @Test
  fun `the axis labels are recomputed, which a zoom never does`() {
    controller.setSpec(twoAxes)
    val atRest = tickLabels()
    repeat(4) { controller.adjustScaleDomain("y", narrow = true) }
    assertNotEquals(atRest, tickLabels(), "the ticks did not change, so this is only a zoom")

    val zoomed = VegaChartController()
    zoomed.setSpec(twoAxes)
    repeat(4) { zoomed.perform(ChartActionKind.ZOOM_IN) }
    val zoomedLabels = mutableListOf<String>()
    fun walk(node: dev.aster.vega.scene.SceneNode) {
      if (node is dev.aster.vega.scene.TextNode) zoomedLabels += node.text
      if (node is dev.aster.vega.scene.GroupNode) node.children.forEach { walk(it) }
    }
    walk(zoomed.state.value.snapshot.scene.root)
    assertEquals(
      atRest,
      zoomedLabels,
      "zooming changed the axis labels, so the two features are no longer distinguishable",
    )
  }

  /**
   * A **log** axis steps geometrically and never leaves the scale, which is why the step is taken
   * in range space and inverted rather than interpolated between the domain's ends.
   *
   * Narrowing a log domain about its arithmetic midpoint is not a log step, and widening one that
   * way walks the low end towards zero and off the scale entirely. Moving the *positions* and
   * asking the scale what they mean cannot produce a domain the scale could not have had.
   */
  @Test
  fun `a log axis keeps a positive domain however far it is widened`() {
    controller.setSpec(
      """
      {"width": 200, "height": 100, "padding": 0, "autosize": "none",
       "data": [{"name": "t", "values": [{"v": 1}, {"v": 1000}]}],
       "scales": [{"name": "y", "type": "log", "domain": {"data": "t", "field": "v"},
                   "range": "height"}],
       "axes": [{"orient": "left", "scale": "y"}],
       "marks": [{"type": "rect", "from": {"data": "t"},
                  "encode": {"enter": {"x": {"value": 0}, "width": {"value": 5},
                                       "y": {"scale": "y", "field": "v"},
                                       "y2": {"value": 0}}}}]}
      """
        .trimIndent()
    )
    repeat(12) { controller.adjustScaleDomain("y", narrow = false) }
    val (low, high) = domain("y")!!
    assertTrue(
      low > 0.0 && high > 0.0,
      "a widened log domain reached zero or below — $low to $high — which is off the scale",
    )
    assertTrue(low.isFinite() && high.isFinite(), "a widened log domain stopped being a number")
  }

  /**
   * The adjustment stops at a limit rather than running forever, and says so by returning false.
   *
   * The same contract the actions use: `false` means nothing happened, so a host announces nothing.
   * An adjustable element that reports a change at its limit tells a reader they are still moving.
   */
  @Test
  fun `an axis stops at the end of its range`() {
    controller.setSpec(twoAxes)
    var steps = 0
    while (controller.adjustScaleDomain("y", narrow = true)) {
      steps++
      assertTrue(steps < 100, "narrowing never stopped")
    }
    assertTrue(steps > 5, "narrowing stopped after only $steps steps")
    assertTrue(
      controller.adjustScaleDomain("y", narrow = false),
      "there is no way back from the limit",
    )
  }

  /** A scale that is not there, or not adjustable, is refused rather than guessed at. */
  @Test
  fun `an unknown or discrete scale is refused`() {
    controller.setSpec(twoAxes)
    assertFalse(
      controller.adjustScaleDomain("nope", narrow = true),
      "an unknown scale was adjusted",
    )
    assertFalse(controller.adjustScaleDomain("x", narrow = true), "a band scale was adjusted")
  }

  /** The way back is a chart action, and it is offered only once there is something to undo. */
  @Test
  fun `the reset action appears only after an axis has been adjusted`() {
    controller.setSpec(twoAxes)
    assertFalse(
      ChartActionKind.RESET_DOMAINS in controller.accessibilityActions.map { it.kind },
      "a chart nobody has adjusted offers a reset that would do nothing",
    )
    val before = domain("y")
    controller.adjustScaleDomain("y", narrow = true)
    assertTrue(
      ChartActionKind.RESET_DOMAINS in controller.accessibilityActions.map { it.kind },
      "an adjusted chart offers no way back",
    )
    assertTrue(controller.perform(ChartActionKind.RESET_DOMAINS), "the reset did nothing")
    assertEquals(before, domain("y"), "the reset did not put the domain back")
    assertFalse(
      ChartActionKind.RESET_DOMAINS in controller.accessibilityActions.map { it.kind },
      "a chart back at rest still offers a reset",
    )
  }

  /**
   * Resetting the axes and resetting the zoom are **separate**, so neither undoes the other's work.
   *
   * A reader who has zoomed and then adjusted an axis has done two things, and a single reset would
   * take away one they did not ask to lose.
   */
  @Test
  fun `resetting the axes leaves the zoom alone, and the other way round`() {
    controller.setSpec(twoAxes)
    controller.perform(ChartActionKind.ZOOM_IN)
    controller.adjustScaleDomain("y", narrow = true)
    val narrowed = domain("y")

    assertTrue(controller.perform(ChartActionKind.RESET_ZOOM))
    assertEquals(narrowed, domain("y"), "resetting the zoom also put the axis back")

    controller.perform(ChartActionKind.ZOOM_IN)
    assertTrue(controller.perform(ChartActionKind.RESET_DOMAINS))
    assertTrue(
      controller.state.value.snapshot.interactionState.viewportScale > 1.0,
      "resetting the axes also undid the zoom",
    )
  }

  /**
   * A specification's own `domainRaw` **wins**, because the document has said what decides it.
   *
   * A chart whose detail panel is driven by a brush already answers this question, and a reader's
   * adjustment quietly taking it over would break the chart's own interaction.
   */
  @Test
  fun `a specification's own domainRaw is not taken over`() {
    controller.setSpec(
      """
      {"width": 200, "height": 100, "padding": 0, "autosize": "none",
       "data": [{"name": "t", "values": [{"v": 10}, {"v": 90}]}],
       "signals": [{"name": "pinned", "value": [20, 40]}],
       "scales": [{"name": "y", "type": "linear", "domain": {"data": "t", "field": "v"},
                   "domainRaw": {"signal": "pinned"}, "range": "height"}],
       "axes": [{"orient": "left", "scale": "y"}],
       "marks": [{"type": "rect", "from": {"data": "t"},
                  "encode": {"enter": {"x": {"value": 0}, "width": {"value": 5},
                                       "y": {"scale": "y", "field": "v"},
                                       "y2": {"value": 0}}}}]}
      """
        .trimIndent()
    )
    assertEquals(20.0 to 40.0, domain("y"), "the specification's raw domain did not apply")
    controller.adjustScaleDomain("y", narrow = true)
    assertEquals(
      20.0 to 40.0,
      domain("y"),
      "a reader's adjustment took over a domain the specification had already decided",
    )
  }

  /** The reset action's label comes from the chart's locale, like every other action's. */
  @Test
  fun `the reset label is the locale's`() {
    val dutch =
      object : VegaCaptions by VegaCaptions.English {
        override fun resetAxesAction(): String = "Assen herstellen"
      }
    val localised = VegaChartController(locale = VegaLocale.EnglishUS.copy(captions = dutch))
    localised.setSpec(twoAxes)
    localised.adjustScaleDomain("y", narrow = true)
    assertEquals(
      "Assen herstellen",
      localised.accessibilityActions.first { it.kind == ChartActionKind.RESET_DOMAINS }.label,
    )
  }

  /** An axis the specification hid from a screen reader offers nothing, adjustable or not. */
  @Test
  fun `an axis hidden from a screen reader is not adjustable`() {
    controller.setSpec(
      twoAxes.replace(
        """"orient": "left", "scale": "y"""",
        """"orient": "left", "scale": "y", "aria": false""",
      )
    )
    assertNull(
      elements().firstOrNull { it.adjustableScale != null },
      "an axis removed from the accessibility tree still offered itself for adjustment",
    )
  }
}
