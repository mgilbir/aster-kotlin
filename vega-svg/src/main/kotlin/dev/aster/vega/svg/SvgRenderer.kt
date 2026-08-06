package dev.aster.vega.svg

import dev.aster.vega.model.DEFAULT_DECIMAL_PRECISION
import dev.aster.vega.model.canonicalNumberString
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.ImageFit
import dev.aster.vega.scene.ImageNode
import dev.aster.vega.scene.PathCommand
import dev.aster.vega.scene.PathData
import dev.aster.vega.scene.PathNode
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneBlendMode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.Stroke
import dev.aster.vega.scene.StrokeCap
import dev.aster.vega.scene.StrokeJoin
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextBaseline
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.Transform2D

/** How images are represented in exported SVG. */
public enum class SvgImagePolicy {
  /** Emit the original URL in `xlink:href`. Smallest output; requires the viewer to fetch it. */
  REFERENCE,
  /** Refuse to emit unresolvable images and report them instead of writing a broken reference. */
  REQUIRE_RESOLVED,
}

public data class SvgOptions(
  val precision: Int = DEFAULT_DECIMAL_PRECISION,
  val pretty: Boolean = true,
  /**
   * Prefix for every generated `id`; keeps ids stable and collision-free across embedded charts.
   */
  val idPrefix: String = "v",
  val imagePolicy: SvgImagePolicy = SvgImagePolicy.REFERENCE,
  /** Emits `<desc>`/`<title>` from node accessibility metadata. */
  val includeMetadata: Boolean = true,
  /** Emits `aria-label` and `role` so exported SVG stays navigable. */
  val includeAccessibility: Boolean = true,
)

/** A mark that could not be represented faithfully. Never dropped silently. */
public data class SvgExportWarning(val code: String, val message: String, val nodeType: String)

public data class SvgDocument(val svg: String, val warnings: List<SvgExportWarning>)

/**
 * Serializes an immutable [Scene] to SVG.
 *
 * SVG is an output format, not the runtime model (PROJECT_BRIEF.md 4.1): this class only reads the
 * scene graph. Output is canonical — attributes appear in a fixed order, numbers use
 * [SvgOptions.precision], and generated ids are sequential from document order — so golden diffs
 * reflect real rendering changes rather than serialization noise.
 */
public class SvgRenderer(private val options: SvgOptions = SvgOptions()) {

  private class Defs {
    val entries = mutableListOf<String>()
    private val byKey = mutableMapOf<String, String>()
    private var next = 0

    fun idFor(key: String, prefix: String, render: (String) -> String): String =
      byKey.getOrPut(key) {
        val id = "$prefix${next++}"
        entries.add(render(id))
        id
      }
  }

  public fun render(scene: Scene): SvgDocument {
    val defs = Defs()
    val warnings = mutableListOf<SvgExportWarning>()
    val body = StringBuilder()

    renderNode(scene.root, body, defs, warnings, depth = 1)

    val out = StringBuilder()
    out.append("<svg xmlns=\"http://www.w3.org/2000/svg\"")
    out.append(" xmlns:xlink=\"http://www.w3.org/1999/xlink\"")
    out.append(" version=\"1.1\"")
    out.append(" width=\"").append(num(scene.width)).append('"')
    out.append(" height=\"").append(num(scene.height)).append('"')
    out.append(" viewBox=\"0 0 ").append(num(scene.width)).append(' ').append(num(scene.height))
    out.append("\">")

    if (defs.entries.isNotEmpty()) {
      newline(out, 1)
      out.append("<defs>")
      for (entry in defs.entries) {
        newline(out, 2)
        out.append(entry)
      }
      newline(out, 1)
      out.append("</defs>")
    }

    scene.background?.let { background ->
      if (!background.isTransparent) {
        newline(out, 1)
        out.append("<rect x=\"0\" y=\"0\" width=\"").append(num(scene.width))
        out.append("\" height=\"").append(num(scene.height))
        out.append("\" fill=\"").append(background.toCssHex()).append("\"/>")
      }
    }

    out.append(body)
    newline(out, 0)
    out.append("</svg>")
    if (options.pretty) out.append('\n')

    return SvgDocument(out.toString(), warnings)
  }

  // ---- nodes ---------------------------------------------------------------

  private fun renderNode(
    node: SceneNode,
    out: StringBuilder,
    defs: Defs,
    warnings: MutableList<SvgExportWarning>,
    depth: Int,
  ) {
    if (!node.visible || node.opacity <= 0.0) return

    when (node) {
      is GroupNode -> renderGroup(node, out, defs, warnings, depth)
      is RectNode -> renderRect(node, out, defs, depth)
      is RuleNode -> renderRule(node, out, defs, depth)
      is PathNode -> renderPath(node, out, defs, depth)
      is SymbolNode -> renderSymbol(node, out, defs, depth)
      is TextNode -> renderText(node, out, defs, depth)
      is ImageNode -> renderImage(node, out, warnings, depth)
    }
  }

  private fun renderGroup(
    node: GroupNode,
    out: StringBuilder,
    defs: Defs,
    warnings: MutableList<SvgExportWarning>,
    depth: Int,
  ) {
    val clipPath = node.clipPath
    val clipRect = node.clip
    val clipId =
      when {
        clipPath != null -> {
          val path = pathToString(clipPath)
          defs.idFor("clipPath:$path", "${options.idPrefix}c") { id ->
            "<clipPath id=\"$id\"><path d=\"$path\"/></clipPath>"
          }
        }
        clipRect != null -> {
          val key =
            "clipRect:${num(clipRect.left)},${num(clipRect.top)}," +
              "${num(clipRect.width)},${num(clipRect.height)}"
          defs.idFor(key, "${options.idPrefix}c") { id ->
            "<clipPath id=\"$id\"><rect x=\"${num(clipRect.left)}\" y=\"${num(clipRect.top)}\" " +
              "width=\"${num(clipRect.width)}\" height=\"${num(clipRect.height)}\"/></clipPath>"
          }
        }
        else -> null
      }

    newline(out, depth)
    out.append("<g")
    appendTransform(out, node.transform)
    appendOpacity(out, node.opacity)
    clipId?.let { out.append(" clip-path=\"url(#").append(it).append(")\"") }
    appendBlendMode(out, node.blendMode)
    appendAccessibility(out, node)
    out.append('>')

    appendDescription(out, node, depth + 1)

    // A group with its own paint draws its clip rectangle as a backing rect, matching Vega's
    // group-mark behaviour.
    if (clipRect != null && (node.fill != null || node.stroke != null)) {
      newline(out, depth + 1)
      out.append("<rect x=\"").append(num(clipRect.left))
      out.append("\" y=\"").append(num(clipRect.top))
      out.append("\" width=\"").append(num(clipRect.width))
      out.append("\" height=\"").append(num(clipRect.height))
      out.append('"')
      appendFill(out, node.fill, defs, node.bounds)
      appendStroke(out, node.stroke, defs, node.bounds)
      out.append("/>")
    }

    for (child in node.children) renderNode(child, out, defs, warnings, depth + 1)

    newline(out, depth)
    out.append("</g>")
  }

  private fun renderRect(node: RectNode, out: StringBuilder, defs: Defs, depth: Int) {
    val r = node.rect
    newline(out, depth)
    out.append("<rect x=\"").append(num(r.left)).append('"')
    out.append(" y=\"").append(num(r.top)).append('"')
    out.append(" width=\"").append(num(r.width)).append('"')
    out.append(" height=\"").append(num(r.height)).append('"')
    if (node.effectiveCornerRadius > 0.0) {
      out.append(" rx=\"").append(num(node.effectiveCornerRadius)).append('"')
      out.append(" ry=\"").append(num(node.effectiveCornerRadius)).append('"')
    }
    appendFill(out, node.fill, defs, node.bounds)
    appendStroke(out, node.stroke, defs, node.bounds)
    appendTransform(out, node.transform)
    appendOpacity(out, node.opacity)
    appendBlendMode(out, node.blendMode)
    appendAccessibility(out, node)
    out.append("/>")
  }

  private fun renderRule(node: RuleNode, out: StringBuilder, defs: Defs, depth: Int) {
    newline(out, depth)
    out.append("<line x1=\"").append(num(node.x1)).append('"')
    out.append(" y1=\"").append(num(node.y1)).append('"')
    out.append(" x2=\"").append(num(node.x2)).append('"')
    out.append(" y2=\"").append(num(node.y2)).append('"')
    appendStroke(out, node.stroke, defs, node.bounds)
    appendTransform(out, node.transform)
    appendOpacity(out, node.opacity)
    appendBlendMode(out, node.blendMode)
    appendAccessibility(out, node)
    out.append("/>")
  }

  private fun renderPath(node: PathNode, out: StringBuilder, defs: Defs, depth: Int) {
    newline(out, depth)
    out.append("<path d=\"").append(pathToString(node.path)).append('"')
    appendFill(out, node.fill, defs, node.bounds)
    appendStroke(out, node.stroke, defs, node.bounds)
    appendTransform(out, node.transform)
    appendOpacity(out, node.opacity)
    appendBlendMode(out, node.blendMode)
    appendAccessibility(out, node)
    out.append("/>")
  }

  private fun renderSymbol(node: SymbolNode, out: StringBuilder, defs: Defs, depth: Int) {
    newline(out, depth)
    out.append("<path d=\"").append(pathToString(node.outline)).append('"')
    appendFill(out, node.fill, defs, node.bounds)
    appendStroke(out, node.stroke, defs, node.bounds)
    appendTransform(out, node.transform)
    appendOpacity(out, node.opacity)
    appendBlendMode(out, node.blendMode)
    appendAccessibility(out, node)
    out.append("/>")
  }

  private fun renderText(node: TextNode, out: StringBuilder, defs: Defs, depth: Int) {
    val run = node.layout.run
    val style = run.style
    newline(out, depth)
    out.append("<text x=\"").append(num(node.x)).append('"')
    out.append(" y=\"").append(num(node.y)).append('"')
    out.append(" font-family=\"").append(escapeXml(style.fontFamily)).append('"')
    out.append(" font-size=\"").append(num(style.fontSize)).append('"')
    if (style.fontWeight != 400) out.append(" font-weight=\"").append(style.fontWeight).append('"')
    if (style.fontStyle != dev.aster.vega.scene.FontStyle.NORMAL) {
      out.append(" font-style=\"italic\"")
    }
    if (style.letterSpacing != 0.0) {
      out.append(" letter-spacing=\"").append(num(style.letterSpacing)).append('"')
    }
    out.append(" text-anchor=\"").append(textAnchor(run.align)).append('"')
    out.append(" dominant-baseline=\"").append(dominantBaseline(run.baseline)).append('"')
    appendFill(out, node.fill, defs, node.bounds)
    appendStroke(out, node.stroke, defs, node.bounds)
    val transform =
      if (node.angleDegrees == 0.0) node.transform
      else
        node.transform
          .concat(Transform2D.translate(node.x, node.y))
          .concat(Transform2D.rotateDegrees(node.angleDegrees))
          .concat(Transform2D.translate(-node.x, -node.y))
    appendTransform(out, transform)
    appendOpacity(out, node.opacity)
    appendBlendMode(out, node.blendMode)
    appendAccessibility(out, node)
    out.append('>')

    val lines = node.layout.lines
    if (lines.size <= 1) {
      out.append(escapeXml(node.text))
    } else {
      // `tspan` with an absolute x keeps every line aligned to the same anchor.
      lines.forEach { line ->
        newline(out, depth + 1)
        out.append("<tspan x=\"").append(num(node.x)).append('"')
        out.append(" y=\"").append(num(node.y + line.baselineY)).append("\">")
        out.append(escapeXml(line.text))
        out.append("</tspan>")
      }
      newline(out, depth)
    }
    out.append("</text>")
  }

  private fun renderImage(
    node: ImageNode,
    out: StringBuilder,
    warnings: MutableList<SvgExportWarning>,
    depth: Int,
  ) {
    if (options.imagePolicy == SvgImagePolicy.REQUIRE_RESOLVED) {
      warnings.add(
        SvgExportWarning(
          code = dev.aster.vega.model.DiagnosticCodes.EXPORT_IMAGE_UNRESOLVED,
          message =
            "Image '${node.url}' was not resolved to embedded bytes and imagePolicy is REQUIRE_RESOLVED",
          nodeType = "image",
        )
      )
      return
    }
    val r = node.rect
    newline(out, depth)
    out.append("<image x=\"").append(num(r.left)).append('"')
    out.append(" y=\"").append(num(r.top)).append('"')
    out.append(" width=\"").append(num(r.width)).append('"')
    out.append(" height=\"").append(num(r.height)).append('"')
    out.append(" preserveAspectRatio=\"")
    out.append(if (node.fit == ImageFit.CONTAIN) "xMidYMid meet" else "none")
    out.append('"')
    if (!node.smooth) out.append(" image-rendering=\"pixelated\"")
    out.append(" xlink:href=\"").append(escapeXml(node.url)).append('"')
    appendTransform(out, node.transform)
    appendOpacity(out, node.opacity)
    appendAccessibility(out, node)
    out.append("/>")
  }

  // ---- attributes ----------------------------------------------------------

  private fun appendFill(out: StringBuilder, fill: Fill?, defs: Defs, bounds: RectD) {
    if (fill == null) {
      out.append(" fill=\"none\"")
      return
    }
    out.append(" fill=\"").append(paintRef(fill.paint, defs, bounds)).append('"')
    if (fill.opacity != 1.0) out.append(" fill-opacity=\"").append(num(fill.opacity)).append('"')
  }

  private fun appendStroke(out: StringBuilder, stroke: Stroke?, defs: Defs, bounds: RectD) {
    if (stroke == null || !stroke.isVisible) return
    out.append(" stroke=\"").append(paintRef(stroke.paint, defs, bounds)).append('"')
    if (stroke.width != 1.0) out.append(" stroke-width=\"").append(num(stroke.width)).append('"')
    if (stroke.cap != StrokeCap.BUTT) {
      out.append(" stroke-linecap=\"").append(strokeCap(stroke.cap)).append('"')
    }
    if (stroke.join != StrokeJoin.MITER) {
      out.append(" stroke-linejoin=\"").append(stroke.join.name.lowercase()).append('"')
    } else if (stroke.miterLimit != Stroke.DEFAULT_MITER_LIMIT) {
      // Omitted at the default, which leaves SVG's own limit of 10 in force. The two only differ at
      // a
      // join sharper than about 29 degrees, which no chart mark produces, and saying nothing keeps
      // the output comparable with upstream's — which also says nothing.
      out.append(" stroke-miterlimit=\"").append(num(stroke.miterLimit)).append('"')
    }
    if (stroke.dashArray.isNotEmpty()) {
      out.append(" stroke-dasharray=\"")
      out.append(stroke.dashArray.joinToString(",") { num(it) })
      out.append('"')
      if (stroke.dashOffset != 0.0) {
        out.append(" stroke-dashoffset=\"").append(num(stroke.dashOffset)).append('"')
      }
    }
    if (stroke.opacity != 1.0) {
      out.append(" stroke-opacity=\"").append(num(stroke.opacity)).append('"')
    }
  }

  private fun appendTransform(out: StringBuilder, transform: Transform2D) {
    if (transform.isIdentity) return
    out.append(" transform=\"matrix(")
    out.append(num(transform.a)).append(' ')
    out.append(num(transform.b)).append(' ')
    out.append(num(transform.c)).append(' ')
    out.append(num(transform.d)).append(' ')
    out.append(num(transform.e)).append(' ')
    out.append(num(transform.f))
    out.append(")\"")
  }

  private fun appendOpacity(out: StringBuilder, opacity: Double) {
    if (opacity != 1.0) out.append(" opacity=\"").append(num(opacity)).append('"')
  }

  private fun appendBlendMode(out: StringBuilder, mode: SceneBlendMode) {
    if (mode == SceneBlendMode.NORMAL) return
    out.append(" style=\"mix-blend-mode:").append(mode.name.lowercase()).append('"')
  }

  private fun appendAccessibility(out: StringBuilder, node: SceneNode) {
    if (!options.includeAccessibility) return
    val descriptor = node.metadata.accessibility ?: return
    descriptor.role?.let { out.append(" role=\"").append(escapeXml(it)).append('"') }
    val label = descriptor.value?.let { "${descriptor.label}: $it" } ?: descriptor.label
    out.append(" aria-label=\"").append(escapeXml(label)).append('"')
  }

  private fun appendDescription(out: StringBuilder, node: SceneNode, depth: Int) {
    if (!options.includeMetadata) return
    val descriptor = node.metadata.accessibility ?: return
    newline(out, depth)
    out.append("<desc>").append(escapeXml(descriptor.label)).append("</desc>")
  }

  private fun paintRef(paint: ScenePaint, defs: Defs, bounds: RectD): String =
    when (paint) {
      is ScenePaint.Solid -> if (paint.color.isTransparent) "none" else paint.color.toCssHex()
      is ScenePaint.LinearGradient -> {
        val stops = paint.stops.joinToString("") { stopElement(it.offset, it.color) }
        val key =
          "lg:${num(paint.x1)},${num(paint.y1)},${num(paint.x2)},${num(paint.y2)}:$stops:" +
            "${num(bounds.width)}x${num(bounds.height)}"
        val id =
          defs.idFor(key, "${options.idPrefix}g") { id ->
            "<linearGradient id=\"$id\" x1=\"${num(paint.x1)}\" y1=\"${num(paint.y1)}\" " +
              "x2=\"${num(paint.x2)}\" y2=\"${num(paint.y2)}\">$stops</linearGradient>"
          }
        "url(#$id)"
      }
      is ScenePaint.RadialGradient -> {
        val stops = paint.stops.joinToString("") { stopElement(it.offset, it.color) }
        val key =
          "rg:${num(paint.cx)},${num(paint.cy)},${num(paint.radius)},${num(paint.focusX)}," +
            "${num(paint.focusY)}:$stops"
        val id =
          defs.idFor(key, "${options.idPrefix}g") { id ->
            "<radialGradient id=\"$id\" cx=\"${num(paint.cx)}\" cy=\"${num(paint.cy)}\" " +
              "r=\"${num(paint.radius)}\" fx=\"${num(paint.focusX)}\" fy=\"${num(paint.focusY)}\">" +
              "$stops</radialGradient>"
          }
        "url(#$id)"
      }
    }

  private fun stopElement(offset: Double, color: dev.aster.vega.scene.SceneColor): String =
    "<stop offset=\"${num(offset)}\" stop-color=\"${color.toCssHex()}\"/>"

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

  private fun num(value: Double): String = canonicalNumberString(value, options.precision)

  private fun newline(out: StringBuilder, depth: Int) {
    if (options.pretty) {
      out.append('\n')
      out.append("  ".repeat(depth))
    }
  }

  private fun textAnchor(align: TextAlign): String =
    when (align) {
      TextAlign.LEFT -> "start"
      TextAlign.CENTER -> "middle"
      TextAlign.RIGHT -> "end"
    }

  private fun dominantBaseline(baseline: TextBaseline): String =
    when (baseline) {
      TextBaseline.ALPHABETIC -> "alphabetic"
      TextBaseline.TOP,
      TextBaseline.LINE_TOP -> "text-before-edge"
      TextBaseline.MIDDLE -> "central"
      TextBaseline.BOTTOM,
      TextBaseline.LINE_BOTTOM -> "text-after-edge"
    }

  private fun strokeCap(cap: StrokeCap): String =
    when (cap) {
      StrokeCap.BUTT -> "butt"
      StrokeCap.ROUND -> "round"
      StrokeCap.SQUARE -> "square"
    }
}

/** Escapes the five XML entities. Text content and attribute values share one escaper. */
public fun escapeXml(value: String): String {
  if (value.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' }) return value
  val out = StringBuilder(value.length + 16)
  for (ch in value) {
    when (ch) {
      '&' -> out.append("&amp;")
      '<' -> out.append("&lt;")
      '>' -> out.append("&gt;")
      '"' -> out.append("&quot;")
      '\'' -> out.append("&apos;")
      else -> out.append(ch)
    }
  }
  return out.toString()
}

/** Renders [this] to SVG text, discarding warnings. Use [SvgRenderer.render] when they matter. */
public fun Scene.toSvg(options: SvgOptions = SvgOptions()): String =
  SvgRenderer(options).render(this).svg
