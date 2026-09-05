@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.runtime

import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.PointD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The **object** spelling of a nested stream: `{"stream": {...}, "between": [...]}`.
 *
 * Upstream's `parseStream` calls this a `nestedStream` — a wrapper whose `stream` says what to
 * listen to and which adds a `between`, a filter or a throttle on top of it. The selector string
 * `[a, b] > [c, d] > mousemove` is the same thing said the short way, and `NestedBetweenTest`
 * covers that half.
 *
 * This half was unimplemented, and the way it failed was the misleading part. `stream` was not a
 * key the object form knew, so the wrapper fell into the missing-`type` error and the handler was
 * dropped — reported, but as **"An event stream object needs a `type`"**, which tells an author to
 * add the one thing this form is correct to omit. A wrapper has no type because the stream it wraps
 * has it. Someone following that diagnostic would have written a stream that listens to everything.
 */
class NestedStreamObjectTest {

  private val controller = VegaChartController()

  private fun spec(events: String) =
    """
    {
      "width": 100, "height": 60, "padding": 0, "autosize": "none",
      "data": [{"name": "t", "values": [{"v": 1}]}],
      "signals": [{"name": "n", "value": 0, "on": [{"events": $events, "update": "n + 1"}]}],
      "marks": [{"type": "rect", "from": {"data": "t"},
                 "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                      "width": {"value": 40}, "height": {"value": 40}}}}]
    }
    """
      .trimIndent()

  private fun n() = controller.lastCompiled!!.signals.values["n"]

  private fun press() =
    controller.dispatch(
      ChartInputEvent.PointerDown(
        PointD(10.0, 10.0),
        pointerId = 1,
        device = PointerDevice.MOUSE,
        buttons = 1,
      )
    )

  private fun release() =
    controller.dispatch(
      ChartInputEvent.PointerUp(
        PointD(10.0, 10.0),
        pointerId = 1,
        device = PointerDevice.MOUSE,
        buttons = 0,
      )
    )

  private fun move() = controller.dispatch(ChartInputEvent.PointerMoved(PointD(12.0, 12.0)))

  /** The wrapper's `between` gates the stream it wraps, exactly as the selector string does. */
  @Test
  fun `a stream object wrapped in a between is gated by it`() {
    controller.setSpec(
      spec(
        """{"stream": {"type": "mousemove"},
            "between": [{"type": "mousedown"}, {"type": "mouseup"}]}"""
      )
    )
    move()
    assertEquals(VegaValue.Num(0.0), n(), "it fired before the latch was opened")
    press()
    move()
    assertEquals(VegaValue.Num(1.0), n(), "it did not fire with the latch open")
    release()
    move()
    assertEquals(VegaValue.Num(1.0), n(), "it kept firing after the latch closed")
  }

  /**
   * And it does **not** fire on every event, which is what it used to do.
   *
   * The regression this is really about: with `stream` unread the object became a stream with no
   * `type`, and a stream with no type matches anything. A press, a release and a move each fired
   * it.
   */
  @Test
  fun `a wrapped stream listens to its own type and no other`() {
    controller.setSpec(
      spec(
        """{"stream": {"type": "mousemove"},
            "between": [{"type": "mousedown"}, {"type": "mouseup"}]}"""
      )
    )
    press()
    assertEquals(
      VegaValue.Num(0.0),
      n(),
      "the press that opened the latch also fired the handler, so the wrapper is matching every " +
        "event type rather than the one it wraps",
    )
    move()
    assertEquals(VegaValue.Num(1.0), n())
  }

  /** `stream` is a key the object form knows, so the wrapper draws no diagnostic at all. */
  @Test
  fun `a wrapper is not reported as a malformed stream`() {
    controller.setSpec(
      spec(
        """{"stream": {"type": "mousemove"},
            "between": [{"type": "mousedown"}, {"type": "mouseup"}]}"""
      )
    )
    val reported = controller.state.value.diagnostics.map { it.message }
    assertTrue(
      reported.none { "needs a 'type'" in it },
      "a wrapper is still being told to add the one thing it is correct to omit: $reported",
    )
    assertTrue(reported.isEmpty(), "a wrapped stream object reported something: $reported")
  }

  /** A filter on the **wrapper** narrows what it wraps, which only this spelling can say. */
  @Test
  fun `a filter on the wrapper applies to the stream it wraps`() {
    controller.setSpec(
      spec(
        """{"stream": {"type": "mousemove"},
            "between": [{"type": "mousedown"}, {"type": "mouseup"}],
            "filter": "false"}"""
      )
    )
    press()
    move()
    assertEquals(
      VegaValue.Num(0.0),
      n(),
      "a filter on the wrapper was dropped, so it fired anyway",
    )
  }
}
