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
 * proportional) rather than exact pixel counts — the documented policy for text tolerances (ADR
 * 0006, ADR 0008).
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

  /**
   * The layout is the base class's, and these are the two facts that proves.
   *
   * `AndroidTextEngine` used to implement `TextEngine` directly and carry its own copy of the
   * layout — newlines, `limit`, wrapping, baselines — which wrapped through `StaticLayout` where
   * `MetricTextEngine` and the Swift `CoreTextTextEngine` wrap greedily on spaces. Two answers to
   * where lines break is two answers to where every label sits, decided by which host drew the
   * chart. Now there is one layout and three sets of numbers.
   */
  @Test
  fun theLayoutIsTheSharedOneAndTheNumbersArePlatformNumbers() {
    // A `limit` shortens what is drawn and leaves the run's own text alone — the base class's rule,
    // which the differential harness and accessibility both depend on.
    val limited = engine.layout(TextRun("Measurement results", style, limit = 40.0))
    assertEquals("Measurement results", limited.run.text)
    assertTrue(
      "expected an ellipsis in ${limited.lines}",
      limited.lines.single().text.endsWith("…"),
    )
    assertTrue(limited.metrics.width <= 40.0)

    // And the widths are the platform's, not a ratio of the font size: `MetricTextEngine` cannot
    // tell
    // `iiii` from `mmmm` and this must.
    val narrow = engine.measure(TextRun("iiii", style)).width
    val wide = engine.measure(TextRun("mmmm", style)).width
    assertTrue("iiii=$narrow mmmm=$wide", wide > narrow * 2.0)
  }

  /**
   * The reader's text scale reaches the **measurement**, which is the only place it can do any
   * good.
   *
   * An axis reserves its label box from a measurement, so a scale applied when drawing and not when
   * measuring is what makes labels overlap at a larger text setting. The parameter existed and
   * nothing set it; `VegaChartView` now passes the device's own scale.
   */
  @Test
  fun theFontScaleWidensAndHeightensTheMeasurement() {
    val plain = AndroidTextEngine(fontScale = 1f)
    val enlarged = AndroidTextEngine(fontScale = 2f)

    val plainMetrics = plain.measure(TextRun("Measurement", style))
    val enlargedMetrics = enlarged.measure(TextRun("Measurement", style))

    assertEquals(plainMetrics.width * 2.0, enlargedMetrics.width, plainMetrics.width * 0.05)
    assertEquals(plainMetrics.ascent * 2.0, enlargedMetrics.ascent, plainMetrics.ascent * 0.05)
    assertEquals(
      plainMetrics.lineHeight * 2.0,
      enlargedMetrics.lineHeight,
      plainMetrics.lineHeight * 0.05,
    )
  }

  /**
   * A measurement cannot be perturbed by what was last drawn with the engine.
   *
   * There was one shared `TextPaint`, handed to the renderer, which sets a colour, a shader and an
   * alignment on it before drawing a label. Measuring and drawing through the same mutable object
   * made every advance depend on the last thing painted; there are two paints now, configured from
   * one place.
   */
  @Test
  fun drawingDoesNotDisturbMeasuring() {
    val before = engine.measure(TextRun("stable", style)).width

    val paint = engine.paintFor(style.copy(fontSize = 40.0, fontFamily = "monospace"))
    paint.textAlign = engine.androidAlign(TextAlign.CENTER)
    paint.color = -0x10000
    paint.textSize = 96f

    assertEquals(before, engine.measure(TextRun("stable", style)).width, 0.01)
  }

  /** A style measured twice costs one configuration; the cache must not change the answer. */
  @Test
  fun repeatedStylesMeasureIdentically() {
    val styles = List(300) { style.copy(fontSize = 8.0 + it) }
    val first = styles.map { engine.measure(TextRun("cache", it)).width }
    val second = styles.map { engine.measure(TextRun("cache", it)).width }
    assertEquals(first, second)
  }

  /**
   * A face this app ships, reaching the chart by the family name a specification asks for.
   *
   * Android resolves a family name against the *system's* families, so a bundled font could not be
   * used at all: the name finds nothing, the default is substituted, and nothing says so. Asserted
   * on a device because it is the platform's resolution that was the problem — `Typeface.create`
   * answering with a fallback is exactly what happens on a real device and cannot be seen in a unit
   * test.
   */
  @Test
  fun aHostSuppliedFaceIsWhatMeasuresAndDraws() {
    val monospace = android.graphics.Typeface.MONOSPACE
    val supplied =
      AndroidTextEngine(
        typefaceResolver = { family -> if (family == "Design System") monospace else null }
      )
    val plain = AndroidTextEngine()
    val style = TextStyle(fontFamily = "Design System", fontSize = 14.0)

    // Measured with the resolver's face: a monospaced one is not the proportional default, so the
    // advances differ. This is asserted on the **widths** rather than on the identity of the
    // paint's
    // typeface, because the engine applies weight and slant through `Typeface.create`, which
    // returns a
    // new object even at a regular weight — identity would fail while everything worked.
    val proportional = plain.measure(TextRun("Illiterate", style)).width
    val fixed = supplied.measure(TextRun("Illiterate", style)).width
    assertTrue(
      "a monospaced face measured the same as a proportional one: $fixed vs $proportional",
      kotlin.math.abs(fixed - proportional) > 0.5,
    )

    // A name the resolver declines is still the platform's business, exactly as before.
    assertEquals(
      plain.measure(TextRun("Illiterate", style.copy(fontFamily = "serif"))).width,
      supplied.measure(TextRun("Illiterate", style.copy(fontFamily = "serif"))).width,
      0.01,
    )
  }

  @Test
  fun aHostSuppliedFaceKeepsItsWeightAndSlant() {
    // A **proportional** supplied face, deliberately: a monospaced bold has the same advances as
    // its
    // regular by definition, so it could not show that the weight reached the measurement.
    val serif = android.graphics.Typeface.SERIF
    val engine = AndroidTextEngine(typefaceResolver = { serif })
    val style = TextStyle(fontFamily = "Design System", fontSize = 14.0)

    // Bold is applied to the *supplied* face rather than by asking the platform for "some bold
    // font",
    // which is how a label ends up in a face nothing in the chart mentions.
    val regular = engine.paintFor(style).typeface
    val bold = engine.paintFor(style.copy(fontWeight = 700)).typeface
    assertTrue("a bold weight produced the same typeface object", regular !== bold || bold.isBold)
    assertTrue("the bold face is not bold", bold.isBold)
    assertTrue(
      "bold is not wider",
      engine.measure(TextRun("Illiterate", style.copy(fontWeight = 700))).width >
        engine.measure(TextRun("Illiterate", style)).width,
    )
  }
}
