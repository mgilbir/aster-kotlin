package dev.aster.vega.runtime.compile

import dev.aster.vega.dataflow.transform.GeoMeasure
import dev.aster.vega.dataflow.transform.ProjectionDefinition
import dev.aster.vega.dataflow.transform.TreeSource
import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.Clock
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionEvaluationException
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.RandomStream
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.expression.indataCounts
import dev.aster.vega.expression.indataLookup
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asNumberOrNull
import dev.aster.vega.model.asString
import dev.aster.vega.model.spec.SignalSpec
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.BinnedScale
import dev.aster.vega.runtime.scale.IdentityScale
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
  /**
   * The chart's one random stream, shared by every scope derived from this one.
   *
   * Shared on purpose: upstream's generator is module-level, so a view's draws form a single
   * sequence and the picture depends on the order the expressions run in. A scope that copied the
   * stream would restart it for every datum and every mark.
   */
  override val random: RandomStream = RandomStream(),
  /** What `now()` answers, pinned by default so a compile is a pure function. */
  private val clock: Clock = Clock.Fixed,
  /** The projections `geoCentroid()` can reach, empty everywhere a chart is not being compiled. */
  private val projections: Map<String, ProjectionDefinition> = emptyMap(),
) : ExpressionScope {

  override fun now(): Double = clock.now()

  override fun setDataset(name: String, rows: List<VegaValue>) {
    datasetSink?.invoke(name, rows)
  }

  /**
   * `modify(name, insert, remove, toggle)` — the interactive write, through the same sink `setdata`
   * uses.
   *
   * The three are applied in upstream's order — remove, then insert, then toggle — and `toggle`
   * removes what is already there and inserts what is not, which is how a click handler builds a
   * multiple selection. `remove: true` empties the dataset; an object removes every row it matches
   * **field by field**, which is upstream's `equalObject` and not identity: the row a click hands
   * back is a copy, so matching on identity would never remove anything.
   *
   * The write happens **once**. Upstream queues a changeset and applies it after the run, and a
   * `modify` written in a signal's `update` rather than in an event handler is then applied a
   * second time — probed: toggling the one row out of a one-row dataset leaves *two* copies of it
   * upstream rather than none. That is re-entrancy in its scheduler, not its documented behaviour,
   * and an engine that compiles a scene once has no equivalent of it. The insert, remove and
   * toggle-that-inserts branches all agree, and `modify-dataset` covers them.
   */
  override fun modifyDataset(
    name: String,
    insert: VegaValue,
    remove: VegaValue,
    toggle: VegaValue,
  ): Double {
    val sink = datasetSink ?: return 0.0
    val current = dataset(name)
    val inserting = rowsOf(insert)
    val toggling = rowsOf(toggle)
    // Upstream's early exit, and it is observable: with nothing in the dataset and nothing to add,
    // `modify` answers 0 and changes nothing.
    if (current.isEmpty() && inserting.isEmpty() && toggling.isEmpty()) return 0.0

    var rows = current
    when {
      remove is VegaValue.Bool && remove.value -> rows = emptyList()
      remove is VegaValue.Arr -> {
        val gone = remove.values
        rows = rows.filterNot { row -> gone.any { matches(it, row) } }
      }
      remove is VegaValue.Obj -> rows = rows.filterNot { matches(remove, it) }
      else -> Unit
    }
    rows = rows + inserting
    for (row in toggling) {
      rows = if (rows.any { matches(row, it) }) rows.filterNot { matches(row, it) } else rows + row
    }
    sink(name, rows)
    return 1.0
  }

  /** A parameter that may be one row or several. */
  private fun rowsOf(value: VegaValue): List<VegaValue> =
    when (value) {
      is VegaValue.Arr -> value.values
      is VegaValue.Obj -> listOf(value)
      else -> emptyList()
    }

  /**
   * Upstream's `equalObject`: every field of the *pattern* equal in the row, extras ignored.
   *
   * One-sided on purpose. A row carries whatever the transforms added to it, and a handler matching
   * on `{"c": "alpha"}` means "the rows whose `c` is alpha" rather than "the rows that are only
   * that".
   */
  private fun matches(pattern: VegaValue, row: VegaValue): Boolean {
    val fields = (pattern as? VegaValue.Obj)?.fields ?: return pattern == row
    val other = (row as? VegaValue.Obj)?.fields ?: return false
    return fields.all { (key, value) -> other[key] == value }
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
    // Projections share the scale namespace, and inverting one is what turns a click on a map into
    // a place on Earth. It takes a *pair* and returns a pair, where a scale takes one number.
    projections[name]?.let { definition ->
      val point = (value as? VegaValue.Arr)?.values ?: return VegaValue.Null
      if (point.size < 2) return VegaValue.Null
      val place = GeoMeasure.invert(definition, point[0].asDouble(), point[1].asDouble())
      if (place == null) {
        diagnostics?.error(
          DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
          "invert() names projection '$name', whose type has no closed-form inverse here; " +
            "a point on the page cannot be read back to a place on the globe",
          operator = name,
        )
        return VegaValue.Null
      }
      return VegaValue.Arr(listOf(VegaValue.Num(place[0]), VegaValue.Num(place[1])))
    }
    val scale = resolveScale(name, "invert") ?: return VegaValue.Null
    // A **band** has an inverse after all — not a number but a *value*: the band a position falls
    // in. `scaleBand.invert` is what a chart animated by a clock reads its frame from, walking the
    // range a step at a time and asking which value each step lands on.
    if (scale is BandScale) {
      val position = value.asDouble()
      return scale.invert(position)?.let { VegaValue.Str(it) } ?: VegaValue.Null
    }
    if (scale is PointScale) {
      val position = value.asDouble()
      return scale.invert(position)?.let { VegaValue.Str(it) } ?: VegaValue.Null
    }
    // A scale with **buckets** runs backwards to a stretch of domain rather than to a point, which
    // is upstream's `invertExtent` — `invert()` falls to it when there is no continuous inverse.
    // This used to report an error instead, refusing the question a chart asks when someone clicks
    // a legend swatch and expects the data behind it.
    if (scale is BinnedScale) {
      // A range value the scale never produces still answers a **pair** — `[NaN, NaN]` — rather
      // than nothing, so an expression reading `[0]` off it gets NaN instead of failing.
      val extent =
        scale.invertExtent(value)
          ?: return VegaValue.Arr(listOf(VegaValue.Num(Double.NaN), VegaValue.Num(Double.NaN)))
      return VegaValue.Arr(
        listOf(
          extent.first?.let { VegaValue.Num(it) } ?: VegaValue.Null,
          extent.second?.let { VegaValue.Num(it) } ?: VegaValue.Null,
        )
      )
    }
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
        // `[0, 1]`, which is d3's and never consulted by the scale itself: the pair exists so
        // `domain('name')` answers something rather than because anything reads it.
        is IdentityScale -> scale.domain.map { VegaValue.Num(it) }
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
      random,
      clock,
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
      random,
      clock,
      projections,
    )

  /** The same scope with a scope's cartographic projections readable by `geoCentroid()`. */
  public fun withProjections(projections: Map<String, ProjectionDefinition>): SignalScope =
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
      random,
      clock,
      projections,
    )

  /**
   * `geoCentroid('name', feature)`.
   *
   * A projection named but not declared is reported rather than quietly measured on the globe: the
   * two answers are in different units and differ by a factor of a hundred, so a chart that got the
   * wrong one would place its labels somewhere plausible and wrong.
   */
  override fun geoCentroid(projection: String?, geojson: VegaValue): VegaValue {
    val definition =
      if (projection == null) null
      else
        projections[projection]
          ?: run {
            diagnostics?.error(
              DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
              "geoCentroid() names projection '$projection', which this scope does not define",
              operator = projection,
            )
            return VegaValue.Null
          }
    val centre = GeoMeasure.centroid(definition, geojson) ?: return VegaValue.Null
    return VegaValue.Arr(listOf(VegaValue.Num(centre[0]), VegaValue.Num(centre[1])))
  }

  /** `geoArea('name', feature)`, in square units of the page. */
  override fun geoArea(projection: String?, geojson: VegaValue): VegaValue {
    val definition =
      if (projection == null) null
      else
        projections[projection]
          ?: run {
            diagnostics?.error(
              DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
              "geoArea() names projection '$projection', which this scope does not define",
              operator = projection,
            )
            return VegaValue.Null
          }
    return VegaValue.Num(GeoMeasure.area(definition, geojson))
  }

  /** `geoBounds('name', feature)` — `[[x0, y0], [x1, y1]]` in units of the page. */
  override fun geoBounds(projection: String?, geojson: VegaValue): VegaValue {
    val definition = projectionFor(projection, "geoBounds") ?: return VegaValue.Null
    val box = GeoMeasure.bounds(definition.orNull(), geojson) ?: return VegaValue.Null
    return VegaValue.Arr(
      listOf(
        VegaValue.Arr(listOf(VegaValue.Num(box[0]), VegaValue.Num(box[1]))),
        VegaValue.Arr(listOf(VegaValue.Num(box[2]), VegaValue.Num(box[3]))),
      )
    )
  }

  /**
   * `geoScale('name')` — the projection's own scale.
   *
   * Worth having for the same reason `fit` is: a fitted projection's scale is computed from the
   * data, so a chart that draws anything sized in projected units — a symbol whose radius is in
   * kilometres — has no other way to ask what one unit currently means.
   */
  /**
   * `warn(...)`, `info(...)` and `debug(...)` — routed to the diagnostics rather than to a console.
   *
   * Upstream writes these to the dataflow's logger at the matching level. There is no console here
   * and a compiled chart carries its diagnostics with it, so that is where they go: a specification
   * that asks a question of itself gets the answer in the same list as everything else the compile
   * has to say. The severity follows the function — `warn` warns, `info` and `debug` are
   * informational — because a specification choosing `warn` is choosing to be noticed.
   */
  override fun log(level: String, message: String) {
    val collector = diagnostics ?: return
    val text = "Expression $level: $message"
    if (level == "warn") {
      collector.warn(DiagnosticCodes.EXPRESSION_LOG, text)
    } else {
      collector.info(DiagnosticCodes.EXPRESSION_LOG, text)
    }
  }

  /**
   * `gradient(scale, p0, p1[, count])` — a colour scale as a gradient object.
   *
   * The stop list is upstream's: the scale's own ticks at `count` or fifteen, with the domain's two
   * ends forced in at either end, each offset being the value's *fraction* of the domain rather
   * than its position in the list — so a log scale's stops bunch up exactly as its colours do. The
   * object is the same shape a specification can write by hand, which is what makes it usable in a
   * `fill` without the encoder knowing where it came from.
   */
  override fun gradient(
    scale: String,
    from: VegaValue,
    to: VegaValue,
    count: VegaValue,
  ): VegaValue {
    val resolved = resolveScale(scale, "gradient") as? SequentialColorScale ?: return VegaValue.Null
    val requested = count.asNumberOrNull()?.takeIf { it.isFinite() && it >= 1.0 }?.toInt() ?: 15
    val lo = resolved.domain.first()
    val hi = resolved.domain.last()
    val values = LinkedHashSet<Double>()
    values += lo
    values += resolved.ticks(requested).filter { it in minOf(lo, hi)..maxOf(lo, hi) }
    values += hi
    val stops =
      values
        .sortedBy { resolved.fraction(it) }
        .mapNotNull { value ->
          resolved.colorAt(value)?.let { colour ->
            VegaValue.Obj(
              linkedMapOf(
                "offset" to VegaValue.Num(resolved.fraction(value)),
                "color" to VegaValue.Str(colour.toCssRgb()),
              )
            )
          }
        }
    fun coordinate(point: VegaValue, index: Int, fallback: Double): Double =
      ((point as? VegaValue.Arr)?.values?.getOrNull(index))?.asNumberOrNull()?.takeIf {
        it.isFinite()
      } ?: fallback
    return VegaValue.Obj(
      linkedMapOf(
        "gradient" to VegaValue.Str("linear"),
        "x1" to VegaValue.Num(coordinate(from, 0, 0.0)),
        "y1" to VegaValue.Num(coordinate(from, 1, 0.0)),
        "x2" to VegaValue.Num(coordinate(to, 0, 1.0)),
        "y2" to VegaValue.Num(coordinate(to, 1, 0.0)),
        "stops" to VegaValue.Arr(stops),
      )
    )
  }

  /** `geoShape('name', feature)` — the outline, which a `shape` channel draws directly. */
  override fun geoShape(projection: String?, geojson: VegaValue): VegaValue {
    val definition = projectionFor(projection, "geoShape") ?: return VegaValue.Null
    return GeoMeasure.shape(definition.orNull(), geojson)?.let { VegaValue.Str(it) }
      ?: VegaValue.Null
  }

  override fun geoScale(projection: String?): VegaValue {
    val definition = projectionFor(projection, "geoScale")?.orNull() ?: return VegaValue.Null
    return GeoMeasure.scaleOf(definition)?.let { VegaValue.Num(it) } ?: VegaValue.Null
  }

  /**
   * Looks up a named projection, reporting one this scope does not define.
   *
   * The outer null means "reported, give up"; an inner null means the caller passed no name at all,
   * which is a measurement on the globe rather than on the page.
   */
  private fun projectionFor(name: String?, caller: String): Optional? {
    if (name == null) return Optional(null)
    val definition = projections[name]
    if (definition == null) {
      diagnostics?.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        "$caller() names projection '$name', which this scope does not define",
        operator = name,
      )
      return null
    }
    return Optional(definition)
  }

  /** A found-or-absent projection, so the two kinds of null above stay distinguishable. */
  private class Optional(private val value: ProjectionDefinition?) {
    fun orNull(): ProjectionDefinition? = value
  }

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
  /** The one stream every scope this resolver builds draws from; see [SignalScope.random]. */
  private val random: RandomStream = RandomStream(),
  private val clock: Clock = Clock.Fixed,
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
        random = random,
        clock = clock,
      )

    public fun resolve(
      name: String,
      datasets: Map<String, List<VegaValue>>,
      scales: Map<String, VegaScale> = emptyMap(),
      pendingScales: Set<String> = emptySet(),
      /**
       * The projections `geoBounds()`, `geoScale()`, `geoCentroid()` and `geoArea()` can reach.
       *
       * Passed in per signal rather than held on the session, because a projection is *made of*
       * signals: it can only be built from the ones that have already settled, so the map is
       * different at every step of the dataflow order. A dataset's transforms have been given the
       * same thing since `geoCentroid` was implemented; a signal's own `update` had not, so
       * `geoScale('p')` reported the projection as undefined however late in the order it ran.
       */
      projections: Map<String, ProjectionDefinition> = emptyMap(),
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
        values[name] = evaluate(spec, datasets, scales, pendingScales, projections)
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
      projections: Map<String, ProjectionDefinition>,
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
            if (dependency != spec.name) {
              resolve(dependency, datasets, scales, pendingScales, projections)
            }
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
                  random = random,
                  clock = clock,
                )
                .withProjections(projections)
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
