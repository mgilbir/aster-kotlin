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
 * A signal handler declared **inside a group mark** does not fire, and now says so.
 *
 * `VegaChartController.publish` builds its event bindings from the specification's *top-level*
 * signals. A signal declared inside a group carries its `on` handlers into a compile that nothing
 * ever dispatches to, so the handler is unreachable — and until this test it was unreachable in
 * silence, which is the thing ADR 0011 exists to forbid.
 *
 * Found by settling two audit questions that turned out to be the same fixture. Vega's own
 * `overview-plus-detail` example declares `brush`, `anchor`, `xdown`, `delta` and `detailDomain`
 * inside its `overview` group, so brushing the overview changes nothing. The audit had recorded
 * that as a *scale* problem (C4, `buildTime` ignoring `domainRaw`) and then as a `push: "outer"`
 * question (Q6); both were real, and neither was the reason it does not work.
 *
 * What this test pins is the **diagnostic**, not the behaviour: the day handlers are dispatched
 * into group scopes, the warning goes and this goes red, which is when the row is rewritten.
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

  /** The same signal, the same handler, moved inside a group. Nothing else differs. */
  private val insideAGroup =
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
   * The control: at the top level the handler fires, so the two specifications differ only in where
   * the signal is declared.
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

  @Test
  fun `the same handler inside a group does not fire, and says so`() {
    val controller = VegaChartController()
    controller.setSpec(insideAGroup)
    press(controller)
    assertEquals(
      listOf("#0000ff"),
      fills(controller),
      "a group-scoped handler fired — events now reach group scopes, so the diagnostic must go",
    )
    val reported = controller.state.value.diagnostics.map { it.message }
    assertTrue(
      reported.any { "inside a group mark" in it && "never fire" in it },
      "a group-scoped handler was ignored in silence: $reported",
    )
  }

  /**
   * Vega's own `overview-plus-detail` is the case this was found on, and it reports for all five.
   *
   * Its `brush`, `anchor`, `xdown`, `delta` and `detailDomain` live inside the `overview` group, so
   * every one of them is unreachable — which is why brushing the overview does not move the detail
   * panel. Named here because a reader who tries that fixture deserves to find this test.
   */
  @Test
  fun `the overview-plus-detail fixture reports every unreachable handler`() {
    val root = File(System.getProperty("user.dir")).parentFile
    val spec = File(root, "test-fixtures/specs/overview-plus-detail.vg.json")
    assertTrue(spec.isFile, "missing ${spec.path}")
    val controller = VegaChartController(loader = FileDataLoader(File(root, "test-fixtures")))
    controller.setSpec(spec.readText())
    val unreachable =
      controller.state.value.diagnostics.filter { "inside a group mark" in it.message }
    assertEquals(
      5,
      unreachable.size,
      "expected the five overview signals to be reported: " + unreachable.map { it.message },
    )
  }
}
