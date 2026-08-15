package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asBoolean
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import dev.aster.vega.model.isMissing
import dev.aster.vega.model.parseFieldPath
import kotlin.math.pow
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
  PRODUCT("product", needsField = true),
  /**
   * An exponentially weighted mean, and the same thing unnormalised.
   *
   * The only two operations that take a **parameter** — the decay rate, from the transform's
   * `aggregate_params` — and the only two whose answer depends on the *order* of the rows, because
   * each value is weighted by how far it is from the end of the group.
   */
  EXPONENTIAL("exponential", needsField = true),
  EXPONENTIALB("exponentialb", needsField = true),
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
  /**
   * The ends of a 95% confidence interval on the group's **mean**, by bootstrap.
   *
   * Not a summary statistic in the sense every other entry here is: upstream resamples the group a
   * thousand times with replacement, takes the mean of each resample, and reports the 2.5th and
   * 97.5th percentiles of those means. It therefore consumes 1,000 × n draws from the chart's
   * random stream, and the two ends come from **one** bootstrap rather than two — asking for both
   * does not run it twice, which is upstream's caching and is load-bearing for the sequence.
   */
  CI0("ci0", needsField = true),
  CI1("ci1", needsField = true),
  VALUES("values", needsField = false);

  public companion object {
    public fun fromName(name: String): AggregateOp? = entries.firstOrNull {
      it.opName.equals(name, ignoreCase = true)
    }
  }
}

/**
 * Whether a row **has** the field at all, as opposed to having it empty.
 *
 * The distinction JavaScript makes between `undefined` and `null`, which this value model does not:
 * `field()` answers [VegaValue.Null] for both. Only `distinct` needs to tell them apart, and it
 * does because upstream counts by the string each coerces to.
 */
private fun hasField(row: VegaValue, path: String): Boolean {
  var current: VegaValue = row
  val segments = parseFieldPath(path)
  for ((index, segment) in segments.withIndex()) {
    val obj = current as? VegaValue.Obj ?: return false
    if (!obj.fields.containsKey(segment)) return false
    if (index == segments.lastIndex) return true
    current = obj.fields.getValue(segment)
  }
  return false
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
    // `cross: true` asks for a cell per **combination** of the group-by values, not just per
    // combination that occurs: a heatmap with a gap wants the gap drawn, so the empty cells are
    // emitted with a zero count rather than left out. Upstream's own documentation is the rule —
    // "the full cross-product of groupby values ... including empty cells" — and the empty ones
    // come
    // *after* the observed ones, in the order the product enumerates them. Found by replaying
    // upstream's own aggregate vectors, where two rows produce four cells.
    val crossed =
      if (params.fields["cross"]?.asBoolean() == true && groupBy.isNotEmpty()) {
        crossProduct(groups.keys.toList(), groupBy.size).filterNot { it in groups }
      } else {
        emptyList()
      }
    val cells =
      groups.entries.map { it.key to it.value } + crossed.map { it to emptyList<VegaValue>() }
    return cells.map { (key, tuples) ->
      val output = LinkedHashMap<String, VegaValue>(groupBy.size + measures.size)
      groupBy.forEachIndexed { index, path -> output[path] = key[index] }
      // One bootstrap per group and field, memoized: `ci0` and `ci1` are two ends of the same
      // interval, and upstream caches it on the cell for exactly that reason.
      val intervals = HashMap<String, Pair<Double, Double>?>()
      val confidence: (String) -> Pair<Double, Double>? = { path ->
        intervals.getOrPut(path) {
          // Upstream's `numbers`: not null, not the empty string, and not NaN. Infinity survives
          // that test, so it is deliberately not filtered here either.
          val values =
            tuples
              .map { it.field(path) }
              .filterNot { it.isMissing || (it is VegaValue.Str && it.value.isEmpty()) }
              .map { JsSemantics.toNumber(it) }
              .filterNot { it.isNaN() }
          context.scope.random.bootstrapConfidence(values)
        }
      }
      for (measure in measures) {
        // A measure with no answer is *absent* from the row rather than null: upstream's operations
        // return `undefined` when they cannot be computed — `stderr` of one value, `min` of nothing
        // numeric — and an undefined property never reaches the tuple. The difference shows in a
        // `formula` reading the field, where absent and null coerce alike, and in an `isValid`
        // test, where they do not.
        val value = measure.compute(tuples, confidence)
        if (value !is VegaValue.Null) output[measure.outputName] = value
      }
      VegaValue.Obj(output)
    }
  }
}

/**
 * Every combination of the values each group-by dimension takes, in first-appearance order.
 *
 * The order is the product's own — the first dimension varies slowest — which is what puts
 * upstream's empty cells where it puts them once the observed ones are removed.
 */
private fun crossProduct(observed: List<List<VegaValue>>, dimensions: Int): List<List<VegaValue>> {
  if (observed.isEmpty()) return emptyList()
  val values =
    List(dimensions) { index ->
      observed.map { it[index] }.distinctBy { it.asComparableKey() }
    }
  var product = listOf(emptyList<VegaValue>())
  for (dimension in values) {
    product = product.flatMap { prefix -> dimension.map { prefix + it } }
  }
  return product
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
  /**
   * The decay rate for [AggregateOp.EXPONENTIAL] and [AggregateOp.EXPONENTIALB]; ignored by the
   * rest.
   *
   * Zero, which is upstream's fallback, makes the mean the last value alone — every earlier row is
   * weighted by a power of zero.
   */
  private val rate: Double = 0.0,
) {
  fun compute(
    tuples: List<VegaValue>,
    /**
     * The group's bootstrap, memoized per field by the caller.
     *
     * Passed in rather than computed here because `ci0` and `ci1` share one run: upstream caches it
     * on the cell, so a group asking for both draws its 1,000 resamples once. Running it twice
     * would give two different intervals *and* leave the stream in a different place for every
     * group after it.
     */
    confidence: ((String) -> Pair<Double, Double>?)? = null,
  ): VegaValue {
    if (op == AggregateOp.COUNT && fieldPath == null) {
      return VegaValue.Num(tuples.size.toDouble())
    }
    // `values` collects the **rows**, not the column: upstream pushes the tuple itself and ignores
    // the field the schema makes you name. It matters because the whole point of the operation is
    // to
    // carry a group's rows along with it — `pluck(datum.rows, 'shift')` in `donut-chart-labelled`
    // reads a *different* column out of them afterwards, and against an array of one column's
    // values
    // that reads back nothing but nulls. The static scene cannot see it: the rows are only ever
    // read
    // by a signal, so every fixture agreed while the array was the wrong thing entirely.
    if (op == AggregateOp.VALUES) return VegaValue.Arr(tuples)
    val path = fieldPath ?: return VegaValue.Null
    // `distinct` counts over **every** row rather than the readable ones, and it counts them as
    // upstream's map does: by `String(value)`, so an absent field and an explicit `null` are two
    // different answers — `"undefined"` and `"null"` — and both count. Filtering the missing ones
    // out
    // first said a column of 4, 9, 4, null and nothing had two distinct values where upstream says
    // four. The one place this still parts company is a column of *objects*: upstream coerces every
    // one to `[object Object]` and counts them as a single value, which nothing sane asks for.
    if (op == AggregateOp.DISTINCT) {
      val seen = HashSet<String>(tuples.size)
      for (tuple in tuples) {
        seen += if (hasField(tuple, path)) tuple.field(path).asString() else "undefined"
      }
      return VegaValue.Num(seen.size.toDouble())
    }
    if (op == AggregateOp.CI0 || op == AggregateOp.CI1) {
      val interval = confidence?.invoke(path) ?: return VegaValue.Null
      return VegaValue.Num(if (op == AggregateOp.CI0) interval.first else interval.second)
    }
    val raw = tuples.map { it.field(path) }

    // `count` is deliberately computed before filtering: it counts tuples, not values.
    if (op == AggregateOp.COUNT) return VegaValue.Num(raw.size.toDouble())
    if (op == AggregateOp.MISSING) {
      return VegaValue.Num(raw.count { it.isMissing }.toDouble())
    }
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

    val numbers = present.map { JsSemantics.toNumber(it) }.filter { it.isFinite() }
    if (numbers.isEmpty()) {
      // Upstream reports 0 for a sum over nothing and null for everything else.
      return if (op == AggregateOp.SUM) VegaValue.Num(0.0) else VegaValue.Null
    }

    return when (op) {
      AggregateOp.SUM -> VegaValue.Num(numbers.sum())
      AggregateOp.PRODUCT -> VegaValue.Num(numbers.fold(1.0) { acc, v -> acc * v })
      // Upstream accumulates `exp = r * exp + v` as the rows arrive, so a value's weight is `r` to
      // the power of how many rows follow it: the **last** row counts most. `exponential` then
      // normalises by `(1 - r) / (1 - r^n)` so the weights sum to one; `exponentialb` leaves the
      // series unnormalised and only scales by `(1 - r)`, which is what makes it comparable across
      // groups of different sizes.
      AggregateOp.EXPONENTIAL,
      AggregateOp.EXPONENTIALB -> {
        val r = rate
        val accumulated = numbers.fold(0.0) { acc, v -> r * acc + v }
        val n = numbers.size
        VegaValue.Num(
          if (op == AggregateOp.EXPONENTIALB) accumulated * (1.0 - r)
          else accumulated * (1.0 - r) / (1.0 - r.pow(n))
        )
      }
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
      AggregateOp.CI0,
      AggregateOp.CI1,
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
  // `aggregate_params` is positional alongside `ops`, and only the two exponential operations read
  // it. Upstream's `_.aggregate_params[i] || null` treats a zero as absent as well, which is why
  // the
  // fallback below is the same for both.
  val rates = params.numberList("aggregate_params")

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
        "'$opName' is not one of Vega's aggregate operations" + (REFUSED[opName.lowercase()] ?: ""),
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
    measures.add(
      Measure(op, path, names.getOrNull(index) ?: defaultName, rates.getOrNull(index) ?: 0.0)
    )
  }
  return measures
}

/** Operations that are missing on purpose, and why, so a reader is not left waiting for them. */
private val REFUSED = emptyMap<String, String>()

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
    // A date is a number for this purpose: two rows on the same instant are the same group,
    // however that instant was written.
    is VegaValue.Timestamp -> "n:${JsSemantics.numberToString(epochMillis)}"
    else -> "o:${JsSemantics.toStringValue(this)}"
  }
