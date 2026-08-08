package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionEvaluationException
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import dev.aster.vega.model.isMissing

/**
 * Everything a transform may read or report.
 *
 * Passed in rather than reachable from ambient state, so a transform's inputs are visible at its
 * call site and a pipeline can be run in isolation by a test.
 */
public interface TransformContext {
  public val diagnostics: DiagnosticCollector

  /** Shared so repeated expression text is parsed once across a whole specification. */
  public val expressions: ExpressionCompiler

  /** Signals and datasets an expression can read. Datum is supplied per tuple. */
  public val scope: ExpressionScope

  /** Transforms like `extent` publish a signal rather than changing the data. */
  public fun setSignal(name: String, value: VegaValue)

  /** Sets the datum an expression sees. */
  public fun scopeFor(datum: VegaValue): ExpressionScope

  /**
   * The tree a `stratify` or `nest` built, for a layout transform later in the same pipeline.
   *
   * It rides on the pipeline rather than on the tuples because that is where it lives: a
   * specification's rows go into `stratify` and come out unchanged, and the layout after it writes
   * coordinates back onto those same rows. Nothing downstream ever sees a nested structure.
   * Upstream keeps it in the same place, hanging it off the source array as `source.root`.
   */
  public var tree: TreeSource?
}

/**
 * The tree a hierarchy transform passes to the layout after it, kept opaque on purpose.
 *
 * Nothing outside `vega-dataflow` can do anything with a tree — a mark reads the coordinates a
 * layout wrote onto the rows, never the structure — so this carries no members. Widening it later
 * is easy; narrowing a published node type would not be.
 */
public interface TreeSource {
  /**
   * The rows on the shortest path from one node to another, as `treePath()` reports them.
   *
   * Up from the first node to the least common ancestor and back down to the second, inclusive at
   * both ends — d3-hierarchy's `node.path`, which is what upstream calls. Null when either key
   * names no node in this tree, which is how `treePath` reports a link to something that was
   * filtered out.
   *
   * **Positions**, not rows. Upstream mutates its tuples, so a node's datum gains whatever later
   * transforms wrote on it; this engine copies, so the rows the tree was built from are stale by
   * the time anything asks. The index is a position in the dataset as it stands now, which is how
   * every other consumer of a tree finds a row too.
   */
  public fun pathBetween(fromKey: String, toKey: String): List<Int>? = null

  /** A node's own row and every ancestor's, the root last. */
  public fun ancestorsOf(key: String): List<Int>? = null
}

/**
 * One data transform.
 *
 * Transforms are pure functions from a tuple list to a tuple list: they never mutate their input.
 * Upstream Vega does mutate tuples in place — which is why its own test fixtures contaminate each
 * other if you reuse an input array — and copying instead costs an allocation per changed tuple but
 * makes the pipeline reasoning local and the results reproducible.
 */
public interface Transform {
  /** The `type` name in a specification, e.g. `"filter"`. */
  public val type: String

  /**
   * Whether this transform honours a top-level `"signal"` naming a signal it **writes**.
   *
   * Upstream accepts one on *every* transform — `parseTransform` does `scope.addSignal(spec.signal,
   * scope.proxy(t))` — and what gets published is the transform operator's own value, which differs
   * by transform: `extent` publishes `[min, max]`, `bin` publishes the bin settings it chose, most
   * publish their tuples. There is no uniform value to hand over here, so each transform says for
   * itself, and [TransformPipeline] reports the ones that would otherwise drop the request
   * silently.
   */
  public val publishesSignal: Boolean
    get() = false

  public fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue>
}

/**
 * Runs a transform pipeline.
 *
 * An unknown or unimplemented transform stops the pipeline at that point and reports
 * `VEGA_TRANSFORM_NOT_IMPLEMENTED`, returning what the earlier stages produced. Continuing past it
 * would feed later stages data they were never meant to see, and silently produce a
 * plausible-looking wrong answer.
 */
public class TransformPipeline(
  private val registry: TransformRegistry = TransformRegistry.Default
) {

  public fun run(
    input: List<VegaValue>,
    transforms: List<VegaValue>,
    context: TransformContext,
  ): List<VegaValue> {
    var current = input
    for ((index, definition) in transforms.withIndex()) {
      val params = definition as? VegaValue.Obj
      if (params == null) {
        context.diagnostics.error(
          DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          "Transform $index is not an object",
        )
        return current
      }
      val type = params.fields["type"]?.asString()
      if (type.isNullOrEmpty()) {
        context.diagnostics.error(
          DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          "Transform $index has no type",
        )
        return current
      }
      val resolved = resolveSignals(params, context) as VegaValue.Obj
      val transform = registry[type]
      if (transform == null) {
        context.diagnostics.error(
          DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
          "Transform '$type' is not implemented; the pipeline stopped here, so later " +
            "transforms did not run and the data is as of the previous stage",
          operator = type,
        )
        return current
      }
      // A top-level `"signal"` names a signal this transform is expected to *write*. Only a string
      // counts: `{"signal": "..."}` as the sole field is a reference to read, and `resolveSignals`
      // has already replaced it above.
      val published = (params.fields["signal"] as? VegaValue.Str)?.value
      if (published != null && !transform.publishesSignal) {
        context.diagnostics.error(
          DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
          "Transform '$type' was asked to publish signal '$published', which is not implemented; " +
            "the signal keeps whatever value it already had, and anything reading it reads that",
          operator = type,
        )
      }
      current = transform.apply(current, resolved, context)
    }
    return current
  }
}

/**
 * Replaces every `{"signal": "..."}` in a parameter tree with what the signal holds.
 *
 * Vega lets almost any parameter be signal-valued, and until this existed such a parameter reached
 * the transform as an object whose string form is `signal:name` — so `{"op": {"signal": "op"}}`
 * became the aggregate operation literally called "signal:op", and was reported as unimplemented. A
 * dozen of the official examples failed that way, each looking like a different missing feature.
 *
 * Resolving here rather than in each transform means none of them can forget, and none of them
 * needs to know that signals exist.
 *
 * Only an object whose **sole** field is `signal` is a reference. That distinction matters: the
 * `extent` transform takes a `signal` parameter naming the signal it *writes*, and its value is a
 * string rather than an object, so it is untouched.
 */
internal fun resolveSignals(value: VegaValue, context: TransformContext): VegaValue =
  when (value) {
    is VegaValue.Obj -> {
      val reference = value.fields["signal"]
      if (value.fields.size == 1 && reference is VegaValue.Str) {
        evaluateSignal(reference.value, context)
      } else {
        VegaValue.Obj(value.fields.mapValues { (_, child) -> resolveSignals(child, context) })
      }
    }
    is VegaValue.Arr -> VegaValue.Arr(value.values.map { resolveSignals(it, context) })
    else -> value
  }

private fun evaluateSignal(expression: String, context: TransformContext): VegaValue =
  when (val compiled = context.expressions.compile(expression)) {
    is ExpressionResult.Failed -> {
      context.diagnostics.add(compiled.diagnostic)
      VegaValue.Null
    }
    is ExpressionResult.Compiled ->
      try {
        compiled.expression.evaluate(context.scope)
      } catch (failure: ExpressionEvaluationException) {
        context.diagnostics.add(failure.diagnostic)
        VegaValue.Null
      }
  }

/** Maps a specification's transform `type` to an implementation. */
public class TransformRegistry(transforms: List<Transform>) {

  private val byType: Map<String, Transform> = transforms.associateBy { it.type }

  public operator fun get(type: String): Transform? = byType[type]

  public val types: Set<String>
    get() = byType.keys

  public companion object {
    /** The transforms the brief lists for the first release (PROJECT_BRIEF.md 3.2). */
    public val Default: TransformRegistry =
      TransformRegistry(
        listOf(
          FilterTransform,
          FormulaTransform,
          CollectTransform,
          ProjectTransform,
          IdentifierTransform,
          ExtentTransform,
          AggregateTransform,
          JoinAggregateTransform,
          BinTransform,
          StackTransform,
          FoldTransform,
          FlattenTransform,
          TimeUnitTransform,
          PieTransform,
          WindowTransform,
          SequenceTransform,
          LookupTransform,
          ImputeTransform,
          CrossTransform,
          PivotTransform,
          CountPatternTransform,
          QuantileTransform,
          RegressionTransform,
          LoessTransform,
          KdeTransform,
          DensityTransform,
          DotBinTransform,
          StratifyTransform,
          NestTransform,
          TreemapTransform,
          PartitionTransform,
          PackTransform,
          TreeTransform,
          TreeLinksTransform,
          LinkPathTransform,
        )
      )
  }
}

// ---- shared helpers ---------------------------------------------------------

/** Reads a parameter as a list of strings, accepting Vega's single-value shorthand. */
internal fun VegaValue.Obj.stringList(key: String): List<String> {
  val value = fields[key] ?: return emptyList()
  return when (value) {
    is VegaValue.Arr -> value.values.map { it.asString() }
    is VegaValue.Null -> emptyList()
    else -> listOf(value.asString())
  }
}

internal fun VegaValue.Obj.numberList(key: String): List<Double> {
  val value = fields[key] ?: return emptyList()
  return when (value) {
    is VegaValue.Arr -> value.values.map { it.asDouble() }
    is VegaValue.Null -> emptyList()
    else -> listOf(value.asDouble())
  }
}

internal fun VegaValue.Obj.number(key: String): Double? =
  fields[key]?.asDouble()?.takeIf { !it.isNaN() }

internal fun VegaValue.Obj.string(key: String): String? =
  fields[key]?.takeIf { it !is VegaValue.Null }?.asString()

internal fun VegaValue.Obj.boolean(key: String): Boolean? =
  when (val value = fields[key]) {
    null,
    is VegaValue.Null -> null
    is VegaValue.Bool -> value.value
    else -> null
  }

/** Returns a copy of [this] tuple with [updates] applied. Transforms never mutate their input. */
internal fun VegaValue.withFields(updates: Map<String, VegaValue>): VegaValue {
  val existing = (this as? VegaValue.Obj)?.fields ?: emptyMap()
  val merged = LinkedHashMap<String, VegaValue>(existing.size + updates.size)
  merged.putAll(existing)
  merged.putAll(updates)
  return VegaValue.Obj(merged)
}

internal fun VegaValue.withField(name: String, value: VegaValue): VegaValue =
  withFields(mapOf(name to value))

/**
 * Compiles and evaluates an expression parameter once per tuple.
 *
 * A failure is reported once for the whole transform rather than once per tuple: the expression
 * fails identically for every row, and a large dataset would otherwise bury every other diagnostic.
 */
internal class TupleExpression(
  private val source: String,
  private val context: TransformContext,
  private val operator: String,
) {
  private val compiled = context.expressions.compile(source)
  private var reported = false

  init {
    if (compiled is ExpressionResult.Failed) report(compiled.diagnostic)
  }

  val isUsable: Boolean
    get() = compiled is ExpressionResult.Compiled

  fun evaluate(datum: VegaValue): VegaValue? {
    val expression = (compiled as? ExpressionResult.Compiled)?.expression ?: return null
    return try {
      expression.evaluate(context.scopeFor(datum))
    } catch (e: ExpressionEvaluationException) {
      report(e.diagnostic)
      null
    }
  }

  private fun report(diagnostic: dev.aster.vega.model.VegaDiagnostic) {
    if (reported) return
    reported = true
    context.diagnostics.add(diagnostic.copy(operator = operator))
  }
}

/**
 * Vega's ascending comparator for arbitrary field values.
 *
 * Null and NaN sort first in ascending order, which is what upstream's `collect` does — verified,
 * and the opposite of the SQL convention many people expect. A descending sort negates the result,
 * so those values move to the end rather than staying pinned at the front.
 *
 * Public because a discrete scale domain's `sort` orders its values with the same comparator, and
 * two orderings that were meant to agree are the kind of thing that silently stops agreeing.
 */
public fun compareFieldValues(left: VegaValue, right: VegaValue): Int {
  val leftMissing = left.isMissing
  val rightMissing = right.isMissing
  if (leftMissing || rightMissing) {
    return when {
      leftMissing && rightMissing -> 0
      leftMissing -> -1
      else -> 1
    }
  }
  val leftNumber = left.asDouble()
  val rightNumber = right.asDouble()
  if (!leftNumber.isNaN() && !rightNumber.isNaN()) return leftNumber.compareTo(rightNumber)
  return left.asString().compareTo(right.asString())
}

/** Builds a comparator from Vega's `{field, order}` sort parameter, accepting arrays for both. */
internal fun sortComparator(sort: VegaValue?): Comparator<VegaValue>? {
  val spec = sort as? VegaValue.Obj ?: return null
  // An **empty** field name orders nothing. It reaches here from a specification that offers
  // sorting
  // as an option and leaves it switched off — `{"field": {"signal": "sortField"}}` with `sortField`
  // an empty string — and upstream reads it as a property no row has, so every comparison is a tie
  // and the declared order survives. This engine reads an empty path as *the datum itself*, which
  // compares two whole objects and reorders the data; a labelled donut then draws its slices in the
  // wrong places, and nothing says so.
  val fields = spec.stringList("field").filter { it.isNotEmpty() }
  if (fields.isEmpty()) return null
  val orders = spec.stringList("order")
  return Comparator { a, b ->
    for ((index, path) in fields.withIndex()) {
      val descending = orders.getOrNull(index)?.startsWith("desc") == true
      val comparison = compareFieldValues(a.field(path), b.field(path))
      if (comparison != 0) return@Comparator if (descending) -comparison else comparison
    }
    0
  }
}
