package dev.aster.vega.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.aster.vega.fixtures.SampleScenes
import dev.aster.vega.runtime.ChartEvent
import dev.aster.vega.runtime.VegaChartController
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.flatten
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The places where a **host** turns a reader's gesture into a chart's coordinates.
 *
 * Every one of these is the same shape of defect: a point in one space handed to something that
 * reads another. `ScenePlacement` exists so there is one origin, and each test below is a dispatch
 * site that was not going through it — a pinch's focus, a screen reader's activation, a tooltip's
 * anchor, the drawn viewport's far corner. They cannot be unit tests: the arithmetic only goes
 * wrong once a real view has been laid out at a size other than the scene's own.
 */
@RunWith(AndroidJUnit4::class)
class HostSemanticsInstrumentedTest {

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

  /**
   * A view laid out **larger than its scene and asymmetrically padded**, which is the whole point.
   */
  private fun laidOut(
    controller: VegaChartController,
    width: Int = 500,
    height: Int = 300,
    padding: Int = 23,
  ) = onMainThread {
    val view = VegaChartView(context)
    view.setPadding(padding, padding, padding, padding)
    view.controller = controller
    view.measure(
      View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
    )
    view.layout(0, 0, width, height)
    view
  }

  // ---- the pinch anchor ------------------------------------------------------

  /**
   * Every point this view dispatches is **placement-relative**, including a pinch's focus.
   *
   * `ScaleGestureDetector` reports its focus in raw view coordinates, and those went straight into
   * `ChartInputEvent.Zoom` while `toPointD` took the placement's origin off every other point in
   * the file. So on a chart that is padded or centred in its slot — which is any chart given
   * `match_parent` in a slot of a different aspect ratio — a pinch zoomed about a point offset from
   * the reader's fingers by exactly that origin.
   *
   * Asserted on the conversion itself, which **exists** for this reason: there is one function now
   * and both dispatch sites go through it, where before the detector had the arithmetic written out
   * separately and wrong. Driving a real pinch is not the alternative it looks like —
   * `ScaleGestureDetector` refuses to begin below a minimum span measured in millimetres of screen,
   * so a synthesised two-pointer sequence never reaches `onScale` at all.
   */
  @Test
  fun everyDispatchedPointHasThePlacementTakenOff() {
    val controller = VegaChartController.fromScene(SampleScenes.barChart())
    val view = laidOut(controller)
    val placed = view.placement()
    assertTrue("this test needs a non-zero origin: $placed", placed.left > 0.0 && placed.top > 0.0)

    // The chart's own top-left corner is the origin of the space a controller reads.
    val atOrigin = view.placedPoint(placed.left.toFloat(), placed.top.toFloat())
    assertEquals(0.0, atOrigin.x, 0.001)
    assertEquals(0.0, atOrigin.y, 0.001)

    // And an arbitrary point is offset by the origin and by nothing else: no fit scale comes off
    // here, because the controller divides by that itself.
    val somewhere = view.placedPoint(140f, 90f)
    assertEquals(140.0 - placed.left, somewhere.x, 0.001)
    assertEquals(90.0 - placed.top, somewhere.y, 0.001)
  }

  // ---- the screen reader's activation ----------------------------------------

  /**
   * A TalkBack activation hits the mark whose frame the reader was on.
   *
   * The helper dispatched `Tap(bounds.centerX, bounds.centerY)` — **scene** coordinates — into a
   * controller that reads placement-relative view pixels and then divides by the fit scale. The two
   * agree only while the fit scale is 1 and nothing has been panned, so on a view laid out larger
   * or smaller than its scene a double-tap activated whichever mark happened to sit at the scene
   * coordinate read as a view coordinate. A reader using a screen reader has no way to see that
   * happen, which is what makes it worth an instrumented test.
   */
  @Test
  fun aScreenReaderActivationHitsTheMarkItWasOn() {
    val scene = SampleScenes.barChart(AndroidTextEngine())
    val bars =
      scene
        .flatten()
        .map { it.node }
        .filterIsInstance<RectNode>()
        .filter {
          it.metadata.markName == "bars"
        }
    assertTrue("the fixture needs several bars to tell a wrong hit from a right one", bars.size > 2)
    val controller = VegaChartController.fromScene(scene)
    // Laid out at nearly twice the scene's size, so a scene coordinate read as a view coordinate
    // lands on a different bar rather than on the same one.
    val view = laidOut(controller, width = 900, height = 700, padding = 0)
    onMainThread { controller.contentScale = view.placement().scale }
    assertNotEquals(1.0, view.placement().scale, 0.05)

    val target = bars[1]
    // The **public** provider, which is what a screen reader actually reaches: `performAction` on
    // `ExploreByTouchHelper` itself is package-private and `getVirtualViewAt` protected.
    val provider = onMainThread {
      requireNotNull(view.accessibilityHelperForTesting().getAccessibilityNodeProvider(view))
    }
    val placed = view.placement()
    // The node whose frame is where this bar is drawn. The frames are known to be right — the
    // activation point was the one thing that was not — so matching on one names the node without
    // depending on the wording of its description.
    val expected =
      android.graphics.Rect(
        (placed.left + target.rect.left * placed.scale).toInt(),
        (placed.top + target.rect.top * placed.scale).toInt(),
        (placed.left + target.rect.right * placed.scale).toInt(),
        (placed.top + target.rect.bottom * placed.scale).toInt(),
      )
    val virtualId = onMainThread {
      val root = requireNotNull(provider.createAccessibilityNodeInfo(View.NO_ID))
      (0 until root.childCount).firstOrNull { id ->
        val info = provider.createAccessibilityNodeInfo(id) ?: return@firstOrNull false
        if (info.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
          return@firstOrNull false
        }
        val frame = android.graphics.Rect()
        info.getBoundsInScreen(frame)
        abs(frame.width() - expected.width()) <= 2 &&
          abs(frame.height() - expected.height()) <= 2 &&
          abs(frame.left % 100000 - expected.left % 100000) <= 2
      }
    }
    assertTrue("no activatable virtual view matching the bar's frame", virtualId != null)

    onMainThread { provider.performAction(virtualId!!, AccessibilityNodeInfo.ACTION_CLICK, null) }
    InstrumentationRegistry.getInstrumentation().waitForIdleSync()

    assertEquals(
      "activating a mark must select that mark",
      setOf(target.id),
      controller.snapshot.interactionState.selection.nodeIds,
    )
  }

  // ---- what the drawing covers ----------------------------------------------

  /**
   * An opaque scene background paints the chart's own box, and the slack on **all four** sides is
   * left alone.
   *
   * The draw viewport's far corner was the padding box's — `width - paddingRight` — while the near
   * corner came from `placement()`, which centres. So the viewport was too large by the whole of
   * the slack, all of it on the right and the bottom: a Vega-Lite chart, which defaults to
   * `"background": "white"`, painted a white margin down two of its four sides on any dark surface,
   * and a zoomed chart's content escaped there.
   */
  @Test
  fun anOpaqueBackgroundCoversTheChartAndNotTheSlack() {
    val scene = SampleScenes.barChart().copy(background = SceneColor.parse("#ff0000"))
    val controller = VegaChartController.fromScene(scene)
    // A wide slot, so the slack is horizontal and asymmetry is visible on the left and right.
    val view = laidOut(controller, width = 900, height = 300, padding = 0)
    val placed = view.placement()
    assertTrue("this test needs horizontal slack: $placed", placed.left > 4.0)

    val bitmap = Bitmap.createBitmap(900, 300, Bitmap.Config.ARGB_8888)
    onMainThread { view.draw(Canvas(bitmap)) }

    val insideLeft = bitmap.getPixel((placed.left + 2).toInt(), 150)
    val outsideLeft = bitmap.getPixel(1, 150)
    val outsideRight = bitmap.getPixel(898, 150)
    assertEquals("the chart's own box is painted", android.graphics.Color.RED, insideLeft)
    // The two sides agree, which is the assertion: before this the right was red and the left was
    // not.
    assertEquals("the slack must be the same on both sides", outsideLeft, outsideRight)
    assertNotEquals(
      "and it must not be the chart's background",
      android.graphics.Color.RED,
      outsideLeft,
    )
  }

  // ---- keys the chart does nothing with -------------------------------------

  /**
   * TAB is not consumed, so focus can leave the chart.
   *
   * `onKeyDown` returned true for TAB, the four arrows, ESC, HOME and END while
   * `VegaChartController.dispatch` has no behaviour for a key and no event stream reaches one. So
   * the chart claimed keys it did nothing with: TAB never moved focus off it, ESC never dismissed
   * the sheet it was in, and on a television — where the d-pad *is* the keyboard — the chart could
   * be entered and not left.
   */
  @Test
  fun aKeyTheChartDoesNothingWithIsNotConsumed() {
    val controller = VegaChartController.fromScene(SampleScenes.barChart())
    val view = laidOut(controller)

    for (code in
      listOf(
        KeyEvent.KEYCODE_TAB,
        KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_MOVE_HOME,
        KeyEvent.KEYCODE_MOVE_END,
      )) {
      val consumed = onMainThread { view.onKeyDown(code, KeyEvent(KeyEvent.ACTION_DOWN, code)) }
      assertFalse("key $code must not be claimed by a chart that ignores it", consumed)
    }
  }

  // ---- the end of a gesture -------------------------------------------------

  /**
   * A pan that ends publishes `ChartEvent.ViewportChanged`, once.
   *
   * `GestureDetector` has no "scroll ended" callback, so every increment was dispatched as
   * `CHANGED` and the `ENDED` that closes the gesture was never sent by this view. The controller
   * emits `ViewportChanged` only on `ENDED` — that is what the phase is for, so a host persists or
   * announces a viewport once rather than sixty times a second — so the event never fired here.
   */
  @Test
  fun aPanThatEndsPublishesTheViewportOnce() {
    val controller = VegaChartController.fromScene(SampleScenes.barChart())
    val view = laidOut(controller)
    val changes = mutableListOf<ChartEvent.ViewportChanged>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val subscription = onMainThread {
      scope.launch {
        controller.events.collect { if (it is ChartEvent.ViewportChanged) changes += it }
      }
    }
    try {
      onMainThread {
        val start = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, 100f, 100f, 0)
        view.dispatchTouchEvent(down)
        down.recycle()
        for (step in 1..4) {
          val move =
            MotionEvent.obtain(
              start,
              start + step * 16L,
              MotionEvent.ACTION_MOVE,
              100f + step * 25f,
              100f,
              0,
            )
          view.dispatchTouchEvent(move)
          move.recycle()
        }
        val up = MotionEvent.obtain(start, start + 100, MotionEvent.ACTION_UP, 200f, 100f, 0)
        view.dispatchTouchEvent(up)
        up.recycle()
      }
      InstrumentationRegistry.getInstrumentation().waitForIdleSync()

      assertEquals("a pan that ends must publish its viewport exactly once", 1, changes.size)
    } finally {
      subscription.cancel()
    }
  }
}
