package dev.aster.vega.expression

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.model.VegaValue

/**
 * A parsed, reusable Vega expression.
 *
 * Dependencies are computed once at parse time so the dataflow can decide what an expression
 * invalidates without re-walking it. Only static references are reported: `datum[someSignal]` names
 * no field at compile time, and that is visible as its absence rather than guessed at.
 */
public class ParsedExpression(
  override val source: String,
  public val ast: Node,
  private val evaluator: Evaluator,
) : Expression {

  override val signalDependencies: Set<String> by lazy { collectSignals() }

  override val fieldDependencies: Set<String> by lazy { collectFields() }

  override fun evaluate(scope: ExpressionScope): VegaValue = evaluator.evaluate(ast, scope)

  /**
   * Evaluates, converting a failure into a diagnostic instead of an exception.
   *
   * Useful where one bad expression should not abort compiling a whole chart.
   */
  public fun evaluateOrNull(scope: ExpressionScope): Result<VegaValue> =
    try {
      Result.success(evaluate(scope))
    } catch (e: ExpressionEvaluationException) {
      Result.failure(e)
    }

  private fun collectSignals(): Set<String> {
    val names = mutableSetOf<String>()
    // A bare identifier is a signal reference unless it is `datum`, a constant, or a function name.
    val called = mutableSetOf<Node>()
    ast.walk { node -> if (node is Node.Call) called.add(node.callee) }
    ast.walk { node ->
      if (node is Node.Identifier && node !in called) {
        if (node.name != "datum" && node.name !in Functions.constants) names.add(node.name)
      }
      // The target of a non-computed member access is the thing being read, not a property name.
      if (node is Node.Member && !node.computed) {
        val property = node.property
        if (property is Node.Identifier) names.remove(property.name)
      }
    }
    return names
  }

  private fun collectFields(): Set<String> {
    val fields = mutableSetOf<String>()
    ast.walk { node -> node.datumFieldPath()?.let { fields.add(it) } }
    // Keep only the longest path in each chain: `datum.a.b` implies reading `datum.a`, and
    // reporting
    // both would make the dependency set misleadingly broad.
    return fields
      .filterNot { candidate -> fields.any { it != candidate && it.startsWith("$candidate.") } }
      .toSet()
  }
}

/**
 * Parses Vega expressions into evaluable trees.
 *
 * Replaces [UnsupportedExpressionCompiler]. Wrap it in [CachingExpressionCompiler] to avoid
 * reparsing the same source, which Vega specifications do constantly — the same encode expression
 * runs once per datum.
 */
public class VegaExpressionCompiler(private val evaluator: Evaluator = Evaluator()) :
  ExpressionCompiler {

  override fun compile(source: String): ExpressionResult =
    try {
      ExpressionResult.Compiled(ParsedExpression(source, Parser(source).parse(), evaluator))
    } catch (e: ExpressionSyntaxException) {
      ExpressionResult.Failed(
        VegaDiagnostic(
          severity = DiagnosticSeverity.ERROR,
          code = DiagnosticCodes.EXPRESSION_PARSE_ERROR,
          message = e.message ?: "Could not parse expression",
          cause = e,
        )
      )
    }
}
