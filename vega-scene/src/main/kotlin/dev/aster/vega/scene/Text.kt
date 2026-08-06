package dev.aster.vega.scene

public enum class TextAlign {
  LEFT,
  CENTER,
  RIGHT,
}

/** Vertical anchor, using Vega's `baseline` vocabulary. */
public enum class TextBaseline {
  ALPHABETIC,
  TOP,
  MIDDLE,
  BOTTOM,
  LINE_TOP,
  LINE_BOTTOM,
}

public enum class FontStyle {
  NORMAL,
  ITALIC,
}

public enum class TextDirection {
  LTR,
  RTL,
}

/**
 * Everything that affects text measurement. Used verbatim as the text-layout cache key, so adding a
 * field here automatically widens the cache key (PROJECT_BRIEF.md 9).
 */
public data class TextStyle(
  val fontFamily: String = "sans-serif",
  val fontSize: Double = 11.0,
  val fontWeight: Int = 400,
  val fontStyle: FontStyle = FontStyle.NORMAL,
  val letterSpacing: Double = 0.0,
  val lineHeight: Double? = null,
  val locale: String = "und",
  val direction: TextDirection = TextDirection.LTR,
) {
  init {
    require(fontSize >= 0.0 && fontSize.isFinite()) { "fontSize must be finite and >= 0" }
    require(fontWeight in 1..1000) { "fontWeight must be in 1..1000, was $fontWeight" }
  }
}

/** A string plus the style it is measured and drawn with. */
public data class TextRun(
  val text: String,
  val style: TextStyle = TextStyle(),
  val align: TextAlign = TextAlign.LEFT,
  val baseline: TextBaseline = TextBaseline.ALPHABETIC,
)

public data class TextMetrics(
  val width: Double,
  val height: Double,
  /** Distance from the first line's baseline up to the top of the layout box. */
  val ascent: Double,
  /** Distance from the last line's baseline down to the bottom of the layout box. */
  val descent: Double,
  val lineCount: Int,
  val lineHeight: Double,
)

public data class TextLine(val text: String, val width: Double, val baselineY: Double)

/**
 * A measured, positioned text block. [bounds] is relative to the text's anchor point, so a renderer
 * only has to translate it.
 */
public data class TextLayout(
  val run: TextRun,
  val metrics: TextMetrics,
  val lines: List<TextLine>,
  val bounds: RectD,
)

/**
 * Text measurement abstraction. Layout is part of chart layout, not just drawing, so the runtime
 * needs it before a scene exists (PROJECT_BRIEF.md 9).
 *
 * The same implementation must be used for measuring and for drawing; mixing two engines produces
 * labels that do not sit where the layout expected.
 */
public interface TextEngine {
  public fun measure(text: TextRun, constraint: SizeD? = null): TextMetrics

  public fun layout(text: TextRun, constraint: SizeD? = null): TextLayout
}

/**
 * Deterministic, platform-independent text engine used by JVM tests and by SVG export when no
 * platform engine is supplied.
 *
 * Advance widths are a fixed fraction of the font size, so measurements are stable across machines
 * but do not match any real font. Anything comparing against on-device metrics must use the
 * documented wider text tolerances (PROJECT_BRIEF.md 18.4).
 */
public class MetricTextEngine(
  private val advanceRatio: Double = 0.6,
  private val ascentRatio: Double = 0.8,
  private val descentRatio: Double = 0.2,
) : TextEngine {

  override fun measure(text: TextRun, constraint: SizeD?): TextMetrics =
    layout(text, constraint).metrics

  override fun layout(text: TextRun, constraint: SizeD?): TextLayout {
    val style = text.style
    val lineHeight = style.lineHeight ?: (style.fontSize * 1.2)
    val ascent = style.fontSize * ascentRatio
    val descent = style.fontSize * descentRatio

    val rawLines = text.text.split('\n')
    val wrapped =
      if (constraint?.width != null && constraint.width > 0.0) {
        rawLines.flatMap { wrap(it, style, constraint.width) }
      } else {
        rawLines
      }

    val lines = wrapped.mapIndexed { index, line ->
      TextLine(text = line, width = advance(line, style), baselineY = index * lineHeight)
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

  private fun advance(line: String, style: TextStyle): Double =
    if (line.isEmpty()) 0.0
    else line.length * style.fontSize * advanceRatio + (line.length - 1) * style.letterSpacing

  private fun wrap(line: String, style: TextStyle, maxWidth: Double): List<String> {
    if (line.isEmpty()) return listOf("")
    val words = line.split(' ')
    val result = mutableListOf<String>()
    var current = StringBuilder()
    for (word in words) {
      val candidate = if (current.isEmpty()) word else "$current $word"
      if (advance(candidate, style) <= maxWidth || current.isEmpty()) {
        current = StringBuilder(candidate)
      } else {
        result.add(current.toString())
        current = StringBuilder(word)
      }
    }
    result.add(current.toString())
    return result
  }
}

/**
 * Bounds of a measured text block relative to its anchor, honouring [TextRun.align] and
 * [TextRun.baseline]. Shared by every [TextEngine] so alignment behaviour cannot drift between
 * platforms.
 */
public fun textBounds(run: TextRun, metrics: TextMetrics): RectD {
  val left =
    when (run.align) {
      TextAlign.LEFT -> 0.0
      TextAlign.CENTER -> -metrics.width / 2.0
      TextAlign.RIGHT -> -metrics.width
    }
  val top =
    when (run.baseline) {
      TextBaseline.ALPHABETIC -> -metrics.ascent
      TextBaseline.TOP -> 0.0
      TextBaseline.LINE_TOP -> 0.0
      TextBaseline.MIDDLE -> -metrics.height / 2.0
      TextBaseline.BOTTOM -> -metrics.height
      TextBaseline.LINE_BOTTOM -> -metrics.height
    }
  return RectD(left, top, left + metrics.width, top + metrics.height).normalized()
}

/**
 * Bounded text-layout cache.
 *
 * Owned by whoever creates it (a renderer or a runtime instance) rather than being global, so its
 * lifetime is explicit. Eviction is insertion-order LRU via [LinkedHashMap].
 */
public class TextLayoutCache(private val engine: TextEngine, private val maxEntries: Int = 2048) :
  TextEngine {

  private data class Key(val run: TextRun, val constraint: SizeD?)

  private val cache =
    object : LinkedHashMap<Key, TextLayout>(128, 0.75f, true) {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, TextLayout>?): Boolean =
        size > maxEntries
    }

  public val size: Int
    get() = cache.size

  override fun measure(text: TextRun, constraint: SizeD?): TextMetrics =
    layout(text, constraint).metrics

  override fun layout(text: TextRun, constraint: SizeD?): TextLayout =
    cache.getOrPut(Key(text, constraint)) { engine.layout(text, constraint) }

  public fun clear() {
    cache.clear()
  }
}
