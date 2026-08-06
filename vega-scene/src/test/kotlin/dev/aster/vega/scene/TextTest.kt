package dev.aster.vega.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextTest {

  private val engine = MetricTextEngine()
  private val style = TextStyle(fontSize = 10.0)

  @Test
  fun `empty text measures to zero width`() {
    val metrics = engine.measure(TextRun("", style))
    assertEquals(0.0, metrics.width)
    assertEquals(1, metrics.lineCount)
  }

  @Test
  fun `width scales with character count and font size`() {
    val short = engine.measure(TextRun("ab", style)).width
    val long = engine.measure(TextRun("abcd", style)).width
    assertEquals(2.0, long / short, 1e-9)

    val bigger = engine.measure(TextRun("ab", style.copy(fontSize = 20.0))).width
    assertEquals(2.0, bigger / short, 1e-9)
  }

  @Test
  fun `letter spacing applies between characters only`() {
    val plain = engine.measure(TextRun("abc", style)).width
    val spaced = engine.measure(TextRun("abc", style.copy(letterSpacing = 2.0))).width
    assertEquals(4.0, spaced - plain, 1e-9)
  }

  @Test
  fun `explicit newlines produce multiple lines`() {
    val metrics = engine.measure(TextRun("one\ntwo\nthree", style))
    assertEquals(3, metrics.lineCount)
    assertTrue(metrics.height > metrics.lineHeight)
  }

  @Test
  fun `width constraint wraps on spaces`() {
    val run = TextRun("aaa bbb ccc ddd", style)
    val unconstrained = engine.layout(run)
    assertEquals(1, unconstrained.metrics.lineCount)

    val wrapped = engine.layout(run, SizeD(width = 45.0, height = Double.MAX_VALUE))
    assertTrue(wrapped.metrics.lineCount > 1)
    assertTrue(wrapped.metrics.width <= 45.0 + 1e-9)
  }

  @Test
  fun `a single word longer than the constraint is not dropped`() {
    val wrapped = engine.layout(TextRun("unbreakableword", style), SizeD(5.0, 100.0))
    assertEquals(1, wrapped.metrics.lineCount)
    assertEquals("unbreakableword", wrapped.lines.single().text)
  }

  @Test
  fun `align positions bounds relative to the anchor`() {
    val metrics = engine.measure(TextRun("abcd", style))
    val width = metrics.width

    val left = textBounds(TextRun("abcd", style, align = TextAlign.LEFT), metrics)
    assertEquals(0.0, left.left, 1e-9)

    val center = textBounds(TextRun("abcd", style, align = TextAlign.CENTER), metrics)
    assertEquals(-width / 2.0, center.left, 1e-9)

    val right = textBounds(TextRun("abcd", style, align = TextAlign.RIGHT), metrics)
    assertEquals(-width, right.left, 1e-9)
  }

  @Test
  fun `baseline positions bounds vertically`() {
    val metrics = engine.measure(TextRun("abcd", style))

    val top = textBounds(TextRun("abcd", style, baseline = TextBaseline.TOP), metrics)
    assertEquals(0.0, top.top, 1e-9)

    val middle = textBounds(TextRun("abcd", style, baseline = TextBaseline.MIDDLE), metrics)
    assertEquals(-metrics.height / 2.0, middle.top, 1e-9)

    val bottom = textBounds(TextRun("abcd", style, baseline = TextBaseline.BOTTOM), metrics)
    assertEquals(-metrics.height, bottom.top, 1e-9)

    val alphabetic = textBounds(TextRun("abcd", style, baseline = TextBaseline.ALPHABETIC), metrics)
    assertEquals(-metrics.ascent, alphabetic.top, 1e-9)
  }

  @Test
  fun `measurement is deterministic across calls`() {
    val run = TextRun("repeatable", style)
    assertEquals(engine.measure(run), engine.measure(run))
  }

  @Test
  fun `invalid font size and weight are rejected`() {
    org.junit.jupiter.api.assertThrows<IllegalArgumentException> { TextStyle(fontSize = -1.0) }
    org.junit.jupiter.api.assertThrows<IllegalArgumentException> {
      TextStyle(fontSize = Double.NaN)
    }
    org.junit.jupiter.api.assertThrows<IllegalArgumentException> { TextStyle(fontWeight = 0) }
  }

  @Test
  fun `cache returns identical layouts and counts entries once`() {
    val counting = CountingTextEngine()
    val cache = TextLayoutCache(counting, maxEntries = 4)
    val run = TextRun("cached", style)

    val first = cache.layout(run)
    val second = cache.layout(run)
    assertTrue(first === second)
    assertEquals(1, counting.calls)
    assertEquals(1, cache.size)
  }

  @Test
  fun `cache key distinguishes style and constraint`() {
    val counting = CountingTextEngine()
    val cache = TextLayoutCache(counting)
    val run = TextRun("cached", style)

    cache.layout(run)
    cache.layout(run.copy(style = style.copy(fontSize = 12.0)))
    cache.layout(run.copy(align = TextAlign.CENTER))
    cache.layout(run, SizeD(10.0, 10.0))

    assertEquals(4, counting.calls)
  }

  @Test
  fun `cache evicts the least recently used entry`() {
    val counting = CountingTextEngine()
    val cache = TextLayoutCache(counting, maxEntries = 2)
    val a = TextRun("a", style)
    val b = TextRun("b", style)
    val c = TextRun("c", style)

    cache.layout(a)
    cache.layout(b)
    cache.layout(a) // makes b the least recently used
    cache.layout(c) // evicts b
    assertEquals(2, cache.size)

    cache.layout(a)
    assertEquals(3, counting.calls, "a should still be cached")

    cache.layout(b)
    assertEquals(4, counting.calls, "b should have been evicted")
  }

  private class CountingTextEngine(private val delegate: TextEngine = MetricTextEngine()) :
    TextEngine {
    var calls = 0

    override fun measure(text: TextRun, constraint: SizeD?): TextMetrics =
      layout(text, constraint).metrics

    override fun layout(text: TextRun, constraint: SizeD?): TextLayout {
      calls++
      return delegate.layout(text, constraint)
    }
  }
}
