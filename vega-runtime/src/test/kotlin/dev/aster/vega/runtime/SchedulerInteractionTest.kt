package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.runtime.interaction.ScheduledTask
import dev.aster.vega.runtime.interaction.Scheduler
import dev.aster.vega.scene.PointD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The two constructs that need a clock: a `debounce` and a timer stream.
 *
 * Driven by a scheduler with **virtual time** rather than a real one, which is the whole reason the
 * capability is handed in rather than owned: a test advances the clock by hand and the result is
 * exact, where sleeping would be slow and flaky in the same breath. The controller's own `clock` is
 * moved alongside it, so what a handler reads agrees with what fired it.
 */
class SchedulerInteractionTest {

  /** A scheduler that runs nothing until the test says the time has come. */
  private class FakeScheduler : Scheduler {
    private class Task(var due: Long, val interval: Long?, val action: () -> Unit) {
      var cancelled = false
    }

    private val tasks = mutableListOf<Task>()
    var now: Long = 0L
      private set

    override fun schedule(
      delayMillis: Long,
      repeating: Boolean,
      action: () -> Unit,
    ): ScheduledTask {
      val task = Task(now + delayMillis, if (repeating) delayMillis else null, action)
      tasks += task
      return ScheduledTask { task.cancelled = true }
    }

    /** Advances the clock, running whatever comes due, in the order it comes due. */
    fun advance(millis: Long) {
      val until = now + millis
      while (true) {
        val next = tasks.filter { !it.cancelled && it.due <= until }.minByOrNull { it.due } ?: break
        now = next.due
        if (next.interval != null) next.due = now + next.interval else tasks.remove(next)
        next.action()
      }
      now = until
    }

    val pending: Int
      get() = tasks.count { !it.cancelled }
  }

  private val scheduler = FakeScheduler()

  private val controller = VegaChartController(clock = { scheduler.now }, scheduler = scheduler)

  private val moves =
    """
    {
      "width": 100, "height": 50, "padding": 0,
      "signals": [
        {"name": "settled", "value": 0,
         "on": [{"events": "mousemove{, 200}", "update": "settled + 1"}]}
      ],
      "data": [{"name": "t", "values": [{"v": 1}]}],
      "marks": [{"type": "rect", "from": {"data": "t"},
        "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
          "width": {"value": 10}, "height": {"value": 10}}}}]
    }
    """
      .trimIndent()

  /**
   * A debounce fires **once**, after the quiet period, however many events arrived.
   *
   * Upstream's is a plain trailing-edge debounce — each event cancels the pending run and schedules
   * another with itself — so four hundred moves during a drag produce one update at the end.
   * Without a scheduler this engine fired on every one of them and said so, which is the opposite
   * edge.
   */
  @Test
  fun `a debounced handler fires once after the quiet period`() {
    controller.setSpec(moves)
    repeat(5) { controller.dispatch(ChartInputEvent.PointerMoved(PointD(5.0, 5.0))) }

    // Still nothing: every move pushed the wait back, and none of them has come due.
    assertEquals(VegaValue.Num(0.0), controller.lastCompiled!!.signals["settled"])
    assertEquals(1, scheduler.pending, "the five moves should leave one pending run, not five")

    scheduler.advance(199)
    assertEquals(VegaValue.Num(0.0), controller.lastCompiled!!.signals["settled"])

    scheduler.advance(1)
    assertEquals(VegaValue.Num(1.0), controller.lastCompiled!!.signals["settled"])

    // And a later move starts a fresh wait rather than reusing the one that fired.
    controller.dispatch(ChartInputEvent.PointerMoved(PointD(6.0, 6.0)))
    scheduler.advance(200)
    assertEquals(VegaValue.Num(2.0), controller.lastCompiled!!.signals["settled"])
  }

  /** With no scheduler the stream fires on every event, and the dispatcher says why. */
  @Test
  fun `without a scheduler a debounce is reported and fires eagerly`() {
    val plain = VegaChartController(clock = { 0L })
    plain.setSpec(moves)
    plain.dispatch(ChartInputEvent.PointerMoved(PointD(5.0, 5.0)))
    plain.dispatch(ChartInputEvent.PointerMoved(PointD(6.0, 6.0)))
    assertEquals(VegaValue.Num(2.0), plain.lastCompiled!!.signals["settled"])
    assertTrue(
      plain.state.value.diagnostics.any { it.message.contains("needs a scheduler") },
      plain.state.value.diagnostics.toString(),
    )
  }

  /**
   * A timer stream fires with nothing to prompt it, at the interval it asked for.
   *
   * The event carries what upstream's does — a wall-clock `timestamp` and the `elapsed` since the
   * timer started — which is what an animation reads to know where it is. The specification below
   * is the shape `donut-chart-labelled` uses: a counter driven to a fixed point by a clock, because
   * the expression language has no loop.
   */
  @Test
  fun `a timer stream ticks until its own condition stops it`() {
    controller.setSpec(
      """
      {
        "width": 100, "height": 50, "padding": 0,
        "signals": [
          {"name": "counter", "value": 0,
           "on": [{"events": {"type": "timer", "throttle": 100},
                   "update": "counter < 3 ? counter + 1 : counter"}]},
          {"name": "ran", "value": 0,
           "on": [{"events": {"type": "timer", "throttle": 100}, "update": "event.elapsed"}]}
        ],
        "data": [{"name": "t", "values": [{"v": 1}]}],
        "marks": [{"type": "rect", "from": {"data": "t"},
          "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
            "width": {"signal": "10 + counter * 10"}, "height": {"value": 10}}}}]
      }
      """
        .trimIndent()
    )
    assertEquals(VegaValue.Num(0.0), controller.lastCompiled!!.signals["counter"])

    scheduler.advance(100)
    assertEquals(VegaValue.Num(1.0), controller.lastCompiled!!.signals["counter"])
    assertEquals(VegaValue.Num(100.0), controller.lastCompiled!!.signals["ran"])

    scheduler.advance(250)
    // Three ticks in, and the handler's own guard holds it there however long the clock runs.
    assertEquals(VegaValue.Num(3.0), controller.lastCompiled!!.signals["counter"])
    scheduler.advance(1000)
    assertEquals(VegaValue.Num(3.0), controller.lastCompiled!!.signals["counter"])
    // The last tick before 1350ms is the one at 1300, and `elapsed` is what that tick saw.
    assertEquals(VegaValue.Num(1300.0), controller.lastCompiled!!.signals["ran"])

    // And a chart that goes away stops ticking.
    controller.stop()
    assertEquals(0, scheduler.pending)
  }
}
