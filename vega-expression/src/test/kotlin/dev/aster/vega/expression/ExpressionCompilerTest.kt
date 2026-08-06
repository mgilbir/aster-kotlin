package dev.aster.vega.expression

import dev.aster.vega.model.DiagnosticCodes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ExpressionCompilerTest {

  @Test
  fun `unsupported compiler reports a diagnostic instead of returning null`() {
    val result = UnsupportedExpressionCompiler().compile("datum.value * 2")
    assertTrue(result is ExpressionResult.Failed, "expected a failure, got $result")
    val failure = result as ExpressionResult.Failed
    assertEquals(DiagnosticCodes.EXPRESSION_UNSUPPORTED_FUNCTION, failure.diagnostic.code)
    assertTrue(failure.diagnostic.message.contains("datum.value * 2"))
  }

  @Test
  fun `cache compiles each distinct source once`() {
    val counting = CountingCompiler()
    val cache = CachingExpressionCompiler(counting)

    cache.compile("a")
    cache.compile("a")
    cache.compile("b")

    assertEquals(2, counting.calls)
    assertEquals(2, cache.size)
  }

  @Test
  fun `cache evicts the least recently used source`() {
    val counting = CountingCompiler()
    val cache = CachingExpressionCompiler(counting, maxEntries = 2)

    cache.compile("a")
    cache.compile("b")
    cache.compile("a") // b becomes least recently used
    cache.compile("c") // evicts b

    assertEquals(3, counting.calls)
    cache.compile("a")
    assertEquals(3, counting.calls, "a should still be cached")
    cache.compile("b")
    assertEquals(4, counting.calls, "b should have been evicted")
  }

  @Test
  fun `clear drops every entry`() {
    val counting = CountingCompiler()
    val cache = CachingExpressionCompiler(counting)
    cache.compile("a")
    cache.clear()
    assertEquals(0, cache.size)
    cache.compile("a")
    assertEquals(2, counting.calls)
  }

  private class CountingCompiler : ExpressionCompiler {
    var calls = 0
    private val delegate = UnsupportedExpressionCompiler()

    override fun compile(source: String): ExpressionResult {
      calls++
      return delegate.compile(source)
    }
  }
}
