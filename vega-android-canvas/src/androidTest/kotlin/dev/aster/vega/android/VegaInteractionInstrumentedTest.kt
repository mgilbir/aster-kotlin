package dev.aster.vega.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aster.vega.model.VegaValue
import dev.aster.vega.runtime.VegaChartController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A real touch on a real view, driving a specification's `on` handlers.
 *
 * Everything below this has JVM tests: the selector parser, the dispatcher, the updater, and the
 * controller's translation from a gesture to Vega's event names. What none of them can check is
 * that an actual `MotionEvent` reaches that translation at all — the gesture detector, the view's
 * touch handling and the content scale sit in between, and all three are Android.
 *
 * The assertion is on **pixels**, not on the signal: the point is that a finger changes what is
 * drawn.
 */
@RunWith(AndroidJUnit4::class)
class VegaInteractionInstrumentedTest {

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  private val spec =
    """
    {
      "width": 300, "height": 300, "padding": 0,
      "data": [{"name": "t", "values": [
        {"c": "a", "v": 3}, {"c": "b", "v": 7}, {"c": "c", "v": 5}
      ]}],
      "signals": [
        {"name": "picked", "value": null,
         "on": [{"events": "rect:click", "update": "datum.c"}]}
      ],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"},
         "range": "width", "padding": 0}
      ],
      "marks": [{
        "type": "rect", "from": {"data": "t"},
        "encode": {"enter": {
          "x": {"scale": "x", "field": "c"},
          "width": {"scale": "x", "band": 1},
          "y": {"value": 0},
          "height": {"value": 300},
          "fill": {"signal": "datum.c === picked ? '#e45756' : '#4c78a8'"}
        }}
      }]
    }
    """
      .trimIndent()

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

  private fun laidOutView(size: Int = 300): Pair<VegaChartView, VegaChartController> =
    onMainThread {
      val controller = VegaChartController(textEngine = AndroidTextEngine())
      controller.setSpec(spec)
      val view = VegaChartView(context)
      view.controller = controller
      view.measure(
        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
      )
      view.layout(0, 0, size, size)
      view to controller
    }

  /** A press and a release at the same place, which is what a tap is to a gesture detector. */
  private fun tap(view: VegaChartView, x: Float, y: Float) {
    onMainThread {
      val down = SystemClock.uptimeMillis()
      val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
      view.dispatchTouchEvent(press)
      press.recycle()
      val release = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0)
      view.dispatchTouchEvent(release)
      release.recycle()
    }
    // The gesture detector reports a tap from the main-thread message queue, so let it drain.
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
  }

  private fun pixels(view: VegaChartView, size: Int = 300): IntArray = onMainThread {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    // One sample from the middle of each of the three bands.
    intArrayOf(
      bitmap.getPixel(size / 6, size / 2),
      bitmap.getPixel(size / 2, size / 2),
      bitmap.getPixel(5 * size / 6, size / 2),
    )
  }

  @Test
  fun aTapOnABarSelectsItAndRedraws() {
    val (view, controller) = laidOutView()
    val before = pixels(view)
    assertEquals("all three bars start the same colour", before[0], before[1])

    tap(view, 150f, 150f)

    assertEquals(VegaValue.Str("b"), controller.lastCompiled!!.signals["picked"])
    val after = pixels(view)
    assertEquals("the untapped bars did not move", before[0], after[0])
    assertEquals("the untapped bars did not move", before[2], after[2])
    assertNotEquals("the tapped bar changed colour", before[1], after[1])
  }

  @Test
  fun tappingAnotherBarMovesTheSelection() {
    val (view, controller) = laidOutView()
    tap(view, 150f, 150f)
    tap(view, 250f, 150f)

    assertEquals(VegaValue.Str("c"), controller.lastCompiled!!.signals["picked"])
    val after = pixels(view)
    assertEquals("only one bar is selected at a time", after[0], after[1])
    assertNotEquals(after[1], after[2])
  }

  /**
   * The host scales the view; a tap must land on the mark under the finger, not off by that factor.
   */
  @Test
  fun aTapLandsCorrectlyWhenTheViewIsScaled() {
    val (view, controller) =
      onMainThread {
        val controller = VegaChartController(textEngine = AndroidTextEngine())
        controller.setSpec(spec)
        val view = VegaChartView(context)
        view.controller = controller
        // The chart is 300 wide and the view is 600, so everything is drawn at 2x.
        view.measure(
          View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 600, 600)
        view to controller
      }
    // 500px across a 600px view is the third band; at 1x it would be the second.
    tap(view, 500f, 300f)
    assertEquals(VegaValue.Str("c"), controller.lastCompiled!!.signals["picked"])
    assertTrue(controller.diagnostics.value.toString(), controller.diagnostics.value.isEmpty())
  }
}
