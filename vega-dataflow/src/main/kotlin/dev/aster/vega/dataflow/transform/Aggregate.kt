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
      for (measure in measures) output[measure.outputName] = measure.compute(tuples)
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
      // Handled before the numeric filter above; listed so the `when` stays exhaustive.
      AggregateOp.COUNT,
      AggregateOp.VALID,
      AggregateOp.MISSING,
      AggregateOp.DISTINCT,
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
        "Aggregate operation '$opName' is not implemented",
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

/** Groups tuples by the `groupby` field values, preserving first-seen group order. */
internal fun groupTuples(
  input: List<VegaValue>,
  groupBy: List<String>,
): Map<List<VegaValue>, List<VegaValue>> {
  if (groupBy.isEmpty()) return mapOf(emptyList<VegaValue>() to input)
  val groups = LinkedHashMap<List<VegaValue>, MutableList<VegaValue>>()
  for (datum in input) {
    groups.getOrPut(groupKey(datum, groupBy)) { mutableListOf() }.add(datum)
  }
  return groups
}

internal fun groupKey(datum: VegaValue, groupBy: List<String>): List<VegaValue> = groupBy.map {
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
