package dev.aster.vega.runtime.compile

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionEvaluationException
import dev.aster.vega.expression.ExpressionResult
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import dev.aster.vega.model.isMissing
import dev.aster.vega.model.roundHalfUp
import dev.aster.vega.model.spec.ChannelValue
import dev.aster.vega.model.spec.EncodeEntry
import dev.aster.vega.model.spec.FieldRef
import dev.aster.vega.model.spec.MarkSpec
import dev.aster.vega.model.spec.MarkType
import dev.aster.vega.runtime.scale.PositionScale
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.AccessibilityDescriptor
import dev.aster.vega.scene.ArcPath
import dev.aster.vega.scene.CurveKind
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.FontStyle
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.ImageAlign
import dev.aster.vega.scene.ImageBaseline
import dev.aster.vega.scene.ImageFit
import dev.aster.vega.scene.ImageNode
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.NodeMetadata
import dev.aster.vega.scene.PathBuilder
import dev.aster.vega.scene.PathCommand
import dev.aster.vega.scene.PathData
import dev.aster.vega.scene.PathNode
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RasterImage
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.RectNode
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.SceneColor
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.ScenePaint
import dev.aster.vega.scene.SizeD
import dev.aster.vega.scene.Stroke
import dev.aster.vega.scene.StrokeCap
import dev.aster.vega.scene.StrokeJoin
import dev.aster.vega.scene.SvgPath
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.SymbolShape
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextBaseline
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.TextRun
import dev.aster.vega.scene.TextStyle
import dev.aster.vega.scene.TrailPath
import dev.aster.vega.scene.Transform2D
import dev.aster.vega.scene.curve
import dev.aster.vega.scene.spokenNumber
import kotlin.math.pow

/**
 * Turns a mark specification plus its data into scene nodes.
 *
 * Implemented: `rect`, `rule`, `symbol`, `text`, `line`, `area`, `arc`, and `group` through
 * [encodeGroup]. Every other type reports `VEGA_TRANSFORM_NOT_IMPLEMENTED` and contributes no
 * nodes, so a chart that needs one is visibly missing it rather than silently short.
 *
 * Channel resolution follows Vega: `enter` provides the base and `update` overrides it, a scaled
 * channel maps its field through the named scale, and `band` adds a fraction of the scale's
 * bandwidth — which is how `{"scale": "x", "band": 1}` expresses a bar's full-band width.
 *
 * `line` and `area` differ structurally from upstream by design: Vega emits one scenegraph item per
 * datum and lets the renderer connect them, while this produces a single [PathNode] carrying the
 * whole outline. The drawn result is the same; the differential harness normalizes both sides to a
 * point list so they can still be compared.
 */
public class MarkEncoder(
  private val scales: Map<String, VegaScale>,
  private val ids: SceneNodeIdAllocator,
  private val diagnostics: DiagnosticCollector,
  /** Signals and datasets expressions can read. */
  private val scope: SignalScope = SignalScope(emptyMap(), emptyMap()),
  private val expressions: ExpressionCompiler = CachingExpressionCompiler(VegaExpressionCompiler()),
  /** Measures text marks. Must be the engine the surface will draw with (docs/adr/0006). */
  private val textEngine: TextEngine = MetricTextEngine(),
  /**
   * The **enclosing group item's** extent, which `{"field": {"group": "width"}}` reads.
   *
   * Not the `width` signal, though the two agree at the top level and that is why reading the
   * signal survived so long. Upstream compiles the reference to `item.mark.group.width` — the size
   * the group item was *encoded* with — so a cell declaring `width: {signal: height}` gives a rule
   * that spans the cell rather than one that spans the chart.
   */
  private val groupExtent: PlotSize = PlotSize(0.0, 0.0),
) {

  /** `width` and `height` off the enclosing group item; anything else is a signal lookup. */
  private fun groupProperty(path: String): VegaValue =
    when (path) {
      "width" -> VegaValue.Num(groupExtent.width)
      "height" -> VegaValue.Num(groupExtent.height)
      else -> scope.signal(path)
    }

  /**
   * @param overrides what a mark's own `transform` wrote onto each item, per row.
   *
   * Upstream runs those transforms *after* the encoding and lets them write straight onto the item,
   * so whatever they set wins over whatever the encoding said — a `force` layout's whole purpose is
   * to replace the `x` and `y` a mark was encoded with. They arrive here as extra `update` channels
   * holding the values already resolved, which is the same statement in this engine's vocabulary.
   */
  public fun encode(
    spec: MarkSpec,
    data: List<VegaValue>,
    overrides: List<Map<String, VegaValue>> = emptyList(),
  ): List<SceneNode> {
    fun at(index: Int): MarkSpec {
      val written = overrides.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: return spec
      return spec.copy(
        encode =
          spec.encode.copy(
            update = spec.encode.update + written.mapValues { ChannelValue.Constant(it.value) }
          )
      )
    }
    return when (spec.type) {
      MarkType.RECT -> data.mapIndexedNotNull { index, datum -> rect(at(index), datum, index) }
      MarkType.RULE -> data.mapIndexedNotNull { index, datum -> rule(at(index), datum, index) }
      MarkType.SYMBOL -> data.mapIndexedNotNull { index, datum -> symbol(at(index), datum, index) }
      MarkType.TEXT -> data.mapIndexedNotNull { index, datum -> text(at(index), datum, index) }
      // One node for the whole series, not one per datum — and the `sort` therefore has to order
      // the *rows* here, since there are no items left to order afterwards.
      MarkType.LINE -> listOfNotNull(line(spec, ordered(spec, data)))
      MarkType.AREA -> listOfNotNull(area(spec, ordered(spec, data)))
      MarkType.ARC -> data.mapIndexedNotNull { index, datum -> arc(at(index), datum, index) }
      MarkType.PATH -> data.mapIndexedNotNull { index, datum -> path(at(index), datum, index) }
      MarkType.SHAPE ->
        data.mapIndexedNotNull { index, datum -> path(at(index), datum, index, "shape") }
      MarkType.TRAIL -> listOfNotNull(trail(spec, ordered(spec, data)))
      MarkType.IMAGE -> data.mapIndexedNotNull { index, datum -> image(at(index), datum, index) }
      else -> {
        diagnostics.error(
          DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
          "The '${spec.type.name.lowercase()}' mark encoder is not implemented; " +
            "${data.size} data row${if (data.size == 1) "" else "s"} produced no marks",
          operator = spec.name ?: spec.type.name.lowercase(),
        )
        emptyList()
      }
    }
  }

  /**
   * Resolves one channel to a number against a datum of the caller's making.
   *
   * For the guides, which encode items this class never builds: an axis label placed by `{"scale":
   * "x", "field": "value"}` needs exactly the channel resolution a mark's `x` gets — scales,
   * signals, conditionals, `mult` and `offset` — over a datum that is the tick rather than a row.
   * Exposing the resolution rather than copying it is the point; the copy is where the two would
   * drift.
   */
  public fun channelNumber(channel: ChannelValue, datum: VegaValue): Double? =
    position(channel, datum)

  /** The same, as text: a legend label read through a scale turns an id into a name. */
  public fun channelText(channel: ChannelValue, datum: VegaValue): String? = string(channel, datum)

  /**
   * The same, as a colour: an axis picking one gridline out of the rest by a condition on its tick.
   *
   * Resolved through the same machinery so the conditional, the signal and the scale lookup all
   * behave here as they do on a mark — the alternative being a second, smaller reader that agrees
   * until the day one of them is fixed.
   */
  public fun channelColor(channel: ChannelValue, datum: VegaValue): SceneColor? =
    string(channel, datum)?.let { SceneColor.parse(it) }

  /**
   * A mark's scene items, as data another mark can be drawn from.
   *
   * Vega marks share a namespace with datasets, so `"from": {"data": "category-line"}` names the
   * *items the line mark produced* rather than anything in the specification's `data`. Each item is
   * the mark's resolved channels plus `datum`, the row it came from — which is why a label reading
   * back a line says `datum.x` for the vertex and `datum.datum.value` for the number behind it.
   * Verified against upstream, whose items carry exactly the encoded channels and nothing else.
   *
   * This is a second pass over the same channels rather than a by-product of [encode], because a
   * line collapses its whole series into one node here and there would be no per-item anything left
   * to read. Diagnostics are not repeated: [encode] has already run over these channels, and a
   * channel that could not resolve is left off the item the same way upstream leaves it undefined.
   */
  public fun items(spec: MarkSpec, data: List<VegaValue>): List<VegaValue> {
    val channels = spec.encode.effective
    return data.map { datum ->
      val fields = LinkedHashMap<String, VegaValue>(channels.size + 1)
      for ((name, channel) in channels) {
        val value = channelValue(channel, datum)
        if (value != null && value !is VegaValue.Null) fields[name] = value
      }
      adjustSpatial(fields, channels, spec.type)
      fields["datum"] = datum
      VegaValue.Obj(fields)
    }
  }

  /**
   * Upstream's `adjustSpatial`, which is what puts an `x` on an item encoded only with `xc` or
   * `x2`.
   *
   * A `rule` is exempt — it keeps its `x2` as an endpoint and never gains a width — and the three
   * mark types with a box swap a start written past its end rather than carrying a negative extent.
   */
  private fun adjustSpatial(
    fields: LinkedHashMap<String, VegaValue>,
    channels: EncodeEntry,
    type: MarkType,
  ) {
    if (type == MarkType.RULE) return
    val swap = type == MarkType.GROUP || type == MarkType.IMAGE || type == MarkType.RECT
    fun read(name: String): Double? = fields[name]?.asDouble()?.takeIf { !it.isNaN() }
    fun put(name: String, value: Double) {
      fields[name] = VegaValue.Num(value)
    }

    for ((start, end, centre, extent) in SPATIAL_AXES) {
      if (channels.containsKey(end)) {
        if (channels.containsKey(start)) {
          var near = read(start)
          var far = read(end)
          if (swap && near != null && far != null && near > far) {
            val held = near
            near = far
            far = held
            put(start, near)
            put(end, far)
          }
          if (near != null && far != null) put(extent, far - near)
        } else {
          read(end)?.let { put(start, it - (read(extent) ?: 0.0)) }
        }
      }
      if (channels.containsKey(centre)) {
        read(centre)?.let { put(start, it - (read(extent) ?: 0.0) / 2) }
      }
    }
  }

  /** One axis of the spatial adjustment: its start, end, centre and extent channel names. */
  private data class SpatialAxis(
    val start: String,
    val end: String,
    val centre: String,
    val extent: String,
  )

  /**
   * Encodes a group mark: one translated container per datum, holding whatever [contents] builds.
   *
   * The nested scene is a callback rather than a parameter because building it needs the group's
   * own scope — its data, signals, scales and axes — and that belongs to the compiler. What stays
   * here is only what a group has in common with every other mark: resolving its channels against
   * its datum.
   *
   * The group's own channels are resolved in the *enclosing* scope, since that is where the scales
   * positioning it live; [contents] is what sees the scope inside.
   */
  public fun encodeGroup(
    spec: MarkSpec,
    data: List<VegaValue>,
    contents: (datum: VegaValue, index: Int, extent: SizeD) -> List<SceneNode>,
  ): List<SceneNode> {
    val channels = spec.encode.effective
    return data.mapIndexed { index, datum ->
      val style = style(channels, datum, spec)
      // Upstream treats an unset dimension as zero once either is given, and gives a group with
      // neither no extent at all — it then paints nothing and measures only its children.
      val width = position(channels["width"], datum)
      val height = position(channels["height"], datum)
      // A group has an extent, so a centre channel halves it — the same adjustment a rect gets.
      val x =
        position(channels["xc"], datum)?.minus((width ?: 0.0) / 2)
          ?: position(channels["x"], datum)
          ?: 0.0
      val y =
        position(channels["yc"], datum)?.minus((height ?: 0.0) / 2)
          ?: position(channels["y"], datum)
          ?: 0.0
      val size = if (width == null && height == null) null else SizeD(width ?: 0.0, height ?: 0.0)
      val extent = size ?: SizeD(0.0, 0.0)

      GroupNode(
        id = ids.allocate(),
        children = contents(datum, index, extent),
        transform = if (x == 0.0 && y == 0.0) Transform2D.Identity else Transform2D.translate(x, y),
        size = size,
        cornerRadius =
          number(channels["cornerRadius"], datum) ?: MarkConfig(spec).number("cornerRadius") ?: 0.0,
        clip = if (spec.clip) RectD(0.0, 0.0, extent.width, extent.height) else null,
        fill = style.fill,
        stroke = style.stroke,
        opacity = style.opacity,
        // Upstream calls a group mark's role "scope", distinguishing it from the guide groups that
        // hold axes and legends.
        metadata = metadata(spec, datum, index, channels).copy(role = "scope"),
      )
    }
  }

  private fun rect(spec: MarkSpec, datum: VegaValue, index: Int): SceneNode? {
    val channels = spec.encode.effective
    if (channels.isEmpty()) return null

    // Vega accepts x/x2, x/width, or x2/width for each axis; resolve whichever pair is present.
    val horizontal = resolveSpan(channels, datum, "x", "x2", "width", "xc", index, spec)
    val vertical = resolveSpan(channels, datum, "y", "y2", "height", "yc", index, spec)
    if (horizontal == null || vertical == null) return null

    val style = style(channels, datum, spec)
    val cornerRadius =
      number(channels["cornerRadius"], datum) ?: MarkConfig(spec).number("cornerRadius") ?: 0.0

    return RectNode(
      id = ids.allocate(),
      x = horizontal.start,
      y = vertical.start,
      width = horizontal.extent,
      height = vertical.extent,
      cornerRadius = cornerRadius,
      fill = style.fill,
      stroke = style.stroke,
      opacity = style.opacity,
      metadata = metadata(spec, datum, index, channels),
    )
  }

  /**
   * `trail`: a line whose thickness follows the data.
   *
   * One filled node for the whole series, like a line — but filled rather than stroked, because a
   * stroke has one width and the whole point here is that it does not. A `null` in the data breaks
   * the trail the same way it breaks a line.
   */
  private fun trail(spec: MarkSpec, data: List<VegaValue>): SceneNode? {
    if (data.isEmpty()) return null
    val channels = spec.encode.effective
    val segments =
      segments(data, channels) { datum ->
        point(channels, datum) to
          (number(channels["size"], datum) ?: MarkConfig(spec).number("size") ?: 1.0)
      }
    if (segments.isEmpty()) return null

    val style = style(channels, data.first(), spec)
    val path = PathData(segments.flatMap { TrailPath.build(it).commands })
    if (path.isEmpty) return null
    return PathNode(
      id = ids.allocate(),
      path = path,
      fill = style.fill ?: Fill.of(MarkDefaults.DEFAULT_FILL),
      stroke = style.stroke,
      opacity = style.opacity,
      metadata = metadata(spec, data.first(), 0, channels).copy(markKind = "trail"),
    )
  }

  /**
   * `path`: an outline written out as an SVG path string, drawn at the mark's own position.
   *
   * The string is in its own coordinates and the mark's `x`/`y` translate it, so the same outline
   * can be placed once per datum. `scaleX`, `scaleY` and `angle` transform it about that anchor.
   */
  private fun path(
    spec: MarkSpec,
    datum: VegaValue,
    index: Int,
    /**
     * `path` for a path mark and `shape` for a shape mark.
     *
     * The two are the same node with the outline read from a different place. Upstream's `shape`
     * mark holds a *generator* the renderer calls; here a `geoshape` transform has already run it,
     * so what is on the item is the same string a path mark would carry.
     */
    channel: String = "path",
  ): SceneNode? {
    val channels = spec.encode.effective
    // A `path` channel that resolves to nothing still makes an item, as upstream does — it simply
    // has no outline and paints nothing. A labelled donut relies on it: its leader lines are drawn
    // from the same 33 label slots as the labels, and only the nine that belong to a slice carry a
    // path, so dropping the empty ones loses two thirds of the mark.
    // A mark-level `geopath` writes the outline onto the *row* rather than through an encode
    // channel — upstream writes it onto the scene item and its renderer reads the item's own
    // `path` — so a mark with no `path` channel falls back to the row's own column.
    val declared = channels[channel]?.let { value(it, datum) } ?: datum.field(channel)
    val source = (declared as? VegaValue.Str)?.value ?: ""
    // A path the specification never produced at all measures as a zero rectangle rather than as
    // nothing; see [PathNode.absent].
    val absent = declared.isMissing
    val parsed = SvgPath.parse(source)
    if (source.isNotEmpty() && !parsed.complete) {
      reportOnce(
        "path:$source",
        dev.aster.vega.model.VegaDiagnostic(
          severity = dev.aster.vega.model.DiagnosticSeverity.WARNING,
          code = DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          message =
            "Could not read all of the path '$source'; the outline stops where the reading did",
          operator = spec.name,
        ),
      )
    }
    val x = centred(channels, datum, "x", "xc") ?: 0.0
    val y = centred(channels, datum, "y", "yc") ?: 0.0
    val scaleX = number(channels["scaleX"], datum) ?: 1.0
    val scaleY = number(channels["scaleY"], datum) ?: 1.0
    val angle = number(channels["angle"], datum) ?: 0.0
    val style = style(channels, datum, spec)

    var transform = Transform2D.translate(x, y)
    if (angle != 0.0) transform = transform.concat(Transform2D.rotateDegrees(angle))
    if (scaleX != 1.0 || scaleY != 1.0) {
      transform = transform.concat(Transform2D.scale(scaleX, scaleY))
    }
    return PathNode(
      id = ids.allocate(),
      path = parsed.path,
      absent = absent,
      transform = transform,
      fill = style.fill,
      stroke = style.stroke,
      opacity = style.opacity,
      // `path` or `shape`, whichever the specification wrote: the node is the same and the name
      // is not, and a comparison that could not tell them apart would be a weaker one.
      metadata = metadata(spec, datum, index, channels).copy(markKind = channel),
    )
  }

  /**
   * `arc`: an annular sector, which is what a pie or donut chart is made of.
   *
   * Angles run clockwise from twelve o'clock, so a slice starting at zero begins straight up. A
   * zero inner radius makes a wedge rather than a ring, and the outline is closed differently in
   * the two cases — a wedge returns through the centre, a ring runs back along the inner edge.
   *
   * `padAngle` and `cornerRadius` are d3's own geometry, ported in [ArcPath] rather than
   * approximated — a padded arc is inset against a pad *radius* and its corners are rounded with
   * tangent circles, neither of which is what the property names suggest.
   */
  private fun arc(spec: MarkSpec, datum: VegaValue, index: Int): SceneNode? {
    val channels = spec.encode.effective
    val cx = centred(channels, datum, "x", "xc") ?: 0.0
    val cy = centred(channels, datum, "y", "yc") ?: 0.0
    val startAngle = number(channels["startAngle"], datum) ?: 0.0
    val endAngle = number(channels["endAngle"], datum) ?: 0.0
    val innerRadius = number(channels["innerRadius"], datum) ?: 0.0
    val outerRadius = number(channels["outerRadius"], datum) ?: 0.0
    if (outerRadius <= 0.0 || startAngle == endAngle) return null

    val config = MarkConfig(spec)
    val style = style(channels, datum, spec)
    val path =
      ArcPath.build(
        centreX = cx,
        centreY = cy,
        innerRadius = innerRadius,
        outerRadius = outerRadius,
        startAngle = startAngle,
        endAngle = endAngle,
        padAngle = number(channels["padAngle"], datum) ?: config.number("padAngle") ?: 0.0,
        cornerRadius =
          number(channels["cornerRadius"], datum) ?: config.number("cornerRadius") ?: 0.0,
        padRadius = number(channels["padRadius"], datum) ?: config.number("padRadius"),
      )
    return PathNode(
      id = ids.allocate(),
      path = path,
      // No fallback fill. The pairing rule in `style` has already decided: a mark that encodes
      // *either* paint channel gets neither default, so an arc drawn as a bare outline — Vega's
      // Monte Carlo quadrant is one — stays unfilled instead of being flooded with the built-in
      // blue.
      fill = style.fill,
      stroke = style.stroke,
      opacity = style.opacity,
      metadata = metadata(spec, datum, index, channels).copy(markKind = "arc"),
    )
  }

  // ---- the other mark types --------------------------------------------------

  private fun rule(spec: MarkSpec, datum: VegaValue, index: Int): SceneNode? {
    val channels = spec.encode.effective
    val x1 = position(channels["x"], datum) ?: 0.0
    val y1 = position(channels["y"], datum) ?: 0.0
    // Vega defaults a missing x2/y2 to the corresponding x/y, making a zero-length rule.
    val x2 = position(channels["x2"], datum) ?: x1
    val y2 = position(channels["y2"], datum) ?: y1
    val style = style(channels, datum, spec)
    val stroke = style.stroke ?: return null
    return RuleNode(
      id = ids.allocate(),
      x1 = x1,
      y1 = y1,
      x2 = x2,
      y2 = y2,
      stroke = stroke,
      opacity = style.opacity,
      metadata = metadata(spec, datum, index, channels),
    )
  }

  private fun symbol(spec: MarkSpec, datum: VegaValue, index: Int): SceneNode? {
    val channels = spec.encode.effective
    val x = centred(channels, datum, "x", "xc") ?: 0.0
    val y = centred(channels, datum, "y", "yc") ?: 0.0
    val style = style(channels, datum, spec)
    val shapeName = string(channels["shape"], datum)
    val shape = shapeName?.let { symbolShape(it, spec) }
    // Anything that is not one of the twelve names is an SVG path string — which is how a
    // specification asks for a shape upstream does not ship.
    val outline = if (shape == null && shapeName != null) customShape(shapeName, spec) else null

    return SymbolNode(
      id = ids.allocate(),
      // The positions resolved above, not a second read of the raw channels: a symbol placed by
      // `xc` has no `x` channel to read.
      x = x,
      y = y,
      size =
        number(channels["size"], datum)
          ?: MarkConfig(spec).number("size")
          ?: MarkDefaults.SYMBOL_SIZE,
      shape = shape ?: SymbolShape.CIRCLE,
      customPath = outline,
      angleDegrees = number(channels["angle"], datum) ?: 0.0,
      fill = style.fill,
      stroke = style.stroke,
      opacity = style.opacity,
      metadata = metadata(spec, datum, index, channels),
    )
  }

  /**
   * A symbol outline written as an SVG path string.
   *
   * Returns null, and reports, when the string cannot be read — a circle in its place is at least a
   * mark the reader can see and ask about, where nothing at all would look like missing data.
   */
  private fun customShape(source: String, spec: MarkSpec): PathData? {
    val parsed = SvgPath.parse(source)
    if (!parsed.complete || parsed.path.isEmpty) {
      reportOnce(
        "shape:$source",
        dev.aster.vega.model.VegaDiagnostic(
          severity = dev.aster.vega.model.DiagnosticSeverity.WARNING,
          code = DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          message =
            "Symbol shape '$source' is neither one of the twelve names nor a path this engine " +
              "could read; a circle was drawn instead",
          operator = spec.name,
        ),
      )
      return null
    }
    return parsed.path
  }

  /** Maps Vega's shape names onto the built-in set; anything else is a path string. */
  private fun symbolShape(name: String, spec: MarkSpec): SymbolShape? {
    val shape =
      when (name.lowercase()) {
        "circle" -> SymbolShape.CIRCLE
        "square" -> SymbolShape.SQUARE
        "cross" -> SymbolShape.CROSS
        "diamond" -> SymbolShape.DIAMOND
        "triangle-up" -> SymbolShape.TRIANGLE_UP
        // Not a synonym for triangle-up: upstream's plain `triangle` balances on its centroid.
        "triangle" -> SymbolShape.TRIANGLE
        "triangle-down" -> SymbolShape.TRIANGLE_DOWN
        "triangle-left" -> SymbolShape.TRIANGLE_LEFT
        "triangle-right" -> SymbolShape.TRIANGLE_RIGHT
        "stroke" -> SymbolShape.STROKE
        "arrow" -> SymbolShape.ARROW
        "wedge" -> SymbolShape.WEDGE
        else -> null
      }
    return shape
  }

  private fun text(spec: MarkSpec, datum: VegaValue, index: Int): SceneNode? {
    val channels = spec.encode.effective
    // An axis the specification never mentions is the origin, not a reason to drop the mark:
    // upstream's `anchorPoint` reads `item.x || 0`, and a heading written as `{"y": -5}` with no
    // `x` is drawn hard against the left of the group it is in. Every other mark encoder here
    // already defaults the same way.
    val anchorX = centred(channels, datum, "x", "xc") ?: 0.0
    val anchorY = centred(channels, datum, "y", "yc") ?: 0.0
    // Upstream's `textValue`: `line == null ? '' : (line + '').trim()`. Both halves matter. A text
    // channel that resolves to nothing draws *nothing* — this engine was stringifying the null and
    // printing the word "null", which the mark comparison cannot see because a text mark is
    // compared
    // by its anchor rather than its content. Vega's labelled donut lays out 33 label slots and
    // fills
    // only the nine that belong to a slice, so it printed "null" twenty-four times.
    val content = textOf(channels["text"], datum)
    val angle = number(channels["angle"], datum) ?: 0.0
    // `dx` and `dy` shift the anchor without affecting alignment — but for rotated text upstream
    // applies them *after* the rotation, so an offset runs along the text rather than along the
    // page.
    // Rotating them here keeps the anchor as the point the text turns about, which is what it is.
    // Every one of these falls back to the mark's own configuration and to the **style blocks** it
    // names — a label styled `{align: "left", baseline: "middle", dx: 3}` says nothing in its
    // encoding, and reading only the encoding left it centred on its anchor with no nudge at all.
    val markConfig = MarkConfig(spec)
    val nudge =
      PointD(
        number(channels["dx"], datum) ?: markConfig.number("dx") ?: 0.0,
        number(channels["dy"], datum) ?: markConfig.number("dy") ?: 0.0,
      )
    val offset =
      if (angle == 0.0) nudge else Transform2D.rotateDegrees(angle).apply(nudge.x, nudge.y)
    val style = style(channels, datum, spec)

    val textStyle =
      TextStyle(
        fontFamily =
          string(channels["font"], datum)
            ?: markConfig.text("font")
            ?: MarkDefaults.TEXT_FONT_FAMILY,
        fontSize =
          number(channels["fontSize"], datum)
            ?: markConfig.number("fontSize")
            ?: MarkDefaults.TEXT_FONT_SIZE,
        fontWeight = fontWeight(channels, datum),
        fontStyle =
          if (string(channels["fontStyle"], datum)?.equals("italic", ignoreCase = true) == true) {
            FontStyle.ITALIC
          } else {
            FontStyle.NORMAL
          },
      )
    val run =
      TextRun(
        text = content,
        style = textStyle,
        align = textAlign(string(channels["align"], datum) ?: markConfig.text("align")),
        baseline = textBaseline(string(channels["baseline"], datum) ?: markConfig.text("baseline")),
      )

    return TextNode(
      id = ids.allocate(),
      x = anchorX + offset.x,
      y = anchorY + offset.y,
      layout = textEngine.layout(run),
      // No fallback fill, for the same reason the arc has none: `style` has already applied the
      // pairing rule, so a label drawn as a bare outline stays one. And a text mark can be
      // *stroked* — a halo under a label on a busy background is written that way, and dropping the
      // stroke left the label unreadable in exactly the charts that asked for it.
      fill = style.fill,
      stroke = style.stroke,
      angleDegrees = angle,
      opacity = style.opacity,
      metadata = metadata(spec, datum, index, channels),
    )
  }

  /**
   * `line`: one path through every datum's position.
   *
   * A datum whose position cannot be resolved breaks the line, matching Vega's `defined` behaviour
   * — the series resumes rather than interpolating across the gap.
   */
  private fun line(spec: MarkSpec, data: List<VegaValue>): SceneNode? {
    if (data.isEmpty()) return null
    val channels = spec.encode.effective
    val segments = segments(data, channels) { datum -> point(channels, datum) }
    if (segments.isEmpty()) return null

    val style = style(channels, data.first(), spec)
    val interpolate = string(channels["interpolate"], data.first())
    reportUnsupportedInterpolation(interpolate, spec)

    val horizontal = string(channels["orient"], data.first())?.lowercase() == "horizontal"
    val path = PathData.build { segments.forEach { trace(it, interpolate, horizontal) } }
    return PathNode(
      id = ids.allocate(),
      path = path,
      stroke =
        style.stroke
          ?: Stroke(ScenePaint.Solid(MarkDefaults.DEFAULT_FILL), MarkDefaults.LINE_STROKE_WIDTH),
      // A line is stroked by default but is not *only* strokeable: upstream fills the path whenever
      // the mark encodes a fill, which is what shades the inside of a closed radar polygon. There
      // is
      // no default fill for a line, so an ordinary series is unaffected.
      fill = style.fill,
      opacity = style.opacity,
      metadata = metadata(spec, data.first(), 0, channels).copy(interpolate = interpolate),
    )
  }

  /**
   * `area`: one filled path bounded by the `y`/`y2` pair, or `x`/`x2` when horizontal.
   *
   * The outline runs forward along one boundary and back along the other, which is what makes it a
   * closed region rather than two lines.
   */
  private fun area(spec: MarkSpec, data: List<VegaValue>): SceneNode? {
    if (data.isEmpty()) return null
    val channels = spec.encode.effective

    // `orient` needs no special case: building each boundary from the (x, y) and (x2, y2) pairs
    // handles both orientations, because a vertical area leaves x2 defaulting to x and a horizontal
    // one leaves y2 defaulting to y.
    val pairs =
      segments(data, channels) { datum ->
        val x = centred(channels, datum, "x", "xc") ?: 0.0
        val y = centred(channels, datum, "y", "yc") ?: 0.0
        val x2 = position(channels["x2"], datum) ?: x
        val y2 = position(channels["y2"], datum) ?: y
        PointD(x, y) to PointD(x2, y2)
      }
    if (pairs.isEmpty()) return null

    val style = style(channels, data.first(), spec)
    val interpolate = string(channels["interpolate"], data.first())
    val horizontal = string(channels["orient"], data.first())?.lowercase() == "horizontal"
    reportUnsupportedInterpolation(interpolate, spec)

    val path = PathData.build {
      for (segment in pairs) {
        // Forward along the primary boundary, back along the secondary one, then close. The
        // return leg steps the opposite way round, so a staircase area closes flush against
        // itself rather than crossing.
        trace(segment.map { it.first }, interpolate, horizontal)
        traceOnward(segment.reversed().map { it.second }, mirrored(interpolate), horizontal)
        close()
      }
    }
    return PathNode(
      id = ids.allocate(),
      path = path,
      fill = style.fill ?: Fill.of(MarkDefaults.DEFAULT_FILL),
      stroke = style.stroke,
      opacity = style.opacity,
      metadata = metadata(spec, data.first(), 0, channels).copy(interpolate = interpolate),
    )
  }

  /**
   * A series point.
   *
   * An unresolvable coordinate becomes zero rather than a gap. That is upstream's behaviour and it
   * surprises people: a `null` in the data does **not** break a Vega line, because the renderer
   * reads the coordinate as `item.y || 0` and draws straight through the axis. Breaking a series is
   * what the `defined` channel is for, and [broken] handles that.
   */
  private fun point(channels: EncodeEntry, datum: VegaValue): PointD =
    PointD(centred(channels, datum, "x", "xc") ?: 0.0, centred(channels, datum, "y", "yc") ?: 0.0)

  /** True when this datum's `defined` channel says the series should break here. */
  private fun broken(channels: EncodeEntry, datum: VegaValue): Boolean {
    val channel = channels["defined"] ?: return false
    val value = boolean(channel, datum) ?: return false
    return !value
  }

  /**
   * Splits data into runs of consecutive defined points, so `defined: false` breaks the series.
   *
   * A run of **one** point is kept. It draws nothing — a path needs two points to have a length —
   * but the mark it belongs to is still a mark: a series filtered down to a single row, or one
   * bracketed by two breaks, is present in the scene and carries its colour into the legend.
   * Dropping the run dropped the whole item with it whenever no run had two points.
   */
  private fun <T> segments(
    data: List<VegaValue>,
    channels: EncodeEntry,
    resolve: (VegaValue) -> T,
  ): List<List<T>> {
    val result = mutableListOf<List<T>>()
    var current = mutableListOf<T>()
    for (datum in data) {
      if (broken(channels, datum)) {
        // The point that broke the series is a subpath of its own. Nothing is drawn to it or from
        // it — a single point has no length — but it is still one of the series' points, and a
        // break is exactly the absence of a line *between* two of them.
        if (current.isNotEmpty()) result.add(current)
        result.add(listOf(resolve(datum)))
        current = mutableListOf()
      } else {
        current.add(resolve(datum))
      }
    }
    if (current.isNotEmpty()) result.add(current)
    return result
  }

  /** Resolves a channel to a boolean, following the same rules as the numeric resolution. */
  private fun boolean(channel: ChannelValue?, datum: VegaValue): Boolean? =
    when (channel) {
      null -> null
      is ChannelValue.Constant -> JsSemantics.truthy(channel.value)
      is ChannelValue.Field -> JsSemantics.truthy(datum.fieldOf(channel.ref))
      is ChannelValue.Signal ->
        evaluateExpression(channel.expression, datum)?.let { JsSemantics.truthy(it) }
      is ChannelValue.Conditional -> boolean(selectRule(channel, datum), datum)
      is ChannelValue.Adjusted -> adjusted(channel, datum)?.let { JsSemantics.truthy(it) }
      is ChannelValue.Scaled -> {
        val scale = scales[scaleNameOf(channel, datum)]
        val input = scaledInput(channel, datum)
        if (scale != null && input != null) JsSemantics.truthy(scale.scale(input)) else null
      }
    }

  /** Traces a segment with whatever interpolation the specification asked for. */
  private fun PathBuilder.trace(points: List<PointD>, method: String?, horizontal: Boolean) {
    curve(points, CurveKind.fromName(method) ?: CurveKind.LINEAR, horizontal)
  }

  /**
   * The same, joining onto the path already under construction rather than starting a subpath.
   *
   * Every curve opens with a `moveTo`, which would break an area's outline in two; that leading
   * command becomes a line to the same place and the rest is copied through unchanged.
   */
  private fun PathBuilder.traceOnward(points: List<PointD>, method: String?, horizontal: Boolean) {
    val kind = CurveKind.fromName(method) ?: CurveKind.LINEAR
    if (kind == CurveKind.LINEAR) {
      points.forEach { lineTo(it.x, it.y) }
      return
    }
    val tail = PathData.build { curve(points, kind, horizontal) }
    for (command in tail.commands) {
      when (command) {
        is PathCommand.MoveTo -> lineTo(command.x, command.y)
        is PathCommand.LineTo -> lineTo(command.x, command.y)
        is PathCommand.CubicTo ->
          cubicTo(command.x1, command.y1, command.x2, command.y2, command.x, command.y)
        else -> Unit
      }
    }
  }

  /**
   * The return leg of a staircase area steps the opposite way round.
   *
   * `step-before` forward is `step-after` backward and the other way about, because reversing the
   * point order swaps which of each pair the vertical belongs to. Getting it wrong leaves the two
   * boundaries out of step and the area self-intersecting at every riser.
   */
  private fun mirrored(method: String?): String? =
    when (method?.lowercase()) {
      "step-before" -> "step-after"
      "step-after" -> "step-before"
      else -> method
    }

  /**
   * Reports an interpolation method this engine cannot draw.
   *
   * Everything but the `catmull-rom` and `bundle` families is implemented; those two carry their
   * own parameterisation and closed variants, and drawing one as straight lines would look
   * plausible and be wrong, so the substitution is reported.
   */
  private fun reportUnsupportedInterpolation(method: String?, spec: MarkSpec) {
    if (method == null || CurveKind.fromName(method) != null) return
    reportOnce(
      "interpolate:$method",
      dev.aster.vega.model.VegaDiagnostic(
        severity = dev.aster.vega.model.DiagnosticSeverity.WARNING,
        code = DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        message =
          "Interpolation method '$method' is not implemented; the points were joined with " +
            "straight lines instead",
        operator = spec.name,
      ),
    )
  }

  // ---- shared style resolution ----------------------------------------------

  /** A mark's resolved paint, with Vega's per-type defaults applied. */
  private data class Style(val fill: Fill?, val stroke: Stroke?, val opacity: Double)

  private fun style(channels: EncodeEntry, datum: VegaValue, spec: MarkSpec): Style {
    val defaults = MarkConfig(spec)
    // Upstream's pairing rule: a mark that encodes *either* paint channel gets **neither**
    // default. So a rect outlined with a stroke and no fill is an outline, where checking only
    // `fill` would have filled it with the built-in blue.
    val paintsItself = channels["fill"] != null || channels["stroke"] != null
    val fillColour =
      paint(channels["fill"], datum, "fill", spec)
        ?: defaults.colour("fill", MarkDefaults.fillFor(spec.type)).takeIf { !paintsItself }
    val strokeColour =
      paint(channels["stroke"], datum, "stroke", spec)
        ?: defaults.colour("stroke", MarkDefaults.strokeFor(spec.type)).takeIf { !paintsItself }
    val fillOpacity =
      number(channels["fillOpacity"], datum) ?: defaults.number("fillOpacity") ?: 1.0
    val strokeOpacity =
      number(channels["strokeOpacity"], datum) ?: defaults.number("strokeOpacity") ?: 1.0
    val strokeWidth =
      number(channels["strokeWidth"], datum)
        ?: defaults.number("strokeWidth")
        ?: MarkDefaults.strokeWidthFor(spec.type)
    val strokeDash =
      numberList(channels["strokeDash"], datum) ?: defaults.numbers("strokeDash") ?: emptyList()
    val cap = strokeCap(string(channels["strokeCap"], datum) ?: defaults.text("strokeCap"), spec)
    val join =
      strokeJoin(string(channels["strokeJoin"], datum) ?: defaults.text("strokeJoin"), spec)

    return Style(
      fill = fillColour?.let { Fill(ScenePaint.Solid(it), fillOpacity) },
      stroke =
        strokeColour?.let {
          Stroke(
            paint = ScenePaint.Solid(it),
            width = strokeWidth,
            cap = cap,
            join = join,
            opacity = strokeOpacity,
            dashArray = strokeDash,
          )
        },
      opacity = number(channels["opacity"], datum) ?: defaults.number("opacity") ?: 1.0,
    )
  }

  /**
   * A mark's `config` defaults, resolved either side of the engine's built-in per-type block.
   *
   * `config.mark` loses to the built-ins and `config.{marktype}` plus the mark's `style` blocks
   * beat them. That ordering is upstream's and is not what the names suggest: it is why setting
   * `config.mark.fill` leaves a rect blue and setting `config.rect.fill` recolours it — the default
   * configuration already fills `config.rect` in.
   */
  private class MarkConfig(spec: MarkSpec) {
    private val below = spec.configBelowDefaults
    private val above = spec.configAboveDefaults

    fun colour(key: String, builtin: SceneColor?): SceneColor? =
      above[key]?.let { SceneColor.parse(it.asString()) }
        ?: builtin
        ?: below[key]?.let { SceneColor.parse(it.asString()) }

    fun number(key: String): Double? = value(key)?.asDouble()?.takeIf { !it.isNaN() }

    fun text(key: String): String? = value(key)?.takeIf { it is VegaValue.Str }?.asString()

    fun numbers(key: String): List<Double>? =
      (value(key) as? VegaValue.Arr)
        ?.values
        ?.map { it.asDouble() }
        ?.takeIf { list -> list.isNotEmpty() && list.all { it.isFinite() } }

    /** For everything but the paints there is no built-in in between, so the two just stack. */
    private fun value(key: String): VegaValue? = above[key] ?: below[key]
  }

  private fun strokeCap(name: String?, spec: MarkSpec): StrokeCap =
    when (name?.lowercase()) {
      null -> StrokeCap.BUTT
      "butt" -> StrokeCap.BUTT
      "round" -> StrokeCap.ROUND
      "square" -> StrokeCap.SQUARE
      else -> {
        diagnostics.warn(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "Unknown strokeCap '$name'; drawing a butt cap",
          operator = spec.name,
        )
        StrokeCap.BUTT
      }
    }

  private fun strokeJoin(name: String?, spec: MarkSpec): StrokeJoin =
    when (name?.lowercase()) {
      null -> StrokeJoin.MITER
      "miter" -> StrokeJoin.MITER
      "round" -> StrokeJoin.ROUND
      "bevel" -> StrokeJoin.BEVEL
      else -> {
        diagnostics.warn(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "Unknown strokeJoin '$name'; drawing a miter join",
          operator = spec.name,
        )
        StrokeJoin.MITER
      }
    }

  /**
   * An array-valued channel, which only `strokeDash` uses.
   *
   * A dash pattern with a non-finite or negative entry is dropped rather than half-applied: SVG and
   * Canvas disagree about what such a pattern draws, and a line that is solid on one backend and
   * dotted on another is worse than one that is solid on both.
   */
  private fun numberList(channel: ChannelValue?, datum: VegaValue): List<Double>? {
    val value =
      when (channel) {
        null -> return null
        is ChannelValue.Constant -> channel.value
        is ChannelValue.Field -> datum.fieldOf(channel.ref)
        is ChannelValue.Signal -> evaluateExpression(channel.expression, datum) ?: return null
        is ChannelValue.Conditional -> return numberList(selectRule(channel, datum), datum)
        // A dash pattern may be *scaled*, which is how a series is told apart by its pattern
        // rather than by its colour: an ordinal scale whose range values are the patterns
        // themselves. Dropping it drew every series solid, and identically.
        is ChannelValue.Scaled -> {
          val scale = scales[scaleNameOf(channel, datum)]
          val input = scaledInput(channel, datum)
          if (scale == null || input == null) return null
          scale.scale(input)
        }
        // A dash pattern is a list, and arithmetic on a list is not a thing upstream does either:
        // its generated expression would produce `[4,2]*2`, which is NaN.
        is ChannelValue.Adjusted -> return null
      }
    val values = (value as? VegaValue.Arr)?.values?.map { it.asDouble() } ?: return null
    return values.takeIf { list -> list.isNotEmpty() && list.all { it.isFinite() && it >= 0.0 } }
  }

  /**
   * An `image` mark.
   *
   * `align` and `baseline` shift the whole image rather than moving text inside a box: `align:
   * "center"` puts `x` at the image's middle, `baseline: "bottom"` puts `y` at its foot. That is
   * upstream's rule and it is the opposite of what a reader coming from `rect` expects, where `x`
   * is always the left edge.
   *
   * `aspect` defaults to true, which fits the image inside the box and letterboxes it; `false`
   * stretches it to fill.
   *
   * **A missing `width` or `height` is taken from the image's own pixels**, which the compiler
   * cannot know — only the renderer has the decoded bitmap. Upstream has the same gap and reports
   * zero until the image loads; this emits the mark at zero and says so, rather than dropping it.
   */
  private fun image(spec: MarkSpec, datum: VegaValue, index: Int): SceneNode? {
    val channels = spec.encode.effective
    // An `image` channel carries the pixels themselves — a `heatmap`'s output — where `url` carries
    // an address. Upstream distinguishes them the same way and its image mark takes either.
    // A mark-level `heatmap` writes its pixels onto the *row* rather than through an encode
    // channel, exactly as `geopath` writes an outline — upstream writes onto the scene item and its
    // renderer reads the item's own `image`. So a mark with no `image` channel falls back to the
    // row's own column.
    val raster = rasterOf(channels["image"], datum) ?: rasterFrom(datum.field("image"))
    val url = string(channels["url"], datum)
    if (raster == null && url.isNullOrEmpty()) {
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "An image mark needs a 'url' or an 'image'; nothing was drawn for this row",
        operator = spec.name ?: "image",
      )
      return null
    }
    val width = number(channels["width"], datum)
    val height = number(channels["height"], datum)
    if (width == null || height == null) {
      diagnostics.warn(
        DiagnosticCodes.EXPORT_IMAGE_UNRESOLVED,
        "Image '${url ?: "(pixels)"}' has no explicit " +
          "${if (width == null) "width" else "height"}; upstream takes " +
          "it from the image's own pixels, which only the renderer has, so the mark was placed " +
          "with a zero extent",
        operator = spec.name ?: "image",
      )
    }
    val w = width ?: 0.0
    val h = height ?: 0.0
    val aspect = boolean(channels["aspect"], datum) ?: true
    val style = style(channels, datum, spec)
    return ImageNode(
      id = ids.allocate(),
      url = url ?: "",
      raster = raster,
      x = number(channels["x"], datum) ?: 0.0,
      y = number(channels["y"], datum) ?: 0.0,
      width = w,
      height = h,
      fit = if (aspect) ImageFit.CONTAIN else ImageFit.FILL,
      smooth = boolean(channels["smooth"], datum) ?: true,
      align = ImageAlign.fromName(string(channels["align"], datum)),
      baseline = ImageBaseline.fromName(string(channels["baseline"], datum)),
      opacity = style.opacity,
      metadata = metadata(spec, datum, index, channels).copy(markKind = "image"),
    )
  }

  /**
   * Reads a channel's input, which is not always a column of the row being drawn.
   *
   * Vega's four object forms of `field` each reach somewhere else, and each is resolved here
   * because this is the only place that knows all of them:
   * - **group** — a property of the enclosing group. Inside a group scope `width` and `height` are
   *   that group's own size, which is exactly what `{"group": "height"}` asks for and why a mark
   *   can be made to span its cell.
   * - **parent** — a column of the facet datum, which the `parent` signal already carries.
   * - **signal** — the column *name* comes from a signal, so the signal is read first and the
   *   result used as a path.
   * - **datum** — the name is itself held in a column, one level of indirection further.
   */
  /**
   * Which scale a channel means, when the specification lets a signal decide.
   *
   * Takes the same four forms a `field` does — a control switching an axis between linear and log
   * is `signal`, a faceted cell inheriting its parent's scale is `parent`, and a
   * parallel-coordinates plot choosing a scale per row is `datum`. None is known until now.
   */
  private fun scaleNameOf(channel: ChannelValue.Scaled, datum: VegaValue): String =
    channel.scaleRef?.let { scaleName(it, datum) } ?: channel.scale

  /**
   * The scale a `{"scale": {"datum": ...}}` reference names.
   *
   * One lookup, not two. The same object under `field` means "the field *named by* `datum.d`", so
   * reading it that way looks the value up twice; under `scale` it is the name itself. A
   * parallel-coordinates plot puts a different scale on every row this way, and the second lookup
   * gave it a scale called "null" on all of them.
   */
  private fun scaleName(ref: FieldRef, datum: VegaValue): String =
    when (ref) {
      is FieldRef.ParentOf -> scope.signal("parent").field(scaleName(ref.name, datum)).asString()
      is FieldRef.Plain -> ref.path
      is FieldRef.Group -> groupProperty(ref.path).asString()
      is FieldRef.Parent -> scope.signal("parent").field(ref.path).asString()
      is FieldRef.Signal -> signalText(ref.expression) ?: ""
      is FieldRef.Datum -> datum.field(ref.path).asString()
    }

  private fun VegaValue.fieldOf(ref: FieldRef): VegaValue =
    when (ref) {
      is FieldRef.Plain -> field(ref.path)
      is FieldRef.Group -> groupProperty(ref.path)
      is FieldRef.Parent -> scope.signal("parent").field(ref.path)
      is FieldRef.Signal -> {
        val name = signalText(ref.expression)
        if (name == null) VegaValue.Null else field(name)
      }
      is FieldRef.Datum -> field(field(ref.path).asString())
      is FieldRef.ParentOf -> scope.signal("parent").field(scaleName(ref.name, this))
    }

  /** Evaluates a signal that supplies a field *name*; a broken one is reported once. */
  private fun signalText(expression: String): String? =
    when (val compiled = expressions.compile(expression)) {
      is ExpressionResult.Failed -> {
        diagnostics.add(compiled.diagnostic)
        null
      }
      is ExpressionResult.Compiled ->
        try {
          compiled.expression.evaluate(scope).asString().takeIf { it.isNotEmpty() }
        } catch (failure: ExpressionEvaluationException) {
          diagnostics.add(failure.diagnostic)
          null
        }
    }

  /**
   * The pixels an `image` channel resolves to, as a `heatmap` writes them.
   *
   * `{width, height, pixels}` with each pixel packed `0xAARRGGBB`. Anything else is not an image
   * and is left to the `url` path, which reports if there is nothing there either.
   */
  private fun rasterOf(channel: ChannelValue?, datum: VegaValue): RasterImage? =
    rasterFrom(channel?.let { value(it, datum) } ?: VegaValue.Null)

  private fun rasterFrom(value: VegaValue): RasterImage? {
    val resolved = value as? VegaValue.Obj ?: return null
    val width = resolved.fields["width"]?.asDouble()?.toInt() ?: return null
    val height = resolved.fields["height"]?.asDouble()?.toInt() ?: return null
    val pixels = (resolved.fields["pixels"] as? VegaValue.Arr)?.values ?: return null
    if (width < 0 || height < 0 || pixels.size != width * height) return null
    return RasterImage(width, height, IntArray(pixels.size) { pixels[it].asDouble().toInt() })
  }

  private fun metadata(
    spec: MarkSpec,
    datum: VegaValue,
    index: Int,
    channels: EncodeEntry,
  ): NodeMetadata =
    NodeMetadata(
      markName = spec.name,
      role = "mark",
      markKind = spec.type.name.lowercase(),
      datumIndex = index,
      interactive = spec.interactive,
      datum = datum,
      tooltip = datum,
      accessibility = describe(spec, datum, channels),
    )

  private fun fontWeight(channels: EncodeEntry, datum: VegaValue): Int {
    val channel = channels["fontWeight"] ?: return 400
    val numeric = number(channel, datum)
    if (numeric != null) return numeric.toInt().coerceIn(1, 1000)
    return when (string(channel, datum)?.lowercase()) {
      "bold",
      "bolder" -> 700
      "lighter" -> 300
      else -> 400
    }
  }

  private fun textAlign(name: String?): TextAlign =
    when (name?.lowercase()) {
      "center" -> TextAlign.CENTER
      "right" -> TextAlign.RIGHT
      else -> TextAlign.LEFT
    }

  private fun textBaseline(name: String?): TextBaseline =
    when (name?.lowercase()) {
      "top" -> TextBaseline.TOP
      "middle" -> TextBaseline.MIDDLE
      "bottom" -> TextBaseline.BOTTOM
      "line-top" -> TextBaseline.LINE_TOP
      "line-bottom" -> TextBaseline.LINE_BOTTOM
      else -> TextBaseline.ALPHABETIC
    }

  /** Resolves a channel to a string, following the same rules as the numeric resolution. */
  /**
   * A text mark's content, resolved the way upstream's `textValue` resolves it.
   *
   * Null and absent alike become the empty string rather than the word "null", and what is left is
   * trimmed — upstream trims before measuring or drawing, so a label written with a trailing space
   * is centred as though it had none.
   */
  private fun textOf(channel: ChannelValue?, datum: VegaValue): String =
    string(channel, datum)?.trim() ?: ""

  /**
   * A channel as text, or null when it holds nothing.
   *
   * A Vega null is *nothing*, not the four letters that spell it. Stringifying it gave a text mark
   * reading "null" and a fill of "null" that no colour parser could read — both of them a value the
   * specification never asked for, drawn where upstream draws nothing at all.
   */
  private fun string(channel: ChannelValue?, datum: VegaValue): String? =
    value(channel, datum)?.takeUnless { it is VegaValue.Null }?.asString()

  /** The same, before it is turned into text. */
  private fun value(channel: ChannelValue?, datum: VegaValue): VegaValue? =
    when (channel) {
      null -> null
      is ChannelValue.Constant -> channel.value
      is ChannelValue.Field -> datum.fieldOf(channel.ref)
      is ChannelValue.Signal -> evaluateExpression(channel.expression, datum)
      is ChannelValue.Conditional -> value(selectRule(channel, datum), datum)
      is ChannelValue.Adjusted -> adjusted(channel, datum)
      is ChannelValue.Scaled -> {
        val scale = scales[scaleNameOf(channel, datum)]
        val input = scaledInput(channel, datum)
        if (scale != null && input != null) scale.scale(input) else null
      }
    }

  /** A resolved position and extent along one axis. */
  private data class Span(val start: Double, val extent: Double)

  /**
   * Resolves one axis of a mark that has an extent of its own, from whichever channels were given.
   *
   * This is upstream's `adjustSpatial` (`vega-runtime/src/util.js`), followed in its own order,
   * which matters twice. An end without a start puts the mark's *far* edge there and lets the
   * extent run back from it. And a centre is applied **last**, overriding whatever the other
   * channels worked out, so `xc` with a `width` centres the mark rather than starting it.
   *
   * [swap] is upstream's `Swap` set — group, image and rect — where a start beyond its end is read
   * as the pair written the other way round rather than as a negative extent. A line or an area
   * keeps the sign, because there the two ends are a direction and not a box.
   */
  private fun resolveSpan(
    channels: EncodeEntry,
    datum: VegaValue,
    startChannel: String,
    endChannel: String,
    sizeChannel: String,
    centerChannel: String,
    index: Int,
    spec: MarkSpec,
    swap: Boolean = true,
  ): Span? {
    var start = position(channels[startChannel], datum)
    val end = position(channels[endChannel], datum)
    var size = position(channels[sizeChannel], datum)
    val center = position(channels[centerChannel], datum)

    if (end != null) {
      if (start != null) {
        val low = if (swap) minOf(start, end) else start
        val high = if (swap) maxOf(start, end) else end
        start = low
        size = high - low
      } else {
        start = end - (size ?: 0.0)
      }
    }
    if (center != null) start = center - (size ?: 0.0) / 2

    if (start == null) {
      if (size != null) return Span(0.0, size)
      // A channel that is *declared* but resolves to nothing is not a malformed specification, and
      // upstream does not treat it as one: it leaves the property off the scene item, which paints
      // nothing, and the item still exists. An interactive chart depends on that — a brush `rect`
      // whose `x` is `brush[0]` has no brush until someone drags one, and the mark has to be in the
      // scene to be dragged. A zero extent is how that is expressed here, since a scene node
      // carries
      // numbers rather than absences, and it paints nothing for the same reason.
      val declared =
        listOf(startChannel, endChannel, sizeChannel, centerChannel).any { channels[it] != null }
      if (declared) return Span(0.0, 0.0)
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "Rect mark '${spec.name ?: "(unnamed)"}' datum $index has no " +
          "$startChannel, $endChannel, $sizeChannel or $centerChannel channel",
        operator = spec.name,
      )
      return null
    }
    return Span(start, size ?: 0.0)
  }

  /**
   * The position of a mark that has no extent of its own — a symbol, a text, a line's vertex.
   *
   * Upstream runs the same spatial adjustment over these, but with no width to halve it reduces to
   * this: a centre channel is the position, and it wins over a start channel written beside it.
   */
  /**
   * A path mark's rows, in the order its `sort` asks for.
   *
   * A `sort` on a mark orders the *items* it draws, and for a line or an area there is only one
   * item — the whole series — so the ordering has to reach the points inside it instead. That is
   * what makes `{"sort": {"field": "x"}}` mean anything on a line: it is how a series with a row
   * inserted into the middle of it (by an `impute`, say) is drawn through that row rather than
   * doubling back to it from the end.
   *
   * The fields are the *item's* properties, which for a row is where that row resolves to.
   */
  private fun ordered(spec: MarkSpec, data: List<VegaValue>): List<VegaValue> {
    val sort = spec.sort ?: return data
    if (data.size < 2) return data
    val channels = spec.encode.effective
    return data.sortedWith { a, b ->
      var result = 0
      for ((index, field) in sort.fields.withIndex()) {
        val left = rowPosition(channels, a, field) ?: continue
        val right = rowPosition(channels, b, field) ?: continue
        val comparison = left.compareTo(right)
        if (comparison != 0) {
          val descending = sort.orders.getOrNull(index)?.startsWith("desc") == true
          result = if (descending) -comparison else comparison
          break
        }
      }
      result
    }
  }

  /** Where a row lands on one axis, as the sort sees it. */
  private fun rowPosition(channels: EncodeEntry, datum: VegaValue, field: String): Double? =
    when (field) {
      "x" -> centred(channels, datum, "x", "xc")
      "y" -> centred(channels, datum, "y", "yc")
      else -> null
    }

  private fun centred(
    channels: EncodeEntry,
    datum: VegaValue,
    channel: String,
    centerChannel: String,
  ): Double? =
    position(channels[centerChannel], datum)
      ?: position(channels[channel], datum)
      // An `x2` or `y2` with no start is still a position, and upstream's `adjustSpatial` reads it
      // as one: `start = end - extent`, and a mark with no extent of its own leaves the end where
      // it
      // is. Vega's stock index chart labels its index date that way — `y2` a fixed distance below
      // the plot and no `y` — and dropping the mark lost the label *and*, because a `fit` chart is
      // sized by how far it reaches, shrank the whole chart by less than upstream shrank it.
      ?: position(channels[END_OF[channel] ?: return null], datum)

  /** The far edge that stands in for a missing start, per axis. */
  private val END_OF = mapOf("x" to "x2", "y" to "y2")

  /** Resolves a positional channel to a number, applying its scale and band offset. */
  private fun position(channel: ChannelValue?, datum: VegaValue): Double? =
    when (channel) {
      null -> null
      is ChannelValue.Constant -> channel.value.asDouble().takeIf { !it.isNaN() }
      is ChannelValue.Field -> datum.fieldOf(channel.ref).asDouble().takeIf { !it.isNaN() }
      is ChannelValue.Signal ->
        evaluateExpression(channel.expression, datum)?.asDouble()?.takeIf { !it.isNaN() }
      is ChannelValue.Conditional -> position(selectRule(channel, datum), datum)
      is ChannelValue.Scaled -> scaledPosition(channel, datum)
      is ChannelValue.Adjusted -> adjustedNumber(channel, datum)
    }

  /**
   * Evaluates an expression against a datum, reporting a failure once rather than per datum.
   *
   * Returns `null` on failure so the caller can leave the channel unset, which is what Vega does
   * with an expression that throws.
   */
  private fun evaluateExpression(source: String, datum: VegaValue): VegaValue? =
    when (val compiled = expressions.compile(source)) {
      is ExpressionResult.Failed -> {
        reportOnce(source, compiled.diagnostic)
        null
      }
      is ExpressionResult.Compiled ->
        try {
          compiled.expression.evaluate(scope.withDatum(datum))
        } catch (e: ExpressionEvaluationException) {
          reportOnce(source, e.diagnostic)
          null
        }
    }

  /**
   * An expression that fails fails for every datum, so report it once.
   *
   * Without this a 10,000-row dataset would produce 10,000 identical diagnostics and bury
   * everything else.
   */
  private fun reportOnce(source: String, diagnostic: dev.aster.vega.model.VegaDiagnostic) {
    if (reported.add(source)) diagnostics.add(diagnostic)
  }

  private val reported = mutableSetOf<String>()

  /**
   * Picks the first production rule whose `test` passes.
   *
   * An untested rule always passes, so a trailing one is the default. If every test fails and there
   * is no default, the channel is left unset — as upstream does.
   */
  private fun selectRule(channel: ChannelValue.Conditional, datum: VegaValue): ChannelValue? {
    for (rule in channel.rules) {
      val test = rule.test ?: return rule.production
      val result = evaluateExpression(test, datum) ?: continue
      if (JsSemantics.truthy(result)) return rule.production
    }
    return null
  }

  private fun scaledPosition(channel: ChannelValue.Scaled, datum: VegaValue): Double? {
    val scale = scales[scaleNameOf(channel, datum)]
    if (scale == null) {
      diagnostics.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        "Encoding refers to scale '${scaleNameOf(channel, datum)}', which was not built",
        operator = scaleNameOf(channel, datum),
      )
      return null
    }
    if (scale !is PositionScale) {
      // A scale that is not *built* for positioning may still produce a number, and upstream simply
      // applies the scale and uses whatever comes out. An `ordinal` scale over `["left", "right"]`
      // ranged onto `[-7, 6]` is the ordinary case: it is how a label is nudged clear of the point
      // it belongs to, and it maps a name to a pixel offset without being a position scale in any
      // sense this engine models. Refusing it outright was stricter than upstream and cost every
      // such label its offset.
      val input =
        channel.field?.let { datum.fieldOf(it) }
          ?: channel.value
          ?: return unpositionable(channel, datum)
      val mapped = scale.scale(input).asDouble()
      return if (mapped.isNaN()) unpositionable(channel, datum) else mapped
    }

    // `{"scale": "x", "band": 1}` with no field means "a whole band", i.e. the bar's width.
    val band = channel.band
    if (channel.field == null && channel.value == null && channel.signal == null && band != null) {
      return scale.bandwidth * band
    }

    val input = scaledInput(channel, datum) ?: return null
    val base = scale.position(input)
    if (base.isNaN()) return null
    val bandOffset = if (band != null) scale.bandwidth * band else 0.0
    return base + bandOffset
  }

  /**
   * A channel resolved to whatever type it happens to be, for [items].
   *
   * The typed resolvers above each know what they want; this one does not, because a scene item
   * carries a number where the mark asked for a number and a colour where it asked for a colour. A
   * scale that was never built returns nothing rather than reporting: [encode] has already run over
   * the same channels and said so once.
   */
  private fun channelValue(channel: ChannelValue?, datum: VegaValue): VegaValue? =
    when (channel) {
      null -> null
      is ChannelValue.Constant -> channel.value
      is ChannelValue.Field -> datum.fieldOf(channel.ref)
      is ChannelValue.Signal -> evaluateExpression(channel.expression, datum)
      is ChannelValue.Conditional -> channelValue(selectRule(channel, datum), datum)
      is ChannelValue.Adjusted -> adjusted(channel, datum)
      is ChannelValue.Scaled -> {
        when (val scale = scales[scaleNameOf(channel, datum)]) {
          null -> null
          // A positional scale goes through the shared path, which is the one that knows about
          // `band` and `offset`; anything else maps its input straight through.
          is PositionScale -> scaledPosition(channel, datum)?.let { VegaValue.Num(it) }
          else -> {
            val input = scaledInput(channel, datum)
            input?.let { scale.scale(it) }
          }
        }
      }
    }

  /**
   * The arithmetic a value reference may carry, in upstream's order: `round(pow(v, e) * m + o)`.
   *
   * Each adjustment is a value reference of its own, so an offset can be read from the datum — a
   * label centred in its bar is `{"field": "y", "offset": {"field": "height", "mult": 0.5}}`. An
   * unresolvable base leaves the channel unset rather than defaulting to zero, which is what the
   * rest of this encoder does with a channel it cannot read.
   */
  private fun adjustedNumber(channel: ChannelValue.Adjusted, datum: VegaValue): Double? {
    var value = number(channel.base, datum) ?: return null
    channel.exponent?.let { number(it, datum)?.let { power -> value = value.pow(power) } }
    channel.mult?.let { number(it, datum)?.let { factor -> value *= factor } }
    channel.offset?.let { number(it, datum)?.let { shift -> value += shift } }
    return if (channel.round) roundHalfUp(value) else value
  }

  /** The same, as a [VegaValue], for the resolvers that do not know what type they want. */
  private fun adjusted(channel: ChannelValue.Adjusted, datum: VegaValue): VegaValue? =
    adjustedNumber(channel, datum)?.let { VegaValue.Num(it) }

  /**
   * What a scaled channel feeds its scale.
   *
   * Upstream's order — `signal`, then `field`, then `value` — because that is the order it builds
   * the base value in before wrapping it in the scale. `null` means the channel named no source at
   * all, which is the `{"scale": "x", "band": 1}` form the caller handles separately.
   */
  private fun scaledInput(channel: ChannelValue.Scaled, datum: VegaValue): VegaValue? =
    channel.signal?.let { evaluateExpression(it, datum) }
      ?: channel.field?.let { datum.fieldOf(it) }
      ?: channel.value

  /** Reports a scale whose output cannot place a mark, and leaves the channel unset. */
  private fun unpositionable(channel: ChannelValue.Scaled, datum: VegaValue): Double? {
    diagnostics.error(
      DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
      "Scale '${scaleNameOf(channel, datum)}' produced no number for this datum, so it cannot " +
        "position a mark",
      operator = scaleNameOf(channel, datum),
    )
    return null
  }

  private fun number(channel: ChannelValue?, datum: VegaValue): Double? =
    when (channel) {
      null -> null
      is ChannelValue.Constant -> channel.value.asDouble().takeIf { !it.isNaN() }
      is ChannelValue.Field -> datum.fieldOf(channel.ref).asDouble().takeIf { !it.isNaN() }
      is ChannelValue.Scaled -> scaledPosition(channel, datum)
      is ChannelValue.Signal ->
        evaluateExpression(channel.expression, datum)?.asDouble()?.takeIf { !it.isNaN() }
      is ChannelValue.Conditional -> number(selectRule(channel, datum), datum)
      is ChannelValue.Adjusted -> adjustedNumber(channel, datum)
    }

  private fun paint(
    channel: ChannelValue?,
    datum: VegaValue,
    channelName: String,
    spec: MarkSpec,
  ): SceneColor? {
    val text =
      when (channel) {
        null -> return null
        is ChannelValue.Constant -> channel.value.asString()
        is ChannelValue.Field -> datum.fieldOf(channel.ref).asString()
        is ChannelValue.Scaled -> {
          val scale = scales[scaleNameOf(channel, datum)]
          if (scale == null) {
            diagnostics.error(
              DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
              "Colour channel '$channelName' refers to scale '${channel.scale}', which was not built",
              operator = scaleNameOf(channel, datum),
            )
            return null
          }
          val input = scaledInput(channel, datum) ?: return null
          scale.scale(input).asString()
        }
        is ChannelValue.Signal ->
          evaluateExpression(channel.expression, datum)?.asString() ?: return null
        is ChannelValue.Conditional -> {
          val selected = selectRule(channel, datum) ?: return null
          return paint(selected, datum, channelName, spec)
        }
        // Arithmetic on a colour is arithmetic on a string, which upstream turns into NaN. The
        // adjustments are dropped rather than applied so at least the colour survives.
        is ChannelValue.Adjusted -> return paint(channel.base, datum, channelName, spec)
      }
    val colour = SceneColor.parse(text)
    if (colour == null) {
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Could not parse colour '$text' for channel '$channelName'",
        operator = spec.name,
      )
    }
    return colour
  }

  /**
   * Builds an accessibility label from the encoded channels.
   *
   * Uses the fields the mark actually encodes, so the description follows the chart rather than
   * guessing at which datum properties matter.
   */
  /** A value as a screen reader should say it; see [spokenNumber]. */
  private fun spoken(value: VegaValue): String =
    if (value is VegaValue.Num) spokenNumber(value.value) else value.asString()

  /**
   * What a screen reader is told about one mark.
   *
   * Upstream's model, plus one deliberate addition. Upstream labels an individual item **only**
   * when the specification supplies a `description` channel; with none, the item carries no label
   * at all and only the mark container is announced. That is right for a pointer, where a reader
   * meets the chart as a whole and the guides carry the meaning.
   *
   * It is wrong for a touch screen. Exploring by touch means landing on individual marks, and an
   * unlabelled one announces nothing — so a bar chart would be silent everywhere except its axes.
   * When there is no `description`, a label is built from the mark's own scaled fields instead.
   * That is a **divergence from upstream**, made on purpose and recorded here rather than
   * discovered later: a specification that says nothing gets something useful, and one that says
   * something gets exactly what it asked for.
   *
   * `aria: false`, on the mark or on the row, suppresses all of it.
   */
  private fun describe(
    spec: MarkSpec,
    datum: VegaValue,
    channels: EncodeEntry,
  ): AccessibilityDescriptor? {
    if (!spec.aria) return null
    if (boolean(channels["aria"], datum) == false) return null

    val role = string(channels["ariaRole"], datum) ?: "graphics-symbol"
    // The specification's own words win over anything derived from the channels.
    string(channels["description"], datum)
      ?.takeIf { it.isNotBlank() }
      ?.let {
        return AccessibilityDescriptor(label = it, role = role, focusable = true)
      }

    val labelField =
      channels.values.filterIsInstance<ChannelValue.Scaled>().firstNotNullOfOrNull { it.field }
    val valueField =
      channels.values.filterIsInstance<ChannelValue.Scaled>().mapNotNull { it.field }.lastOrNull()
    if (labelField == null) return null
    return AccessibilityDescriptor(
      label = spoken(datum.fieldOf(labelField)),
      value = valueField?.takeIf { it != labelField }?.let { spoken(datum.fieldOf(it)) },
      role = role,
      focusable = true,
    )
  }

  private companion object {
    /** The two axes the spatial adjustment runs over, in upstream's order. */
    private val SPATIAL_AXES =
      listOf(SpatialAxis("x", "x2", "xc", "width"), SpatialAxis("y", "y2", "yc", "height"))
  }
}
