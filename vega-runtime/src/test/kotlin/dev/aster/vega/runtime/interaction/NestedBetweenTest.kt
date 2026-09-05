@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.runtime.interaction

import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.EventSelector
import dev.aster.vega.model.spec.SignalHandler
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A `between` pair wrapping another one fires, which it never did.
 *
 * `[a, b] > [c, d] > mousemove` was refused by name, on the grounds that honouring it means gating
 * a gate and that "guessing at the ordering would be worse than saying so". Reading upstream
 * instead of guessing shows there is **no ordering to get wrong**. `vega-dataflow`'s `between(a,
 * b)` is four lines: a boolean set true by `a`, false by `b`, and a filter on it. `parseStream`
 * composes a nested pair as `stream.between(c, d).between(a, b)`, so an event fires exactly when
 * the innermost stream matches and *every* latch in the chain is open. Each latch is opened and
 * closed by its own pair, independently of the others.
 *
 * So the chain is flattened into a list of gates, and the gate watches are updated before anything
 * they gate — which is what this class already did for a single pair.
 *
 * **What the wrapper is, and is not.** It carries a `between` and the stream it wraps, and nothing
 * that says what to listen to: the source, the type and the mark all live on the innermost stream.
 * Everything in `register` that asks "is this a timer, a `window:` stream, a `keyup`" was asking
 * the wrapper, which answers with the defaults every time — so the chain is resolved before any of
 * those questions rather than after.
 */
class NestedBetweenTest {

  private val expressions = VegaExpressionCompiler()

  private val emptyScope =
    object : ExpressionScope {
      override val datum: VegaValue = VegaValue.Null

      override fun signal(name: String): VegaValue = VegaValue.Null

      override fun dataset(name: String): List<VegaValue> = emptyList()
    }

  private fun dispatcher(selector: String): EventDispatcher {
    val handler = SignalHandler(streams = EventSelector.parse(selector))
    return EventDispatcher(
      listOf(HandlerBinding("fired", handler)),
      expressions,
      DiagnosticCollector(),
      emptyScope,
    )
  }

  private fun event(type: String) = InputEvent(type = type, timestampMillis = 0)

  private fun fired(dispatcher: EventDispatcher, type: String) =
    dispatcher.dispatch(event(type)).size

  /**
   * Both latches must be open, and neither alone is enough.
   *
   * The whole of the semantics in one case: `mousemove` fires only once `mousedown` **and**
   * `keydown` have both been seen and neither has been closed.
   */
  @Test
  fun `a nested pair fires only when every latch is open`() {
    val d = dispatcher("[mousedown, mouseup] > [keydown, keyup] > mousemove")

    assertEquals(0, fired(d, "mousemove"), "it fired with both latches shut")

    d.dispatch(event("mousedown"))
    assertEquals(0, fired(d, "mousemove"), "the outer latch alone was enough")

    d.dispatch(event("keydown"))
    assertEquals(1, fired(d, "mousemove"), "both latches are open and it did not fire")

    d.dispatch(event("keyup"))
    assertEquals(0, fired(d, "mousemove"), "closing the inner latch did not stop it")

    d.dispatch(event("keydown"))
    assertEquals(1, fired(d, "mousemove"), "reopening the inner latch did not restart it")

    d.dispatch(event("mouseup"))
    assertEquals(0, fired(d, "mousemove"), "closing the outer latch did not stop it")
  }

  /**
   * The latches are **independent**: closing and reopening one does not touch the other.
   *
   * The property that says this is a list of gates rather than a stack of them, and the one a
   * guessed ordering would most likely have got wrong.
   */
  @Test
  fun `each latch is opened and closed by its own pair alone`() {
    val d = dispatcher("[mousedown, mouseup] > [keydown, keyup] > mousemove")
    d.dispatch(event("mousedown"))
    d.dispatch(event("keydown"))
    assertEquals(1, fired(d, "mousemove"))

    // Close and reopen the *outer* pair. The inner latch was never told anything.
    d.dispatch(event("mouseup"))
    d.dispatch(event("mousedown"))
    assertEquals(1, fired(d, "mousemove"), "reopening the outer latch found the inner one closed")
  }

  /** Three deep, because "a pair wrapping a pair" should not be a special case of its own. */
  @Test
  fun `the chain is any depth`() {
    val d =
      dispatcher("[mousedown, mouseup] > [keydown, keyup] > [dragenter, dragleave] > mousemove")
    d.dispatch(event("mousedown"))
    d.dispatch(event("keydown"))
    assertEquals(0, fired(d, "mousemove"), "two of three latches were enough")
    d.dispatch(event("dragenter"))
    assertEquals(1, fired(d, "mousemove"), "all three latches are open and it did not fire")
    d.dispatch(event("dragleave"))
    assertEquals(0, fired(d, "mousemove"), "the innermost latch did not close it")
  }

  /** A single pair is unchanged, so nothing was traded for this. */
  @Test
  fun `an ordinary between still works`() {
    val d = dispatcher("[mousedown, mouseup] > mousemove")
    assertEquals(0, fired(d, "mousemove"))
    d.dispatch(event("mousedown"))
    assertEquals(1, fired(d, "mousemove"))
    d.dispatch(event("mouseup"))
    assertEquals(0, fired(d, "mousemove"))
  }

  /** And it is no longer reported as undispatched. */
  @Test
  fun `a nested pair is no longer refused`() {
    val diagnostics = DiagnosticCollector()
    val handler =
      SignalHandler(
        streams = EventSelector.parse("[mousedown, mouseup] > [keydown, keyup] > click")
      )
    EventDispatcher(listOf(HandlerBinding("fired", handler)), expressions, diagnostics, emptyScope)
    assertTrue(
      diagnostics.diagnostics.none { "wrapping another" in it.message },
      "a nested between is still reported: ${diagnostics.diagnostics.map { it.message }}",
    )
  }

  /**
   * The **innermost** stream is what says what to listen to, so a wrapped `keyup` is still refused.
   *
   * The half that would have gone quiet: every refusal in `register` used to ask the outer wrapper,
   * which carries no type at all and answers "not a keyup" whatever it wraps. A specification
   * gating a `keyup` would have been registered and then never fired, with nothing said — the exact
   * shape those refusals exist to prevent.
   */
  @Test
  fun `a refusal reads the innermost stream, not the wrapper`() {
    val diagnostics = DiagnosticCollector()
    val handler =
      SignalHandler(
        streams = EventSelector.parse("[mousedown, mouseup] > [keydown, keyup] > keyup")
      )
    EventDispatcher(listOf(HandlerBinding("fired", handler)), expressions, diagnostics, emptyScope)
    assertTrue(
      diagnostics.diagnostics.any { "keyup" in it.message && "keydown" in it.message },
      "a gated keyup was registered rather than refused: " +
        "${diagnostics.diagnostics.map { it.message }}",
    )
  }

  /**
   * A latch is a **latch**, so a close nobody opened leaves it shut rather than inverting it.
   *
   * The property that makes a lost `mouseup` behave as upstream does, checked here for the chain
   * because a list of gates is a new way to get it wrong.
   */
  @Test
  fun `closing a latch that was never opened leaves it closed`() {
    val d = dispatcher("[mousedown, mouseup] > [keydown, keyup] > mousemove")
    d.dispatch(event("mouseup"))
    d.dispatch(event("keyup"))
    assertEquals(0, fired(d, "mousemove"), "closing an unopened latch opened it")
    d.dispatch(event("mousedown"))
    d.dispatch(event("keydown"))
    assertEquals(1, fired(d, "mousemove"), "and then it could not be opened at all")
  }
}
