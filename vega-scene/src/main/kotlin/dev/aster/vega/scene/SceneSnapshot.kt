package dev.aster.vega.scene

import dev.aster.vega.model.DEFAULT_DECIMAL_PRECISION
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.model.canonicalNumberString

/**
 * Canonical JSON serialization of a scene, for snapshot tests and debugging.
 *
 * Guarantees (ADR 0008): keys are emitted in a fixed order, node order follows paint order, numbers
 * use a fixed precision, `-0.0` is normalized, ids come from build order, and nothing platform- or
 * time-dependent appears. A change to this output must be reviewed as a rendering change.
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
      // `blendMode` is a drawing instruction with fifteen values and the snapshot could not see any
      // of them, so a mark that stopped compositing looked identical here. ADR-0008 calls this the
      // level-2 regression check; a property it does not write is a property it cannot check.
      val blend = blendModeOf(node)
      if (blend != SceneBlendMode.NORMAL) {
        field("blendMode", str(blend.name.lowercase().replace('_', '-')))
      }

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
          // `corners`, not `effectiveCornerRadius`. The two are different clamps of the same
          // input — `effectiveCornerRadius` takes the absolute width and height and every renderer
          // draws through `Corners.of`, which does not — so the snapshot was recording a number
          // nothing draws, and the four **per-corner** overrides were invisible to it entirely.
          val corners = node.corners
          if (!corners.isSquare) {
            field(
              "corners",
              "[${num(corners.topLeft)}, ${num(corners.topRight)}, " +
                "${num(corners.bottomRight)}, ${num(corners.bottomLeft)}]",
            )
          }
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
          // The **outline**, when the shape is a specification's own SVG path. `shape` alone reads
          // `custom` for every one of them, so a wrong path string was a snapshot that did not
          // move.
          node.customPath?.let { field("customPath", str(pathToString(it))) }
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
          // `align` and `baseline` decide where the image lands, and `smooth` decides what it looks
          // like when scaled. None of the three was written, so all three were unwatched.
          field("align", str(node.align.name.lowercase()))
          field("baseline", str(node.baseline.name.lowercase()))
          if (!node.smooth) field("smooth", "false")
        }
      }

      if (node.metadata != NodeMetadata.None) field("metadata", metadata(node.metadata, depth + 1))
    }
    return out.toString()
  }

  /**
   * A node's blend mode, which lives on each concrete type rather than on the interface.
   *
   * Written out rather than reached through a shared property because adding one to `SceneNode`
   * would make every implementor restate it; here the exhaustive `when` is the reminder that a new
   * node type has to answer.
   */
  private fun blendModeOf(node: SceneNode): SceneBlendMode =
    when (node) {
      is GroupNode -> node.blendMode
      is RectNode -> node.blendMode
      is RuleNode -> node.blendMode
      is PathNode -> node.blendMode
      is SymbolNode -> node.blendMode
      is TextNode -> node.blendMode
      is ImageNode -> node.blendMode
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
      // Where the dash pattern starts. A gridline that begins on a gap rather than a dash is a
      // different picture and was the same snapshot.
      if (stroke.dashOffset != 0.0) append(", \"dashOffset\": ${num(stroke.dashOffset)}")
    }
    // How far a miter joint may run past its vertex, which is what decides whether a sharp corner
    // is pointed or cut off — and which this engine deliberately sets to upstream's value rather
    // than the platform's, per `Stroke.miterLimit`.
    if (stroke.miterLimit != Stroke.DEFAULT_MITER_LIMIT) {
      append(", \"miterLimit\": ${num(stroke.miterLimit)}")
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
      // A link and a paint-order override are both things a specification asks for and neither was
      // written, so a mark that lost its `href` or its `zindex` left the snapshot unchanged.
      metadata.href?.let { field("href", str(it)) }
      if (metadata.zindex != 0) field("zindex", metadata.zindex.toString())
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
