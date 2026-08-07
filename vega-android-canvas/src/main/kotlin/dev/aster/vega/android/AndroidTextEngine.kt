package dev.aster.vega.android

import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.aster.vega.scene.FontStyle
import dev.aster.vega.scene.SizeD
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.TextLayout
import dev.aster.vega.scene.TextLine
import dev.aster.vega.scene.TextMetrics
import dev.aster.vega.scene.TextRun
import dev.aster.vega.scene.TextStyle
import dev.aster.vega.scene.displayText
import dev.aster.vega.scene.textBounds
import kotlin.math.ceil

/**
 * Text measurement backed by the platform's own text stack.
 *
 * This is the same engine the renderer draws with, so a label always lands where layout put it
 * (PROJECT_BRIEF.md 9). Measurement happens during scene compilation, never inside `onDraw`.
 *
 * Not thread-safe: it owns a mutable [TextPaint]. Create one per renderer or per compile pass.
 */
public class AndroidTextEngine(
  /** Logical-to-physical scale. Text is measured in logical units and scaled at draw time. */
  private val fontScale: Float = 1f
) : TextEngine {

  private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
  private val typefaceCache = HashMap<TypefaceKey, Typeface>()

  private data class TypefaceKey(val family: String, val weight: Int, val italic: Boolean)

  override fun measure(text: TextRun, constraint: SizeD?): TextMetrics =
    layout(text, constraint).metrics

  override fun layout(text: TextRun, constraint: SizeD?): TextLayout {
    applyStyle(text.style)
    val fontMetrics = paint.fontMetrics
    val ascent = -fontMetrics.ascent.toDouble()
    val descent = fontMetrics.descent.toDouble()
    val defaultLineHeight = (fontMetrics.descent - fontMetrics.ascent).toDouble()
    val lineHeight = text.style.lineHeight ?: defaultLineHeight

    val constraintWidth = constraint?.width?.takeIf { it > 0.0 && it.isFinite() }
    // A guide's `limit` shortens what is drawn without changing what the run says, so a truncated
    // label still reports the value it came from to accessibility and to the differential harness.
    val shown = text.displayText { paint.measureText(it).toDouble() }
    val lines =
      if (constraintWidth == null && !shown.contains('\n')) {
        listOf(TextLine(shown, paint.measureText(shown).toDouble(), 0.0))
      } else {
        layoutMultiline(text.copy(text = shown), constraintWidth, lineHeight)
      }

    val width = lines.maxOfOrNull { it.width } ?: 0.0
    val height = if (lines.isEmpty()) 0.0 else (lines.size - 1) * lineHeight + ascent + descent
    val metrics =
      TextMetrics(
        width = width,
        height = height,
        ascent = ascent,
        descent = descent,
        lineCount = lines.size,
        lineHeight = lineHeight,
      )
    return TextLayout(
      run = text,
      metrics = metrics,
      lines = lines,
      bounds = textBounds(text, metrics),
    )
  }

  /**
   * Multiline layout via [StaticLayout], so Android's own line breaking, font fallback and
   * bidirectional handling apply rather than a reimplementation of them.
   */
  private fun layoutMultiline(
    run: TextRun,
    constraintWidth: Double?,
    lineHeight: Double,
  ): List<TextLine> {
    val width =
      constraintWidth?.let { ceil(it).toInt() }
        ?: run.text.lines().maxOf { ceil(paint.measureText(it).toDouble()).toInt() }
    val staticLayout =
      StaticLayout.Builder.obtain(run.text, 0, run.text.length, paint, width.coerceAtLeast(1))
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .build()

    return (0 until staticLayout.lineCount).map { index ->
      val start = staticLayout.getLineStart(index)
      val end = staticLayout.getLineEnd(index)
      TextLine(
        text = run.text.substring(start, end).trimEnd('\n'),
        width = staticLayout.getLineWidth(index).toDouble(),
        baselineY = index * lineHeight,
      )
    }
  }

  /** Configures the shared paint. Called before every measurement and before every draw. */
  internal fun applyStyle(style: TextStyle) {
    paint.reset()
    paint.isAntiAlias = true
    paint.textSize = (style.fontSize * fontScale).toFloat()
    paint.typeface = resolveTypeface(style)
    paint.letterSpacing =
      if (style.fontSize > 0.0) (style.letterSpacing / style.fontSize).toFloat() else 0f
    paint.textAlign = Paint.Align.LEFT
  }

  /** The configured paint, for the renderer to draw with. Do not retain it across style changes. */
  internal fun paintFor(style: TextStyle): TextPaint {
    applyStyle(style)
    return paint
  }

  internal fun androidAlign(align: TextAlign): Paint.Align =
    when (align) {
      TextAlign.LEFT -> Paint.Align.LEFT
      TextAlign.CENTER -> Paint.Align.CENTER
      TextAlign.RIGHT -> Paint.Align.RIGHT
    }

  private fun resolveTypeface(style: TextStyle): Typeface {
    val italic = style.fontStyle == FontStyle.ITALIC
    val key = TypefaceKey(style.fontFamily, style.fontWeight, italic)
    return typefaceCache.getOrPut(key) {
      createTypeface(style.fontFamily, style.fontWeight, italic)
    }
  }

  private fun createTypeface(family: String, weight: Int, italic: Boolean): Typeface {
    // Vega's generic families map onto Android's built-in families; anything else is passed through
    // to the platform, which falls back rather than failing.
    val base =
      when (family.lowercase()) {
        "sans-serif",
        "helvetica",
        "arial" -> Typeface.SANS_SERIF
        "serif",
        "times",
        "times new roman" -> Typeface.SERIF
        "monospace",
        "courier",
        "courier new" -> Typeface.MONOSPACE
        else -> Typeface.create(family, Typeface.NORMAL)
      }
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
      Typeface.create(base, weight.coerceIn(1, 1000), italic)
    } else {
      val legacyStyle =
        when {
          weight >= 600 && italic -> Typeface.BOLD_ITALIC
          weight >= 600 -> Typeface.BOLD
          italic -> Typeface.ITALIC
          else -> Typeface.NORMAL
        }
      Typeface.create(base, legacyStyle)
    }
  }
}
