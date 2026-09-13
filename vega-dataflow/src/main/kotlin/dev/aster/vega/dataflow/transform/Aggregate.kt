package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asBoolean
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import dev.aster.vega.model.isMissing
import dev.aster.vega.model.isNullish
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

    val groups = groupTuples(input, groupBy, params.string("key"))
    // `cross: true` asks for a cell per **combination** of the group-by values, not just per
    // combination that occurs: a heatmap with a gap wants the gap drawn, so the empty cells are
    // emitted with a zero count rather than left out. Upstream's own documentation is the rule —
    // "the full cross-product of groupby values ... including empty cells" — and the empty ones
    // come
    // *after* the observed ones, in the order the product enumerates them. Found by replaying
    // upstream's own aggregate vectors, where two rows produce four cells.
    val crossed =
      if (params.fields["cross"]?.asBoolean() == true && groupBy.isNotEmpty()) {
        // Compared against the group-by values the observed cells **report**, which is the same
        // set as the cells' own keys unless a `key` renamed them; see [GroupKey].
        val observed = groups.keys.map { GroupKey(it.values) }.toSet()
        crossProduct(groups.keys.toList(), groupBy.size).filterNot { it in observed }
      } else {
        emptyList()
      }
    val cells =
      groups.entries.map { it.key to it.value } + crossed.map { it to emptyList<VegaValue>() }
    return cells.map { (key, tuples) ->
      val output = LinkedHashMap<String, VegaValue>(groupBy.size + measures.size)
      groupBy.forEachIndexed { index, path -> output[path] = key.values[index] }
      val confidence = bootstrapFor(tuples, context)
      for (measure in measures) {
        // A measure with no answer is *absent* from the row rather than null: upstream's operations
        // return `undefined` when they cannot be computed — `stderr` of one value, `min` of nothing
        // numeric — and an undefined property never reaches the tuple. The difference shows in a
        // `formula` reading the field, where absent and null coerce alike, and in an `isValid`
        // test, where they do not.
        val value = measure.compute(tuples, confidence)
        if (!value.isNullish) output[measure.outputName] = value
      }
      VegaValue.Obj(output)
    }
  }
}

/**
 * One bootstrap per group and field, memoized.
 *
 * `ci0` and `ci1` are two ends of the same interval, and upstream caches it on the cell for exactly
 * that reason: running the resampling twice would give two different intervals *and* leave the
 * seeded stream in a different place for every group after it.
 */
private fun bootstrapFor(
  tuples: List<VegaValue>,
  context: TransformContext,
): (String) -> Pair<Double, Double>? {
  val intervals = HashMap<String, Pair<Double, Double>?>()
  return { path ->
    intervals.getOrPut(path) {
      // Upstream's `numbers`: not null, not the empty string, and not NaN. Infinity survives that
      // test, so it is deliberately not filtered here either.
      val values =
        tuples
          .map { it.field(path) }
          .filterNot { it.isMissing || (it is VegaValue.Str && it.value.isEmpty()) }
          .map { JsSemantics.toNumber(it) }
          .filterNot { it.isNaN() }
      context.scope.random.bootstrapConfidence(values)
    }
  }
}

/**
 * Every combination of the values each group-by dimension takes, in first-appearance order.
 *
 * The order is the product's own — the first dimension varies slowest — which is what puts
 * upstream's empty cells where it puts them once the observed ones are removed.
 */
private fun crossProduct(observed: List<GroupKey>, dimensions: Int): List<GroupKey> {
  if (observed.isEmpty()) return emptyList()
  val values =
    List(dimensions) { index ->
      observed.map { it.values[index] }.distinctBy { it.asComparableKey() }
    }
  var product = listOf(emptyList<VegaValue>())
  for (dimension in values) {
    product = product.flatMap { prefix -> dimension.map { prefix + it } }
  }
  return product.map { GroupKey(it) }
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
    val cellKey = params.string("key")

    val groups = groupTuples(input, groupBy, cellKey)
    val summaries = HashMap<GroupKey, Map<String, VegaValue>>(groups.size)
    for ((key, tuples) in groups) {
      // The **same** bootstrap closure `aggregate` builds. Without one, `Measure.compute` had
      // nothing to ask and returned null for `ci0` and `ci1`, silently — so a `joinaggregate`
      // asking for a confidence interval wrote nulls onto every row of its group and the error
      // bars it was for were drawn nowhere.
      val confidence = bootstrapFor(tuples, context)
      // The **cell's whole tuple** is written back, group-by values included: upstream's
      // `extend(t, cells[cellkey(t)].tuple)` copies every property the cell carries, and the cell
      // carries the group-by values of the first row that reached it. Identical to the row's own
      // values in the ordinary case — and not identical under a `key`, where rows with different
      // group-by values share a cell and all of them come out carrying the first row's.
      summaries[key] =
        LinkedHashMap<String, VegaValue>(groupBy.size + measures.size).apply {
          groupBy.forEachIndexed { index, path -> put(path, key.values[index]) }
          measures.forEach { put(it.outputName, it.compute(tuples, confidence)) }
        }
    }
    return input.map { datum ->
      datum.withFields(summaries[groupKey(datum, groupBy, cellKey)] ?: emptyMap())
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
  /**
   * The decay rate the two exponential operations take, which upstream calls `aggregate_params`.
   *
   * Null is "none named", and it is the right default for every caller here: a `pivot` forwards no
   * rate to the aggregate it builds, and neither does a scale domain's sort. See [exponentialRates]
   * for what upstream then computes.
   */
  rate: Double? = null,
): VegaValue = Measure(op, fieldPath, outputName = "", rate = rate).compute(tuples)

// ---- shared machinery -------------------------------------------------------

/** One requested summary: an operation, the field it reads, and the name it writes. */
internal class Measure(
  private val op: AggregateOp,
  private val fieldPath: String?,
  val outputName: String,
  /**
   * The decay rate for [AggregateOp.EXPONENTIAL] and [AggregateOp.EXPONENTIALB]; ignored by the
   * rest, and **null where the specification named none**; see [exponentialRates].
   */
  private val rate: Double? = null,
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
    // Upstream's cell sorts every value into exactly one of three boxes, and the boundaries are
    // not the ones this engine's `isMissing` draws:
    // ```js
    // if (v == null || v === '') { ++this.missing; return; }
    // if (v !== v) return;                       // a NaN is neither missing nor valid
    // ++this.valid;
    // ```
    // So the empty string is **missing** — a dirty CSV column of `""` was entering the numeric
    // list as a valid 0 and dragging every mean toward it — and a NaN is counted in neither box,
    // where `missing` was counting it.
    if (op == AggregateOp.MISSING) {
      return VegaValue.Num(raw.count { it.isCellMissing() }.toDouble())
    }
    // The **valid** values, raw and unfiltered. Not coerced and not screened for finiteness: an
    // infinity is a valid value upstream and so is a string that coerces to NaN, and both are
    // meant to poison the sum. Filtering them answered 1 for the sum of `[1, "abc"]`, where
    // upstream answers NaN — a total that silently omits the rows it could not read.
    val present = raw.filter { !it.isCellMissing() && !it.isCellNaN() }
    if (op == AggregateOp.VALID) return VegaValue.Num(present.size.toDouble())
    // `min` and `max` track the extreme **incrementally**, over the raw values and with
    // JavaScript's relational comparison: `if (v < m.min || m.min === undefined) m.min = v`. A
    // string in a numeric column therefore never displaces a number, because `1 < "abc"` and
    // `"abc" < 1` are both false — where coercing first would have made the answer NaN.
    if (op == AggregateOp.MIN || op == AggregateOp.MAX) {
      var best: VegaValue? = null
      for (value in present) {
        val ordering = best?.let { JsSemantics.compare(value, it) }
        val better =
          best == null ||
            (ordering != null && if (op == AggregateOp.MIN) ordering < 0 else ordering > 0)
        if (better) best = value
      }
      return best ?: VegaValue.Null
    }
    // The arg operations pick a **tuple**, and upstream reaches them through a different route
    // from `min`/`max`: `m.argmin || m.cell.data.argmin(m.get)`, whose fallback is `extentIndex`
    // over every stored row. That is transcribed below, because the two routes genuinely disagree
    // — `argmin` over `[Infinity, 1]` is the second row while `min` is 1, and `argmin` over
    // `['abc', 1]` is the *first* row while `min` is `'abc'`. An infinity and a non-numeric value
    // both take part, where this used to skip them.
    if (op == AggregateOp.ARGMIN || op == AggregateOp.ARGMAX) {
      return extremeTuple(tuples, path, smallest = op == AggregateOp.ARGMIN) ?: VegaValue.Null
    }

    val numbers = present.map { JsSemantics.toNumber(it) }
    // `m.valid ? … : undefined` guards every numeric operation upstream, sum included: a group
    // with no valid value at all answers **undefined** and not 0. A comment here said upstream
    // reports 0 for a sum over nothing; it was probed false, and the code followed it — so a total
    // over a group of nulls passed an `isValid` filter that upstream's drops.
    if (numbers.isEmpty()) return VegaValue.Null

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
        // **NaN where there is no rate**, which is upstream's uninitialised accumulator and not a
        // degenerate mean: `init` is called as `op.init(this)` when the parameter is absent, so
        // `exp_r` is `undefined` and every piece of arithmetic below it is NaN. Reading a missing
        // rate as zero answered "the last value in the group" for a specification upstream answers
        // nothing for — a plausible number in place of a blank. See [exponentialRates].
        val r = rate ?: Double.NaN
        val accumulated = numbers.fold(0.0) { acc, v -> r * acc + v }
        val n = numbers.size
        VegaValue.Num(
          if (op == AggregateOp.EXPONENTIALB) accumulated * (1.0 - r)
          else accumulated * (1.0 - r) / (1.0 - r.pow(n))
        )
      }
      AggregateOp.MEAN,
      // The **running** mean, not `sum / n`: upstream accumulates it incrementally, and the two
      // part
      // company where the sum overflows — the mean of `[MAX_VALUE, MAX_VALUE]` is `MAX_VALUE`
      // upstream and `Infinity` from a sum that overflowed before it divided.
      AggregateOp.AVERAGE -> VegaValue.Num(welford(numbers).mean)
      AggregateOp.MEDIAN -> VegaValue.Num(quantile(numbers.sorted(), 0.5))
      AggregateOp.Q1 -> VegaValue.Num(quantile(numbers.sorted(), 0.25))
      AggregateOp.Q3 -> VegaValue.Num(quantile(numbers.sorted(), 0.75))
      // `m.valid > 1 ? … : undefined` for the three sample forms, `m.valid ? … : undefined` for
      // the two population ones. One value gives **no** variance rather than a NaN one: the
      // property is absent from the row upstream, and an absent property and a NaN read the same
      // through arithmetic and the opposite through `isValid`.
      AggregateOp.VARIANCE ->
        if (numbers.size < 2) VegaValue.Null else VegaValue.Num(variance(numbers, sample = true))
      AggregateOp.VARIANCEP -> VegaValue.Num(variance(numbers, sample = false))
      AggregateOp.STDEV ->
        if (numbers.size < 2) VegaValue.Null
        else VegaValue.Num(sqrt(variance(numbers, sample = true)))
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
      AggregateOp.MIN,
      AggregateOp.MAX,
      AggregateOp.ARGMIN,
      AggregateOp.ARGMAX,
      AggregateOp.CI0,
      AggregateOp.CI1,
      AggregateOp.VALUES -> VegaValue.Null
    }
  }

  /** Upstream's cell: `v == null || v === ''`. The empty string is missing, and NaN is not. */
  private fun VegaValue.isCellMissing(): Boolean =
    isNullish || (this is VegaValue.Str && value.isEmpty())

  /** Upstream's `v !== v`, which is true for a NaN **number** and for nothing else. */
  private fun VegaValue.isCellNaN(): Boolean = this is VegaValue.Num && value.isNaN()

  /**
   * `extentIndex`, transcribed: the rows holding the least and greatest value of [path].
   *
   * The first candidate has to pass `b != null && b >= b`, which admits a string — `'abc' >= 'abc'`
   * is true — and rejects null and NaN. Every later value is compared with `>` and `<` under
   * JavaScript's relational rules, so a value that cannot be ordered against the running extreme
   * simply never displaces it, and the **first** row wins a tie.
   */
  private fun extremeTuple(
    tuples: List<VegaValue>,
    path: String,
    smallest: Boolean,
  ): VegaValue? {
    var lowest: VegaValue? = null
    var highest: VegaValue? = null
    var lowestRow: VegaValue? = null
    var highestRow: VegaValue? = null
    for (tuple in tuples) {
      val value = tuple.field(path)
      if (lowest == null) {
        // `b != null && b >= b`
        if (value.isNullish || value.isCellNaN()) continue
        lowest = value
        highest = value
        lowestRow = tuple
        highestRow = tuple
        continue
      }
      if (value.isNullish) continue
      if (JsSemantics.compare(lowest, value)?.let { it > 0 } == true) {
        lowest = value
        lowestRow = tuple
      }
      if (JsSemantics.compare(highest!!, value)?.let { it < 0 } == true) {
        highest = value
        highestRow = tuple
      }
    }
    return if (smallest) lowestRow else highestRow
  }

  /** Sample variance divides by `n - 1`; the population form divides by `n`. */
  private fun variance(values: List<Double>, sample: Boolean): Double {
    if (values.size < 2) return if (sample) Double.NaN else 0.0
    return maxOf(0.0, welford(values).dev) / (if (sample) values.size - 1 else values.size)
  }

  /**
   * The running mean and squared deviation, which is how **both** upstream engines compute them.
   *
   * `dev += (v - oldMean) * (v - newMean)` — Welford's — rather than a mean followed by a sum of
   * squares. The two agree to the last bit on ordinary data and part company at the extremes: the
   * variance of `[MAX_VALUE, MAX_VALUE]` is **0** upstream and was `Infinity` here, because taking
   * the average first overflows the sum before it divides. Found by replaying d3-array's own
   * vectors, which pass exactly that array.
   */
  private fun welford(values: List<Double>): Running {
    var mean = 0.0
    var dev = 0.0
    var seen = 0
    for (value in values) {
      seen++
      val delta = value - mean
      mean += delta / seen
      dev += delta * (value - mean)
    }
    return Running(mean, dev)
  }

  private class Running(val mean: Double, val dev: Double)

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
 * Which rate each exponential measure actually runs at, given `ops`, `fields` and
 * `aggregate_params`.
 *
 * Three separate pieces of upstream's `compileMeasures` decide this, and none of them is what the
 * parameter's name suggests:
 * * **A rate of zero is no rate at all.** Both transforms read `aggregate_params[i] || null`, so a
 *   zero, a NaN and an absent entry arrive identically.
 * * **With no rate the accumulator is never initialised.** `init` runs as `op.init(this)` rather
 *   than `op.init(this, param)`, leaving `exp_r` undefined, and `exp * (1 - undefined) / (1 -
 *   undefined ** n)` is NaN. An `exponential` with no rate therefore answers nothing — not the last
 *   value in the group, which is what reading the missing rate as zero computes.
 * * **One accumulator per operation name per field.** `resolve()` keys its map by `a.name`, so two
 *   `exponential` measures over the same field share one running total *and* one rate, the last one
 *   declared — and both report the same number. `exponentialb` has no accumulator of its own at
 *   all: it is declared `req: ['exponential']` and reads that state, so its own `aggregate_params`
 *   entry is never read, and a specification asking for `exponentialb` *alone* gets the
 *   uninitialised accumulator and a column of NaN.
 *
 * Returns the rate to give a measure of [op] over [path], or null for "upstream never initialised
 * one". Verified against upstream: `exponentialb` with a rate of 0.5 and no `exponential` beside it
 * is NaN, and beside `exponential` at 0.9 it is `exp(0.9) * 0.1` rather than `exp(0.5) * 0.5`.
 */
internal fun exponentialRates(
  ops: List<String>,
  fields: List<String>,
  rates: List<Double>,
): (AggregateOp, String?) -> Double? {
  val byField = HashMap<String, Double?>()
  for (index in 0 until maxOf(ops.size, fields.size)) {
    if (AggregateOp.fromName(ops.getOrNull(index) ?: "") != AggregateOp.EXPONENTIAL) continue
    val path = fields.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: continue
    // Recorded even when there is no usable rate, because the *last* declaration is the one whose
    // `init` runs: an `exponential` with a rate followed by one without leaves both of them NaN.
    byField[path] = rates.getOrNull(index)?.takeIf { it != 0.0 && !it.isNaN() }
  }
  return { op, path ->
    if (op == AggregateOp.EXPONENTIAL || op == AggregateOp.EXPONENTIALB) path?.let { byField[it] }
    else null
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
  val exponential = exponentialRates(opNames, fields, params.numberList("aggregate_params"))

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
        "'$opName' is not one of Vega's aggregate operations",
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
    measures.add(Measure(op, path, names.getOrNull(index) ?: defaultName, exponential(op, path)))
  }
  return measures
}

/**
 * Groups tuples by the `groupby` field values, preserving first-seen group order.
 *
 * Public because faceting needs the same partitioning: upstream implements a faceted group mark by
 * inserting an `aggregate` transform with the group's `groupby`, so the two must agree on both the
 * grouping and its order.
 */
/**
 * What makes two rows the same group, which is **not** the raw values they were grouped on.
 *
 * Upstream groups through `fastmap`, which is object-backed, so JavaScript coerces every key to a
 * string before storing it: the integer `1001` and the string `"1001"` are one property and
 * therefore one group. Keying on the raw values split what upstream merges — the same defect
 * [asComparableKey] was written for, and which `lookup`, `impute` and `dotbin` already avoided
 * while `aggregate`, `window`, `pivot`, `regression`, `density` and the facet compiler did not.
 *
 * [values] are the **first** row's, which is where upstream reads a group's own fields from too, so
 * a group formed from a string and a number carries the spelling that arrived first.
 */
public class GroupKey(
  public val values: List<VegaValue>,
  /**
   * What decides which cell a row falls into, where that is **not** the group-by values.
   *
   * `aggregate`, `joinaggregate` and `pivot` all declare a `key` parameter, and upstream reads it
   * as `this.cellkey = _.key ? _.key : groupkey(this._dims)` — one cell per distinct value of that
   * field, however many group-by fields there are. The cell still *reports* group-by values, taken
   * from the first row that reached it, so the two are genuinely different lists: `{"groupby":
   * ["a"], "key": "b"}` over rows `(x, p), (y, p), (z, q)` gives two cells, and the first says `a:
   * "x"` for a pair of rows whose `a` values differ.
   */
  identityValues: List<VegaValue> = values,
) {

  private val identity: List<String> = identityValues.map { it.asComparableKey() }

  override fun equals(other: Any?): Boolean = other is GroupKey && other.identity == identity

  override fun hashCode(): Int = identity.hashCode()

  override fun toString(): String = identity.toString()
}

public fun groupTuples(
  input: List<VegaValue>,
  groupBy: List<String>,
  /**
   * The cell key, where the specification names one instead of grouping by value; see [GroupKey].
   */
  key: String? = null,
): Map<GroupKey, List<VegaValue>> {
  // No `groupby` means one group over everything — but only if there is something. An aggregate
  // over nothing produces **no rows**, not a row of nulls: upstream never invents a group it saw no
  // tuples for, with or without a groupby. The difference shows wherever a filter can empty a
  // dataset, which is every tooltip and every brush — a row of nulls there draws the tooltip's
  // frame at the origin over a chart nobody is pointing at.
  if (input.isEmpty()) return emptyMap()
  // A named key still splits the rows when there is no `groupby` at all, so the one-group shortcut
  // is only a shortcut where nothing else decides the cell.
  if (groupBy.isEmpty() && key == null) return mapOf(GroupKey(emptyList()) to input)
  val groups = LinkedHashMap<GroupKey, MutableList<VegaValue>>()
  for (datum in input) {
    groups.getOrPut(groupKey(datum, groupBy, key)) { mutableListOf() }.add(datum)
  }
  return groups
}

public fun groupKey(datum: VegaValue, groupBy: List<String>, key: String? = null): GroupKey =
  GroupKey(
    groupBy.map { datum.field(it) },
    if (key == null) groupBy.map { datum.field(it) } else listOf(datum.field(key)),
  )

/**
 * A value usable as a map key.
 *
 * `VegaValue.Arr` and `Obj` wrap collections whose equality is structural, so they are safe keys,
 * but numbers and strings that represent the same group must collide — hence the string form.
 */
internal fun VegaValue.asComparableKey(): String =
  when (this) {
    // **Untagged, so a number and its own text are one key.** Upstream indexes and groups through
    // `fastmap`, which is object-backed, so JavaScript coerces every key to a string before it is
    // stored: the integer `1001` and the string `"1001"` are the same property. Tagging them apart
    // — `"s:1001"` against `"n:1001"` — split what upstream merges, and it showed up as a join that
    // matched nothing: a TSV column of `"22051"` against TopoJSON's integer `22051` drew an empty
    // map with no error. Verified both ways against upstream, which merges the two into one
    // aggregate group and matches the two in one lookup.
    is VegaValue.Str -> value
    is VegaValue.Num -> JsSemantics.numberToString(value)
    // A date keeps a namespace of its own, which is *closer* to upstream than sharing one: a `Date`
    // used as a key stringifies to its written form, not to its epoch, so it cannot collide with a
    // number that happens to have the same digits.
    is VegaValue.Timestamp -> "date:${JsSemantics.numberToString(epochMillis)}"
    else -> JsSemantics.toStringValue(this)
  }
