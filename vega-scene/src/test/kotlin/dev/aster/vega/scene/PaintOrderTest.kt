package dev.aster.vega.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `zindex` paint order, against what upstream's SVG actually draws.
 *
 * The differential harness cannot see this: it compares the **scene**, and upstream keeps its items
 * in data order and reorders inside `visit` at render time. So the reordering has to happen in the
 * renderer, and the evidence has to come from the drawn output — every expectation below is the
 * left-to-right order of the rects in upstream's SVG for the same `zindex` list.
 *
 * The rule is not a sort, which is what makes the third and fourth cases worth having. Upstream
 * paints every item whose `zindex` is **zero** first, in data order, and only then the ones that
 * carry one, ordered by it. A negative `zindex` is still non-zero, so it joins the second pass and
 * draws *on top* — `[0, -1, 0]` and `[0, 5, 0]` produce the same order, which no sort would give.
 */
class PaintOrderTest {

  private fun item(x: Double, zindex: Int, ordinal: Int = 0, name: String? = "bars"): SceneNode =
    RectNode(
      id = SceneNodeId(x.toLong()),
      x = x,
      y = 10.0,
      width = 8.0,
      height = 30.0,
      fill = Fill.of(SceneColor.Black),
      metadata =
        NodeMetadata(
          role = "mark",
          markName = name,
          markKind = "rect",
          zindex = zindex,
          markOrdinal = ordinal,
        ),
    )

  private fun order(zindexes: List<Int>): List<Double> =
    paintOrder(zindexes.mapIndexed { index, z -> item(index * 10.0, z) }).map {
      (it as RectNode).x
    }

  @Test
  fun `zero-index items are painted first, then the rest by their index`() {
    assertEquals(listOf(0.0, 20.0, 10.0), order(listOf(0, 5, 0)))
    assertEquals(listOf(0.0, 20.0, 30.0, 10.0), order(listOf(0, 2, 0, 1)))
  }

  /** No zeroes at all, so every item is in the second pass and the order is by `zindex`. */
  @Test
  fun `without a zero index the order is the zindex order`() {
    assertEquals(listOf(10.0, 20.0, 0.0), order(listOf(3, 1, 2)))
  }

  /**
   * The case a sort gets wrong: a negative `zindex` draws over the untouched items, not under them.
   */
  @Test
  fun `a negative zindex draws on top`() {
    assertEquals(listOf(0.0, 20.0, 10.0), order(listOf(0, -1, 0)))
  }

  /** Nothing to reorder is the common case, and it comes back as the same list. */
  @Test
  fun `a group with no zindex is left alone`() {
    val children = listOf(item(0.0, 0), item(10.0, 0))
    assertEquals(children, paintOrder(children))
  }

  /**
   * `zindex` is paint order *within one mark*, so a run stops where the mark changes.
   *
   * A raised bar draws over its neighbours and still under the axis, which is what confines the
   * reordering to the run of children that came from the same mark.
   */
  @Test
  fun `the reordering does not cross a mark boundary`() {
    val bars = listOf(item(0.0, 0), item(10.0, 5))
    val axis =
      RuleNode(
        id = SceneNodeId(99),
        x1 = 0.0,
        y1 = 0.0,
        x2 = 100.0,
        y2 = 0.0,
        stroke = Stroke(ScenePaint.Black),
        metadata = NodeMetadata(role = "axis-domain"),
      )
    val ordered = paintOrder(bars + axis)
    assertEquals(listOf(0.0, 10.0), ordered.filterIsInstance<RectNode>().map { it.x })
    assertEquals(axis, ordered.last())
  }

  /**
   * Two `rect` marks side by side are two runs, and neither reorders into the other.
   *
   * The case that needed [NodeMetadata.markOrdinal]: with only a name and a type to go by, two
   * unnamed marks of the same kind read as one run, and the second mark's raised item was painted
   * among the first mark's — `[a0, b0, a-raised, b-raised]` where upstream draws each mark whole.
   * The expectation is upstream's own SVG for the same pair of marks, left to right.
   */
  @Test
  fun `two marks of the same type are two runs`() {
    // Upstream's own order for these two marks is 0, 20, 10 then 30, 50, 40 — each mark's zeroes in
    // data order followed by its own raised item. Merged into one run it comes out 0, 20, 30, 50,
    // 40, 10: the second mark's raised bar painted under the first's.
    val first =
      listOf(
        item(0.0, 0, ordinal = 0, name = null),
        item(10.0, 5, ordinal = 0, name = null),
        item(20.0, 0, ordinal = 0, name = null),
      )
    val second =
      listOf(
        item(30.0, 0, ordinal = 1, name = null),
        item(40.0, 3, ordinal = 1, name = null),
        item(50.0, 0, ordinal = 1, name = null),
      )
    assertEquals(
      listOf(0.0, 20.0, 10.0, 30.0, 50.0, 40.0),
      paintOrder(first + second).map { (it as RectNode).x },
    )
  }
}
