package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A **legend**'s string properties may be signals too, part by part.
 *
 * ```js
 * addEncoders(encode, {
 *   fill: _('symbolFillColor', baseFill), shape: _('symbolType'),
 *   stroke: _('symbolStrokeColor', baseStroke), …
 * });
 * ```
 *
 * `legend-symbol-groups` builds the swatches and their labels out of encoders, `legend-title` the
 * heading — the same arrangement an axis has — so every one of these takes a signal wherever it
 * takes a word. Read as strings, a legend whose colours come from a theme's parameters was drawn in
 * the defaults, with nothing to say so.
 *
 * The swatch's own `fill` here comes from the **scale**, not from `symbolFillColor`: upstream
 * passes the scale's colour as the base and the property only stands in where there is none. That
 * is the arm most likely to be got wrong by a fold, so it is the first thing this pins.
 *
 * Every expectation was read off a live upstream view.
 */
class LegendSignalTest {

  private fun scene() =
    requireNotNull(
      SpecCompiler(VegaHeadlessTextEngine())
        .compileJson(
          """
          {
            "width": 120, "height": 60, "padding": 5, "autosize": "none",
            "signals": [{"name": "ink", "value": "#e66c37"}, {"name": "side", "value": "left"},
                        {"name": "sym", "value": "square"}],
            "scales": [{"name": "c", "type": "ordinal", "domain": ["a", "b"],
                        "range": ["#118dff", "#12239e"]}],
            "legends": [{"fill": "c", "title": "heading",
                         "labelColor": {"signal": "ink"}, "labelAlign": {"signal": "side"},
                         "titleColor": {"signal": "ink"}, "symbolType": {"signal": "sym"},
                         "symbolFillColor": {"signal": "'#00ff00'"}}]
          }
          """
            .trimIndent()
        )
        .scene
    )

  private fun texts(role: String) =
    scene()
      .flatten()
      .map { it.node }
      .filterIsInstance<TextNode>()
      .filter {
        it.metadata.role == role
      }

  @Test
  fun `a legend's labels take a colour and an alignment from signals`() {
    assertEquals(
      listOf("a align=LEFT fill=#e66c37", "b align=LEFT fill=#e66c37"),
      texts("legend-label").map {
        "${it.text} align=${it.layout.run.align} " +
          "fill=${(it.fill?.paint as? ScenePaint.Solid)?.color?.toCssHex()}"
      },
    )
  }

  @Test
  fun `a legend's title takes its colour from a signal`() {
    assertEquals(
      listOf("heading fill=#e66c37"),
      texts("legend-title").map {
        "${it.text} fill=${(it.fill?.paint as? ScenePaint.Solid)?.color?.toCssHex()}"
      },
    )
  }

  /** The shape comes from the signal; the fill stays the scale's, which is upstream's base. */
  @Test
  fun `a swatch takes its shape from a signal and its fill from the scale`() {
    assertEquals(
      listOf("CIRCLE? #118dff", "CIRCLE? #12239e").map { it.replace("CIRCLE?", "SQUARE") },
      scene()
        .flatten()
        .map { it.node }
        .filterIsInstance<SymbolNode>()
        .filter { it.metadata.role == "legend-symbol" }
        .map { "${it.shape} ${(it.fill?.paint as? ScenePaint.Solid)?.color?.toCssHex()}" },
    )
  }
}
