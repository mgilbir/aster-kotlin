package dev.aster.vega.expression

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ParserTest {

  private fun parse(source: String): Node = Parser(source).parse()

  @Test
  fun `literals parse to their value model`() {
    assertEquals(Node.Literal(VegaValue.Num(42.0)), parse("42"))
    assertEquals(Node.Literal(VegaValue.Num(1.5)), parse("1.5"))
    assertEquals(Node.Literal(VegaValue.Num(0.5)), parse(".5"))
    assertEquals(Node.Literal(VegaValue.Num(1000.0)), parse("1e3"))
    assertEquals(Node.Literal(VegaValue.Num(16.0)), parse("0x10"))
    assertEquals(Node.Literal(VegaValue.Num(5.0)), parse("0b101"))
    assertEquals(Node.Literal(VegaValue.Str("hi")), parse("'hi'"))
    assertEquals(Node.Literal(VegaValue.Bool(true)), parse("true"))
    assertEquals(Node.Literal(VegaValue.Null), parse("null"))
  }

  @Test
  fun `string escapes are decoded`() {
    assertEquals(Node.Literal(VegaValue.Str("a\nb")), parse("'a\\nb'"))
    assertEquals(Node.Literal(VegaValue.Str("it's")), parse("'it\\'s'"))
    assertEquals(Node.Literal(VegaValue.Str("é")), parse("'\\u00e9'"))
  }

  @Test
  fun `member access chains left to right`() {
    val node = parse("datum.a.b")
    val outer = node as Node.Member
    assertEquals("b", (outer.property as Node.Identifier).name)
    val inner = outer.target as Node.Member
    assertEquals("a", (inner.property as Node.Identifier).name)
    assertEquals("datum", (inner.target as Node.Identifier).name)
  }

  @Test
  fun `bracket access is marked computed`() {
    val node = parse("datum['a b']") as Node.Member
    assertTrue(node.computed)
    assertEquals(VegaValue.Str("a b"), (node.property as Node.Literal).value)
  }

  @Test
  fun `calls can be nested and take expressions as arguments`() {
    val node = parse("min(abs(datum.v), 10)") as Node.Call
    assertEquals("min", (node.callee as Node.Identifier).name)
    assertEquals(2, node.arguments.size)
    assertTrue(node.arguments[0] is Node.Call)
  }

  @Test
  fun `logical operators become Logical nodes so they can short-circuit`() {
    assertTrue(parse("a && b") is Node.Logical)
    assertTrue(parse("a || b") is Node.Logical)
    assertTrue(parse("a & b") is Node.Binary)
  }

  @Test
  fun `conditional is right-associative`() {
    // `a ? b : c ? d : e` groups as `a ? b : (c ? d : e)`.
    val node = parse("a ? b : c ? d : e") as Node.Conditional
    assertEquals("a", (node.test as Node.Identifier).name)
    assertTrue(node.alternate is Node.Conditional)
  }

  @Test
  fun `unary operators bind tighter than binary ones`() {
    val node = parse("-a + b") as Node.Binary
    assertEquals("+", node.operator)
    assertTrue(node.left is Node.Unary)
  }

  @Test
  fun `object and array literals parse`() {
    val obj = parse("{a: 1, 'b c': 2}") as Node.ObjectLiteral
    assertEquals(2, obj.entries.size)
    assertEquals(VegaValue.Str("a"), (obj.entries[0].first as Node.Literal).value)
    assertEquals(VegaValue.Str("b c"), (obj.entries[1].first as Node.Literal).value)

    val array = parse("[1, 'two', three]") as Node.ArrayLiteral
    assertEquals(3, array.elements.size)
  }

  @Test
  fun `empty literals parse`() {
    assertEquals(0, (parse("[]") as Node.ArrayLiteral).elements.size)
    assertEquals(0, (parse("{}") as Node.ObjectLiteral).entries.size)
  }

  // ---- errors ---------------------------------------------------------------

  @Test
  fun `syntax errors point at the offending offset`() {
    val failure = assertThrows<ExpressionSyntaxException> { parse("1 +") }
    assertTrue(failure.message!!.contains("offset"), failure.message)

    assertThrows<ExpressionSyntaxException> { parse("(1") }
    assertThrows<ExpressionSyntaxException> { parse("1 2") }
    assertThrows<ExpressionSyntaxException> { parse("datum.") }
    assertThrows<ExpressionSyntaxException> { parse("'unterminated") }
    assertThrows<ExpressionSyntaxException> { parse("[1,") }
    assertThrows<ExpressionSyntaxException> { parse("a ? b") }
  }

  @Test
  fun `an assignment is rejected rather than silently accepted`() {
    // There is no assignment in the language; `=` must not be mistaken for equality.
    assertThrows<ExpressionSyntaxException> { parse("a = 1") }
  }

  @Test
  fun `a regular expression literal is rejected`() {
    assertThrows<ExpressionSyntaxException> { parse("/ab+c/.test('x')") }
  }

  @Test
  fun `the compiler turns a syntax error into a diagnostic`() {
    val result = VegaExpressionCompiler().compile("1 +")
    assertTrue(result is ExpressionResult.Failed)
    assertEquals(
      DiagnosticCodes.EXPRESSION_PARSE_ERROR,
      (result as ExpressionResult.Failed).diagnostic.code,
    )
  }

  // ---- dependencies ---------------------------------------------------------

  private fun compiled(source: String): ParsedExpression =
    (VegaExpressionCompiler().compile(source) as ExpressionResult.Compiled).expression
      as ParsedExpression

  @Test
  fun `field dependencies report the datum paths an expression reads`() {
    assertEquals(setOf("amount"), compiled("datum.amount * 2").fieldDependencies)
    assertEquals(setOf("a.b"), compiled("datum.a.b").fieldDependencies)
    assertEquals(setOf("x", "y"), compiled("datum.x + datum.y").fieldDependencies)
    assertEquals(setOf("odd name"), compiled("datum['odd name']").fieldDependencies)
  }

  @Test
  fun `a nested path is reported once, at its longest form`() {
    // Reporting both `a` and `a.b` would overstate what the expression depends on.
    assertEquals(setOf("a.b"), compiled("datum.a.b + 1").fieldDependencies)
  }

  @Test
  fun `a dynamic field access reports no static path`() {
    assertEquals(emptySet<String>(), compiled("datum[someSignal]").fieldDependencies)
  }

  @Test
  fun `signal dependencies exclude datum, constants and function names`() {
    assertEquals(setOf("width"), compiled("width / 2").signalDependencies)
    assertEquals(emptySet<String>(), compiled("datum.v * PI").signalDependencies)
    assertEquals(emptySet<String>(), compiled("abs(datum.v)").signalDependencies)
    assertEquals(setOf("threshold"), compiled("datum.v > threshold").signalDependencies)
  }

  @Test
  fun `a signal used as a dynamic field is still a dependency`() {
    assertEquals(setOf("chosenField"), compiled("datum[chosenField]").signalDependencies)
  }

  @Test
  fun `a dataset named as a literal is reported as a dependency`() {
    assertEquals(setOf("summary"), compiled("data('summary')[0].mean").dataDependencies)
    assertEquals(setOf("table"), compiled("indata('table', 'k', 1)").dataDependencies)
    assertEquals(false, compiled("data('summary')").readsUnnamedDataset)
  }

  @Test
  fun `a dataset named by an expression is reported as unnamed`() {
    // No name to record, so anything ordering against this has to wait for every dataset.
    val expression = compiled("data(chosen)")
    assertEquals(emptySet<String>(), expression.dataDependencies)
    assertEquals(true, expression.readsUnnamedDataset)
  }

  @Test
  fun `every function that takes a scale name reports it`() {
    // `domain` and `range` belong on this list as much as `scale` does; a signal reading
    // `domain('xscale')` waits on that scale exactly the way one calling `scale('xscale', 0)` does.
    assertEquals(setOf("x"), compiled("scale('x', datum.v)").scaleDependencies)
    assertEquals(setOf("x"), compiled("invert('x', 10)").scaleDependencies)
    assertEquals(setOf("xscale"), compiled("domain('xscale')").scaleDependencies)
    assertEquals(setOf("xscale"), compiled("range('xscale')").scaleDependencies)
    assertEquals(setOf("y"), compiled("bandwidth('y')").scaleDependencies)
    assertEquals(setOf("x", "y"), compiled("scale('x', 0) + bandwidth('y')").scaleDependencies)
  }

  @Test
  fun `a scale named by an expression is reported as unnamed`() {
    val expression = compiled("scale(which, datum.v)")
    assertEquals(emptySet<String>(), expression.scaleDependencies)
    assertEquals(true, expression.readsUnnamedScale)
  }

  @Test
  fun `a name that is not a call is not a dependency`() {
    // A signal *named* `data`, and a field *called* `scale`; neither is a call, and reading the
    // source text rather than the tree would take both for one.
    val expression = compiled("data + datum.scale")
    assertEquals(emptySet<String>(), expression.dataDependencies)
    assertEquals(emptySet<String>(), expression.scaleDependencies)
    assertEquals(false, expression.readsUnnamedDataset)
    assertEquals(false, expression.readsUnnamedScale)
    assertEquals(setOf("data"), expression.signalDependencies)
  }

  @Test
  fun `parsing is reusable across evaluations`() {
    val expression = compiled("datum.v * 2")
    val first = expression.evaluate(scopeOf("v" to VegaValue.Num(3.0)))
    val second = expression.evaluate(scopeOf("v" to VegaValue.Num(5.0)))
    assertEquals(VegaValue.Num(6.0), first)
    assertEquals(VegaValue.Num(10.0), second)
  }

  private fun scopeOf(vararg fields: Pair<String, VegaValue>): ExpressionScope =
    object : ExpressionScope {
      override val datum: VegaValue = VegaValue.Obj(linkedMapOf(*fields))

      override fun signal(name: String): VegaValue = VegaValue.Null

      override fun dataset(name: String): List<VegaValue> = emptyList()
    }

  // ---- unsupported functions -------------------------------------------------

  @Test
  fun `an unknown function reports rather than returning null`() {
    val expression = compiled("nosuchfunction(1)")
    val failure = assertThrows<ExpressionEvaluationException> { expression.evaluate(scopeOf()) }
    assertEquals(DiagnosticCodes.EXPRESSION_UNSUPPORTED_FUNCTION, failure.diagnostic.code)
    assertTrue(failure.diagnostic.message.contains("nosuchfunction"))
  }

  @Test
  fun `a deliberately excluded function explains why`() {
    val failure =
      assertThrows<ExpressionEvaluationException> { compiled("geoArea()").evaluate(scopeOf()) }
    assertTrue(
      failure.diagnostic.message.contains("geographic"),
      failure.diagnostic.message,
    )
  }

  @Test
  fun `method calls are rejected with an explanation`() {
    val failure =
      assertThrows<ExpressionEvaluationException> {
        compiled("datum.v.toFixed(2)").evaluate(scopeOf())
      }
    assertTrue(failure.diagnostic.message.contains("method calls"), failure.diagnostic.message)
  }

  @Test
  fun `evaluateOrNull converts a failure into a Result`() {
    assertTrue(compiled("geoArea()").evaluateOrNull(scopeOf()).isFailure)
    assertTrue(compiled("1 + 1").evaluateOrNull(scopeOf()).isSuccess)
  }
}
