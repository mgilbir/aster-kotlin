package dev.aster.vega.runtime.compile

import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionEvaluationException
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.spec.NumberValue

/**
 * Resolves a [NumberValue] to a number, evaluating a signal expression if that is what it is.
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
}
