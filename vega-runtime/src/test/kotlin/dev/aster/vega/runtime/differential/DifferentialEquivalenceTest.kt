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
 * - **A stroke detail on an unstroked mark paints nothing.** `addEncoders` puts every property the
 *   specification names on the item whether or not anything uses it, so a legend symbol given a
 *   `symbolDash` carries a `strokeDash` even where the legend maps a fill and has no stroke colour.
 * - **A channel the mark type cannot read paints nothing.** `item.clip` is read for a *group* item
 *   and nowhere else; a `limit` is read by `textValue`, which only a text mark calls.
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
  fun `a dash on a mark with no stroke paints nothing`() {
    val upstream =
      Differential.Mark(
        "symbol",
        "legend-symbol",
        mapOf("x" to 10.0, "size" to 100.0),
        mapOf("fill" to "#4c78a8", "strokeDash" to "4,2"),
      )
    val ours =
      Differential.Mark(
        "symbol",
        "legend-symbol",
        mapOf("x" to 10.0, "size" to 100.0),
        mapOf("fill" to "#4c78a8"),
      )
    assertEquals(
      emptyList<Differential.Difference>(),
      Differential.compareMarks(listOf(upstream), listOf(ours)),
    )
  }

  @Test
  fun `a dash on a mark that is stroked is still compared`() {
    val upstream =
      Differential.Mark(
        "symbol",
        "legend-symbol",
        mapOf("x" to 10.0, "size" to 100.0),
        mapOf("stroke" to "#333333", "strokeDash" to "4,2"),
      )
    val ours =
      Differential.Mark(
        "symbol",
        "legend-symbol",
        mapOf("x" to 10.0, "size" to 100.0),
        mapOf("stroke" to "#333333"),
      )
    assertEquals(
      listOf("symbol/legend-symbol[0].strokeDash: expected 4,2, got absent"),
      Differential.compareMarks(listOf(upstream), listOf(ours)).map { it.toString() },
    )
  }

  @Test
  fun `a stroke the reference has and this side lacks is still a difference`() {
    val upstream =
      Differential.Mark(
        "symbol",
        "legend-symbol",
        mapOf("x" to 10.0, "size" to 100.0),
        mapOf("stroke" to "#333333", "strokeDash" to "4,2"),
      )
    val ours =
      Differential.Mark("symbol", "legend-symbol", mapOf("x" to 10.0, "size" to 100.0), emptyMap())
    // Both: the colour, because a mark upstream outlines and this one does not is a different
    // drawing, and the dash with it, since the reference *does* stroke here.
    assertEquals(
      listOf(
        "symbol/legend-symbol[0].stroke: expected #333333, got absent",
        "symbol/legend-symbol[0].strokeDash: expected 4,2, got absent",
      ),
      Differential.compareMarks(listOf(upstream), listOf(ours)).map { it.toString() },
    )
  }

  @Test
  fun `a channel the mark cannot read is not a difference`() {
    val upstream =
      Differential.Mark(
        "rect",
        "mark",
        mapOf("x" to 10.0, "limit" to 8.0),
        mapOf("fill" to "#4c78a8", "clip" to "true", "strokeCap" to "round"),
      )
    val ours = Differential.Mark("rect", "mark", mapOf("x" to 10.0), mapOf("fill" to "#4c78a8"))
    assertEquals(
      emptyList<Differential.Difference>(),
      Differential.compareMarks(listOf(upstream), listOf(ours)),
    )
  }

  @Test
  fun `the same channel on the mark that does read it is compared`() {
    val upstream =
      Differential.Mark("text", "mark", mapOf("x" to 10.0, "limit" to 8.0), mapOf("text" to "a"))
    val ours = Differential.Mark("text", "mark", mapOf("x" to 10.0), mapOf("text" to "a"))
    assertEquals(
      listOf("text/mark[0].limit: expected 8, got absent"),
      Differential.compareMarks(listOf(upstream), listOf(ours)).map { it.toString() },
    )
  }

  @Test
  fun `a stroke cap on a stroked mark is compared against the implied default`() {
    val upstream =
      Differential.Mark(
        "rect",
        "mark",
        mapOf("x" to 10.0),
        mapOf("stroke" to "#333333", "strokeCap" to "round"),
      )
    val ours = Differential.Mark("rect", "mark", mapOf("x" to 10.0), mapOf("stroke" to "#333333"))
    assertEquals(
      listOf("rect/mark[0].strokeCap: expected round, got butt"),
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
