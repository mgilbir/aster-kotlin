package dev.aster.vega.runtime.differential

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.canonicalNumberString
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.BinOrdinalScale
import dev.aster.vega.runtime.scale.IdentityScale
import dev.aster.vega.runtime.scale.LinearScale
import dev.aster.vega.runtime.scale.OrdinalScale
import dev.aster.vega.runtime.scale.PointScale
import dev.aster.vega.runtime.scale.QuantileScale
import dev.aster.vega.runtime.scale.QuantizeScale
import dev.aster.vega.runtime.scale.SequentialColorScale
import dev.aster.vega.runtime.scale.ThresholdScale
import dev.aster.vega.runtime.scale.TimeScale
import dev.aster.vega.runtime.scale.TransformedScale
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.FontStyle
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.ImageFit
import dev.aster.vega.scene.ImageNode
import dev.aster.vega.scene.PathNode
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.Scene
import dev.aster.vega.scene.SceneBlendMode
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.Stroke
import dev.aster.vega.scene.StrokeCap
import dev.aster.vega.scene.StrokeJoin
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
 * coordinates, and the comparison covers what ADR 0008 asks for: mark count, mark types,
 * coordinates, extents and scale outputs.
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
   * How close two colours have to be, as a fraction of a channel.
   *
   * Half of one 8-bit step. Upstream records a colour as text — hex for a scheme it was given,
   * `rgb(r, g, b)` for one an interpolator produced — and both forms quantise to whole bytes on the
   * way out, so two engines agreeing exactly still differ by up to half a step once one of them has
   * been through a string. Tighter than that compares the spelling; looser admits a colour a reader
   * could tell apart.
   */
  public const val COLOR_TOLERANCE: Double = 0.5 / 255.0

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
  /**
   * A gradient fill or stroke, in the one shape both sides can be put into.
   *
   * Upstream records one as an object — `{gradient, x1, y1, x2, y2, stops: [{color, offset}]}` —
   * and `readReference` kept only numbers and strings, so every gradient in the corpus was dropped
   * on the way in. This engine's side dropped them too, because `solidColour` answers null for
   * anything that is not a `Solid`, so no `fill` key was written either. Both silent, which is why
   * nothing ever failed: 20 gradients across 12 fixtures, and not one of them compared.
   *
   * The rect a legend ramp is painted on was compared all along. What is *inside* it was not.
   */
  public data class GradientReference(
    val kind: String,
    val coordinates: List<Double>,
    val stops: List<Pair<Double, String>>,
  ) {
    override fun toString(): String =
      "$kind(${coordinates.joinToString(",") { fmt(it) }}) " +
        stops.joinToString(" ") { "${fmt(it.first)}:${it.second}" }
  }

  public data class Mark(
    val type: String,
    val role: String?,
    val numbers: Map<String, Double>,
    val strings: Map<String, String>,
    /** By channel — `fill` or `stroke` — for the few marks painted with one. */
    val gradients: Map<String, GradientReference> = emptyMap(),
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
    /**
     * The scales a **named** group mark built for itself — a faceted one once per cell.
     *
     * Keyed by the group's name, and for a faceted group by the name plus its **facet key**:
     * `site[|"Waseca"|]`, the `groupby` values that made the cell. Absent from most references,
     * because most charts declare no scale inside a group, and absent for an **unnamed** group,
     * which has no key to record it under.
     *
     * The key is a facet key rather than a cell index because both engines build their cells into
     * an array and pairing by position mis-pairs the moment either reorders — a comparison against
     * the wrong cell, which is worse than no comparison. See [facetKeyOf].
     */
    val nestedScales: Map<String, Map<String, ScaleReference>> = emptyMap(),
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

  /**
   * Upstream's recorded gradient, normalised to [GradientReference].
   *
   * The coordinates are recorded only when upstream states them; a gradient with none is a legend
   * ramp that took the default, and both engines then use the same default, so an empty list
   * compares equal to an empty list rather than to a guess at what the default was.
   */
  private fun gradientReference(value: VegaValue.Obj): GradientReference {
    val kind = value.fields["gradient"]?.asString() ?: "linear"
    val coordinates = listOf("x1", "y1", "x2", "y2").mapNotNull { value.fields[it]?.asDouble() }
    val stops =
      (value.fields["stops"] as? VegaValue.Arr)?.values.orEmpty().mapNotNull { entry ->
        val stop = entry as? VegaValue.Obj ?: return@mapNotNull null
        val offset = stop.fields["offset"]?.asDouble() ?: return@mapNotNull null
        val colour = stop.fields["color"]?.asString() ?: return@mapNotNull null
        offset to colour
      }
    return GradientReference(kind, coordinates, stops)
  }

  /** This engine's gradient, in the same shape, or null where the paint is a plain colour. */
  private fun gradientOf(paint: dev.aster.vega.scene.ScenePaint): GradientReference? =
    when (paint) {
      is dev.aster.vega.scene.ScenePaint.LinearGradient ->
        GradientReference(
          "linear",
          listOf(paint.x1, paint.y1, paint.x2, paint.y2),
          paint.stops.map { it.offset to it.color.toCssRgb() },
        )
      is dev.aster.vega.scene.ScenePaint.RadialGradient ->
        GradientReference(
          "radial",
          listOf(paint.cx, paint.cy, paint.radius),
          paint.stops.map { it.offset to it.color.toCssRgb() },
        )
      else -> null
    }

  /**
   * A cell's facet key, in the recorder's spelling: `|"Waseca"|`.
   *
   * Mirrors `facetKey` in `oracle-js/src/normalize.js`, which writes `JSON.stringify` of each
   * `groupby` value after `scaleValue` has canonicalised it — a number stays a number and
   * everything else becomes a string. `canonicalNumberString` is the same rounding the recorder's
   * `canonicalNumber` applies, which is why it exists in `vega-model` rather than in either
   * harness.
   *
   * Null where the datum carries none of the `groupby` fields, so a cell is left unpaired rather
   * than paired under a key that means something else.
   */
  public fun facetKeyOf(datum: VegaValue, groupby: List<String>): String? {
    if (groupby.isEmpty()) return null
    val parts = mutableListOf<String>()
    for (field in groupby) {
      val value = (datum as? VegaValue.Obj)?.fields?.get(field) ?: return null
      parts +=
        when (value) {
          is VegaValue.Num -> canonicalNumberString(value.value)
          // `String(value)` in the recorder, then quoted by `JSON.stringify`. A boolean and a null
          // both become their own spelling, which is what `String()` does to them in JavaScript.
          is VegaValue.Str -> VegaJson.write(value)
          is VegaValue.Bool -> VegaJson.write(VegaValue.Str(value.value.toString()))
          is VegaValue.Null -> VegaJson.write(VegaValue.Str("null"))
          else -> return null
        }
    }
    return "|" + parts.joinToString("|") + "|"
  }

  /** One recorded scale, shared by the top-level reading and the nested one. */
  private fun scaleReference(value: VegaValue): ScaleReference {
    val obj = value as VegaValue.Obj
    return ScaleReference(
      domain =
        (obj.fields["domain"] as? VegaValue.Arr)?.values?.map { it.asString() } ?: emptyList(),
      range = (obj.fields["range"] as? VegaValue.Arr)?.values?.map { it.asString() } ?: emptyList(),
      bandwidth = obj.fields["bandwidth"]?.asDouble(),
      step = obj.fields["step"]?.asDouble(),
      ticks = (obj.fields["ticks"] as? VegaValue.Arr)?.values?.map { it.asDouble() },
    )
  }

  public fun readReference(file: File): Reference {
    require(file.isFile) {
      "Missing reference file ${file.path}. Regenerate it with ./scripts/oracle.sh"
    }
    val root = VegaJson.parse(file.readText()) as VegaValue.Obj
    val size = root.fields["size"] as VegaValue.Obj
    val scales =
      (root.fields["scales"] as? VegaValue.Obj)?.fields?.mapValues { (_, value) ->
        scaleReference(value)
      } ?: emptyMap()

    val marks =
      (root.fields["marks"] as? VegaValue.Arr)?.values?.map { markValue ->
        val obj = markValue as VegaValue.Obj
        val numbers = LinkedHashMap<String, Double>()
        val strings = LinkedHashMap<String, String>()
        val gradients = LinkedHashMap<String, GradientReference>()
        for ((key, value) in obj.fields) {
          if (key == "type" || key == "role") continue
          when (value) {
            is VegaValue.Num -> numbers[key] = value.value
            is VegaValue.Str -> strings[key] = value.value
            // A **gradient**, which used to fall into an `else -> Unit` and be lost.
            is VegaValue.Obj ->
              if (value.fields["gradient"] != null) gradients[key] = gradientReference(value)
            // A **boolean**, which is only ever `clip` in this corpus. This side records it as the
            // string "true", so reading it makes the two meet; they never did before.
            is VegaValue.Bool -> strings[key] = value.value.toString()
            // A **null** needs nothing here, and checking it was the one idea in this change that
            // turned out to be redundant: upstream records `stroke: null` on 298 marks and
            // `fill: null` on 24, and those are the only nulls in the corpus — both covered
            // already by the `COLOUR_CHANNELS` sweep below, which reports paint this side invents
            // where the reference has none. Worth the comment so the next reader does not add it
            // back.
            else -> Unit
          }
        }
        Mark(
          type = obj.fields["type"]!!.asString(),
          role = obj.fields["role"]?.takeIf { it !is VegaValue.Null }?.asString(),
          numbers = numbers,
          strings = strings,
          gradients = gradients,
        )
      } ?: emptyList()

    return Reference(
      vegaVersion = root.fields["vegaVersion"]?.asString() ?: "unknown",
      width = (size.fields["width"] ?: VegaValue.Num(0.0)).asDouble(),
      height = (size.fields["height"] ?: VegaValue.Num(0.0)).asDouble(),
      scales = scales,
      nestedScales =
        (root.fields["nestedScales"] as? VegaValue.Obj)?.fields?.mapValues { (_, group) ->
          (group as VegaValue.Obj).fields.mapValues { (_, value) -> scaleReference(value) }
        } ?: emptyMap(),
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
        if (node.fill != null || node.stroke != null) {
          out.add(withGradients(node, groupMark(node, world)))
        }
        node.children.forEach { collect(it, world, out) }
      }
      is RectNode -> out.add(withGradients(node, withOpacity(node, rectMark(node, world))))
      is RuleNode -> out.add(withGradients(node, withOpacity(node, ruleMark(node, world))))
      is TextNode -> out.add(withGradients(node, withOpacity(node, textMark(node, world))))
      is SymbolNode -> out.add(withGradients(node, withOpacity(node, symbolMark(node, world))))
      is PathNode -> out.add(withGradients(node, withOpacity(node, pathMark(node, world))))
      is ImageNode -> out.add(withGradients(node, withOpacity(node, imageMark(node, world))))
    }
  }

  /**
   * A node's own opacity, which is not the same channel as a fill's or a stroke's.
   *
   * Added centrally because it was missing from every mark type at once, and nothing else in the
   * comparison could see it: a legend swatch faded to 0.6 by `symbolOpacity` has the same geometry,
   * the same fill and the same stroke as one at full strength.
   */
  /**
   * The two things every mark carries whatever its shape: its opacity and its link.
   *
   * `href` lives here rather than in `paintStrings` because a text, rule or group mark does not go
   * through that — and those were exactly the marks whose links went uncompared when it did.
   */
  private fun withOpacity(node: SceneNode, mark: Mark): Mark {
    val strings = node.metadata.href?.let { mark.strings + ("href" to it) } ?: mark.strings
    return mark.copy(numbers = mark.numbers + ("opacity" to node.opacity), strings = strings)
  }

  /**
   * Attaches whatever gradient a node is painted with, for every node kind at once.
   *
   * One place rather than a line in each of the seven mark builders, and an **exhaustive** `when`
   * on purpose: a non-exhaustive one is exactly what hid the scale families and then hid these, so
   * a node kind added to the sealed hierarchy is a build error here rather than a channel that
   * quietly stops being compared. `ImageNode` is named and answers nothing, because it paints no
   * fill or stroke of its own — which is a statement, not an omission.
   */
  private fun withGradients(node: SceneNode, mark: Mark): Mark {
    val (fill, stroke) =
      when (node) {
        is GroupNode -> node.fill?.paint to node.stroke?.paint
        is RectNode -> node.fill?.paint to node.stroke?.paint
        is RuleNode -> null to node.stroke.paint
        is TextNode -> node.fill?.paint to node.stroke?.paint
        is SymbolNode -> node.fill?.paint to node.stroke?.paint
        is PathNode -> node.fill?.paint to node.stroke?.paint
        is ImageNode -> null to null
      }
    val gradients = LinkedHashMap<String, GradientReference>()
    fill?.let { gradientOf(it) }?.let { gradients["fill"] = it }
    stroke?.let { gradientOf(it) }?.let { gradients["stroke"] = it }
    return if (gradients.isEmpty()) mark else mark.copy(gradients = gradients)
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
    return Mark(
      "rect",
      node.metadata.role,
      numbers +
        cornerNumbers(
          node.cornerRadius,
          node.cornerRadiusTopLeft,
          node.cornerRadiusTopRight,
          node.cornerRadiusBottomRight,
          node.cornerRadiusBottomLeft,
        ) +
        paintNumbers(node),
      paintStrings(node),
    )
  }

  /**
   * A rectangle's corner radii as the specification declared them, each omitted when unset.
   *
   * Declared rather than drawn, because that is what upstream's scene item holds: its path
   * generator clamps a radius to `min(width, height) / 2` while drawing and never writes the
   * clamped value back. Comparing the declared numbers keeps the two sides describing the same
   * thing; that the clamp itself matches is pinned separately, against upstream's own path strings,
   * in `RectPathTest`.
   */
  private fun cornerNumbers(
    cornerRadius: Double,
    topLeft: Double?,
    topRight: Double?,
    bottomRight: Double?,
    bottomLeft: Double?,
  ): Map<String, Double> {
    val out = LinkedHashMap<String, Double>()
    if (cornerRadius != 0.0) out["cornerRadius"] = cornerRadius
    topLeft?.let { out["cornerRadiusTopLeft"] = it }
    topRight?.let { out["cornerRadiusTopRight"] = it }
    bottomRight?.let { out["cornerRadiusBottomRight"] = it }
    bottomLeft?.let { out["cornerRadiusBottomLeft"] = it }
    return out
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
      ) + (miterLimitOf(node.stroke)?.let { mapOf("strokeMiterLimit" to it) } ?: emptyMap())
    val strings = LinkedHashMap<String, String>()
    solidColour(node.stroke.paint)?.let { strings["stroke"] = it.toCssHex() }
    dashOf(node.stroke)?.let { strings["strokeDash"] = it }
    strokeDetails(node.stroke, strings)
    return Mark("rule", node.metadata.role, numbers, strings)
  }

  /**
   * A dash pattern as one comparable string, or null for a solid line.
   *
   * Compared because nothing else here can see it: a dashed gridline and a solid one agree on
   * position, colour, width and opacity, so leaving the dash out would let a chart pass the
   * comparison and draw differently — the same way a symbol's outline once did.
   */
  /**
   * A dash pattern, with its offset. The offset is part of the pattern: the same array started a
   * half-period along draws its gaps where the other draws its marks.
   */
  /**
   * The stroke details nothing else here can see: the cap, the join and the mitre limit.
   *
   * A round-capped rule is longer than a butt-capped one by its own width, and a mitre limit
   * decides whether a spike on a zig-zag comes to a point or is cut flat. Both were compared past
   * for as long as the comparison looked only at position, colour, width and opacity.
   *
   * Recorded only when they differ from the default. That is *not* the same rule upstream follows —
   * it records a cap whenever the specification set one, even to the default — so the two are
   * reconciled by [IMPLIED_BY_ABSENCE] rather than by recording a cap on every rule in the corpus.
   */
  /**
   * What this side means by leaving a channel out.
   *
   * Only where the absence is a *value* rather than an omission. A cap this engine does not record
   * is butt and a join it does not record is mitre, because [strokeDetails] drops them exactly when
   * they hold those values — so comparing upstream's explicit `butt` against this side's silence is
   * comparing two spellings of the same thing.
   *
   * Deliberately short. A channel added here stops being compared where upstream states the default
   * and this omits it, which is right for a cap and would be wrong for anything whose absence means
   * "nothing was drawn".
   */
  private val IMPLIED_BY_ABSENCE = mapOf("strokeCap" to "butt", "strokeJoin" to "miter")

  private fun strokeDetails(stroke: Stroke, into: MutableMap<String, String>) {
    if (stroke.cap != StrokeCap.BUTT) into["strokeCap"] = stroke.cap.name.lowercase()
    if (stroke.join != StrokeJoin.MITER) into["strokeJoin"] = stroke.join.name.lowercase()
  }

  /**
   * The mitre limit, as a **number**, which is the side of the comparison it has to be on.
   *
   * It used to be written into the string map beside the cap and the join, and it was therefore
   * never compared at all: `compareMark` looks a channel up in `actual.numbers` when upstream
   * recorded it there, and `normalize.js` records this one through `styleValue`, which leaves a
   * number a number. So every mitre limit read as "absent" on this side and the difference was
   * announced for a channel nobody had ever set — no fixture carried a non-default limit until one
   * did, and then five marks reported it at once.
   *
   * Null at the default, which is what upstream leaves off an item that never set it.
   */
  private fun miterLimitOf(stroke: Stroke): Double? =
    stroke.miterLimit.takeIf { it != Stroke.DEFAULT_MITER_LIMIT }

  private fun dashOf(stroke: Stroke): String? =
    stroke.dashArray
      .takeIf { it.isNotEmpty() }
      ?.let { dash ->
        dash.joinToString(",") { fmt(it) } +
          if (stroke.dashOffset != 0.0) " @${fmt(stroke.dashOffset)}" else ""
      }

  private fun textMark(node: TextNode, world: Transform2D): Mark {
    val anchor = world.apply(node.x, node.y)
    val run = node.layout.run
    val numbers =
      linkedMapOf(
        "x" to anchor.x,
        "y" to anchor.y,
        "fontSize" to run.style.fontSize,
        // Published to **both** maps, because upstream carries whatever the specification wrote:
        // `"bold"` arrives as a string and `800` as a number, and the comparison walks upstream's
        // channels. The string side canonicalises the keywords; this side needs no conversion.
        "fontWeight" to run.style.fontWeight.toDouble(),
        // Rotation is geometry, not styling: a quarter-turned axis title reads down the page.
        "angle" to node.angleDegrees,
      )
    val strings =
      linkedMapOf(
        "align" to run.align.name.lowercase(),
        "baseline" to vegaBaseline(run.baseline),
        "font" to run.style.fontFamily,
        // Compared as a **number**, because the two sides spell it differently: upstream carries
        // whatever the specification wrote — `bold`, `normal`, `800` — while this engine resolves
        // it to a weight at encode time. Canonicalising both is the comparison; excluding the
        // channel, as this harness used to, hid the fact that it was never mapped here either.
        "fontWeight" to run.style.fontWeight.toString(),
      )
    // An item with no text at all contributes no `text` property, exactly as upstream's does — its
    // formatter returned nothing. An item with an *empty* text still contributes one, so the two
    // stay distinguishable.
    if (!node.absent) strings["text"] = run.text
    if (run.style.fontStyle == FontStyle.ITALIC) strings["fontStyle"] = "italic"
    if (run.style.direction == dev.aster.vega.scene.TextDirection.RTL) strings["dir"] = "rtl"
    run.lineBreak?.let { strings["lineBreak"] = it }
    run.style.lineHeight?.let { numbers["lineHeight"] = it }
    node.fill?.let { fill ->
      solidColour(fill.paint)?.let { strings["fill"] = it.toCssHex() }
      numbers["fillOpacity"] = fill.opacity
    }
    // A text mark can be *stroked*, and this comparison could not see it — the seventh channel to
    // have been invisible here. A halo under a label on a busy background is exactly that stroke,
    // and a label drawn without one is unreadable while agreeing on every other channel.
    node.stroke?.let { stroke ->
      solidColour(stroke.paint)?.let { strings["stroke"] = it.toCssHex() }
      numbers["strokeWidth"] = stroke.width
      numbers["strokeOpacity"] = stroke.opacity
      dashOf(stroke)?.let { strings["strokeDash"] = it }
    }
    // `limit` decides whether a label reads "September" or "Sep…", and `ellipsis` decides how the
    // truncation looks. Both were compared past: the item's `text` is the untruncated string on
    // both
    // sides, so a wrong limit changed the drawing and nothing else.
    if (run.limit != 0.0) {
      numbers["limit"] = run.limit
      strings["ellipsis"] = run.ellipsis
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
  /**
   * The four extent channels, written the way upstream's `Bounds` writes them.
   *
   * An empty bounds is not geometry, and the two engines spell it differently. Upstream leaves its
   * `Bounds` at the `±Number.MAX_VALUE` it starts from, so the reference records a left of
   * 1.7976931348623157e308 and a width of `-Infinity` — the subtraction overflows. This engine has
   * an explicit empty rectangle instead. Written out upstream's way so the two agree on "nothing
   * here" rather than comparing two different spellings of it.
   *
   * It is not a rare case: a labelled donut draws a leader line for every label slot and only the
   * nine belonging to a slice have an outline, so two thirds of that mark is empty by design.
   */
  /**
   * A mark's drawn extent, in upstream's own spelling for a shape that drew nothing.
   *
   * [world] is applied here rather than by the caller because an **empty** rectangle must not be
   * mapped through it: the sentinel a cleared `Bounds` holds is `MAX_VALUE`, and putting that
   * through a translation gives a number that is no longer recognisably empty — which is how a
   * county with no outline came to report a width of zero on one side and an infinity on the other.
   */
  private fun extentChannels(bounds: RectD, world: Transform2D): Map<String, Double> =
    extentChannels(if (bounds.isEmpty) bounds else world.mapBounds(bounds))

  private fun extentChannels(bounds: RectD): Map<String, Double> =
    if (bounds.isEmpty) {
      linkedMapOf(
        "shapeLeft" to Double.MAX_VALUE,
        "shapeTop" to Double.MAX_VALUE,
        "shapeWidth" to Double.NEGATIVE_INFINITY,
        "shapeHeight" to Double.NEGATIVE_INFINITY,
      )
    } else {
      linkedMapOf(
        "shapeLeft" to bounds.left,
        "shapeTop" to bounds.top,
        "shapeWidth" to bounds.width,
        "shapeHeight" to bounds.height,
      )
    }

  private fun symbolMark(node: SymbolNode, world: Transform2D): Mark {
    val centre = world.apply(node.x, node.y)
    val numbers =
      linkedMapOf(
        "x" to centre.x,
        "y" to centre.y,
        "size" to node.size,
      ) + extentChannels(node.bounds, world)
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
      val numbers = LinkedHashMap(extentChannels(node.bounds, world))
      // A `path` mark also reports the anchor it was placed at, which upstream carries as the
      // item's own x and y — the outline itself is in the path string's coordinates.
      if (kind == "path") {
        val anchor = world.apply(0.0, 0.0)
        numbers["x"] = anchor.x
        numbers["y"] = anchor.y
      }
      return Mark(kind, node.metadata.role, numbers + paintNumbers(node), paintStrings(node))
    }

    // **Each vertex carries the command that produced it**, which is the whole of subpath
    // structure. `MoveTo` and `LineTo` were both flattened to a bare pair, so a line broken into
    // separate subpaths and a line drawn straight through the break produced the *identical* point
    // list — and a break is exactly what `defined: false` means. A regression joining across a gap
    // passed every assertion on both sides of this comparison, which is the shape of blind spot
    // worth naming: the model erased the thing the channel exists to express.
    //
    // The letters match `oracle-js/src/normalize.js`, which records the same three from d3's own
    // path context, so the two strings are comparable character for character.
    val vertices =
      node.path.commands.flatMap { command ->
        when (command) {
          is dev.aster.vega.scene.PathCommand.MoveTo ->
            listOf("M" to world.apply(command.x, command.y))
          is dev.aster.vega.scene.PathCommand.LineTo ->
            listOf("L" to world.apply(command.x, command.y))
          // A cubic's control points are part of the outline: two curves through the same anchors
          // are different shapes, and comparing anchors alone would not see it.
          is dev.aster.vega.scene.PathCommand.CubicTo ->
            listOf(
              "C" to world.apply(command.x1, command.y1),
              "C" to world.apply(command.x2, command.y2),
              "C" to world.apply(command.x, command.y),
            )
          else -> emptyList()
        }
      }
    val strings = LinkedHashMap<String, String>()
    strings["points"] =
      vertices.joinToString(" ") { (command, point) ->
        "$command ${fmt(point.x)} ${fmt(point.y)}"
      }
    node.metadata.interpolate?.let { strings["interpolate"] = it }
    val numbers = LinkedHashMap<String, Double>()
    node.metadata.tension?.let { numbers["tension"] = it }
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
      ) +
        (node.raster?.let {
          mapOf(
            "rasterWidth" to it.width.toDouble(),
            "rasterHeight" to it.height.toDouble(),
          )
        } ?: emptyMap()),
      linkedMapOf(
        "url" to node.url,
        "align" to node.align.name.lowercase(),
        "baseline" to node.baseline.name.lowercase(),
        // Two images with the same box can be drawn completely differently: `aspect: false`
        // stretches to the box where the default letterboxes inside it, and `smooth: false` asks
        // for
        // nearest-neighbour sampling.
        "aspect" to if (node.fit == ImageFit.CONTAIN) "fit" else "none",
        "smooth" to if (node.smooth) "smooth" else "none",
      ) +
        // The pixels, as a digest. An image mark is otherwise compared by its box alone, so a
        // heatmap that painted nothing would agree with one that painted the right thing in the
        // right place. Written as a string on both sides because the hash is 64-bit and JSON's
        // numbers are doubles.
        (node.raster?.let { mapOf("rasterDigest" to it.digest.toString()) } ?: emptyMap()),
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
      // A group can be dashed too — a legend's own outline is where it shows — and this was
      // reporting
      // only its colour.
      dashOf(s)?.let { strings["strokeDash"] = it }
    }
    val corners =
      cornerNumbers(
        node.cornerRadius,
        node.cornerRadiusTopLeft,
        node.cornerRadiusTopRight,
        node.cornerRadiusBottomRight,
        node.cornerRadiusBottomLeft,
      )
    // A clipped group hides whatever its children draw outside it, which no coordinate here
    // reveals.
    if (node.clip != null) strings["clip"] = "true"
    // A legend **entry** is a group with contents, which upstream gives the same `scope` role a
    // group mark's cell carries. This scene keeps them apart under its own name so the
    // accessibility
    // walk can tell a legend row from a plotting cell; upstream has no such distinction, so the
    // comparison reads them as one.
    val role = if (node.metadata.role == "legend-entry-item") "scope" else node.metadata.role
    return Mark("group", role, numbers + corners + paintNumbers(node), strings)
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
        // A group is a painted mark too: a tooltip is a translucent rounded box with its content
        // inside it, and nothing else in this comparison could see the translucency.
        is GroupNode -> node.fill
        else -> null
      }
    fill?.let { result["fillOpacity"] = it.opacity }
    val stroke =
      when (node) {
        is RectNode -> node.stroke
        is SymbolNode -> node.stroke
        is PathNode -> node.stroke
        is GroupNode -> node.stroke
        else -> null
      }
    stroke?.let {
      result["strokeWidth"] = it.width
      result["strokeOpacity"] = it.opacity
      miterLimitOf(it)?.let { limit -> result["strokeMiterLimit"] = limit }
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
      strokeDetails(s, result)
    }
    blendOf(node)?.let { result["blend"] = it }
    return result
  }

  /**
   * A blend mode, which changes every pixel a mark covers and was invisible to this comparison.
   *
   * Absent for `normal`, which is what upstream leaves off the item.
   */
  private fun blendOf(node: SceneNode): String? {
    val mode =
      when (node) {
        is RectNode -> node.blendMode
        is SymbolNode -> node.blendMode
        is PathNode -> node.blendMode
        is RuleNode -> node.blendMode
        is TextNode -> node.blendMode
        is ImageNode -> node.blendMode
        is GroupNode -> node.blendMode
      }
    if (mode == SceneBlendMode.NORMAL) return null
    // The scene spells them as enum constants; upstream carries the CSS keyword.
    return mode.name.lowercase().replace('_', '-')
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
        if (unpaintedStroke(expected, channel)) continue
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
    // The same both ways for corner radii: rounding a corner the reference leaves square changes
    // the outline, and iterating only the reference's channels would never see it.
    for (channel in CORNER_CHANNELS) {
      if (channel in ignored) continue
      val invented = actual.numbers[channel] ?: continue
      if (channel !in expected.numbers && abs(invented) > tolerance) {
        out.add(Difference("$where.$channel", "absent", fmt(invented)))
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
        // What this side must hold depends on which non-finite it is, and the two mean different
        // things:
        //
        // `NaN` is a channel upstream never set — `x2 - x` with both undefined — which its renderer
        // paints as zero. `interactive-legend`'s brush rect has `width: NaN` in the scene and draws
        // `M0,0h0v200h0Z` in upstream's own SVG. A scene node here holds numbers a renderer can use
        // rather than the arithmetic that produced them, so it holds that zero.
        //
        // An infinity is an **empty bounds**, and `extentChannels` writes this side's in upstream's
        // own spelling, so the two match exactly.
        val required = if (wanted == "NaN") 0.0 else wanted.toDouble()
        val held = actual.numbers[channel]
        // A `NaN` is accepted either way round, and the two spellings say the same thing. Where the
        // channel is an *extent* this engine holds the painted zero, because a scene node carries
        // numbers a renderer can use. Where it is a *position* that upstream never computed, it
        // holds the same absence: an axis's `tickExtra` label scales a value its datum does not
        // carry, and upstream's own SVG contains no element for it. Neither form is a relaxation —
        // a reference holding a real number still demands that number.
        val agrees = held != null && (held == required || (wanted == "NaN" && held.isNaN()))
        if (!agrees) {
          out.add(
            Difference(
              "$where.$channel",
              "$wanted (as ${fmt(required)})",
              held?.let(::fmt) ?: "absent",
            )
          )
        }
        continue
      }
      // A channel this side leaves absent **because it is the default** is not a difference: the
      // absence means that value. Upstream records a cap or a join whenever the specification set
      // one, even to the default, where this records one only when it differs — so the two agree
      // except where a specification explicitly writes the default, and there they disagreed for a
      // reason neither engine was wrong about. Found on a guide encode setting `strokeCap` to
      // `butt`: upstream wrote it, this left it out, and the comparison called that a difference.
      val got = actual.strings[channel] ?: IMPLIED_BY_ABSENCE[channel]
      if (got == null) {
        out.add(Difference("$where.$channel", wanted, "absent"))
        continue
      }
      val equal =
        when {
          // `bold` is 700 and `normal` is 400, which is what a renderer does with them.
          channel == "fontWeight" -> cssWeight(wanted) == cssWeight(got)
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
    compareGradients(where, expected, actual, out)
  }

  /**
   * The gradients a mark is painted with, which were compared nowhere at all.
   *
   * Both sides dropped them: `readReference` kept numbers and strings and let an object fall into
   * an `else -> Unit`, and this engine's extractor answered null for any paint that was not a
   * `Solid`, so no `fill` key was written either. Two silences that cancelled out — the rect a
   * legend ramp is painted on was compared all along, and what is *inside* it was not. Twenty
   * gradients across twelve fixtures, including every legend colour ramp in the corpus.
   *
   * Colours are compared as colours rather than as text, for the reason the scale ranges are:
   * upstream prints a stop as `rgb(r, g, b)` and a hex spelling of the same colour is the same
   * colour. Offsets carry the geometry tolerance, being arithmetic.
   */
  private fun compareGradients(
    where: String,
    expected: Mark,
    actual: Mark,
    out: MutableList<Difference>,
  ) {
    for ((channel, theirs) in expected.gradients) {
      val ours = actual.gradients[channel]
      if (ours == null) {
        out.add(Difference("$where.$channel gradient", theirs.toString(), "absent"))
        continue
      }
      if (theirs.kind != ours.kind) {
        out.add(Difference("$where.$channel gradient", theirs.kind, ours.kind))
        continue
      }
      // Upstream records the coordinates only where it states them; where it does not, both
      // engines take the same default and there is nothing to compare.
      if (theirs.coordinates.isNotEmpty()) {
        compareNumberList(
          "$where.$channel gradient",
          theirs.coordinates.map { fmt(it) },
          ours.coordinates,
          GEOMETRY_TOLERANCE,
          out,
        )
      }
      if (theirs.stops.size != ours.stops.size) {
        out.add(
          Difference(
            "$where.$channel gradient stops",
            theirs.stops.size.toString(),
            ours.stops.size.toString(),
          )
        )
        continue
      }
      theirs.stops.zip(ours.stops).forEachIndexed { index, (want, got) ->
        if (abs(want.first - got.first) > GEOMETRY_TOLERANCE) {
          out.add(
            Difference(
              "$where.$channel gradient stop[$index] offset",
              fmt(want.first),
              fmt(got.first),
            )
          )
        }
        val at = "$where.$channel gradient stop[$index]"
        if (!sameColour(want.second, got.second) && at !in GRADIENT_STOP_TIES) {
          out.add(Difference(at, want.second, got.second))
        }
      }
    }
    // A gradient this side paints where upstream painted none is an invention, reported the way an
    // invented channel is.
    for (channel in actual.gradients.keys - expected.gradients.keys) {
      out.add(
        Difference("$where.$channel gradient", "absent", actual.gradients.getValue(channel).kind)
      )
    }
  }

  /**
   * Gradient stops where the interpolation lands on an exact half-unit and the two engines break
   * the tie differently.
   *
   * Pinned by name rather than absorbed into [COLOR_TOLERANCE], which would have to widen to a
   * whole unit and would then accept a genuinely wrong stop anywhere in the corpus.
   *
   * The one case, measured rather than assumed. Both engines interpolate the **same** 31-colour
   * `viridis` palette piecewise in RGB — vega-scale's `palettes.js` and this engine's
   * `ColorSchemes` hold the identical hex string — and at offset 0.95 the stop falls exactly
   * halfway along segment 28, `#d2e21b` to `#e9e51a`. The blue channel is therefore 26.5 exactly.
   * `interpolateColors` on its own rounds that to 27, which is what this engine answers; a scale in
   * a live view answers 26. One unit in one channel of one stop, on a tie.
   *
   * A *new* entry here is a regression. Deleting this one without the comparison going red means
   * the tie now breaks the same way, and the entry should go.
   */
  private val GRADIENT_STOP_TIES = setOf("rect/legend-gradient[0].fill gradient stop[19]")

  /** Two colour spellings compared as colours; see [COLOR_TOLERANCE]. */
  private fun sameColour(expected: String, actual: String): Boolean {
    val wanted = SceneColor.parse(expected) ?: return expected == actual
    val got = SceneColor.parse(actual) ?: return expected == actual
    return abs(wanted.red - got.red) <= COLOR_TOLERANCE &&
      abs(wanted.green - got.green) <= COLOR_TOLERANCE &&
      abs(wanted.blue - got.blue) <= COLOR_TOLERANCE &&
      abs(wanted.alpha - got.alpha) <= COLOR_TOLERANCE
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
      // A radius the reference never set is a square corner, which is what this side draws when it
      // omits the channel. The reverse — a radius here and none upstream — is caught below.
      in CORNER_CHANNELS -> 0.0
      "strokeWidth" -> null // absence means no stroke at all, which is a real difference
      else -> null
    }

  /**
   * A stroke property the reference carries with no stroke **colour** beside it.
   *
   * Upstream's items are property bags, so a mark encoding `strokeWidth` and no `stroke` has a
   * width on it and paints no outline — its own SVG renderer emits neither attribute. A scene node
   * here holds what a renderer needs, so it has no stroke at all. The two describe the same
   * drawing, and an interactive Voronoi overlay is where it shows: every one of its 600 transparent
   * cells sets a hairline width it never uses.
   *
   * Narrow on purpose. A reference carrying a stroke colour still demands a stroke of that width.
   */
  private fun unpaintedStroke(expected: Mark, channel: String): Boolean =
    (channel == "strokeWidth" || channel == "strokeOpacity") &&
      !expected.strings.containsKey("stroke")

  /**
   * A continuous scale's three comparable facts, shared by every family that has them.
   *
   * One function rather than a branch apiece, because the last time these were written out
   * per-scale only `linear` got written: the others fell through to `else -> Unit` and went
   * unchecked for as long as the harness has existed.
   */
  private fun compareContinuous(
    name: String,
    reference: ScaleReference,
    domain: List<Double>,
    range: List<Double>,
    ticks: List<Double>,
    tolerance: Double,
    differences: MutableList<Difference>,
  ) {
    compareNumberList("scale $name domain", reference.domain, domain, tolerance, differences)
    compareNumberList("scale $name range", reference.range, range, tolerance, differences)
    reference.ticks?.let { wanted ->
      compareNumberList("scale $name ticks", wanted.map { fmt(it) }, ticks, tolerance, differences)
    }
  }

  /**
   * A scale range whose entries may be colours, compared as **colours** rather than as text.
   *
   * The formats genuinely differ and neither side is wrong: upstream records an ordinal scheme in
   * the hex it was defined in — `#1f77b4` — and an interpolated endpoint in the `rgb(255, 255,
   * 204)` its interpolator returns. Comparing the strings would report a difference between two
   * spellings of the same colour, so both sides are parsed and compared by component, and anything
   * that is not a colour falls back to exact text.
   */
  private fun compareRangeValues(
    name: String,
    expected: List<String>,
    actual: List<String>,
    out: MutableList<Difference>,
  ) {
    if (expected.size != actual.size) {
      out.add(
        Difference("scale $name range size", expected.size.toString(), actual.size.toString())
      )
      return
    }
    expected.zip(actual).forEachIndexed { index, (e, a) ->
      val theirs = SceneColor.parse(e)
      val ours = SceneColor.parse(a)
      val same =
        if (theirs != null && ours != null) {
          abs(theirs.red - ours.red) <= COLOR_TOLERANCE &&
            abs(theirs.green - ours.green) <= COLOR_TOLERANCE &&
            abs(theirs.blue - ours.blue) <= COLOR_TOLERANCE &&
            abs(theirs.alpha - ours.alpha) <= COLOR_TOLERANCE
        } else {
          e == a
        }
      if (!same) out.add(Difference("scale $name range[$index]", e, a))
    }
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
      // **No `else`**, and that is the point. This `when` used to end `else -> Unit`, and a third
      // of the corpus's scales fell into it — compared not at all, under a test called "scale
      // domains, ranges, bandwidth and ticks match upstream". Every kind is named now, so the
      // *compiler* refuses a scale family nobody compared: adding one to the sealed hierarchy
      // without a branch here is a build error rather than a silence. That is a stronger guarantee
      // than the coverage test that was written to watch the gap, and it is why that test now
      // asserts the total rather than policing a list of exemptions.
      when (scale) {
        // Every continuous position scale, not only `linear`. The `when` used to name `linear`,
        // `band` and `point` and fall to `else -> Unit`, so a third of the scales in the corpus
        // were compared *not at all* — no domain, no range, no ticks — under a test called "scale
        // domains, ranges, bandwidth and ticks match upstream". `log`, `pow`, `symlog` and `time`
        // all carry the same three facts as `linear` and are read the same way.
        is LinearScale ->
          compareContinuous(
            name,
            reference,
            scale.domain,
            scale.range,
            scale.ticks(),
            tolerance,
            differences,
          )
        is TimeScale ->
          compareContinuous(
            name,
            reference,
            scale.domain,
            scale.range,
            scale.ticks(),
            tolerance,
            differences,
          )
        is TransformedScale ->
          compareContinuous(
            name,
            reference,
            scale.domain,
            scale.range,
            scale.ticks(),
            tolerance,
            differences,
          )
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
        // An **ordinal** scale: a discrete domain and whatever the range holds, which for the
        // commonest case is a colour scheme. Recorded in full rather than only the entries the
        // chart happens to use, which is what makes it worth comparing directly — a scheme that
        // wrapped one colour early, or a domain in the wrong order, moves colours the mark
        // comparison only notices where a mark exists.
        is OrdinalScale -> {
          // `effectiveDomain`, not `domain`: an ordinal scale whose `domainImplicit` is set grows
          // its domain as values arrive, and upstream records the grown one — four entries where
          // the specification declared two. Reading the declared list reported
          // `[alpha, beta, gamma, delta]` against `[alpha, beta]` on `scale-domain-implicit`, which
          // is the comparison looking at the wrong property rather than the engine losing values:
          // the marks in that fixture have always matched.
          if (reference.domain != scale.effectiveDomain) {
            differences.add(
              Difference(
                "scale $name domain",
                reference.domain.toString(),
                scale.effectiveDomain.toString(),
              )
            )
          }
          compareRangeValues(
            name,
            reference.range,
            scale.rangeValues.map { it.asString() },
            differences,
          )
        }
        // A **sequential colour** scale: its numeric domain and its ticks, and deliberately not its
        // range.
        //
        // The two sides do not hold the same object there, and neither is wrong. Upstream's
        // `scale.range()` answers the *interpolator's* endpoints — two colours for `viridis` —
        // while
        // this engine keeps every stop of the scheme it was built from, which is 31 for `viridis`
        // and 64 for `turbo`. Comparing them reported "expected 2, got 31" on seventeen fixtures,
        // which is a difference of representation rather than of behaviour: the colour the scale
        // actually *produces* is compared wherever it is used, by the mark comparison, and its
        // interpolation directly by the `colour-ramps` and `colour-interpolation` fixtures.
        is SequentialColorScale -> {
          compareNumberList(
            "scale $name domain",
            reference.domain,
            scale.domain,
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
        // The four **discretizing** families and `identity`. Each records a numeric domain and a
        // range in the reference — the same two facts every other branch compares — so the reason
        // this class used to give for skipping them, that their boundaries were "recorded in a
        // different shape", was a guess and wrong. What differs is only which property holds the
        // domain: a quantile scale's is its sample of the data, a threshold scale's is its cuts.
        is BinOrdinalScale -> {
          compareNumberList(
            "scale $name domain",
            reference.domain,
            scale.domain,
            tolerance,
            differences,
          )
          compareRangeValues(
            name,
            reference.range,
            scale.rangeValues.map { it.asString() },
            differences,
          )
        }
        is QuantizeScale -> {
          compareNumberList(
            "scale $name domain",
            reference.domain,
            scale.domain,
            tolerance,
            differences,
          )
          compareRangeValues(
            name,
            reference.range,
            scale.rangeValues.map { it.asString() },
            differences,
          )
          // No ticks: a quantize scale has no tick generator of its own here, and upstream's
          // recorded ones are the linear ticks of its domain, which the axis beside it already
          // compares.
        }
        // A quantile scale's domain is the **data** it was given, which upstream records in full
        // and this engine keeps as `sampleDomain`.
        is QuantileScale -> {
          compareNumberList(
            "scale $name domain",
            reference.domain,
            scale.sampleDomain,
            tolerance,
            differences,
          )
          compareRangeValues(
            name,
            reference.range,
            scale.rangeValues.map { it.asString() },
            differences,
          )
        }
        // A threshold scale's domain is its **cuts**, one fewer than its range.
        is ThresholdScale -> {
          compareNumberList(
            "scale $name domain",
            reference.domain,
            scale.thresholds,
            tolerance,
            differences,
          )
          compareRangeValues(
            name,
            reference.range,
            scale.rangeValues.map { it.asString() },
            differences,
          )
        }
        is IdentityScale -> {
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
        }
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

  /** A CSS font weight as the number a renderer resolves it to. */
  private fun cssWeight(value: String): Int? =
    when (value.trim().lowercase()) {
      "normal" -> 400
      "bold" -> 700
      // `lighter` and `bolder` are relative to the parent and cannot be resolved from one item.
      "lighter",
      "bolder" -> null
      else -> value.trim().toDoubleOrNull()?.toInt()
    }

  private fun fmt(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

  /**
   * Channels excluded from comparison: **none**.
   *
   * This used to hold `font` and `fontWeight`, under the documented text-metrics exception (ADR
   * 0008, docs/adr/0006). That exception is real but it is about *measurement* — a browser and
   * Android size glyphs differently, so the width of a label is not comparable — and these two
   * channels are neither measured nor derived. They are the family and weight the specification
   * asked for, and this engine either resolves them the way upstream does or it does not.
   *
   * It did not. Comparing them found a `style` block's `font` leaking from a title into its
   * subtitle, and found that `fontWeight` had never been mapped into the comparison at all — it was
   * excluded *and* absent, so removing it from this set changed nothing until the channel was
   * published. Text bounds remain excluded, which is what the exception was for.
   */
  public val DEFAULT_IGNORED_CHANNELS: Set<String> = emptySet()

  /** Mark types whose drawn extent comes from a curve approximating a true circular arc. */
  private val CURVE_EXTENT_TYPES = setOf("arc", "trail", "path")

  private val COLOUR_CHANNELS = setOf("fill", "stroke")

  private val CORNER_CHANNELS =
    setOf(
      "cornerRadius",
      "cornerRadiusTopLeft",
      "cornerRadiusTopRight",
      "cornerRadiusBottomRight",
      "cornerRadiusBottomLeft",
    )

  /** The channels that carry a position or an extent, and so have a painted equivalent of zero. */
  private val GEOMETRY_CHANNELS =
    setOf(
      "x",
      "y",
      "x2",
      "y2",
      "width",
      "height",
      "size",
      "innerRadius",
      "outerRadius",
      "shapeLeft",
      "shapeTop",
      "shapeWidth",
      "shapeHeight",
    )

  /** What `canonicalNumber` writes where a number is not finite. */
  private val NON_FINITE = setOf("NaN", "Infinity", "-Infinity")
}
