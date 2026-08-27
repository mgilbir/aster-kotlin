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

  private val deferred by lazy { collectDeferred() }

  override val dataDependencies: Set<String>
    get() = deferred.datasets

  override val readsUnnamedDataset: Boolean
    get() = deferred.unnamedDataset

  override val scaleDependencies: Set<String>
    get() = deferred.scales

  override val readsUnnamedScale: Boolean
    get() = deferred.unnamedScale

  override val writtenDatasets: Set<String>
    get() = deferred.written

  override val functionDependencies: Set<String>
    get() = deferred.functions

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
    // `walk` does not descend into a non-computed member's property — `datum.year` visits the
    // member and its target and never the name after the dot — so a property name is never added
    // here and there is nothing to take back out.
    //
    // There used to be a second clause that removed one anyway, and it removed it *by name* from
    // the whole set: `"year == datum.year"` reported no dependency on the signal `year` at all. A
    // dependency that is never recorded is never missed, so `DataflowOrder` resolved the
    // expression before the signal existed and never re-evaluated it after — a slider bound to
    // `year` moved nothing, and there was no diagnostic to read, because from the compiler's point
    // of view the expression did not mention it.
    ast.walk { node ->
      if (node is Node.Identifier && node !in called) {
        if (node.name != "datum" && node.name !in Functions.constants) names.add(node.name)
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

  /** What one walk of the tree found about the datasets and scales this expression reaches for. */
  private class Deferred(
    val datasets: Set<String>,
    val unnamedDataset: Boolean,
    val scales: Set<String>,
    val unnamedScale: Boolean,
    val written: Set<String>,
    val functions: Set<String>,
  )

  /**
   * Reads the dataset and scale names out of the tree, as upstream's visitors do.
   *
   * A name counts only when it is written as a string literal, because that is the only case where
   * it is knowable before the expression runs. Anything else is recorded as a nameless read, which
   * an ordering has to satisfy by building everything of that kind first.
   */
  private fun collectDeferred(): Deferred {
    val datasets = mutableSetOf<String>()
    val scales = mutableSetOf<String>()
    val written = mutableSetOf<String>()
    var unnamedDataset = false
    var unnamedScale = false
    // Collected in this walk rather than in one of its own: it already visits every `Call` node and
    // reads the callee's name, so the extra fact is free where a second traversal would not be.
    val functions = mutableSetOf<String>()
    ast.walk { node ->
      if (node !is Node.Call) return@walk
      val callee = node.callee
      if (callee !is Node.Identifier) return@walk
      functions.add(callee.name)
      val into =
        when (callee.name) {
          in DATA_FUNCTIONS -> datasets
          in SCALE_FUNCTIONS -> scales
          // `modify` writes a dataset as surely as `setdata` replaces one, so anything reading that
          // dataset has to be ordered behind the expression either way.
          "setdata",
          "modify" -> written
          else -> return@walk
        }
      val first = node.arguments.firstOrNull()
      // Upstream's visitors branch on the argument being a **`Literal` node**, not on its being a
      // string, and the difference is load-bearing. `geoArea(null, feature)` — the documented way
      // to measure on the globe rather than through a projection — names no scale, and reading it
      // as "some scale, we cannot tell which" made *every* scale a dependency of the dataset and
      // reported a cycle for a chart upstream compiles. A non-string literal names whatever it
      // stringifies to, which matches no operator, so it contributes nothing.
      val literal = (first as? Node.Literal)?.value?.let { JsSemantics.toStringValue(it) }
      when {
        literal != null -> into.add(literal)
        into === datasets -> unnamedDataset = true
        into === written -> Unit // nothing can be ordered around a name nobody wrote down
        else -> unnamedScale = true
      }
    }
    return Deferred(datasets, unnamedDataset, scales, unnamedScale, written, functions)
  }

  private companion object {
    /**
     * Functions taking a dataset name, matching upstream's `dataVisitor` registrations.
     *
     * `treePath` and `treeAncestors` are on the list because upstream puts them there; this engine
     * does not evaluate them, so they only ever contribute an ordering nobody uses.
     */
    private val DATA_FUNCTIONS = setOf("data", "indata", "treePath", "treeAncestors")

    /**
     * Functions taking a scale name, matching upstream's `scaleVisitor` registrations.
     *
     * `domain` and `range` belong here and were missing while the only question asked of this walk
     * was "does it read anything deferred": a signal reading `domain('xscale')` looked free of both
     * data and scales, and so was resolved before either existed.
     */
    private val SCALE_FUNCTIONS =
      setOf(
        "scale",
        "invert",
        "domain",
        "range",
        "bandwidth",
        "copy",
        "gradient",
        "geoArea",
        "geoBounds",
        "geoCentroid",
        "geoShape",
        "geoScale",
      )
  }
}

/**
 * Parses Vega expressions into evaluable trees.
 *
 * The real one; [RefusingExpressionCompiler] is the stand-in that evaluates nothing. Wrap it in
 * [CachingExpressionCompiler] to avoid reparsing the same source, which Vega specifications do
 * constantly — the same encode expression runs once per datum.
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
