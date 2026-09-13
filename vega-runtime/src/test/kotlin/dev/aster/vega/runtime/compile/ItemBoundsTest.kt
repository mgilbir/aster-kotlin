package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * An item a mark is drawn *from* carries the **bounds** of what was drawn.
 *
 * Vega marks share a namespace with datasets, so `"from": {"data": "labels"}` names the items the
 * `labels` mark produced. Those items are scene items, and a scene item has been through the
 * bounder: `item.bounds` is a `Bounds` with `x1`, `y1`, `x2` and `y2` on it. Reading it is how a
 * specification places something against what a mark *came out* as rather than against what it was
 * told — a box behind a label, a dot at the centre of one, a leader line from the edge of one.
 *
 * The items this engine exposed carried the encoded channels and the row behind them, and no
 * bounds. So `(datum.bounds.x1 + datum.bounds.x2) / 2` was arithmetic on nothing: a parliament
 * diagram that lays three hundred seats out as text items and then draws a circle on each put every
 * one of them on the origin. It was the largest single disagreement in the Deneb corpus — 1579
 * differences, three quarters of the whole — and it falls to six.
 *
 * The expectations are upstream's own scene items for this specification.
 */
class ItemBoundsTest {

  private val spec =
    """
    {
      "width": 200, "height": 100, "padding": 0, "autosize": "none",
      "data": [{"name": "t", "values": [
        {"s": "one", "x": 20}, {"s": "three", "x": 90}, {"s": "seventeen", "x": 150}
      ]}],
      "marks": [
        {"name": "labels", "type": "text", "from": {"data": "t"}, "encode": {"enter": {
          "x": {"field": "x"}, "y": {"value": 40}, "text": {"field": "s"},
          "align": {"value": "center"}, "fontSize": {"value": 12}}}},
        {"name": "boxes", "type": "rect", "from": {"data": "labels"}, "encode": {"enter": {
          "x": {"signal": "datum.bounds.x1"}, "x2": {"signal": "datum.bounds.x2"},
          "y": {"signal": "datum.bounds.y1"}, "y2": {"signal": "datum.bounds.y2"},
          "fill": {"value": "#eeeeee"}}}},
        {"name": "dots", "type": "symbol", "from": {"data": "labels"}, "encode": {"enter": {
          "x": {"signal": "(datum.bounds.x1+datum.bounds.x2)/2"},
          "y": {"signal": "(datum.bounds.y1+datum.bounds.y2)/2"},
          "size": {"value": 20}, "fill": {"value": "red"}}}}
      ]
    }
    """
      .trimIndent()

  private fun nodes(): List<SceneNode> =
    requireNotNull(SpecCompiler(VegaHeadlessTextEngine()).compileJson(spec).scene).flatten().map {
      it.node
    }

  /** What upstream's own text items bound to, which is what the two marks below read. */
  @Test
  fun `the labels bound where upstream bounds them`() {
    val labels = nodes().filterIsInstance<TextNode>()
    assertEquals(
      listOf("6.0,30.0,34.0,42.0", "66.0,30.0,114.0,42.0", "107.0,30.0,193.0,42.0"),
      labels.map { "${it.bounds.left},${it.bounds.top},${it.bounds.right},${it.bounds.bottom}" },
    )
  }

  /** A box drawn from each corner of the label's bounds. */
  @Test
  fun `a rect drawn from the bounds spans the label`() {
    val boxes = nodes().filterIsInstance<RectNode>()
    assertEquals(
      listOf("6.0,30.0,34.0,42.0", "66.0,30.0,114.0,42.0", "107.0,30.0,193.0,42.0"),
      boxes.map { "${it.x},${it.y},${it.x + it.width},${it.y + it.height}" },
    )
  }

  /** And a dot at the centre of one, which is the shape the parliament diagram uses. */
  @Test
  fun `a symbol drawn from the bounds lands at the label's centre`() {
    val dots = nodes().filterIsInstance<SymbolNode>()
    assertEquals(listOf("20.0,36.0", "90.0,36.0", "150.0,36.0"), dots.map { "${it.x},${it.y}" })
  }

  /** The bounds are the item's **turned** box, not the upright one it would have had. */
  @Test
  fun `a rotated label is read back rotated`() {
    val scene =
      requireNotNull(
        SpecCompiler(VegaHeadlessTextEngine())
          .compileJson(
            """
            {
              "width": 200, "height": 100, "padding": 0, "autosize": "none",
              "data": [{"name": "t", "values": [{"s": "abcdef", "x": 100}]}],
              "marks": [
                {"name": "labels", "type": "text", "from": {"data": "t"}, "encode": {"enter": {
                  "x": {"field": "x"}, "y": {"value": 50}, "text": {"field": "s"},
                  "angle": {"value": 45}, "align": {"value": "center"},
                  "fontSize": {"value": 12}}}},
                {"name": "dots", "type": "symbol", "from": {"data": "labels"}, "encode": {"enter": {
                  "x": {"signal": "datum.bounds.x1"}, "y": {"signal": "datum.bounds.y1"},
                  "size": {"value": 20}}}}
              ]
            }
            """
              .trimIndent()
          )
          .scene
      )
    val dot = scene.flatten().map { it.node }.filterIsInstance<SymbolNode>().single()
    assertEquals(78.4332431738103, dot.x, 1e-6)
    assertEquals(22.776388924317928, dot.y, 1e-6)
  }

  /**
   * A series drawn as **one** node hands every item that node's box.
   *
   * Upstream's `line`, `area` and `trail` are nested marks: the items exist per row and the bounds
   * are the mark's, so all three items of a three-point line report the same box. This engine draws
   * the series as a single node, which is the same fact wearing different clothes — so the one node
   * stands for every item it was drawn from, and a label placed at `datum.bounds.x1` lands at the
   * left edge of the line rather than nowhere.
   */
  @Test
  fun `a line hands its own bounds to every item drawn from it`() {
    val scene =
      requireNotNull(
        SpecCompiler(VegaHeadlessTextEngine())
          .compileJson(
            """
            {
              "width": 200, "height": 100, "padding": 0, "autosize": "none",
              "data": [{"name": "t", "values": [
                {"x": 10, "y": 20}, {"x": 100, "y": 80}, {"x": 190, "y": 40}
              ]}],
              "marks": [
                {"name": "series", "type": "line", "from": {"data": "t"}, "encode": {"enter": {
                  "x": {"field": "x"}, "y": {"field": "y"}, "stroke": {"value": "black"}}}},
                {"name": "dots", "type": "symbol", "from": {"data": "series"}, "encode": {"enter": {
                  "x": {"signal": "datum.bounds.x1"}, "y": {"signal": "datum.bounds.y2"},
                  "size": {"value": 20}}}}
              ]
            }
            """
              .trimIndent()
          )
          .scene
      )
    val dots = scene.flatten().map { it.node }.filterIsInstance<SymbolNode>()
    assertEquals(listOf("6.0,84.0", "6.0,84.0", "6.0,84.0"), dots.map { "${it.x},${it.y}" })
  }
}
