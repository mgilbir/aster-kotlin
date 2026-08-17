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
  /**
   * The widest the text may be drawn, in the same units as the layout. Zero means no limit.
   *
   * Upstream's axes and legends both set one by default — 180 and 160 — so a label longer than that
   * is truncated with an ellipsis without anything in the specification asking for it. The
   * scenegraph keeps the **whole** string and only the drawn lines and the measured width shrink,
   * which is why [TextLayout.run] still carries what the data said.
   */
  val limit: Double = 0.0,
  val ellipsis: String = "\u2026",
  /**
   * The separator [text] is broken into lines on, when the specification names one.
   *
   * Null means the newline this engine uses to carry an array-valued `text`. Upstream splits on
   * `lineBreak` **instead of** on anything else, so a string holding both is one line here as it is
   * there. Kept on the run rather than folded into [text] because the scenegraph has to keep saying
   * what the data said — a label a screen reader reads, or the differential harness compares, is
   * the original string and not the broken one.
   */
  val lineBreak: String? = null,
  /**
   * The lines, when the specification gave them as a list rather than as one string.
   *
   * A `text` channel whose value is an **array** is a line list — upstream's `textLines` returns
   * the array itself — and that has to be carried separately from [text] rather than encoded into
   * it, because `lineBreak` stays on the item and must be *ignored*: upstream's condition is
   * `item.lineBreak && !isArray(item.text)`. Clearing `lineBreak` instead would render correctly
   * and record the wrong scene, which is exactly what a differential fixture caught.
   *
   * Null for the ordinary case, where [text] is split on [lineBreak] or on newlines.
   */
  val lines: List<String>? = null,
)

/**
 * One line as it actually gets drawn: trimmed, then shortened to fit [TextRun.limit].
 *
 * Upstream's `textValue`, and every part of it matters. The line is **trimmed** first, which
 * changes its measured width. Truncation happens only for a limit greater than zero — a negative
 * limit is not a truncation from the other end, it is no truncation at all. And the binary search
 * keeps both of upstream's **strict** comparisons: a string exactly as wide as the limit is already
 * too wide, and so is a prefix exactly filling the space the ellipsis leaves. Together they take
 * one more character off than a reading of "fits within the limit" would, and an off-by-one here is
 * visible in every truncated label.
 *
 * A right-to-left run keeps its **tail** instead, with the ellipsis in front, because the end of an
 * RTL string is where its meaning starts.
 */
public fun TextRun.displayLine(line: String, measure: (String) -> Double): String {
  val text = line.trim()
  if (limit <= 0.0 || text.isEmpty() || measure(text) < limit) return text
  val room = limit - measure(ellipsis)
  var low = 0
  var high = text.length
  if (style.direction == TextDirection.RTL) {
    while (low < high) {
      val mid = (low + high) ushr 1
      if (measure(text.substring(mid)) > room) low = mid + 1 else high = mid
    }
    return ellipsis + text.substring(low)
  }
  while (low < high) {
    val mid = 1 + ((low + high) ushr 1)
    if (mid <= text.length && measure(text.substring(0, mid)) < room) low = mid else high = mid - 1
  }
  return text.substring(0, low) + ellipsis
}

/**
 * The whole run as it gets drawn, line by line.
 *
 * Per line, not over the joined string: a limit bounds each line's own width, so truncating the
 * text with its newlines still in it would measure a two-line label as one long one and cut the
 * first line down to nothing.
 */
public fun TextRun.displayLines(measure: (String) -> Double): List<String> =
  // An explicit line list wins, and `lineBreak` is ignored when there is one — upstream's
  // `item.lineBreak && !isArray(item.text)`. The two mechanisms never combine.
  (lines ?: if (lineBreak != null) text.split(lineBreak) else text.split('\n')).map {
    displayLine(it, measure)
  }

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
/**
 * A [TextEngine] that owns the **layout** and asks a subclass only how wide the text is.
 *
 * Laying a run out — splitting it on newlines, honouring `limit` and its ellipsis, wrapping to a
 * constraint, stacking baselines, deriving bounds from the alignment — is the same arithmetic
 * whatever measures the glyphs. What differs between platforms is one question: how wide is this
 * string in this style. So that is the only thing a subclass answers.
 *
 * This exists because it was written three times. [MetricTextEngine] had it, `AndroidTextEngine`
 * had its own copy with a different wrapping rule, and a CoreText engine for iOS would have been
 * the third — at which point "the same implementation must be used for measuring and for drawing"
 * (see [TextEngine]) becomes impossible to keep true by inspection. A label sits where the layout
 * put it, so a second layout is a second answer to where labels go.
 *
 * Subclasses are expected from other languages as well: `CoreTextTextEngine` in
 * `swift/AsterVegaRender` is a Swift subclass, which is what makes an iOS chart measure text with
 * the same font that draws it.
 */
public abstract class MeasuredTextEngine : TextEngine {

  /**
   * The advance width of `line` in `style`, including any letter spacing between its characters.
   */
  public abstract fun advanceOf(line: String, style: TextStyle): Double

  /** How far the tallest glyph rises above the baseline. */
  public abstract fun ascentOf(style: TextStyle): Double

  /** How far the lowest glyph falls below it. */
  public abstract fun descentOf(style: TextStyle): Double

  /**
   * The line height to use when the style names none.
   *
   * Upstream's default is `fontSize + 2`, not a ratio — `vega-scenegraph`'s `lineHeight(item)`. The
   * two agree at 10 point and part company everywhere else, which nothing noticed until a legend
   * title arrived with two lines in it. A platform engine may prefer its font's own line height and
   * overrides this to say so.
   */
  public open fun defaultLineHeightOf(style: TextStyle): Double = style.fontSize + 2.0

  final override fun measure(text: TextRun, constraint: SizeD?): TextMetrics =
    layout(text, constraint).metrics

  final override fun layout(text: TextRun, constraint: SizeD?): TextLayout {
    val style = text.style
    val lineHeight = style.lineHeight ?: defaultLineHeightOf(style)
    val ascent = ascentOf(style)
    val descent = descentOf(style)

    val rawLines = text.displayLines { advanceOf(it, style) }
    val wrapped =
      if (constraint?.width != null && constraint.width > 0.0) {
        rawLines.flatMap { wrap(it, style, constraint.width) }
      } else {
        rawLines
      }

    val lines = wrapped.mapIndexed { index, line ->
      TextLine(text = line, width = advanceOf(line, style), baselineY = index * lineHeight)
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
   * Greedy word wrapping: a word that does not fit starts a line, and one that never fits gets one.
   */
  private fun wrap(line: String, style: TextStyle, maxWidth: Double): List<String> {
    if (line.isEmpty()) return listOf("")
    val words = line.split(' ')
    val result = mutableListOf<String>()
    var current = StringBuilder()
    for (word in words) {
      val candidate = if (current.isEmpty()) word else "$current $word"
      if (advanceOf(candidate, style) <= maxWidth || current.isEmpty()) {
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
) : MeasuredTextEngine() {

  override fun advanceOf(line: String, style: TextStyle): Double =
    if (line.isEmpty()) 0.0
    else line.length * style.fontSize * advanceRatio + (line.length - 1) * style.letterSpacing

  override fun ascentOf(style: TextStyle): Double = style.fontSize * ascentRatio

  override fun descentOf(style: TextStyle): Double = style.fontSize * descentRatio
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
 * lifetime is explicit. Eviction is least-recently-*used*: a hit is as good as a write.
 *
 * Written out rather than delegated to `LinkedHashMap`'s access-order mode, which is a JVM-only
 * facility — the three-argument constructor and `removeEldestEntry` do not exist off it, and the
 * class is final there besides. A map that keeps insertion order does exist everywhere, and moving
 * an entry to the young end by removing and re-adding it turns that into the same policy in four
 * lines. This was the only place in the core where portability was a claim rather than a fact.
 */
public class TextLayoutCache(private val engine: TextEngine, private val maxEntries: Int = 2048) :
  TextEngine {

  private data class Key(val run: TextRun, val constraint: SizeD?)

  private val cache = LinkedHashMap<Key, TextLayout>()

  public val size: Int
    get() = cache.size

  override fun measure(text: TextRun, constraint: SizeD?): TextMetrics =
    layout(text, constraint).metrics

  override fun layout(text: TextRun, constraint: SizeD?): TextLayout {
    val key = Key(text, constraint)
    val hit = cache.remove(key)
    if (hit != null) {
      // Re-inserted, so it goes back at the young end and is the last thing to be evicted.
      cache[key] = hit
      return hit
    }
    val layout = engine.layout(text, constraint)
    cache[key] = layout
    // Insertion order puts the least recently used first, which is the one to drop.
    if (cache.size > maxEntries) cache.remove(cache.keys.first())
    return layout
  }

  public fun clear() {
    cache.clear()
  }
}
