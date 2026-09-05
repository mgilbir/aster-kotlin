package dev.aster.vega.runtime.interaction

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.EventConfig
import dev.aster.vega.model.spec.EventPermit
import dev.aster.vega.model.spec.EventSelector
import dev.aster.vega.model.spec.EventStream
import dev.aster.vega.model.spec.SignalHandler
import dev.aster.vega.model.spec.SignalUpdate
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which handlers an event fires.
 *
 * Time is passed in rather than read from a clock, so a throttle is testable at all — a dispatcher
 * that consulted the wall clock could only be checked by sleeping.
 */
class EventDispatcherTest {

  private val diagnostics = DiagnosticCollector()

  private val emptyScope =
    object : ExpressionScope {
      override val datum: VegaValue = VegaValue.Null

      override fun signal(name: String): VegaValue = VegaValue.Null

      override fun dataset(name: String): List<VegaValue> = emptyList()
    }

  private fun binding(signal: String, selector: String) =
    HandlerBinding(
      signal,
      SignalHandler(
        streams = EventSelector.parse(selector),
        update = SignalUpdate.Expression("1"),
      ),
    )

  private fun dispatcher(vararg bindings: HandlerBinding) = dispatcher(EventConfig(), *bindings)

  private fun dispatcher(events: EventConfig, vararg bindings: HandlerBinding) =
    EventDispatcher(
      bindings.toList(),
      CachingExpressionCompiler(VegaExpressionCompiler()),
      diagnostics,
      emptyScope,
      events,
    )

  private fun event(
    type: String,
    at: Long = 0,
    markType: String? = null,
    markName: String? = null,
    source: String = EventStream.SOURCE_VIEW,
    properties: Map<String, VegaValue> = emptyMap(),
  ) =
    InputEvent(
      type,
      at,
      source = source,
      markType = markType,
      markName = markName,
      properties = properties,
    )

  private fun fired(dispatcher: EventDispatcher, event: InputEvent): String =
    dispatcher.dispatch(event).joinToString(",") { it.signalName }

  @Test
  fun `an event fires the handlers whose type it matches`() {
    val d = dispatcher(binding("a", "click"), binding("b", "mousemove"), binding("c", "click"))
    assertEquals("a,c", fired(d, event("click")))
    assertEquals("b", fired(d, event("mousemove")))
    assertEquals("", fired(d, event("mouseup")))
  }

  /** A mark selector needs the event to have landed on a mark, and on the right one. */
  @Test
  fun `mark type and name narrow the match`() {
    val d =
      dispatcher(
        binding("anyMark", "*:click"),
        binding("rects", "rect:click"),
        binding("named", "@legend:click"),
        binding("view", "click"),
      )
    assertEquals("view", fired(d, event("click")))
    assertEquals("anyMark,rects,view", fired(d, event("click", markType = "rect")))
    assertEquals("anyMark,view", fired(d, event("click", markType = "symbol")))
    assertEquals(
      "anyMark,named,view",
      fired(d, event("click", markType = "symbol", markName = "legend")),
    )
  }

  /** A stream on the window does not see a view event, and the other way round. */
  @Test
  fun `the source must match`() {
    val d = dispatcher(binding("w", "window:mousemove"), binding("v", "mousemove"))
    assertEquals("v", fired(d, event("mousemove")))
    assertEquals(
      "w",
      fired(
        d,
        InputEvent("mousemove", 0, source = dev.aster.vega.model.spec.EventStream.SOURCE_WINDOW),
      ),
    )
  }

  /** Filters read `event.something`; an absent property is null, which is falsy. */
  @Test
  fun `a filter expression gates the handler`() {
    val d = dispatcher(binding("shifted", "click[event.shiftKey]"))
    assertEquals("", fired(d, event("click")))
    assertEquals(
      "",
      fired(d, event("click", properties = mapOf("shiftKey" to VegaValue.Bool(false)))),
    )
    assertEquals(
      "shifted",
      fired(d, event("click", properties = mapOf("shiftKey" to VegaValue.Bool(true)))),
    )
  }

  @Test
  fun `stacked filters all have to hold`() {
    val d = dispatcher(binding("both", "click[event.shiftKey][event.altKey]"))
    assertEquals(
      "",
      fired(d, event("click", properties = mapOf("shiftKey" to VegaValue.Bool(true)))),
    )
    assertEquals(
      "both",
      fired(
        d,
        event(
          "click",
          properties = mapOf("shiftKey" to VegaValue.Bool(true), "altKey" to VegaValue.Bool(true)),
        ),
      ),
    )
  }

  /**
   * A broken filter **suppresses** the event rather than being ignored. Treating it as absent would
   * fire the handler on every event of that type, which is the loudest possible way to be wrong.
   */
  @Test
  fun `a filter that cannot be read stops the handler and is reported`() {
    val d =
      dispatcher(
        HandlerBinding(
          "s",
          SignalHandler(
            streams = EventSelector.parse("click[event.]"),
            update = SignalUpdate.Expression("1"),
          ),
        )
      )
    assertEquals("", fired(d, event("click")))
    assertTrue(
      diagnostics.diagnostics.any { it.message.contains("no event will pass it") },
      diagnostics.diagnostics.toString(),
    )
  }

  /**
   * The form a drag is written in. The pair is a **latch**: the first stream opens it, the second
   * closes it, and the gated stream fires while it is open. Nothing queues, so a `mouseup` that
   * never arrives leaves the gate open — which is what lets a drag survive the pointer leaving the
   * chart, and equally why a lost release leaves it stuck.
   */
  @Test
  fun `a between pair latches the stream between its two events`() {
    val d = dispatcher(binding("drag", "[mousedown, mouseup] > mousemove"))
    assertEquals("", fired(d, event("mousemove", at = 0)))
    assertEquals("", fired(d, event("mousedown", at = 1)))
    assertEquals("drag", fired(d, event("mousemove", at = 2)))
    assertEquals("drag", fired(d, event("mousemove", at = 3)))
    assertEquals("", fired(d, event("mouseup", at = 4)))
    assertEquals("", fired(d, event("mousemove", at = 5)))
    // And it re-arms.
    assertEquals("", fired(d, event("mousedown", at = 6)))
    assertEquals("drag", fired(d, event("mousemove", at = 7)))
  }

  /**
   * When one event both closes a gate and would fire the stream it gates, the gate wins.
   *
   * Upstream's ordering here falls out of the order streams happened to be registered and is not
   * stated anywhere; this fixes it, so the behaviour does not depend on how the specification was
   * written.
   */
  @Test
  fun `a gate closes before the stream it gates is tested`() {
    val d = dispatcher(binding("x", "[mousedown, click] > click"))
    assertEquals("", fired(d, event("mousedown", at = 0)))
    assertEquals("", fired(d, event("click", at = 1)))
  }

  /** A throttle drops anything arriving sooner than its interval since the last fire. */
  @Test
  fun `a throttle spaces out the firings`() {
    val d = dispatcher(binding("s", "mousemove{100}"))
    assertEquals("s", fired(d, event("mousemove", at = 1000)))
    assertEquals("", fired(d, event("mousemove", at = 1050)))
    assertEquals("", fired(d, event("mousemove", at = 1099)))
    assertEquals("s", fired(d, event("mousemove", at = 1100)))
    assertEquals("s", fired(d, event("mousemove", at = 5000)))
  }

  /** A `!` consumes the event, so nothing registered after it sees it. */
  @Test
  fun `a consumed event stops the streams behind it`() {
    val d = dispatcher(binding("mark", "rect:click!"), binding("view", "click"))
    assertEquals("mark", fired(d, event("click", markType = "rect")))
    // Off the mark, the consuming stream does not match, so the view handler still fires.
    assertEquals("view", fired(d, event("click")))
  }

  /** A debounce needs something that can wake up later; it is reported rather than approximated. */
  @Test
  fun `a debounce is reported as needing a scheduler`() {
    dispatcher(binding("s", "mousemove{, 200}"))
    assertTrue(
      diagnostics.diagnostics.any { it.message.contains("needs a scheduler") },
      diagnostics.diagnostics.toString(),
    )
  }

  /**
   * A `between` wrapping another one is dispatched, gated by **both** latches.
   *
   * This asserted the opposite, on the grounds that honouring it means gating a gate and that the
   * ordering could not be guessed at. There is no ordering: `vega-dataflow`'s `between(a, b)` is a
   * boolean set true by `a` and false by `b`, plus a filter on it, and a nested pair composes as
   * `stream.between(c, d).between(a, b)` — so an event fires when the innermost stream matches and
   * every latch is open, each latch minding only its own pair. `NestedBetweenTest` covers it in
   * full; this keeps the case in the class that used to refuse it.
   */
  @Test
  fun `a nested between is gated by every latch in the chain`() {
    val d = dispatcher(binding("s", "[keydown, keyup] > [mousedown, mouseup] > mousemove"))
    assertEquals("", fired(d, event("mousemove")), "it fired with both latches shut")
    d.dispatch(event("keydown"))
    assertEquals("", fired(d, event("mousemove")), "one latch was enough")
    d.dispatch(event("mousedown"))
    assertEquals("s", fired(d, event("mousemove")), "both latches are open and it did not fire")
    assertTrue(
      diagnostics.diagnostics.none { it.message.contains("wrapping another") },
      diagnostics.diagnostics.toString(),
    )
  }

  /**
   * `config.events` refuses the listener rather than the event.
   *
   * A host that embeds a chart it did not write says `{"window": false}` to keep it from watching
   * the pointer across the whole page. The rest of the chart still works, which is why this is a
   * warning and not a failure — and the view listener below proves the refusal is narrow.
   */
  @Test
  fun `a blocked source does not get a listener`() {
    val d =
      dispatcher(
        EventConfig(window = EventPermit.All(false)),
        binding("windowed", "window:mousemove"),
        binding("viewed", "mousemove"),
      )
    assertEquals("viewed", fired(d, event("mousemove", source = EventStream.SOURCE_VIEW)))
    assertEquals("", fired(d, event("mousemove", source = EventStream.SOURCE_WINDOW)))
    assertTrue(
      diagnostics.diagnostics.any {
        it.message.startsWith("Blocked window mousemove event listener")
      },
      diagnostics.diagnostics.toString(),
    )
  }

  /** A list names the types that are allowed, so one it does not name is refused. */
  @Test
  fun `a type list is an allow-list`() {
    val d =
      dispatcher(
        EventConfig(view = EventPermit.Types(setOf("click"))),
        binding("clicked", "click"),
        binding("moved", "mousemove"),
      )
    assertEquals("clicked", fired(d, event("click")))
    assertEquals("", fired(d, event("mousemove")))
  }

  /** A `scope` stream listens on the view, so the view rule governs it. */
  @Test
  fun `a scope stream is governed by the view rule`() {
    val d =
      dispatcher(
        EventConfig(view = EventPermit.All(false)),
        HandlerBinding(
          "s",
          SignalHandler(
            streams = EventSelector.parse("click", EventStream.SOURCE_SCOPE),
            update = SignalUpdate.Expression("1"),
          ),
        ),
      )
    assertEquals("", fired(d, event("click", source = EventStream.SOURCE_SCOPE)))
    assertTrue(
      diagnostics.diagnostics.any { it.message.startsWith("Blocked view click event listener") },
      diagnostics.diagnostics.toString(),
    )
  }

  /** A timer fires on its own, which needs a clock this dispatcher does not have. */
  @Test
  fun `a timer stream is reported as needing a clock`() {
    val d = dispatcher(binding("s", "timer{500}"))
    assertEquals("", fired(d, event("timer")))
    assertTrue(
      diagnostics.diagnostics.any { it.message.contains("needs a clock") },
      diagnostics.diagnostics.toString(),
    )
  }

  /**
   * A handler with no event streams — driven by another signal changing — never fires from input.
   */
  @Test
  fun `a signal-driven handler is not fired by an event`() {
    val d =
      dispatcher(
        HandlerBinding(
          "s",
          SignalHandler(signalSources = listOf("width"), update = SignalUpdate.Expression("1")),
        )
      )
    assertEquals("", fired(d, event("click")))
  }
}
