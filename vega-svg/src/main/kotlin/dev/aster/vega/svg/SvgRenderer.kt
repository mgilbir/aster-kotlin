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
import dev.aster.vega.scene.PngEncoder
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
import dev.aster.vega.scene.paintOrder
import dev.aster.vega.scene.paintsNothing

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
   * Prefix for every generated `id`.
   *
   * Ids are sequential from document order, so two documents rendered with the **same** prefix
   * generate the same ids — `vc0`, `vg0` — and an `xlink:href="#vc0"` in the second one resolves
   * against the first when both are inlined into one page. Two charts on one page therefore need
   * two prefixes; a standalone file needs nothing. There is no way for a renderer to detect this
   * for itself, which is why it is written here rather than warned about.
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
 * SVG is an output format, not the runtime model (ADR 0001): this class only reads the scene graph.
 * Output is canonical — attributes appear in a fixed order, numbers use [SvgOptions.precision], and
 * generated ids are sequential from document order — so golden diffs reflect real rendering changes
 * rather than serialization noise.
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
    // A fully transparent *group* still emits its children: upstream puts `opacity="0"` on the
    // group's own background path and leaves the child elements exactly as they were, because a
    // group's opacity applies to its panel and is not inherited. Anything else paints nothing at
    // zero opacity, so omitting it says the same thing in less markup — and an item with no text
    // and no outline at all emits no element, which upstream also does and this export did not.
    // The predicate is shared with the three other walks; see `paintsNothing`.
    if (paintsNothing(node)) return

    // An `href` makes the item a link, which in SVG means wrapping whatever it draws in an anchor —
    // upstream emits `<a xlink:href="…">` around the element and nothing else. Done here rather
    // than in
    // each of the seven renderers below, because it is the same wrapper whatever the shape is.
    val href =
      node.metadata.href?.let { candidate ->
        if (isSafeHref(candidate)) {
          candidate
        } else {
          // Upstream refuses it outright — `handleHref` goes through
          // `loader.sanitize(href, {context: 'href'})`, which throws "Sanitize failure, invalid
          // URI"
          // and emits no anchor at all. The mark is still drawn; only the link is dropped.
          warnings.add(
            SvgExportWarning(
              code = "SVG_HREF_REFUSED",
              message =
                "The link '$candidate' is not a scheme this export will write; the mark was drawn " +
                  "without it. Upstream refuses the same set.",
              nodeType = node::class.simpleName ?: "node",
            )
          )
          null
        }
      }
    if (href != null) {
      newline(out, depth)
      out.append("<a xlink:href=\"").append(escapeXml(href)).append("\">")
    }

    when (node) {
      is GroupNode -> renderGroup(node, out, defs, warnings, depth)
      is RectNode -> renderRect(node, out, defs, depth)
      is RuleNode -> renderRule(node, out, defs, depth)
      is PathNode -> renderPath(node, out, defs, depth)
      is SymbolNode -> renderSymbol(node, out, defs, depth)
      is TextNode -> renderText(node, out, defs, depth)
      is ImageNode -> renderImage(node, out, warnings, depth)
    }

    if (href != null) {
      newline(out, depth)
      out.append("</a>")
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
    // **Not** `appendOpacity` here. `opacity` on a `<g>` composites the whole subtree, so a
    // half-opaque group drew its opaque children at half and an `opacity: 0` group made its
    // children vanish — which is neither what the canvas renderers do (they apply a group's own
    // opacity to its own paint and nothing else, and say so) nor what upstream emits. Upstream
    // puts it on the group's background `<path>`: probed by rendering a half-opaque group with an
    // opaque child through `view.toSVG()`, which comes back
    // `<path class="background" … opacity="0.5"/>` with the child's own `<path>` untouched.
    clipId?.let { out.append(" clip-path=\"url(#").append(it).append(")\"") }
    appendStyle(out, node, node.blendMode)
    appendAccessibility(out, node)
    out.append('>')

    appendDescription(out, node, depth + 1)

    // A group with its own paint draws its clip rectangle as a backing rect, matching Vega's
    // group-mark behaviour. `strokeForeground` splits that into two elements — the fill under the
    // children and the stroke over them — because the alternative, one element drawn twice, would
    // paint the fill over the children as well.
    // Which rectangle that is comes from the scene graph's `paintRect`: the group's declared size,
    // or failing that its clip. Reading the clip alone missed every Vega-Lite chart — its plotting
    // area states a size and a `#ddd` border and does not clip, so that border was drawn on the
    // device and absent from every SVG export.
    val background = node.paintRect?.takeIf { node.fill != null || node.stroke != null }
    if (background != null) {
      renderGroupPaint(
        node,
        background,
        out,
        defs,
        depth + 1,
        stroked = !node.strokeForeground,
      )
    }

    // Painted in `zindex` order, which is a render-time question rather than a scene one: upstream
    // keeps its items in data order and reorders in `visit`, and the differential harness compares
    // the
    // scene, so the reordering has to happen here or the two engines draw the same list
    // differently.
    renderChildren(paintOrder(node.children), out, defs, warnings, depth + 1)

    if (background != null && node.strokeForeground && node.stroke != null) {
      renderGroupPaint(node, background, out, defs, depth + 1, filled = false)
    }

    newline(out, depth)
    out.append("</g>")
  }

  /**
   * A group's children, with each mark's items inside a container element.
   *
   * Upstream draws every item of a mark inside one `<g>` and hangs the mark's own announcement on
   * it: a `description` on the mark names the whole series, so a reader hears "Monthly revenue"
   * once rather than on each of the twelve bars. This scene has no mark level — the items *are* the
   * group's children — so the container is rebuilt from a run of items that agree on which mark
   * they came from, which is what [NodeMetadata.markOrdinal] is for.
   *
   * Emitted only when there is something to say. Upstream's container carries CSS classes as well
   * and so is always present; this one holds nothing but the announcement, and an export with
   * accessibility switched off would otherwise gain a wrapper that means nothing.
   */
  private fun renderChildren(
    children: List<SceneNode>,
    out: StringBuilder,
    defs: Defs,
    warnings: MutableList<SvgExportWarning>,
    depth: Int,
  ) {
    var index = 0
    while (index < children.size) {
      val first = children[index]
      val container = first.metadata.markAccessibility.takeIf { options.includeAccessibility }
      if (container == null) {
        renderNode(first, out, defs, warnings, depth)
        index++
        continue
      }
      var end = index
      while (end + 1 < children.size && sameMark(children[end + 1], first)) end++
      newline(out, depth)
      out.append("<g")
      if (container.hidden) {
        out.append(" aria-hidden=\"true\"")
      } else {
        container.role?.let { out.append(" role=\"").append(escapeXml(it)).append('"') }
        container.roleDescription?.let {
          out.append(" aria-roledescription=\"").append(escapeXml(it)).append('"')
        }
        container.label?.let { out.append(" aria-label=\"").append(escapeXml(it)).append('"') }
      }
      out.append('>')
      for (i in index..end) renderNode(children[i], out, defs, warnings, depth + 1)
      newline(out, depth)
      out.append("</g>")
      index = end + 1
    }
  }

  /** Whether two nodes came from the same mark, and so belong in the same container. */
  private fun sameMark(node: SceneNode, first: SceneNode): Boolean =
    node.metadata.markAccessibility == first.metadata.markAccessibility &&
      node.metadata.markOrdinal == first.metadata.markOrdinal &&
      node.metadata.markName == first.metadata.markName &&
      node.metadata.markKind == first.metadata.markKind

  /**
   * A group's own rectangle, and **the only thing a group's `opacity` applies to**.
   *
   * See the note in `renderGroup`: the opacity belongs on this element rather than on the `<g>`
   * that holds the children, which is where upstream puts it and what the canvas renderers do.
   */
  private fun renderGroupPaint(
    node: GroupNode,
    clipRect: RectD,
    out: StringBuilder,
    defs: Defs,
    depth: Int,
    filled: Boolean = true,
    stroked: Boolean = true,
  ) {
    newline(out, depth)
    val rounded = node.roundedPaintPath
    if (rounded != null) {
      out.append("<path d=\"").append(pathToString(rounded)).append('"')
    } else {
      val offset = node.effectiveStrokeOffset
      out.append("<rect x=\"").append(num(clipRect.left + offset))
      out.append("\" y=\"").append(num(clipRect.top + offset))
      out.append("\" width=\"").append(num(clipRect.width))
      out.append("\" height=\"").append(num(clipRect.height))
      out.append('"')
    }
    appendFill(out, if (filled) node.fill else null, defs, node.bounds)
    appendStroke(out, if (stroked) node.stroke else null, defs, node.bounds)
    appendOpacity(out, node.opacity)
    out.append("/>")
  }

  private fun renderRect(node: RectNode, out: StringBuilder, defs: Defs, depth: Int) {
    newline(out, depth)
    // A rounded rectangle is emitted as a path, as upstream emits it. `rx`/`ry` cannot hold four
    // different radii, and even for one it draws a true elliptical arc where Vega draws a Bézier
    // approximation of one — so a `<rect rx>` would be a different shape at every corner.
    val rounded = node.roundedPath
    if (rounded != null) {
      out.append("<path d=\"").append(pathToString(rounded)).append('"')
    } else {
      val r = node.rect
      out.append("<rect x=\"").append(num(r.left)).append('"')
      out.append(" y=\"").append(num(r.top)).append('"')
      out.append(" width=\"").append(num(r.width)).append('"')
      out.append(" height=\"").append(num(r.height)).append('"')
    }
    appendFill(out, node.fill, defs, node.bounds)
    appendStroke(out, node.stroke, defs, node.bounds)
    appendTransform(out, node.transform)
    appendOpacity(out, node.opacity)
    appendStyle(out, node, node.blendMode)
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
    appendStyle(out, node, node.blendMode)
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
    appendStyle(out, node, node.blendMode)
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
    appendStyle(out, node, node.blendMode)
    appendAccessibility(out, node)
    out.append("/>")
  }

  private fun renderText(node: TextNode, out: StringBuilder, defs: Defs, depth: Int) {
    // A text item with no usable anchor draws nothing, which is what upstream's own SVG contains:
    // its `tickExtra` label has a `NaN` position in the scene and no element in the output at all.
    if (!node.x.isFinite() || !node.y.isFinite()) return
    val run = node.layout.run
    val style = run.style
    newline(out, depth)
    // The **first line's baseline**, not the anchor; see the note on the baseline below. The
    // rotation transform further down still pivots about the anchor, which is where it belongs.
    val firstBaseline = node.y + firstBaselineOffset(node.layout)
    out.append("<text x=\"").append(num(node.x)).append('"')
    out.append(" y=\"").append(num(firstBaseline)).append('"')
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
    // No `dominant-baseline`. It is a *per-line* instruction, so a three-line label with
    // `baseline: middle` had each of its lines centred on its own `y` and the block came out
    // (n − 1)·lineHeight/2 lower than every canvas renderer draws it — those apply one offset from
    // the anchor to the **first** baseline and stack from there, which is what `TextMetrics.height`
    // being the whole block's height means. An export that does not match the screen is the one
    // thing this renderer exists to avoid. The offset is folded into `y` instead, and the element
    // is left on SVG's own default baseline, which is what upstream emits too.
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
    appendStyle(out, node, node.blendMode)
    appendAccessibility(out, node)
    out.append('>')

    val lines = node.layout.lines
    if (lines.size <= 1) {
      // The laid-out line, not the node's own text: the two differ when a guide's `limit` has
      // shortened the label, and the scene deliberately keeps the whole string on the run so a
      // reader — or the accessibility tree — still gets the value it came from.
      out.append(escapeXml(lines.firstOrNull()?.text ?: node.text))
    } else {
      // `tspan` with an absolute x keeps every line aligned to the same anchor.
      lines.forEach { line ->
        newline(out, depth + 1)
        out.append("<tspan x=\"").append(num(node.x)).append('"')
        out.append(" y=\"").append(num(firstBaseline + line.baselineY)).append("\">")
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
    // Pixels the mark carries are already resolved — there is nothing to fetch — so they are
    // encoded straight into the document whatever the policy says about addresses.
    val href = node.raster?.let { PngEncoder.dataUrl(it) }
    if (href == null && options.imagePolicy == SvgImagePolicy.REQUIRE_RESOLVED) {
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
    out.append(" xlink:href=\"").append(escapeXml(href ?: node.url)).append('"')
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

  /**
   * The one `style` attribute an element gets, holding whatever needs to be CSS rather than SVG.
   *
   * One attribute rather than two, because a blend mode and a cursor both live in `style` and an
   * element carrying it twice is invalid — a browser keeps one and silently drops the other.
   */
  private fun appendStyle(out: StringBuilder, node: SceneNode, mode: SceneBlendMode) {
    val parts = mutableListOf<String>()
    // `COLOR_DODGE` is CSS's `color-dodge`; the enum's underscores are the only difference.
    if (mode != SceneBlendMode.NORMAL) {
      parts += "mix-blend-mode:" + mode.name.lowercase().replace('_', '-')
    }
    node.metadata.cursor?.takeIf { it.isNotEmpty() }?.let { parts += "cursor:" + it }
    if (parts.isEmpty()) return
    out.append(" style=\"").append(escapeXml(parts.joinToString(";"))).append('"')
  }

  private fun appendAccessibility(out: StringBuilder, node: SceneNode) {
    if (!options.includeAccessibility) return
    val descriptor = node.metadata.accessibility ?: return
    descriptor.role?.let { out.append(" role=\"").append(escapeXml(it)).append('"') }
    // Said together with the role: "graphics-symbol" alone tells a reader nothing, and upstream
    // pairs every label with the kind of thing in words.
    descriptor.roleDescription?.let {
      out.append(" aria-roledescription=\"").append(escapeXml(it)).append('"')
    }
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
        // The key is the **emitted element**, and nothing else. It used to carry the node's
        // bounds, which the `<linearGradient>` below does not mention: two marks of different
        // sizes sharing one gradient therefore got two identical definitions in `<defs>`, one per
        // size. `bounds` is still a parameter because a radial gradient's geometry needs it.
        val key = "lg:${num(paint.x1)},${num(paint.y1)},${num(paint.x2)},${num(paint.y2)}:$stops"
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

  /**
   * Anchor to first baseline, the same rule `AndroidCanvasSceneRenderer.baselineOffset` applies.
   *
   * One rule stated twice is a rule that drifts, and this one had: the export used SVG's own
   * per-line `dominant-baseline` and the canvas renderers used the block's metrics, so a multi-line
   * label came out in two different places. Written here rather than shared into `vega-scene` only
   * because `TextMetrics` already carries everything it needs.
   */
  private fun firstBaselineOffset(layout: dev.aster.vega.scene.TextLayout): Double {
    val metrics = layout.metrics
    return when (layout.run.baseline) {
      TextBaseline.ALPHABETIC -> 0.0
      TextBaseline.TOP,
      TextBaseline.LINE_TOP -> metrics.ascent
      TextBaseline.MIDDLE -> metrics.ascent - metrics.height / 2.0
      TextBaseline.BOTTOM,
      TextBaseline.LINE_BOTTOM -> metrics.ascent - metrics.height
    }
  }

  private fun strokeCap(cap: StrokeCap): String =
    when (cap) {
      StrokeCap.BUTT -> "butt"
      StrokeCap.ROUND -> "round"
      StrokeCap.SQUARE -> "square"
    }
}

/** Escapes the five XML entities. Text content and attribute values share one escaper. */
/**
 * Whether a spec-supplied `href` may be written into an export, transcribed from upstream's own
 * allowlist.
 *
 * ```js
 * const whitespace_re = /[\u0000-\u0020\u00A0\u1680\u180E\u2000-\u2029\u205f\u3000]/g;
 * const allowed_re =
 *   /^(?:(?:(?:f|ht)tps?|mailto|tel|callto|cid|xmpp|file|data):|[^a-z]|[a-z+.\-]+(?:[^a-z+.\-:]|$))/i;
 * ```
 *
 * A link is a **specification-controlled string**, and this project's own threat model treats a
 * specification as untrusted. The export was XML-escaping it and writing it through, so a
 * `javascript:` URL survived into a file that is clickable the moment a browser opens it — the one
 * thing an export of untrusted input must not do. Upstream sanitizes the same string through its
 * loader and refuses this set; that answers the audit's open question about whether it does.
 *
 * Three alternatives, and the third is the one worth reading twice: a **listed scheme**; a first
 * character that is not a letter, which is every relative link (`/path`, `#anchor`, `?q=1`); or a
 * run of scheme characters that is *not* followed by a colon, which is what lets `images/a.png`
 * through and keeps `javascript:` out. The whitespace class is stripped first, because
 * `java\nscript:` is the same URL to a browser and a different string to a matcher.
 */
public fun isSafeHref(href: String): Boolean {
  val stripped = href.filterNot { it.code <= 0x20 || it in HREF_WHITESPACE }
  if (stripped.isEmpty()) return false
  for (scheme in HREF_SCHEMES) {
    if (
      stripped.length > scheme.length && stripped.regionMatches(0, scheme, 0, scheme.length, true)
    ) {
      return true
    }
  }
  val first = stripped[0]
  if (!first.isLetter()) return true
  // `[a-z+.\-]+(?:[^a-z+.\-:]|$)`
  var index = 0
  while (index < stripped.length && isSchemeChar(stripped[index])) index++
  if (index == 0) return false
  return index == stripped.length || stripped[index] != ':'
}

private fun isSchemeChar(ch: Char): Boolean =
  (ch in 'a'..'z') || (ch in 'A'..'Z') || ch == '+' || ch == '.' || ch == '-'

private val HREF_WHITESPACE =
  charArrayOf('\u00A0', '\u1680', '\u180E', '\u205F', '\u3000').concatToString() +
    (0x2000..0x2029).map { it.toChar() }.joinToString("")

private val HREF_SCHEMES =
  listOf(
    "http:",
    "https:",
    "ftp:",
    "ftps:",
    "mailto:",
    "tel:",
    "callto:",
    "cid:",
    "xmpp:",
    "file:",
    "data:",
  )

public fun escapeXml(value: String): String {
  if (value.none { it == '&' || it == '<' || it == '>' || it == '"' || it == '\'' || it < ' ' }) {
    return value
  }
  val out = StringBuilder(value.length + 16)
  for (ch in value) {
    when {
      ch == '&' -> out.append("&amp;")
      ch == '<' -> out.append("&lt;")
      ch == '>' -> out.append("&gt;")
      ch == '"' -> out.append("&quot;")
      ch == '\'' -> out.append("&apos;")
      // XML 1.0 has **no way to write** a C0 control character other than tab, newline and
      // carriage return — not even as a numeric reference — so a document containing one is not
      // well formed and a viewer refuses the whole file. Escaping the five entities and passing
      // everything else through meant one stray byte in a data-derived label took the export down,
      // which is not a thing a reader can diagnose. The snapshot serializer already drops them;
      // this is the same rule at the other end.
      ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> Unit
      else -> out.append(ch)
    }
  }
  return out.toString()
}

/** Renders [this] to SVG text, discarding warnings. Use [SvgRenderer.render] when they matter. */
public fun Scene.toSvg(options: SvgOptions = SvgOptions()): String =
  SvgRenderer(options).render(this).svg
