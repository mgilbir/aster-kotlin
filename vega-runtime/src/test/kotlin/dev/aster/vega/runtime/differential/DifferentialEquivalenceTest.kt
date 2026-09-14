package dev.aster.vega.runtime.differential

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The two spellings this harness reads as one value, and the ones it must not.
 *
 * A differential harness earns its keep by failing. Every equivalence it allows is a place where a
 * real difference could hide, so each one is asserted here in both directions: the pair that means
 * the same thing agrees, and the neighbouring pair that does not still fails.
 *
 * - **A `limit` of zero is no limit.** `item.limit > 0 && text.length ? truncate(item, text) :
 *   text` — upstream's own test — so a specification writing `labelLimit: 0` and one leaving it out
 *   draw the same label. Upstream's item records the zero it was given; a `TextRun` here holds the
 *   same number and the harness omits it.
 * - **`bolder` is 700 and `lighter` is 100.** Both are relative to the inherited weight, and
 *   upstream writes `font-weight` on the `<text>` element with none on any ancestor — so a browser
 *   resolves them against the initial `normal`. This engine resolves them at compile time instead.
 */
class DifferentialEquivalenceTest {

  private fun text(numbers: Map<String, Double>, strings: Map<String, String>) =
    Differential.Mark("text", "axis-label", numbers, strings)

  @Test
  fun `a zero limit and no limit are the same label`() {
    val upstream = text(mapOf("x" to 10.0, "limit" to 0.0), mapOf("text" to "alpha"))
    val ours = text(mapOf("x" to 10.0), mapOf("text" to "alpha"))
    assertEquals(
      emptyList<Differential.Difference>(),
      Differential.compareMarks(listOf(upstream), listOf(ours)),
    )
  }

  @Test
  fun `a limit that truncates is still compared`() {
    val upstream = text(mapOf("x" to 10.0, "limit" to 30.0), mapOf("text" to "alpha"))
    val ours = text(mapOf("x" to 10.0), mapOf("text" to "alpha"))
    assertEquals(
      listOf("text/axis-label[0].limit: expected 30, got absent"),
      Differential.compareMarks(listOf(upstream), listOf(ours)).map { it.toString() },
    )
  }

  @Test
  fun `the relative weights resolve against normal`() {
    for ((keyword, resolved) in listOf("bolder" to "700", "lighter" to "100", "bold" to "700")) {
      val upstream = text(mapOf("x" to 10.0), mapOf("fontWeight" to keyword))
      val ours = text(mapOf("x" to 10.0), mapOf("fontWeight" to resolved))
      assertEquals(
        emptyList<Differential.Difference>(),
        Differential.compareMarks(listOf(upstream), listOf(ours)),
        keyword,
      )
    }
  }

  @Test
  fun `a weight resolved to the wrong number still fails`() {
    // 300 is what all three of this engine's font-weight parsers said for `lighter` before they
    // were merged, and it is a value CSS Fonts 4's table does not contain.
    val upstream = text(mapOf("x" to 10.0), mapOf("fontWeight" to "lighter"))
    val ours = text(mapOf("x" to 10.0), mapOf("fontWeight" to "300"))
    assertEquals(1, Differential.compareMarks(listOf(upstream), listOf(ours)).size)
  }
}
