package dev.aster.vega.runtime.interaction

import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionEvaluationException
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.SignalUpdate

/**
 * Applies fired handlers to the signals they set.
 *
 * The last step of the interaction chain, and the smallest, because measuring removed the hard part
 * of it: a full recompile of the heaviest fixture costs well under a frame, so a changed signal
 * simply means compiling the specification again with that signal pinned. No incremental dataflow.
 *
 * The values accumulate here rather than in the compiler, because
 * [dev.aster.vega.runtime.compile.SignalResolver] is deliberately stateless between calls — the
 * state of an interaction belongs to the thing being interacted with, not to the resolver.
 */
public class SignalUpdater(
  private val expressions: ExpressionCompiler,
  private val diagnostics: DiagnosticCollector,
) {

  private val values = LinkedHashMap<String, VegaValue>()

  /** Everything a handler has set so far, to be passed to the next compile as overrides. */
  public val overrides: Map<String, VegaValue>
    get() = values

  public fun reset() {
    values.clear()
  }

  /**
   * @param scope the current signal values and datasets, which an update expression may read.
   * @return the signals whose value actually changed, so the caller can skip a recompile when
   *   nothing did. A handler marked `force` reports its signal as changed either way.
   */
  public fun apply(fired: List<FiredHandler>, scope: ExpressionScope): Set<String> {
    val changed = LinkedHashSet<String>()
    for (entry in fired) {
      val handler = entry.handler
      if (handler.encode != null) {
        // `encode` sets properties on the event's own item rather than producing a signal value.
        // Doing it means reaching into the scene graph and mutating a node the compiler owns,
        // which is a different shape of change from everything here.
        diagnostics.warn(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "A handler on signal '${entry.signalName}' uses 'encode', which sets properties on the " +
            "event's mark rather than a signal value; nothing was applied",
          operator = entry.signalName,
        )
        continue
      }
      val update = handler.update ?: continue
      val next = evaluate(update, entry, scope) ?: continue
      val previous = values[entry.signalName] ?: scope.signal(entry.signalName)
      // `force` re-runs everything downstream even when the value is unchanged — needed when the
      // value is an object mutated in place, where equality would say nothing had happened.
      if (handler.force || next != previous) {
        values[entry.signalName] = next
        changed += entry.signalName
      }
    }
    return changed
  }

  private fun evaluate(
    update: SignalUpdate,
    entry: FiredHandler,
    scope: ExpressionScope,
  ): VegaValue? =
    when (update) {
      is SignalUpdate.Constant -> update.value
      is SignalUpdate.Reference -> values[update.name] ?: scope.signal(update.name)
      is SignalUpdate.Expression -> {
        when (val compiled = expressions.compile(update.expr)) {
          is ExpressionResult.Failed -> {
            diagnostics.add(compiled.diagnostic.copy(operator = entry.signalName))
            null
          }
          is ExpressionResult.Compiled ->
            try {
              compiled.expression.evaluate(HandlerScope(scope, values, entry))
            } catch (failure: ExpressionEvaluationException) {
              diagnostics.add(failure.diagnostic.copy(operator = entry.signalName))
              null
            }
        }
      }
    }

  /**
   * What an update expression can see: the specification's own scope, plus `event`, plus anything
   * an earlier handler in the same batch already set.
   *
   * `datum` is the datum of the mark the event landed on, which is what makes `{"events":
   * "rect:click", "update": "datum.category"}` — the commonest handler there is — work.
   */
  private class HandlerScope(
    private val delegate: ExpressionScope,
    private val pending: Map<String, VegaValue>,
    private val entry: FiredHandler,
  ) : ExpressionScope {

    private val event = entry.event?.asValue() ?: VegaValue.Null

    override val datum: VegaValue
      get() = entry.event?.datum ?: VegaValue.Null

    override fun signal(name: String): VegaValue =
      when (name) {
        "event" -> event
        else -> pending[name] ?: delegate.signal(name)
      }

    override fun dataset(name: String): List<VegaValue> = delegate.dataset(name)
  }
}
