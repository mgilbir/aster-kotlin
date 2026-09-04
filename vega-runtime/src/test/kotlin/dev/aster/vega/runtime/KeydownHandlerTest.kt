package dev.aster.vega.runtime

import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `{"events": "keydown"}` fires, which it never did.
 *
 * `fireSignalHandlers` mapped only the pointer family, so a specification writing a `keydown`
 * handler got a chart that compiled, drew, and never updated from the keyboard — and a signal that
 * never updates looks exactly like one whose expression is wrong.
 *
 * It is a **view** stream, not a window one, which is what makes it deliverable here at all:
 * `vega-scenegraph`'s own `Events` list has `keydown` beside `pointerdown` as an event the handler
 * binds on the view element. So this is not the `window:` family that this engine reports it cannot
 * dispatch — it is an ordinary listener on the chart.
 *
 * `event.key` answers in the **DOM's** vocabulary rather than this engine's enum — `ArrowRight`,
 * not `ARROW_RIGHT` — because that is what a `KeyboardEvent` carries and what every specification
 * written for the web already reads.
 */
class KeydownHandlerTest {

  private val controller = VegaChartController()

  private fun spec(signals: String) =
    """
    {
      "width": 100, "height": 60, "padding": 0, "autosize": "none",
      "data": [{"name": "t", "values": [{"v": 1}]}],
      "signals": [$signals],
      "marks": [{"type": "rect", "from": {"data": "t"},
                 "encode": {"enter": {"x": {"value": 0}, "y": {"value": 0},
                                      "width": {"value": 40}, "height": {"value": 40}}}}]
    }
    """
      .trimIndent()

  private fun signal(name: String) = controller.lastCompiled!!.signals.values[name]

  private fun press(key: ChartKey, modifiers: Modifiers = Modifiers.None) =
    controller.dispatch(ChartInputEvent.Key(key, modifiers))

  @Test
  fun `a keydown handler fires`() {
    controller.setSpec(
      spec("""{"name": "n", "value": 0, "on": [{"events": "keydown", "update": "n + 1"}]}""")
    )
    assertEquals(VegaValue.Num(0.0), signal("n"))
    press(ChartKey.ARROW_RIGHT)
    assertEquals(VegaValue.Num(1.0), signal("n"), "a keydown handler did not fire")
    press(ChartKey.ENTER)
    assertEquals(VegaValue.Num(2.0), signal("n"), "the second key did not fire it")
  }

  /** `event.key` is the DOM's name, which is what a specification written for the web reads. */
  @Test
  fun `event key answers the browser's own names`() {
    controller.setSpec(
      spec("""{"name": "k", "value": null, "on": [{"events": "keydown", "update": "event.key"}]}""")
    )
    for ((key, name) in
      listOf(
        ChartKey.ARROW_LEFT to "ArrowLeft",
        ChartKey.ARROW_UP to "ArrowUp",
        ChartKey.ARROW_RIGHT to "ArrowRight",
        ChartKey.ARROW_DOWN to "ArrowDown",
        ChartKey.ENTER to "Enter",
        ChartKey.SPACE to " ",
        ChartKey.ESCAPE to "Escape",
        ChartKey.TAB to "Tab",
        ChartKey.HOME to "Home",
        ChartKey.END to "End",
      )) {
      press(key)
      assertEquals(VegaValue.Str(name), signal("k"), "$key reported the wrong event.key")
    }
  }

  /** `event.keyCode`, deprecated in the DOM and still read by specifications written years ago. */
  @Test
  fun `event keyCode answers the browser's own numbers`() {
    controller.setSpec(
      spec(
        """{"name": "c", "value": null,
            "on": [{"events": "keydown", "update": "event.keyCode"}]}"""
      )
    )
    press(ChartKey.ARROW_RIGHT)
    assertEquals(VegaValue.Num(39.0), signal("c"))
    press(ChartKey.ESCAPE)
    assertEquals(VegaValue.Num(27.0), signal("c"))
  }

  /** The modifier flags, so a specification can tell a plain arrow from a shifted one. */
  @Test
  fun `the modifier flags reach the expression`() {
    controller.setSpec(
      spec(
        """{"name": "s", "value": null,
            "on": [{"events": "keydown", "update": "event.shiftKey"}]}"""
      )
    )
    press(ChartKey.ARROW_RIGHT)
    assertEquals(VegaValue.Bool(false), signal("s"))
    press(ChartKey.ARROW_RIGHT, Modifiers(shift = true))
    assertEquals(VegaValue.Bool(true), signal("s"), "a shifted key did not report its modifier")
  }

  /**
   * A **filter** on the stream works too, which is how a chart picks out one key.
   *
   * The idiom a specification actually writes: one handler per key rather than a chain of
   * conditionals inside a single update.
   */
  @Test
  fun `a filter on the key selects one of them`() {
    controller.setSpec(
      spec(
        """{"name": "right", "value": 0,
            "on": [{"events": {"type": "keydown", "filter": "event.key === 'ArrowRight'"},
                    "update": "right + 1"}]}"""
      )
    )
    press(ChartKey.ARROW_LEFT)
    assertEquals(VegaValue.Num(0.0), signal("right"), "a filtered-out key fired the handler")
    press(ChartKey.ARROW_RIGHT)
    assertEquals(VegaValue.Num(1.0), signal("right"), "the filter suppressed the key it names")
  }

  /**
   * `keyup` and `keypress` are refused **by name** rather than left to never match.
   *
   * A host reports one `ChartInputEvent.Key` per press with no phase, so there is nothing here to
   * tell a release from a repeat. Producing a `keyup` for a press would be worse than producing
   * none; saying so is the only honest third option, and it names the selector that does work.
   */
  @Test
  fun `keyup and keypress are reported rather than silently never firing`() {
    for (type in listOf("keyup", "keypress")) {
      val fresh = VegaChartController()
      fresh.setSpec(
        spec("""{"name": "n", "value": 0, "on": [{"events": "$type", "update": "n + 1"}]}""")
      )
      fresh.dispatch(ChartInputEvent.Key(ChartKey.ENTER))
      assertEquals(
        VegaValue.Num(0.0),
        fresh.lastCompiled!!.signals.values["n"],
        "a '$type' handler fired, so this engine now distinguishes key phases",
      )
      val reported = fresh.state.value.diagnostics.map { it.message }
      assertTrue(
        reported.any { type in it && "keydown" in it },
        "a '$type' handler was dropped without saying what to write instead: $reported",
      )
    }
  }

  /**
   * Traversal and the handler both happen, and neither eats the other.
   *
   * The two features meet on the same event, and a reader using the keyboard should get both: the
   * focus moves *and* a specification watching `keydown` sees the press.
   */
  @Test
  fun `a key both moves focus and fires the handler`() {
    controller.setSpec(
      """
      {
        "width": 100, "height": 60, "padding": 0, "autosize": "none",
        "data": [{"name": "t", "values": [{"v": 1}, {"v": 2}]}],
        "signals": [{"name": "n", "value": 0,
                     "on": [{"events": "keydown", "update": "n + 1"}]}],
        "marks": [{"type": "rect", "from": {"data": "t"},
                   "encode": {"enter": {"x": {"field": "v"}, "y": {"value": 0},
                                        "width": {"value": 20}, "height": {"value": 20},
                                        "description": {"signal": "'bar ' + datum.v"}}}}]
      }
      """
        .trimIndent()
    )
    press(ChartKey.ARROW_RIGHT)
    assertEquals(VegaValue.Num(1.0), signal("n"), "traversal swallowed the keydown handler")
    assertTrue(
      controller.state.value.snapshot.interactionState.focusedNodeId != null,
      "the handler swallowed the traversal",
    )
  }
}
