package dev.aster.vega.runtime.compile

import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.model.spec.DataSpec
import dev.aster.vega.model.spec.DomainSpec
import dev.aster.vega.model.spec.FieldRef
import dev.aster.vega.model.spec.NumberValue
import dev.aster.vega.model.spec.ProjectionSpec
import dev.aster.vega.model.spec.RangeSpec
import dev.aster.vega.model.spec.ScaleSpec
import dev.aster.vega.model.spec.SchemeRef
import dev.aster.vega.model.spec.SignalSpec

/**
 * One thing a scope declares that has to be resolved before something else can read it.
 *
 * Upstream has no such enumeration because it has no phases: `vega-parser` adds every dataset,
 * scale and signal to one dataflow as an operator, wires each one's parameters to the operators it
 * reads, and lets the dataflow's topological ranking decide what runs when. This is that graph,
 * named, for a compiler that resolves in a single pass instead of pulsing a dataflow.
 */
internal sealed interface Operator {
  val name: String

  data class Data(override val name: String) : Operator

  data class Scale(override val name: String) : Operator

  data class Signal(override val name: String) : Operator
}

/**
 * The order to resolve a scope's datasets, scales and signals in, and any cycle found on the way.
 *
 * [order] holds every declared operator exactly once. A cycle cannot be ordered, so one operator on
 * it is placed arbitrarily and the whole cycle is reported: the chart then draws with that operator
 * reading whatever its neighbour held, which is wrong but visible, rather than not drawing at all.
 */
internal class DataflowOrder(
  val order: List<Operator>,
  /** What each operator waits on, kept so a caller can say which scales are still pending. */
  val dependencies: Map<Operator, Set<Operator>>,
) {

  companion object {

    /**
     * Ranks a scope's declarations by what they read.
     *
     * The three kinds are ordered *together*, which is the point. `probability-density` is the case
     * that needs it: `xscale`'s domain is `{"data": "points", "field": "u"}` so the scale waits on
     * the `points` dataset, and the `density` dataset's `extent` is `{"signal":
     * "domain('xscale')"}` so the dataset waits on the scale. No arrangement of "all data, then all
     * signals, then all scales" resolves both.
     *
     * Ties are broken towards signals, then datasets, then scales, in declaration order. That
     * matters for more than determinism: it is what keeps a signal reading no dataset ahead of
     * every dataset, so a transform parameter written as a signal still sees a value.
     */
    fun of(
      data: List<DataSpec>,
      scales: List<ScaleSpec>,
      signals: List<SignalSpec>,
      expressions: ExpressionCompiler,
      diagnostics: DiagnosticCollector,
      /**
       * The chart's projections, which are not operators here but are read like scales.
       *
       * Upstream registers a projection in the **same namespace as a scale** and visits
       * `geoScale('p')` with its `scaleVisitor`, so the reference already arrives as a scale
       * dependency. What it needs is somewhere to land: a projection with a `fit` is built from a
       * dataset, so a signal asking it anything has to wait for that dataset.
       */
      projections: List<ProjectionSpec> = emptyList(),
    ): DataflowOrder {
      // Distinct, because a name is what identifies an operator and a specification may declare the
      // same one twice. Upstream refuses that outright — "Duplicate data set name" — and this
      // reports it and lets the later definition win, which is how a duplicate *signal* has always
      // been handled here and leaves a chart to draw rather than nothing.
      val nodes =
        (signals.map { Operator.Signal(it.name) } +
            data.map { Operator.Data(it.name) } +
            scales.map { Operator.Scale(it.name) })
          .distinct()
      reportDuplicates(data.map { it.name }, "dataset", diagnostics)
      reportDuplicates(scales.map { it.name }, "scale", diagnostics)
      val reader = Reader(data, scales, signals, expressions, projections)
      val dependencies = nodes.associateWith { node ->
        // A declaration may not read itself; `{"name": "x", "update": "x + 1"}` is a self
        // reference upstream resolves against the previous value, and here there is none.
        reader.dependencies(node) - node
      }

      val emitted = LinkedHashSet<Operator>()
      val order = ArrayList<Operator>(nodes.size)
      while (order.size < nodes.size) {
        val ready = nodes.firstOrNull { node ->
          node !in emitted && dependencies.getValue(node).all { it in emitted }
        }
        val next =
          ready
            ?: run {
              // Nothing is ready, so everything left is on a cycle or behind one. Report the cycle
              // itself rather than the first thing stuck behind it, and break it there.
              val remaining = nodes.filterNot { it in emitted }
              val cycle = findCycle(remaining, dependencies)
              val broken = cycle?.first() ?: remaining.first()
              diagnostics.error(
                DiagnosticCodes.SIGNAL_CYCLE,
                if (cycle == null) {
                  "Cannot order ${describe(broken)}: it waits on something that never resolves"
                } else {
                  "Dependency cycle: ${cycle.joinToString(" -> ") { describe(it) }} -> " +
                    describe(cycle.first()) +
                    ". ${describe(broken)} is resolved first and reads the others before they " +
                    "have a value."
                },
                operator = broken.name,
              )
              broken
            }
        order.add(next)
        emitted.add(next)
      }
      return DataflowOrder(order, dependencies)
    }

    private fun reportDuplicates(
      names: List<String>,
      kind: String,
      diagnostics: DiagnosticCollector,
    ) {
      for ((name, count) in names.groupingBy { it }.eachCount()) {
        if (count > 1) {
          diagnostics.warn(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "Duplicate $kind '$name'; the later definition wins",
            operator = name,
          )
        }
      }
    }

    /** `signal 'x'`, for a message a reader has to act on. */
    private fun describe(operator: Operator): String =
      when (operator) {
        is Operator.Data -> "dataset '${operator.name}'"
        is Operator.Scale -> "scale '${operator.name}'"
        is Operator.Signal -> "signal '${operator.name}'"
      }

    /**
     * A cycle among [remaining], as the path that closed it, or null if none of them is on one.
     *
     * Depth-first from each candidate in turn, so the cycle reported is the one reachable from the
     * earliest declaration — which is stable, and is also the one whose break is least surprising.
     */
    private fun findCycle(
      remaining: List<Operator>,
      dependencies: Map<Operator, Set<Operator>>,
    ): List<Operator>? {
      val live = remaining.toSet()
      for (start in remaining) {
        val path = LinkedHashSet<Operator>()
        val found = walk(start, start, live, dependencies, path)
        if (found != null) return found
      }
      return null
    }

    private fun walk(
      node: Operator,
      target: Operator,
      live: Set<Operator>,
      dependencies: Map<Operator, Set<Operator>>,
      path: LinkedHashSet<Operator>,
    ): List<Operator>? {
      if (!path.add(node)) return null
      for (dependency in dependencies.getValue(node)) {
        if (dependency !in live) continue
        if (dependency == target) return path.toList()
        walk(dependency, target, live, dependencies, path)?.let {
          return it
        }
      }
      path.remove(node)
      return null
    }
  }

  /**
   * Reads one declaration's dependencies. Holds the declared names, so an unknown one is no edge.
   */
  private class Reader(
    data: List<DataSpec>,
    scales: List<ScaleSpec>,
    signals: List<SignalSpec>,
    private val expressions: ExpressionCompiler,
    projections: List<ProjectionSpec> = emptyList(),
  ) {
    private val dataSpecs = data.associateBy { it.name }

    /**
     * For each projection, the datasets its `fit` reads.
     *
     * Only `fit` matters: every other projection property is a number or a signal, and a signal is
     * already an operator that the ordering knows how to wait for.
     */
    /**
     * What each projection's `fit` waits for, as operators.
     *
     * Lazy because it is `readsOf` in disguise and that needs every other table built first. Going
     * through `readsOf` rather than reading `dataDependencies` directly is the point: a fit written
     * `{"signal": "data('land')"}` names a dataset, but one written `{"signal": "cloud"}` names a
     * signal a `geojson` transform **publishes**, and only `readsOf` knows to turn that into an
     * edge to the dataset whose pipeline publishes it.
     */
    private val projectionSources: Map<String, Set<Operator>> by lazy {
      projections.associate { projection ->
        val source = (projection.fit as? VegaValue.Obj)?.fields?.get("signal")?.asString()
        projection.name to (source?.let { readsOf(it) } ?: emptySet())
      }
    }

    /**
     * The datasets that publish each projection's fit, by name.
     *
     * A fit written `{"signal": "[geo_a, geo_b]"}` names two signals, and a `geojson` transform in
     * some dataset writes each. Knowing *which* datasets those are is what tells a mutual wait (two
     * publishers, no order) from an ordinary one (a reader waiting for someone else's fit).
     */
    private val projectionPublishers: Map<String, Set<String>> by lazy {
      projections.associate { projection ->
        val source = (projection.fit as? VegaValue.Obj)?.fields?.get("signal")?.asString()
        val names = mutableSetOf<String>()
        if (source != null) {
          for (dataset in data) {
            for (transform in dataset.transform) {
              val published =
                ((transform as? VegaValue.Obj)?.fields?.get("signal") as? VegaValue.Str)?.value
              if (
                published != null &&
                  Regex("\\b${Regex.escape(published)}\\b").containsMatchIn(source)
              ) {
                names += dataset.name
              }
            }
          }
        }
        projection.name to names
      }
    }

    /** Which projection each transform type names, so a dataset waits for that projection's fit. */
    private val projectionParameter = "projection"
    private val scaleSpecs = scales.associateBy { it.name }
    private val signalSpecs = signals.associateBy { it.name }

    /**
     * Signals a transform **writes**, mapped to the dataset whose pipeline writes them.
     *
     * `{"type": "extent", "field": "v", "signal": "span"}` publishes `span`; `{"type": "bin",
     * "signal": "bins"}` publishes the bin settings it chose. Upstream treats these as ordinary
     * signals — `parseTransform` does `scope.addSignal(spec.signal, scope.proxy(t))`, so the signal
     * is an operator standing in for the transform — which puts everything reading one behind the
     * dataset that produces it.
     *
     * They are not declared anywhere, so without this a signal reading `bins` looked like a signal
     * reading nothing and was resolved first, against a name that had no value yet. `bin`'s own
     * `signal` is a plain string beside the other parameters, which is exactly how the transform
     * pipeline tells a *written* signal from a `{"signal": "..."}` reference it should read.
     */
    /**
     * Datasets a *signal* replaces with `setdata`, mapped to the signal that does it.
     *
     * The write happens when the signal resolves, so everything reading that dataset has to be
     * ordered behind the signal as well as behind the dataset's own declaration. Vega's pacman
     * builds each ghost's next move that way, in a signal, into a dataset a mark then draws.
     */
    private val dataWriters: Map<String, String> =
      signals
        .flatMap { signal ->
          val source = signal.expression ?: return@flatMap emptyList()
          val compiled = expressions.compile(source)
          if (compiled !is ExpressionResult.Compiled) return@flatMap emptyList()
          compiled.expression.writtenDatasets.map { it to signal.name }
        }
        .toMap()

    /**
     * Which parameter of each transform holds a per-row expression, by transform type.
     *
     * Three, and they are upstream's three: probe `T.Definition.params` over `vega.transforms` for
     * `type: 'expr'` and nothing else comes back.
     */
    private val EXPRESSION_PARAMETERS =
      mapOf("filter" to "expr", "formula" to "expr", "cross" to "filter")

    private val publishers: Map<String, String> =
      data
        .flatMap { spec ->
          spec.transform.mapNotNull { transform ->
            ((transform as? VegaValue.Obj)?.fields?.get("signal") as? VegaValue.Str)?.value?.let {
              it to spec.name
            }
          }
        }
        .toMap()

    fun dependencies(operator: Operator): Set<Operator> =
      when (operator) {
        is Operator.Data -> dataSpecs[operator.name]?.let(::dependenciesOf) ?: emptySet()
        is Operator.Scale -> scaleSpecs[operator.name]?.let(::dependenciesOf) ?: emptySet()
        is Operator.Signal -> signalSpecs[operator.name]?.let(::dependenciesOf) ?: emptySet()
      }

    /**
     * A signal reads whatever its expression reads.
     *
     * A signal with a plain `value` reads nothing, which is what puts every one of them first.
     */
    private fun dependenciesOf(spec: SignalSpec): Set<Operator> {
      val source = spec.expression ?: return emptySet()
      val result = readsOf(source).toMutableSet()
      // A signal that **writes** a dataset has to run after that dataset exists, or its rows are
      // laid down first and then overwritten by the dataset's own declaration. Upstream never has
      // the
      // question: `modify` and `setdata` pulse a changeset that the dataflow applies once
      // everything
      // has settled, so the write is always last by construction.
      val compiled = expressions.compile(source)
      if (compiled is ExpressionResult.Compiled) {
        for (name in compiled.expression.writtenDatasets) {
          if (name in dataSpecs) result.add(Operator.Data(name))
        }
      }
      return result
    }

    /**
     * A dataset reads the dataset it sources from, and whatever its parameters reach for.
     *
     * Almost any transform parameter may be `{"signal": "..."}`, and the expression inside is where
     * a dataset comes to depend on a scale: `{"extent": {"signal": "domain('xscale')"}}` is how a
     * density is computed over exactly the span an axis will show.
     */
    private fun dependenciesOf(spec: DataSpec): Set<Operator> {
      val result = mutableSetOf<Operator>()
      for (source in spec.sources) if (source in dataSpecs) result.add(Operator.Data(source))
      spec.urlSignal?.let { result.addAll(readsOf(it)) }
      for (transform in spec.transform) {
        collectSignalReferences(transform, result)
        collectTransformExpressions(transform, result)
        // A `geopath`, `geopoint` or `geoshape` names its projection in a plain string, and a
        // *fitted* projection is built from data — so a dataset that places rows through one waits
        // for whatever the fit reads. Without this, a `geopoint` under a projection fitted to what
        // another dataset published placed every row through the family's unfitted default: right
        // shape, wrong scale, and nothing said.
        val named =
          ((transform as? VegaValue.Obj)?.fields?.get(projectionParameter) as? VegaValue.Str)?.value
        named?.let { projection ->
          // …**unless this dataset is one of the publishers**, and there is more than one. Two
          // datasets that each publish part of a fit and each read it back wait for one another,
          // which is a cycle with no ordering to find: whichever went first would read a fit built
          // from half the geometry. Upstream has no such knot because its operators are per
          // transform. `SpecCompiler` resolves these datasets a second time once every publisher
          // has published, so the edge would only forbid an order that the second pass makes
          // correct anyway. A reader that publishes *nothing* still waits, because for it the first
          // pass is the only one.
          val publishers = projectionPublishers[projection] ?: emptySet()
          if (publishers.size > 1 && spec.name in publishers) return@let
          projectionSources[projection]?.let(result::addAll)
        }
      }
      return result
    }

    /**
     * The transform parameters upstream declares as `type: 'expr'`, which are edges after all.
     *
     * `filter`'s `expr`, `formula`'s `expr` and `cross`'s `filter` are per-row expressions rather
     * than `{"signal": ...}` references, and it is tempting to conclude they cannot be
     * dependencies. Upstream's `parseExpression` says otherwise: it walks *every* expression's AST
     * and lets the `scale`, `data` and `indata` visitors register what they find as operator
     * **parameters**, so a `formula` calling `scale('x', datum.v)` waits for that scale exactly as
     * a signal-valued parameter would. Vega's serpentine timeline is written that way, and without
     * the edge its scale is asked for before it exists.
     */
    private fun collectTransformExpressions(transform: VegaValue, into: MutableSet<Operator>) {
      val obj = transform as? VegaValue.Obj ?: return
      val type = (obj.fields["type"] as? VegaValue.Str)?.value?.lowercase()
      EXPRESSION_PARAMETERS[type]?.let { parameter ->
        (obj.fields[parameter] as? VegaValue.Str)?.value?.let { into.addAll(readsOf(it)) }
      }
      collectExprObjects(obj, into)
    }

    /**
     * Every `{"expr": "..."}` anywhere in a parameter tree, however deeply nested.
     *
     * The second of the two spellings a per-row expression takes. Three parameters hold one as a
     * bare string because upstream declares them `type: 'expr'`; everywhere else it is this object
     * standing in for a field accessor, which is how `kde2d` takes `{"x": {"expr": "scale('x',
     * datum.Horsepower)"}}`. Walking the tree for it is what upstream's AST walk amounts to.
     */
    private fun collectExprObjects(value: VegaValue, into: MutableSet<Operator>) {
      when (value) {
        is VegaValue.Obj -> {
          (value.fields["expr"] as? VegaValue.Str)?.value?.let { into.addAll(readsOf(it)) }
          value.fields.values.forEach { collectExprObjects(it, into) }
        }
        is VegaValue.Arr -> value.values.forEach { collectExprObjects(it, into) }
        else -> Unit
      }
    }

    /**
     * A scale reads the datasets its domain names and every signal-valued property it carries.
     *
     * The `"width"` and `"height"` ranges are the subtle ones: they resolve against the plotting
     * area, which *is* the `width` and `height` signals, so a chart that declares either as a
     * signal has scales that wait on it. A chart that does not has nothing to wait for, because the
     * seeded value is already the answer.
     */
    private fun dependenciesOf(spec: ScaleSpec): Set<Operator> {
      val result = mutableSetOf<Operator>()
      collectDomain(spec.domain, result)
      collectRange(spec.range, result)
      for (number in
        listOf(
          spec.domainMin,
          spec.domainMax,
          spec.domainMid,
          spec.padding,
          spec.paddingInner,
          spec.paddingOuter,
          spec.align,
          spec.base,
          spec.exponent,
          spec.constant,
        )) {
        if (number is NumberValue.Signal) result.addAll(readsOf(number.expression))
      }
      return result
    }

    private fun collectDomain(domain: DomainSpec, into: MutableSet<Operator>) {
      when (domain) {
        is DomainSpec.FromField -> {
          if (domain.data in dataSpecs) into.add(Operator.Data(domain.data))
          // The *name* of the column may itself come from a signal, so the scale cannot be built
          // until that signal has a value.
          (domain.field as? FieldRef.Signal)?.let { into.addAll(readsOf(it.expression)) }
        }
        is DomainSpec.FromFields ->
          if (domain.data in dataSpecs) into.add(Operator.Data(domain.data))
        is DomainSpec.Union -> domain.parts.forEach { collectDomain(it, into) }
        is DomainSpec.FromSignal -> into.addAll(readsOf(domain.expression))
        is DomainSpec.Literal,
        DomainSpec.Unset -> Unit
      }
    }

    private fun collectRange(range: RangeSpec, into: MutableSet<Operator>) {
      when (range) {
        is RangeSpec.Signal -> into.addAll(readsOf(range.expression))
        is RangeSpec.Step ->
          (range.step as? NumberValue.Signal)?.let { into.addAll(readsOf(it.expression)) }
        is RangeSpec.Scheme ->
          (range.scheme as? SchemeRef.Signal)?.let { into.addAll(readsOf(it.expression)) }
        is RangeSpec.FromField -> if (range.data in dataSpecs) into.add(Operator.Data(range.data))
        // `"width"` and `"height"` are the plotting area, which the same-named signals settle.
        is RangeSpec.Named ->
          range.name.lowercase().let { if (it in signalSpecs) into.add(Operator.Signal(it)) }
        // An element of a range array may be a signal on its own: `[{"signal": "barStep"}, 0]`.
        is RangeSpec.Literal -> range.values.forEach { collectSignalReferences(it, into) }
        RangeSpec.Unset -> Unit
      }
    }

    /**
     * Walks raw specification JSON for `{"signal": "..."}` references and reads each one.
     *
     * The same rule the transform pipeline applies: an object is a reference only when `signal` is
     * its **sole** field, because `extent` takes a `signal` parameter naming the signal it *writes*
     * and that is a plain string beside other fields.
     */
    private fun collectSignalReferences(value: VegaValue, into: MutableSet<Operator>) {
      when (value) {
        is VegaValue.Obj -> {
          val reference = value.fields["signal"]
          if (value.fields.size == 1 && reference is VegaValue.Str) {
            into.addAll(readsOf(reference.value))
          } else {
            value.fields.values.forEach { collectSignalReferences(it, into) }
          }
        }
        is VegaValue.Arr -> value.values.forEach { collectSignalReferences(it, into) }
        else -> Unit
      }
    }

    /**
     * What one expression reads, as operators.
     *
     * A name this scope does not declare is no edge: an implicit signal is already settled, and
     * anything else is a mistake reported where it is read rather than silently ordered around.
     */
    private fun readsOf(source: String): Set<Operator> {
      val compiled = expressions.compile(source)
      if (compiled !is ExpressionResult.Compiled) return emptySet()
      val expression = compiled.expression
      val result = mutableSetOf<Operator>()
      for (name in expression.signalDependencies) {
        // A declared signal is its own operator. One a transform publishes stands for that
        // transform, so reading it means waiting for the dataset that runs it.
        if (name in signalSpecs) result.add(Operator.Signal(name))
        else publishers[name]?.let { result.add(Operator.Data(it)) }
      }
      for (name in expression.dataDependencies) {
        if (name in dataSpecs) result.add(Operator.Data(name))
        // A dataset something else *writes* is only complete once that writer has run.
        dataWriters[name]?.let { result.add(Operator.Signal(it)) }
      }
      if (expression.readsUnnamedDataset) {
        dataSpecs.keys.forEach { result.add(Operator.Data(it)) }
      }
      for (name in expression.scaleDependencies) {
        if (name in scaleSpecs) result.add(Operator.Scale(name))
        // A projection is read through the same functions a scale is, and a *fitted* one is built
        // from data — so asking it anything means waiting for whatever it fits. Without this,
        // `geoScale('p')` on a projection fitted to `data('land')` answered with the family's
        // unfitted default: `albers` reported 1070 where upstream reported 34.3, because the fit
        // had
        // not happened yet.
        projectionSources[name]?.let { result.addAll(it) }
      }
      if (expression.readsUnnamedScale) {
        scaleSpecs.keys.forEach { result.add(Operator.Scale(it)) }
      }
      return result
    }
  }
}
