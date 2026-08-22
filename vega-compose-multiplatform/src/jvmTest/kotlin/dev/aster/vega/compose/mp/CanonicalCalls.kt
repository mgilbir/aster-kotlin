package dev.aster.vega.compose.mp

import dev.aster.vega.scene.RasterImage
import kotlin.math.abs
import kotlin.math.floor

/**
 * The sequence of draw calls a scene produces, written so that **two implementations can be
 * compared**.
 *
 * There are two walks over a scene — this module's `SceneWalk` and the Swift package's — and each
 * claims to emit "the same calls in the same order". Nothing checked that. It cost a real defect:
 * the Swift walk had no zero-opacity guard, so on `label-overlap.vg.json` it drew 43 text runs
 * where this one drew 19, and the two were only compared by a person reading two files.
 *
 * `RecordingTarget` cannot do the comparing. There is one on each side and their formats drifted
 * the way anything with two copies does — `->` against ` -> `, `@` against ` a`, a path summarised
 * two different ways — and aligning them would churn every assertion written against either. So
 * this is a *third* recorder, on each side, whose only job is to be byte-identical:
 * `CanonicalCalls` here and `CanonicalCalls.swift` there, one golden file between them.
 *
 * **The format, which is a contract between two languages.** Every field is written, always, in a
 * fixed order, with no shorthand for a default — a recorder that omits what it thinks is
 * uninteresting is a recorder that cannot prove agreement about it. Numbers are three decimals with
 * ties rounded away from zero, spelled out in both languages rather than left to a platform's `%f`:
 * Java's rounds ties up and C's rounds them to even, which is a difference that would show up as a
 * parity failure once in a thousand coordinates and be mystifying.
 *
 * ```
 * group clip=-|(x,y,w,h)
 *   rect (x,y,w,h) corners=[tl,tr,br,bl] fill=<brush> stroke=<stroke>
 *   line (x,y)-(x,y) stroke=<stroke>
 *   path <commands> fill=<brush> stroke=<stroke>
 *   text "…" origin=(x,y) anchor=(x,y) ascent=a font=… size=… weight=… italic=0 angle=0 …
 *   image url="…" raster=-|WxH (x,y,w,h) fit=… smooth=… opacity=…
 * end
 * ```
 *
 * A path is written **command by command**, control points included. The two `RecordingTarget`s
 * both summarise one — a count and a tally here, the first and last there — and a summary is
 * exactly what hides a curve drawn through the wrong control points, which is the kind of thing
 * these two walks could disagree about.
 */
internal class CanonicalCalls : SceneDrawTarget {

  private val lines = mutableListOf<String>()
  private var depth = 0

  val text: String
    get() = lines.joinToString("\n")

  override fun beginGroup(clip: DrawRect?) {
    note("group clip=" + (clip?.let { rect(it) } ?: "-"))
    depth += 1
  }

  override fun endGroup() {
    depth = maxOf(0, depth - 1)
    note("end")
  }

  override fun rect(
    rect: DrawRect,
    corners: DrawCorners,
    fill: DrawBrush?,
    stroke: DrawStroke?,
  ) {
    note(
      "rect ${rect(rect)} corners=[${num(corners.topLeft)},${num(corners.topRight)}," +
        "${num(corners.bottomRight)},${num(corners.bottomLeft)}]" +
        " fill=${brush(fill)} stroke=${stroke(stroke)}"
    )
  }

  override fun line(from: DrawPoint, to: DrawPoint, stroke: DrawStroke?) {
    note("line ${point(from)}-${point(to)} stroke=${stroke(stroke)}")
  }

  override fun path(commands: List<DrawPathCommand>, fill: DrawBrush?, stroke: DrawStroke?) {
    val written =
      commands.joinToString("") {
        when (it) {
          is DrawPathCommand.MoveTo -> "M${point(it.to)}"
          is DrawPathCommand.LineTo -> "L${point(it.to)}"
          is DrawPathCommand.CubicTo -> "C${point(it.control1)}${point(it.control2)}${point(it.to)}"
          DrawPathCommand.Close -> "Z"
        }
      }
    note("path $written fill=${brush(fill)} stroke=${stroke(stroke)}")
  }

  override fun text(run: DrawTextRun, fill: DrawBrush?, stroke: DrawStroke?) {
    note(
      "text ${quoted(run.text)} origin=${point(run.origin)} anchor=${point(run.anchor)}" +
        " ascent=${num(run.ascent)} font=${quoted(run.fontFamily)} size=${num(run.fontSize)}" +
        " weight=${run.fontWeight} italic=${flag(run.italic)}" +
        " angle=${num(run.angleDegrees)} spacing=${num(run.letterSpacing)}" +
        " fill=${brush(fill)} stroke=${stroke(stroke)}"
    )
  }

  override fun image(
    url: String,
    raster: RasterImage?,
    rect: DrawRect,
    fit: DrawImageFit,
    smooth: Boolean,
    opacity: Double,
  ) {
    // A raster's pixels are neither readable nor stable as text, so it is written by its extent.
    // Its
    // *presence* is what the two walks could disagree about.
    note(
      "image url=${quoted(url)} raster=" +
        (raster?.let { "${it.width}x${it.height}" } ?: "-") +
        " ${rect(rect)} fit=${fit.name.lowercase()} smooth=${flag(smooth)}" +
        " opacity=${num(opacity)}"
    )
  }

  private fun note(line: String) {
    lines += "  ".repeat(depth) + line
  }

  private fun flag(value: Boolean) = if (value) "1" else "0"

  private fun point(point: DrawPoint) = "(${num(point.x)},${num(point.y)})"

  private fun rect(rect: DrawRect) =
    "(${num(rect.x)},${num(rect.y)},${num(rect.width)},${num(rect.height)})"

  private fun brush(brush: DrawBrush?): String =
    when (brush) {
      null -> "-"
      is DrawBrush.Solid -> paint(brush.paint)
      is DrawBrush.Linear -> "linear${point(brush.from)}-${point(brush.to)}[${stops(brush.stops)}]"
      is DrawBrush.Radial ->
        "radial${point(brush.center)}r=${num(brush.radius)}[${stops(brush.stops)}]"
    }

  private fun stops(stops: List<DrawStop>) =
    stops.joinToString(",") { "${num(it.offset)}=${paint(it.paint)}" }

  private fun stroke(stroke: DrawStroke?): String =
    if (stroke == null) "-"
    else
      "${brush(stroke.brush)} w=${num(stroke.width)} cap=${stroke.cap.name.lowercase()}" +
        " join=${stroke.join.name.lowercase()} miter=${num(stroke.miterLimit)}" +
        " dash=[${stroke.dash.joinToString(",") { num(it) }}]" +
        " dashOffset=${num(stroke.dashOffset)}"

  private fun paint(paint: DrawPaint): String =
    "#" +
      listOf(paint.red, paint.green, paint.blue, paint.alpha).joinToString("") { channel ->
        val byte = halfAwayFromZero(channel.coerceIn(0.0, 1.0) * 255.0).toInt()
        byte.toString(16).padStart(2, '0')
      }

  /** JSON-ish quoting, so a label containing a quote or a newline cannot break a line. */
  private fun quoted(value: String): String = buildString {
    append('"')
    for (character in value) {
      when (character) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> append(character)
      }
    }
    append('"')
  }

  /**
   * Three decimals, ties away from zero, `-0` written as `0`.
   *
   * Spelled out rather than handed to a format string: Java's `%.3f` rounds a tie up and C's rounds
   * it to even, so `String.format` here and `String(format:)` there would disagree about a
   * coordinate ending in exactly `…5` — a parity failure once in a thousand numbers, with no way to
   * read it.
   */
  private fun num(value: Double): String {
    if (value.isNaN()) return "nan"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
    val thousandths = halfAwayFromZero(value * 1000.0)
    val negative = thousandths < 0
    val magnitude = abs(thousandths)
    val whole = (magnitude / 1000).toLong()
    val fraction = (magnitude % 1000).toLong()
    val sign = if (negative && (whole != 0L || fraction != 0L)) "-" else ""
    return "$sign$whole.${fraction.toString().padStart(3, '0')}"
  }

  private fun halfAwayFromZero(value: Double): Double =
    if (value >= 0.0) floor(value + 0.5) else -floor(-value + 0.5)
}
