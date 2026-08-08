package dev.aster.vega.runtime.differential

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.LinearScale
import dev.aster.vega.runtime.scale.PointScale
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.FontStyle
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.ImageNode
import dev.aster.vega.scene.PathNode
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.Stroke
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.Transform2D
import java.io.File
import kotlin.math.abs

/**
 * Differential comparison against upstream Vega.
 *
 * The two engines nest their scene graphs differently — Vega groups items under a marktype, this
 * engine produces a flat list — so comparing tree shape would fail on every fixture while saying
 * nothing about correctness. Instead both sides emit a flat list of marks in absolute content-space
 * coordinates, and the comparison covers what PROJECT_BRIEF.md 18.4 asks for: mark count, mark
 * types, coordinates, extents and scale outputs.
 *
 * Coordinates are in **content space**, excluding the padding translation, so chart geometry is
 * checked independently of layout. Layout is checked separately through the surface size.
 *
 * Tolerances follow the brief: discrete values compare exactly, geometry compares tightly, and text
 * bounds are excluded entirely because font metrics legitimately differ from a browser's
 * (docs/adr/0006).
 */
public object Differential {

  /**
   * Tight tolerance for geometry: enough for double arithmetic order, far below a visible pixel.
   */
  public const val GEOMETRY_TOLERANCE: Double = 1e-6

  /**
   * Tolerance for the extent of a mark built from curves.
   *
   * Upstream measures an arc exactly, from its centre and radii, because its renderer emits a true
   * circular arc. This engine's scene graph has only cubics — every backend it draws on takes them
   * — so an arc is approximated, and its bounds are the bounds of the curves actually painted. The
   * difference is about one part in a hundred thousand: a thousandth of a pixel on a 90-unit
   * radius, and on the side of describing what is drawn rather than what was intended.
   */
  public const val CURVE_EXTENT_TOLERANCE: Double = 1e-2

  /** One mark, flattened. Channel names match the reference file's. */
  public data class Mark(
    val type: String,
    val role: String?,
    val numbers: Map<String, Double>,
    val strings: Map<String, String>,
  ) {
    val key: String
      get() = "$type/${role ?: "-"}"

    override fun toString(): String {
      val n = numbers.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${fmt(it.value)}" }
      val s = strings.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }
      return "$key $n $s".trim()
    }
  }

  public data class Reference(
    val vegaVersion: String,
    val width: Double,
    val height: Double,
    val scales: Map<String, ScaleReference>,
    val marks: List<Mark>,
  )

  public data class ScaleReference(
    val domain: List<String>,
    val range: List<String>,
    val bandwidth: Double?,
    val step: Double?,
    val ticks: List<Double>?,
  )

  // ---- reading the reference ------------------------------------------------

  public fun readReference(file: File): Reference {
    require(file.isFile) {
      "Missing reference file ${file.path}. Regenerate it with ./scripts/oracle.sh"
    }
    val root = VegaJson.parse(file.readText()) as VegaValue.Obj
    val size = root.fields["size"] as VegaValue.Obj
    val scales =
      (root.fields["scales"] as? VegaValue.Obj)?.fields?.mapValues { (_, value) ->
        val obj = value as VegaValue.Obj
        ScaleReference(
          domain =
            (obj.fields["domain"] as? VegaValue.Arr)?.values?.map { it.asString() } ?: emptyList(),
          range =
            (obj.fields["range"] as? VegaValue.Arr)?.values?.map { it.asString() } ?: emptyList(),
          bandwidth = obj.fields["bandwidth"]?.asDouble(),
          step = obj.fields["step"]?.asDouble(),
          ticks = (obj.fields["ticks"] as? VegaValue.Arr)?.values?.map { it.asDouble() },
        )
      } ?: emptyMap()

    val marks =
      (root.fields["marks"] as? VegaValue.Arr)?.values?.map { markValue ->
        val obj = markValue as VegaValue.Obj
        val numbers = LinkedHashMap<String, Double>()
        val strings = LinkedHashMap<String, String>()
        for ((key, value) in obj.fields) {
          if (key == "type" || key == "role") continue
          when (value) {
            is VegaValue.Num -> numbers[key] = value.value
            is VegaValue.Str -> strings[key] = value.value
            else -> Unit
          }
        }
        Mark(
          type = obj.fields["type"]!!.asString(),
          role = obj.fields["role"]?.takeIf { it !is VegaValue.Null }?.asString(),
          numbers = numbers,
          strings = strings,
        )
      } ?: emptyList()

    return Reference(
      vegaVersion = root.fields["vegaVersion"]?.asString() ?: "unknown",
      width = (size.fields["width"] ?: VegaValue.Num(0.0)).asDouble(),
      height = (size.fields["height"] ?: VegaValue.Num(0.0)).asDouble(),
      scales = scales,
      marks = marks,
    )
  }

  // ---- flattening our own scene ---------------------------------------------

  /**
   * Flattens [scene] into comparable marks in content space.
   *
   * Starts from the frame group so the padding translation is excluded, matching how upstream
   * reports item coordinates relative to its own frame item.
   */
  public fun flattenScene(scene: Scene): List<Mark> {
    val frame = findFrame(scene.root) ?: scene.root
    val marks = mutableListOf<Mark>()
    collect(frame, Transform2D.Identity, marks)
    return marks
  }

  private fun findFrame(node: SceneNode): SceneNode? {
    if (node.metadata.role == "frame") return node
    if (node is GroupNode) {
      for (child in node.children) findFrame(child)?.let {
        return it
      }
    }
    return null
  }

  private fun collect(node: SceneNode, parent: Transform2D, out: MutableList<Mark>) {
    if (!node.visible) return
    val world = parent.concat(node.transform)
    when (node) {
      is GroupNode -> {
        // Only a painted group is a visible mark; a layout group carries no pixels.
        if (node.fill != null || node.stroke != null) out.add(groupMark(node, world))
        node.children.forEach { collect(it, world, out) }
      }
      is RectNode -> out.add(withOpacity(node, rectMark(node, world)))
      is RuleNode -> out.add(withOpacity(node, ruleMark(node, world)))
      is TextNode -> out.add(withOpacity(node, textMark(node, world)))
      is SymbolNode -> out.add(withOpacity(node, symbolMark(node, world)))
      is PathNode -> out.add(withOpacity(node, pathMark(node, world)))
      is ImageNode -> out.add(withOpacity(node, imageMark(node, world)))
    }
  }

  /**
   * A node's own opacity, which is not the same channel as a fill's or a stroke's.
   *
   * Added centrally because it was missing from every mark type at once, and nothing else in the
   * comparison could see it: a legend swatch faded to 0.6 by `symbolOpacity` has the same geometry,
   * the same fill and the same stroke as one at full strength.
   */
  private fun withOpacity(node: SceneNode, mark: Mark): Mark =
    mark.copy(numbers = mark.numbers + ("opacity" to node.opacity))

  private fun rectMark(node: RectNode, world: Transform2D): Mark {
    val rect = node.rect
    val origin = world.apply(rect.left, rect.top)
    val numbers =
      linkedMapOf(
        "x" to origin.x,
        "y" to origin.y,
        "width" to rect.width,
        "height" to rect.height,
      )
    return Mark("rect", node.metadata.role, numbers + paintNumbers(node), paintStrings(node))
  }

  private fun ruleMark(node: RuleNode, world: Transform2D): Mark {
    val a = world.apply(node.x1, node.y1)
    val b = world.apply(node.x2, node.y2)
    val numbers =
      linkedMapOf(
        "x" to a.x,
        "y" to a.y,
        "x2" to b.x,
        "y2" to b.y,
        "strokeWidth" to node.stroke.width,
        "strokeOpacity" to node.stroke.opacity,
      )
    val strings = LinkedHashMap<String, String>()
    solidColour(node.stroke.paint)?.let { strings["stroke"] = it.toCssHex() }
    dashOf(node.stroke)?.let { strings["strokeDash"] = it }
    return Mark("rule", node.metadata.role, numbers, strings)
  }

  /**
   * A dash pattern as one comparable string, or null for a solid line.
   *
   * Compared because nothing else here can see it: a dashed gridline and a solid one agree on
   * position, colour, width and opacity, so leaving the dash out would let a chart pass the
   * comparison and draw differently — the same way a symbol's outline once did.
   */
  private fun dashOf(stroke: Stroke): String? =
    stroke.dashArray.takeIf { it.isNotEmpty() }?.joinToString(",") { fmt(it) }

  private fun textMark(node: TextNode, world: Transform2D): Mark {
    val anchor = world.apply(node.x, node.y)
    val run = node.layout.run
    val numbers =
      linkedMapOf(
        "x" to anchor.x,
        "y" to anchor.y,
        "fontSize" to run.style.fontSize,
        // Rotation is geometry, not styling: a quarter-turned axis title reads down the page.
        "angle" to node.angleDegrees,
      )
    val strings =
      linkedMapOf(
        "text" to run.text,
        "align" to run.align.name.lowercase(),
        "baseline" to vegaBaseline(run.baseline),
        "font" to run.style.fontFamily,
      )
    if (run.style.fontStyle == FontStyle.ITALIC) strings["fontStyle"] = "italic"
    node.fill?.let { fill ->
      solidColour(fill.paint)?.let { strings["fill"] = it.toCssHex() }
      numbers["fillOpacity"] = fill.opacity
    }
    return Mark("text", node.metadata.role, numbers, strings)
  }

  /**
   * A symbol, compared by drawn extent as well as by position.
   *
   * The `size` channel alone would not catch a wrong shape table: upstream replaces d3-shape's
   * symbols with its own, sized so every shape fits a `sqrt(size)` box rather than by area.
   * Comparing the outline extent is what makes that visible.
   */
  private fun symbolMark(node: SymbolNode, world: Transform2D): Mark {
    val centre = world.apply(node.x, node.y)
    val extent = world.mapBounds(node.bounds)
    val numbers =
      linkedMapOf(
        "x" to centre.x,
        "y" to centre.y,
        "size" to node.size,
        "shapeLeft" to extent.left,
        "shapeTop" to extent.top,
        "shapeWidth" to extent.width,
        "shapeHeight" to extent.height,
      )
    return Mark("symbol", node.metadata.role, numbers + paintNumbers(node), paintStrings(node))
  }

  /**
   * A path node standing in for a line or an area.
   *
   * Upstream emits one item per datum and connects them at render time; this engine builds the
   * whole outline as one node. Both sides therefore report a point list rather than per-item
   * coordinates — see `SERIES_TYPES` in `oracle-js/src/normalize.js`.
   */
  private fun pathMark(node: PathNode, world: Transform2D): Mark {
    val kind = node.metadata.markKind ?: "path"
    if (kind != "line" && kind != "area") {
      // An arc is compared by the wedge it drew rather than by a centre point, which says nothing
      // about its radii or its sweep.
      val bounds = world.mapBounds(node.bounds)
      val numbers =
        linkedMapOf(
          "shapeLeft" to bounds.left,
          "shapeTop" to bounds.top,
          "shapeWidth" to bounds.width,
          "shapeHeight" to bounds.height,
        )
      // A `path` mark also reports the anchor it was placed at, which upstream carries as the
      // item's own x and y — the outline itself is in the path string's coordinates.
      if (kind == "path") {
        val anchor = world.apply(0.0, 0.0)
        numbers["x"] = anchor.x
        numbers["y"] = anchor.y
      }
      return Mark(kind, node.metadata.role, numbers + paintNumbers(node), paintStrings(node))
    }

    val vertices =
      node.path.commands.flatMap { command ->
        when (command) {
          is dev.aster.vega.scene.PathCommand.MoveTo -> listOf(world.apply(command.x, command.y))
          is dev.aster.vega.scene.PathCommand.LineTo -> listOf(world.apply(command.x, command.y))
          // A cubic's control points are part of the outline: two curves through the same anchors
          // are different shapes, and comparing anchors alone would not see it.
          is dev.aster.vega.scene.PathCommand.CubicTo ->
            listOf(
              world.apply(command.x1, command.y1),
              world.apply(command.x2, command.y2),
              world.apply(command.x, command.y),
            )
          else -> emptyList()
        }
      }
    val strings = LinkedHashMap<String, String>()
    strings["points"] = vertices.joinToString(" ") { "${fmt(it.x)} ${fmt(it.y)}" }
    node.metadata.interpolate?.let { strings["interpolate"] = it }
    val numbers = LinkedHashMap<String, Double>()
    // Whether the outline joins back onto itself. Nothing else here can see it: `linear-closed`
    // draws exactly the points `linear` does and closes the polygon, so a line left open would
    // otherwise match its reference on every channel and render with one side missing.
    if (kind == "line") {
      val closed = node.path.commands.any { it is dev.aster.vega.scene.PathCommand.Close }
      numbers["closed"] = if (closed) 1.0 else 0.0
    }
    node.fill?.let { f ->
      solidColour(f.paint)?.let { strings["fill"] = it.toCssHex() }
      numbers["fillOpacity"] = f.opacity
    }
    node.stroke?.let { st ->
      solidColour(st.paint)?.let { strings["stroke"] = it.toCssHex() }
      dashOf(st)?.let { strings["strokeDash"] = it }
      numbers["strokeWidth"] = st.width
      numbers["strokeOpacity"] = st.opacity
    }
    return Mark(kind, node.metadata.role, numbers, strings)
  }

  /**
   * An image's reported `x` is the one the specification gave, **not** where it is drawn.
   *
   * Upstream keeps `align` and `baseline` on the item and offsets only at paint time, so comparing
   * the drawn corner would disagree with it on every centred image. `align` and `baseline` are
   * compared too, since two images at different anchors can be drawn in the same place and are not
   * interchangeable to anything that lays out again.
   */
  private fun imageMark(node: ImageNode, world: Transform2D): Mark {
    val anchor = world.apply(node.x, node.y)
    return Mark(
      "image",
      node.metadata.role,
      linkedMapOf(
        "x" to anchor.x,
        "y" to anchor.y,
        "width" to node.width,
        "height" to node.height,
      ),
      linkedMapOf(
        "url" to node.url,
        "align" to node.align.name.lowercase(),
        "baseline" to node.baseline.name.lowercase(),
      ),
    )
  }

  /**
   * A painted group: a facet cell's background, or any group mark with a fill or a stroke.
   *
   * Its origin is its own coordinate origin, which the accumulated transform already carries, and
   * its extent is the size it declared — the same two things upstream reports as the group item's
   * `x`/`y` and `width`/`height`.
   */
  private fun groupMark(node: GroupNode, world: Transform2D): Mark {
    val origin = world.apply(0.0, 0.0)
    val size = node.size
    val numbers = linkedMapOf("x" to origin.x, "y" to origin.y)
    if (size != null) {
      numbers["width"] = size.width
      numbers["height"] = size.height
    }
    val strings = LinkedHashMap<String, String>()
    node.fill?.let { f -> solidColour(f.paint)?.let { strings["fill"] = it.toCssHex() } }
    node.stroke?.let { s ->
      solidColour(s.paint)?.let { strings["stroke"] = it.toCssHex() }
      numbers["strokeWidth"] = s.width
    }
    return Mark("group", node.metadata.role, numbers, strings)
  }

  /**
   * Paint values that are numbers, including the opacities.
   *
   * Opacity is easy to leave out and expensive to get wrong: a mark at 0.75 fill opacity and one at
   * 1 have identical geometry, so nothing else in this comparison would notice.
   */
  private fun paintNumbers(node: SceneNode): Map<String, Double> {
    val result = LinkedHashMap<String, Double>()
    val fill =
      when (node) {
        is RectNode -> node.fill
        is SymbolNode -> node.fill
        is PathNode -> node.fill
        else -> null
      }
    fill?.let { result["fillOpacity"] = it.opacity }
    val stroke =
      when (node) {
        is RectNode -> node.stroke
        is SymbolNode -> node.stroke
        is PathNode -> node.stroke
        else -> null
      }
    stroke?.let {
      result["strokeWidth"] = it.width
      result["strokeOpacity"] = it.opacity
    }
    return result
  }

  private fun paintStrings(node: SceneNode): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    val fill =
      when (node) {
        is RectNode -> node.fill
        is SymbolNode -> node.fill
        is PathNode -> node.fill
        else -> null
      }
    fill?.let { f -> solidColour(f.paint)?.let { result["fill"] = it.toCssHex() } }
    val stroke =
      when (node) {
        is RectNode -> node.stroke
        is SymbolNode -> node.stroke
        is PathNode -> node.stroke
        else -> null
      }
    stroke?.let { s ->
      solidColour(s.paint)?.let { result["stroke"] = it.toCssHex() }
      dashOf(s)?.let { result["strokeDash"] = it }
    }
    return result
  }

  private fun solidColour(paint: dev.aster.vega.scene.ScenePaint): SceneColor? =
    (paint as? dev.aster.vega.scene.ScenePaint.Solid)?.color

  /**
   * Vega spells the alphabetic baseline differently in its scenegraph than the scene model does.
   */
  private fun vegaBaseline(baseline: dev.aster.vega.scene.TextBaseline): String =
    when (baseline) {
      dev.aster.vega.scene.TextBaseline.ALPHABETIC -> "alphabetic"
      dev.aster.vega.scene.TextBaseline.TOP -> "top"
      dev.aster.vega.scene.TextBaseline.MIDDLE -> "middle"
      dev.aster.vega.scene.TextBaseline.BOTTOM -> "bottom"
      dev.aster.vega.scene.TextBaseline.LINE_TOP -> "line-top"
      dev.aster.vega.scene.TextBaseline.LINE_BOTTOM -> "line-bottom"
    }

  // ---- comparison -----------------------------------------------------------

  public data class Difference(val where: String, val expected: String, val actual: String) {
    override fun toString(): String = "$where: expected $expected, got $actual"
  }

  /**
   * Compares our marks against the reference.
   *
   * Marks are matched positionally within each `type/role` group, because both sides emit in paint
   * order. A count mismatch is reported before any coordinate comparison, since positional matching
   * is meaningless once the counts differ.
   */
  public fun compareMarks(
    expected: List<Mark>,
    actual: List<Mark>,
    tolerance: Double = GEOMETRY_TOLERANCE,
    ignoredChannels: Set<String> = DEFAULT_IGNORED_CHANNELS,
  ): List<Difference> {
    val differences = mutableListOf<Difference>()
    val expectedGroups = expected.groupBy { it.key }
    val actualGroups = actual.groupBy { it.key }

    for (key in (expectedGroups.keys + actualGroups.keys).sorted()) {
      val want = expectedGroups[key].orEmpty()
      val got = actualGroups[key].orEmpty()
      if (want.size != got.size) {
        differences.add(Difference("$key count", want.size.toString(), got.size.toString()))
        continue
      }
      want.zip(got).forEachIndexed { index, (e, a) ->
        compareMark("$key[$index]", e, a, tolerance, ignoredChannels, differences)
      }
    }
    return differences
  }

  private fun compareMark(
    where: String,
    expected: Mark,
    actual: Mark,
    tolerance: Double,
    ignored: Set<String>,
    out: MutableList<Difference>,
  ) {
    for ((channel, wanted) in expected.numbers) {
      if (channel in ignored) continue
      val got = actual.numbers[channel] ?: defaultFor(channel)
      if (got == null) {
        out.add(Difference("$where.$channel", fmt(wanted), "absent"))
        continue
      }
      val allowed =
        // A trail's end caps are semicircles, so it is measured the same way an arc is: by the
        // cubics actually painted rather than by an exact circle. A `path` mark joins them because
        // its outline may contain an SVG `A` command, which both engines approximate with cubics
        // and neither splits identically — a circle written as one arc measures 14.000002 upstream
        // and 14.0000001 here.
        if (channel.startsWith("shape") && expected.type in CURVE_EXTENT_TYPES) {
          CURVE_EXTENT_TOLERANCE
        } else {
          tolerance
        }
      if (abs(wanted - got) > allowed) {
        out.add(Difference("$where.$channel", fmt(wanted), fmt(got)))
      }
    }
    // Paint the reference does not have is as much a difference as paint it has and we lack: a mark
    // stroked here and unstroked upstream draws an outline that should not be there, and comparing
    // only the reference's own channels would never notice.
    for (channel in COLOUR_CHANNELS) {
      if (channel in ignored) continue
      if (channel !in expected.strings && channel in actual.strings) {
        out.add(Difference("$where.$channel", "absent", actual.strings.getValue(channel)))
      }
    }
    for ((channel, wanted) in expected.strings) {
      if (channel in ignored) continue
      // A geometry channel the reference records as `NaN` or an infinity. It is a *string* there
      // because JSON has no such literal and `canonicalNumber` stringifies it on purpose, so that
      // upstream producing one stays visible rather than being rounded away.
      //
      // It means upstream's scene item carries no usable number, which its renderer then paints as
      // zero: `interactive-legend`'s brush rect has `width: NaN` in the scene and draws
      // `M0,0h0v200h0Z` in upstream's own SVG. This engine's scene *is* the painted form — a scene
      // node holds numbers a renderer can use, not the arithmetic that produced them — so it stores
      // the zero directly.
      //
      // Checked as zero rather than skipped: a real number here is still a difference, so this says
      // the two agree on what gets painted without giving up the channel.
      if (channel in GEOMETRY_CHANNELS && wanted in NON_FINITE) {
        val painted = actual.numbers[channel]
        if (painted == null || painted != 0.0) {
          out.add(
            Difference("$where.$channel", "$wanted (painted as 0)", painted?.let(::fmt) ?: "absent")
          )
        }
        continue
      }
      val got = actual.strings[channel]
      if (got == null) {
        out.add(Difference("$where.$channel", wanted, "absent"))
        continue
      }
      val equal =
        when {
          // Colours compare by value, not by spelling: "steelblue" and "#4682b4" are one colour.
          channel in COLOUR_CHANNELS -> {
            val a = SceneColor.parse(wanted)
            val b = SceneColor.parse(got)
            a != null && b != null && a.toCssHex() == b.toCssHex()
          }
          // A point list is geometry, so it compares numerically within tolerance. Comparing the
          // text
          // would fail on nothing worse than the reference rounding 57.6 where we print
          // 57.599999999999994.
          channel == "points" -> pointsMatch(wanted, got, tolerance)
          else -> wanted == got
        }
      if (!equal) out.add(Difference("$where.$channel", wanted, got))
    }
  }

  /** Compares two whitespace-separated coordinate lists numerically. */
  private fun pointsMatch(expected: String, actual: String, tolerance: Double): Boolean {
    val wanted = expected.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
    val got = actual.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
    if (wanted.size != got.size) return false
    return wanted.indices.all { abs(wanted[it] - got[it]) <= tolerance }
  }

  /** Channels Vega always emits that default to a known value on our side. */
  private fun defaultFor(channel: String): Double? =
    when (channel) {
      "opacity",
      "fillOpacity",
      "strokeOpacity" -> 1.0
      "angle" -> 0.0
      "strokeWidth" -> null // absence means no stroke at all, which is a real difference
      else -> null
    }

  public fun compareScales(
    expected: Map<String, ScaleReference>,
    actual: Map<String, VegaScale>,
    tolerance: Double = GEOMETRY_TOLERANCE,
  ): List<Difference> {
    val differences = mutableListOf<Difference>()
    for ((name, reference) in expected) {
      val scale = actual[name]
      if (scale == null) {
        differences.add(Difference("scale $name", "present", "absent"))
        continue
      }
      when (scale) {
        is LinearScale -> {
          compareNumberList(
            "scale $name domain",
            reference.domain,
            scale.domain,
            tolerance,
            differences,
          )
          compareNumberList(
            "scale $name range",
            reference.range,
            scale.range,
            tolerance,
            differences,
          )
          reference.ticks?.let { wanted ->
            compareNumberList(
              "scale $name ticks",
              wanted.map { fmt(it) },
              scale.ticks(),
              tolerance,
              differences,
            )
          }
        }
        is BandScale -> {
          if (reference.domain != scale.domain) {
            differences.add(
              Difference("scale $name domain", reference.domain.toString(), scale.domain.toString())
            )
          }
          compareNumberList(
            "scale $name range",
            reference.range,
            scale.range,
            tolerance,
            differences,
          )
          reference.bandwidth?.let {
            if (abs(it - scale.bandwidth) > tolerance) {
              differences.add(Difference("scale $name bandwidth", fmt(it), fmt(scale.bandwidth)))
            }
          }
          reference.step?.let {
            if (abs(it - scale.step) > tolerance) {
              differences.add(Difference("scale $name step", fmt(it), fmt(scale.step)))
            }
          }
        }
        is PointScale -> {
          if (reference.domain != scale.domain) {
            differences.add(
              Difference("scale $name domain", reference.domain.toString(), scale.domain.toString())
            )
          }
          compareNumberList(
            "scale $name range",
            reference.range,
            scale.range,
            tolerance,
            differences,
          )
        }
        else -> Unit
      }
    }
    return differences
  }

  private fun compareNumberList(
    where: String,
    expected: List<String>,
    actual: List<Double>,
    tolerance: Double,
    out: MutableList<Difference>,
  ) {
    val wanted = expected.mapNotNull { it.toDoubleOrNull() }
    if (wanted.size != expected.size) return // a non-numeric list, compared elsewhere
    if (wanted.size != actual.size) {
      out.add(Difference("$where size", wanted.size.toString(), actual.size.toString()))
      return
    }
    wanted.zip(actual).forEachIndexed { index, (e, a) ->
      if (abs(e - a) > tolerance) out.add(Difference("$where[$index]", fmt(e), fmt(a)))
    }
  }

  private fun fmt(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

  /**
   * Channels excluded from comparison.
   *
   * Text glyph metrics are the documented exception (PROJECT_BRIEF.md 18.4, docs/adr/0006): a
   * browser and Android measure fonts differently, so comparing them would fail for reasons
   * unrelated to this engine's behaviour. Everything else is compared.
   */
  public val DEFAULT_IGNORED_CHANNELS: Set<String> = setOf("font", "fontWeight")

  /** Mark types whose drawn extent comes from a curve approximating a true circular arc. */
  private val CURVE_EXTENT_TYPES = setOf("arc", "trail", "path")

  private val COLOUR_CHANNELS = setOf("fill", "stroke")

  /** The channels that carry a position or an extent, and so have a painted equivalent of zero. */
  private val GEOMETRY_CHANNELS =
    setOf("x", "y", "x2", "y2", "width", "height", "size", "innerRadius", "outerRadius")

  /** What `canonicalNumber` writes where a number is not finite. */
  private val NON_FINITE = setOf("NaN", "Infinity", "-Infinity")
}
