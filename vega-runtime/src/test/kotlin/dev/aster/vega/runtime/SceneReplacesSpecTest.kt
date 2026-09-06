package dev.aster.vega.runtime

import dev.aster.vega.fixtures.SampleScenes
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SizeD
import dev.aster.vega.scene.flatten
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A hand-authored scene **replaces** the compiled chart, and nothing the specification left behind
 * puts it back.
 *
 * Found by using the demo rather than by reading: switching from a pasted specification to one of
 * the built-in scenes drew the built-in chart, and then the first tap on it drew the *pasted* chart
 * again — with the tooltip and the selection still answering for the built-in one, since the hit
 * index had been replaced and the scene had not. That reads as three separate faults, which is how
 * it was reported: a selection that will not clear, a tooltip in the wrong place, and a chart drawn
 * outside the box it was given.
 *
 * One cause. `setScene` published the scene and the hit index and left every field the
 * specification path installs, so eight code paths went on answering about a chart that is no
 * longer on screen. The two that put it back are pinned here; the rest — `setSignal`, the timer
 * tick, the scale-fingerprint comparison — read the same fields and are covered by clearing them.
 */
class SceneReplacesSpecTest {

  /** A chart with a hover variant, so `hoveredScene` has something to want `lastCompiled` for. */
  private val spec =
    """
    {"width": 200, "height": 100, "padding": 5,
     "data": [{"name": "t", "values": [{"x": 0}, {"x": 40}, {"x": 80}]}],
     "marks": [{"type": "rect", "name": "spec-marks", "from": {"data": "t"},
                "encode": {"enter": {"x": {"field": "x"}, "width": {"value": 30},
                                     "y": {"value": 0}, "height": {"value": 90},
                                     "fill": {"value": "steelblue"}},
                           "hover": {"fill": {"value": "orange"}}}}]}
    """

  private fun markNames(scene: Scene): Set<String> =
    scene.flatten().mapNotNull { it.node.metadata.markName }.toSet()

  private fun firstMarkCentre(scene: Scene): PointD {
    val mark = scene.flatten().map { it.node }.filterIsInstance<RectNode>().first()
    return PointD(mark.rect.centerX, mark.rect.centerY)
  }

  /**
   * The reported one: a hover after `setScene` republished the specification's chart.
   *
   * A **hover**, not a tap, because `handleTap` calls `updateHover` first — so a tap on a touch
   * screen took this path before it ever got to the selection, which is why the chart changed under
   * a finger that was only trying to select a mark.
   */
  @Test
  fun `a hover after setScene does not bring the compiled chart back`() {
    val controller = VegaChartController()
    controller.setSpec(spec)
    assertTrue(
      "spec-marks" in markNames(controller.snapshot.scene),
      "the specification did not draw",
    )

    val hand = SampleScenes.barChart()
    controller.setScene(hand)
    assertTrue("bars" in markNames(controller.snapshot.scene), "setScene did not draw the scene")

    controller.dispatch(ChartInputEvent.PointerMoved(firstMarkCentre(controller.snapshot.scene)))

    assertEquals(
      emptySet<String>(),
      markNames(controller.snapshot.scene).intersect(setOf("spec-marks")),
      "hovering brought the compiled chart back: ${markNames(controller.snapshot.scene)}",
    )
    assertTrue("bars" in markNames(controller.snapshot.scene), "the hand-authored scene was lost")
  }

  /** The same for a tap, which is what a reader actually does on a touch screen. */
  @Test
  fun `a tap after setScene does not bring the compiled chart back`() {
    val controller = VegaChartController()
    controller.setSpec(spec)
    controller.setScene(SampleScenes.barChart())

    val centre = firstMarkCentre(controller.snapshot.scene)
    controller.dispatch(ChartInputEvent.Tap(centre))

    assertTrue(
      "spec-marks" !in markNames(controller.snapshot.scene),
      "tapping brought the compiled chart back: ${markNames(controller.snapshot.scene)}",
    )
    // And the selection is of the scene that is actually drawn, rather than of one that is not.
    val selected = controller.snapshot.interactionState.selection.nodeIds
    assertTrue(selected.isNotEmpty(), "the tap selected nothing")
    assertTrue(
      controller.snapshot.scene.flatten().any { it.node.id in selected },
      "the selected node is not in the drawn scene, so its highlight has nothing to sit on",
    )
  }

  /**
   * The second route, which needs no interaction at all: a host reporting its size.
   *
   * `adoptContainerSize` recompiles `loadedSpecJson` when the size changes, so a layout pass alone
   * was enough to replace a hand-authored scene with the specification's chart.
   *
   * **`width: "container"`**, and that is the whole test rather than a detail. `adoptContainerSize`
   * returns early when `lastCompiled?.readsContainerSize == false`, so a specification declaring
   * its own width never reaches the recompile and a test using one passes whether or not the bug is
   * there — which is what the first draft of this did.
   */
  @Test
  fun `a container resize after setScene does not bring the compiled chart back`() {
    val controller = VegaChartController()
    controller.setSpec(
      spec.replace(
        "\"width\": 200,",
        "\"signals\": [{\"name\": \"width\", \"update\": \"containerSize()[0]\"}],",
      )
    )
    assertTrue(
      controller.lastCompiled?.readsContainerSize == true,
      "this specification has to read the container size or the test proves nothing",
    )
    controller.setScene(SampleScenes.barChart())

    controller.containerSize = SizeD(640.0, 480.0)

    assertTrue(
      "spec-marks" !in markNames(controller.snapshot.scene),
      "a resize brought the compiled chart back: ${markNames(controller.snapshot.scene)}",
    )
  }

  /** And the ordinary case still works: a scene, then a specification, draws the specification. */
  @Test
  fun `a specification after a scene still draws`() {
    val controller = VegaChartController()
    controller.setScene(SampleScenes.barChart())
    controller.setSpec(spec)
    assertTrue(
      "spec-marks" in markNames(controller.snapshot.scene),
      "a specification set after a scene did not draw: ${markNames(controller.snapshot.scene)}",
    )
  }

  /**
   * Two scenes in a row are unaffected by any of this.
   *
   * Compared by **content** rather than by identity, because a hover legitimately republishes the
   * scene through `withoutFocusRing`, which rebuilds the root to strip a ring that may not be
   * there. Asserting on the object would fail for a reason that has nothing to do with this.
   */
  @Test
  fun `setScene twice keeps the second scene`() {
    val controller = VegaChartController()
    controller.setScene(SampleScenes.barChart())
    val second = SampleScenes.lineChart()
    controller.setScene(second)
    // The middle of the chart, rather than a mark: a line chart has no `RectNode` to find, and any
    // hover at all is enough to prove that hovering does not swap the scene.
    controller.dispatch(
      ChartInputEvent.PointerMoved(PointD(second.width / 2.0, second.height / 2.0))
    )
    assertEquals(
      markNames(second),
      markNames(controller.snapshot.scene),
      "a hover replaced the scene that was set",
    )
  }
}
