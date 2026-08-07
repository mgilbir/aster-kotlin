package dev.aster.vega.runtime.compile

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionEvaluationException
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.expression.indataCounts
import dev.aster.vega.expression.indataLookup
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.spec.SignalSpec
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.BinnedScale
import dev.aster.vega.runtime.scale.InvertibleScale
import dev.aster.vega.runtime.scale.LinearScale
import dev.aster.vega.runtime.scale.OrdinalScale
import dev.aster.vega.runtime.scale.PointScale
import dev.aster.vega.runtime.scale.PositionScale
import dev.aster.vega.runtime.scale.SequentialColorScale
import dev.aster.vega.runtime.scale.TimeScale
import dev.aster.vega.runtime.scale.TransformedScale
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
  /**
   * `indata` indexes, shared with every scope derived from this one.
   *
   * Upstream keeps one index per dataset and field for the life of the view; here the equivalent is
   * to keep it for the life of a compile, because [withDatum] makes a fresh scope for every row of
   * every mark. Without this an `indata` in an encoding rescans the whole target dataset once per
   * datum, which is the difference between linear and quadratic on the specifications that use it.
   */
  private val indataIndexes: MutableMap<Pair<String, String>, Map<String, Int>> = mutableMapOf(),
  /**
   * Scale names this scope defines but has not built yet.
   *
   * Only ever non-empty while signals are resolving. It exists so the diagnostic can tell "you
   * asked for this too early" apart from "there is no such scale" — a group whose own signal calls
   * `bandwidth()` on one of the group's own scales is the first, and reading it as the second sends
   * the reader hunting for a typo that is not there.
   */
  private val pendingScales: Set<String> = emptySet(),
) : ExpressionScope {

  override fun signal(name: String): VegaValue = values[name] ?: VegaValue.Null

  override fun dataset(name: String): List<VegaValue> = datasets[name] ?: emptyList()

  override fun indata(name: String, field: String, value: VegaValue): VegaValue {
    val rows = datasets[name]
    if (rows == null) {
      // Upstream refuses this at parse time — "Undefined data set name" — so it is never a chart
      // that quietly finds nothing; it is a chart that does not compile. Reported rather than
      // fatal, since the rest of the specification is still worth drawing.
      diagnostics?.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "indata() names dataset '$name', which this specification does not define",
        operator = name,
      )
      return VegaValue.Null
    }
    val counts = indataIndexes.getOrPut(name to field) { indataCounts(rows, field) }
    return indataLookup(counts, value)
  }

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

  override fun scaleDomain(name: String): VegaValue {
    val scale = resolveScale(name, "domain") ?: return VegaValue.Null
    return VegaValue.Arr(
      when (scale) {
        is LinearScale -> scale.domain.map { VegaValue.Num(it) }
        is TransformedScale -> scale.domain.map { VegaValue.Num(it) }
        is TimeScale -> scale.domain.map { VegaValue.Num(it) }
        is SequentialColorScale -> scale.domain.map { VegaValue.Num(it) }
        is BandScale -> scale.domain.map { VegaValue.Str(it) }
        is PointScale -> scale.domain.map { VegaValue.Str(it) }
        is OrdinalScale -> scale.domain.map { VegaValue.Str(it) }
        is BinnedScale -> scale.thresholds.map { VegaValue.Num(it) }
      }
    )
  }

  override fun scaleRange(name: String): VegaValue {
    val scale = resolveScale(name, "range") ?: return VegaValue.Null
    return when (scale) {
      is PositionScale -> VegaValue.Arr(scale.range.map { VegaValue.Num(it) })
      is OrdinalScale -> VegaValue.Arr(scale.rangeValues)
      is BinnedScale -> VegaValue.Arr(scale.rangeValues)
      else -> VegaValue.Arr(emptyList())
    }
  }

  /** Zero for anything that is not a band scale, which is what upstream reports. */
  override fun scaleBandwidth(name: String): VegaValue =
    VegaValue.Num((resolveScale(name, "bandwidth") as? PositionScale)?.bandwidth ?: 0.0)

  private fun resolveScale(name: String, function: String): VegaScale? {
    val scale = scales[name]
    if (scale == null) {
      diagnostics?.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        // Three different failures, and telling them apart is the whole value of the message. Only
        // the last is a typo; the first two are questions asked too early, and answering them with
        // "no such scale" sends the reader hunting for a misspelling that is not there.
        when {
          name in pendingScales ->
            "$function() names scale '$name', which this scope defines but has not built yet: a " +
              "scale is built *from* the signals of its own scope, so a signal cannot read one " +
              "declared beside it"
          scales.isEmpty() ->
            "$function() cannot be used while signals are resolving: a scale is built *from* " +
              "signals — its domain or range may be signal-valued — so none exists yet"
          else -> "$function() names scale '$name', which this specification does not define"
        },
        operator = name,
      )
    }
    return scale
  }

  public fun withDatum(datum: VegaValue): SignalScope =
    SignalScope(values, datasets, datum, scales, diagnostics, indataIndexes)

  /** Adds the scales once they exist, which is after every signal has resolved. */
  public fun withScales(
    scales: Map<String, VegaScale>,
    diagnostics: DiagnosticCollector,
  ): SignalScope = SignalScope(values, datasets, datum, scales, diagnostics, indataIndexes)

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
    /**
     * Scales built in an *enclosing* scope, which a group's own signals may read.
     *
     * Empty at the top level, where a scale genuinely does not exist yet. Inside a group it is the
     * chart's scales, which were built long before the group was reached — which is what lets
     * `{"name": "height", "update": "bandwidth('yscale')"}` give a trellis cell the height of one
     * band of the outer scale.
     */
    enclosingScales: Map<String, VegaScale> = emptyMap(),
    /**
     * Scales this scope will define *after* its signals, so one named here can be reported as
     * premature rather than as a typo.
     */
    pendingScales: Set<String> = emptySet(),
  ): SignalScope =
    Resolution(signals, datasets, implicit, pinned, enclosingScales, pendingScales).run()

  /** One resolution pass. Holds the mutable bookkeeping so the resolver itself stays reusable. */
  private inner class Resolution(
    signals: List<SignalSpec>,
    private val datasets: Map<String, List<VegaValue>>,
    implicit: Map<String, VegaValue>,
    pinned: Map<String, VegaValue>,
    private val enclosingScales: Map<String, VegaScale>,
    private val pendingScales: Set<String>,
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
      return SignalScope(values, datasets, diagnostics = diagnostics, scales = enclosingScales)
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
            compiled.expression.evaluate(
              SignalScope(
                values,
                datasets,
                diagnostics = diagnostics,
                scales = enclosingScales,
                pendingScales = pendingScales,
              )
            )
          } catch (e: ExpressionEvaluationException) {
            diagnostics.add(e.diagnostic.copy(operator = spec.name))
            spec.value ?: VegaValue.Null
          }
        }
      }
    }
  }
}
