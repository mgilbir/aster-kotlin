package dev.aster.vega.runtime

import dev.aster.vega.runtime.interaction.ScheduledTask
import dev.aster.vega.runtime.interaction.Scheduler
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.TextNode
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The timer that is not an animation, run to its fixed point.
 *
 * `donut-chart-labelled` passes the differential comparison and still looks wrong in the demo: its
 * three most crowded labels are drawn on top of each other. The fixture is not lying — upstream's
 * static scene stacks them too, because what the gallery shows is a *later frame* — and the reason
 * there are frames at all is that the timer is standing in for a loop the expression language
 * cannot express. `counter` walks the labels one per tick, `p1` accumulates the running shift and
 * `p2` builds the output array by joining strings, because there is no append either.
 *
 * So this is what a scheduler bought beyond animation: a bounded loop with a termination condition
 * now runs to its end, and the labels spread. Nothing about it is verifiable against upstream —
 * `runAsync` never returns for a specification with a timer, so there is no settled reference to
 * compare with — which is exactly why the claim is pinned here instead of assumed in a note.
 */
class TimerLoopTest {

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
  }

  private fun labelPositions(controller: VegaChartController): Map<String, Double> {
    val out = LinkedHashMap<String, Double>()
    fun walk(node: SceneNode, dy: Double) {
      val at = dy + node.transform.f
      if (node is TextNode && node.metadata.markName == "labels") {
        out[node.layout.run.text] = at + node.y
      }
      if (node is GroupNode) node.children.forEach { walk(it, at) }
    }
    walk(controller.state.value.snapshot.scene.root, 0.0)
    return out
  }

  @Test
  fun `a timer standing in for a loop settles, and the stacked labels spread`() {
    val root = File(System.getProperty("user.dir")).parentFile
    val json = File(root, "test-fixtures/specs/donut-chart-labelled.vg.json").readText()
    val scheduler = FakeScheduler()
    val controller = VegaChartController(clock = { scheduler.now }, scheduler = scheduler)
    controller.setSpec(json)

    val before = labelPositions(controller)
    val crowded = listOf("United States", "France", "Germany")
    assertTrue(before.keys.containsAll(crowded), before.keys.toString())
    // Every one of the three starts at the same height, which is what the chart looks like wrong.
    assertEquals(1, crowded.map { before.getValue(it) }.toSet().size, before.toString())

    // The loop's own termination condition stops it: `counter` walks to the number of labels and
    // then holds. A thousand ticks is far more than the count and the answer stops moving long
    // before them, which is the fixed point.
    scheduler.advance(1_000)
    val settled = labelPositions(controller)
    val heights = crowded.map { settled.getValue(it) }
    assertEquals(3, heights.toSet().size, settled.toString())

    // And they are spread in the order the loop walks them rather than shuffled.
    assertEquals(heights, heights.sorted(), settled.toString())

    // Running the clock on does nothing further: the loop has reached its fixed point, which is
    // what
    // makes this a loop rather than an animation.
    scheduler.advance(5_000)
    assertEquals(settled, labelPositions(controller))
  }
}
