package dev.aster.vega.scene

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The layout cache's eviction policy, run on every target — because it only exists in this form
 * *because* of the native targets.
 *
 * It used to be an anonymous subclass of `LinkedHashMap` with the three-argument access-order
 * constructor and `removeEldestEntry`, neither of which exists off the JVM, where the class is
 * final besides. `LinkedHashMap` itself is common Kotlin, so nothing noticed until the core was
 * compiled for Kotlin/Native. The replacement is four lines of explicit LRU and this is what says
 * it is the same policy: a **hit counts as a use**, which is the part a naive insertion-order cache
 * gets wrong and the part that decides whether a chart re-measures text it just measured.
 */
class TextLayoutCacheCommonTest {

  /** Counts how many times the engine underneath is actually asked. */
  private class CountingEngine : TextEngine {
    var layouts = 0

    override fun measure(text: TextRun, constraint: SizeD?): TextMetrics =
      layout(text, constraint).metrics

    override fun layout(text: TextRun, constraint: SizeD?): TextLayout {
      layouts++
      val metrics =
        TextMetrics(
          width = text.text.length * 6.0,
          height = 10.0,
          ascent = 8.0,
          descent = 2.0,
          lineCount = 1,
          lineHeight = 10.0,
        )
      return TextLayout(
        run = text,
        metrics = metrics,
        lines = emptyList(),
        bounds = RectD(0.0, -8.0, metrics.width, 2.0),
      )
    }
  }

  private fun run(text: String) = TextRun(text)

  @Test
  fun `a repeated layout is served from the cache`() {
    val engine = CountingEngine()
    val cache = TextLayoutCache(engine, maxEntries = 4)
    cache.layout(run("hello"))
    cache.layout(run("hello"))
    cache.layout(run("hello"))
    assertEquals(1, engine.layouts, "the engine should be asked once")
    assertEquals(1, cache.size)
    assertEquals(30.0, cache.layout(run("hello")).metrics.width)
  }

  @Test
  fun `the cache never grows past its bound`() {
    val engine = CountingEngine()
    val cache = TextLayoutCache(engine, maxEntries = 3)
    for (i in 1..10) cache.layout(run("label $i"))
    assertEquals(3, cache.size)
    assertEquals(10, engine.layouts)
  }

  /**
   * The whole point of access order: reading an entry makes it the *youngest*, so the thing evicted
   * is the least recently **used** rather than the least recently added.
   */
  @Test
  fun `a hit saves an entry from eviction`() {
    val engine = CountingEngine()
    val cache = TextLayoutCache(engine, maxEntries = 3)
    cache.layout(run("a"))
    cache.layout(run("b"))
    cache.layout(run("c"))
    cache.layout(run("a")) // a is now the youngest; b is the oldest
    cache.layout(run("d")) // evicts b
    assertEquals(3, cache.size)
    assertEquals(4, engine.layouts, "a, b, c, d were each laid out once so far")

    cache.layout(run("a"))
    assertEquals(4, engine.layouts, "a survived, so it is still cached")
    cache.layout(run("b"))
    assertEquals(5, engine.layouts, "b was the one evicted, so it is laid out again")
  }

  @Test
  fun `a different constraint is a different entry`() {
    val engine = CountingEngine()
    val cache = TextLayoutCache(engine, maxEntries = 4)
    cache.layout(run("hello"), null)
    cache.layout(run("hello"), SizeD(20.0, 10.0))
    assertEquals(2, engine.layouts)
    assertEquals(2, cache.size)
  }

  @Test
  fun `clearing empties it`() {
    val engine = CountingEngine()
    val cache = TextLayoutCache(engine, maxEntries = 4)
    cache.layout(run("hello"))
    cache.clear()
    assertEquals(0, cache.size)
    cache.layout(run("hello"))
    assertEquals(2, engine.layouts)
  }
}
