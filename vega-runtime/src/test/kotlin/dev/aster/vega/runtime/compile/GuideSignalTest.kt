package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An **axis**'s string properties may be signals, or scaled, exactly as a title's may.
 *
 * ```js
 * addEncoders(encode, {
 *   angle: _('labelAngle'), fill: _('labelColor'), font: _('labelFont'), …
 * }, { align: labelAlign, baseline: labelBaseline });
 * ```
 *
 * `parseAxis` builds each part of an axis out of encoders, so every property takes a signal
 * wherever it takes a word — and `addEncode` treats **any object** as an encoder, so `{"scale":
 * "c", "signal": "'t'"}` is a scaled channel rather than a broken string. This engine read them as
 * strings: a lollipop chart asking for `labelAlign: {"signal": "'left'"}` had its labels aligned
 * the other way, which moved the measured axis extent, which moved the fitted plotting area, which
 * moved every mark in the chart; and a back-to-back bar chart's two headings, coloured through a
 * scale, both came out black.
 *
 * Every expectation was read off a live upstream view of this specification.
 */
class GuideSignalTest {

  private val spec =
    """
    {
      "width": 100, "height": 60, "padding": 5, "autosize": "none",
      "signals": [{"name": "side", "value": "left"}, {"name": "ink", "value": "#e66c37"}],
      "scales": [
        {"name": "y", "type": "band", "domain": ["a", "b"], "range": "height"},
        {"name": "c", "type": "ordinal", "domain": ["t"], "range": ["#118dff"]}
      ],
      "axes": [{"orient": "left", "scale": "y", "title": "heading",
                "labelAlign": {"signal": "side"},
                "labelColor": {"signal": "ink"},
                "titleColor": {"scale": "c", "signal": "'t'"},
                "labelFontStyle": {"signal": "'italic'"}}]
    }
    """
      .trimIndent()

  private fun parts(role: String): List<TextNode> =
    requireNotNull(SpecCompiler(VegaHeadlessTextEngine()).compileJson(spec).scene)
      .flatten()
      .map { it.node }
      .filterIsInstance<TextNode>()
      .filter { it.metadata.role == role }

  private fun described(node: TextNode): String {
    val run = node.layout.run
    val colour = (node.fill?.paint as? ScenePaint.Solid)?.color?.toCssHex()
    return "${node.text} align=${run.align} fill=$colour style=${run.style.fontStyle}"
  }

  /** A label's alignment, colour and slant, each arriving through a signal. */
  @Test
  fun `a label reads its align, colour and style from signals`() {
    assertEquals(
      listOf("a align=LEFT fill=#e66c37 style=ITALIC", "b align=LEFT fill=#e66c37 style=ITALIC"),
      parts("axis-label").map(::described),
    )
  }

  /** And a title coloured **through a scale**, which is an encoder and not a word at all. */
  @Test
  fun `a title takes a colour a scale produced`() {
    assertEquals(
      listOf("heading align=CENTER fill=#118dff style=NORMAL"),
      parts("axis-title").map(::described),
    )
  }
}
