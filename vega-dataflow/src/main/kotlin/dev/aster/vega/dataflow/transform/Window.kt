package dev.aster.vega.dataflow.transform

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.field
import kotlin.math.ceil

/**
 * `window`: a calculation over a moving span of rows, which is what a running total is.
 *
 * Two kinds of operation share the transform and behave differently, and the difference is the
 * thing to know before reading anything else:
 *
 * - **Ranking operations** — `rank`, `row_number`, `lag`, `first_value` and the rest — look at the
 *   whole partition and ignore the frame entirely.
 * - **Aggregate operations** — the same seventeen the `aggregate` transform has — are computed over
 *   the *frame*, a span of rows relative to the current one.
 *
 * The frame's default is `[null, 0]`: everything from the start of the partition up to and
 * including this row. That is what makes a bare `{"type": "window", "ops": ["sum"]}` a running
 * total rather than a partition total — the second is `[null, null]`, and the two differ by one
 * character in a place nothing draws attention to.
 *
 * With a `sort` and without `ignorePeers`, the frame is widened to take in **peers**: rows that tie
 * under the sort. A moving average over a sorted column therefore covers every row sharing the
 * current value, not just the current row, so two rows that compare equal always get the same
 * answer.
 */
public object WindowTransform : Transform {
  override val type: String = "window"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val operations = operations(params, context) ?: return input
    if (operations.isEmpty()) return input

    val groupBy = params.stringList("groupby")
    val comparator = sortComparator(params.fields["sort"])
    val ignorePeers = params.fields["ignorePeers"]?.let { it == VegaValue.Bool(true) } ?: false
    val frame = frame(params)

    // Partitions keep their first-seen order, and a partition's rows keep theirs unless sorted —
    // Kotlin's sort is stable, as upstream's is, so ties never shuffle.
    val partitions = groupTuples(input, groupBy)
    val results = HashMap<VegaValue, VegaValue>()
    for ((_, rows) in partitions) {
      val ordered = if (comparator == null) rows else rows.sortedWith(comparator)
      val computed = process(ordered, operations, frame, comparator, ignorePeers)
      for ((original, updated) in ordered.zip(computed)) results[original] = updated
    }
    // Emitted in input order: `window` annotates rows, it does not reorder them.
    return input.map { results[it] ?: it }
  }

  private fun process(
    data: List<VegaValue>,
    operations: List<Operation>,
    frame: Pair<Int?, Int?>,
    comparator: Comparator<VegaValue>?,
    ignorePeers: Boolean,
  ): List<VegaValue> {
    val n = data.size
    val ranks = RankState(data, comparator)
    return data.mapIndexed { index, row ->
      var i0 = (frame.first?.let { index + it } ?: 0).coerceIn(0, n)
      var i1 = (frame.second?.let { index + it + 1 } ?: n).coerceIn(0, n)
      if (comparator != null && !ignorePeers) {
        val widened = widenToPeers(data, comparator, i0, i1)
        i0 = widened.first
        i1 = widened.second
      }
      val values = LinkedHashMap<String, VegaValue>(operations.size)
      for (operation in operations) {
        values[operation.name] = operation.compute(data, index, i0, i1, ranks)
      }
      row.withFields(values)
    }
  }

  /**
   * Widens the frame to cover every row tying with the one at each end.
   *
   * Upstream only widens an end that is already sitting between two equal rows, so a frame whose
   * edge falls on a boundary is left alone. The effect is that two rows which compare equal see the
   * same window and therefore get the same answer.
   */
  private fun widenToPeers(
    data: List<VegaValue>,
    comparator: Comparator<VegaValue>,
    i0: Int,
    i1: Int,
  ): Pair<Int, Int> {
    var start = i0
    var end = i1
    val last = data.size - 1
    val r1 = i1 - 1
    if (i0 in 1..last && comparator.compare(data[i0], data[i0 - 1]) == 0) {
      while (start > 0 && comparator.compare(data[start - 1], data[i0]) == 0) start--
    }
    if (r1 in 0 until last && comparator.compare(data[r1], data[r1 + 1]) == 0) {
      while (end < data.size && comparator.compare(data[end], data[r1]) == 0) end++
    }
    return start to end
  }

  /** The frame, as offsets relative to the current row. `null` on either end means unbounded. */
  private fun frame(params: VegaValue.Obj): Pair<Int?, Int?> {
    val values = (params.fields["frame"] as? VegaValue.Arr)?.values ?: return null to 0
    fun at(index: Int): Int? =
      values
        .getOrNull(index)
        ?.takeIf { it !is VegaValue.Null }
        ?.asDouble()
        ?.takeIf { it.isFinite() }
        ?.toInt()
    return at(0) to at(1)
  }

  /**
   * Ranks, computed once per partition because each depends on the row before it.
   *
   * `rank` restarts at the row's own index whenever the sort changes, so a run of ties all take the
   * index of the first of them — 1, 1, 3 rather than 1, 1, 2. `dense_rank` counts distinct values
   * instead and gives 1, 1, 2.
   */
  private class RankState(data: List<VegaValue>, comparator: Comparator<VegaValue>?) {
    val rank = IntArray(data.size)
    val denseRank = IntArray(data.size)

    /** The last index tying with each row, which is what `cume_dist` counts up to. */
    val peerEnd = IntArray(data.size)

    init {
      var currentRank = 1
      var currentDense = 1
      for (index in data.indices) {
        val changed =
          index > 0 && comparator != null && comparator.compare(data[index - 1], data[index]) != 0
        if (changed) {
          currentRank = index + 1
          currentDense++
        }
        rank[index] = currentRank
        denseRank[index] = currentDense
      }
      var index = data.size - 1
      while (index >= 0) {
        var end = index
        while (
          end + 1 < data.size &&
            comparator != null &&
            comparator.compare(data[end], data[end + 1]) == 0
        ) {
          end++
        }
        // With no comparator every row is its own peer group, matching upstream's constant(-1).
        val resolved = if (comparator == null) index else end
        var back = index
        while (
          back >= 0 && (comparator == null || comparator.compare(data[back], data[index]) == 0)
        ) {
          peerEnd[back] = resolved
          if (comparator == null) break
          back--
        }
        index = if (comparator == null) index - 1 else back
      }
    }
  }

  /** One requested calculation. */
  private class Operation(
    val name: String,
    private val kind: Kind,
    private val fieldPath: String?,
    private val param: Double?,
    private val aggregate: AggregateOp?,
  ) {
    enum class Kind {
      ROW_NUMBER,
      RANK,
      DENSE_RANK,
      PERCENT_RANK,
      CUME_DIST,
      NTILE,
      LAG,
      LEAD,
      FIRST_VALUE,
      LAST_VALUE,
      NTH_VALUE,
      PREV_VALUE,
      NEXT_VALUE,
      AGGREGATE,
    }

    fun compute(
      data: List<VegaValue>,
      index: Int,
      i0: Int,
      i1: Int,
      ranks: RankState,
    ): VegaValue {
      fun valueAt(at: Int): VegaValue =
        if (at in data.indices) fieldPath?.let { data[at].field(it) } ?: VegaValue.Null
        else VegaValue.Null

      val cume = (1.0 + ranks.peerEnd[index]) / data.size
      return when (kind) {
        Kind.ROW_NUMBER -> VegaValue.Num((index + 1).toDouble())
        Kind.RANK -> VegaValue.Num(ranks.rank[index].toDouble())
        Kind.DENSE_RANK -> VegaValue.Num(ranks.denseRank[index].toDouble())
        Kind.PERCENT_RANK ->
          VegaValue.Num(if (data.size <= 1) 0.0 else (ranks.rank[index] - 1.0) / (data.size - 1.0))
        Kind.CUME_DIST -> VegaValue.Num(cume)
        Kind.NTILE -> VegaValue.Num(ceil((param ?: 1.0) * cume))
        Kind.LAG -> valueAt(index - (param?.toInt() ?: 1))
        Kind.LEAD -> valueAt(index + (param?.toInt() ?: 1))
        Kind.FIRST_VALUE -> valueAt(i0)
        Kind.LAST_VALUE -> valueAt(i1 - 1)
        Kind.NTH_VALUE -> {
          val at = i0 + ((param?.toInt() ?: 1) - 1)
          if (at < i1) valueAt(at) else VegaValue.Null
        }
        // The last non-null seen up to here, and the next one from here on.
        Kind.PREV_VALUE -> {
          var at = index
          while (at >= 0 && valueAt(at) is VegaValue.Null) at--
          valueAt(at)
        }
        Kind.NEXT_VALUE -> {
          var at = index
          while (at < data.size && valueAt(at) is VegaValue.Null) at++
          valueAt(at)
        }
        Kind.AGGREGATE ->
          aggregateOver(aggregate!!, fieldPath, data.subList(i0.coerceAtMost(i1), i1))
      }
    }
  }

  private fun operations(params: VegaValue.Obj, context: TransformContext): List<Operation>? {
    val ops = params.stringList("ops")
    val fields = params.stringList("fields")
    val names = params.stringList("as")
    val values = (params.fields["params"] as? VegaValue.Arr)?.values

    val result = mutableListOf<Operation>()
    for ((index, op) in ops.withIndex()) {
      val path = fields.getOrNull(index)?.takeIf { it.isNotEmpty() }
      val param = values?.getOrNull(index)?.asDouble()?.takeIf { it.isFinite() }
      val kind = KINDS[op.lowercase()]
      val aggregate = if (kind == null) AggregateOp.fromName(op) else null
      if (kind == null && aggregate == null) {
        context.diagnostics.error(
          DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
          "Window operation '$op' is not implemented",
          operator = type,
        )
        return null
      }
      if (aggregate != null && aggregate.needsField && path == null) {
        context.diagnostics.error(
          DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          "Window operation '$op' needs a field",
          operator = type,
        )
        return null
      }
      // Upstream names an output `{op}_{field}` unless `as` says otherwise, the same way aggregate
      // does — except that a fieldless ranking operation is just its own name.
      val name =
        names.getOrNull(index)?.takeIf { it.isNotEmpty() }
          ?: if (path == null) op else "${op}_$path"
      result.add(Operation(name, kind ?: Operation.Kind.AGGREGATE, path, param, aggregate))
    }
    return result
  }

  private val KINDS: Map<String, Operation.Kind> =
    mapOf(
      "row_number" to Operation.Kind.ROW_NUMBER,
      "rank" to Operation.Kind.RANK,
      "dense_rank" to Operation.Kind.DENSE_RANK,
      "percent_rank" to Operation.Kind.PERCENT_RANK,
      "cume_dist" to Operation.Kind.CUME_DIST,
      "ntile" to Operation.Kind.NTILE,
      "lag" to Operation.Kind.LAG,
      "lead" to Operation.Kind.LEAD,
      "first_value" to Operation.Kind.FIRST_VALUE,
      "last_value" to Operation.Kind.LAST_VALUE,
      "nth_value" to Operation.Kind.NTH_VALUE,
      "prev_value" to Operation.Kind.PREV_VALUE,
      "next_value" to Operation.Kind.NEXT_VALUE,
    )
}
