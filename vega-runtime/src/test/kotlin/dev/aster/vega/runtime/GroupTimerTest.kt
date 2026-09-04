@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.runtime

import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.runtime.interaction.ScheduledTask
import dev.aster.vega.runtime.interaction.Scheduler
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.SceneNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A timer stream declared **inside a group mark** ticks, which it never did.
 *
 * `startTimers` read `spec.signals` — the top level only — while `bindingsOf` beside it walks the
 * group marks. So a group's `{"type": "timer"}` handler was bound to the dispatcher and then never
 * dispatched to, because nothing produces a timer event except the scheduler and the scheduler had
 * never been told about it. An animation declared inside a trellis cell simply stood still, with no
 * diagnostic: the same silent shape as the group-scoped handlers that were just fixed, and found
 * while fixing them.
 *
 * **A timer is not scope-filtered**, which is the one way it differs from every other stream in a
 * group. Upstream's `parseStream` builds a timer as `scope.event(Timer, throttle)` and then
 * *replaces* the stream object with `{between, filter}` — dropping `source` — so the
 * `inScope(event.item)` filter it appends to every other scope-sourced stream is not appended to
 * this one. It has no item to be in scope of. A timer inside a group therefore fires on every tick,
 * exactly like one at the top level; what makes it the group's is only which scope its update reads
 * and writes.
 */
class GroupTimerTest {

  /** A scheduler with virtual time, so a tick is exact rather than slept for. */
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

  /** One group, one timer inside it, and a rect whose width follows the signal it sets. */
  private val timerInAGroup =
    """
    {
      "width": 200, "height": 100, "padding": 0, "autosize": "none",
      "data": [{"name": "t", "values": [{"v": 1}]}],
      "marks": [{
        "type": "group", "name": "panel",
        "signals": [{"name": "ticks", "value": 0,
                     "on": [{"events": {"type": "timer", "throttle": 100},
                             "update": "ticks + 1"}]}],
        "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                             "width": {"value": 200}, "height": {"value": 100}}},
        "marks": [{"type": "rect", "from": {"data": "t"},
                   "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                        "height": {"value": 10}},
                              "update": {"width": {"signal": "10 + ticks * 10"}}}}]
      }]
    }
    """
      .trimIndent()

  private fun ticks(scope: String) =
    controller.lastCompiled!!.groupScopes[scope]?.values?.get("ticks")

  @Test
  fun `a timer inside a group ticks`() {
    controller.setSpec(timerInAGroup)
    assertEquals(VegaValue.Num(0.0), ticks("panel"))
    assertTrue(scheduler.pending > 0, "a group's timer was never scheduled, so it can never fire")

    scheduler.advance(100)
    assertEquals(VegaValue.Num(1.0), ticks("panel"), "a group's timer did not fire")
    scheduler.advance(200)
    assertEquals(VegaValue.Num(3.0), ticks("panel"), "it fired once and then stopped repeating")
  }

  /** And the cell redraws from it, which is what an animation inside a group actually is. */
  @Test
  fun `the group's marks redraw from what its timer set`() {
    controller.setSpec(timerInAGroup)
    fun widths(): List<Double> {
      val out = mutableListOf<Double>()
      fun walk(node: SceneNode) {
        if (node is RectNode) out += node.rect.width
        if (node is GroupNode) node.children.forEach { walk(it) }
      }
      walk(controller.state.value.snapshot.scene.root)
      return out
    }

    assertEquals(listOf(10.0), widths(), "the rect did not start at its untouched width")
    scheduler.advance(200)
    assertEquals(listOf(30.0), widths(), "the group's marks did not follow its own timer")
  }

  /**
   * A **faceted** group's timer ticks once per cell, each cell counting its own.
   *
   * The same rule as every other handler in a faceted group — one live copy per cell — and worth
   * its own case because a timer reaches its cells through a different door: it is scheduled rather
   * than dispatched, so a fix that only taught `bindingsOf` about facets would leave this at one
   * shared timer writing one shared signal.
   */
  @Test
  fun `a faceted group's timer ticks once per cell`() {
    controller.setSpec(
      """
      {
        "width": 200, "height": 100, "padding": 0, "autosize": "none",
        "data": [{"name": "t", "values": [{"c": "a"}, {"c": "b"}]}],
        "scales": [{"name": "cells", "type": "band", "domain": {"data": "t", "field": "c"},
                    "range": "width"}],
        "marks": [{
          "type": "group", "name": "cell",
          "from": {"facet": {"name": "rows", "data": "t", "groupby": "c"}},
          "signals": [{"name": "ticks", "value": 0,
                       "on": [{"events": {"type": "timer", "throttle": 100},
                               "update": "ticks + 1"}]}],
          "encode": {"enter": {"x": {"scale": "cells", "field": "c"}, "y": {"value": 0},
                               "width": {"scale": "cells", "band": 1}, "height": {"value": 100}}},
          "marks": [{"type": "rect", "from": {"data": "rows"},
                     "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                          "height": {"value": 10}},
                                "update": {"width": {"signal": "10 + ticks * 10"}}}}]
        }]
      }
      """
        .trimIndent()
    )
    scheduler.advance(200)
    assertEquals(VegaValue.Num(2.0), ticks("cell/cells[0]"), "the first cell's timer did not tick")
    assertEquals(VegaValue.Num(2.0), ticks("cell/cells[1]"), "the second cell's timer did not tick")
  }

  /** A top-level timer is unaffected, so nothing was traded for this. */
  @Test
  fun `a top-level timer still ticks`() {
    controller.setSpec(
      """
      {
        "width": 100, "height": 50, "padding": 0,
        "signals": [{"name": "ticks", "value": 0,
                     "on": [{"events": {"type": "timer", "throttle": 100},
                             "update": "ticks + 1"}]}],
        "data": [{"name": "t", "values": [{"v": 1}]}],
        "marks": [{"type": "rect", "from": {"data": "t"},
                   "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                        "width": {"value": 10}, "height": {"value": 10}}}}]
      }
      """
        .trimIndent()
    )
    scheduler.advance(300)
    assertEquals(VegaValue.Num(3.0), controller.lastCompiled!!.signals["ticks"])
  }
}
