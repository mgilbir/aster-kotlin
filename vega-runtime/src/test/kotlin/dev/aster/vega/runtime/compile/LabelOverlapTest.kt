package dev.aster.vega.runtime.compile

import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hiding labels that collide.
 *
 * The asymmetry is the thing to know, and it is not in the documentation: a **legend** removes
 * overlapping labels by default and an **axis** does not. `labelOverlap: true` sits in the `legend`
 * block of upstream's `config.js` and the `axis` block has no entry at all, so a dense axis prints
 * every label on top of the last unless the specification asks otherwise. Verified by rendering the
 * same twelve-category axis at four widths upstream and watching nothing disappear.
 *
 * A hidden label is not removed. It stays in the scene at zero opacity, so a chart has the same
 * marks however wide it is drawn — only the axis's measured extent shrinks.
 */
class LabelOverlapTest {

  private fun compile(axis: String, width: Int = 200) =
    SpecCompiler()
      .compileJson(
        """
        {
          "width": $width, "height": 80, "padding": 0,
          "data": [{"name": "t", "values": [
            {"c": "category-0"}, {"c": "category-1"}, {"c": "category-2"}, {"c": "category-3"},
            {"c": "category-4"}, {"c": "category-5"}, {"c": "category-6"}, {"c": "category-7"}
          ]}],
          "scales": [
            {"name": "s", "type": "band", "domain": {"data": "t", "field": "c"},
             "range": "width"}
          ],
          "axes": [{"orient": "bottom", "scale": "s"$axis}]
        }
        """
          .trimIndent()
      )

  private fun labels(axis: String, width: Int = 200): List<Pair<String, Double>> =
    compile(axis, width)
      .scene!!
      .flatten()
      .map { it.node }
      .filterIsInstance<TextNode>()
      .filter { it.metadata.role == "axis-label" }
      .map { it.layout.run.text to it.opacity }

  private fun visible(axis: String, width: Int = 200): List<String> =
    labels(axis, width).filter { it.second > 0.0 }.map { it.first }

  @Test
  fun `an axis prints every label unless it is asked not to`() {
    assertEquals(8, visible("").size)
    assertEquals(8, visible(""", "labelOverlap": false""").size)
  }

  @Test
  fun `parity hides every other label until the collisions stop`() {
    assertEquals(
      listOf("category-0", "category-7"),
      visible(""", "labelOverlap": "parity""""),
    )
  }

  /** Greedy keeps whatever clears the last one it kept, so it can keep more than parity does. */
  @Test
  fun `greedy scans forwards and keeps what fits`() {
    val greedy = visible(""", "labelOverlap": "greedy"""", width = 400)
    val parity = visible(""", "labelOverlap": "parity"""", width = 400)
    assertTrue(greedy.size >= parity.size, "greedy $greedy vs parity $parity")
    assertEquals("category-0", greedy.first())
  }

  /** `true` is upstream's fallback for any method it does not recognize, and means parity. */
  @Test
  fun `true means parity`() {
    assertEquals(
      visible(""", "labelOverlap": "parity""""),
      visible(""", "labelOverlap": true"""),
    )
  }

  @Test
  fun `a hidden label stays in the scene at zero opacity`() {
    val all = labels(""", "labelOverlap": "parity"""")
    assertEquals(8, all.size, "the mark count must not change with the chart's width")
    assertEquals(6, all.count { it.second == 0.0 })
  }

  /** Both ends survive: upstream puts the last label back if the halving dropped it. */
  @Test
  fun `the first and last labels are always kept`() {
    val kept = visible(""", "labelOverlap": "parity"""", width = 60)
    assertEquals(listOf("category-0", "category-7"), kept)
  }

  /** A wider separation demands a bigger gap, so it hides more than the default zero does. */
  @Test
  fun `separation widens what counts as a collision`() {
    val tight = visible(""", "labelOverlap": "greedy"""", width = 900)
    val loose = visible(""", "labelOverlap": "greedy", "labelSeparation": 200""", width = 900)
    assertTrue(loose.size < tight.size, "tight $tight vs loose $loose")
  }

  /** Nothing collides, so nothing is hidden however the method is spelled. */
  @Test
  fun `a roomy axis keeps everything`() {
    assertEquals(8, visible(""", "labelOverlap": "parity"""", width = 2000).size)
  }
}
