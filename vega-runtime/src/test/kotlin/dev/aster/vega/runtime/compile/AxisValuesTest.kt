package dev.aster.vega.runtime.compile

import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Explicit axis tick values.
 *
 * Every expectation was read off upstream drawing the same axis. That mattered: `values` looks like
 * "draw these ticks" and is four separate rules, three of which surprise.
 *
 * - A value outside the scale's **range** is dropped, not clamped.
 * - The survivors are ordered by where they land, so a list written out of order comes out in order
 *   — and backwards when the range is reversed.
 * - Too many for `tickCount` and every other one is dropped, repeatedly; if that leaves fewer than
 *   three, the first and last are used instead. Five values with `tickCount: 4` give **three**.
 * - The labels are formatted at a precision derived from the *number of values*, not from the
 *   values themselves, so `[0.5, 1.5]` on a `[0, 2]` domain reads "1" and "2".
 */
class AxisValuesTest {

  private fun compile(scale: String, axis: String) =
    SpecCompiler()
      .compileJson(
        """
        {
          "width": 200, "height": 100, "padding": 0,
          "data": [{"name": "t", "values": [{"c": "a"}, {"c": "b"}, {"c": "c"}, {"c": "d"}]}],
          "scales": [{"name": "s", "range": "width", $scale}],
          "axes": [{"orient": "bottom", "scale": "s", $axis}]
        }
        """
          .trimIndent()
      )

  private fun labels(scale: String, axis: String): List<String> =
    compile(scale, axis)
      .scene!!
      .flatten()
      .map { it.node }
      .filterIsInstance<TextNode>()
      .filter { it.metadata.role == "axis-label" }
      .map { it.layout.run.text }

  private fun tickCount(scale: String, axis: String): Int =
    compile(scale, axis)
      .scene!!
      .flatten()
      .map { it.node }
      .count { it is RuleNode && it.metadata.role == "axis-tick" }

  private val linear = """"type": "linear", "domain": [0, 100]"""
  private val band = """"type": "band", "domain": {"data": "t", "field": "c"}"""

  @Test
  fun `values replace the scale's own ticks`() {
    assertEquals(listOf("0", "33", "77", "100"), labels(linear, """"values": [0, 33, 77, 100]"""))
    assertEquals(4, tickCount(linear, """"values": [0, 33, 77, 100]"""))
  }

  @Test
  fun `a value outside the range is dropped rather than clamped`() {
    assertEquals(listOf("50"), labels(linear, """"values": [-40, 50, 300]"""))
  }

  @Test
  fun `values come out in range order however they were written`() {
    assertEquals(listOf("0", "33", "77"), labels(linear, """"values": [77, 0, 33]"""))
    assertEquals(
      listOf("0", "50", "100"),
      labels("""$linear, "reverse": true""", """"values": [50, 100, 0]"""),
    )
  }

  /**
   * The halving overshoots and the endpoints are only restored below three, which is why five
   * values under `tickCount: 4` give three and not four.
   */
  @Test
  fun `too many values for the tick count are thinned, then fall back to the ends`() {
    val five = """"values": [0, 25, 50, 75, 100]"""
    assertEquals(listOf("0", "50", "100"), labels(linear, """$five, "tickCount": 4"""))
    assertEquals(listOf("0", "100"), labels(linear, """$five, "tickCount": 1"""))
    assertEquals(listOf("0", "25", "50", "75", "100"), labels(linear, five))
  }

  /**
   * The count that sets the label precision is the number of values given, not the tick count a
   * reader would infer from them — so both of these read as whole numbers.
   */
  @Test
  fun `labels are formatted at the precision the value count implies`() {
    assertEquals(
      listOf("1", "2"),
      labels(""""type": "linear", "domain": [0, 2]""", """"values": [0.5, 1.5]"""),
    )
    assertEquals(
      listOf("0.5", "1.5"),
      labels(""""type": "linear", "domain": [0, 2]""", """"values": [0.5, 1.5], "tickCount": 10"""),
    )
  }

  @Test
  fun `a band scale filters its domain, and an unknown value is dropped`() {
    assertEquals(listOf("b", "d"), labels(band, """"values": ["b", "d"]"""))
    assertEquals(listOf("a", "b"), labels(band, """"values": ["b", "zz", "a"]"""))
  }

  /** A discrete label is the domain value itself, with no number formatting applied. */
  @Test
  fun `an empty values array draws no ticks at all`() {
    assertEquals(emptyList<String>(), labels(linear, """"values": []"""))
    assertEquals(0, tickCount(linear, """"values": []"""))
  }

  @Test
  fun `the gridlines follow the values too`() {
    val grid =
      compile(linear, """"values": [10, 90], "grid": true""")
        .scene!!
        .flatten()
        .map { it.node }
        .filterIsInstance<RuleNode>()
        .filter { it.metadata.role == "axis-grid" }
    assertEquals(2, grid.size)
    // Node-local coordinates: the axis group carries the half-pixel crisp offset, so upstream's
    // absolute 20.5 and 180.5 are 20 and 180 here.
    assertEquals(listOf(20.0, 180.0), grid.map { it.x1 })
  }
}
