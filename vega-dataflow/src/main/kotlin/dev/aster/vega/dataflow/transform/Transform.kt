@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionEvaluationException
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import dev.aster.vega.model.isNullish
import kotlinx.datetime.TimeZone

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

  /**
   * What **local** time means for this compile, or null for the device's own zone.
   *
   * Only `timeunit` reads it — `{"timezone": "local"}` and the default — and it reads it for the
   * two things a bucket needs a zone for: which day an instant falls on, and which zone a naive
   * date string in the data was written in. Upstream has no such input because a browser is always
   * on the device it draws for; an app whose reader lives in a different zone from the device is
   * exactly the case that needs it. See `VegaTimeZones`.
   */
  public val timeZone: TimeZone?
    get() = null

  /**
   * The order the rows **were created in**, which is what a sort breaks its ties by.
   *
   * ```js
   * export function stableCompare(cmp, f) {
   *   return !cmp ? null
   *     : (a, b) => cmp(a, b) || (tupleid(a) - tupleid(b));
   * }
   * ```
   *
   * Upstream's `collect` — and `window`'s own `sort`, and `dotbin` — resolve a tie by **tuple id**,
   * the number stamped on a tuple when it was made. That is not the order the rows are in: a table
   * sorted by one field and then by another breaks the second sort's ties by where the rows
   * started, not by where the first sort left them. Sorting stably on the current order instead
   * reverses such a group, which decides which seat of a parliament diagram gets which colour.
   *
   * Indexed as the rows currently stand: entry `i` is the ordinal of the row at position `i`. Null
   * where nothing is tracking it — a transform run outside a pipeline — and a sort is then plain
   * stable, which is what it was before.
   *
   * The ordinals are a **pipeline's own**: the rows arrive in creation order and this follows them
   * through it. A dataset built from another that had already been sorted therefore starts from
   * that sorted order, where upstream would still have the original ids; nothing in the corpora
   * reaches that, and it is the one case this does not answer.
   */
  public fun creationOrder(): IntArray? = null

  /** Records the order after a transform has **permuted** its rows; see [creationOrder]. */
  public fun reordered(order: IntArray) {}

  /**
   * What a layout leaves behind for the **next pass over the same chart**, and finds again there.
   *
   * `resquarify` is the reason: it keeps the rows a squarified treemap chose and re-applies them at
   * the new size, so the rectangles hold still while the numbers move. Upstream keeps them on the
   * node — `parent._squarify` — because its nodes live as long as the view does. A compile here is
   * a pure function and builds a fresh tree every time, so there is nowhere on a node to keep them;
   * a chart that compiles **twice**, which is every `autosize: "fit"` chart, would then tile its
   * second pass from scratch and land somewhere upstream does not. Verified: the same template with
   * `squarify` lands where this engine used to, and with `resquarify` it lands where the first pass
   * put it.
   *
   * Null where nothing is carrying it — a bare pipeline, a chart that compiles once — and a layout
   * then tiles afresh, which is what upstream does on its own first pass.
   */
  public val layoutMemory: LayoutMemory?
    get() = null

  /** The name this context's layouts remember themselves under; see [layoutMemory]. */
  public val layoutScope: String
    get() = ""

  /** Transforms like `extent` publish a signal rather than changing the data. */
  public fun setSignal(name: String, value: VegaValue)

  /** Sets the datum an expression sees. */
  public fun scopeFor(datum: VegaValue): ExpressionScope

  /**
   * A cartographic projection this scope defines, with its signals already resolved.
   *
   * Named like a scale and reached like one, but it is not one: a `geoshape` transform asks for it
   * by name and gets a description rather than a function, because the projection itself is a
   * stream pipeline this module builds. A scope with no projections answers null to everything,
   * which is what a bare transform pipeline should do.
   */
  public fun projection(name: String): ProjectionDefinition? = null

  /**
   * How wide a string is, in the same units a mark is measured in.
   *
   * Only `label` needs it, and it needs it for the same reason upstream does: a label's width
   * decides whether it fits, and there is no way to know it without measuring. A context with no
   * text engine falls back to upstream's own canvas-less approximation, which is what the
   * differential harness measures with anyway.
   */
  public fun measureText(text: String, fontSize: Double): Double =
    (0.8 * text.length * fontSize).toInt().toDouble()

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
 * What the layouts of one chart remember across the passes it is compiled in.
 *
 * Only `resquarify` uses it today, and it is deliberately not a general cache: a compile is a pure
 * function of the specification, and the one thing that legitimately crosses from a measuring pass
 * to the pass that is drawn is the *choice* a layout already made — which is exactly what upstream
 * keeps on its long-lived nodes. Nothing here changes a number; it only says how a tiling was cut.
 *
 * Keyed by where the layout ran — the dataset's name and the transform's place in its pipeline — so
 * a chart with two treemaps keeps them apart. Not thread-safe, and does not need to be: a compile
 * runs on one thread and each pass runs to completion before the next begins.
 */
public class LayoutMemory {
  private val rows = HashMap<String, List<List<TileRowShape>>>()

  public fun remember(key: String, value: List<List<TileRowShape>>) {
    rows[key] = value
  }

  public fun recall(key: String): List<List<TileRowShape>>? = rows[key]
}

/** One tiled row as a layout may reuse it: how many children, and which way it was cut. */
public data class TileRowShape(public val count: Int, public val dice: Boolean)

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
  @InternalAsterVegaApi
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
    // The rows arrive in the order they were made, so that is where the creation ordinals start.
    // See [TransformContext.creationOrder] for what they are for and what they do not cover.
    val order = RowOrder(input.size)
    @Suppress("NAME_SHADOWING") val context = order.tracking(context)
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
      reportUnevaluatedFieldExpressions(type, resolved, context)
      val before = current.size
      current = transform.apply(current, resolved, context)
      order.after(before, current.size)
    }
    return current
  }
}

/**
 * The creation ordinals of a pipeline's rows, as they stand at each stage.
 *
 * Kept by the pipeline rather than by the rows because a row here is a **value**: two rows that say
 * the same thing are the same value, so there is nowhere on one to write which of them it is.
 * Upstream writes an id on the tuple and keeps it for the tuple's life; this follows the rows
 * through one pipeline instead, which is the span a sort's tie-break is decided in.
 */
internal class RowOrder(size: Int) {
  private var ordinals = IntArray(size) { it }
  /** Where the next created row's ordinal comes from, so rows a transform makes keep arriving. */
  private var next = size

  fun current(): IntArray = ordinals

  fun permute(order: IntArray) {
    if (order.size == ordinals.size) ordinals = IntArray(order.size) { ordinals[order[it]] }
  }

  /**
   * What a transform that did not say otherwise leaves behind.
   *
   * Same count: the rows correspond one for one and keep their ordinals — which is every transform
   * that only writes fields, `formula` and `window` among them. A different count: rows were made
   * or dropped, and the ones that come out are numbered afresh in the order they come out in. That
   * is exactly right for a transform that *creates* rows, and harmless for one that drops them,
   * since a tie-break only ever compares two ordinals and dropping rows leaves the rest in order.
   */
  fun after(before: Int, after: Int) {
    if (before == after) return
    ordinals = IntArray(after) { next + it }
    next += after
  }

  fun tracking(base: TransformContext): TransformContext = Tracking(base, this)

  private class Tracking(base: TransformContext, private val order: RowOrder) :
    TransformContext by base {
    override fun creationOrder(): IntArray = order.current()

    override fun reordered(order: IntArray) {
      this.order.permute(order)
    }
  }
}

/**
 * Sorts as upstream's `stableCompare` does: by the comparator, and then by **creation order**.
 *
 * Returns the rows in their new order and tells the pipeline how they moved, so a later sort in the
 * same pipeline breaks its own ties by where the rows started rather than by where this one left
 * them. See [TransformContext.creationOrder].
 */
internal fun sortStably(
  input: List<VegaValue>,
  comparator: Comparator<VegaValue>,
  context: TransformContext,
): List<VegaValue> {
  val order = input.indices.sortedWith(byCreation(input, comparator, context))
  context.reordered(order.toIntArray())
  return order.map { input[it] }
}

/**
 * Compares two **positions** in [input] by [comparator], and a tie by creation order.
 *
 * Positions rather than rows, because a row is a value: two rows that say the same thing are the
 * same value and there is nothing on one to tell it from the other. Used by every sort upstream
 * runs through `stableCompare` — `collect`, and the within-partition sorts of `window` and `stack`,
 * which order the rows they work over without reordering what they emit.
 */
internal fun byCreation(
  input: List<VegaValue>,
  comparator: Comparator<VegaValue>,
  context: TransformContext,
): Comparator<Int> {
  val creation = context.creationOrder()?.takeIf { it.size == input.size }
  return Comparator { a, b ->
    val comparison = comparator.compare(input[a], input[b])
    when {
      comparison != 0 -> comparison
      creation != null -> creation[a].compareTo(creation[b])
      // Nothing tracked where they came from, so the order they are in is the best answer there
      // is — which is a plain stable sort.
      else -> a.compareTo(b)
    }
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
    /** The transforms the brief lists for the first release (ADR 0011). */
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
          CrossFilterTransform,
          ResolveFilterTransform,
          IsocontourTransform,
          ContourTransform,
          WordcloudTransform,
          GeoPathTransform,
          Kde2dTransform,
          HeatmapTransform,
          ForceTransform,
          GeoShapeTransform,
          GeoPointTransform,
          GeoJsonTransform,
          SampleTransform,
          GraticuleTransform,
          VoronoiTransform,
          LabelTransform,
        )
      )
  }
}

// ---- shared helpers ---------------------------------------------------------

/** Reads a parameter as a list of strings, accepting Vega's single-value shorthand. */
internal fun VegaValue.Obj.stringList(key: String): List<String> {
  val value = fields[key] ?: return emptyList()
  fieldName(value)?.let {
    return listOf(it)
  }
  return when (value) {
    // A `null` *element* is a deliberate blank, not the four letters that spell it: `"fields":
    // [null, "delay"]` beside `"ops": ["count", "average"]` says the count has no field while
    // keeping the two lists aligned. Reading it as the name "null" gave `count` a column no row
    // has,
    // and it counted nothing.
    is VegaValue.Arr ->
      value.values.map {
        when {
          it is VegaValue.Null -> ""
          // An element may be written longhand as well: `"groupby": [{"field": "g"}]` is the
          // same as `"groupby": ["g"]`; see [fieldName].
          else -> fieldName(it) ?: it.asString()
        }
      }
    is VegaValue.Null -> emptyList()
    else -> listOf(value.asString())
  }
}

/**
 * Every transform's **field parameters**, by the name upstream gives each one.
 *
 * Read off `'type': 'field'` in upstream's own transform definitions, which is what makes a
 * parameter accept `{"expr": …}` and `{"field": …}` as well as a column name. The second spelling
 * is unwrapped for all of them in [fieldName]; the first needs evaluating per row, which the five
 * transforms in [EVALUATES_FIELD_EXPRESSIONS] do and the rest do not — so this table is what lets
 * the pipeline *say so* rather than let the expression be stringified into a name no row has.
 */
private val FIELD_PARAMETERS =
  mapOf(
    "aggregate" to listOf("groupby", "fields", "key"),
    "bin" to listOf("field"),
    "contour" to listOf("x", "y", "weight"),
    "countpattern" to listOf("field"),
    "crossfilter" to listOf("fields"),
    "density" to listOf("field"),
    "dotbin" to listOf("field", "groupby"),
    "extent" to listOf("field"),
    "flatten" to listOf("fields"),
    "fold" to listOf("fields"),
    "force" to listOf("id", "x", "y"),
    "geojson" to listOf("fields", "geojson"),
    "geopath" to listOf("field"),
    "geopoint" to listOf("fields"),
    "geoshape" to listOf("field"),
    "heatmap" to listOf("field"),
    "impute" to listOf("field", "groupby", "key"),
    "isocontour" to listOf("field"),
    "joinaggregate" to listOf("fields", "groupby", "key"),
    "kde" to listOf("field", "groupby"),
    "kde2d" to listOf("x", "y", "weight", "groupby"),
    "linkpath" to listOf("sourceX", "sourceY", "targetX", "targetY"),
    "loess" to listOf("x", "y", "groupby"),
    "lookup" to listOf("fields", "key", "values"),
    "nest" to listOf("keys"),
    "pack" to listOf("field", "radius"),
    "partition" to listOf("field"),
    "pie" to listOf("field"),
    "pivot" to listOf("field", "groupby", "key", "value"),
    "project" to listOf("fields"),
    "quantile" to listOf("field", "groupby"),
    "regression" to listOf("x", "y", "groupby"),
    "stack" to listOf("field", "groupby"),
    "stratify" to listOf("key", "parentKey"),
    "timeunit" to listOf("field"),
    "tree" to listOf("field"),
    "treemap" to listOf("field"),
    "voronoi" to listOf("x", "y"),
    "window" to listOf("fields", "groupby"),
    "wordcloud" to listOf("text"),
  )

/**
 * The transforms that **evaluate** a field parameter written as an expression; see [fieldAccessor].
 */
private val EVALUATES_FIELD_EXPRESSIONS = setOf("voronoi", "linkpath", "kde2d", "contour", "force")

/**
 * Says so when a **field parameter** was written as an expression the transform cannot evaluate.
 *
 * Every field-typed parameter upstream accepts `{"expr": …}`, and the five transforms that meet one
 * in the wild evaluate it. The rest read a column name, and an expression reaching them would be
 * stringified into a name no row has — a silently empty result. One line of diagnostics is the
 * difference between that and a gap somebody can act on.
 */
internal fun reportUnevaluatedFieldExpressions(
  type: String,
  params: VegaValue.Obj,
  context: TransformContext,
) {
  if (type in EVALUATES_FIELD_EXPRESSIONS) return
  for (key in FIELD_PARAMETERS[type].orEmpty()) {
    val written = params.fields[key] ?: continue
    for (item in (written as? VegaValue.Arr)?.values ?: listOf(written)) {
      val expression =
        (item as? VegaValue.Obj)?.fields?.get("expr")?.asString()?.takeIf { it.isNotEmpty() }
          ?: continue
      context.diagnostics.warn(
        DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
        "$type's '$key' was written as the expression '$expression'; this transform reads a " +
          "column name there, so the parameter was ignored",
        operator = type,
      )
    }
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
  fields[key]?.takeIf { it !is VegaValue.Null }?.let { fieldName(it) ?: it.asString() }

/**
 * The column a **field parameter** names, when it was written as an object rather than as a string.
 *
 * ```js
 * const expr = def.expr || isField(type);
 * return expr && outerExpr(value) ? scope.exprRef(value.expr, value.as)
 *      : expr && outerField(value) ? fieldRef(value.field, value.as)
 *      : …
 * ```
 *
 * Upstream accepts three spellings for every field-typed parameter of every transform: a name, an
 * `{"expr": …}` and a `{"field": …}`. The third is the same thing as the first written longhand, so
 * it is unwrapped here once for every transform rather than in each of them — read as a string it
 * stringified to `field:amount`, which no row has and nothing reported.
 *
 * The `{"expr": …}` spelling cannot be unwrapped to a name: it needs evaluating per row, which the
 * transforms that meet it in the wild — `voronoi`, `linkpath`, `kde2d`, `contour`, `force` — do
 * through [fieldAccessor]. Anywhere else it is reported rather than misread; see
 * [reportUnevaluatedExpression].
 */
private fun fieldName(value: VegaValue): String? =
  (value as? VegaValue.Obj)?.fields?.get("field")?.asString()?.takeIf { it.isNotEmpty() }

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
/**
 * A **field** parameter, which upstream lets a specification write as an expression.
 *
 * ```js
 * { 'name': 'sourceX', 'type': 'field' }
 * ```
 *
 * A `field`-typed parameter is a *tuple accessor*, and `parseParameter` builds one from a string
 * (`"source.x"`), from `{"field": …}` or from `{"expr": …}` — so `{"expr": "scale('x', datum.k)"}`
 * is as ordinary as a column name, and it is how a sankey places its links: the coordinates are
 * scale lookups rather than columns. Read as a string alone, such a parameter stringified to
 * `expr:scale(…)`, no row had a field by that name, and every path came out `MNaN,NaN…`.
 *
 * Returns null where the parameter is absent, so the caller can apply its own default.
 */
internal fun fieldAccessor(
  params: VegaValue.Obj,
  key: String,
  context: TransformContext,
  operator: String,
): ((VegaValue) -> VegaValue)? {
  when (val written = params.fields[key]) {
    null -> return null
    is VegaValue.Obj -> {
      written.fields["expr"]
        ?.asString()
        ?.takeIf { it.isNotEmpty() }
        ?.let { source ->
          val compiled = TupleExpression(source, context, operator)
          if (!compiled.isUsable) return null
          return { row -> compiled.evaluate(row) ?: VegaValue.Null }
        }
      val field = written.fields["field"]?.asString()?.takeIf { it.isNotEmpty() } ?: return null
      return { row -> row.field(field) }
    }
    is VegaValue.Str -> {
      val field = written.value.takeIf { it.isNotEmpty() } ?: return null
      return { row -> row.field(field) }
    }
    else -> return null
  }
}

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
 * Vega's ascending comparator for arbitrary field values, which is `vega-util`'s `ascending`:
 * ```js
 * (u < v || u == null) && v != null ? -1
 *   : (u > v || v == null) && u != null ? 1
 *   : (v = v instanceof Date ? +v : v, u = u instanceof Date ? +u : u) !== u && v === v ? -1
 *   : v !== v && u === u ? 1
 *   : 0
 * ```
 *
 * Absent values sort first and NaN sorts after them, which is what upstream's `collect` does — and
 * the opposite of the SQL convention many people expect. A descending sort negates the result, so
 * those values move to the end rather than staying pinned at the front.
 *
 * The `<` is **JavaScript's** relational comparison, so two strings compare lexicographically and
 * everything else compares numerically. A pair it cannot order — a string against a number —
 * compares **equal**, which leaves them where they were, because both engines' sorts are stable.
 * Falling back to a lexicographic comparison of the two, which is what this did, ordered a mixed
 * column differently from upstream while looking like the more helpful answer.
 *
 * Public because a discrete scale domain's `sort` orders its values with the same comparator, and
 * two orderings that were meant to agree are the kind of thing that silently stops agreeing.
 */
public fun compareFieldValues(left: VegaValue, right: VegaValue): Int {
  val leftAbsent = left.isNullish
  val rightAbsent = right.isNullish
  val ordering = JsSemantics.compare(left, right)
  val leftNaN = left is VegaValue.Num && left.value.isNaN()
  val rightNaN = right is VegaValue.Num && right.value.isNaN()
  return when {
    ((ordering != null && ordering < 0) || leftAbsent) && !rightAbsent -> -1
    ((ordering != null && ordering > 0) || rightAbsent) && !leftAbsent -> 1
    leftNaN && !rightNaN -> -1
    rightNaN && !leftNaN -> 1
    else -> 0
  }
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
