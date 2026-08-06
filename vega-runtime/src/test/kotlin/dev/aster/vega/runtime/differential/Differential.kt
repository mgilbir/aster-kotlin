package dev.aster.vega.runtime.differential

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.LinearScale
import dev.aster.vega.runtime.scale.PointScale
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.ImageNode
import dev.aster.vega.scene.PathNode
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNode
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
      is RectNode -> out.add(rectMark(node, world))
      is RuleNode -> out.add(ruleMark(node, world))
      is TextNode -> out.add(textMark(node, world))
      is SymbolNode -> out.add(symbolMark(node, world))
      is PathNode -> out.add(pathMark(node, world))
      is ImageNode -> out.add(imageMark(node, world))
    }
  }

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
      )
    val strings = LinkedHashMap<String, String>()
    solidColour(node.stroke.paint)?.let { strings["stroke"] = it.toCssHex() }
    return Mark("rule", node.metadata.role, numbers, strings)
  }

  private fun textMark(node: TextNode, world: Transform2D): Mark {
    val anchor = world.apply(node.x, node.y)
    val run = node.layout.run
    val numbers = linkedMapOf("x" to anchor.x, "y" to anchor.y, "fontSize" to run.style.fontSize)
    val strings =
      linkedMapOf(
        "text" to run.text,
        "align" to run.align.name.lowercase(),
        "baseline" to vegaBaseline(run.baseline),
        "font" to run.style.fontFamily,
      )
    node.fill?.let { fill ->
      solidColour(fill.paint)?.let { strings["fill"] = it.toCssHex() }
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
      val bounds = world.mapBounds(node.path.bounds)
      return Mark(
        "path",
        node.metadata.role,
        linkedMapOf("x" to bounds.left, "y" to bounds.top),
        emptyMap(),
      )
    }

    val vertices =
      node.path.commands.mapNotNull { command ->
        when (command) {
          is dev.aster.vega.scene.PathCommand.MoveTo -> world.apply(command.x, command.y)
          is dev.aster.vega.scene.PathCommand.LineTo -> world.apply(command.x, command.y)
          else -> null
        }
      }
    val strings = LinkedHashMap<String, String>()
    strings["points"] = vertices.joinToString(" ") { "${fmt(it.x)} ${fmt(it.y)}" }
    val numbers = LinkedHashMap<String, Double>()
    node.fill?.let { f ->
      solidColour(f.paint)?.let { strings["fill"] = it.toCssHex() }
      numbers["fillOpacity"] = f.opacity
    }
    node.stroke?.let { st ->
      solidColour(st.paint)?.let { strings["stroke"] = it.toCssHex() }
      numbers["strokeWidth"] = st.width
      numbers["strokeOpacity"] = st.opacity
    }
    return Mark(kind, node.metadata.role, numbers, strings)
  }

  private fun imageMark(node: ImageNode, world: Transform2D): Mark {
    val rect = node.rect
    val origin = world.apply(rect.left, rect.top)
    return Mark(
      "image",
      node.metadata.role,
      linkedMapOf("x" to origin.x, "y" to origin.y, "width" to rect.width, "height" to rect.height),
      linkedMapOf("url" to node.url),
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

  private fun paintNumbers(node: SceneNode): Map<String, Double> {
    val result = LinkedHashMap<String, Double>()
    val stroke =
      when (node) {
        is RectNode -> node.stroke
        is SymbolNode -> node.stroke
        is PathNode -> node.stroke
        else -> null
      }
    stroke?.let { result["strokeWidth"] = it.width }
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
    stroke?.let { s -> solidColour(s.paint)?.let { result["stroke"] = it.toCssHex() } }
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
      if (abs(wanted - got) > tolerance) {
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

  private val COLOUR_CHANNELS = setOf("fill", "stroke")
}
