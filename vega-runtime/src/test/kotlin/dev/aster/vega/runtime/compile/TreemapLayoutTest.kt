package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Three things a treemap gets from the *layout* rather than from the data.
 *
 * A **ratio at or below one is one**, not the default: `custom.ratio = x => custom((x = +x) > 1 ? x
 * : 1)`. One is a meaningful setting — squares rather than golden rectangles — and rejecting it
 * fell back to φ, which tiles a chart differently everywhere.
 *
 * A layout's answer belongs to the **row it was computed for**, and the rows may have moved since
 * the tree was built: `nest`, then `collect`, then `treemap` is how a template orders its
 * rectangles by size, and looking the results up by position handed each row the layout of
 * whichever row used to sit there.
 *
 * And `resquarify` **reuses** the rows an earlier pass chose. That is what it is for, and it is not
 * only about animation: a fitted chart compiles twice, once to be measured and once to be drawn, so
 * its second pass re-applies the first pass's rows at the fitted size. With `squarify` the same
 * chart tiles afresh and lands somewhere else, which is the pair below.
 *
 * Every expectation was read off upstream running the same specification.
 */
class TreemapLayoutTest {

  private fun chart(method: String, ratio: String, autosize: String, axis: Boolean) =
    """
    {
      "width": 120, "height": 200, "padding": 5, "autosize": $autosize,
      "data": [{
        "name": "t",
        "values": [
          {"k": "a", "v": 6}, {"k": "b", "v": 24}, {"k": "c", "v": 12},
          {"k": "d", "v": 3}, {"k": "e", "v": 9}, {"k": "f", "v": 18}
        ],
        "transform": [
          {"type": "nest", "keys": ["k"]},
          {"type": "collect", "sort": {"field": "v", "order": "descending"}},
          {"type": "treemap", "field": "v", "method": $method, "ratio": $ratio,
           "paddingInner": 0, "paddingOuter": 1,
           "size": [{"signal": "width"}, {"signal": "height"}]}
        ]
      }],
      ${if (axis) SCALED_AXIS else ""}
      "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"update": {
        "x": {"field": "x0"}, "y": {"field": "y0"},
        "x2": {"field": "x1"}, "y2": {"field": "y1"},
        "fill": {"value": "steelblue"}}}}]
    }
    """
      .trimIndent()

  /** The rectangles in the order the rows come out in, which the `collect` sorted by size. */
  private fun tiles(json: String): String =
    requireNotNull(SpecCompiler(VegaHeadlessTextEngine()).compileJson(json).scene)
      .flatten()
      .map { it.node }
      .filterIsInstance<RectNode>()
      .joinToString(" ") {
        "%.2f,%.2f,%.2f,%.2f"
          .format(
            java.util.Locale.ROOT,
            it.x,
            it.y,
            it.x + it.width,
            it.y + it.height,
          )
      }

  /**
   * Each row gets **its own** rectangle, though the rows were re-ordered after the tree was built.
   *
   * The biggest value is first after the `collect`, and the biggest rectangle is first here.
   */
  @Test
  fun `a sort between the tree and the layout does not shuffle the rectangles`() {
    assertEquals(
      "25.60,2.00,118.00,82.50 44.14,123.00,118.00,198.00 2.00,84.50,42.14,174.90 " +
        "44.14,84.50,118.00,121.00 2.00,2.00,23.60,82.50 2.00,176.90,42.14,198.00",
      tiles(chart(""""squarify"""", "1", """"none"""", axis = false)),
    )
  }

  /** And φ is a different tiling, which is what a ratio of one had been falling back to. */
  @Test
  fun `a ratio of one is not the golden ratio`() {
    assertEquals(
      "25.60,2.00,118.00,82.50 69.43,84.50,118.00,198.00 2.00,84.50,67.43,140.25 " +
        "2.00,156.69,67.43,198.00 2.00,2.00,23.60,82.50 2.00,142.25,67.43,154.69",
      tiles(chart(""""squarify"""", "1.618033988749895", """"none"""", axis = false)),
    )
  }

  /**
   * A fitted chart is compiled twice, and `resquarify` keeps the first pass's rows.
   *
   * The axis is what makes the two passes differ: it takes 38 units off the width, so the second
   * pass tiles a narrower box. `squarify` re-chooses its rows for that box; `resquarify` keeps the
   * ones chosen for the full width and only re-proportions them.
   */
  @Test
  fun `resquarify keeps the rows the measuring pass chose`() {
    assertEquals(
      "18.00,2.00,80.00,78.33 30.57,116.89,80.00,188.00 2.00,80.33,28.57,166.07 " +
        "30.57,80.33,80.00,114.89 2.00,2.00,16.00,78.33 2.00,168.07,28.57,188.00",
      tiles(chart(""""resquarify"""", "1", """"fit"""", axis = true)),
    )
  }

  /**
   * The same chart tiled afresh, which is what `squarify` does and what this used to do for both.
   */
  @Test
  fun `squarify tiles the fitted box from scratch`() {
    assertEquals(
      "18.00,2.00,80.00,78.33 28.67,119.50,80.00,188.00 2.00,80.33,64.00,117.50 " +
        "2.00,119.50,26.67,188.00 2.00,2.00,16.00,78.33 66.00,80.33,80.00,117.50",
      tiles(chart(""""squarify"""", "1", """"fit"""", axis = true)),
    )
  }

  private companion object {
    const val SCALED_AXIS =
      """"scales": [{"name": "y", "type": "linear", "domain": [0, 10], "range": "height"}],
         "axes": [{"orient": "left", "scale": "y", "title": "left axis title"}],"""
  }
}
