package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.scene.PathNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A centre is where a mark's **middle** goes, so its start is half its extent back from it.
 *
 * ```js
 * if (encode.yc) { code += 'o.y=o.yc-(o.height||0)/2;'; }
 * ```
 *
 * `adjustSpatial` does that for every mark type but `rule`, and with no extent encoded the half is
 * zero and the centre *is* the start — which is why reading the centre straight through looked
 * right everywhere until something encoded an extent beside it. A violin plot is that something:
 * each half is an area with `yc` and a scaled `height`, and this drew all twelve of them flat along
 * their own middles.
 *
 * The expectations are upstream's own scene items for this specification.
 */
class CentredExtentTest {

  private fun area(): PathNode =
    requireNotNull(
        SpecCompiler(VegaHeadlessTextEngine())
          .compileJson(
            """
            {
              "width": 100, "height": 50, "padding": 0, "autosize": "none",
              "data": [{"name": "t", "values": [
                {"x": 0, "h": 10}, {"x": 50, "h": 30}, {"x": 100, "h": 20}
              ]}],
              "marks": [{"type": "area", "from": {"data": "t"}, "encode": {"update": {
                "x": {"field": "x"}, "yc": {"value": 25}, "height": {"field": "h"},
                "fill": {"value": "steelblue"}}}}]
            }
            """
              .trimIndent()
          )
          .scene
      )
      .flatten()
      .map { it.node }
      .filterIsInstance<PathNode>()
      .single()

  /**
   * Upstream's items are `(0, 20, h 10)`, `(50, 10, h 30)` and `(100, 15, h 20)` — each `y` half
   * its own height above the centre of 25 — and the area is drawn between `y` and `y + height`.
   */
  @Test
  fun `an area centred with a height spans half of it either side`() {
    val bounds = area().bounds
    assertEquals(0.0, bounds.left, 1e-9)
    assertEquals(100.0, bounds.right, 1e-9)
    // The top edge is the smallest `y`: 10, from the tallest slice at x = 50.
    assertEquals(10.0, bounds.top, 1e-9)
    // And the bottom is the largest `y + height`: 40, from the same slice.
    assertEquals(40.0, bounds.bottom, 1e-9)
  }
}
