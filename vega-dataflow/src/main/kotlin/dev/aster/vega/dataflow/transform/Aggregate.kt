package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field
import dev.aster.vega.model.isMissing
import kotlin.math.sqrt

/**
 * An aggregate operation.
 *
 * Two distinctions matter and are easy to get backwards, both verified against upstream:
 * - `count` counts **tuples**, including ones whose field is missing; `valid` counts non-missing
 *   values and `missing` counts the rest. For a three-row group with one null, `count` is 3 and
 *   `valid` is 2.
 * - `variance` and `stdev` are the **sample** forms, dividing by `n - 1`. For `[1, 4, 9, 16]` the
 *   variance is 43, not 32.25.
 */
public enum class AggregateOp(public val opName: String, public val needsField: Boolean) {
  COUNT("count", needsField = false),
  VALID("valid", needsField = true),
  MISSING("missing", needsField = true),
  DISTINCT("distinct", needsField = true),
  SUM("sum", needsField = true),
  MEAN("mean", needsField = true),
  AVERAGE("average", needsField = true),
  MIN("min", needsField = true),
  MAX("max", needsField = true),
  MEDIAN("median", needsField = true),
  VARIANCE("variance", needsField = true),
  VARIANCEP("variancep", needsField = true),
  STDEV("stdev", needsField = true),
  STDEVP("stdevp", needsField = true),
  /** The standard error of the mean: the sample standard deviation over `sqrt(n)`. */
  STDERR("stderr", needsField = true),
  /**
   * The **whole tuple** holding the smallest value of the field, not the value itself.
   *
   * That is what makes it useful and what makes it unlike every other operation here: a chart
   * labels its last point by aggregating with `argmax` over the date and then reading any column of
   * the row that came back. The output is therefore an object, and a specification reads through it
   * — `argmax_date.value` rather than `argmax_date`.
   */
  ARGMIN("argmin", needsField = true),
  ARGMAX("argmax", needsField = true),
  Q1("q1", needsField = true),
  Q3("q3", needsField = true),
  VALUES("values", needsField = false);

  public companion object {
    public fun fromName(name: String): AggregateOp? = entries.firstOrNull {
      it.opName.equals(name, ignoreCase = true)
    }
  }
}

/**
 * `aggregate`: groups tuples and summarizes each group.
 *
 * Output field names follow upstream: `{op}_{field}`, except a fieldless `count`, which is just
 * `count`. An explicit `as` overrides per position.
 */
public object AggregateTransform : Transform {
  override val type: String = "aggregate"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val groupBy = params.stringList("groupby")
    val measures = measures(params, context) ?: return input

    val groups = groupTuples(input, groupBy)
    return groups.map { (key, tuples) ->
      val output = LinkedHashMap<String, VegaValue>(groupBy.size + measures.size)
      groupBy.forEachIndexed { index, path -> output[path] = key[index] }
      for (measure in measures) {
        // A measure with no answer is *absent* from the row rather than null: upstream's operations
        // return `undefined` when they cannot be computed — `stderr` of one value, `min` of nothing
        // numeric — and an undefined property never reaches the tuple. The difference shows in a
        // `formula` reading the field, where absent and null coerce alike, and in an `isValid`
        // test, where they do not.
        val value = measure.compute(tuples)
        if (value !is VegaValue.Null) output[measure.outputName] = value
      }
      VegaValue.Obj(output)
    }
  }
}

/**
 * `joinaggregate`: computes the same summaries but writes them back onto every tuple of the group.
 *
 * Used for things like "this row's share of its group's total", which needs the aggregate alongside
 * the original row rather than instead of it.
 */
public object JoinAggregateTransform : Transform {
  override val type: String = "joinaggregate"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val groupBy = params.stringList("groupby")
    val measures = measures(params, context) ?: return input

    val groups = groupTuples(input, groupBy)
    val summaries = HashMap<List<VegaValue>, Map<String, VegaValue>>(groups.size)
    for ((key, tuples) in groups) {
      summaries[key] = measures.associate { it.outputName to it.compute(tuples) }
    }
    return input.map { datum ->
      datum.withFields(summaries[groupKey(datum, groupBy)] ?: emptyMap())
    }
  }
}

/**
 * Computes one summary over a group of tuples, outside any transform pipeline.
 *
 * A discrete scale domain sorted by `{"op": "sum", "field": "amount"}` needs exactly this, and
 * upstream implements that sort by inserting an `aggregate` into the dataflow. Sharing the
 * machinery is what keeps the two from drifting: seventeen operations with `count`-versus-`valid`
 * and sample-versus-population distinctions are not worth reimplementing beside themselves.
 */
public fun aggregateOver(
  op: AggregateOp,
  fieldPath: String?,
  tuples: List<VegaValue>,
): VegaValue = Measure(op, fieldPath, outputName = "").compute(tuples)

// ---- shared machinery -------------------------------------------------------

/** One requested summary: an operation, the field it reads, and the name it writes. */
internal class Measure(
  private val op: AggregateOp,
  private val fieldPath: String?,
  val outputName: String,
) {
  fun compute(tuples: List<VegaValue>): VegaValue {
    if (op == AggregateOp.COUNT && fieldPath == null) {
      return VegaValue.Num(tuples.size.toDouble())
    }
    val path = fieldPath ?: return VegaValue.Null
    val raw = tuples.map { it.field(path) }

    // `count` is deliberately computed before filtering: it counts tuples, not values.
    if (op == AggregateOp.COUNT) return VegaValue.Num(raw.size.toDouble())
    if (op == AggregateOp.MISSING) {
      return VegaValue.Num(raw.count { it.isMissing }.toDouble())
    }
    if (op == AggregateOp.VALUES) return VegaValue.Arr(raw)

    val present = raw.filterNot { it.isMissing }
    if (op == AggregateOp.VALID) return VegaValue.Num(present.size.toDouble())
    // The arg operations pick a *tuple*, so they run over the rows rather than over the values the
    // rows hold, and a row whose field is missing cannot win. Upstream keeps the first row at the
    // extreme, which is why this compares strictly.
    if (op == AggregateOp.ARGMIN || op == AggregateOp.ARGMAX) {
      var best: VegaValue? = null
      var bestValue = 0.0
      for (tuple in tuples) {
        val value = tuple.field(path)
        if (value.isMissing) continue
        val number = JsSemantics.toNumber(value)
        if (!number.isFinite()) continue
        val better =
          best == null || if (op == AggregateOp.ARGMIN) number < bestValue else number > bestValue
        if (better) {
          best = tuple
          bestValue = number
        }
      }
      return best ?: VegaValue.Null
    }
    if (op == AggregateOp.DISTINCT) {
      return VegaValue.Num(present.map { it.asComparableKey() }.distinct().size.toDouble())
    }

    val numbers = present.map { JsSemantics.toNumber(it) }.filter { it.isFinite() }
    if (numbers.isEmpty()) {
      // Upstream reports 0 for a sum over nothing and null for everything else.
      return if (op == AggregateOp.SUM) VegaValue.Num(0.0) else VegaValue.Null
    }

    return when (op) {
      AggregateOp.SUM -> VegaValue.Num(numbers.sum())
      AggregateOp.MEAN,
      AggregateOp.AVERAGE -> VegaValue.Num(numbers.average())
      AggregateOp.MIN -> VegaValue.Num(numbers.min())
      AggregateOp.MAX -> VegaValue.Num(numbers.max())
      AggregateOp.MEDIAN -> VegaValue.Num(quantile(numbers.sorted(), 0.5))
      AggregateOp.Q1 -> VegaValue.Num(quantile(numbers.sorted(), 0.25))
      AggregateOp.Q3 -> VegaValue.Num(quantile(numbers.sorted(), 0.75))
      AggregateOp.VARIANCE -> VegaValue.Num(variance(numbers, sample = true))
      AggregateOp.VARIANCEP -> VegaValue.Num(variance(numbers, sample = false))
      AggregateOp.STDEV -> VegaValue.Num(sqrt(variance(numbers, sample = true)))
      AggregateOp.STDEVP -> VegaValue.Num(sqrt(variance(numbers, sample = false)))
      // `sqrt(dev / (n * (n - 1)))`, upstream's own arrangement — the sample standard deviation
      // divided by `sqrt(n)`, which is what an error bar's half-length is.
      AggregateOp.STDERR ->
        if (numbers.size < 2) VegaValue.Null
        else VegaValue.Num(sqrt(variance(numbers, sample = true) / numbers.size))
      // Handled before the numeric filter above; listed so the `when` stays exhaustive.
      AggregateOp.COUNT,
      AggregateOp.VALID,
      AggregateOp.MISSING,
      AggregateOp.DISTINCT,
      AggregateOp.ARGMIN,
      AggregateOp.ARGMAX,
      AggregateOp.VALUES -> VegaValue.Null
    }
  }

  /** Sample variance divides by `n - 1`; the population form divides by `n`. */
  private fun variance(values: List<Double>, sample: Boolean): Double {
    if (values.size < 2) return if (sample) Double.NaN else 0.0
    val mean = values.average()
    val sumSquares = values.sumOf {
      val d = it - mean
      d * d
    }
    return sumSquares / (if (sample) values.size - 1 else values.size)
  }

  /** d3's `quantile`: linear interpolation between the two straddling values. */
  private fun quantile(sorted: List<Double>, p: Double): Double {
    if (sorted.isEmpty()) return Double.NaN
    if (sorted.size == 1) return sorted[0]
    val position = (sorted.size - 1) * p
    val lower = kotlin.math.floor(position).toInt()
    val upper = kotlin.math.ceil(position).toInt()
    if (lower == upper) return sorted[lower]
    val weight = position - lower
    return sorted[lower] * (1 - weight) + sorted[upper] * weight
  }
}

/**
 * Reads the `fields`, `ops` and `as` parameters into measures.
 *
 * With no `ops`, Vega defaults to a single `count`, which is why `{"type": "aggregate"}` alone
 * yields a row count per group.
 */
internal fun measures(params: VegaValue.Obj, context: TransformContext): List<Measure>? {
  val fields = params.stringList("fields")
  val opNames = params.stringList("ops")
  val names = params.stringList("as")

  if (opNames.isEmpty() && fields.isEmpty()) {
    return listOf(Measure(AggregateOp.COUNT, null, names.getOrNull(0) ?: "count"))
  }

  val count = maxOf(fields.size, opNames.size)
  val measures = mutableListOf<Measure>()
  for (index in 0 until count) {
    val opName = opNames.getOrNull(index) ?: "count"
    val op = AggregateOp.fromName(opName)
    if (op == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
        "Aggregate operation '$opName' is not implemented" + (REFUSED[opName.lowercase()] ?: ""),
        operator = opName,
      )
      return null
    }
    val path = fields.getOrNull(index)?.takeIf { it.isNotEmpty() }
    if (op.needsField && path == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "Aggregate operation '$opName' needs a field",
        operator = opName,
      )
      return null
    }
    // Upstream names a fieldless count just "count", and everything else "{op}_{field}".
    val defaultName = if (path == null) op.opName else "${op.opName}_$path"
    measures.add(Measure(op, path, names.getOrNull(index) ?: defaultName))
  }
  return measures
}

/**
 * Operations that are missing on purpose, and why, so a reader is not left waiting for them.
 *
 * `ci0` and `ci1` are the interesting pair: they look like ordinary summary statistics and are not.
 * Upstream computes them by **bootstrap** — a thousand resamples of the group drawn with
 * `Math.random()`, then the 2.5th and 97.5th percentiles of those means — so the same data gives a
 * different answer every run. A scene has to be reproducible (PROJECT_BRIEF.md 18.2), which is the
 * same reason `random()` itself is refused, so implementing these would mean either a chart that
 * moves when nothing changed or a quietly different statistic under the same name.
 */
private val REFUSED =
  mapOf(
    "ci0" to
      ": it is a bootstrap over 1,000 random resamples, so it differs run to run and a scene has " +
        "to be reproducible. 'stderr' is implemented and is what a symmetric error bar needs",
    "ci1" to
      ": it is a bootstrap over 1,000 random resamples, so it differs run to run and a scene has " +
        "to be reproducible. 'stderr' is implemented and is what a symmetric error bar needs",
  )

/**
 * Groups tuples by the `groupby` field values, preserving first-seen group order.
 *
 * Public because faceting needs the same partitioning: upstream implements a faceted group mark by
 * inserting an `aggregate` transform with the group's `groupby`, so the two must agree on both the
 * grouping and its order.
 */
public fun groupTuples(
  input: List<VegaValue>,
  groupBy: List<String>,
): Map<List<VegaValue>, List<VegaValue>> {
  // No `groupby` means one group over everything — but only if there is something. An aggregate
  // over nothing produces **no rows**, not a row of nulls: upstream never invents a group it saw no
  // tuples for, with or without a groupby. The difference shows wherever a filter can empty a
  // dataset, which is every tooltip and every brush — a row of nulls there draws the tooltip's
  // frame at the origin over a chart nobody is pointing at.
  if (input.isEmpty()) return emptyMap()
  if (groupBy.isEmpty()) return mapOf(emptyList<VegaValue>() to input)
  val groups = LinkedHashMap<List<VegaValue>, MutableList<VegaValue>>()
  for (datum in input) {
    groups.getOrPut(groupKey(datum, groupBy)) { mutableListOf() }.add(datum)
  }
  return groups
}

public fun groupKey(datum: VegaValue, groupBy: List<String>): List<VegaValue> = groupBy.map {
  datum.field(it)
}

/**
 * A value usable as a map key.
 *
 * `VegaValue.Arr` and `Obj` wrap collections whose equality is structural, so they are safe keys,
 * but numbers and strings that represent the same group must collide — hence the string form.
 */
internal fun VegaValue.asComparableKey(): String =
  when (this) {
    is VegaValue.Str -> "s:$value"
    is VegaValue.Num -> "n:${JsSemantics.numberToString(value)}"
    else -> "o:${JsSemantics.toStringValue(this)}"
  }
