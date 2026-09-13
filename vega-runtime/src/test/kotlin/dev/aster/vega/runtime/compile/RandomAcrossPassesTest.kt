package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A fitted chart draws **fresh** random numbers for the pass that is drawn.
 *
 * Upstream's generator belongs to the view, not to a render: `setRandom` replaces a module-level
 * binding, so a chart that is laid out twice — which is every `autosize: "fit"` chart, once to be
 * measured and once to be drawn — asks it for a second set of numbers. This engine restarted the
 * sequence for each pass and drew the first set twice, so every jittered mark sat where the
 * *measuring* pass had put it.
 *
 * It also drew **twice as many** numbers as upstream per pass, because the items a mark exposes for
 * another mark to read were built whether or not anything read them — a second evaluation of every
 * channel, `random()` included. They are built on demand now.
 *
 * Three rows, a seeded generator, and `random() * 10` as the offset on a band position. Upstream's
 * three dots land at 15.204171, 29.023366 and 51.269463, which are the fourth, fifth and sixth
 * draws of the sequence laid over the band centres the fitted pass produced — the measuring pass
 * having taken the first three. Read off a live view with the same seed this engine uses.
 */
class RandomAcrossPassesTest {

  @Test
  fun `the drawn pass continues the sequence the measuring pass started`() {
    val compiled =
      SpecCompiler(VegaHeadlessTextEngine())
        .compileJson(
          """
          {
            "width": 100, "height": 60, "padding": 0, "autosize": "fit",
            "data": [{"name": "t", "values": [{"c": "a"}, {"c": "b"}, {"c": "c"}]}],
            "scales": [{"name": "y", "type": "band", "domain": ["a", "b", "c"],
                        "range": "height"}],
            "axes": [{"orient": "left", "scale": "y"}],
            "marks": [{"type": "symbol", "from": {"data": "t"}, "encode": {"update": {
              "x": {"value": 10}, "size": {"value": 20},
              "y": {"scale": "y", "field": "c", "band": 0.5,
                    "offset": {"signal": "random()*10"}}}}}]
          }
          """
            .trimIndent()
        )
    val dots =
      requireNotNull(compiled.scene).flatten().map { it.node }.filterIsInstance<SymbolNode>()
    assertEquals(
      listOf("15.204171", "29.023366", "51.269463"),
      dots.map { "%.6f".format(java.util.Locale.ROOT, it.y) },
    )
  }
}
