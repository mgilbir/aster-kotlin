package dev.aster.vega.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aster.vega.model.VegaValue
import dev.aster.vega.runtime.ForeignData
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A chart drawn from the app's own data, on a device, through the view that draws it.
 *
 * The JVM tests establish that the rows reach the compile. This establishes the thing an app
 * actually does: a specification arrives with a dataset it does not carry, the store answers later,
 * and the chart repaints — through `VegaChartView`, on the main thread, with a real
 * `AndroidTextEngine` measuring the axis. The diary surface in the adopting app is exactly this
 * shape, and the failure this guards against is a recompile that publishes a snapshot the view
 * never draws.
 */
@RunWith(AndroidJUnit4::class)
class HostDataInstrumentedTest {

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  private val awaitingData =
    """
    {
      "width": 300, "height": 150, "padding": 5,
      "data": [{"name": "diary"}],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "diary", "field": "bucket"},
         "range": "width", "padding": 0.1},
        {"name": "y", "type": "linear", "domain": {"data": "diary", "field": "v"},
         "range": "height", "zero": true}
      ],
      "axes": [{"orient": "bottom", "scale": "x", "title": "Part of day"}],
      "marks": [{"type": "rect", "from": {"data": "diary"}, "encode": {"enter": {
        "x": {"scale": "x", "field": "bucket"}, "width": {"scale": "x", "band": 1},
        "y": {"scale": "y", "field": "v"}, "y2": {"scale": "y", "value": 0},
        "fill": {"value": "#4c78a8"}}}}]
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

  private fun rows(vararg pairs: Pair<String, Double>): List<VegaValue> =
    pairs.map { (bucket, value) ->
      ForeignData.row(mapOf("bucket" to VegaValue.Str(bucket), "v" to VegaValue.Num(value)))
    }

  private fun bars(controller: VegaChartController) =
    requireNotNull(controller.snapshot.scene) { "no scene" }.flatten().count { it.node is RectNode }

  @Test
  fun aStoreThatAnswersLaterFillsTheChart() {
    val view = onMainThread { VegaChartView(context) }
    val controller = VegaChartController(textEngine = view.newCompatibleTextEngine())
    onMainThread { view.controller = controller }

    controller.setSpec(awaitingData)
    assertEquals("nothing supplied yet", 0, bars(controller))

    controller.setData("diary", rows("morning" to 3.0, "afternoon" to 5.0, "evening" to 7.0))
    assertEquals("one bar per row from the store", 3, bars(controller))

    // And the view draws what the recompile published, which is the half a unit test cannot see.
    val size = 400
    onMainThread {
      view.measure(
        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
      )
      view.layout(0, 0, size, size)
    }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    onMainThread { view.draw(Canvas(bitmap)) }

    var steelblue = 0
    for (x in 0 until size step 4) {
      for (y in 0 until size step 4) {
        if (bitmap.getPixel(x, y) == 0xFF4C78A8.toInt()) steelblue++
      }
    }
    assertTrue("the bars the host's data produced were not drawn", steelblue > 50)
  }

  @Test
  fun aTableThatArrivedBeforeTheSpecificationIsNotLost() {
    // The order an app meets in practice: the store answers while the specification is still being
    // fetched. Whichever arrives first waits for the other.
    val controller = VegaChartController(textEngine = AndroidTextEngine())
    controller.setData("diary", rows("morning" to 3.0))
    controller.setSpec(awaitingData)

    assertEquals(1, bars(controller))
  }
}
