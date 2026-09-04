package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import dev.aster.vega.scene.PointD
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A signal handler's update expression can use the scale functions, which it could not.
 *
 * `SignalUpdater.HandlerScope` and `EventDispatcher.EventScope` each wrap the chart's scope to add
 * one thing — the values an earlier handler in the batch set, and `event` — and each implemented
 * `ExpressionScope` by hand, overriding `datum`, `signal`, `dataset` and the item hooks. The
 * members they did **not** name default to `VegaValue.Null`, so the wrapper silently answered null
 * for all of them: `scale`, `invert`, `domain`, `range`, `bandwidth`, `bandspace`, the whole geo
 * family, `treePath` and `treeAncestors`.
 *
 * `indata` survived, and the reason is the point of the whole thing: its default is written in
 * terms of `dataset()`, which these wrappers happened to override. Which members broke was decided
 * by which ones the author of each wrapper thought to name, and nothing anywhere records that
 * choice — a defaulted member added to the interface tomorrow would be null here again.
 *
 * The effect was that **no scale function worked inside any handler**, at the top level as much as
 * in a group, and nothing said so — `{"events": "click", "update": "invert('x', x())"}` is how
 * every pan and zoom in Vega's gallery is written, and it produced null, which arithmetic then read
 * as zero.
 *
 * Both now delegate with `by`, which is what `DataResolver`'s own wrapper already did. The lesson
 * is in the shape rather than in the members: an interface with defaulted members and a
 * hand-written wrapper is a silent hole by construction, and the only reliable fix is not to
 * hand-write it.
 */
class HandlerScaleFunctionsTest {

  private val controller = VegaChartController()

  private fun press() =
    controller.dispatch(
      ChartInputEvent.PointerDown(
        PointD(20.0, 20.0),
        pointerId = 1,
        device = PointerDevice.MOUSE,
        buttons = 1,
      )
    )

  private fun signal(name: String) = controller.lastCompiled!!.signals.values[name]

  private fun setUp(updates: String) {
    controller.setSpec(
      """
      {
        "width": 200, "height": 100, "padding": 0, "autosize": "none",
        "data": [{"name": "t", "values": [{"c": "a", "v": 1}, {"c": "b", "v": 2}]}],
        "scales": [
          {"name": "x", "type": "linear", "domain": [0, 10], "range": [0, 200]},
          {"name": "b", "type": "band", "domain": ["a", "b"], "range": [0, 100]}
        ],
        "signals": [$updates],
        "marks": [{"type": "rect", "from": {"data": "t"},
                   "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                        "width": {"value": 50}, "height": {"value": 50}}}}]
      }
      """
        .trimIndent()
    )
  }

  private fun handler(name: String, update: String) =
    """{"name": "$name", "value": null,
        "on": [{"events": "pointerdown", "update": "$update"}]}"""

  /** `invert` is the one every pan and zoom is built from, and the one this was found on. */
  @Test
  fun `invert works inside a handler`() {
    setUp(handler("picked", "invert('x', 20)"))
    press()
    assertEquals(
      VegaValue.Num(1.0),
      signal("picked"),
      "invert() inside a handler returned ${signal("picked")}; the handler's scope answers null " +
        "for every scale function it does not name",
    )
  }

  @Test
  fun `scale, domain, range and bandwidth work inside a handler`() {
    setUp(
      listOf(
          handler("scaled", "scale('x', 5)"),
          handler("dom", "domain('x')"),
          handler("rng", "range('x')"),
          handler("bw", "bandwidth('b')"),
        )
        .joinToString(", ")
    )
    press()
    assertEquals(VegaValue.Num(100.0), signal("scaled"), "scale()")
    assertEquals(
      listOf(0.0, 10.0),
      (signal("dom") as VegaValue.Arr).values.map { (it as VegaValue.Num).value },
      "domain()",
    )
    assertEquals(
      listOf(0.0, 200.0),
      (signal("rng") as VegaValue.Arr).values.map { (it as VegaValue.Num).value },
      "range()",
    )
    assertEquals(VegaValue.Num(50.0), signal("bw"), "bandwidth()")
  }

  /**
   * `indata`, which is a dataset question rather than a scale one and had the same hole.
   *
   * Asserted as truthy and falsy rather than against a literal, because upstream's `indata` answers
   * a truthy value rather than a boolean and this engine follows it.
   *
   * This one passed **before** the fix as well, which is why it is here: its default is written in
   * terms of `dataset()`, which the wrapper did override. It documents the behaviour rather than
   * guarding the change, and it is the case that shows the hole was arbitrary rather than
   * systematic.
   */
  @Test
  fun `indata works inside a handler`() {
    setUp(
      handler("found", "indata('t', 'c', 'a')") + ", " + handler("absent", "indata('t', 'c', 'z')")
    )
    press()
    val found = signal("found")
    val absent = signal("absent")
    assertTrue(
      found != VegaValue.Null && found != absent,
      "indata() inside a handler answered $found for a value that is present and $absent for one " +
        "that is not; the handler's scope is not reaching the datasets",
    )
  }

  /**
   * And in an event **filter**, which is the other hand-written wrapper.
   *
   * Worth its own case because it fails differently and worse: a filter that cannot be evaluated
   * suppresses the event, so the handler does not fire at all and there is no wrong value to notice
   * — the chart simply stops responding.
   */
  @Test
  fun `a scale function in an event filter is evaluated rather than suppressing the event`() {
    setUp(
      handler("fired", "1")
        .replace(
          """"events": "pointerdown"""",
          """"events": {"type": "pointerdown", "filter": "scale('x', 5) > 50"}""",
        )
    )
    press()
    assertEquals(
      VegaValue.Num(1.0),
      signal("fired"),
      "a filter calling scale() read it as null, so the comparison failed and the event was " +
        "suppressed — the chart stops responding and says nothing",
    )
  }

  /**
   * The guard: a filter that is genuinely false still suppresses.
   *
   * Without it, every assertion above would pass equally well for a dispatcher that had stopped
   * evaluating filters at all, which is the other way to make this test green.
   */
  @Test
  fun `a filter that is false still suppresses the event`() {
    setUp(
      handler("fired", "1")
        .replace(
          """"events": "pointerdown"""",
          """"events": {"type": "pointerdown", "filter": "scale('x', 5) > 500"}""",
        )
    )
    press()
    assertEquals(
      VegaValue.Null,
      signal("fired"),
      "a false filter let the event through, so filters are not being evaluated",
    )
  }

  /** A scale nobody declared is still null, so this did not paper over a missing name. */
  @Test
  fun `a scale that does not exist is still null`() {
    setUp(handler("missing", "scale('nope', 5)"))
    press()
    assertTrue(
      signal("missing") == VegaValue.Null,
      "an undeclared scale answered ${signal("missing")}",
    )
  }
}
