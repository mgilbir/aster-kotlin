package dev.aster.vega.compose.mp

import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
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
import kotlin.test.assertTrue
import org.jetbrains.skia.Image

/**
 * Pan and zoom, which this renderer accumulated in a controller and never showed.
 *
 * `VegaChartController` owns the viewport: it adds a pan's delta into `viewportOffset`, multiplies
 * a pinch into `viewportScale`, clamps the zoom, keeps the anchor stationary and emits
 * `ViewportChanged`. The **Android View** reads that state back and draws through it. This renderer
 * did not, and neither did the Swift one — so a pan on either updated state, made `canReset` true,
 * and left the chart exactly where it was. A gesture that does nothing is worse than a gesture that
 * is missing: it looks like the renderer is broken rather than unfinished.
 *
 * Three things have to move together, and each is asserted here: the **drawing**, the **touch
 * target** and the **accessibility frames**. They move together because they share one placement —
 * which is the same argument this project has made twice before, on Android and in Swift, after a
 * finger landed beside the mark it looked like it hit.
 */
@OptIn(ExperimentalTestApi::class)
class ComposeViewportTest {

  private val chart =
    """
    {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 100, "height": 60, "padding": 0,
     "background": "white",
     "data": [{"name": "t", "values": [{"c": "Total", "v": 2}]}],
     "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
       "x": {"value": 20}, "y": {"value": 20}, "width": {"value": 10}, "height": {"value": 10},
       "fill": {"value": "black"}}}}]}
    """

  /**
   * A data-driven chart, for the test that looks a mark up by the label the engine derived for it.
   */
  private val labelled =
    """
    {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
     "width": 100, "height": 60, "padding": 0,
     "data": [{"name": "t", "values": [{"c": "Total", "v": 2}]}],
     "scales": [
       {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
       {"name": "y", "domain": [0, 2], "range": "height"}],
     "marks": [{"type": "rect", "from": {"data": "t"}, "encode": {"enter": {
       "x": {"scale": "x", "field": "c"}, "width": {"scale": "x", "band": 1},
       "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
       "fill": {"value": "black"}}}}]}
    """

  private fun scene(specification: String = chart): Scene {
    val compiled =
      SpecCompiler(textEngine = MetricTextEngine(), loader = DenyLoader).compileJson(specification)
    val complaints =
      compiled.diagnostics.filter {
        it.severity == DiagnosticSeverity.ERROR || it.severity == DiagnosticSeverity.FATAL
      }
    assertTrue(complaints.isEmpty(), "compiled with errors: ${complaints.map { it.message }}")
    return requireNotNull(compiled.scene) { "no scene" }
  }

  /**
   * The leftmost inked column and the topmost inked row of a raster, or null where nothing is
   * drawn.
   */
  private fun inkOrigin(
    scene: Scene,
    viewportOffset: VectorD = VectorD.Zero,
    viewportScale: Double = 1.0,
  ): Pair<Int, Int>? {
    val width = scene.width.toInt()
    val height = scene.height.toInt()
    val composed = ImageComposeScene(width, height, density = Density(1f, 1f))
    try {
      composed.setContent {
        VegaChart(
          scene,
          fit = SceneFit.None,
          viewportOffset = viewportOffset,
          viewportScale = viewportScale,
        )
      }
      val bytes = composed.render().encodeToData()!!.bytes
      val pixels = Image.makeFromEncoded(bytes).toComposeImageBitmap().toPixelMap()
      var left = Int.MAX_VALUE
      var top = Int.MAX_VALUE
      for (y in 0 until height) {
        for (x in 0 until width) {
          val colour = pixels[x, y]
          // Alpha first. A pan uncovers part of the canvas, and an unpainted pixel is transparent —
          // whose red, green and blue are all zero, so a naive darkness test reads it as ink and
          // reports the chart as having moved to the origin.
          if (
            colour.alpha > 0.5f && colour.red < 0.5f && colour.green < 0.5f && colour.blue < 0.5f
          ) {
            if (x < left) left = x
            if (y < top) top = y
          }
        }
      }
      return if (left == Int.MAX_VALUE) null else left to top
    } finally {
      composed.close()
    }
  }

  @Test
  fun `a pan moves the drawing by the pixels the controller accumulated`() {
    val scene = scene()
    val resting = assertNotNull(inkOrigin(scene), "nothing was drawn at all")
    val panned =
      assertNotNull(inkOrigin(scene, viewportOffset = VectorD(12.0, 7.0)), "nothing was drawn")

    // Pixels, exactly: the offset is what the controller accumulated in pixels and the drawing adds
    // it
    // to the placement rather than scaling it — the arithmetic `visibleViewport` inverts.
    assertEquals(
      resting.first + 12,
      panned.first,
      "the ink did not move by the pan: $resting $panned",
    )
    assertEquals(resting.second + 7, panned.second)
  }

  @Test
  fun `a zoom scales the drawing about the origin the controller zoomed about`() {
    val scene = scene()
    val bar = scene.flatten().first { it.node.metadata.role == "mark" }.worldBounds

    // `SceneFit.None` at density 1 draws a scene unit as a pixel, so at 2× the bar's left edge is
    // twice as far from the origin — which is what the controller's own zoom arithmetic produces
    // once
    // it has moved the offset to keep an anchor still.
    val zoomed = assertNotNull(inkOrigin(scene, viewportScale = 2.0))
    assertEquals((bar.left * 2).toInt(), zoomed.first, "the drawing was not scaled by the zoom")
  }

  @Test
  fun `a tap after a pan finds the mark that is now under the finger`() = runComposeUiTest {
    val scene = scene()
    val bar = scene.flatten().first { it.node.metadata.role == "mark" }.worldBounds
    val pan = VectorD(20.0, 0.0)
    var hit: SceneNodeId? = null
    var reported: PointD? = null
    var firstReport: PointD? = null
    setContent {
      VegaChart(
        scene,
        modifier = Modifier.size(scene.width.dp, scene.height.dp),
        fit = SceneFit.None,
        viewportOffset = pan,
        onTap = { point, nodeId ->
          if (firstReport == null) firstReport = point
          reported = point
          hit = nodeId
        },
      )
    }

    // Where the bar is *now*: its own left edge plus the pan. Tapping where it used to be must find
    // nothing, and tapping where it is must find it — which is the round trip through the same
    // placement the drawing used.
    val movedX = bar.left + pan.dx + 2.0
    onRoot().performTouchInput { click(Offset(movedX.toFloat(), bar.centerY.toFloat())) }
    assertNotNull(hit, "a tap where the bar now is found nothing: reported $reported")

    hit = null
    onRoot().performTouchInput { click(Offset(2f, bar.centerY.toFloat())) }
    assertEquals(null, hit, "a tap where the bar used to be found it anyway")

    // And the point handed to a host is the controller's space — pixels with the centring off, the
    // pan
    // still in them, because the controller subtracts its own offset when it inverts.
    // The **first** report: the second tap above overwrote the latest one.
    val point = assertNotNull(firstReport)
    assertTrue(abs(point.x - movedX) < 2.0, "$point should be the pixel that was touched")
  }

  @Test
  fun `the accessibility frames move with the chart`() = runComposeUiTest {
    val scene = scene(labelled)
    var resting = 0f
    setContent {
      VegaChart(
        scene,
        modifier = Modifier.size(scene.width.dp, scene.height.dp),
        fit = SceneFit.None,
      )
    }
    resting =
      onNodeWithContentDescription("Total: 2", substring = true).getBoundsInRoot().left.value

    setContent {
      VegaChart(
        scene,
        modifier = Modifier.size(scene.width.dp, scene.height.dp),
        fit = SceneFit.None,
        viewportOffset = VectorD(15.0, 0.0),
      )
    }
    val moved =
      onNodeWithContentDescription("Total: 2", substring = true).getBoundsInRoot().left.value

    // A reader exploring by touch has to land on the mark where it is now. Fifteen dp at density 1.
    assertEquals(resting + 15f, moved, 1.5f, "the element did not move with the drawing")
  }

  @Test
  fun `the fit is reported so a host can tell its controller the content scale`() =
    runComposeUiTest {
      val scene = scene()
      var placement: ChartPlacement? = null
      setContent {
        VegaChart(
          scene,
          // Twice the scene's size, so `Contain` fits at 2× and the reported scale is not 1 by
          // accident.
          modifier = Modifier.size((scene.width * 2).dp, (scene.height * 2).dp),
          viewportOffset = VectorD(9.0, 9.0),
          viewportScale = 3.0,
          onPlaced = { placement = it },
        )
      }
      waitForIdle()

      val placed = assertNotNull(placement, "the placement was never reported")
      assertEquals(2.0, placed.scale, 0.01, "the fit scale is what a host sets as `contentScale`")
      // **The fit alone.** The controller applies the pan and the zoom itself, so a scale carrying
      // them
      // would apply each twice — the double-inversion this whole file exists to prevent.
      assertEquals(0.0, placed.left, 0.01)
      assertEquals(0.0, placed.top, 0.01)
    }
}
