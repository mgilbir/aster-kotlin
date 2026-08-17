package dev.aster.vega.expression

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The expression cache's eviction policy, run on every target, and here for the same reason as
 * `TextLayoutCacheCommonTest`: it is written out by hand *because* `LinkedHashMap`'s access-order
 * mode is a JVM-only facility, and a policy nobody executes off the JVM is a policy nobody has
 * checked. A Vega specification reuses the same expression text across many marks and updates, so
 * what this protects is a parse per row.
 */
class CachingCompilerCommonTest {

  /** Counts compilations, and never actually parses anything. */
  private class CountingCompiler : ExpressionCompiler {
    var compilations = 0

    override fun compile(source: String): ExpressionResult {
      compilations++
      return VegaExpressionCompiler().compile(source)
    }
  }

  @Test
  fun `a repeated expression is compiled once`() {
    val delegate = CountingCompiler()
    val cache = CachingExpressionCompiler(delegate, maxEntries = 4)
    repeat(5) { cache.compile("datum.a + 1") }
    assertEquals(1, delegate.compilations)
    assertEquals(1, cache.size)
  }

  @Test
  fun `the cache never grows past its bound`() {
    val delegate = CountingCompiler()
    val cache = CachingExpressionCompiler(delegate, maxEntries = 3)
    for (i in 1..10) cache.compile("datum.a + $i")
    assertEquals(3, cache.size)
    assertEquals(10, delegate.compilations)
  }

  /** A hit is a use: the entry read most recently is the last one to be dropped. */
  @Test
  fun `a hit saves an entry from eviction`() {
    val delegate = CountingCompiler()
    val cache = CachingExpressionCompiler(delegate, maxEntries = 3)
    cache.compile("a")
    cache.compile("b")
    cache.compile("c")
    cache.compile("a") // a becomes the youngest, so b is now the oldest
    cache.compile("d") // evicts b
    assertEquals(4, delegate.compilations)

    cache.compile("a")
    assertEquals(4, delegate.compilations, "a survived")
    cache.compile("b")
    assertEquals(5, delegate.compilations, "b was evicted and must be compiled again")
  }

  /** A failure is cached like anything else — reparsing it would not make it parse. */
  @Test
  fun `a failed compilation is cached too`() {
    val delegate = CountingCompiler()
    val cache = CachingExpressionCompiler(delegate, maxEntries = 4)
    val first = cache.compile("datum.")
    val second = cache.compile("datum.")
    assertEquals(1, delegate.compilations)
    assertEquals(true, first is ExpressionResult.Failed)
    assertEquals(first, second)
  }
}
