package dev.aster.vega.runtime

import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.ScenePaint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A mark's `hover` block, reaching the scene.
 *
 * This was the gap behind "hovering a mark does nothing": the block was parsed, carried through the
 * compiler and never applied, because a mark's effective encoding is `enter` overridden by `update`
 * and nothing consulted the third set. The item under the pointer is now redrawn from it.
 *
 * The mechanism is worth knowing before changing it. Each item is encoded **twice** at compile time
 * — resting, and as it looks hovered — with the id allocator rewound between the two so the pair
 * share an id. Responding to the pointer is then a node swap, not a recompile, and the ids that the
 * hit index, the selection and the accessibility tree all key on stay put.
 */
class HoverEncodeTest {

  private val controller = VegaChartController()

  private val json =
    """
    {
      "width": 300, "height": 100, "padding": 0,
      "data": [{"name": "t", "values": [{"c": "a"}, {"c": "b"}, {"c": "c"}]}],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
         "range": "width", "padding": 0}
      ],
      "marks": [{
        "type": "rect", "from": {"data": "t"},
        "encode": {
          "enter": {
            "x": {"scale": "x", "field": "c"},
            "width": {"scale": "x", "band": 1},
            "y": {"value": 0},
            "height": {"value": 100},
            "fill": {"value": "#4c78a8"},
            "tooltip": {"field": "c"}
          },
          "hover": {"fill": {"value": "firebrick"}, "fillOpacity": {"value": 0.5}}
        }
      }]
    }
    """
      .trimIndent()

  /**
   * The same chart, but the hovered bar raises itself, names a cursor and writes its own tooltip.
   */
  private val raisingJson =
    json
      .replace(
        """"tooltip": {"field": "c"}""",
        """"tooltip": {"signal": "'bar ' + datum.c"}, "cursor": {"value": "pointer"},
           "zindex": {"value": 0}""",
      )
      .replace(
        """"hover": {"fill": {"value": "firebrick"}, "fillOpacity": {"value": 0.5}}""",
        """"hover": {"fill": {"value": "firebrick"}, "fillOpacity": {"value": 0.5},
           "zindex": {"value": 1}}""",
      )

  private fun rects(): List<RectNode> {
    val out = mutableListOf<RectNode>()
    fun walk(node: SceneNode) {
      when (node) {
        is RectNode -> out += node
        is GroupNode -> node.children.forEach { walk(it) }
        else -> Unit
      }
    }
    walk(controller.state.value.snapshot.scene.root)
    return out
  }

  private fun fillOf(index: Int): SceneColor? =
    (rects()[index].fill?.paint as? ScenePaint.Solid)?.color

  /** The middle band of three across 300px, so x from 100 to 200. */
  private val onMiddle = PointD(150.0, 50.0)
  private val offAllBars = PointD(150.0, 400.0)

  @Test
  fun `the item under the pointer is drawn from its hover block, and only that item`() {
    controller.setSpec(json)
    val resting = fillOf(1)
    val neighbourBefore = fillOf(0)

    controller.dispatch(ChartInputEvent.PointerMoved(onMiddle))

    assertNotEquals(resting, fillOf(1), "the hovered item kept its resting fill")
    assertEquals(SceneColor.parse("firebrick"), fillOf(1))
    assertEquals(0.5, rects()[1].fill?.opacity ?: -1.0, 1e-9, "the hover block's fillOpacity")
    assertEquals(neighbourBefore, fillOf(0), "a neighbour changed as well")
    assertEquals(3, rects().size, "the swap added or lost an item")
  }

  @Test
  fun `moving off restores the resting appearance`() {
    controller.setSpec(json)
    val resting = fillOf(1)

    controller.dispatch(ChartInputEvent.PointerMoved(onMiddle))
    controller.dispatch(ChartInputEvent.PointerExited(null))

    assertEquals(resting, fillOf(1))
    assertNull(controller.snapshot.interactionState.hoveredNodeId)
  }

  @Test
  fun `the hovered item keeps its id, so the pointer stays over it`() {
    controller.setSpec(json)
    val restingId = rects()[1].id

    controller.dispatch(ChartInputEvent.PointerMoved(onMiddle))

    assertEquals(restingId, rects()[1].id)
    assertEquals(restingId, controller.snapshot.interactionState.hoveredNodeId)
  }

  @Test
  fun `a tap hovers too, because a touch screen has no pointer`() {
    // A browser synthesises `pointerover` from a touch before it reports the click, and without
    // this
    // a `hover` block and a tooltip would be unreachable on a phone.
    controller.setSpec(json)
    val resting = fillOf(1)

    controller.dispatch(ChartInputEvent.Tap(onMiddle))

    assertEquals(SceneColor.parse("firebrick"), fillOf(1))
    assertNotEquals(resting, fillOf(1))
    assertEquals("b", controller.snapshot.interactionState.tooltip?.let { tooltipText(it) })
  }

  @Test
  fun `a selection after a hover keeps the hover styling`() {
    controller.setSpec(json)
    controller.dispatch(ChartInputEvent.PointerMoved(onMiddle))
    controller.dispatch(ChartInputEvent.Tap(onMiddle))

    assertEquals(SceneColor.parse("firebrick"), fillOf(1))
    assertTrue(controller.snapshot.interactionState.selection.nodeIds.isNotEmpty())
  }

  @Test
  fun `a raised item is drawn over its neighbours, and only within its own mark`() {
    // `zindex` on an item is paint order *within* the mark. The middle bar starts second of three
    // and, raised, has to be painted last of the three — but still under the axis, which is a
    // sibling of the mark in the same group.
    controller.setSpec(raisingJson)
    assertEquals(listOf(0, 1, 2), rects().map { it.metadata.datumIndex })

    controller.dispatch(ChartInputEvent.PointerMoved(onMiddle))

    assertEquals(listOf(0, 2, 1), rects().map { it.metadata.datumIndex })
    assertEquals(1, rects().last().metadata.zindex)
  }

  @Test
  fun `moving off puts the raised item back where the data had it`() {
    controller.setSpec(raisingJson)
    controller.dispatch(ChartInputEvent.PointerMoved(onMiddle))
    controller.dispatch(ChartInputEvent.PointerExited(null))

    assertEquals(listOf(0, 1, 2), rects().map { it.metadata.datumIndex })
  }

  @Test
  fun `the cursor the item asks for is published for the host to apply`() {
    // Published rather than applied here: what a cursor *is* differs by platform, so the host maps
    // the CSS name to a `PointerIcon` or writes it into a style attribute.
    controller.setSpec(raisingJson)
    assertNull(controller.snapshot.interactionState.cursor)

    controller.dispatch(ChartInputEvent.PointerMoved(onMiddle))
    assertEquals("pointer", controller.snapshot.interactionState.cursor)

    controller.dispatch(ChartInputEvent.PointerExited(null))
    assertNull(controller.snapshot.interactionState.cursor)
  }

  @Test
  fun `a tooltip channel wins over the datum`() {
    controller.setSpec(raisingJson)
    controller.dispatch(ChartInputEvent.PointerMoved(onMiddle))

    assertEquals("bar b", tooltipText(controller.snapshot.interactionState.tooltip!!))
  }

  /**
   * A mark that **raises one of its own items** and also has a `hover` block.
   *
   * The two passes are paired by id rather than by position, and this is the arrangement that tells
   * the difference: the resting list is sorted by `zindex` and the hovered one is not, so pairing
   * by position hands the pointed-at item another item's hover appearance. Only the bar under the
   * pointer may change, whichever order the sort left them in.
   */
  @Test
  fun `a raised item still gets its own hover appearance`() {
    controller.setSpec(
      json
        .replace(
          """"tooltip": {"field": "c"}""",
          """"zindex": {"signal": "datum.c === 'a' ? 3 : 0"}""",
        )
        .replace(
          """"hover": {"fill": {"value": "firebrick"}, "fillOpacity": {"value": 0.5}}""",
          """"hover": {"fill": {"signal": "datum.c === 'b' ? 'firebrick' : 'seagreen'"}}""",
        )
    )
    val resting = rects().map { (it.fill?.paint as? ScenePaint.Solid)?.color }

    controller.dispatch(ChartInputEvent.PointerMoved(onMiddle))

    val hovered = rects()
    val changed =
      hovered.indices.filter {
        (hovered[it].fill?.paint as? ScenePaint.Solid)?.color != resting[it]
      }
    assertEquals(1, changed.size, "only the pointed-at bar may change: $changed")
    // And it is the middle bar's own hover colour, not the raised bar's.
    assertEquals(
      SceneColor.parse("firebrick"),
      (hovered[changed.single()].fill?.paint as? ScenePaint.Solid)?.color,
    )
    assertEquals("b", hovered[changed.single()].metadata.datum?.let { datumC(it) })
  }

  private fun datumC(value: dev.aster.vega.model.VegaValue): String? =
    ((value as? dev.aster.vega.model.VegaValue.Obj)?.fields?.get("c")
        as? dev.aster.vega.model.VegaValue.Str)
      ?.value

  @Test
  fun `pointing at nothing leaves every item resting`() {
    controller.setSpec(json)
    val before = rects().map { it.fill?.paint }

    controller.dispatch(ChartInputEvent.PointerMoved(offAllBars))

    assertEquals(before, rects().map { it.fill?.paint })
  }

  private fun tooltipText(value: dev.aster.vega.model.VegaValue): String? =
    (value as? dev.aster.vega.model.VegaValue.Str)?.value
      ?: ((value as? dev.aster.vega.model.VegaValue.Obj)?.fields?.get("c")
          as? dev.aster.vega.model.VegaValue.Str)
        ?.value
}
