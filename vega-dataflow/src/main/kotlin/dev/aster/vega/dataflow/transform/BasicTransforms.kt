package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field
import dev.aster.vega.model.isMissing

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
 * convention. Sorting is stable, so tuples that compare equal keep their input order.
 */
public object CollectTransform : Transform {
  override val type: String = "collect"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val comparator = sortComparator(params.fields["sort"]) ?: return input
    return input.sortedWith(comparator)
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
 * per pipeline, which keeps a compile reproducible — the property snapshots depend on
 * (PROJECT_BRIEF.md 18.2) — at the cost of ids not being globally unique. Recorded in
 * SUPPORTED_FEATURES.md.
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
 * `extent`: publishes a field's `[min, max]` as a signal and leaves the data untouched.
 *
 * Missing values are excluded, verified against upstream: a field of `[1, 9, null]` yields `[1,
 * 9]`.
 */
public object ExtentTransform : Transform {
  override val type: String = "extent"

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
    val numbers =
      input
        .map { it.field(path) }
        .filterNot { it.isMissing }
        .map { JsSemantics.toNumber(it) }
        .filter { it.isFinite() }

    val signal = params.string("signal")
    if (signal != null) {
      val extent =
        if (numbers.isEmpty()) {
          VegaValue.Arr(listOf(VegaValue.Null, VegaValue.Null))
        } else {
          VegaValue.Arr(listOf(VegaValue.Num(numbers.min()), VegaValue.Num(numbers.max())))
        }
      context.setSignal(signal, extent)
    }
    return input
  }
}
