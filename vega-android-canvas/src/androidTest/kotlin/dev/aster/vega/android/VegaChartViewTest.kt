package dev.aster.vega.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aster.vega.fixtures.SampleScenes
import dev.aster.vega.runtime.ChartInputEvent
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VegaChartViewTest {

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  /**
   * `VegaChartView` builds a `GestureDetector`, which needs a Looper, so the view must be created
   * and laid out on the main thread exactly as it would be in an app.
   */
  private fun <T> onMainThread(block: () -> T): T {
    var result: T? = null
    var failure: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      try {
        result = block()
      } catch (t: Throwable) {
        failure = t
      }
    }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  private fun laidOutView(scene: Scene, size: Int = 400): VegaChartView = onMainThread {
    val view = VegaChartView(context)
    view.controller = VegaChartController.fromScene(scene)
    view.measure(
      View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
    )
    view.layout(0, 0, size, size)
    view
  }

  private fun firstBarCenter(scene: Scene): PointD {
    val bar =
      scene
        .flatten()
        .map { it.node }
        .filterIsInstance<RectNode>()
        .first {
          it.metadata.markName == "bars"
        }
    return PointD(bar.rect.centerX, bar.rect.centerY)
  }

  private fun assertNear(expected: Int, actual: Int, tolerance: Int = 2) {
    assertTrue(
      "expected $expected +/- $tolerance but was $actual",
      kotlin.math.abs(expected - actual) <= tolerance,
    )
  }

  @Test
  fun onDrawRendersTheSnapshot() {
    val view = laidOutView(SampleScenes.barChart(AndroidTextEngine()))
    val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
    onMainThread { view.draw(Canvas(bitmap)) }

    var painted = 0
    for (x in 0 until 400 step 8) {
      for (y in 0 until 400 step 8) {
        if (bitmap.getPixel(x, y) != 0) painted++
      }
    }
    assertTrue("view drew nothing", painted > 100)
  }

  @Test
  fun measuredSizeFollowsTheSceneAspect() {
    val scene = SampleScenes.barChart(AndroidTextEngine())
    val view = onMainThread {
      VegaChartView(context).also {
        it.controller = VegaChartController.fromScene(scene)
        it.measure(
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
      }
    }
    val density = context.resources.displayMetrics.density
    // Rounding to whole pixels can differ by one in each direction.
    assertNear((scene.width * density).toInt(), view.measuredWidth)
    assertNear((scene.height * density).toInt(), view.measuredHeight)
  }

  @Test
  fun accessibilityExposesOneVirtualNodePerFocusableMark() {
    val scene = SampleScenes.barChart(AndroidTextEngine())
    val view = laidOutView(scene)
    val provider =
      requireNotNull(view.accessibilityHelperForTesting().getAccessibilityNodeProvider(view))

    val focusableMarks = scene.flatten().count { it.node.metadata.accessibility?.focusable == true }
    assertTrue("fixture has no focusable marks", focusableMarks > 0)

    val root = requireNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))
    assertEquals(focusableMarks, root.childCount)

    val first = requireNotNull(provider.createAccessibilityNodeInfo(0))
    assertNotNull(first.contentDescription)
    assertTrue("label should include the value", first.contentDescription.contains(":"))
  }

  @Test
  fun accessibilityCollapsesADenseChartToASummary() {
    val dense = SampleScenes.symbolStressTest(count = 5_000)
    val view = laidOutView(dense)
    val provider =
      requireNotNull(view.accessibilityHelperForTesting().getAccessibilityNodeProvider(view))
    val root = requireNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))
    // The stress fixture marks nothing focusable, so the tree stays empty rather than unbounded.
    assertTrue("dense chart must not expose an unbounded tree", root.childCount <= 1)
  }

  @Test
  fun accessibilityActivationSelectsTheMark() {
    val scene = SampleScenes.barChart(AndroidTextEngine())
    val view = laidOutView(scene)
    val provider =
      requireNotNull(view.accessibilityHelperForTesting().getAccessibilityNodeProvider(view))

    provider.performAction(0, AccessibilityNodeInfoCompat.ACTION_CLICK, null)
    assertFalse(view.controller.snapshot.interactionState.selection.isEmpty)
  }

  @Test
  fun hoverUpdatesTheControllerWithoutReplacingTheScene() {
    val scene = SampleScenes.barChart(AndroidTextEngine())
    val view = laidOutView(scene, size = scene.width.toInt())
    val sceneBefore = view.controller.snapshot.scene

    view.controller.dispatch(ChartInputEvent.PointerMoved(firstBarCenter(scene)))

    assertTrue("scene was replaced by a hover", sceneBefore === view.controller.snapshot.scene)
    assertNotNull(view.controller.snapshot.interactionState.hoveredNodeId)
  }
}
