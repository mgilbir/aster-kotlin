package dev.aster.vega.runtime.interaction

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Something that can run a block later.
 *
 * The one capability the interaction chain lacks, and the only reason two of Vega's constructs were
 * reported rather than honoured: a `debounce` fires *after* a quiet period, and a timer stream
 * fires on its own with nothing to prompt it. Both need a way to be woken, which a compiler that is
 * a pure function of its specification has no business owning — so it is handed in.
 *
 * A host that passes none loses nothing it had: a debounce still fires on every matching event, a
 * timer still does not fire, and both still say so. That default is deliberate. A chart is a pure
 * function of its specification here, which is what makes it comparable against upstream at all,
 * and a clock quietly turning up inside the engine would end that.
 *
 * Deliberately not a `CoroutineDispatcher` or anything else with a platform behind it. The core is
 * portable Kotlin and this is the seam where "later" is defined, so a host on Android can drive it
 * from a lifecycle-aware scope, a test can drive it from virtual time, and neither needs the
 * other's machinery.
 */
public fun interface Scheduler {

  /**
   * Runs [action] after [delayMillis], once or over and over.
   *
   * @return a handle that stops it. Cancelling one that has already run, or twice, does nothing.
   */
  public fun schedule(delayMillis: Long, repeating: Boolean, action: () -> Unit): ScheduledTask
}

/** A pending or repeating run, and the way to stop it. */
public fun interface ScheduledTask {
  public fun cancel()
}

/**
 * A [Scheduler] driven by coroutines, which is as portable as the rest of the core.
 *
 * `delay` is multiplatform, so nothing here is JVM- or Android-only; the host supplies the scope,
 * and so decides the lifetime. On Android that is the view's — a timer that outlives the view it
 * draws into is a leak with a repaint attached to it.
 *
 * Timing is by [kotlinx.coroutines.delay], which schedules relative to now rather than to a fixed
 * grid, so a repeating task drifts by however long the work takes. That is what upstream's
 * `d3.interval` does too, and for a chart it is the right trade: an animation that skipped a frame
 * should carry on from where it is rather than fire twice to catch up.
 */
public class CoroutineScheduler(private val scope: CoroutineScope) : Scheduler {

  override fun schedule(
    delayMillis: Long,
    repeating: Boolean,
    action: () -> Unit,
  ): ScheduledTask {
    val interval = delayMillis.coerceAtLeast(0)
    val job: Job = scope.launch {
      do {
        delay(interval)
        if (!isActive) break
        action()
      } while (repeating && isActive)
    }
    return ScheduledTask { job.cancel() }
  }
}
