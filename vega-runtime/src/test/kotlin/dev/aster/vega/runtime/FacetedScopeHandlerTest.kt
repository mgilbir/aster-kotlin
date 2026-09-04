@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.runtime

import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.ScenePaint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A **faceted** group's handlers fire, in the cell the event landed in and in no other.
 *
 * This was the last shape of group-scoped handler that did not, and it was reported by name rather
 * than left silent: a faceted group resolves one scope per cell, so its path names no single scope
 * and the event has to say which cell it was in.
 *
 * It has to be per cell rather than per group, and that is the whole difficulty. A faceted group's
 * signals are the cell's own — each small multiple has its own `brush`, its own `hover` — so one
 * binding shared across the group would make brushing one multiple move the brush in all of them.
 * That is a worse answer than not firing at all, because it looks like it works.
 *
 * **Naming a mark does not narrow it either**, which is what makes the named-mark case below worth
 * its own test: every cell holds a mark called `box`, so `@box:mousedown` matches in all of them
 * and only the scope tells them apart. Upstream says the same by compiling `inScope(event.item)`
 * into *every* stream declared in a subscope, whether or not it names a mark.
 */
class FacetedScopeHandlerTest {

  private val controller = VegaChartController()

  /** Two cells side by side, each with a full-height rect and a handler of its own. */
  private fun facets(events: String, markName: String = "") =
    """
    {
      "width": 200, "height": 100, "padding": 0, "autosize": "none",
      "data": [{"name": "t", "values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}]}],
      "scales": [{"name": "cells", "type": "band", "domain": {"data": "t", "field": "c"},
                  "range": "width"}],
      "marks": [{
        "type": "group", "name": "cell",
        "from": {"facet": {"name": "rows", "data": "t", "groupby": "c"}},
        "signals": [{"name": "hit", "value": 0,
                     "on": [{"events": $events, "update": "hit + 1"}]}],
        "encode": {"enter": {"x": {"scale": "cells", "field": "c"}, "y": {"value": 0},
                             "width": {"scale": "cells", "band": 1}, "height": {"value": 100}}},
        "marks": [{"type": "rect"$markName, "from": {"data": "rows"},
                   "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                        "width": {"value": 100}, "height": {"value": 100}},
                              "update": {"fill": {"signal": "hit ? '#ff0000' : '#0000ff'"}}}}]
      }]
    }
    """
      .trimIndent()

  private fun press(x: Double, y: Double) =
    controller.dispatch(
      ChartInputEvent.PointerDown(
        PointD(x, y),
        pointerId = 1,
        device = PointerDevice.MOUSE,
        buttons = 1,
      )
    )

  /** The `hit` signal of one cell, by the path the compiler recorded that cell under. */
  private fun hits(cell: Int) =
    controller.lastCompiled!!.groupScopes["cell/cells[$cell]"]?.values?.get("hit")

  private fun fills(): List<String> {
    val out = mutableListOf<String>()
    fun walk(node: SceneNode) {
      if (node is RectNode)
        out += ((node.fill?.paint as? ScenePaint.Solid)?.color?.toCssHex() ?: "-")
      if (node is GroupNode) node.children.forEach { walk(it) }
    }
    walk(controller.state.value.snapshot.scene.root)
    return out
  }

  @Test
  fun `a bare scope handler fires in the cell the event landed in`() {
    controller.setSpec(facets(""""mousedown""""))
    assertEquals(VegaValue.Num(0.0), hits(0), "the corpus did not record two cells")
    assertEquals(VegaValue.Num(0.0), hits(1))

    press(50.0, 50.0)
    assertEquals(VegaValue.Num(1.0), hits(0), "a faceted group's handler did not fire in its cell")
    assertEquals(VegaValue.Num(0.0), hits(1), "it fired in the other cell as well")

    press(150.0, 50.0)
    assertEquals(
      VegaValue.Num(1.0),
      hits(0),
      "the first cell fired again for a press in the second",
    )
    assertEquals(VegaValue.Num(1.0), hits(1), "the second cell's handler did not fire")
  }

  /**
   * The same with the mark **named**, which does not narrow it: both cells hold a `box`.
   *
   * The case that would look like it worked while being wrong — `@box:mousedown` matches every
   * cell's copy of the mark, so without the scope every cell's handler fires on every press.
   */
  @Test
  fun `naming the mark does not widen the handler to every cell`() {
    controller.setSpec(facets(""""@box:mousedown"""", markName = ""","name": "box""""))
    press(50.0, 50.0)
    assertEquals(VegaValue.Num(1.0), hits(0), "a named-mark handler did not fire in its own cell")
    assertEquals(
      VegaValue.Num(0.0),
      hits(1),
      "naming the mark fired every cell's handler, because a mark name is not a scope",
    )
  }

  /**
   * And the cell **redraws** from its own signal, which is what a reader sees.
   *
   * A signal that changes in the right scope and never reaches the marks compiled from it is the
   * same chart as one that never fired. The per-cell value has to survive the recompile — it does,
   * through the compiler's scoped overrides, keyed by the same cell path.
   */
  @Test
  fun `only the pressed cell redraws`() {
    controller.setSpec(facets(""""mousedown""""))
    assertEquals(listOf("#0000ff", "#0000ff"), fills(), "the two cells did not start alike")
    press(50.0, 50.0)
    assertEquals(
      listOf("#ff0000", "#0000ff"),
      fills(),
      "the press repainted the wrong cell, or both of them",
    )
  }

  /** An event on nothing at all fires nothing, so no cell was widened to the view. */
  @Test
  fun `a press on no mark fires nothing`() {
    controller.setSpec(
      facets(""""mousedown"""")
        .replace(
          """"width": {"value": 100}, "height": {"value": 100}""",
          """"width": {"value": 40},
                              "height": {"value": 40}""",
        )
    )
    press(70.0, 80.0)
    assertEquals(VegaValue.Num(0.0), hits(0), "a press on empty space fired the first cell")
    assertEquals(VegaValue.Num(0.0), hits(1), "a press on empty space fired the second cell")
  }

  /**
   * A mark that **overflows** its unclipped cell is still in that cell's scope.
   *
   * The case that says this is item ancestry and not a rectangle: the group is narrowed to 40 and
   * the rect inside it is still 100 wide, so a press at 70 is outside the cell's own bounds and on
   * a mark the cell drew. Upstream's `inScope` walks `item.mark.group` and finds the cell, so it
   * fires; testing containment of the group's rectangle would say it does not, and that is the
   * reading this used to have.
   */
  @Test
  fun `a mark overflowing its cell is still inside it`() {
    controller.setSpec(
      facets(""""mousedown"""")
        .replace(""""width": {"scale": "cells", "band": 1}""", """"width": {"value": 40}""")
    )
    press(70.0, 50.0)
    assertEquals(
      VegaValue.Num(1.0),
      hits(0),
      "a press on a mark the first cell drew, outside the cell's own bounds, did not fire it",
    )
    assertEquals(VegaValue.Num(0.0), hits(1), "and it fired the second cell as well")
  }

  /** No diagnostic: a faceted group's handlers are ordinary Vega and are dispatched now. */
  @Test
  fun `a faceted group is no longer reported`() {
    controller.setSpec(facets(""""mousedown""""))
    val reported = controller.state.value.diagnostics.map { it.message }
    assertTrue(
      reported.none { "do not fire" in it },
      "a faceted group's handlers are still reported as unbound: $reported",
    )
  }

  /**
   * A group **inside** a facet is a different scope in each cell, and its handler follows.
   *
   * Found while landing the cells above, and wrong in a way nothing was watching: the compiler
   * appended the cell index in `cellPath` but compiled the cell's *contents* under the group's own
   * path, so both cells recorded `cell/inner` and the second overwrote the first. One scope for two
   * cells meant a press in the left one fired nothing at all — the node recorded under that key was
   * the right cell's — and a press in the right one moved a signal the left cell's marks were also
   * drawn from.
   *
   * Two halves fix it and both are needed: the compiler pushes the cell onto the path its contents
   * compile under, and the binding walk carries the **concrete** scopes down instead of the shape
   * the specification has. A spec-shaped prefix names `cell/inner`, which is now no scope at all.
   */
  @Test
  fun `a group nested inside a cell gets that cell's own scope`() {
    controller.setSpec(
      """
      {
        "width": 200, "height": 100, "padding": 0, "autosize": "none",
        "data": [{"name": "t", "values": [{"c": "a"}, {"c": "b"}]}],
        "scales": [{"name": "cells", "type": "band", "domain": {"data": "t", "field": "c"},
                    "range": "width"}],
        "marks": [{
          "type": "group", "name": "cell",
          "from": {"facet": {"name": "rows", "data": "t", "groupby": "c"}},
          "encode": {"enter": {"x": {"scale": "cells", "field": "c"}, "y": {"value": 0},
                               "width": {"scale": "cells", "band": 1}, "height": {"value": 100}}},
          "marks": [{
            "type": "group", "name": "inner",
            "signals": [{"name": "hit", "value": 0,
                         "on": [{"events": "mousedown", "update": "hit + 1"}]}],
            "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                 "width": {"value": 100}, "height": {"value": 100}}},
            "marks": [{"type": "rect", "from": {"data": "rows"},
                       "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                            "width": {"value": 100}, "height": {"value": 100},
                                            "fill": {"value": "#cccccc"}}}}]
          }]
        }]
      }
      """
        .trimIndent()
    )
    fun inner(cell: Int) =
      controller.lastCompiled!!.groupScopes["cell/cells[$cell]/inner"]?.values?.get("hit")
    assertEquals(
      listOf("cell/cells[0]", "cell/cells[0]/inner", "cell/cells[1]", "cell/cells[1]/inner"),
      controller.lastCompiled!!.groupScopes.keys.sorted(),
      "the two cells' inner groups did not each record a scope of their own",
    )

    press(50.0, 50.0)
    assertEquals(VegaValue.Num(1.0), inner(0), "the first cell's inner group did not fire")
    assertEquals(VegaValue.Num(0.0), inner(1), "and the second cell's fired with it")

    press(150.0, 50.0)
    assertEquals(
      VegaValue.Num(1.0),
      inner(0),
      "the first cell fired again for a press in the second",
    )
    assertEquals(VegaValue.Num(1.0), inner(1), "the second cell's inner group did not fire")
  }
}
