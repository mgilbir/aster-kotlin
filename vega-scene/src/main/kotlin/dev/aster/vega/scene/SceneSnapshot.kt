package dev.aster.vega.scene

import dev.aster.vega.model.DEFAULT_DECIMAL_PRECISION
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.model.canonicalNumberString

/**
 * Canonical JSON serialization of a scene, for snapshot tests and debugging.
 *
 * Guarantees (PROJECT_BRIEF.md 18.2): keys are emitted in a fixed order, node order follows paint
 * order, numbers use a fixed precision, `-0.0` is normalized, ids come from build order, and
 * nothing platform- or time-dependent appears. A change to this output must be reviewed as a
 * rendering change.
 *
 * Hand-written rather than `kotlinx.serialization` because the ordering and numeric-formatting
 * guarantees are the whole point, and a schema-driven encoder would hide them.
 */
public class SceneSnapshotSerializer(
  private val precision: Int = DEFAULT_DECIMAL_PRECISION,
  /** `false` collapses the output onto one line; goldens use the indented form. */
  private val pretty: Boolean = true,
  /** Node ids are stable but noisy in diffs; excluded by default. */
  private val includeNodeIds: Boolean = false,
) {

  public fun serialize(scene: Scene): String {
    val out = StringBuilder()
    writeObject(out, 0) {
      field("width", num(scene.width))
      field("height", num(scene.height))
      field("background", scene.background?.let { str(it.toCssHex()) } ?: "null")
      field("revision", scene.revision.toString())
      field("nodeCount", scene.nodeCount.toString())
      field("root", serializeNode(scene.root, 1))
    }
    return out.toString()
  }

  private fun serializeNode(node: SceneNode, depth: Int): String {
    val out = StringBuilder()
    writeObject(out, depth) {
      field("type", str(typeName(node)))
      if (includeNodeIds) field("id", node.id.value.toString())
      field("bounds", rect(node.bounds))
      if (!node.transform.isIdentity) field("transform", transform(node.transform))
      if (node.opacity != 1.0) field("opacity", num(node.opacity))
      if (!node.visible) field("visible", "false")

      when (node) {
        is GroupNode -> {
          node.fill?.let { field("fill", fill(it)) }
          node.stroke?.let { field("stroke", stroke(it)) }
          node.clip?.let { field("clip", rect(it)) }
          node.clipPath?.let { field("clipPath", str(pathToString(it))) }
          field(
            "children",
            writeArray(depth + 1, node.children) { child, childDepth ->
              serializeNode(child, childDepth)
            },
          )
        }
        is RectNode -> {
          field("x", num(node.x))
          field("y", num(node.y))
          field("width", num(node.width))
          field("height", num(node.height))
          if (node.cornerRadius != 0.0) field("cornerRadius", num(node.effectiveCornerRadius))
          node.fill?.let { field("fill", fill(it)) }
          node.stroke?.let { field("stroke", stroke(it)) }
        }
        is RuleNode -> {
          field("x1", num(node.x1))
          field("y1", num(node.y1))
          field("x2", num(node.x2))
          field("y2", num(node.y2))
          field("stroke", stroke(node.stroke))
        }
        is PathNode -> {
          field("path", str(pathToString(node.path)))
          node.fill?.let { field("fill", fill(it)) }
          node.stroke?.let { field("stroke", stroke(it)) }
        }
        is SymbolNode -> {
          field("x", num(node.x))
          field("y", num(node.y))
          field("size", num(node.size))
          field("shape", str(node.shape.name.lowercase().replace('_', '-')))
          if (node.angleDegrees != 0.0) field("angle", num(node.angleDegrees))
          node.fill?.let { field("fill", fill(it)) }
          node.stroke?.let { field("stroke", stroke(it)) }
        }
        is TextNode -> {
          field("x", num(node.x))
          field("y", num(node.y))
          field("text", str(node.text))
          field("align", str(node.layout.run.align.name.lowercase()))
          field("baseline", str(node.layout.run.baseline.name.lowercase().replace('_', '-')))
          field("fontFamily", str(node.layout.run.style.fontFamily))
          field("fontSize", num(node.layout.run.style.fontSize))
          field("fontWeight", node.layout.run.style.fontWeight.toString())
          field("lineCount", node.layout.metrics.lineCount.toString())
          if (node.angleDegrees != 0.0) field("angle", num(node.angleDegrees))
          node.fill?.let { field("fill", fill(it)) }
          node.stroke?.let { field("stroke", stroke(it)) }
        }
        is ImageNode -> {
          field("url", str(node.url))
          field("x", num(node.x))
          field("y", num(node.y))
          field("width", num(node.width))
          field("height", num(node.height))
          field("fit", str(node.fit.name.lowercase()))
        }
      }

      if (node.metadata != NodeMetadata.None) field("metadata", metadata(node.metadata, depth + 1))
    }
    return out.toString()
  }

  // ---- value writers -------------------------------------------------------

  private fun num(value: Double): String = canonicalNumberString(value, precision)

  private fun str(value: String): String = "\"${escapeJson(value)}\""

  private fun rect(rect: RectD): String =
    if (rect.isEmpty) "null"
    else "[${num(rect.left)}, ${num(rect.top)}, ${num(rect.right)}, ${num(rect.bottom)}]"

  private fun transform(t: Transform2D): String =
    "[${num(t.a)}, ${num(t.b)}, ${num(t.c)}, ${num(t.d)}, ${num(t.e)}, ${num(t.f)}]"

  private fun fill(fill: Fill): String =
    if (fill.opacity == 1.0) str(paintToString(fill.paint))
    else "{\"paint\": ${str(paintToString(fill.paint))}, \"opacity\": ${num(fill.opacity)}}"

  private fun stroke(stroke: Stroke): String = buildString {
    append("{\"paint\": ")
    append(str(paintToString(stroke.paint)))
    append(", \"width\": ")
    append(num(stroke.width))
    if (stroke.cap != StrokeCap.BUTT) append(", \"cap\": ${str(stroke.cap.name.lowercase())}")
    if (stroke.join != StrokeJoin.MITER) append(", \"join\": ${str(stroke.join.name.lowercase())}")
    if (stroke.dashArray.isNotEmpty()) {
      append(", \"dash\": [")
      append(stroke.dashArray.joinToString(", ") { num(it) })
      append("]")
    }
    if (stroke.opacity != 1.0) append(", \"opacity\": ${num(stroke.opacity)}")
    append("}")
  }

  private fun paintToString(paint: ScenePaint): String =
    when (paint) {
      is ScenePaint.Solid -> paint.color.toCssHex()
      is ScenePaint.LinearGradient ->
        "linear(${num(paint.x1)},${num(paint.y1)},${num(paint.x2)},${num(paint.y2)};" +
          paint.stops.joinToString(",") { "${num(it.offset)}:${it.color.toCssHex()}" } +
          ")"
      is ScenePaint.RadialGradient ->
        "radial(${num(paint.cx)},${num(paint.cy)},${num(paint.radius)};" +
          paint.stops.joinToString(",") { "${num(it.offset)}:${it.color.toCssHex()}" } +
          ")"
    }

  private fun metadata(metadata: NodeMetadata, depth: Int): String {
    val out = StringBuilder()
    writeObject(out, depth) {
      metadata.markName?.let { field("markName", str(it)) }
      metadata.role?.let { field("role", str(it)) }
      metadata.datumId?.let { field("datumId", it.toString()) }
      metadata.datumIndex?.let { field("datumIndex", it.toString()) }
      if (metadata.interactive) field("interactive", "true")
      metadata.tooltip?.let { field("tooltip", str(tooltipToString(it))) }
      metadata.accessibility?.let { descriptor ->
        field(
          "accessibility",
          "{\"label\": ${str(descriptor.label)}" +
            (descriptor.value?.let { ", \"value\": ${str(it)}" } ?: "") +
            (descriptor.role?.let { ", \"role\": ${str(it)}" } ?: "") +
            (if (descriptor.focusable) ", \"focusable\": true" else "") +
            "}",
        )
      }
    }
    return out.toString()
  }

  private fun tooltipToString(value: VegaValue): String = value.asString()

  /** SVG-style path text, also used as the canonical snapshot form for paths. */
  private fun pathToString(path: PathData): String =
    path.commands.joinToString(" ") { command ->
      when (command) {
        is PathCommand.MoveTo -> "M${num(command.x)},${num(command.y)}"
        is PathCommand.LineTo -> "L${num(command.x)},${num(command.y)}"
        is PathCommand.CubicTo ->
          "C${num(command.x1)},${num(command.y1)} ${num(command.x2)},${num(command.y2)} " +
            "${num(command.x)},${num(command.y)}"
        PathCommand.Close -> "Z"
      }
    }

  // ---- structural writers --------------------------------------------------

  private inner class ObjectWriter(private val out: StringBuilder, private val depth: Int) {
    private var count = 0

    fun field(name: String, value: String) {
      if (count > 0) out.append(',')
      if (pretty) {
        out.append('\n')
        out.append(indent(depth + 1))
      } else if (count > 0) {
        out.append(' ')
      }
      out.append('"').append(name).append("\": ").append(value)
      count++
    }

    fun finish() {
      if (pretty && count > 0) {
        out.append('\n')
        out.append(indent(depth))
      }
    }
  }

  private fun writeObject(out: StringBuilder, depth: Int, block: ObjectWriter.() -> Unit) {
    out.append('{')
    val writer = ObjectWriter(out, depth)
    writer.block()
    writer.finish()
    out.append('}')
  }

  private fun <T> writeArray(
    depth: Int,
    items: List<T>,
    render: (T, Int) -> String,
  ): String {
    if (items.isEmpty()) return "[]"
    val out = StringBuilder("[")
    items.forEachIndexed { index, item ->
      if (index > 0) out.append(',')
      if (pretty) {
        out.append('\n')
        out.append(indent(depth + 1))
      } else if (index > 0) {
        out.append(' ')
      }
      out.append(render(item, depth + 1))
    }
    if (pretty) {
      out.append('\n')
      out.append(indent(depth))
    }
    out.append(']')
    return out.toString()
  }

  private fun indent(depth: Int): String = "  ".repeat(depth)

  private fun escapeJson(value: String): String {
    val out = StringBuilder(value.length + 8)
    for (ch in value) {
      when (ch) {
        '"' -> out.append("\\\"")
        '\\' -> out.append("\\\\")
        '\n' -> out.append("\\n")
        '\r' -> out.append("\\r")
        '\t' -> out.append("\\t")
        else ->
          if (ch < ' ') out.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
          else out.append(ch)
      }
    }
    return out.toString()
  }
}

/** Convenience wrapper around the default [SceneSnapshotSerializer] configuration. */
public fun Scene.toCanonicalJson(
  precision: Int = DEFAULT_DECIMAL_PRECISION,
  pretty: Boolean = true,
  includeNodeIds: Boolean = false,
): String = SceneSnapshotSerializer(precision, pretty, includeNodeIds).serialize(this)
