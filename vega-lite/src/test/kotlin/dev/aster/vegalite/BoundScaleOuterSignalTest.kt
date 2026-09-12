package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A view inside a **nested** layer pushes its bound-scale state outward, as a plot of a
 * concatenation does.
 *
 * ```js
 * // Nested signals need only push to top-level signals with multiview displays.
 * if (model.parent && !isTopLevelLayer(model)) {
 *   for (const proj of selCmpt.scales) {
 *     const signal = signals.find((s) => s.name === proj.signals.data);
 *     signal.push = 'outer';
 *     …
 *   }
 * }
 * ```
 *
 * with
 *
 * ```js
 * function isTopLevelLayer(model: Model): boolean {
 *   return model.parent && isLayerModel(model.parent) && (!model.parent.parent || isTopLevelLayer(model.parent.parent));
 * }
 * ```
 *
 * `vlSelectionResolve` knows nothing about bound scales, so in a chart of several views the state
 * is reassembled from what each view pushes out into an empty signal declared above it. A view
 * drawn by itself has nothing above it, and a member of a **single** layer at the root is drawn in
 * the chart's own group — neither pushes. Everything else does, and a *nested* layer is what a
 * layer of layers and a composite mark both are.
 *
 * This engine only did it for a concatenation and a trellis, so a nested layer whose members pan
 * and zoom their own axes was missing both halves: two specifications in the wild corpus differ by
 * exactly those eight signals.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class BoundScaleOuterSignalTest {

  private class Signals(val top: List<String>, val groups: List<String>)

  private fun signals(spec: String): Signals {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun render(signal: VegaValue.Obj) = buildString {
      append(signal.string("name"))
      if (signal.fields.containsKey("push")) append("(push)")
      if (signal.fields.containsKey("on")) append("[on]")
    }
    return Signals(
      top =
        (compiled.fields["signals"] as? VegaValue.Arr)
          ?.values
          .orEmpty()
          .mapNotNull { it as? VegaValue.Obj }
          .filter { (it.string("name") ?: "").startsWith("s_a") }
          .map(::render),
      groups =
        (compiled.fields["marks"] as? VegaValue.Arr)
          ?.values
          .orEmpty()
          .mapNotNull { it as? VegaValue.Obj }
          .flatMap { group ->
            (group.fields["signals"] as? VegaValue.Arr)?.values.orEmpty().mapNotNull {
              it as? VegaValue.Obj
            }
          }
          .filter { (it.string("name") ?: "").startsWith("s_a") }
          .map(::render),
    )
  }

  private val rows = """"data":{"values":[{"a":1,"b":2}]}"""
  private val position =
    """"encoding":{"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"quantitative"}}"""
  private val bound =
    """"params":[{"name":"s","select":{"type":"interval","encodings":["x"]},"bind":"scales"}]"""

  /** A view drawn by itself has nothing above it to push into. */
  @Test
  fun `a single view pushes nothing`() {
    val signals = signals("""{$rows,$bound,"mark":"point",$position}""")
    assertEquals(listOf("s_a[on]"), signals.top)
    assertEquals(emptyList<String>(), signals.groups)
  }

  /** Nor does a member of a single layer at the root, drawn in the chart's own group. */
  @Test
  fun `a member of the chart's own layer pushes nothing`() {
    val signals =
      signals("""{$rows,"layer":[{"mark":"point",$bound,$position},{"mark":"line",$position}]}""")
    assertEquals(listOf("s_a[on]"), signals.top)
  }

  /** The reported shape: a layer inside a layer, which pushes and is pushed into. */
  @Test
  fun `a member of a nested layer pushes outward`() {
    val signals =
      signals(
        """
        {$rows,"layer":[{"layer":[{"mark":"point",$bound,$position},
                                  {"mark":"line",$position}]}]}
        """
      )
    assertEquals(
      listOf("s_a", "s_a(push)[on]"),
      signals.top,
      "the empty declaration above, and the view's own pushing into it",
    )
  }

  /** A plot of a concatenation does it too, and its own signal sits inside its group. */
  @Test
  fun `a plot of a concatenation pushes outward`() {
    val signals =
      signals("""{$rows,"concat":[{"mark":"point",$bound,$position},{"mark":"line",$position}]}""")
    assertEquals(listOf("s_a"), signals.top)
    assertEquals(listOf("s_a(push)[on]"), signals.groups)
  }
}
