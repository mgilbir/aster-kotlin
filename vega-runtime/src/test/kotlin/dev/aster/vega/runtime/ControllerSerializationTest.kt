package dev.aster.vega.runtime

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.runtime.interaction.ScheduledTask
import dev.aster.vega.runtime.interaction.Scheduler
import dev.aster.vega.runtime.load.DataLoader
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SizeD
import dev.aster.vega.scene.VectorD
import dev.aster.vega.scene.flatten
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the controller owes a host that is calling it from more than one place.
 *
 * `VegaChartController` is the only mutable object in the engine, and it is the one a host holds:
 * everything below it is a value. So every question about ordering, staleness and repeated work
 * lands here, and each test below is one of those questions asked as a fact rather than left as a
 * comment. [VegaChartControllerTest] covers what the controller *does*; this covers what happens
 * when two callers ask at once, or when one caller asks for the same thing twice.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControllerSerializationTest {

  /**
   * Counts fetches per URL, so a test can prove work happened once rather than once per compile.
   */
  private class Counting(private val body: String) : DataLoader {
    val fetches = mutableMapOf<String, Int>()

    override fun sanitize(uri: String): String = uri

    override fun load(uri: String): String {
      fetches[uri] = (fetches[uri] ?: 0) + 1
      return body
    }
  }

  /** A scheduler that records rather than runs, so a test can see a timer being (re)started. */
  private class Counter : Scheduler {
    var scheduled = 0

    override fun schedule(
      delayMillis: Long,
      repeating: Boolean,
      action: () -> Unit,
    ): ScheduledTask {
      scheduled++
      return ScheduledTask {}
    }
  }

  private val loadedSpec =
    """
    {"width": 100, "height": 50, "padding": 0,
     "data": [{"name": "t", "url": "https://example.com/rows.json"}],
     "marks": [{"type": "rect", "name": "bars", "from": {"data": "t"},
                "encode": {"enter": {"x": {"field": "x"}, "width": {"value": 5},
                                     "y": {"value": 0}, "height": {"value": 5}}}}]}
    """

  private fun spec(width: Int) =
    """
    {"width": $width, "height": 50, "padding": 0,
     "data": [{"name": "t", "values": [{"x": 1}]}],
     "marks": [{"type": "rect", "name": "bars", "from": {"data": "t"},
                "encode": {"enter": {"x": {"field": "x"}, "width": {"value": 5},
                                     "y": {"value": 0}, "height": {"value": 5}}}}]}
    """

  // ---- staleness -------------------------------------------------------------

  /**
   * A compile that finished after a newer one started must not publish over it.
   *
   * The asynchronous entry points hold a lock, so the *compiles* are ordered; what was not ordered
   * was the publish, because each one asked "am I finished?" rather than "am I still the answer?".
   * A host that resizes while a specification is still compiling makes the two calls in that order
   * and can be left looking at the older result — the chart nobody asked for.
   */
  @Test
  fun `a later request is what a reader is left looking at`() =
    runTest(UnconfinedTestDispatcher()) {
      val controller = VegaChartController()
      val started = launch { controller.setSpecAsync(spec(width = 100), Dispatchers.Unconfined) }
      started.join()
      controller.setSpec(spec(width = 300))
      assertEquals(300.0, controller.snapshot.scene.width)
    }

  /** The same rule across entry points: whichever asked last is the one on screen. */
  @Test
  fun `the newest request wins whichever entry point asked`() = runTest {
    val controller = VegaChartController()
    controller.setSpec(spec(width = 100))
    val first = controller.snapshot.revision

    controller.setSpecAsync(spec(width = 200), Dispatchers.Unconfined)
    assertEquals(200.0, controller.snapshot.scene.width)
    assertTrue(controller.snapshot.revision > first)

    // A container size is a compile input too, and takes a generation for the same reason.
    controller.setContainerSizeAsync(SizeD(400.0, 80.0), Dispatchers.Unconfined)
    assertNotNull(controller.snapshot.scene)
  }

  // ---- repeated work ---------------------------------------------------------

  /**
   * Every interaction recompiles, and a compile resolves every dataset from scratch — so without a
   * cache in front of the loader a tap issued a blocking GET, on the dispatching thread, with the
   * loader's own timeouts. This is the fact that makes recompiling on every event affordable.
   */
  @Test
  fun `a url is fetched once per document, not once per compile`() {
    val loader = Counting("""[{"x": 1}, {"x": 2}]""")
    val controller = VegaChartController(loader = loader)
    controller.setSpec(loadedSpec)
    assertEquals(1, loader.fetches["https://example.com/rows.json"])

    // Recompiles from other inputs: a container size, host data.
    controller.containerSize = SizeD(200.0, 100.0)
    controller.hostData = mapOf("other" to listOf(VegaValue.Num(1.0)))
    assertEquals(1, loader.fetches["https://example.com/rows.json"])

    // A **new document** is a new decision, and re-reads: what is behind the URL may have moved,
    // and the host said as much by handing over a specification again.
    controller.setSpec(loadedSpec)
    assertEquals(2, loader.fetches["https://example.com/rows.json"])
  }

  // ---- the stop latch --------------------------------------------------------

  /**
   * `stop()` has to stay stopped.
   *
   * It cancelled the timers, and then the next publish started them again — any publish, and a host
   * that keeps feeding `hostData` to a view it has torn down publishes constantly. So a chart the
   * host had explicitly stopped went on ticking, with a repaint attached to every tick.
   */
  @Test
  fun `stop outlasts a later publish, and a new document lifts it`() {
    val timed =
      """
      {"width": 100, "height": 50, "padding": 0,
       "signals": [{"name": "t", "value": 0,
                    "on": [{"events": {"type": "timer", "throttle": 50}, "update": "t + 1"}]}],
       "data": [{"name": "d", "values": [{"x": 1}]}],
       "marks": []}
      """
    val scheduler = Counter()
    val controller = VegaChartController(scheduler = scheduler)
    controller.setSpec(timed)
    assertTrue(scheduler.scheduled > 0, "a timer stream must schedule something")

    controller.stop()
    val afterStop = scheduler.scheduled
    // A publish that is not a new document must not restart what the host stopped.
    controller.hostData = mapOf("other" to listOf(VegaValue.Num(1.0)))
    assertEquals(afterStop, scheduler.scheduled)

    // A new document is a new decision, so the latch clears.
    controller.setSpec(timed)
    assertTrue(scheduler.scheduled > afterStop, "a new specification restarts the timers")
  }

  // ---- what a mark reports ---------------------------------------------------

  /**
   * A tooltip a specification did not ask for is absent, not the datum.
   *
   * The encoder fell back to the whole bound row when no `tooltip` channel was written, so every
   * mark on every chart had a tooltip and it held whatever the dataset held — which on a chart
   * drawn from an internal table is that table, rendered on hover, on screen.
   */
  @Test
  fun `no tooltip channel means no tooltip`() {
    val controller = VegaChartController()
    controller.setSpec(
      """
      {"width": 100, "height": 50, "padding": 0,
       "data": [{"name": "t", "values": [{"x": 1, "secret": "employee-4711"}]}],
       "marks": [{"type": "rect", "name": "bars", "from": {"data": "t"},
                  "encode": {"enter": {"x": {"value": 1}, "width": {"value": 5},
                                       "y": {"value": 0}, "height": {"value": 5}}}}]}
      """
    )
    assertNull(
      bars(controller.snapshot.scene).first().metadata.tooltip,
      "an unasked-for tooltip must not carry the row",
    )

    // Asked for, it appears.
    controller.setSpec(
      """
      {"width": 100, "height": 50, "padding": 0,
       "data": [{"name": "t", "values": [{"x": 1, "label": "shown"}]}],
       "marks": [{"type": "rect", "name": "bars", "from": {"data": "t"},
                  "encode": {"enter": {"x": {"value": 1}, "width": {"value": 5},
                                       "y": {"value": 0}, "height": {"value": 5},
                                       "tooltip": {"field": "label"}}}}]}
      """
    )
    assertEquals(
      VegaValue.Str("shown"),
      bars(controller.snapshot.scene).first().metadata.tooltip,
    )
  }

  // ---- gestures that carry no number -----------------------------------------

  /**
   * A gesture whose numbers are not numbers is refused rather than applied.
   *
   * A NaN reaching the pan offset or the zoom anchor poisons every coordinate derived from it, and
   * the failure then surfaces three layers away as a chart that draws nothing. Platform gesture
   * recognisers do produce these: a fling whose velocity divides by a zero time delta, a pinch
   * whose two pointers land on one pixel.
   */
  @Test
  fun `a gesture with a non-finite number is refused`() {
    val controller = VegaChartController()
    controller.setSpec(spec(width = 200))
    val before = controller.snapshot.interactionState

    controller.dispatch(ChartInputEvent.Pan(VectorD(Double.NaN, 0.0), GesturePhase.CHANGED))
    controller.dispatch(
      ChartInputEvent.Zoom(2.0, PointD(Double.POSITIVE_INFINITY, 0.0), GesturePhase.CHANGED)
    )

    assertEquals(before, controller.snapshot.interactionState, "neither gesture may take effect")
    assertTrue(
      controller.diagnostics.value.any { it.code == DiagnosticCodes.INTERACTION_UNSUPPORTED },
      controller.diagnostics.value.toString(),
    )
  }

  private fun bars(scene: Scene): List<RectNode> =
    scene
      .flatten()
      .map { it.node }
      .filterIsInstance<RectNode>()
      .filter {
        it.metadata.markName == "bars"
      }
}
