package dev.aster.vega.compose.mp

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneNodeId
import dev.aster.vega.scene.VectorD
import dev.aster.vega.scene.flatten
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A finger on a chart drawn by Compose Multiplatform, which until now did nothing at all.
 *
 * This renderer had **no pointer input**: a tap was ignored, and the only way to reach a mark was
 * through the accessibility tree — so a screen-reader user could activate a bar and a sighted user
 * could not. Every other host answers a finger, and a specification that says `"tooltip": true`
 * expects one to.
 *
 * What the module owns is the part a host must not repeat: inverting the **same placement** the
 * drawing used, and hit testing through `SceneHitIndex`. It does not dispatch — there is no
 * dataflow in a module that depends on `vega-scene` alone — so a host with a controller forwards
 * these, and a host that only draws passes nothing and pays for nothing.
 *
 * The chart below is 160×80 with a padding of 5, so the scene is 170×90 and is drawn into a slot of
 * exactly that many dp: at a density of 1 a scene unit is a pixel and the assertions can be written
 * in the coordinates a reader of this test can check by hand.
 */
@OptIn(ExperimentalTestApi::class)
class ComposePointerTest {

  private val chart =
    """
    {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 160, "height": 80, "padding": 5,
     "data": [{"name": "t", "values": [{"c": "Total", "v": 18}, {"c": "Sleep", "v": 7}]}],
     "scales": [
       {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
       {"name": "y", "type": "linear", "domain": [0, 27], "range": "height"}],
     "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
       "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
       "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
       "fill": {"value": "steelblue"}}}}]}
    """

  private fun scene(): Scene {
    val compiled =
      SpecCompiler(textEngine = MetricTextEngine(), loader = DenyLoader).compileJson(chart)
    val complaints =
      compiled.diagnostics.filter {
        it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.FATAL
      }
    assertTrue(complaints.isEmpty(), "compiled with errors: ${complaints.map { it.message }}")
    return requireNotNull(compiled.scene) { "no scene" }
  }

  /** The first bar's centre in **scene** coordinates, worked out from the scene itself. */
  private fun firstBarCentre(scene: Scene): PointD {
    val bar =
      scene.flatten().filter { it.node.metadata.role == "mark" }.minByOrNull { it.worldBounds.left }
        ?: error("no marks")
    return PointD(bar.worldBounds.centerX, bar.worldBounds.centerY)
  }

  @Test
  fun `a tap reports where it landed and what it hit`() = runComposeUiTest {
    val scene = scene()
    var reported: Pair<PointD, SceneNodeId?>? = null
    setContent {
      VegaChart(
        scene,
        modifier = Modifier.size(scene.width.dp, scene.height.dp),
        onTap = { point, nodeId -> reported = point to nodeId },
      )
    }

    val centre = firstBarCentre(scene)
    onRoot().performTouchInput { click(Offset(centre.x.toFloat(), centre.y.toFloat())) }

    val (point, nodeId) = assertNotNull(reported, "the tap was not reported at all")
    // Scene coordinates, not slot coordinates: the placement is inverted here so a host does not
    // have
    // to. At this size the two agree, which is what makes the next assertion meaningful.
    assertTrue(abs(point.x - centre.x) < 2.0 && abs(point.y - centre.y) < 2.0, "$point vs $centre")
    assertNotNull(nodeId, "a tap on a bar found no mark")
    assertEquals(
      scene.flatten().first { it.node.metadata.role == "mark" }.node.id,
      nodeId,
      "the mark under the finger is the one the hit test found",
    )
  }

  @Test
  fun `a tap on blank space reports the point and no mark`() = runComposeUiTest {
    val scene = scene()
    var reported: Pair<PointD, SceneNodeId?>? = null
    setContent {
      VegaChart(
        scene,
        modifier = Modifier.size(scene.width.dp, scene.height.dp),
        onTap = { point, nodeId -> reported = point to nodeId },
      )
    }

    // Top-left, inside the padding: above every bar and clear of the axis.
    onRoot().performTouchInput { click(Offset(2f, 2f)) }

    val (point, nodeId) = assertNotNull(reported)
    assertNull(nodeId, "nothing is drawn at (2, 2), so nothing was hit")
    assertTrue(point.x < 5.0 && point.y < 5.0, "$point")
  }

  @Test
  fun `a long press is reported separately, which is where a tooltip hangs`() = runComposeUiTest {
    val scene = scene()
    var pressed: SceneNodeId? = null
    setContent {
      VegaChart(
        scene,
        modifier = Modifier.size(scene.width.dp, scene.height.dp),
        onLongPress = { _, nodeId -> pressed = nodeId },
      )
    }

    val centre = firstBarCentre(scene)
    onRoot().performTouchInput { longClick(Offset(centre.x.toFloat(), centre.y.toFloat())) }

    assertNotNull(pressed, "a long press on a bar found no mark")
  }

  @Test
  fun `a drag is reported as an increment in scene units`() = runComposeUiTest {
    val scene = scene()
    val moves = mutableListOf<VectorD>()
    val ends = mutableListOf<VectorD>()
    setContent {
      VegaChart(
        scene,
        modifier = Modifier.size(scene.width.dp, scene.height.dp),
        onPan = { delta, ended -> (if (ended) ends else moves).add(delta) },
      )
    }

    onRoot().performTouchInput {
      down(Offset(40f, 40f))
      moveTo(Offset(60f, 40f))
      moveTo(Offset(80f, 40f))
      up()
    }

    assertTrue(moves.isNotEmpty(), "the drag was not reported")
    // Increments, not absolute positions: the sum is the whole gesture, and each report is what a
    // controller adds to its viewport. Compose consumes some of the first move as the touch slop,
    // so
    // the total is the travel less that.
    val travelled = moves.sumOf { it.dx }
    assertTrue(travelled > 20.0 && travelled <= 40.0, "reported $travelled units of a 40-unit drag")
    assertTrue(
      moves.all { abs(it.dy) < 1.0 },
      "a horizontal drag reported vertical movement: $moves",
    )
    // **And the gesture ends.** `ended = true` is what makes `ChartEvent.ViewportChanged` fire —
    // the phase exists so a host persists a viewport once rather than once a frame — and nothing
    // ever passed it. Exactly one, carrying a zero increment: the movement is already reported.
    assertEquals(listOf(VectorD(0.0, 0.0)), ends, "the pan must be closed exactly once")
  }

  @Test
  fun `a pinch is reported as a factor about a scene anchor`() = runComposeUiTest {
    val scene = scene()
    val factors = mutableListOf<Double>()
    val anchors = mutableListOf<PointD>()
    val endedAt = mutableListOf<PointD>()
    setContent {
      VegaChart(
        scene,
        modifier = Modifier.size(scene.width.dp, scene.height.dp),
        onZoom = { factor, at, ended ->
          if (ended) endedAt.add(at) else factors.add(factor)
          if (!ended) anchors.add(at)
        },
      )
    }

    // Two fingers moving apart about (60, 45): a pinch open, which is a zoom in. One detector
    // handles
    // pan and pinch together, because two separate ones would each claim the pointers and a
    // two-finger
    // gesture would arrive as whichever won.
    onRoot().performTouchInput {
      down(0, Offset(50f, 45f))
      down(1, Offset(70f, 45f))
      moveTo(0, Offset(30f, 45f))
      moveTo(1, Offset(90f, 45f))
      up(0)
      up(1)
    }

    assertTrue(factors.isNotEmpty(), "the pinch was not reported")
    assertTrue(factors.all { it > 1.0 }, "a pinch open is a factor above one: $factors")
    // The anchor is the gesture's centroid, in **scene** units. Which report is the last one is a
    // question about how Compose batches a two-pointer move — the centroid shifts as each pointer
    // is
    // reported — so what is pinned is that every anchor lies between the fingers rather than one
    // exact
    // value, and that the vertical coordinate is the row they were both on.
    assertTrue(anchors.isNotEmpty())
    assertTrue(
      anchors.all { it.x > 40.0 && it.x < 80.0 && abs(it.y - 45.0) < 2.0 },
      "an anchor was not between the two fingers: $anchors",
    )
    // **And the pinch ends**, once, about the point the fingers were last on. Without this the
    // controller never emits `ChartEvent.ViewportChanged` for a pinch on this renderer at all.
    assertEquals(1, endedAt.size, "the pinch must be closed exactly once: $endedAt")
    assertTrue(
      endedAt.all { it.x > 40.0 && it.x < 80.0 && abs(it.y - 45.0) < 2.0 },
      "the closing anchor was not between the two fingers: $endedAt",
    )
  }

  @Test
  fun `a pointer moving over the chart is reported, and so is leaving it`() = runComposeUiTest {
    val scene = scene()
    val hovered = mutableListOf<Pair<PointD?, SceneNodeId?>>()
    setContent {
      VegaChart(
        scene,
        modifier = Modifier.size(scene.width.dp, scene.height.dp),
        onHover = { point, nodeId -> hovered.add(point to nodeId) },
      )
    }

    val centre = firstBarCentre(scene)
    onRoot().performMouseInput {
      moveTo(Offset(centre.x.toFloat(), centre.y.toFloat()))
      exit()
    }

    assertTrue(hovered.isNotEmpty(), "the pointer was not reported")
    val overTheBar = hovered.firstOrNull { it.second != null }
    assertNotNull(overTheBar, "a pointer over a bar found no mark: $hovered")
    // Leaving says so with a null, which is what clears a hover state — the engine's own
    // `PointerExited` means the same thing.
    assertEquals(null to null, hovered.last(), "leaving the chart was not reported: $hovered")
  }

  @Test
  fun `a chart with no callbacks takes no pointer input at all`() = runComposeUiTest {
    // The module's bargain: a host that only draws pays for nothing. Asserted by tapping a chart
    // with
    // no callbacks and observing that nothing anywhere claims the gesture — if the modifier were
    // installed unconditionally this would still pass, so what it really pins is that the
    // composition
    // succeeds and the tap is inert rather than a crash in an empty handler.
    val scene = scene()
    setContent { VegaChart(scene, modifier = Modifier.size(scene.width.dp, scene.height.dp)) }

    onRoot().performTouchInput { click(Offset(20f, 20f)) }
  }
}
