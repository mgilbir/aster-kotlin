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
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.spec.SignalSpec
import dev.aster.vega.runtime.scale.InvertibleScale
import dev.aster.vega.runtime.scale.VegaScale

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
  /**
   * The chart's scales, for `scale('x', datum.v)` inside an expression.
   *
   * Empty while the signals themselves are being resolved, because scales are built *from* signals
   * and do not exist yet — which is upstream's ordering too, and why a signal's own `update` cannot
   * call `scale()` while a mark encoding can.
   */
  private val scales: Map<String, VegaScale> = emptyMap(),
  /**
   * Reports an expression naming a scale that does not exist, rather than quietly yielding null.
   */
  private val diagnostics: DiagnosticCollector? = null,
) : ExpressionScope {

  override fun signal(name: String): VegaValue = values[name] ?: VegaValue.Null

  override fun dataset(name: String): List<VegaValue> = datasets[name] ?: emptyList()

  override fun applyScale(name: String, value: VegaValue): VegaValue =
    resolveScale(name, "scale")?.scale(value) ?: VegaValue.Null

  override fun invertScale(name: String, value: VegaValue): VegaValue {
    val scale = resolveScale(name, "invert") ?: return VegaValue.Null
    if (scale !is InvertibleScale) {
      diagnostics?.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        "invert() needs a continuous scale; '$name' cannot be inverted",
        operator = name,
      )
      return VegaValue.Null
    }
    val position = value.asDouble()
    if (position.isNaN()) return VegaValue.Null
    return VegaValue.Num(scale.invert(position))
  }

  private fun resolveScale(name: String, function: String): VegaScale? {
    val scale = scales[name]
    if (scale == null) {
      diagnostics?.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        // Two different failures, and telling them apart is the whole value of the message: one is
        // a typo, the other is asking for something that cannot exist at that point.
        if (scales.isEmpty()) {
          "$function() cannot be used while signals are resolving: a scale is built *from* " +
            "signals — its domain or range may be signal-valued — so none exists yet"
        } else {
          "$function() names scale '$name', which this specification does not define"
        },
        operator = name,
      )
    }
    return scale
  }

  public fun withDatum(datum: VegaValue): SignalScope =
    SignalScope(values, datasets, datum, scales, diagnostics)

  /** Adds the scales once they exist, which is after every signal has resolved. */
  public fun withScales(
    scales: Map<String, VegaScale>,
    diagnostics: DiagnosticCollector,
  ): SignalScope = SignalScope(values, datasets, datum, scales, diagnostics)

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

  /**
   * @param pinned signals whose value comes from outside and must not be recomputed.
   *
   * This is how an event handler's result survives the recompile that follows it. A pinned signal
   * keeps the value it was given and everything reading it sees that, rather than the `update`
   * expression it was declared with — which is the point, since the whole reason a handler fired
   * was to override that.
   */
  public fun resolve(
    signals: List<SignalSpec>,
    datasets: Map<String, List<VegaValue>>,
    implicit: Map<String, VegaValue> = emptyMap(),
    pinned: Map<String, VegaValue> = emptyMap(),
  ): SignalScope = Resolution(signals, datasets, implicit, pinned).run()

  /** One resolution pass. Holds the mutable bookkeeping so the resolver itself stays reusable. */
  private inner class Resolution(
    signals: List<SignalSpec>,
    private val datasets: Map<String, List<VegaValue>>,
    implicit: Map<String, VegaValue>,
    pinned: Map<String, VegaValue>,
  ) {
    private val specs = LinkedHashMap<String, SignalSpec>()
    private val values = LinkedHashMap<String, VegaValue>(implicit)
    private val settled = mutableSetOf<String>()
    private val inProgress = LinkedHashSet<String>()

    init {
      // Seeded as already settled, so `resolve` returns before evaluating anything.
      values.putAll(pinned)
      settled.addAll(pinned.keys)
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
      for (name in pinned.keys) {
        val spec = specs[name] ?: continue
        if (spec.expression != null) {
          // Upstream would re-run the update when one of its dependencies changed, even after a
          // handler had set the signal. Nothing here tracks that, so the handler's value simply
          // stays — which is right until a dependency moves, and wrong after.
          diagnostics.warn(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "Signal '$name' was set by an event handler and also has an update expression; the " +
              "handler's value stays and the expression will not run again",
            operator = name,
          )
        }
      }
    }

    fun run(): SignalScope {
      for (name in specs.keys) resolve(name)
      return SignalScope(values, datasets, diagnostics = diagnostics)
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
            compiled.expression.evaluate(SignalScope(values, datasets, diagnostics = diagnostics))
          } catch (e: ExpressionEvaluationException) {
            diagnostics.add(e.diagnostic.copy(operator = spec.name))
            spec.value ?: VegaValue.Null
          }
        }
      }
    }
  }
}
