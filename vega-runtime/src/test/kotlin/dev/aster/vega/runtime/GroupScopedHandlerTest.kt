package dev.aster.vega.runtime

import dev.aster.vega.loader.FileDataLoader
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
 * The two shapes of group-scoped handler that still do not fire, each reported by name.
 *
 * This class used to say that **no** handler declared inside a group fires — which was true, and is
 * the thing `GroupScopedHandlerFiresTest` now closes for a group drawn once whose selector names a
 * mark. What is left is narrower, and neither case may go quiet: a reader whose brush does nothing
 * has to be told which of the two it is.
 *
 * **A bare `scope` selector.** A handler declared in a group defaults to source `scope` — upstream
 * attaches the listener to that group's own item — so `{"events": "mousedown"}` inside a group
 * means "a mousedown anywhere in this group". Where the selector names a mark, the mark name does
 * the narrowing and the two are the same listener; where it names none, narrowing it needs scene
 * containment that nothing here answers yet. Refused at registration rather than widened to the
 * whole view, because widening it would fire a group's handler on every event in the chart — the
 * loud wrong answer where this is the quiet one (ADR 0011).
 *
 * **A faceted group.** One scope per cell, so which cell the event landed in is part of the
 * question, and it is not asked yet.
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

  @Test
  fun `a bare scope selector does not fire, and says which mark to name`() {
    val controller = VegaChartController()
    controller.setSpec(bareSelectorInAGroup)
    press(controller)
    assertEquals(
      listOf("#0000ff"),
      fills(controller),
      "a bare scope selector fired, so it was widened to the whole view",
    )
    val reported = controller.state.value.diagnostics.map { it.message }
    assertTrue(
      reported.any { "@markname:" in it && "'hit'" in it },
      "a bare scope selector was dropped without saying what to write instead: $reported",
    )
  }

  /**
   * A faceted group's handlers do not fire, and the reason names the group.
   *
   * Distinct from the case above and reported differently, because the remedy is different: there
   * is nothing the author can rewrite here.
   */
  @Test
  fun `a faceted group's handlers are reported rather than silently unbound`() {
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
    val reported = controller.state.value.diagnostics.map { it.message }
    assertTrue(
      reported.any { "once per facet" in it && "cell" in it },
      "a faceted group's handlers were left unbound in silence: $reported",
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
