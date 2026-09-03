package dev.aster.vega.runtime

import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.TextNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A legend over a **discretizing** scale is upstream's stacked colour bar.
 *
 * `SUPPORTED_FEATURES.md` said it was not: "the right colours against the right cut points, drawn
 * as ordinary symbol swatches. Upstream draws a stacked colour bar instead … so this is not the
 * same picture and says so." That was true once. It is drawn as a colour bar now, and three
 * fixtures — `legend-discretizing`, `county-unemployment` and `map-with-tooltip` — already compare
 * it against upstream mark for mark, their references carrying the `legend-band` role.
 *
 * This is the narrower check, for the parts a mark comparison cannot make legible: the band
 * arithmetic and the label precision, which the row itself calls out.
 */
class BandedLegendTest {

  private fun compile(scale: String) =
    SpecCompiler()
      .compileJson(
        """
        {"width": 200, "height": 120, "padding": 5,
         "data": [{"name": "t", "values": [{"v": 1}, {"v": 5}, {"v": 9}]}],
         "scales": [$scale],
         "legends": [{"fill": "c", "title": "q"}],
         "marks": [{"type": "rect", "from": {"data": "t"},
                    "encode": {"enter": {"x": {"field": "v"}, "y": {"value": 0},
                                         "width": {"value": 5}, "height": {"value": 5},
                                         "fill": {"scale": "c", "field": "v"}}}}]}
        """
          .trimIndent()
      )

  private val quantize =
    """{"name": "c", "type": "quantize", "domain": [0, 10],
        "range": {"scheme": "blues", "count": 4}}"""

  private fun <T> collect(root: SceneNode, role: String, of: (SceneNode) -> T?): List<T> {
    val out = mutableListOf<T>()
    fun walk(node: SceneNode, inherited: String?) {
      val here = node.metadata.role ?: inherited
      if (here == role) of(node)?.let { out += it }
      if (node is GroupNode) node.children.forEach { walk(it, here) }
    }
    walk(root, null)
    return out
  }

  @Test
  fun `the swatches are a stacked colour bar, not a row of symbols`() {
    val scene = requireNotNull(compile(quantize).scene).root
    val bands = collect(scene, "legend-band") { it as? RectNode }
    assertEquals(4, bands.size, "expected one band per bucket")
    val symbols = collect(scene, "legend-symbol") { it as? SymbolNode }
    assertTrue(symbols.isEmpty(), "the legend drew ${symbols.size} symbol swatches as well")
  }

  /**
   * Each band is `gradientLength / buckets` tall, and they stack **bottom upwards**.
   *
   * The default gradient length is 200 and there are four buckets, so each is 50. Upwards because
   * the lowest bucket belongs at the bottom — a colour bar read against an axis has to run the way
   * the axis does, and reversing it would put the smallest value at the top of the key.
   */
  @Test
  fun `each band is the gradient length over the bucket count, stacked upwards`() {
    val scene = requireNotNull(compile(quantize).scene).root
    val bands = collect(scene, "legend-band") { it as? RectNode }
    for (band in bands) assertEquals(50.0, band.height, 1e-9, "band height")
    val tops = bands.map { it.y }.sorted()
    assertEquals(listOf(0.0, 50.0, 100.0, 150.0), tops, "the bands do not tile the gradient")

    // Darkest at the top, because the scheme runs light to dark and the top is the largest bucket.
    val byTop = bands.sortedBy { it.y }
    val colours = byTop.map { (it.fill?.paint as? ScenePaint.Solid)?.color?.toCssHex() }
    assertEquals(colours.distinct(), colours, "two bands share a colour")
  }

  /**
   * Labels carry the precision the **cut points** need, not a tick step's.
   *
   * The row's own example: quartiles land wherever the data puts them, so `2.5` beside a `5` must
   * not print as `2`. A step-derived format would round the boundaries into each other and the key
   * would say two buckets begin at the same number.
   */
  @Test
  fun `labels keep the precision the cut points need`() {
    val scene = requireNotNull(compile(quantize).scene).root
    val labels = collect(scene, "legend-label") { (it as? TextNode)?.text }
    assertTrue("2.5" in labels, "the boundary at 2.5 was printed as ${labels.filter { "2" in it }}")
    assertEquals(labels.filter { it.isNotEmpty() }.distinct(), labels.filter { it.isNotEmpty() })
  }

  /** `quantile` and `threshold` are the same picture, which is what makes it the scale *kind*. */
  @Test
  fun `the other discretizing scales are banded too`() {
    for (scale in
      listOf(
        """{"name": "c", "type": "quantile", "domain": {"data": "t", "field": "v"},
            "range": {"scheme": "blues", "count": 3}}""",
        """{"name": "c", "type": "threshold", "domain": [3, 7],
            "range": {"scheme": "blues", "count": 3}}""",
      )) {
      val scene = requireNotNull(compile(scale).scene).root
      assertTrue(
        collect(scene, "legend-band") { it as? RectNode }.isNotEmpty(),
        "a discretizing scale drew no colour bar",
      )
    }
  }
}
