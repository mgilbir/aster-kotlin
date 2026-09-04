@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.runtime

import dev.aster.vega.loader.FileDataLoader
import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.ScenePaint
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every shape of group-scoped handler fires, and this class is what is left of the list that did
 * not.
 *
 * It began by saying that **no** handler declared inside a group fires, which was true. Then only a
 * bare `scope` selector did not, then only a faceted group's. All three are closed, and the tests
 * that pinned each limit were rewritten into the case they had been refusing rather than deleted —
 * a reader who comes here after finding an old note deserves to land on the answer.
 *
 * The two halves live elsewhere and are worth reading together: `ScopeSourcedHandlerTest` covers a
 * bare selector in a group drawn once, and `FacetedScopeHandlerTest` covers a group drawn once per
 * cell. What kept both refused for so long is the case those classes assert hardest — a group's
 * handler must *not* fire for an event in a sibling group or a neighbouring cell, and widening it
 * to the whole view would have done exactly that.
 */
class GroupScopedHandlerTest {

  private fun fills(controller: VegaChartController): List<String> {
    val out = mutableListOf<String>()
    fun walk(node: SceneNode) {
      if (node is RectNode)
        out += ((node.fill?.paint as? ScenePaint.Solid)?.color?.toCssHex() ?: "none")
      if (node is GroupNode) node.children.forEach { walk(it) }
    }
    walk(controller.state.value.snapshot.scene.root)
    return out
  }

  private val topLevel =
    """
    {"width": 100, "height": 100, "padding": 0, "autosize": "none",
     "data": [{"name": "t", "values": [{"v": 1}]}],
     "signals": [{"name": "hit", "value": 0, "on": [{"events": "mousedown", "update": "1"}]}],
     "marks": [{"type": "rect", "from": {"data": "t"},
                "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                     "width": {"value": 100}, "height": {"value": 100}},
                           "update": {"fill": {"signal": "hit ? '#ff0000' : '#0000ff'"}}}}]}
    """
      .trimIndent()

  /** The same signal and handler moved inside a group, with the selector left bare. */
  private val bareSelectorInAGroup =
    """
    {"width": 100, "height": 100, "padding": 0, "autosize": "none",
     "data": [{"name": "t", "values": [{"v": 1}]}],
     "marks": [{"type": "group", "name": "g",
       "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                            "width": {"value": 100}, "height": {"value": 100}}},
       "signals": [{"name": "hit", "value": 0, "on": [{"events": "mousedown", "update": "1"}]}],
       "marks": [{"type": "rect", "from": {"data": "t"},
                  "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                       "width": {"value": 100}, "height": {"value": 100}},
                             "update": {"fill": {"signal": "hit ? '#ff0000' : '#0000ff'"}}}}]}]}
    """
      .trimIndent()

  /** The same again, with the mark named — which is all it takes, and which does fire. */
  private val namedSelectorInAGroup =
    bareSelectorInAGroup
      .replace(""""events": "mousedown"""", """"events": "@box:mousedown"""")
      .replace(""""type": "rect", "from"""", """"type": "rect", "name": "box", "from"""")

  private fun press(controller: VegaChartController) =
    controller.dispatch(
      ChartInputEvent.PointerDown(
        PointD(50.0, 50.0),
        pointerId = 1,
        device = PointerDevice.MOUSE,
        buttons = 1,
      )
    )

  /**
   * The control: at the top level the handler fires, so the specifications differ only in where the
   * signal is declared.
   */
  @Test
  fun `a top-level handler fires`() {
    val controller = VegaChartController()
    controller.setSpec(topLevel)
    assertEquals(listOf("#0000ff"), fills(controller))
    press(controller)
    assertEquals(listOf("#ff0000"), fills(controller), "a top-level handler did not fire")
    assertTrue(controller.state.value.diagnostics.isEmpty())
  }

  /**
   * Naming the mark is the whole difference, and it is what makes the refusal below a real choice
   * rather than a limitation of group scopes in general.
   */
  @Test
  fun `the same handler fires inside a group once its selector names a mark`() {
    val controller = VegaChartController()
    controller.setSpec(namedSelectorInAGroup)
    press(controller)
    assertEquals(
      listOf("#ff0000"),
      fills(controller),
      "a group-scoped handler naming a mark did not fire",
    )
  }

  /**
   * A bare `scope` selector fires now, for an event inside its own group.
   *
   * This class used to hold the opposite, and the diagnostic it checked told the author to write
   * `@markname:mousedown` instead. `ScopeSourcedHandlerTest` covers the behaviour in full — most
   * importantly that it does *not* fire for an event in a sibling group, which is what made the
   * refusal the right answer while the question went unanswered.
   */
  @Test
  fun `a bare scope selector fires and is no longer reported`() {
    val controller = VegaChartController()
    controller.setSpec(bareSelectorInAGroup)
    press(controller)
    assertEquals(
      listOf("#ff0000"),
      fills(controller),
      "a bare scope selector did not fire for a press inside its own group",
    )
    assertTrue(
      controller.state.value.diagnostics.none { "@markname:" in it.message },
      "a bare scope selector is still being refused",
    )
  }

  /**
   * A faceted group's handlers fire, one live copy per cell, and nothing is reported.
   *
   * This asserted the opposite: there was no remedy the author could write, so the group was named
   * in a diagnostic instead. `FacetedScopeHandlerTest` is where the behaviour is checked in full —
   * that each cell fires alone, and that naming the mark does not widen it back across the facet.
   * Kept here, inverted, so the shape this class was built around still has a case in it.
   */
  @Test
  fun `a faceted group's handlers are bound, one scope per cell`() {
    val controller = VegaChartController()
    controller.setSpec(
      """
      {"width": 300, "height": 60, "padding": 0, "autosize": "none",
       "data": [{"name": "t",
                 "values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}]}],
       "marks": [{
         "type": "group", "name": "cell",
         "from": {"facet": {"name": "rows", "data": "t", "groupby": "c"}},
         "signals": [{"name": "hit", "value": 0,
                      "on": [{"events": "@box:mousedown", "update": "1"}]}],
         "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                              "width": {"value": 80}, "height": {"value": 50}}},
         "marks": [{"type": "rect", "name": "box", "from": {"data": "rows"},
                    "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                         "width": {"value": 20}, "height": {"value": 20}}}}]}]}
      """
        .trimIndent()
    )
    assertEquals(
      listOf("cell/cells[0]", "cell/cells[1]"),
      controller.lastCompiled!!.groupScopes.keys.filter { it.startsWith("cell/") },
      "a faceted group did not record one scope per cell",
    )
    assertTrue(
      controller.state.value.diagnostics.none { "do not fire" in it.message },
      "a faceted group's handlers are still reported as unbound",
    )
  }

  /**
   * Vega's own `overview-plus-detail`, which is the fixture this was all found on.
   *
   * Every one of its five overview signals now binds — four to a named mark and one to a signal
   * source — so nothing about them is reported any more. Kept, and inverted, because a reader who
   * tries that fixture deserves to find this test either way.
   */
  @Test
  fun `the overview-plus-detail fixture reports no unreachable handler`() {
    val root = File(System.getProperty("user.dir")).parentFile
    val spec = File(root, "test-fixtures/specs/overview-plus-detail.vg.json")
    assertTrue(spec.isFile, "missing ${spec.path}")
    val controller = VegaChartController(loader = FileDataLoader(File(root, "test-fixtures")))
    controller.setSpec(spec.readText())
    val unreachable =
      controller.state.value.diagnostics.filter {
        "do not fire" in it.message || "not dispatched" in it.message
      }
    assertEquals(
      emptyList<String>(),
      unreachable.map { it.message },
      "a handler in the overview group is still unreachable",
    )
  }
}
