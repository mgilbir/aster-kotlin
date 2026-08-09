package dev.aster.vega.runtime.compile

import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionEvaluationException
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.NumberValue

/**
 * Resolves a property that a specification may have supplied as a signal.
 *
 * Mostly numbers, hence the name, but a title's words can come from a signal too.
 *
 * Returns `null` when the value is absent or its expression fails, so the caller applies its own
 * default rather than a shared one — the default for `padding` is not the default for `tickSize`.
 */
public class NumberResolver(
  private val expressions: ExpressionCompiler,
  private val scope: SignalScope,
  private val diagnostics: DiagnosticCollector,
) {

  public fun resolve(value: NumberValue?, owner: String): Double? =
    when (value) {
      null -> null
      is NumberValue.Constant -> value.value
      is NumberValue.Signal ->
        when (val compiled = expressions.compile(value.expression)) {
          is ExpressionResult.Failed -> {
            diagnostics.add(compiled.diagnostic.copy(operator = owner))
            null
          }
          is ExpressionResult.Compiled ->
            try {
              JsSemantics.toNumber(compiled.expression.evaluate(scope)).takeIf { !it.isNaN() }
            } catch (e: ExpressionEvaluationException) {
              diagnostics.add(e.diagnostic.copy(operator = owner))
              null
            }
        }
    }

  public fun resolveInt(value: NumberValue?, owner: String): Int? = resolve(value, owner)?.toInt()

  /**
   * Evaluates an expression to a list of values, for the places a signal supplies a whole array.
   *
   * A scale domain is the common one: the `extent` transform publishes a two-element array and a
   * specification points a scale straight at it. A signal that is not an array is treated as a
   * one-element list rather than rejected, which is what upstream's own array coercion does.
   */
  /**
   * Evaluates an expression to text, for the properties whose value is words rather than a size.
   */
  public fun resolveText(expression: String, owner: String): String? =
    when (val compiled = expressions.compile(expression)) {
      is ExpressionResult.Failed -> {
        diagnostics.add(compiled.diagnostic.copy(operator = owner))
        null
      }
      is ExpressionResult.Compiled ->
        try {
          JsSemantics.toStringValue(compiled.expression.evaluate(scope))
        } catch (e: ExpressionEvaluationException) {
          diagnostics.add(e.diagnostic.copy(operator = owner))
          null
        }
    }

  /**
   * A guide's title, which upstream lets a signal supply as **lines** rather than as one string.
   *
   * `['Local Density', '(Normalized)']` is a two-line title: upstream's `textLines` reads an array
   * as the lines and collapses a one-element array to its element. Stringifying it instead joins
   * the lines with a comma and draws them on one, which is a different chart and a wider legend.
   */
  public fun resolveLines(expression: String, owner: String): String? {
    val value = resolveValue(expression, owner) ?: return null
    if (value !is VegaValue.Arr) return JsSemantics.toStringValue(value)
    if (value.values.size == 1) return JsSemantics.toStringValue(value.values.first())
    return value.values.joinToString("\n") { JsSemantics.toStringValue(it) }
  }

  /** The raw value of a signal, for a property whose shape depends on what the signal holds. */
  public fun resolveValue(expression: String, owner: String): VegaValue? =
    when (val compiled = expressions.compile(expression)) {
      is ExpressionResult.Failed -> {
        diagnostics.add(compiled.diagnostic.copy(operator = owner))
        null
      }
      is ExpressionResult.Compiled ->
        try {
          compiled.expression.evaluate(scope)
        } catch (e: ExpressionEvaluationException) {
          diagnostics.add(e.diagnostic.copy(operator = owner))
          null
        }
    }

  public fun resolveList(expression: String, owner: String): List<VegaValue>? =
    when (val compiled = expressions.compile(expression)) {
      is ExpressionResult.Failed -> {
        diagnostics.add(compiled.diagnostic.copy(operator = owner))
        null
      }
      is ExpressionResult.Compiled ->
        try {
          when (val value = compiled.expression.evaluate(scope)) {
            is VegaValue.Arr -> value.values
            VegaValue.Null -> null
            else -> listOf(value)
          }
        } catch (e: ExpressionEvaluationException) {
          diagnostics.add(e.diagnostic.copy(operator = owner))
          null
        }
    }
}
