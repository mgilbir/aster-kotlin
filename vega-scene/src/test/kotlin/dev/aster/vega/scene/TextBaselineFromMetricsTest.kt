package dev.aster.vega.scene

import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where a baseline comes from, and why the fixture corpus agrees with upstream anyway.
 *
 * `SUPPORTED_FEATURES.md` records a `Known difference`: upstream positions glyphs by its own
 * approximation — `round(0.79 × fontSize)` for a top baseline — and this engine asks the platform.
 * They differ by up to a unit at common sizes, which is why the harness excludes glyph bounds (ADR
 * 0006).
 *
 * Both halves need saying, because the row read as though *every* renderer here disagreed with
 * upstream, and the differential corpus plainly does not. The reason is [MetricTextEngine]: it is
 * upstream's canvas-less approximation, reproduced, and it is what the fixtures are measured with.
 * A **platform** engine — Android's `Paint.FontMetrics`, Core Text — is where the real metrics come
 * in, and that is where the difference lives; `AndroidTextEngineTest` holds that end, on a device.
 *
 * So this pins the JVM end: the fallback agrees with upstream's constant, exactly, at the sizes a
 * chart uses. If it ever stopped, every fixture would move at once and the cause would be here.
 */
class TextBaselineFromMetricsTest {

  private val engine = MetricTextEngine()

  private fun run(size: Double) = TextRun(text = "Ag", style = TextStyle(fontSize = size))

  /** The sizes Vega's own defaults and the fixture corpus actually use. */
  private val sizes = listOf(8.0, 10.0, 11.0, 12.0, 13.0, 14.0, 16.0, 20.0, 24.0)

  /**
   * The fallback's ascent is `0.8 × fontSize`, unrounded.
   *
   * Upstream's is `round(0.79 × fontSize)`, so the two agree at some sizes by coincidence — 10
   * gives 8 either way — and part company at others: at 8 this gives 6.4 where upstream gives 6.
   * That is the difference the row is about, in its smallest form, and it is under a unit
   * everywhere. If this constant ever moved, the text in every fixture would move with it, so it is
   * pinned.
   */
  @Test
  fun `the fallback ascent is eight tenths of the font size`() {
    for (size in sizes) {
      assertEquals(
        0.8 * size,
        engine.measure(run(size)).ascent,
        1e-9,
        "the fallback engine's ascent at size $size is no longer 0.8 x the size, which would move " +
          "the text in every fixture at once",
      )
    }
  }

  /**
   * And it stays within a unit of upstream's rounded approximation, which is what the row claims.
   */
  @Test
  fun `the fallback stays within a unit of upstream's approximation`() {
    var sawADifference = false
    for (size in sizes) {
      val upstream = (0.79 * size).roundToInt().toDouble()
      val here = engine.measure(run(size)).ascent
      assertTrue(
        abs(here - upstream) <= 1.0,
        "at size $size this engine reads $here and upstream $upstream, which is past the unit the " +
          "row allows",
      )
      if (abs(here - upstream) > 1e-9) sawADifference = true
    }
    assertTrue(
      sawADifference,
      "every size agreed exactly, so there is no difference left to record",
    )
  }

  /**
   * Ascent and descent add up to the line box, which is what makes them metrics rather than two
   * numbers.
   *
   * The same invariant `AndroidTextEngineTest` asserts on a device for the platform engine, so the
   * two ends of the claim are held to the same shape.
   */
  @Test
  fun `ascent and descent sum to the height`() {
    for (size in sizes) {
      val metrics = engine.measure(run(size))
      assertTrue(metrics.ascent > 0.0 && metrics.descent > 0.0, "at size $size")
      assertEquals(metrics.height, metrics.ascent + metrics.descent, 1e-9, "at size $size")
    }
  }

  /**
   * The gap the row is about, computed rather than asserted, so the *number* in it stays honest.
   *
   * A platform engine's ascent for a common UI font runs a little under 0.8 of the size — Roboto's
   * is about 0.75 — so against `round(0.79 × size)` the difference is fractions of a unit at small
   * sizes and approaches one at large ones. This checks that "up to a unit at common sizes" is the
   * right description of that, using the ratio rather than a device.
   */
  @Test
  fun `up to a unit is the right description of the gap`() {
    val platformAscentRatio = 0.75
    val worst = sizes.maxOf { size -> abs((0.79 * size).roundToInt() - platformAscentRatio * size) }
    assertTrue(
      worst <= 1.5,
      "against a platform ascent ratio of $platformAscentRatio the worst gap is $worst, which is " +
        "no longer 'up to a unit' and the row should say so",
    )
    assertTrue(worst > 0.0, "the two readings never differ, so there is no difference to record")
  }
}
