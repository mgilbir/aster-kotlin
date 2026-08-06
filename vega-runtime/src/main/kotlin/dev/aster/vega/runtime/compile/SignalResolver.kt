package dev.aster.vega.runtime.compile

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionEvaluationException
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.SignalSpec

/**
 * Resolved signal values, and the scope expressions read them through.
 *
 * Implements [ExpressionScope] so it can be handed straight to an expression. [datum] is supplied
 * per mark instance by [withDatum], which shares the underlying maps rather than copying them.
 */
public class SignalScope(
  /** Exposed so a nested group scope can inherit these and add its own. */
  public val values: Map<String, VegaValue>,
  private val datasets: Map<String, List<VegaValue>>,
  override val datum: VegaValue = VegaValue.Null,
) : ExpressionScope {

  override fun signal(name: String): VegaValue = values[name] ?: VegaValue.Null

  override fun dataset(name: String): List<VegaValue> = datasets[name] ?: emptyList()

  public fun withDatum(datum: VegaValue): SignalScope = SignalScope(values, datasets, datum)

  public val names: Set<String>
    get() = values.keys

  public operator fun get(name: String): VegaValue? = values[name]
}

/**
 * Evaluates a specification's signals in dependency order.
 *
 * Three behaviours are Vega's, verified against upstream rather than assumed:
 * - a signal's value is its `update` expression if present, otherwise `init`, otherwise `value`; so
 *   `{value: 5, update: "99"}` resolves to 99, not 5
 * - resolution follows the dependency graph, not declaration order, so a signal may reference one
 *   declared after it
 * - `width` and `height` are implicit signals available to every expression
 *
 * A dependency cycle is an error, as upstream. It is reported as the path that closed it, so it can
 * be found — rather than surfacing as a stack overflow or a silently zeroed signal.
 *
 * Stateless between calls: all resolution state is local to [resolve].
 */
public class SignalResolver(
  private val diagnostics: DiagnosticCollector,
  /** Shared so the same expression text is parsed once across signals, encodings and axes. */
  private val expressions: ExpressionCompiler = CachingExpressionCompiler(VegaExpressionCompiler()),
) {

  public fun resolve(
    signals: List<SignalSpec>,
    datasets: Map<String, List<VegaValue>>,
    implicit: Map<String, VegaValue> = emptyMap(),
  ): SignalScope = Resolution(signals, datasets, implicit).run()

  /** One resolution pass. Holds the mutable bookkeeping so the resolver itself stays reusable. */
  private inner class Resolution(
    signals: List<SignalSpec>,
    private val datasets: Map<String, List<VegaValue>>,
    implicit: Map<String, VegaValue>,
  ) {
    private val specs = LinkedHashMap<String, SignalSpec>()
    private val values = LinkedHashMap<String, VegaValue>(implicit)
    private val settled = mutableSetOf<String>()
    private val inProgress = LinkedHashSet<String>()

    init {
      for (signal in signals) {
        if (specs.containsKey(signal.name)) {
          diagnostics.warn(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "Duplicate signal '${signal.name}'; the later definition wins",
            operator = signal.name,
          )
        }
        specs[signal.name] = signal
      }
    }

    fun run(): SignalScope {
      for (name in specs.keys) resolve(name)
      return SignalScope(values, datasets)
    }

    private fun resolve(name: String) {
      if (name in settled) return
      val spec = specs[name] ?: return

      if (!inProgress.add(name)) {
        val cycle = inProgress.dropWhile { it != name } + name
        diagnostics.error(
          DiagnosticCodes.SIGNAL_CYCLE,
          "Signal cycle: ${cycle.joinToString(" -> ")}. " +
            "These signals keep their declared values instead.",
          operator = name,
        )
        return
      }

      try {
        values[name] = evaluate(spec)
        settled.add(name)
      } finally {
        inProgress.remove(name)
      }
    }

    private fun evaluate(spec: SignalSpec): VegaValue {
      val source = spec.expression ?: return spec.value ?: VegaValue.Null

      return when (val compiled = expressions.compile(source)) {
        is ExpressionResult.Failed -> {
          diagnostics.add(compiled.diagnostic.copy(operator = spec.name))
          spec.value ?: VegaValue.Null
        }
        is ExpressionResult.Compiled -> {
          // Resolve everything this signal reads before reading it.
          for (dependency in compiled.expression.signalDependencies) {
            if (dependency != spec.name) resolve(dependency)
          }
          try {
            compiled.expression.evaluate(SignalScope(values, datasets))
          } catch (e: ExpressionEvaluationException) {
            diagnostics.add(e.diagnostic.copy(operator = spec.name))
            spec.value ?: VegaValue.Null
          }
        }
      }
    }
  }
}
