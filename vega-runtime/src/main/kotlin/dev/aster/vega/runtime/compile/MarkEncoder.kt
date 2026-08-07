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
import dev.aster.vega.model.spec.ChannelValue
import dev.aster.vega.model.spec.EncodeEntry
import dev.aster.vega.model.spec.MarkSpec
import dev.aster.vega.model.spec.MarkType
import dev.aster.vega.runtime.scale.PositionScale
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.AccessibilityDescriptor
import dev.aster.vega.scene.Fill
import dev.aster.vega.scene.FontStyle
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.MetricTextEngine
import dev.aster.vega.scene.NodeMetadata
import dev.aster.vega.scene.PathData
import dev.aster.vega.scene.PathNode
import dev.aster.vega.scene.PointD
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
import dev.aster.vega.scene.SymbolNode
import dev.aster.vega.scene.SymbolShape
import dev.aster.vega.scene.TextAlign
import dev.aster.vega.scene.TextBaseline
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.TextRun
import dev.aster.vega.scene.TextStyle
import dev.aster.vega.scene.Transform2D

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
) {

  public fun encode(spec: MarkSpec, data: List<VegaValue>): List<SceneNode> =
    when (spec.type) {
      MarkType.RECT -> data.mapIndexedNotNull { index, datum -> rect(spec, datum, index) }
      MarkType.RULE -> data.mapIndexedNotNull { index, datum -> rule(spec, datum, index) }
      MarkType.SYMBOL -> data.mapIndexedNotNull { index, datum -> symbol(spec, datum, index) }
      MarkType.TEXT -> data.mapIndexedNotNull { index, datum -> text(spec, datum, index) }
      // One node for the whole series, not one per datum.
      MarkType.LINE -> listOfNotNull(line(spec, data))
      MarkType.AREA -> listOfNotNull(area(spec, data))
      MarkType.ARC -> data.mapIndexedNotNull { index, datum -> arc(spec, datum, index) }
      else -> {
        diagnostics.error(
          DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
          "The '${spec.type.name.lowercase()}' mark encoder is not implemented; " +
            "${data.size} data values produced no marks",
          operator = spec.name ?: spec.type.name.lowercase(),
        )
        emptyList()
      }
    }

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
      val x = position(channels["x"], datum) ?: 0.0
      val y = position(channels["y"], datum) ?: 0.0
      // Upstream treats an unset dimension as zero once either is given, and gives a group with
      // neither no extent at all — it then paints nothing and measures only its children.
      val width = position(channels["width"], datum)
      val height = position(channels["height"], datum)
      val size = if (width == null && height == null) null else SizeD(width ?: 0.0, height ?: 0.0)
      val extent = size ?: SizeD(0.0, 0.0)

      GroupNode(
        id = ids.allocate(),
        children = contents(datum, index, extent),
        transform = if (x == 0.0 && y == 0.0) Transform2D.Identity else Transform2D.translate(x, y),
        size = size,
        cornerRadius = number(channels["cornerRadius"], datum) ?: 0.0,
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
    val horizontal = resolveSpan(channels, datum, "x", "x2", "width", index, spec)
    val vertical = resolveSpan(channels, datum, "y", "y2", "height", index, spec)
    if (horizontal == null || vertical == null) return null

    val style = style(channels, datum, spec)
    val cornerRadius = number(channels["cornerRadius"], datum) ?: 0.0

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
   * `arc`: an annular sector, which is what a pie or donut chart is made of.
   *
   * Angles run clockwise from twelve o'clock, so a slice starting at zero begins straight up. A
   * zero inner radius makes a wedge rather than a ring, and the outline is closed differently in
   * the two cases — a wedge returns through the centre, a ring runs back along the inner edge.
   *
   * `padAngle` and `cornerRadius` are reported rather than approximated: upstream insets a padded
   * arc against a pad *radius* and rounds its corners with tangent circles, and a slice that is
   * visibly the wrong shape is worse than one that is honestly missing.
   */
  private fun arc(spec: MarkSpec, datum: VegaValue, index: Int): SceneNode? {
    val channels = spec.encode.effective
    val cx = position(channels["x"], datum) ?: 0.0
    val cy = position(channels["y"], datum) ?: 0.0
    val startAngle = number(channels["startAngle"], datum) ?: 0.0
    val endAngle = number(channels["endAngle"], datum) ?: 0.0
    val innerRadius = number(channels["innerRadius"], datum) ?: 0.0
    val outerRadius = number(channels["outerRadius"], datum) ?: 0.0
    if (outerRadius <= 0.0 || startAngle == endAngle) return null

    for (unsupported in listOf("padAngle", "cornerRadius")) {
      if (channels[unsupported] != null) {
        reportOnce(
          "arc:$unsupported",
          dev.aster.vega.model.VegaDiagnostic(
            severity = dev.aster.vega.model.DiagnosticSeverity.WARNING,
            code = DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
            message =
              "Arc '$unsupported' is not implemented; the slice was drawn with square corners and " +
                "no gap",
            operator = spec.name,
          ),
        )
      }
    }

    val style = style(channels, datum, spec)
    val path = PathData.build {
      arcTo(cx, cy, outerRadius, startAngle, endAngle)
      if (innerRadius > 0.0) {
        arcTo(cx, cy, innerRadius, endAngle, startAngle)
      } else {
        lineTo(cx, cy)
      }
      close()
    }
    return PathNode(
      id = ids.allocate(),
      path = path,
      fill = style.fill ?: Fill.of(MarkDefaults.DEFAULT_FILL),
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
    val x = position(channels["x"], datum) ?: return null
    val y = position(channels["y"], datum) ?: return null
    val style = style(channels, datum, spec)
    val shapeName = string(channels["shape"], datum)
    val shape = shapeName?.let { symbolShape(it, spec) } ?: SymbolShape.CIRCLE

    return SymbolNode(
      id = ids.allocate(),
      x = x,
      y = y,
      size = number(channels["size"], datum) ?: MarkDefaults.SYMBOL_SIZE,
      shape = shape,
      angleDegrees = number(channels["angle"], datum) ?: 0.0,
      fill = style.fill,
      stroke = style.stroke,
      opacity = style.opacity,
      metadata = metadata(spec, datum, index, channels),
    )
  }

  /** Maps Vega's shape names onto the built-in set, reporting anything else. */
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
    if (shape == null) {
      // Only an SVG path string is left, which would need a path parser.
      reportOnce(
        "shape:$name",
        dev.aster.vega.model.VegaDiagnostic(
          severity = dev.aster.vega.model.DiagnosticSeverity.WARNING,
          code = DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          message = "Symbol shape '$name' is not implemented; drawing a circle instead",
          operator = spec.name,
        ),
      )
    }
    return shape
  }

  private fun text(spec: MarkSpec, datum: VegaValue, index: Int): SceneNode? {
    val channels = spec.encode.effective
    val anchorX = position(channels["x"], datum) ?: return null
    val anchorY = position(channels["y"], datum) ?: return null
    val content = string(channels["text"], datum) ?: return null
    val angle = number(channels["angle"], datum) ?: 0.0
    // `dx` and `dy` shift the anchor without affecting alignment — but for rotated text upstream
    // applies them *after* the rotation, so an offset runs along the text rather than along the
    // page.
    // Rotating them here keeps the anchor as the point the text turns about, which is what it is.
    val nudge = PointD(number(channels["dx"], datum) ?: 0.0, number(channels["dy"], datum) ?: 0.0)
    val offset =
      if (angle == 0.0) nudge else Transform2D.rotateDegrees(angle).apply(nudge.x, nudge.y)
    val style = style(channels, datum, spec)

    val textStyle =
      TextStyle(
        fontFamily = string(channels["font"], datum) ?: MarkDefaults.TEXT_FONT_FAMILY,
        fontSize = number(channels["fontSize"], datum) ?: MarkDefaults.TEXT_FONT_SIZE,
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
        align = textAlign(string(channels["align"], datum)),
        baseline = textBaseline(string(channels["baseline"], datum)),
      )

    return TextNode(
      id = ids.allocate(),
      x = anchorX + offset.x,
      y = anchorY + offset.y,
      layout = textEngine.layout(run),
      fill = style.fill ?: Fill.of(MarkDefaults.TEXT_FILL),
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

    val path = PathData.build { segments.forEach { polyline(it) } }
    return PathNode(
      id = ids.allocate(),
      path = path,
      stroke =
        style.stroke
          ?: Stroke(ScenePaint.Solid(MarkDefaults.DEFAULT_FILL), MarkDefaults.LINE_STROKE_WIDTH),
      fill = null,
      opacity = style.opacity,
      metadata = metadata(spec, data.first(), 0, channels),
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
        val x = position(channels["x"], datum) ?: 0.0
        val y = position(channels["y"], datum) ?: 0.0
        val x2 = position(channels["x2"], datum) ?: x
        val y2 = position(channels["y2"], datum) ?: y
        PointD(x, y) to PointD(x2, y2)
      }
    if (pairs.isEmpty()) return null

    val style = style(channels, data.first(), spec)
    reportUnsupportedInterpolation(string(channels["interpolate"], data.first()), spec)

    val path = PathData.build {
      for (segment in pairs) {
        // Forward along the primary boundary, back along the secondary one, then close.
        polyline(segment.map { it.first })
        segment.reversed().forEach { lineTo(it.second.x, it.second.y) }
        close()
      }
    }
    return PathNode(
      id = ids.allocate(),
      path = path,
      fill = style.fill ?: Fill.of(MarkDefaults.DEFAULT_FILL),
      stroke = style.stroke,
      opacity = style.opacity,
      metadata = metadata(spec, data.first(), 0, channels),
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
    PointD(position(channels["x"], datum) ?: 0.0, position(channels["y"], datum) ?: 0.0)

  /** True when this datum's `defined` channel says the series should break here. */
  private fun broken(channels: EncodeEntry, datum: VegaValue): Boolean {
    val channel = channels["defined"] ?: return false
    val value = boolean(channel, datum) ?: return false
    return !value
  }

  /** Splits data into runs of consecutive defined points, so `defined: false` breaks the series. */
  private fun <T> segments(
    data: List<VegaValue>,
    channels: EncodeEntry,
    resolve: (VegaValue) -> T,
  ): List<List<T>> {
    val result = mutableListOf<List<T>>()
    var current = mutableListOf<T>()
    for (datum in data) {
      if (broken(channels, datum)) {
        if (current.size > 1) result.add(current)
        current = mutableListOf()
      } else {
        current.add(resolve(datum))
      }
    }
    if (current.size > 1) result.add(current)
    return result
  }

  /** Resolves a channel to a boolean, following the same rules as the numeric resolution. */
  private fun boolean(channel: ChannelValue?, datum: VegaValue): Boolean? =
    when (channel) {
      null -> null
      is ChannelValue.Constant -> JsSemantics.truthy(channel.value)
      is ChannelValue.Field -> JsSemantics.truthy(datum.field(channel.path))
      is ChannelValue.Signal ->
        evaluateExpression(channel.expression, datum)?.let { JsSemantics.truthy(it) }
      is ChannelValue.Conditional -> boolean(selectRule(channel, datum), datum)
      is ChannelValue.Scaled -> {
        val scale = scales[channel.scale]
        val input = channel.field?.let { datum.field(it) } ?: channel.value
        if (scale != null && input != null) JsSemantics.truthy(scale.scale(input)) else null
      }
    }

  /**
   * Reports an interpolation method other than linear.
   *
   * Vega's `basis`, `cardinal`, `catmull-rom`, `monotone` and the step family each need their own
   * spline generator. Drawing them as straight lines would look plausible and be wrong, so the
   * substitution is reported.
   */
  private fun reportUnsupportedInterpolation(method: String?, spec: MarkSpec) {
    if (method == null || method.equals("linear", ignoreCase = true)) return
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
    val fillColour =
      paint(channels["fill"], datum, "fill", spec)
        ?: MarkDefaults.fillFor(spec.type).takeIf { channels["fill"] == null }
    val strokeColour =
      paint(channels["stroke"], datum, "stroke", spec)
        ?: MarkDefaults.strokeFor(spec.type).takeIf { channels["stroke"] == null }
    val fillOpacity = number(channels["fillOpacity"], datum) ?: 1.0
    val strokeOpacity = number(channels["strokeOpacity"], datum) ?: 1.0
    val strokeWidth =
      number(channels["strokeWidth"], datum) ?: MarkDefaults.strokeWidthFor(spec.type)
    val strokeDash = numberList(channels["strokeDash"], datum) ?: emptyList()
    val cap = strokeCap(string(channels["strokeCap"], datum), spec)
    val join = strokeJoin(string(channels["strokeJoin"], datum), spec)

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
      opacity = number(channels["opacity"], datum) ?: 1.0,
    )
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
        is ChannelValue.Field -> datum.field(channel.path)
        is ChannelValue.Signal -> evaluateExpression(channel.expression, datum) ?: return null
        is ChannelValue.Conditional -> return numberList(selectRule(channel, datum), datum)
        is ChannelValue.Scaled -> return null
      }
    val values = (value as? VegaValue.Arr)?.values?.map { it.asDouble() } ?: return null
    return values.takeIf { list -> list.isNotEmpty() && list.all { it.isFinite() && it >= 0.0 } }
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
      tooltip = datum,
      accessibility = describe(datum, channels),
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
  private fun string(channel: ChannelValue?, datum: VegaValue): String? =
    when (channel) {
      null -> null
      is ChannelValue.Constant -> channel.value.asString()
      is ChannelValue.Field -> datum.field(channel.path).asString()
      is ChannelValue.Signal -> evaluateExpression(channel.expression, datum)?.asString()
      is ChannelValue.Conditional -> string(selectRule(channel, datum), datum)
      is ChannelValue.Scaled -> {
        val scale = scales[channel.scale]
        val input = channel.field?.let { datum.field(it) } ?: channel.value
        if (scale != null && input != null) scale.scale(input).asString() else null
      }
    }

  /** A resolved position and extent along one axis. */
  private data class Span(val start: Double, val extent: Double)

  /**
   * Resolves one axis of a rect from whichever channel pair the specification used.
   *
   * Vega allows `x` + `x2`, `x` + `width`, or `x2` + `width`; a lone `x` with a band scale takes
   * its extent from the band. Anything else cannot be positioned and is reported.
   */
  private fun resolveSpan(
    channels: EncodeEntry,
    datum: VegaValue,
    startChannel: String,
    endChannel: String,
    sizeChannel: String,
    index: Int,
    spec: MarkSpec,
  ): Span? {
    val start = position(channels[startChannel], datum)
    val end = position(channels[endChannel], datum)
    val size = position(channels[sizeChannel], datum)

    return when {
      start != null && end != null -> Span(minOf(start, end), kotlin.math.abs(end - start))
      start != null && size != null -> Span(start, size)
      end != null && size != null -> Span(end - size, size)
      start != null -> Span(start, 0.0)
      end != null -> Span(end, 0.0)
      else -> {
        diagnostics.error(
          DiagnosticCodes.PARSE_MISSING_PROPERTY,
          "Rect mark '${spec.name ?: "(unnamed)"}' datum $index has no " +
            "$startChannel, $endChannel or $sizeChannel channel",
          operator = spec.name,
        )
        null
      }
    }
  }

  /** Resolves a positional channel to a number, applying its scale and band offset. */
  private fun position(channel: ChannelValue?, datum: VegaValue): Double? =
    when (channel) {
      null -> null
      is ChannelValue.Constant -> channel.value.asDouble().takeIf { !it.isNaN() }
      is ChannelValue.Field -> datum.field(channel.path).asDouble().takeIf { !it.isNaN() }
      is ChannelValue.Signal ->
        evaluateExpression(channel.expression, datum)?.asDouble()?.takeIf { !it.isNaN() }
      is ChannelValue.Conditional -> position(selectRule(channel, datum), datum)
      is ChannelValue.Scaled -> scaledPosition(channel, datum)
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
    val scale = scales[channel.scale]
    if (scale == null) {
      diagnostics.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        "Encoding refers to scale '${channel.scale}', which was not built",
        operator = channel.scale,
      )
      return null
    }
    if (scale !is PositionScale) {
      diagnostics.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        "Scale '${channel.scale}' has no numeric range and cannot position a mark",
        operator = channel.scale,
      )
      return null
    }

    // `{"scale": "x", "band": 1}` with no field means "a whole band", i.e. the bar's width.
    val band = channel.band
    if (channel.field == null && channel.value == null && band != null) {
      return scale.bandwidth * band + (channel.offset ?: 0.0)
    }

    val fieldPath = channel.field
    val constant = channel.value
    val input =
      when {
        fieldPath != null -> datum.field(fieldPath)
        constant != null -> constant
        else -> return null
      }
    val base = scale.position(input)
    if (base.isNaN()) return null
    val bandOffset = if (band != null) scale.bandwidth * band else 0.0
    return base + bandOffset + (channel.offset ?: 0.0)
  }

  private fun number(channel: ChannelValue?, datum: VegaValue): Double? =
    when (channel) {
      null -> null
      is ChannelValue.Constant -> channel.value.asDouble().takeIf { !it.isNaN() }
      is ChannelValue.Field -> datum.field(channel.path).asDouble().takeIf { !it.isNaN() }
      is ChannelValue.Scaled -> scaledPosition(channel, datum)
      is ChannelValue.Signal ->
        evaluateExpression(channel.expression, datum)?.asDouble()?.takeIf { !it.isNaN() }
      is ChannelValue.Conditional -> number(selectRule(channel, datum), datum)
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
        is ChannelValue.Field -> datum.field(channel.path).asString()
        is ChannelValue.Scaled -> {
          val scale = scales[channel.scale]
          if (scale == null) {
            diagnostics.error(
              DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
              "Colour channel '$channelName' refers to scale '${channel.scale}', which was not built",
              operator = channel.scale,
            )
            return null
          }
          val input = channel.field?.let { datum.field(it) } ?: channel.value ?: return null
          scale.scale(input).asString()
        }
        is ChannelValue.Signal ->
          evaluateExpression(channel.expression, datum)?.asString() ?: return null
        is ChannelValue.Conditional -> {
          val selected = selectRule(channel, datum) ?: return null
          return paint(selected, datum, channelName, spec)
        }
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
  private fun describe(datum: VegaValue, channels: EncodeEntry): AccessibilityDescriptor? {
    val labelField =
      channels.values.filterIsInstance<ChannelValue.Scaled>().firstNotNullOfOrNull { it.field }
    val valueField =
      channels.values.filterIsInstance<ChannelValue.Scaled>().mapNotNull { it.field }.lastOrNull()
    if (labelField == null) return null
    val label = datum.field(labelField).asString()
    val value = valueField?.takeIf { it != labelField }?.let { datum.field(it).asString() }
    return AccessibilityDescriptor(
      label = label,
      value = value,
      role = "graphics-symbol",
      focusable = true,
    )
  }
}
