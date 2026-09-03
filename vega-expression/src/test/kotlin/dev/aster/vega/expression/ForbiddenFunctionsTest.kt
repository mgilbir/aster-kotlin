package dev.aster.vega.expression

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `eval` and its neighbours: not missing, **refused**, and refused for a reason.
 *
 * `SUPPORTED_FEATURES.md` files `eval` / JavaScript execution as `Not planned` — "explicitly
 * forbidden" — and that is a security claim rather than a scheduling one. A Vega specification is
 * *data*: it arrives pasted into a text box, fetched from a server, or embedded in a document
 * somebody else wrote. An expression language that could reach a general evaluator would turn every
 * one of those into arbitrary code execution in the host process.
 *
 * So the claim needs a test, and a stronger one than "we did not implement it". The evaluator has a
 * **closed** function registry and an unregistered name is an error, which is the property that
 * actually holds the line: nothing is reachable by naming it, whatever the host has linked in. The
 * day somebody registers `eval` this goes red, which is the point.
 */
class ForbiddenFunctionsTest {

  /** A scope that knows nothing, so only the registry can answer a name. */
  private val nothing =
    object : ExpressionScope {
      override val datum: VegaValue = VegaValue.Null

      override fun signal(name: String): VegaValue = VegaValue.Null

      override fun dataset(name: String): List<VegaValue> = emptyList()
    }

  private fun evaluate(source: String): VegaValue {
    val compiled = VegaExpressionCompiler().compile(source)
    check(compiled is ExpressionResult.Compiled) { "did not compile: $source" }
    return compiled.expression.evaluate(nothing)
  }

  /**
   * Whether [source] is refused, either at the parser or by the closed registry.
   *
   * Both count. `import('fs')` never reaches the evaluator because the grammar has no import, and
   * `eval('1')` parses as an ordinary call and dies on the lookup. What matters to the claim is
   * that neither runs, not which layer said no.
   */
  private fun refused(source: String): Boolean {
    val compiled = VegaExpressionCompiler().compile(source)
    if (compiled !is ExpressionResult.Compiled) return true
    return try {
      compiled.expression.evaluate(nothing)
      false
    } catch (failure: ExpressionEvaluationException) {
      failure.diagnostic.code == DiagnosticCodes.EXPRESSION_UNSUPPORTED_FUNCTION
    }
  }

  @Test
  fun `eval is refused by name`() {
    assertTrue(
      refused("eval('1 + 1')"),
      "an expression calling eval was evaluated rather than refused",
    )
  }

  /**
   * The whole family of *calls*, not just the one the row names.
   *
   * `eval` is the obvious door; these are the others a JavaScript host leaves open. None is
   * registered, and the test exists so that adding any of them has to be a deliberate act that
   * breaks a build rather than a convenience nobody reviewed.
   */
  @Test
  fun `nothing else that runs code can be called`() {
    for (source in
      listOf(
        "eval('1')",
        "Function('return 1')",
        "setTimeout('1', 0)",
        "setInterval('1', 0)",
        "require('fs')",
        "fetch('http://example.com')",
        "XMLHttpRequest()",
        "open('http://example.com')",
        "postMessage('x')",
      )) {
      assertTrue(refused(source), "`$source` was not refused")
    }
  }

  /**
   * A **bare name** is not refused — it resolves to nothing, which is the stronger property.
   *
   * `globalThis` is an identifier rather than a call, so the registry never sees it; what answers
   * is the scope, and the scope only knows signals, datasets and the datum. So the question is not
   * whether the language says no, it is whether naming a host object can *reach* one. It cannot:
   * every one of these comes back null.
   *
   * That distinction matters for the claim. "Refused" would be a statement about the parser and
   * would say nothing about what a host might have put in scope; "resolves to nothing" is a
   * statement about reachability, and reachability is what a security claim is made of.
   */
  @Test
  fun `naming a host object reaches nothing`() {
    for (source in listOf("globalThis", "process", "window", "self", "this", "constructor")) {
      val value = evaluate(source)
      assertTrue(
        value == VegaValue.Null || value == VegaValue.Undefined,
        "`$source` resolved to $value rather than to nothing",
      )
    }
  }

  /**
   * A datum's own prototype chain is not a way round it either.
   *
   * `datum.constructor` is the classic escape in a sandbox built on real JavaScript objects: reach
   * the constructor, reach `Function`, and you have an evaluator. Here a datum is a `VegaValue` and
   * a member lookup is a map lookup, so there is no chain to walk — but it is worth pinning,
   * because the day somebody backs `VegaValue` with a platform object this is the test that
   * notices.
   */
  @Test
  fun `a datum has no prototype chain to walk`() {
    for (source in
      listOf("datum.constructor", "datum.__proto__", "datum.prototype", "datum.constructor.name")) {
      val value = evaluate(source)
      assertTrue(
        value == VegaValue.Null || value == VegaValue.Undefined,
        "`$source` resolved to $value rather than to nothing",
      )
    }
  }

  /**
   * The registry is **closed**, which is the property the refusals rest on.
   *
   * Checked with an invented name rather than a real one: if an unregistered function were called
   * on some fallback — a host's own table, a reflective lookup — then every assertion above would
   * be measuring the absence of a name rather than the absence of a door.
   */
  @Test
  fun `an unregistered name is an error rather than a lookup`() {
    assertTrue(
      refused("aFunctionNobodyHasEverRegistered(1, 2)"),
      "an unknown function did not produce an unsupported-function diagnostic",
    )
  }

  /**
   * And the refusal is *narrow*: the functions Vega does document still work.
   *
   * A registry that refused everything would pass every test above and be useless. This is what
   * says the line is where it should be.
   */
  @Test
  fun `the documented functions still evaluate`() {
    assertEquals(VegaValue.Str("OK3"), evaluate("upper('ok') + length([1, 2, 3])"))
  }
}
