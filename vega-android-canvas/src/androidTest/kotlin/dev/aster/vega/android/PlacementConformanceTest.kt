package dev.aster.vega.android

import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.Scene
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This renderer against `test-fixtures/host-conformance/placement.txt`.
 *
 * One golden, one reader per host. This view pinned a scene to the padded top-left until #99 in
 * 0.3.0 where the other two centred it, so the same chart sat in a different place depending on the
 * host and nothing compared them — `scripts/host-parity.py` cannot, because a signature says
 * nothing about arithmetic.
 */
@RunWith(AndroidJUnit4::class)
class PlacementConformanceTest {

  private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

  private fun <T> onMainThread(block: () -> T): T {
    var result: T? = null
    var failure: Throwable? = null
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      try {
        result = block()
      } catch (error: Throwable) {
        failure = error
      }
    }
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
  }

  @Test
  fun placesASceneWhereEveryOtherRendererPlacesIt() {
    val golden =
      InstrumentationRegistry.getInstrumentation()
        .context
        .assets
        .open("placement.txt")
        .bufferedReader()
        .use { it.readText() }

    for ((case, expected) in HostConformance.cases(golden)) {
      val (sceneSize, slot) = HostConformance.placementCase(case)
      val view = onMainThread {
        val created = VegaChartView(context)
        created.controller =
          VegaChartController.fromScene(
            Scene.empty(width = sceneSize.first, height = sceneSize.second)
          )
        created.measure(
          View.MeasureSpec.makeMeasureSpec(slot.first.toInt(), View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(slot.second.toInt(), View.MeasureSpec.EXACTLY),
        )
        created.layout(0, 0, slot.first.toInt(), slot.second.toInt())
        created
      }
      val placed = view.placement()

      assertEquals(
        "for $case",
        expected,
        listOf(
          HostConformance.six(placed.scale),
          HostConformance.six(placed.left),
          HostConformance.six(placed.top),
        ),
      )
    }
  }
}
