package dev.aster.vega.runtime.compile

import dev.aster.vega.runtime.scale.LinearScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What a `width` that is not a plain positive number settles to, and what a scale ranged on it
 * gets.
 *
 * ```js
 * Math.max(0, w || 0)
 * ```
 *
 * Two operations, and the **order** is the whole of it: the falsiness runs on the raw value, before
 * the coercion. So a numeric `NaN` is falsey and becomes `0`, while a *word* is truthy and survives
 * into `Math.max`, whose coercion is `+` and whose NaN propagates. The two land in different places
 * from the same line.
 *
 * Every expectation below was read off a live upstream view — `view.signal('width')` and
 * `view.scale('x').range()` — and only that expression reproduces all eight.
 */
class LayoutSizeTest {

  private fun widthAndRange(update: String): Pair<Double, List<Double>> {
    val compiled =
      SpecCompiler()
        .compileJson(
          """
          {
            "width": {"signal": "w"}, "height": 40, "padding": 5,
            "signals": [{"name": "w", "update": $update}],
            "scales": [{"name": "x", "type": "linear", "domain": [0, 1], "range": "width"}],
            "marks": [{"type": "rect", "encode": {"update": {
              "x": {"scale": "x", "value": 0.5}, "y": {"value": 0}, "y2": {"value": 10},
              "width": {"value": 4}}}}]
          }
          """
            .trimIndent()
        )
    val width = (compiled.signals.values["width"] as? dev.aster.vega.model.VegaValue.Num)?.value
    return (width ?: Double.NaN) to (compiled.scales["x"] as LinearScale).range
  }

  @Test
  fun `a numeric NaN is falsey and settles to zero`() {
    val (width, range) = widthAndRange("0/0")
    assertEquals(0.0, width)
    assertEquals(listOf(0.0, 0.0), range)
  }

  /** And a **word** is truthy, so it reaches `Math.max` and stays NaN — the case that was wrong. */
  @Test
  fun `a word is truthy and settles to NaN`() {
    val (width, range) = widthAndRange("'wide'")
    assertTrue(width.isNaN(), "upstream's width signal is NaN, was $width")
    assertEquals(0.0, range.first())
    assertTrue(range.last().isNaN(), "a scale ranged on it gets [0, NaN], was $range")
  }

  @Test
  fun `an empty string and a null are falsey`() {
    assertEquals(0.0, widthAndRange("''").first)
    assertEquals(0.0, widthAndRange("null").first)
  }

  /** A negative size is no size — the clamp — and a negative infinity is clamped the same way. */
  @Test
  fun `a negative size is clamped to zero`() {
    assertEquals(0.0, widthAndRange("-50").first)
    assertEquals(0.0, widthAndRange("-1/0").first)
  }

  /** An **infinity** is not clamped: upstream ranges a scale on it as `[0, Infinity]`. */
  @Test
  fun `an infinite size survives the clamp`() {
    val (width, range) = widthAndRange("1/0")
    assertEquals(Double.POSITIVE_INFINITY, width)
    assertEquals(Double.POSITIVE_INFINITY, range.last())
  }

  @Test
  fun `an ordinary size is itself`() {
    val (width, range) = widthAndRange("120")
    assertEquals(120.0, width)
    assertEquals(listOf(0.0, 120.0), range)
  }
}
