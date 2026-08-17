package dev.aster.vega.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.aster.vega.scene.FontStyle
import dev.aster.vega.scene.SizeD
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextBaseline
import dev.aster.vega.scene.TextRun
import dev.aster.vega.scene.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Text metrics come from the platform, so these assertions are about relationships (wider, taller,
 * proportional) rather than exact pixel counts — the documented policy for text tolerances
 * (PROJECT_BRIEF.md 9, 18.4).
 */
@RunWith(AndroidJUnit4::class)
class AndroidTextEngineTest {

  private val engine = AndroidTextEngine()
  private val style = TextStyle(fontSize = 14.0)

  @Test
  fun emptyTextHasZeroWidth() {
    assertEquals(0.0, engine.measure(TextRun("", style)).width, 0.01)
  }

  @Test
  fun widthGrowsWithTextLength() {
    val short = engine.measure(TextRun("ab", style)).width
    val long = engine.measure(TextRun("abcdefgh", style)).width
    assertTrue("expected $long > $short", long > short)
  }

  @Test
  fun widthGrowsWithFontSize() {
    val small = engine.measure(TextRun("sample", style)).width
    val large = engine.measure(TextRun("sample", style.copy(fontSize = 28.0))).width
    assertTrue("expected $large > $small", large > small)
  }

  @Test
  fun ascentAndDescentArePositiveAndSumToTheLineBox() {
    val metrics = engine.measure(TextRun("Ag", style))
    assertTrue(metrics.ascent > 0.0)
    assertTrue(metrics.descent > 0.0)
    assertEquals(metrics.ascent + metrics.descent, metrics.height, 0.01)
  }

  @Test
  fun explicitNewlinesProduceMultipleLines() {
    val metrics = engine.measure(TextRun("one\ntwo\nthree", style))
    assertEquals(3, metrics.lineCount)
    assertTrue(metrics.height > metrics.lineHeight)
  }

  @Test
  fun widthConstraintWrapsText() {
    val run = TextRun("the quick brown fox jumps over the lazy dog", style)
    val unwrapped = engine.layout(run)
    val wrapped = engine.layout(run, SizeD(width = 80.0, height = Double.MAX_VALUE))

    assertEquals(1, unwrapped.metrics.lineCount)
    assertTrue("expected wrapping", wrapped.metrics.lineCount > 1)
    assertTrue(wrapped.metrics.width <= 81.0)
  }

  @Test
  fun boldIsWiderThanRegularForTheSameText() {
    val regular = engine.measure(TextRun("Measurement", style)).width
    val bold = engine.measure(TextRun("Measurement", style.copy(fontWeight = 700))).width
    assertTrue("expected bold >= regular ($bold vs $regular)", bold >= regular)
  }

  @Test
  fun italicIsMeasurableAndNonZero() {
    val italic = engine.measure(TextRun("Italic", style.copy(fontStyle = FontStyle.ITALIC)))
    assertTrue(italic.width > 0.0)
  }

  @Test
  fun letterSpacingWidensTheRun() {
    val plain = engine.measure(TextRun("spacing", style)).width
    val spaced = engine.measure(TextRun("spacing", style.copy(letterSpacing = 3.0))).width
    assertTrue("expected $spaced > $plain", spaced > plain)
  }

  @Test
  fun monospaceGlyphsAllAdvanceEqually() {
    val mono = style.copy(fontFamily = "monospace")
    val one = engine.measure(TextRun("i", mono)).width
    val other = engine.measure(TextRun("W", mono)).width
    assertEquals(one, other, 0.5)
  }

  @Test
  fun alignmentAndBaselineShiftTheLayoutBounds() {
    val left = engine.layout(TextRun("anchored", style, align = TextAlign.LEFT))
    val centered = engine.layout(TextRun("anchored", style, align = TextAlign.CENTER))
    assertEquals(0.0, left.bounds.left, 0.01)
    assertEquals(-left.metrics.width / 2.0, centered.bounds.left, 0.01)

    val top = engine.layout(TextRun("anchored", style, baseline = TextBaseline.TOP))
    assertEquals(0.0, top.bounds.top, 0.01)
  }

  @Test
  fun measurementIsRepeatable() {
    val run = TextRun("stable", style)
    assertEquals(engine.measure(run), engine.measure(run))
  }

  @Test
  fun unknownFontFamilyFallsBackInsteadOfFailing() {
    val metrics = engine.measure(TextRun("fallback", style.copy(fontFamily = "NoSuchFont-XYZ")))
    assertTrue(metrics.width > 0.0)
  }
}
