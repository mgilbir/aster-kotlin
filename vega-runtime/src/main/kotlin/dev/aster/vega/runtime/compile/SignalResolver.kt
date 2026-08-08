package dev.aster.vega.runtime.compile

import dev.aster.vega.dataflow.transform.TreeSource
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
import dev.aster.vega.model.asString
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
   * Holds whatever exists *by the point this scope was made*, which for a top-level signal is every
   * scale the dependency order put ahead of it. So a signal may call `scale()` on a scale that does
   * not wait on it, and only a genuine cycle leaves one unavailable.
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
  /**
   * Where `setdata` puts what it is given, when this scope belongs to a chart being compiled.
   *
   * Null everywhere else, and then the write is ignored — a scope with no chart behind it has no
   * dataset to replace.
   */
  private val datasetSink: ((String, List<VegaValue>) -> Unit)? = null,
  /** The hierarchy each stratified dataset built, for `treePath` and `treeAncestors`. */
  private val trees: Map<String, TreeSource> = emptyMap(),
) : ExpressionScope {

  override fun setDataset(name: String, rows: List<VegaValue>) {
    datasetSink?.invoke(name, rows)
  }

  override fun treePath(name: String, from: VegaValue, to: VegaValue): VegaValue =
    rowsAt(name, trees[name]?.pathBetween(from.asString(), to.asString()))

  override fun treeAncestors(name: String, node: VegaValue): VegaValue =
    rowsAt(name, trees[name]?.ancestorsOf(node.asString()))

  /**
   * The dataset's rows at the given positions, **as they stand now**.
   *
   * A tree records positions rather than rows because this engine's transforms copy: the rows the
   * hierarchy was built from no longer carry what the formulas after it wrote, and a path drawn
   * from them would have every node at the origin.
   */
  private fun rowsAt(name: String, positions: List<Int>?): VegaValue {
    if (positions == null) return VegaValue.Null
    val rows = datasets[name] ?: return VegaValue.Null
    return VegaValue.Arr(positions.map { rows.getOrNull(it) ?: VegaValue.Null })
  }

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
        // Two different failures, and telling them apart is the whole value of the message. Only
        // the second is a typo; the first is a question asked too early, and answering it with "no
        // such scale" sends the reader hunting for a misspelling that is not there.
        when {
          name in pendingScales ->
            "$function() names scale '$name', which this scope defines but has not built yet — it " +
              "comes later in the dependency order. At the top level that means a cycle, reported " +
              "on its own; inside a group mark it means reading one of the group's own scales, " +
              "which are built from the very signals being resolved"
          else -> "$function() names scale '$name', which this specification does not define"
        },
        operator = name,
      )
    }
    return scale
  }

  public fun withDatum(datum: VegaValue): SignalScope =
    SignalScope(
      values,
      datasets,
      datum,
      scales,
      diagnostics,
      indataIndexes,
      pendingScales,
      datasetSink,
      trees,
    )

  /** Adds the scales once they exist, which is after every signal has resolved. */
  public fun withScales(
    scales: Map<String, VegaScale>,
    diagnostics: DiagnosticCollector,
  ): SignalScope =
    SignalScope(
      values,
      datasets,
      datum,
      scales,
      diagnostics,
      indataIndexes,
      pendingScales,
      datasetSink,
      trees,
    )

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
  ): SignalScope {
    val session = session(signals, LinkedHashMap(implicit), pinned)
    for (signal in signals) session.resolve(signal.name, datasets, enclosingScales, pendingScales)
    return session.scope(datasets, enclosingScales)
  }

  /**
   * A resolution the caller drives one signal at a time.
   *
   * The batch [resolve] above is the whole of a group scope's needs: its signals resolve together,
   * against data and scales that already exist. The top level is not like that — a signal may sit
   * between a dataset and a scale in dependency order — so [SpecCompiler] interleaves the three and
   * asks for one signal at a time, handing over the datasets and scales that exist by then.
   *
   * @param values the map to fill, seeded with whatever is already known. Shared rather than
   *   copied, because a transform may *publish* a signal — `extent` writes its result to one — and
   *   the caller resolving datasets into the same map is how that becomes visible here.
   */
  public fun session(
    signals: List<SignalSpec>,
    values: MutableMap<String, VegaValue>,
    pinned: Map<String, VegaValue> = emptyMap(),
    /** Where a `setdata` in one of these signals puts its rows. */
    datasetSink: ((String, List<VegaValue>) -> Unit)? = null,
  ): Resolution = Resolution(signals, values, pinned, datasetSink)

  /**
   * One resolution pass. Holds the mutable bookkeeping so the resolver itself stays reusable.
   *
   * The datasets and scales are arguments to [resolve] rather than fields because they grow while a
   * resolution is in progress: what a signal can read depends on where it sits in the order.
   */
  public inner class Resolution
  internal constructor(
    signals: List<SignalSpec>,
    private val values: MutableMap<String, VegaValue>,
    pinned: Map<String, VegaValue>,
    private val datasetSink: ((String, List<VegaValue>) -> Unit)? = null,
  ) {
    private val specs = LinkedHashMap<String, SignalSpec>()
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

    /** Everything resolved so far, as a scope an expression can read. */
    public fun scope(
      datasets: Map<String, List<VegaValue>>,
      scales: Map<String, VegaScale> = emptyMap(),
    ): SignalScope =
      SignalScope(
        values,
        datasets,
        diagnostics = diagnostics,
        scales = scales,
        datasetSink = datasetSink,
      )

    public fun resolve(
      name: String,
      datasets: Map<String, List<VegaValue>>,
      scales: Map<String, VegaScale> = emptyMap(),
      pendingScales: Set<String> = emptySet(),
    ) {
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
        // A signal's `update` may read *itself*: `dashing ? frame : lastFrameDashing` holds the
        // last
        // frame a thing happened on and leaves it alone otherwise. Upstream's operator holds its
        // previous value, so on the first pass that is the declared `value` — seeded here, because
        // reading it as null turns "leave it alone" into "reset it to zero", and a chart written
        // that way then draws a state it was never in.
        if (name !in values) values[name] = spec.value ?: VegaValue.Null
        values[name] = evaluate(spec, datasets, scales, pendingScales)
        settled.add(name)
      } finally {
        inProgress.remove(name)
      }
    }

    private fun evaluate(
      spec: SignalSpec,
      datasets: Map<String, List<VegaValue>>,
      scales: Map<String, VegaScale>,
      pendingScales: Set<String>,
    ): VegaValue {
      val source = spec.expression ?: return spec.value ?: VegaValue.Null

      return when (val compiled = expressions.compile(source)) {
        is ExpressionResult.Failed -> {
          diagnostics.add(compiled.diagnostic.copy(operator = spec.name))
          spec.value ?: VegaValue.Null
        }
        is ExpressionResult.Compiled -> {
          // Resolve everything this signal reads before reading it. Already done when the caller
          // drives the order itself, and the settled check makes that a no-op.
          for (dependency in compiled.expression.signalDependencies) {
            if (dependency != spec.name) resolve(dependency, datasets, scales, pendingScales)
          }
          try {
            compiled.expression.evaluate(
              SignalScope(
                values,
                datasets,
                diagnostics = diagnostics,
                scales = scales,
                pendingScales = pendingScales,
                datasetSink = datasetSink,
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
