package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.VegaValue
import dev.aster.vega.runtime.scale.LinearScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `autosize: "fit"` fits against the **`width` signal**, not the `width` property.
 *
 * ```js
 * view._resizeWidth = view.add(null, _ => { view._width = _.size; … }, {size: w});
 * ```
 * ```js
 * view.signal(Width, width, Skip); // set width, skip update calc
 * view._resizeWidth.skip(true);    // skip width resize handler
 * ```
 *
 * Upstream's view has no notion of a width *property*: `parseView` seeds a `width` **signal** from
 * it and the view then follows that signal, so `viewSizeLayout` fits against whatever the signal
 * currently says. A specification that declares the signal itself — `{"name": "width", "value":
 * 400}`, or an `update` expression — is therefore fitted exactly like one that writes `"width":
 * 400`, and the fitted number is written back to that same signal with its update skipped.
 *
 * This engine read the property. A chart that declared the signal instead had nothing for the fit
 * to bite on: the plotting area kept its full width and every scale range, axis and mark under it
 * was out by the width of the axes. That is how a chart sized by its host is written — the size
 * arrives at run time — and it is how all sixty-three Deneb templates are written.
 *
 * Every expectation here was rendered with upstream Vega.
 */
class AutosizeFitSignalTest {

  /**
   * A 400x200 chart with a titled axis on each side, which is what the references were read off.
   */
  private fun chart(autosize: String, size: String, padding: Int = 5) =
    """
    {
      "autosize": $autosize, "padding": $padding, $size
      "scales": [
        {"name": "x", "type": "linear", "domain": [0, 100], "range": "width"},
        {"name": "y", "type": "linear", "domain": [0, 10], "range": "height"}
      ],
      "axes": [
        {"orient": "left", "scale": "y", "title": "Y axis"},
        {"orient": "bottom", "scale": "x", "title": "X axis"}
      ]
    }
    """
      .trimIndent()

  /** The surface, and the two ranges the fitting decides. */
  private fun fitted(json: String): String {
    val compiled = SpecCompiler(VegaHeadlessTextEngine()).compileJson(json)
    val scene = requireNotNull(compiled.scene)
    val ranges =
      listOf("x", "y").joinToString(" ") { name ->
        "$name=${(compiled.scales[name] as LinearScale).range}"
      }
    return "${scene.width}x${scene.height} $ranges"
  }

  /** The declared size as a property, which is what already worked and must not move. */
  private val asProperty = """"width": 400, "height": 200,"""

  /** The same size as a signal, which is the form a chart sized by its host is written in. */
  private val asSignal =
    """"signals": [{"name": "width", "value": 400}, {"name": "height", "value": 200}],"""

  @Test
  fun `a size declared as a signal fits exactly as a property does`() {
    val expected = "410.0x210.0 x=[0.0, 350.0] y=[163.0, 0.0]"
    assertEquals(expected, fitted(chart(""""fit"""", asProperty)))
    assertEquals(expected, fitted(chart(""""fit"""", asSignal)))
  }

  /**
   * Including a signal whose value comes from an expression, which is the shape that makes the rule
   * clear: there is no property anywhere to read, and upstream fits all the same.
   */
  @Test
  fun `a size computed by an update expression is fitted too`() {
    assertEquals(
      "410.0x210.0 x=[0.0, 350.0] y=[163.0, 0.0]",
      fitted(
        chart(
          """"fit"""",
          """"signals": [{"name": "width", "update": "200 + 200"},
                         {"name": "height", "value": 200}],""",
        )
      ),
    )
  }

  /**
   * `contains: "padding"` comes off the fitted room rather than off the pass that gets measured:
   * `viewSizeLayout` subtracts it from `view._width` *after* a drawing has been made at the full
   * size. Property and signal agree, and both agree with upstream.
   */
  @Test
  fun `padding is taken out of the room left, whichever way the size was written`() {
    val expected = "400.0x200.0 x=[0.0, 310.0] y=[123.0, 0.0]"
    val contains = """{"type": "fit", "contains": "padding"}"""
    assertEquals(expected, fitted(chart(contains, asProperty, padding = 20)))
    assertEquals(expected, fitted(chart(contains, asSignal, padding = 20)))
  }

  /** `fit-x` fits the width and lets the height grow the way `pad` does. */
  @Test
  fun `fit-x fits a signal width and leaves the height alone`() {
    assertEquals(
      "410.0x247.0 x=[0.0, 350.0] y=[200.0, 0.0]",
      fitted(chart(""""fit-x"""", asSignal)),
    )
  }

  /** And `fit-y` the other way about. */
  @Test
  fun `fit-y fits a signal height and leaves the width alone`() {
    assertEquals(
      "460.0x210.0 x=[0.0, 400.0] y=[163.0, 0.0]",
      fitted(chart(""""fit-y"""", asSignal)),
    )
  }

  /**
   * What everything else reads is the **fitted** width, not the declared one.
   *
   * Upstream re-runs the dataflow after the resize, so a signal written as `width / 2` settles
   * against the fitted number — which is what puts a rule drawn at the middle of the chart in the
   * middle of it rather than half an axis to the right.
   */
  @Test
  fun `a signal reading the width sees the fitted one`() {
    val compiled =
      SpecCompiler(VegaHeadlessTextEngine())
        .compileJson(
          chart(
            """"fit"""",
            """"signals": [{"name": "width", "value": 400}, {"name": "height", "value": 200},
                           {"name": "mid", "update": "width / 2"}],""",
          )
        )
    assertEquals(175.0, (compiled.signals.values["mid"] as VegaValue.Num).value)
  }

  /**
   * The `width` signal a fitted chart publishes is the **fitted** width.
   *
   * Read off a live upstream view rather than a rendered scene, which is the only place it shows:
   * `view.signal('width')` answers 350 for a chart that declared 400, because `resizeView` wrote
   * it.
   */
  @Test
  fun `the width signal a fitted chart publishes is the fitted one`() {
    val compiled = SpecCompiler(VegaHeadlessTextEngine()).compileJson(chart(""""fit"""", asSignal))
    assertEquals(350.0, (compiled.signals.values["width"] as VegaValue.Num).value)
    assertEquals(163.0, (compiled.signals.values["height"] as VegaValue.Num).value)
  }

  /**
   * A size a **handler** set is fitted too, and the fitting has the last word.
   *
   * ```
   * view.signal('width', 500); await view.runAsync();
   * // width signal 450, x range [0, 450]
   * ```
   *
   * Upstream measures the drawing the handler's size produced and then overwrites that same signal,
   * so the order is: the handler decides how much room there is, the view decides how much of it
   * the plotting area gets. Which is why the engine's own answer is seeded after a handler's and
   * not before it.
   */
  @Test
  fun `a width a handler set is fitted, not taken as final`() {
    val compiled =
      SpecCompiler(VegaHeadlessTextEngine())
        .compileJson(chart(""""fit"""", asSignal), mapOf("width" to VegaValue.Num(500.0)))
    assertEquals(450.0, (compiled.scales["x"] as LinearScale).range[1])
    assertEquals(450.0, (compiled.signals.values["width"] as VegaValue.Num).value)
  }
}
