@file:OptIn(InternalAsterVegaApi::class)

package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.InternalAsterVegaApi
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field
import dev.aster.vega.model.isNullish

/** `filter`: keeps tuples whose `expr` is truthy. */
public object FilterTransform : Transform {
  override val type: String = "filter"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val source = params.string("expr")
    if (source == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "filter needs an 'expr'",
        operator = type,
      )
      return input
    }
    val expression = TupleExpression(source, context, type)
    // An unusable expression must not silently drop every tuple; leave the data alone instead.
    if (!expression.isUsable) return input
    return input.filter { datum ->
      expression.evaluate(datum)?.let { JsSemantics.truthy(it) } ?: false
    }
  }
}

/** `formula`: adds or replaces a field with an expression's value. */
public object FormulaTransform : Transform {
  override val type: String = "formula"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val source = params.string("expr")
    val target = params.string("as")
    if (source == null || target == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "formula needs both 'expr' and 'as'",
        operator = type,
      )
      return input
    }
    val expression = TupleExpression(source, context, type)
    if (!expression.isUsable) return input
    return input.map { datum ->
      datum.withField(target, expression.evaluate(datum) ?: VegaValue.Null)
    }
  }
}

/**
 * `collect`: sorts the dataset.
 *
 * Missing values sort first in ascending order, matching upstream — the opposite of the SQL
 * convention. A tie is broken by the order the rows were **created** in, which is upstream's
 * `stableCompare` and is not the same as the order they are in; see
 * [TransformContext.creationOrder].
 */
public object CollectTransform : Transform {
  override val type: String = "collect"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val comparator = sortComparator(params.fields["sort"]) ?: return input
    return sortStably(input, comparator, context)
  }
}

/** `project`: keeps only the named fields, optionally renaming them. */
public object ProjectTransform : Transform {
  override val type: String = "project"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val fields = params.stringList("fields")
    if (fields.isEmpty()) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "project needs 'fields'",
        operator = type,
      )
      return input
    }
    val names = params.stringList("as")
    return input.map { datum ->
      val projected = LinkedHashMap<String, VegaValue>(fields.size)
      fields.forEachIndexed { index, path ->
        projected[names.getOrNull(index) ?: path] = datum.field(path)
      }
      VegaValue.Obj(projected)
    }
  }
}

/**
 * `identifier`: numbers the tuples from 1.
 *
 * Upstream allocates from a view-wide counter, so ids are unique across datasets. Here they restart
 * per pipeline, which keeps a compile reproducible — the property snapshots depend on (ADR 0008) —
 * at the cost of ids not being globally unique. Recorded in SUPPORTED_FEATURES.md.
 */
public object IdentifierTransform : Transform {
  override val type: String = "identifier"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val target = params.string("as")
    if (target == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "identifier needs 'as'",
        operator = type,
      )
      return input
    }
    return input.mapIndexed { index, datum ->
      datum.withField(target, VegaValue.Num((index + 1).toDouble()))
    }
  }
}

/**
 * `sample`: keeps at most `size` rows, chosen by reservoir sampling.
 *
 * A transcription of upstream's algorithm rather than of its intent, because the two differ in ways
 * a fixture can see. The reservoir fills with the first `size` rows in order; after that each row
 * is offered slot `trunc((seen + 1) * random())` and taken only if that slot is inside the
 * reservoir, so a later row *replaces* an earlier one in place. The output is therefore the
 * reservoir's own order, not the input's — row 30 may sit between rows 2 and 4 — and the counter
 * advances for every row including the ones that filled the reservoir, which is what makes the
 * acceptance probability `size / seen`.
 *
 * Deterministic here for the same reason it is in the oracle: both draw from the seeded stream that
 * upstream's `setRandom` installs, so the same rows survive on both sides. A specification running
 * against a real random source gets a different sample every render, upstream included.
 *
 * Input shorter than `size` passes through untouched, which is the case worth knowing about: a
 * `sample` that never fires costs nothing and reorders nothing.
 */
public object SampleTransform : Transform {
  override val type: String = "sample"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val size = params.number("size")?.takeIf { it.isFinite() } ?: DEFAULT_SIZE
    val capacity = size.toInt()
    if (capacity <= 0) return emptyList()
    if (input.size <= capacity) return input

    val random = context.scope.random
    val reservoir = ArrayList<VegaValue>(capacity)
    var seen = 0
    for (row in input) {
      if (reservoir.size < capacity) {
        reservoir.add(row)
      } else {
        // `~~((cnt + 1) * random())`: truncation toward zero, and `cnt` counts the rows *before*
        // this one, so the first row past the reservoir is offered one of `capacity + 1` slots.
        val slot = ((seen + 1) * random.next()).toInt()
        if (slot < reservoir.size) reservoir[slot] = row
      }
      seen++
    }
    return reservoir
  }

  /** Upstream's default, from `Sample.Definition`. */
  private const val DEFAULT_SIZE = 1000.0
}

/**
 * `extent`: publishes a field's `[min, max]` as a signal and leaves the data untouched.
 *
 * Missing values are excluded, verified against upstream: a field of `[1, 9, null]` yields `[1,
 * 9]`.
 */
public object ExtentTransform : Transform {
  override val type: String = "extent"

  /** `[min, max]`, which is what upstream's extent operator holds. */
  override val publishesSignal: Boolean = true

  /**
   * Upstream's `Extent.js`, over values already plucked from their field.
   *
   * **`null` means there is no extent**, which is a different answer from an empty one. The last
   * four lines of the operator are
   *
   * ```js
   * if (!Number.isFinite(min) || !Number.isFinite(max)) {
   *   pulse.dataflow.warn(`Infinite extent${name}: [${min}, ${max}]`);
   *   min = max = undefined;
   * }
   * ```
   *
   * so a column of `Infinity`, a column with no number anywhere in it, and a column with no rows
   * all reach the same place: a scale with `[undefined, undefined]` for a domain, which answers
   * `NaN` for every value and whose axis draws nothing. The infinity is not filtered out on the way
   * in — `Number.isFinite` is asked of the *result*, which is why a single `Infinity` in an
   * otherwise ordinary column takes the whole extent with it.
   *
   * Public and shared because it was transcribed twice. The scale resolver had a second copy that
   * dropped every non-finite value before comparing, and so reported the finite extent of a column
   * upstream reports no extent for at all — which drew five axis labels where upstream draws none.
   */
  public fun extentOf(values: Iterable<VegaValue>): ClosedFloatingPointRange<Double>? {
    var low = Double.POSITIVE_INFINITY
    var high = Double.NEGATIVE_INFINITY
    for (value in values) {
      // `toNumber(_) { return _ == null || _ === '' ? null : +_; }` from `vega-util`, then
      // `if (v != null)`. The empty string is the one coercion upstream declines to make here, and
      // it is not the same as skipping it: `+""` is 0, and a column of empty cells would otherwise
      // report an extent of `[0, 0]`.
      if (value.isNullish || (value is VegaValue.Str && value.value.isEmpty())) continue
      // `if (v < min) min = v; if (v > max) max = v`, with upstream's own comment on why there is
      // no NaN test: "NaNs will fail all comparisons!". An **infinity** does not fail them.
      val number = JsSemantics.toNumber(value)
      if (number < low) low = number
      if (number > high) high = number
    }
    return if (low.isFinite() && high.isFinite()) low..high else null
  }

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val path = params.string("field")
    if (path == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "extent needs a 'field'",
        operator = type,
      )
      return input
    }
    val extent = extentOf(input.map { it.field(path) })
    // "Infinite extent": upstream warns and answers `[undefined, undefined]`, which is what an
    // empty column gives too — a scale over it has no domain rather than a domain of infinities.
    if (extent == null && input.isNotEmpty()) {
      context.diagnostics.warn(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "Infinite extent for field '$path'; the signal is left with no extent",
        operator = type,
      )
    }

    val signal = params.string("signal")
    if (signal != null) {
      context.setSignal(
        signal,
        if (extent == null) {
          VegaValue.Arr(listOf(VegaValue.Null, VegaValue.Null))
        } else {
          VegaValue.Arr(listOf(VegaValue.Num(extent.start), VegaValue.Num(extent.endInclusive)))
        },
      )
    }
    return input
  }
}
