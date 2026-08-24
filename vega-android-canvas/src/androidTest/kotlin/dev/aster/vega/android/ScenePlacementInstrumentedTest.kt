package dev.aster.vega.android

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aster.vega.fixtures.SampleScenes
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.ScenePlacement
import dev.aster.vega.scene.flatten
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the chart was drawn, told to a host.
 *
 * The Compose Multiplatform and SwiftUI charts have reported this since they existed; the two
 * `View`-based surfaces could not, because `ScenePlacement` was declared in a Compose module a
 * `View` cannot depend on. It is in `vega-scene` now.
 *
 * The other half of the change is that the view has **one** placement rather than four copies of
 * the origin — the draw's viewport, a touch's conversion, and the accessibility helper's two
 * mappings each wrote `paddingLeft` out for themselves. Two of those agreeing and one drifting is
 * how a finger lands beside the mark it looked like it hit, so the test that matters here is the
 * one asserting a touch and the report use the same numbers.
 */
@RunWith(AndroidJUnit4::class)
class ScenePlacementInstrumentedTest {

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

  private fun laidOut(size: Int, padding: Int = 0, onPlaced: ((ScenePlacement) -> Unit)? = null) =
    onMainThread {
      val view = VegaChartView(context)
      view.setPadding(padding, padding, padding, padding)
      view.onPlaced = onPlaced
      view.controller = VegaChartController.fromScene(SampleScenes.barChart())
      view.measure(
        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
      )
      view.layout(0, 0, size, size)
      view
    }

  @Test
  fun aHostIsToldWhereTheChartWasDrawn() {
    val reports = mutableListOf<ScenePlacement>()
    val view = laidOut(size = 400) { reports.add(it) }

    assertTrue("expected at least one report, got $reports", reports.isNotEmpty())
    val placed = reports.last()
    assertEquals(view.placement(), placed)
    assertTrue("a fit scale should be positive: $placed", placed.scale > 0.0)
  }

  @Test
  fun theChartIsCentredInWhateverIsLeftOver() {
    // This used to assert the padding outright, because the view pinned the chart to the padded
    // top-left where the Compose Multiplatform and SwiftUI charts centre it. It centres now, so the
    // offset is the padding **plus half the slack** along whichever axis has any.
    val scene = SampleScenes.barChart()
    val view = laidOut(size = 400, padding = 17)
    val placed = view.placement()

    val available = 400.0 - 17.0 - 17.0
    assertEquals(17.0 + (available - scene.width * placed.scale) / 2.0, placed.left, 0.5)
    assertEquals(17.0 + (available - scene.height * placed.scale) / 2.0, placed.top, 0.5)
  }

  @Test
  fun theAxisThatFitsExactlyDoesNotMove() {
    // The fit scale is the smaller of the two ratios, so one axis fills its box and has no slack.
    // That axis must sit at the padding: centring something that already fits would move it off by
    // rounding, and a chart measured at its own preferred size must be unmoved by this change.
    val scene = SampleScenes.barChart()
    val view = laidOut(size = 400)
    val placed = view.placement()

    val fills = if (scene.width * placed.scale > scene.height * placed.scale) "width" else "height"
    if (fills == "width") {
      assertEquals(0.0, placed.left, 0.5)
    } else {
      assertEquals(0.0, placed.top, 0.5)
    }
  }

  @Test
  fun aTallSlotCentresHorizontallyAndAWideOneVertically() {
    // The case the change is for: a slot of the wrong aspect ratio. One of these offsets is zero
    // and
    // the other is not, and which is which follows the slot rather than the scene.
    val tall = onMainThread {
      val view = VegaChartView(context)
      view.controller = VegaChartController.fromScene(SampleScenes.barChart())
      view.measure(
        View.MeasureSpec.makeMeasureSpec(200, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
      )
      view.layout(0, 0, 200, 600)
      view
    }
    val placed = tall.placement()

    assertTrue("a tall slot should leave vertical slack: $placed", placed.top > 0.0)
    assertEquals("and none horizontally", 0.0, placed.left, 0.5)
  }

  @Test
  fun aTapLandsOnTheMarkThePlacementSaysItIsOver() {
    // The reason the four copies of the origin were unified, and the only test here that would have
    // caught them drifting. The tap is aimed with the *reported* placement; if the view converted a
    // touch through a different origin — as it would if one of the four copies were edited and the
    // others were not — the finger would miss the bar by the padding and nothing would be selected.
    //
    // The padding is deliberately large and asymmetric to the bar, so a wrong origin cannot land on
    // it by luck.
    val scene = SampleScenes.barChart(AndroidTextEngine())
    val bar =
      scene
        .flatten()
        .map { it.node }
        .filterIsInstance<RectNode>()
        .first { it.metadata.markName == "bars" }

    val controller = VegaChartController.fromScene(scene)
    val view = onMainThread {
      val created = VegaChartView(context)
      created.setPadding(31, 31, 0, 0)
      created.controller = controller
      created.measure(
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
      )
      created.layout(0, 0, 400, 400)
      created
    }

    val placed = view.placement()
    val x = (placed.left + bar.rect.centerX * placed.scale).toFloat()
    val y = (placed.top + bar.rect.centerY * placed.scale).toFloat()

    onMainThread {
      val down = SystemClock.uptimeMillis()
      val press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
      view.dispatchTouchEvent(press)
      press.recycle()
      val release = MotionEvent.obtain(down, down + 40, MotionEvent.ACTION_UP, x, y, 0)
      view.dispatchTouchEvent(release)
      release.recycle()
    }
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    // **That bar**, not any bar. Asserting only that something was selected is not enough, which I
    // found by introducing the drift this exists to catch: an origin wrong by forty pixels still
    // landed on a *neighbouring* bar, so the weaker assertion passed and reported nothing.
    val selection = controller.snapshot.interactionState.selection
    assertEquals(
      "a tap aimed through the reported placement should have hit the bar it was aimed at",
      setOf(bar.id),
      selection.nodeIds,
    )
  }

  @Test
  fun anUnchangedPlacementIsNotReportedTwice() {
    val reports = mutableListOf<ScenePlacement>()
    val view = laidOut(size = 400) { reports.add(it) }
    val after = reports.size

    // A layout pass that changes nothing. A report per frame would be useless to a host that wants
    // to move an overlay when the chart moves.
    onMainThread {
      view.measure(
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
      )
      view.layout(0, 0, 400, 400)
    }

    assertEquals(after, reports.size)
  }

  @Test
  fun aResizeIsReported() {
    val reports = mutableListOf<ScenePlacement>()
    val view = laidOut(size = 400) { reports.add(it) }
    val before = reports.last()

    onMainThread {
      view.measure(
        View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.EXACTLY),
      )
      view.layout(0, 0, 250, 250)
    }

    assertTrue("a smaller slot should report a new placement", reports.last() != before)
    assertTrue("and a smaller fit scale", reports.last().scale < before.scale)
  }
}
