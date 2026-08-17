package dev.aster.vega.compose.mp

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * A [SceneDrawTarget] that writes down what it was asked to draw instead of drawing it.
 *
 * This is how the renderer is tested. What a renderer can get wrong is which primitives it emits,
 * with which geometry, in which order — and that is exactly what a recording holds, so the tests
 * need no screen, no Compose runtime and no golden images. They run in `commonTest`, which means
 * they run on the JVM, on Android and on both iOS targets: the walk is verified on every platform
 * it claims.
 *
 * The numbers are rounded to three decimals, because a coordinate that came through a transform is
 * a `Double` and the last bits of one are not something a test should assert.
 */
public class RecordingTarget : SceneDrawTarget {

  private val lines = mutableListOf<String>()
  private var depth = 0

  /** The calls so far, indented by group nesting. */
  public val calls: List<String>
    get() = lines.toList()

  override fun toString(): String = lines.joinToString("\n")

  override fun beginGroup(clip: DrawRect?) {
    note("group" + (clip?.let { " clip ${show(it)}" } ?: ""))
    depth += 1
  }

  override fun endGroup() {
    depth = maxOf(0, depth - 1)
  }

  override fun rect(
    rect: DrawRect,
    corners: DrawCorners,
    fill: DrawBrush?,
    stroke: DrawStroke?,
  ) {
    note(
      "rect ${show(rect)}" +
        (if (corners.isSquare) "" else " corners ${show(corners)}") +
        paints(fill, stroke)
    )
  }

  override fun line(from: DrawPoint, to: DrawPoint, stroke: DrawStroke?) {
    note("line ${show(from)}->${show(to)}${paints(null, stroke)}")
  }

  override fun path(commands: List<DrawPathCommand>, fill: DrawBrush?, stroke: DrawStroke?) {
    // A path's every coordinate would swamp the recording, so its shape is summarised: how many
    // commands, of which kinds, starting where. That is enough to tell an arc from a rectangle and
    // a
    // circle from its bounding box — a renderer that mangled the middle of a path would still pass,
    // which is what the platform renderers' own tests are for.
    val shape =
      if (commands.isEmpty()) {
        "empty"
      } else {
        val tally =
          commands
            .groupingBy { kind(it) }
            .eachCount()
            .entries
            .sortedBy { it.key }
            .joinToString(", ") { "${it.value} ${it.key}" }
        "${commands.size} commands ($tally) from ${show(start(commands))}"
      }
    note("path $shape${paints(fill, stroke)}")
  }

  override fun text(run: DrawTextRun, fill: DrawBrush?, stroke: DrawStroke?) {
    note(
      "text \"${run.text}\" at ${show(run.origin)} ${run.fontFamily} ${show(run.fontSize)}" +
        (if (run.fontWeight != 400) " w${run.fontWeight}" else "") +
        (if (run.italic) " italic" else "") +
        (if (run.angleDegrees != 0.0) " angle ${show(run.angleDegrees)} about ${show(run.anchor)}"
        else "") +
        paints(fill, stroke)
    )
  }

  override fun image(url: String, rect: DrawRect, opacity: Double) {
    note("image $url ${show(rect)}${if (opacity != 1.0) " opacity ${show(opacity)}" else ""}")
  }

  private fun note(line: String) {
    lines += "  ".repeat(depth) + line
  }

  /** Where a path begins, which for every path this engine emits is its first `move`. */
  private fun start(commands: List<DrawPathCommand>): DrawPoint =
    when (val first = commands.first()) {
      is DrawPathCommand.MoveTo -> first.to
      is DrawPathCommand.LineTo -> first.to
      is DrawPathCommand.CubicTo -> first.to
      DrawPathCommand.Close -> DrawPoint(0.0, 0.0)
    }

  private fun kind(command: DrawPathCommand): String =
    when (command) {
      is DrawPathCommand.MoveTo -> "move"
      is DrawPathCommand.LineTo -> "line"
      is DrawPathCommand.CubicTo -> "cubic"
      DrawPathCommand.Close -> "close"
    }

  private fun paints(fill: DrawBrush?, stroke: DrawStroke?): String =
    (fill?.let { " fill ${show(it)}" } ?: "") +
      (stroke?.let { " stroke ${show(it.brush)} w${show(it.width)}" } ?: "")

  private fun show(brush: DrawBrush): String =
    when (brush) {
      is DrawBrush.Solid -> show(brush.paint)
      is DrawBrush.Linear ->
        "linear ${show(brush.from)}->${show(brush.to)} ${brush.stops.size} stops"
      is DrawBrush.Radial ->
        "radial ${show(brush.center)} r${show(brush.radius)} ${brush.stops.size} stops"
    }

  private fun show(paint: DrawPaint): String {
    val hex =
      listOf(paint.red, paint.green, paint.blue).joinToString("") { channel ->
        val byte = (channel.coerceIn(0.0, 1.0) * 255.0).roundToLong().toInt()
        byte.toString(16).padStart(2, '0')
      }
    return "#$hex" + if (paint.alpha < 1.0) "@${show(paint.alpha)}" else ""
  }

  private fun show(point: DrawPoint): String = "(${show(point.x)},${show(point.y)})"

  private fun show(rect: DrawRect): String =
    "(${show(rect.x)},${show(rect.y)} ${show(rect.width)}x${show(rect.height)})"

  private fun show(corners: DrawCorners): String =
    "[${show(corners.topLeft)},${show(corners.topRight)}," +
      "${show(corners.bottomRight)},${show(corners.bottomLeft)}]"

  private fun show(value: Double): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "inf" else "-inf"
    val rounded = (value * 1000.0).roundToLong() / 1000.0
    // An integral value prints without its zero, so a recording of a chart on whole pixels reads
    // like the numbers in the specification that produced it.
    return if (abs(rounded - rounded.toLong()) < 1e-9) rounded.toLong().toString()
    else rounded.toString()
  }
}
