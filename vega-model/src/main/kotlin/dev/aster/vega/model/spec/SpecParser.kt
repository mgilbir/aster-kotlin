package dev.aster.vega.model.spec

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asBoolean
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString

/**
 * The aggregate operations a domain over several fields may be sorted by.
 *
 * Upstream summarizes each field separately and then combines the results, so only operations that
 * survive being applied twice are allowed: a count of counts is their sum, a min of mins is a min.
 * There is no way to combine two means, so upstream rejects one rather than producing an average of
 * averages.
 */
private val MULTI_FIELD_SORT_OPS = listOf("count", "min", "max")

/**
 * The `config` blocks whose defaults reach the chart.
 *
 * Everything else upstream accepts — `mark`, the per-mark-type blocks, `range`, `group`,
 * `projection` — is reported by name, because a theme that reaches the axes and not the bars looks
 * more broken than one that reaches neither.
 */
private val CONFIG_HONOURED =
  setOf(
    "mark",
    "rect",
    "symbol",
    "line",
    "area",
    "rule",
    "text",
    "arc",
    "path",
    "image",
    "trail",
    "shape",
    "axis",
    "axisX",
    "axisY",
    "axisTop",
    "axisBottom",
    "axisLeft",
    "axisRight",
    "axisBand",
    "legend",
    "title",
  )

/**
 * Axis properties this engine reads.
 *
 * Upstream's axis takes 74 of them and this reads twenty-odd. Everything else is reported —
 * [AXIS_UNSUPPORTED] where there is something specific to say about what will be drawn instead, and
 * the generic message otherwise — because an axis that quietly drew ten ticks where the
 * specification asked for four, or drew its labels horizontally where the specification turned them
 * 45 degrees, looks like a chart and is not the chart that was asked for.
 */
/** Everything an `on` handler may say. */
private val SIGNAL_HANDLER_CONSUMED = setOf("events", "update", "encode", "force")

/** Everything the object form of an event stream may say. */
private val EVENT_STREAM_CONSUMED =
  setOf(
    "source",
    "type",
    "marktype",
    "markname",
    "markrole",
    "filter",
    "throttle",
    "debounce",
    "consume",
    "between",
  )

private val AXIS_CONSUMED =
  setOf(
    "scale",
    "orient",
    "title",
    "titlePadding",
    "titleFontSize",
    "titleAnchor",
    "grid",
    "ticks",
    "labels",
    "domain",
    "tickCount",
    "tickSize",
    "labelPadding",
    "labelFontSize",
    "offset",
    "zindex",
    "values",
    "labelOverlap",
    "labelSeparation",
    "labelAngle",
    "labelAlign",
    "labelBaseline",
    "labelLimit",
    "encode",
    "format",
    "formatType",
    "bandPosition",
    // Read by the axis builder since the parallel-coordinates work; they were still being
    // reported as unimplemented, which is the stale half of "nothing silently ignored".
    "titleX",
    "titleY",
    "titleAngle",
    "titleAlign",
    "titleBaseline",
  ) + guideStyleKeys("label", "tick", "grid", "domain", "title")

/**
 * The `{prefix}Color`/`Width`/`Dash`/`Opacity`/`Font`/`FontWeight`/`FontStyle` family for each part
 * of a guide, which [SpecParser.guideStroke] reads as a group.
 */
private fun guideStyleKeys(vararg prefixes: String): Set<String> =
  prefixes
    .flatMap { prefix ->
      listOf("Color", "Width", "Dash", "Opacity", "Font", "FontWeight", "FontStyle").map {
        "$prefix$it"
      }
    }
    .toSet()

private val AXIS_UNSUPPORTED =
  mapOf(
    "labelBound" to "Bounding axis labels to the plotting area is not implemented",
    "labelFlush" to "Flushing the first and last axis label to the range ends is not implemented",
    "labelFlushOffset" to "Axis label flush offsets are not implemented; they need labelFlush",
    "labelOffset" to "Axis label offsets along the axis are not implemented",
    "labelLineHeight" to "Multi-line axis labels are not implemented",
    "tickMinStep" to "A minimum tick step is not implemented; the scale's own tick count is used",
    "tickExtra" to "Adding a tick at the range end is not implemented",
    "tickRound" to "Suppressing tick rounding is not implemented; ticks are always rounded",
    "tickOffset" to "Axis tick offsets are not implemented",
    "tickBand" to "Placing band-scale ticks at band edges is not implemented; they sit at centres",
    "tickCap" to "Axis tick line caps are not implemented",
    "tickDashOffset" to "Dash offsets are not implemented; the dash pattern starts at the line end",
    "gridCap" to "Gridline caps are not implemented",
    "gridDashOffset" to "Dash offsets are not implemented; the dash pattern starts at the line end",
    "gridScale" to "Gridlines driven by a second scale are not implemented",
    "domainCap" to "Domain line caps are not implemented",
    "domainDashOffset" to
      "Dash offsets are not implemented; the dash pattern starts at the line end",
    "position" to "Positioning an axis along its own dimension is not implemented",
    "translate" to "Overriding the axis's half-pixel translation is not implemented",
    "minExtent" to "A minimum axis extent is not implemented; the axis is measured by its contents",
    "maxExtent" to "A maximum axis extent is not implemented; the axis is measured by its contents",
    "titleLimit" to "Axis title truncation is not implemented; the title is drawn in full",
    "titleLineHeight" to "Multi-line axis titles are not implemented",
    "aria" to "Accessibility attributes on a guide are not implemented",
    "description" to "Accessibility descriptions on a guide are not implemented",
  )

/** Scale properties this engine reads. */
private val SCALE_CONSUMED =
  setOf(
    "name",
    "type",
    "domain",
    "domainMin",
    "domainMax",
    "domainMid",
    "range",
    "reverse",
    "round",
    "clamp",
    "nice",
    "zero",
    "padding",
    "paddingInner",
    "paddingOuter",
    "align",
    "base",
    "exponent",
    "constant",
    "interpolate",
    "bins",
  )

private val SCALE_UNSUPPORTED =
  mapOf(
    "domainRaw" to "Overriding a resolved domain with 'domainRaw' is not implemented",
    "domainImplicit" to "Extending an ordinal domain with unseen values is not implemented",
  )

/** Legend properties this engine reads. */
private val LEGEND_CONSUMED =
  setOf(
    "fill",
    "stroke",
    "size",
    "shape",
    "opacity",
    "type",
    "format",
    "orient",
    "direction",
    "title",
    "values",
    "tickCount",
    "offset",
    "padding",
    "titlePadding",
    "titleOrient",
    "titleLimit",
    "titleFontSize",
    "labelFontSize",
    "labelOffset",
    "symbolType",
    "symbolSize",
    "symbolStrokeWidth",
    "gradientLength",
    "gradientThickness",
    "rowPadding",
    "columnPadding",
    "columns",
    "legendX",
    "legendY",
    "zindex",
    "symbolDash",
    "symbolOpacity",
    "labelOverlap",
    "labelSeparation",
    "labelLimit",
    "encode",
  ) + guideStyleKeys("label", "title", "symbolStroke")

/** Title properties this engine reads. */
private val TITLE_CONSUMED =
  setOf(
    "text",
    "subtitle",
    "orient",
    "anchor",
    "frame",
    "offset",
    "subtitlePadding",
    "fontSize",
    "fontWeight",
    "font",
    "dx",
    "dy",
    "subtitleFontSize",
    "fontStyle",
    "subtitleFontStyle",
    "zindex",
  )

/**
 * Which guide property each `encode` channel is another spelling of.
 *
 * Upstream's guide parsers build the same channel from either, so this is the mapping that already
 * exists there, written down: `gridDash` and `encode.grid.enter.strokeDash` are one thing. Note the
 * asymmetry that is upstream's and not a simplification — a *label's* colour is a fill and every
 * other part's is a stroke, so the two spellings of "colour" differ by part.
 */
private fun strokeEncodeMap(prefix: String): Map<String, String> =
  mapOf(
    "stroke" to "${prefix}Color",
    "strokeWidth" to "${prefix}Width",
    "strokeDash" to "${prefix}Dash",
    "strokeOpacity" to "${prefix}Opacity",
    "opacity" to "${prefix}Opacity",
  )

private fun textEncodeMap(prefix: String): Map<String, String> =
  mapOf(
    "fill" to "${prefix}Color",
    "fillOpacity" to "${prefix}Opacity",
    "opacity" to "${prefix}Opacity",
    "font" to "${prefix}Font",
    "fontSize" to "${prefix}FontSize",
    "fontWeight" to "${prefix}FontWeight",
    "fontStyle" to "${prefix}FontStyle",
  )

/**
 * Guide encode channels resolved against the item rather than folded into a property.
 *
 * Only a label's position so far, which is the one a specification cannot say any other way: Vega's
 * budget example moves its labels to the start of each band with `{"scale": "x", "field":
 * "value"}`.
 */
private val RESOLVED_GUIDE_CHANNELS =
  setOf(
    // An axis label's position, which no property can say.
    "labels.update.x",
    "labels.update.y",
    // A label's own nudge, and the text it draws when a scale supplies it rather than a format.
    "labels.update.dx",
    "labels.update.dy",
    // An axis label's own visibility, which a calendar uses to name only the first week of each
    // month. It is a rule over the tick's own value, so no property could say it.
    "labels.update.opacity",
    // A legend label's text, which is how an id becomes a name — read through a scale, so there is
    // nothing constant to fold.
    "labels.update.text",
    // A legend swatch's fill opacity. Upstream has `symbolOpacity`, which fades the outline with
    // the swatch; this fades only what is inside it, and there is no property for that.
    "symbols.enter.fillOpacity",
    "symbols.update.fillOpacity",
    // A legend swatch's overall opacity. `symbolOpacity` says the same thing as a constant, but an
    // interactive legend writes a *conditional rule* here — a swatch dims when its series is
    // deselected — and no property can express one, so it is resolved against the entry instead.
    "symbols.enter.opacity",
    "symbols.update.opacity",
  )

/**
 * Channels a guide writes into `update` itself, so a specification's `enter` never survives.
 *
 * Verified upstream: an axis label given `enter: {text: {value: 'E'}}` still reads its tick's own
 * text, and the same block under `update` does replace it.
 */
private val GUIDE_UPDATE_CHANNELS = setOf("text", "x", "y")

private val AXIS_ENCODE_PARTS: Map<String, Map<String, String>> =
  mapOf(
    "grid" to strokeEncodeMap("grid"),
    "ticks" to strokeEncodeMap("tick"),
    "domain" to strokeEncodeMap("domain"),
    "labels" to
      textEncodeMap("label") +
        mapOf(
          "limit" to "labelLimit",
          "align" to "labelAlign",
          "baseline" to "labelBaseline",
          "angle" to "labelAngle",
        ),
    "title" to textEncodeMap("title"),
  )

private val LEGEND_ENCODE_PARTS: Map<String, Map<String, String>> =
  mapOf(
    // A legend symbol keeps its dash and opacity under names of their own rather than under the
    // `symbolStroke` prefix the colour and width use, which is why this one is written out.
    "symbols" to
      mapOf(
        "stroke" to "symbolStrokeColor",
        "strokeWidth" to "symbolStrokeWidth",
        "strokeDash" to "symbolDash",
        "strokeOpacity" to "symbolOpacity",
        "opacity" to "symbolOpacity",
        "size" to "symbolSize",
        "shape" to "symbolType",
      ),
    "labels" to textEncodeMap("label") + mapOf("limit" to "labelLimit"),
    "title" to textEncodeMap("title"),
  )

/** Mark properties this engine reads. */
private val MARK_CONSUMED =
  setOf(
    "type",
    "name",
    "role",
    "from",
    "encode",
    "marks",
    "axes",
    "data",
    "signals",
    "scales",
    "legends",
    "layout",
    "title",
    "zindex",
    "interactive",
    "aria",
    "clip",
    // Both are read only to be reported, which reportUnhandled would otherwise duplicate.
    "transform",
    "sort",
    // Reported by reportUnsupportedGroupScope, for the same reason.
    "style",
    "on",
  )

/** The formats a loaded document can be read as. `topojson` needs projections and is a non-goal. */
private val READABLE_FORMATS = setOf("json", "csv", "tsv", "dsv")

/** Data properties this engine reads. */
private val DATA_CONSUMED = setOf("name", "values", "source", "transform", "format", "url")

/**
 * Encode channels this engine's mark encoders read, across every mark type.
 *
 * A union rather than a per-type list: a channel outside it is one nothing can consume, which is
 * the silence worth breaking. A channel inside it but wrong for the mark at hand — `innerRadius` on
 * a rect — is upstream's business too, and it ignores those the same way.
 */
private val ENCODE_CONSUMED =
  setOf(
    // The accessibility channels. `description` is Vega's *only* documented way to label an
    // individual mark; `aria: false` hides one from a screen reader entirely.
    "description",
    "aria",
    "ariaRole",
    "ariaRoleDescription",
    "x",
    "x2",
    "y",
    "y2",
    "width",
    "height",
    "size",
    "shape",
    "text",
    "angle",
    "align",
    "baseline",
    "dx",
    "dy",
    "font",
    "fontSize",
    "fontWeight",
    "fontStyle",
    "fill",
    "fillOpacity",
    "stroke",
    "strokeOpacity",
    "strokeWidth",
    "opacity",
    "cornerRadius",
    "defined",
    "interpolate",
    "orient",
    "startAngle",
    "endAngle",
    "innerRadius",
    "outerRadius",
    "strokeDash",
    "strokeCap",
    "strokeJoin",
    "padAngle",
    "padRadius",
    "xc",
    "yc",
    "url",
    // An outline written as an SVG path string, which `SvgPath` has read since the `path-marks`
    // fixture. The entry below it stayed behind and told every specification using one that it had
    // been ignored — including both of Vega's tree-diagram examples, whose links are path marks.
    "path",
  )

private val ENCODE_UNSUPPORTED =
  mapOf(
    "limit" to "Text truncation is not implemented; the text is drawn in full",
    "ellipsis" to "Text truncation is not implemented, so its ellipsis has nothing to mark",
    "tooltip" to
      "Tooltip content from an encode channel is not implemented; a tooltip is built from the " +
        "mark's own fields instead",
    "cornerRadiusTopLeft" to
      "Per-corner radii are not implemented; use 'cornerRadius' for all four",
    "cornerRadiusTopRight" to
      "Per-corner radii are not implemented; use 'cornerRadius' for all four",
    "cornerRadiusBottomLeft" to
      "Per-corner radii are not implemented; use 'cornerRadius' for all four",
    "cornerRadiusBottomRight" to
      "Per-corner radii are not implemented; use 'cornerRadius' for all four",
    "blend" to "Blend modes from an encode channel are not implemented",
    "clip" to "Clipping from an encode channel is not implemented; use the mark's own 'clip'",
    "zindex" to "Per-item z-order is not implemented; marks are drawn in specification order",
    "tension" to "Curve tension is not implemented; it needs an interpolation method first",
    "theta" to "Polar positioning is not implemented",
    "radius" to "Polar positioning is not implemented",
    "scaleX" to "Per-item scaling is not implemented",
    "scaleY" to "Per-item scaling is not implemented",
  )

/** A parsed specification plus everything the parser could not honour. */
public data class ParsedSpec(val spec: VegaSpec?, val diagnostics: List<VegaDiagnostic>) {
  public val isUsable: Boolean
    get() = spec != null
}

/**
 * Parses a compiled Vega specification into [VegaSpec].
 *
 * Two rules shape this class:
 * - **Nothing is silently dropped.** Every unknown mark type, scale type, channel form or property
 *   produces a diagnostic carrying its JSON path, so a partially supported specification is
 *   inspectable rather than mysteriously wrong (PROJECT_BRIEF.md 3.3, 14).
 * - **Syntax only.** The parser resolves shapes and reports gaps; it does not resolve domains,
 *   apply defaults that depend on scale type, or lay anything out. Those belong to the runtime,
 *   which has the data.
 *
 * Only the subset the runtime can execute is modelled. Coverage is tracked in
 * SUPPORTED_FEATURES.md.
 */
public class SpecParser {

  private val diagnostics = DiagnosticCollector()

  /** The specification's `config` block, which every guide reads behind its own properties. */
  private var config: GuideConfig = GuideConfig.Empty

  /**
   * Every scale's type, by name, collected before anything else is parsed.
   *
   * An axis needs it to know whether `config.axisBand` applies, and axes are parsed before the
   * scales inside the group that holds them. Collected from the whole specification in one pass
   * rather than per scope, so a scale name reused at two different types in two different scopes
   * resolves to whichever was written last — rare enough to accept, and better than an axis that
   * silently drops a band correction.
   */
  private var scaleTypes: Map<String, ScaleType> = emptyMap()

  public fun parseJson(json: String): ParsedSpec {
    val root =
      VegaJson.parseOrNull(json, diagnostics) ?: return ParsedSpec(null, diagnostics.diagnostics)
    return parse(root)
  }

  public fun parse(root: VegaValue): ParsedSpec {
    if (root !is VegaValue.Obj) {
      diagnostics.fatal(
        DiagnosticCodes.PARSE_INVALID_JSON,
        "A specification must be a JSON object, found ${root::class.simpleName}",
        jsonPath = "$",
      )
      return ParsedSpec(null, diagnostics.diagnostics)
    }

    config = parseConfig(root.fields["config"])
    scaleTypes = collectScaleTypes(root)

    val spec =
      VegaSpec(
        width = root.optionalNumber("width", "$.width"),
        height = root.optionalNumber("height", "$.height"),
        // Each falls back to `config`, which is where a theme sets a chart's frame.
        padding =
          parsePadding(root.fields["padding"] ?: configScalar(root, "padding"), "$.padding"),
        autosize =
          parseAutosize(root.fields["autosize"] ?: configScalar(root, "autosize"), "$.autosize"),
        background =
          (root.fields["background"] ?: configScalar(root, "background"))
            ?.takeIf { it is VegaValue.Str }
            ?.asString(),
        signals = parseArray(root, "signals") { value, path -> parseSignal(value, path) },
        data = parseArray(root, "data") { value, path -> parseData(value, path) },
        scales = parseArray(root, "scales") { value, path -> parseScale(value, path) },
        axes = parseArray(root, "axes") { value, path -> parseAxis(value, path) },
        legends = parseArray(root, "legends") { value, path -> parseLegend(value, path) },
        title = root.fields["title"]?.let { parseTitle(it, "$.title") },
        layout = root.fields["layout"]?.let { parseLayout(it, "$.layout") },
        marks = parseArray(root, "marks") { value, path -> parseMark(value, path) },
        encode = parseEncode(root.fields["encode"], "$.encode"),
        description = root.fields["description"]?.asString()?.takeIf { it.isNotBlank() },
      )

    reportUnsupportedTopLevel(root)
    return ParsedSpec(spec, diagnostics.diagnostics)
  }

  // ---- config ---------------------------------------------------------------

  /**
   * Reads the `config` block, reporting the parts of it nothing consumes yet.
   *
   * The blocks that are honoured are the guide ones. A `config.mark` or a per-mark-type block still
   * changes what a chart looks like and is reported by name, because a theme that silently applies
   * to the axes and not to the bars is worse than one that applies to neither.
   */
  private fun parseConfig(value: VegaValue?): GuideConfig {
    val obj = value as? VegaValue.Obj ?: return GuideConfig.Empty
    val blocks = LinkedHashMap<String, VegaValue.Obj>()
    for ((key, block) in obj.fields) {
      val child = block as? VegaValue.Obj
      when {
        child == null ->
          diagnostics.warn(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "A config block must be an object; '$key' was ignored",
            jsonPath = "$.config.$key",
          )
        key in CONFIG_HONOURED || key == "style" -> blocks[key] = child
        else ->
          diagnostics.warn(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "Config block '$key' is not implemented; the engine's own defaults are used, so a " +
              "theme setting it will not reach the chart",
            jsonPath = "$.config.$key",
          )
      }
    }
    return GuideConfig(blocks)
  }

  /** A chart-level value written in `config` rather than at the top level. */
  private fun configScalar(root: VegaValue.Obj, key: String): VegaValue? =
    (root.fields["config"] as? VegaValue.Obj)?.fields?.get(key)

  /**
   * Every scale's type, gathered from the whole specification including group scopes.
   *
   * Needed before the axes are parsed, and a group's axes are parsed before its scales, so this
   * cannot be built up as it goes.
   */
  private fun collectScaleTypes(root: VegaValue.Obj): Map<String, ScaleType> {
    val types = LinkedHashMap<String, ScaleType>()
    fun walk(scope: VegaValue.Obj) {
      (scope.fields["scales"] as? VegaValue.Arr)?.values?.forEach { entry ->
        val scale = entry as? VegaValue.Obj ?: return@forEach
        val name = scale.fields["name"]?.asString() ?: return@forEach
        ScaleType.fromName(scale.fields["type"]?.asString() ?: "linear")?.let { types[name] = it }
      }
      (scope.fields["marks"] as? VegaValue.Arr)?.values?.forEach { entry ->
        (entry as? VegaValue.Obj)?.let { walk(it) }
      }
    }
    walk(root)
    return types
  }

  // ---- top level ------------------------------------------------------------

  /**
   * Reports specification sections the runtime does not implement.
   *
   * Silence here would be the worst outcome: a chart with a title or a layout would render without
   * it and look merely wrong rather than unsupported.
   */
  private fun reportUnsupportedTopLevel(root: VegaValue.Obj) {
    val unsupported =
      mapOf(
        "projections" to "Geographic projections are out of scope",
        "usermeta" to "usermeta is ignored",
      )
    for ((key, reason) in unsupported) {
      val value = root.fields[key] ?: continue
      val empty = value is VegaValue.Arr && value.values.isEmpty()
      if (empty) continue
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "$reason; '$key' was ignored",
        jsonPath = "$.$key",
      )
    }
  }

  // ---- signals --------------------------------------------------------------

  private fun parseSignal(value: VegaValue, path: String, subscope: Boolean = false): SignalSpec? {
    val obj = value as? VegaValue.Obj ?: return unexpected("a signal definition", path)
    val name = obj.fields["name"]?.asString()
    if (name.isNullOrEmpty()) {
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "A signal needs a name",
        jsonPath = path,
      )
      return null
    }

    val on =
      ((obj.fields["on"] as? VegaValue.Arr)?.values ?: emptyList()).mapIndexedNotNull { index, entry
        ->
        parseSignalHandler(entry, "$path.on[$index]", name, subscope)
      }
    if (obj.fields["bind"] != null) {
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Signal bindings create input widgets, which have no equivalent here; signal '$name' " +
          "will keep its initial value",
        jsonPath = "$path.bind",
      )
    }

    return SignalSpec(
      name = name,
      value = obj.fields["value"],
      init = obj.fields["init"]?.asString(),
      update = obj.fields["update"]?.asString(),
      on = on,
      bind = obj.fields["bind"],
    )
  }

  /**
   * One `on` entry: its sources, and what it sets the signal to.
   *
   * `events` mixes two unrelated things on purpose — event selectors, and `{"signal": ...}` or
   * `{"scale": ...}` entries that fire when *those* change. Upstream treats both as sources pushing
   * a new value, which is why a chart can be made reactive with no events at all.
   */
  private fun parseSignalHandler(
    value: VegaValue,
    path: String,
    signalName: String,
    subscope: Boolean,
  ): SignalHandler? {
    val obj = value as? VegaValue.Obj ?: return unexpected("a signal handler", path)
    val events = obj.fields["events"]
    if (events == null) {
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "A handler on signal '$signalName' needs an 'events' specification",
        jsonPath = path,
      )
      return null
    }

    val streams = mutableListOf<EventStream>()
    val signals = mutableListOf<String>()
    val scales = mutableListOf<String>()
    val defaultSource = if (subscope) EventStream.SOURCE_SCOPE else EventStream.SOURCE_VIEW
    for (entry in if (events is VegaValue.Arr) events.values else listOf(events)) {
      when (entry) {
        is VegaValue.Str ->
          try {
            streams += EventSelector.parse(entry.value, defaultSource)
          } catch (failure: EventSelectorException) {
            diagnostics.error(
              DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
              "Could not read the event selector for signal '$signalName': ${failure.message}",
              jsonPath = "$path.events",
            )
            return null
          }
        is VegaValue.Obj -> {
          val signal = entry.fields["signal"]?.asString()
          val scale = entry.fields["scale"]?.asString()
          when {
            !signal.isNullOrEmpty() -> signals += signal
            !scale.isNullOrEmpty() -> scales += scale
            else -> {
              val stream = parseEventStreamObject(entry, "$path.events", defaultSource)
              if (stream == null) return null
              streams += stream
            }
          }
        }
        else -> {
          diagnostics.error(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "An 'events' entry must be a selector string or an object",
            jsonPath = "$path.events",
          )
          return null
        }
      }
    }

    val encode = obj.fields["encode"]
    val updateValue = obj.fields["update"]
    if (encode != null && updateValue != null) {
      // Upstream rewrites `encode` into an `encode(item(), ...)` call, so the two would fight over
      // the same slot. It errors rather than picking one, and so does this.
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "A handler on signal '$signalName' has both 'encode' and 'update'; they set the same " +
          "thing and cannot both apply",
        jsonPath = path,
      )
      return null
    }

    val update =
      when {
        updateValue == null -> null
        updateValue is VegaValue.Str -> SignalUpdate.Expression(updateValue.value)
        updateValue is VegaValue.Obj -> {
          val expr = updateValue.fields["expr"]?.asString()
          val signal = updateValue.fields["signal"]?.asString()
          val literal = updateValue.fields["value"]
          when {
            !expr.isNullOrEmpty() -> SignalUpdate.Expression(expr)
            literal != null -> SignalUpdate.Constant(literal)
            !signal.isNullOrEmpty() -> SignalUpdate.Reference(signal)
            else -> {
              diagnostics.error(
                DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
                "An 'update' object needs one of 'expr', 'value' or 'signal'",
                jsonPath = "$path.update",
              )
              return null
            }
          }
        }
        else -> SignalUpdate.Constant(updateValue)
      }
    if (update == null && encode == null) {
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "A handler on signal '$signalName' needs an 'update' or an 'encode'",
        jsonPath = path,
      )
      return null
    }

    obj.reportUnhandled("Signal handler", path, SIGNAL_HANDLER_CONSUMED)
    return SignalHandler(
      streams = streams,
      signalSources = signals,
      scaleSources = scales,
      update = update,
      encode = encode,
      force = (obj.fields["force"] as? VegaValue.Bool)?.value ?: false,
    )
  }

  /**
   * The object form of a stream, which a specification uses when a selector string cannot say it.
   */
  private fun parseEventStreamObject(
    obj: VegaValue.Obj,
    path: String,
    defaultSource: String,
  ): EventStream? {
    (obj.fields["merge"] as? VegaValue.Arr)?.let {
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "A 'merge' stream combines several streams into one; write them as a comma-separated " +
          "selector string instead",
        jsonPath = path,
      )
      return null
    }
    val type = obj.fields["type"]?.asString()
    if (type.isNullOrEmpty()) {
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "An event stream object needs a 'type'",
        jsonPath = path,
      )
      return null
    }
    val between =
      (obj.fields["between"] as? VegaValue.Arr)?.values?.mapNotNull {
        (it as? VegaValue.Obj)?.let { child -> parseEventStreamObject(child, path, defaultSource) }
      } ?: emptyList()
    obj.reportUnhandled("Event stream", path, EVENT_STREAM_CONSUMED)
    return EventStream(
      source = obj.fields["source"]?.asString()?.takeIf { it.isNotEmpty() } ?: defaultSource,
      type = type,
      markType = obj.fields["marktype"]?.asString()?.takeIf { it.isNotEmpty() },
      markName = obj.fields["markname"]?.asString()?.takeIf { it.isNotEmpty() },
      filters =
        (obj.fields["filter"] as? VegaValue.Arr)?.values?.map { it.asString() }
          ?: obj.fields["filter"]?.asString()?.let { listOf(it) }
          ?: emptyList(),
      throttle = obj.fields["throttle"]?.asDouble()?.takeIf { !it.isNaN() && it != 0.0 },
      debounce = obj.fields["debounce"]?.asDouble()?.takeIf { !it.isNaN() && it != 0.0 },
      consume = (obj.fields["consume"] as? VegaValue.Bool)?.value ?: false,
      between = between,
    )
  }

  private fun parsePadding(value: VegaValue?, path: String): Padding =
    when (value) {
      null -> Padding.Default
      is VegaValue.Num -> Padding.uniform(value.value)
      is VegaValue.Obj ->
        Padding(
          left = value.fields["left"]?.asDouble() ?: 0.0,
          top = value.fields["top"]?.asDouble() ?: 0.0,
          right = value.fields["right"]?.asDouble() ?: 0.0,
          bottom = value.fields["bottom"]?.asDouble() ?: 0.0,
        )
      else -> {
        diagnostics.warn(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "padding must be a number or an object; using the default of none",
          jsonPath = path,
        )
        Padding.Default
      }
    }

  private fun parseAutosize(value: VegaValue?, path: String): Autosize {
    val (name, resize, contains) =
      when (value) {
        null -> return Autosize.Default
        is VegaValue.Str -> Triple(value.value, false, "content")
        is VegaValue.Obj ->
          Triple(
            value.fields["type"]?.asString() ?: "pad",
            value.fields["resize"]?.asBoolean() ?: false,
            value.fields["contains"]?.asString() ?: "content",
          )
        else -> {
          diagnostics.warn(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "autosize must be a string or an object; using 'pad'",
            jsonPath = path,
          )
          return Autosize.Default
        }
      }
    val type =
      when (name.lowercase()) {
        "pad" -> AutosizeType.PAD
        "fit" -> AutosizeType.FIT
        "fit-x" -> AutosizeType.FIT_X
        "fit-y" -> AutosizeType.FIT_Y
        "none" -> AutosizeType.NONE
        else -> {
          diagnostics.warn(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "Unknown autosize type '$name'; using 'pad'",
            jsonPath = path,
          )
          AutosizeType.PAD
        }
      }
    return Autosize(type, resize, contains)
  }

  // ---- data -----------------------------------------------------------------

  private fun parseData(value: VegaValue, path: String): DataSpec? {
    val obj = value as? VegaValue.Obj ?: return unexpected("a data definition", path)
    val name = obj.fields["name"]?.asString()
    if (name.isNullOrEmpty()) {
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "A data definition needs a name",
        jsonPath = path,
      )
      return null
    }

    val values = (obj.fields["values"] as? VegaValue.Arr)?.values
    val urlValue = obj.fields["url"]
    val urlSignal = (urlValue as? VegaValue.Obj)?.fields?.get("signal")?.asString()
    val url = if (urlSignal == null) urlValue?.asString() else null
    val format = obj.fields["format"] as? VegaValue.Obj
    val parse = LinkedHashMap<String, String>()
    var parseAuto = false
    if (format != null) {
      for ((key, value) in format.fields) {
        if (key == "type" || key == "property" || key == "delimiter") continue
        if (key == "parse") {
          if (value is VegaValue.Str && value.value.equals("auto", ignoreCase = true)) {
            parseAuto = true
            continue
          }
          val fields = value as? VegaValue.Obj
          if (fields == null) {
            diagnostics.warn(
              DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
              "'format.parse' must be \"auto\" or name each field and how to read it",
              jsonPath = "$path.format.parse",
            )
            continue
          }
          for ((field, kind) in fields.fields) parse[field] = kind.asString()
        } else {
          diagnostics.warn(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "Data format option '$key' is not implemented; values are used as parsed JSON",
            jsonPath = "$path.format.$key",
          )
        }
      }
    }

    val formatType = format?.fields?.get("type")?.asString()?.lowercase()
    if (formatType != null && formatType !in READABLE_FORMATS) {
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Data format '$formatType' is not implemented; dataset '$name' will be empty. " +
          "Readable formats are ${READABLE_FORMATS.sorted().joinToString(", ")}",
        jsonPath = "$path.format.type",
      )
    }

    return DataSpec(
      name = name,
      values = values,
      url = url,
      urlSignal = urlSignal,
      formatType = formatType,
      property = format?.fields?.get("property")?.asString()?.takeIf { it.isNotEmpty() },
      delimiter = format?.fields?.get("delimiter")?.asString()?.takeIf { it.isNotEmpty() },
      transform = (obj.fields["transform"] as? VegaValue.Arr)?.values ?: emptyList(),
      sources =
        when (val source = obj.fields["source"]) {
          is VegaValue.Arr -> source.values.map { it.asString() }.filter { it.isNotEmpty() }
          null -> emptyList()
          else -> listOfNotNull(source.asString().takeIf { it.isNotEmpty() })
        },
      parse = parse,
      parseAuto = parseAuto,
    )
  }

  // ---- scales ---------------------------------------------------------------

  private fun parseScale(value: VegaValue, path: String): ScaleSpec? {
    val obj = value as? VegaValue.Obj ?: return unexpected("a scale definition", path)
    val name = obj.fields["name"]?.asString()
    if (name.isNullOrEmpty()) {
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "A scale needs a name",
        jsonPath = path,
      )
      return null
    }

    val typeName = obj.fields["type"]?.asString() ?: "linear"
    val type = ScaleType.fromName(typeName)
    if (type == null) {
      diagnostics.error(
        DiagnosticCodes.SCALE_UNSUPPORTED_TYPE,
        "Unknown scale type '$typeName' on scale '$name'",
        jsonPath = "$path.type",
      )
      return null
    }

    obj.reportUnhandled("Scale", path, SCALE_CONSUMED, SCALE_UNSUPPORTED)

    return ScaleSpec(
      name = name,
      type = type,
      domain = parseDomain(obj, "$path.domain"),
      domainMin = obj.numberOrSignal("domainMin", "$path.domainMin"),
      domainMax = obj.numberOrSignal("domainMax", "$path.domainMax"),
      domainMid = obj.numberOrSignal("domainMid", "$path.domainMid"),
      range = parseRange(obj.fields["range"], "$path.range"),
      reverse = obj.fields["reverse"]?.asBoolean() ?: false,
      round = obj.fields["round"]?.asBoolean() ?: false,
      clamp = obj.fields["clamp"]?.asBoolean() ?: false,
      nice = parseNice(obj.fields["nice"], "$path.nice"),
      niceCount = (obj.fields["nice"] as? VegaValue.Num)?.value?.toInt(),
      zero = obj.fields["zero"]?.asBoolean(),
      padding = obj.numberOrSignal("padding", "$path.padding"),
      paddingInner = obj.numberOrSignal("paddingInner", "$path.paddingInner"),
      paddingOuter = obj.numberOrSignal("paddingOuter", "$path.paddingOuter"),
      align = obj.numberOrSignal("align", "$path.align"),
      base = obj.numberOrSignal("base", "$path.base"),
      exponent = obj.numberOrSignal("exponent", "$path.exponent"),
      constant = obj.numberOrSignal("constant", "$path.constant"),
      interpolate = obj.fields["interpolate"]?.takeIf { it is VegaValue.Str }?.asString(),
      bins = parseBins(obj.fields["bins"], "$path.bins"),
    )
  }

  /**
   * `bins`, in each of the three forms upstream's `parseScaleBins` accepts.
   *
   * A signal or an array is taken as it comes; anything else is read as the `{start, stop, step}`
   * description, whose properties may each be signal-valued.
   */
  private fun parseBins(value: VegaValue?, path: String): BinsSpec? =
    when (value) {
      null -> null
      is VegaValue.Arr -> BinsSpec.Values(value.values)
      is VegaValue.Obj ->
        (value.fields["signal"] as? VegaValue.Str)
          ?.takeIf { value.fields.size == 1 }
          ?.let {
            BinsSpec.Signal(it.value)
          }
          ?: BinsSpec.Steps(
            start = value.numberOrSignal("start", "$path.start"),
            stop = value.numberOrSignal("stop", "$path.stop"),
            step = value.numberOrSignal("step", "$path.step"),
          )
      else -> {
        diagnostics.error(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "Scale 'bins' must be an array, a {start, stop, step} object or a signal",
          jsonPath = path,
        )
        null
      }
    }

  private fun parseNice(value: VegaValue?, path: String): Boolean =
    when (value) {
      null -> false
      is VegaValue.Bool -> value.value
      is VegaValue.Num -> true
      else -> {
        diagnostics.warn(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "Time-unit 'nice' values are not implemented; treating nice as false",
          jsonPath = path,
        )
        false
      }
    }

  private fun parseDomain(scale: VegaValue.Obj, path: String): DomainSpec {
    val domain = scale.fields["domain"] ?: return DomainSpec.Unset
    return when (domain) {
      is VegaValue.Arr -> DomainSpec.Literal(domain.values)
      is VegaValue.Obj -> {
        domain.fields["signal"]?.let {
          return DomainSpec.FromSignal(it.asString())
        }
        val data = domain.fields["data"]?.asString()
        val field = fieldPath(domain.fields["field"], "$path.field")
        val rawFields = (domain.fields["fields"] as? VegaValue.Arr)?.values
        // With no `data` of its own, each entry of `fields` names its own source — a union across
        // datasets rather than across columns of one. An entry may also be a literal array, which
        // is how a specification widens a data-driven domain to cover a fixed range as well.
        if (data == null && rawFields != null) {
          val parts = rawFields.mapNotNull { part ->
            when (part) {
              is VegaValue.Arr -> DomainSpec.Literal(part.values)
              is VegaValue.Obj -> parseDomain(VegaValue.Obj(mapOf("domain" to part)), path)
              else -> {
                diagnostics.error(
                  DiagnosticCodes.SCALE_INVALID_DOMAIN,
                  "A domain union entry must be an array or a data reference",
                  jsonPath = path,
                )
                null
              }
            }
          }
          return DomainSpec.Union(
            parts,
            parseDomainSort(domain.fields["sort"], "$path.sort", multiField = true),
          )
        }
        val fields = rawFields?.map { it.asString() }
        when {
          data != null && field != null ->
            DomainSpec.FromField(
              data,
              field,
              parseDomainSort(domain.fields["sort"], "$path.sort", multiField = false),
            )
          data != null && fields != null ->
            DomainSpec.FromFields(
              data,
              fields,
              parseDomainSort(domain.fields["sort"], "$path.sort", multiField = true),
            )
          else -> {
            diagnostics.error(
              DiagnosticCodes.SCALE_INVALID_DOMAIN,
              "A data-driven domain needs 'data' plus 'field' or 'fields'",
              jsonPath = path,
            )
            DomainSpec.Unset
          }
        }
      }
      else -> {
        diagnostics.error(
          DiagnosticCodes.SCALE_INVALID_DOMAIN,
          "A domain must be an array or a data reference",
          jsonPath = path,
        )
        DomainSpec.Unset
      }
    }
  }

  /**
   * A discrete domain's `sort`, which upstream normalizes in `parseSort` before the dataflow ever
   * sees it.
   *
   * Three of its four branches are only visible by reading that function:
   * - an object naming neither `op` nor `field` sorts by the domain value, exactly as `sort: true`
   *   does, so `{"order": "descending"}` is the way to reverse a domain alphabetically;
   * - a `field` with no `op` names an aggregate output that was never computed, so upstream sorts
   *   on a column of undefined and — its sort being stable — changes nothing at all. Reproduced,
   *   because a specification written against upstream may be relying on the order it *does* get,
   *   but reported, because it is almost certainly not the order the author wanted;
   * - an `op` other than `count` with no `field`, and a multi-field domain sorted by anything but
   *   `count`, `min` or `max`, are both hard errors upstream. Reported here and the sort dropped,
   *   which leaves the domain in the order upstream would have produced had the sort been absent.
   */
  private fun parseDomainSort(value: VegaValue?, path: String, multiField: Boolean): DomainSort? {
    val obj =
      when (value) {
        null -> return null
        is VegaValue.Bool -> return if (value.value) DomainSort.ByValue() else null
        is VegaValue.Obj -> value
        else -> {
          diagnostics.warn(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "A domain 'sort' must be true or an object; ignoring it",
            jsonPath = path,
          )
          return null
        }
      }
    val op = obj.fields["op"]?.asString()?.takeIf { it.isNotEmpty() }
    val field = obj.fields["field"]?.asString()?.takeIf { it.isNotEmpty() }
    val descending = obj.fields["order"]?.asString()?.startsWith("desc") == true
    return when {
      op == null && field == null -> DomainSort.ByValue(descending)
      op == null -> {
        diagnostics.warn(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "Domain sort field '$field' has no 'op', so upstream computes no such aggregate and " +
            "the domain keeps its first-appearance order; name an 'op' to sort by it",
          jsonPath = path,
        )
        null
      }
      field == null && !op.equals("count", ignoreCase = true) -> {
        diagnostics.error(
          DiagnosticCodes.SCALE_INVALID_DOMAIN,
          "Domain sort op '$op' needs a 'field'; leaving the domain unsorted",
          jsonPath = path,
        )
        null
      }
      multiField && !MULTI_FIELD_SORT_OPS.any { it.equals(op, ignoreCase = true) } -> {
        diagnostics.error(
          DiagnosticCodes.SCALE_INVALID_DOMAIN,
          "A domain over several fields cannot be sorted by '$op'; upstream allows only " +
            "${MULTI_FIELD_SORT_OPS.joinToString(", ")}, because each field is summarized " +
            "separately and the results then combined. Leaving the domain unsorted",
          jsonPath = path,
        )
        null
      }
      else -> DomainSort.ByAggregate(op, field, descending)
    }
  }

  private fun parseRange(value: VegaValue?, path: String): RangeSpec =
    when (value) {
      null -> RangeSpec.Unset
      is VegaValue.Str -> RangeSpec.Named(value.value)
      is VegaValue.Arr -> RangeSpec.Literal(value.values)
      is VegaValue.Obj -> {
        val scheme = value.fields["scheme"]
        val signal = value.fields["signal"]?.asString()
        val step = value.fields["step"]
        val count = value.fields["count"]
        val data = value.fields["data"]
        val field = value.fields["field"]?.asString()
        if (count != null && count !is VegaValue.Num) {
          diagnostics.warn(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "A scheme 'count' must be a number; the whole scheme will be used",
            jsonPath = "$path.count",
          )
        }
        when {
          scheme != null ->
            RangeSpec.Scheme(
              // The name may itself come from a signal — how a chart offers a palette picker — and
              // the stops may be written out instead of named.
              when (scheme) {
                is VegaValue.Obj -> SchemeRef.Signal(scheme.fields["signal"]?.asString() ?: "")
                is VegaValue.Arr -> SchemeRef.Colors(scheme.values)
                else -> SchemeRef.Named(scheme.asString())
              },
              (count as? VegaValue.Num)?.value?.toInt(),
            )
          !signal.isNullOrEmpty() -> RangeSpec.Signal(signal)
          step != null ->
            RangeSpec.Step(value.numberOrSignal("step", path) ?: NumberValue.Constant(0.0))
          data != null && !field.isNullOrEmpty() -> RangeSpec.FromField(data.asString(), field)
          else -> {
            diagnostics.warn(
              DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
              "A range object must name a 'scheme', a 'signal', a 'step', or a 'data' and 'field'",
              jsonPath = path,
            )
            RangeSpec.Unset
          }
        }
      }
      else -> {
        diagnostics.warn(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "Unsupported range form",
          jsonPath = path,
        )
        RangeSpec.Unset
      }
    }

  // ---- axes -----------------------------------------------------------------

  private fun parseAxis(value: VegaValue, path: String): AxisSpec? {
    val own = value as? VegaValue.Obj ?: return unexpected("an axis definition", path)
    val scale = own.fields["scale"]?.asString()
    if (scale.isNullOrEmpty()) {
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "An axis needs a scale",
        jsonPath = path,
      )
      return null
    }
    val orientName = own.fields["orient"]?.asString() ?: "bottom"
    val orient = Orient.fromName(orientName)
    if (orient == null) {
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Unknown axis orientation '$orientName'",
        jsonPath = "$path.orient",
      )
      return null
    }

    // Reported against the axis's *own* properties: a theme should not make every axis in a chart
    // complain about something it set once in `config`.
    own.reportUnhandled("Axis", path, AXIS_CONSUMED, AXIS_UNSUPPORTED)

    val obj =
      GuideConfig.merge(own, config.axisDefaults(orient, scaleTypes[scale] == ScaleType.BAND))
        .withGuideEncode(AXIS_ENCODE_PARTS, "Axis", path)

    return AxisSpec(
      scale = scale,
      orient = orient,
      title = obj.fields["title"]?.takeIf { it is VegaValue.Str }?.asString(),
      titleExpression = (obj.fields["title"] as? VegaValue.Obj)?.fields?.get("signal")?.asString(),
      titlePadding = obj.numberOrSignal("titlePadding", "$path.titlePadding"),
      titleFontSize = obj.numberOrSignal("titleFontSize", "$path.titleFontSize"),
      titleAnchor = obj.enumOrNull("titleAnchor", path, "title anchor") { Anchor.fromName(it) },
      grid = obj.fields["grid"]?.asBoolean() ?: false,
      ticks = obj.fields["ticks"]?.asBoolean() ?: true,
      labels = obj.fields["labels"]?.asBoolean() ?: true,
      domainLine = obj.fields["domain"]?.asBoolean() ?: true,
      tickCount = obj.numberOrSignal("tickCount", "$path.tickCount"),
      tickSize = obj.numberOrSignal("tickSize", "$path.tickSize"),
      labelPadding = obj.numberOrSignal("labelPadding", "$path.labelPadding"),
      labelFontSize = obj.numberOrSignal("labelFontSize", "$path.labelFontSize"),
      offset = obj.numberOrSignal("offset", "$path.offset"),
      titleX = obj.numberOrSignal("titleX", "$path.titleX"),
      titleY = obj.numberOrSignal("titleY", "$path.titleY"),
      titleAngle = obj.numberOrSignal("titleAngle", "$path.titleAngle"),
      titleAlign = obj.fields["titleAlign"]?.takeIf { it is VegaValue.Str }?.asString(),
      titleBaseline = obj.fields["titleBaseline"]?.takeIf { it is VegaValue.Str }?.asString(),
      offsetChannel =
        (obj.fields["offset"] as? VegaValue.Obj)
          ?.takeIf { it.fields["signal"] == null }
          ?.let { parseChannel("offset", it, "$path.offset") },
      zindex = (obj.fields["zindex"] as? VegaValue.Num)?.value?.toInt() ?: 0,
      values = (obj.fields["values"] as? VegaValue.Arr)?.values,
      labelOverlap = obj.fields["labelOverlap"]?.asString(),
      labelSeparation = obj.numberOrSignal("labelSeparation", "$path.labelSeparation"),
      labelAngle = obj.numberOrSignal("labelAngle", "$path.labelAngle"),
      labelLimit = obj.numberOrSignal("labelLimit", "$path.labelLimit"),
      labelAlign = obj.fields["labelAlign"]?.takeIf { it is VegaValue.Str }?.asString(),
      labelBaseline = obj.fields["labelBaseline"]?.takeIf { it is VegaValue.Str }?.asString(),
      format = obj.fields["format"]?.takeIf { it is VegaValue.Str }?.asString(),
      formatExpression =
        (obj.fields["format"] as? VegaValue.Obj)?.fields?.get("signal")?.asString(),
      formatType = axisFormatType(obj.fields["formatType"], "$path.formatType"),
      bandPosition = obj.numberOrSignal("bandPosition", "$path.bandPosition"),
      encode =
        (obj.fields["encode"] as? VegaValue.Obj)?.fields.orEmpty().mapValues { (part, block) ->
          parseEncode(block, "$path.encode.$part")
        },
      labelStyle = obj.guideStroke("label"),
      tickStyle = obj.guideStroke("tick"),
      gridStyle = obj.guideStroke("grid"),
      domainStyle = obj.guideStroke("domain"),
      titleStyle = obj.guideStroke("title"),
    )
  }

  /**
   * `titleOrient`, of which only `top` and `left` are implemented.
   *
   * `right` and `bottom` are reported: each needs its own anchoring rule — a bottom title is
   * `end`-anchored against the entries and a right one is measured from their far edge — and a
   * legend that quietly put its title on the wrong side would look finished.
   */
  private fun legendTitleOrient(value: VegaValue?, path: String): String? {
    val name = (value as? VegaValue.Str)?.value?.lowercase() ?: return null
    if (name == "top" || name == "left") return name
    diagnostics.warn(
      DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
      "A legend title on the '$name' is not implemented; it is drawn above the entries",
      jsonPath = path,
    )
    return null
  }

  /**
   * `formatType`, which upstream's schema restricts to `number`, `time` and `utc`.
   *
   * A signal may choose it, and that form is reported rather than read: the specifier it selects
   * changes what every label *says*, so guessing `number` would produce a chart full of epoch
   * milliseconds that looks finished.
   */
  private fun axisFormatType(value: VegaValue?, path: String): String? {
    val name =
      when (value) {
        null -> return null
        is VegaValue.Str -> value.value.lowercase()
        else -> {
          diagnostics.error(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "A label format type chosen by a signal is not implemented; write one of " +
              "'number', 'time' or 'utc'",
            jsonPath = path,
          )
          return null
        }
      }
    if (name !in setOf("number", "time", "utc")) {
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Label format type '$name' is not one of 'number', 'time' or 'utc'",
        jsonPath = path,
      )
      return null
    }
    return name
  }

  /**
   * Folds a guide's `encode` block into the properties it is another spelling of.
   *
   * Upstream builds each part of an axis or a legend from an encode block of its own and *extends*
   * it with whatever the specification wrote, so `{"grid": {"enter": {"strokeDash": {"value":
   * [3,3]}}}}` and `"gridDash": [3,3]` end up as the same channel on the same mark. Rewriting the
   * first into the second is therefore not an approximation — it is the same merge, done a step
   * earlier — and it means the encode block participates in *measurement* too, which matters: a
   * legend symbol resized through `encode` moves every label beside it.
   *
   * Only constants fold. A `signal`, a `field` or a conditional would need the guide's own datum,
   * which does not exist until the axis has been laid out, and each is reported by name rather than
   * dropped. `update` beats `enter`, as everywhere else.
   */
  private fun VegaValue.Obj.withGuideEncode(
    parts: Map<String, Map<String, String>>,
    subject: String,
    path: String,
  ): VegaValue.Obj {
    val encode = fields["encode"] as? VegaValue.Obj ?: return this
    val folded = LinkedHashMap(fields)
    for ((part, block) in encode.fields) {
      val channels = parts[part]
      if (channels == null) {
        diagnostics.warn(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "$subject encode block '$part' is not implemented; it was ignored",
          jsonPath = "$path.encode.$part",
        )
        continue
      }
      val entry = block as? VegaValue.Obj ?: continue
      // `enter` first, then `update` over it, matching the effective set everywhere else.
      for (pass in listOf("enter", "update")) {
        val set = entry.fields[pass] as? VegaValue.Obj ?: continue
        for ((channel, value) in set.fields) {
          val property = channels[channel]
          val constant = (value as? VegaValue.Obj)?.fields?.get("value")
          when {
            // Resolved later, against the item the guide is drawing, so nothing to say here.
            "$part.$pass.$channel" in RESOLVED_GUIDE_CHANNELS -> Unit
            // A guide writes its own text and position into `update` on every pass, so a
            // specification's `enter` for one of those is overwritten before anything is drawn.
            // Upstream ignores it too — `enter: {text: {value: 'E'}}` on an axis label leaves the
            // tick's own text — so saying "not implemented" would blame this engine for a rule
            // that is Vega's.
            pass == "enter" && channel in GUIDE_UPDATE_CHANNELS ->
              diagnostics.warn(
                DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
                "$subject encode sets '$channel' on '$part' in 'enter', which changes nothing: " +
                  "the guide writes that channel in 'update' on every pass and overwrites it. " +
                  "Move it to 'update'",
                jsonPath = "$path.encode.$part.$pass.$channel",
              )
            property == null ->
              diagnostics.warn(
                DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
                "$subject encode channel '$channel' on '$part' is not implemented; it was ignored",
                jsonPath = "$path.encode.$part.$pass.$channel",
              )
            constant == null ->
              diagnostics.warn(
                DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
                "$subject encode channel '$channel' on '$part' is only implemented as a constant " +
                  "'value'; it was ignored",
                jsonPath = "$path.encode.$part.$pass.$channel",
              )
            else -> folded[property] = constant
          }
        }
      }
    }
    return VegaValue.Obj(folded)
  }

  /**
   * Reads the `{prefix}Color`, `Width`, `Dash`, `Opacity`, `Font`, `FontWeight` and `FontStyle`
   * family for one part of a guide.
   *
   * Upstream spells all five parts the same way — the prefix is the only thing that changes — so
   * they are read the same way rather than five times over.
   */
  private fun VegaValue.Obj.guideStroke(prefix: String): GuideStroke =
    GuideStroke(
      color = fields["${prefix}Color"]?.takeIf { it is VegaValue.Str }?.asString(),
      width = (fields["${prefix}Width"] as? VegaValue.Num)?.value,
      dash =
        (fields["${prefix}Dash"] as? VegaValue.Arr)
          ?.values
          ?.map { it.asDouble() }
          ?.takeIf { values -> values.isNotEmpty() && values.all { it.isFinite() } },
      opacity = (fields["${prefix}Opacity"] as? VegaValue.Num)?.value,
      font = fields["${prefix}Font"]?.takeIf { it is VegaValue.Str }?.asString(),
      // Vega accepts either a keyword (`"bold"`) or a number (`700`); both reach the renderer as
      // text, so a number is normalized to its integer spelling rather than kept as a double.
      fontWeight =
        when (val weight = fields["${prefix}FontWeight"]) {
          is VegaValue.Str -> weight.value
          is VegaValue.Num -> weight.value.takeIf { it.isFinite() }?.toInt()?.toString()
          else -> null
        },
      fontStyle = fields["${prefix}FontStyle"]?.takeIf { it is VegaValue.Str }?.asString(),
    )

  // ---- titles ---------------------------------------------------------------

  /**
   * Parses the chart title.
   *
   * Vega accepts either a bare string or an object, and both mean the same thing; `encode`
   * overrides and the styling properties beyond font size are reported rather than partly honoured.
   */
  /**
   * A title's `dx` or `dy`, from the property or from its own `encode.update`.
   *
   * Upstream takes either; a specification that writes one usually writes it in the encode block,
   * because that is where every other guide's overrides go.
   */
  private fun titleNudge(obj: VegaValue.Obj, channel: String, path: String): NumberValue? {
    obj.numberOrSignal(channel, "$path.$channel")?.let {
      return it
    }
    val update = (obj.fields["encode"] as? VegaValue.Obj)?.fields?.get("update") as? VegaValue.Obj
    val entry = update?.fields?.get(channel) as? VegaValue.Obj ?: return null
    return entry.numberOrSignal("value", "$path.encode.update.$channel")
  }

  private fun parseTitle(value: VegaValue, path: String): TitleSpec? {
    // `config.title` supplies what the title does not say for itself — a theme setting the chart's
    // heading in one place. Merged the way an axis merges its own config, so a property written on
    // the title still wins.
    if (value is VegaValue.Str) {
      return parseTitle(
        GuideConfig.merge(
          VegaValue.Obj(linkedMapOf("text" to VegaValue.Str(value.value))),
          config.titleDefaults(),
        ),
        path,
      )
    }
    val own = value as? VegaValue.Obj ?: return unexpected("a title definition", path)
    val obj = GuideConfig.merge(own, config.titleDefaults())

    val textField = obj.fields["text"]
    val expression = (textField as? VegaValue.Obj)?.fields?.get("signal")?.asString()
    val text = textField?.takeIf { it is VegaValue.Str }?.asString() ?: ""
    if (text.isEmpty() && expression == null) {
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "A title needs a 'text'",
        jsonPath = path,
      )
      return null
    }

    own.reportUnhandled(
      "Title",
      path,
      TITLE_CONSUMED,
      mapOf(
        "encode" to "Only 'dx' and 'dy' are read from a title's encode block; the rest was ignored",
        "style" to "Title styles are not implemented",
        "limit" to "Title text limits are not implemented",
        "align" to "Title alignment follows 'anchor'; an explicit align is not implemented",
        "angle" to "Title rotation follows 'orient'; an explicit angle is not implemented",
      ),
    )

    return TitleSpec(
      text = text,
      textExpression = expression,
      subtitle = obj.fields["subtitle"]?.takeIf { it is VegaValue.Str }?.asString(),
      orient =
        obj.enumOrNull("orient", path, "title orientation") { Orient.fromName(it) } ?: Orient.TOP,
      anchor =
        obj.enumOrNull("anchor", path, "title anchor") { Anchor.fromName(it) } ?: Anchor.MIDDLE,
      frame = obj.fields["frame"]?.asString(),
      offset = obj.numberOrSignal("offset", "$path.offset"),
      subtitlePadding = obj.numberOrSignal("subtitlePadding", "$path.subtitlePadding"),
      fontSize = obj.numberOrSignal("fontSize", "$path.fontSize"),
      dx = titleNudge(obj, "dx", path),
      dy = titleNudge(obj, "dy", path),
      fontWeight =
        when (val weight = obj.fields["fontWeight"]) {
          is VegaValue.Str -> weight.value
          is VegaValue.Num -> weight.value.takeIf { it.isFinite() }?.toInt()?.toString()
          else -> null
        },
      subtitleFontSize = obj.numberOrSignal("subtitleFontSize", "$path.subtitleFontSize"),
      fontStyle = obj.fields["fontStyle"]?.takeIf { it is VegaValue.Str }?.asString(),
      subtitleFontStyle =
        obj.fields["subtitleFontStyle"]?.takeIf { it is VegaValue.Str }?.asString(),
      zindex = (obj.fields["zindex"] as? VegaValue.Num)?.value?.toInt() ?: 0,
    )
  }

  // ---- legends --------------------------------------------------------------

  /**
   * Parses a legend.
   *
   * Only what the runtime can build is modelled. `encode` overrides, `format`, `labelOverlap`,
   * `symbolLimit` and multi-column grids each report rather than being partly honoured, because a
   * legend that silently drops half its entries or ignores a formatter looks finished and is not.
   */
  private fun parseLegend(value: VegaValue, path: String): LegendSpec? {
    val own = value as? VegaValue.Obj ?: return unexpected("a legend definition", path)
    val obj =
      GuideConfig.merge(own, config.legendDefaults())
        .withGuideEncode(LEGEND_ENCODE_PARTS, "Legend", path)

    val spec =
      LegendSpec(
        fill = obj.fields["fill"]?.asString(),
        stroke = obj.fields["stroke"]?.asString(),
        size = obj.fields["size"]?.asString(),
        shape = obj.fields["shape"]?.asString(),
        opacity = obj.fields["opacity"]?.asString(),
        type = obj.enumOrNull("type", path, "legend type") { LegendType.fromName(it) },
        orient =
          obj.enumOrNull("orient", path, "legend orientation") { LegendOrient.fromName(it) }
            ?: LegendOrient.RIGHT,
        direction =
          obj.enumOrNull("direction", path, "legend direction") { Direction.fromName(it) },
        title = obj.fields["title"]?.takeIf { it is VegaValue.Str }?.asString(),
        values = (obj.fields["values"] as? VegaValue.Arr)?.values,
        format = obj.fields["format"]?.takeIf { it is VegaValue.Str }?.asString(),
        tickCount = obj.numberOrSignal("tickCount", "$path.tickCount"),
        offset = obj.numberOrSignal("offset", "$path.offset"),
        padding = obj.numberOrSignal("padding", "$path.padding"),
        titlePadding = obj.numberOrSignal("titlePadding", "$path.titlePadding"),
        titleOrient = legendTitleOrient(obj.fields["titleOrient"], "$path.titleOrient"),
        titleLimit = obj.numberOrSignal("titleLimit", "$path.titleLimit"),
        titleFontSize = obj.numberOrSignal("titleFontSize", "$path.titleFontSize"),
        labelFontSize = obj.numberOrSignal("labelFontSize", "$path.labelFontSize"),
        labelOffset = obj.numberOrSignal("labelOffset", "$path.labelOffset"),
        symbolType = obj.fields["symbolType"]?.asString(),
        symbolSize = obj.numberOrSignal("symbolSize", "$path.symbolSize"),
        symbolStrokeWidth = obj.numberOrSignal("symbolStrokeWidth", "$path.symbolStrokeWidth"),
        gradientLength = obj.numberOrSignal("gradientLength", "$path.gradientLength"),
        gradientThickness = obj.numberOrSignal("gradientThickness", "$path.gradientThickness"),
        rowPadding = obj.numberOrSignal("rowPadding", "$path.rowPadding"),
        columnPadding = obj.numberOrSignal("columnPadding", "$path.columnPadding"),
        columns = obj.numberOrSignal("columns", "$path.columns"),
        legendX = obj.numberOrSignal("legendX", "$path.legendX"),
        legendY = obj.numberOrSignal("legendY", "$path.legendY"),
        zindex = (obj.fields["zindex"] as? VegaValue.Num)?.value?.toInt() ?: 0,
        labelOverlap = obj.fields["labelOverlap"]?.asString(),
        labelSeparation = obj.numberOrSignal("labelSeparation", "$path.labelSeparation"),
        labelLimit = obj.numberOrSignal("labelLimit", "$path.labelLimit"),
        encode =
          (obj.fields["encode"] as? VegaValue.Obj)?.fields.orEmpty().mapValues { (part, block) ->
            parseEncode(block, "$path.encode.$part")
          },
        labelStyle = obj.guideStroke("label"),
        titleStyle = obj.guideStroke("title"),
        // `symbolStrokeColor`/`symbolStrokeWidth` rather than `symbolColor`/`symbolWidth`, so the
        // shared reader is pointed at the `symbolStroke` prefix and the dash and opacity are picked
        // up separately.
        symbolStyle =
          obj
            .guideStroke("symbolStroke")
            .copy(
              dash =
                (obj.fields["symbolDash"] as? VegaValue.Arr)
                  ?.values
                  ?.map { it.asDouble() }
                  ?.takeIf { values -> values.isNotEmpty() && values.all { it.isFinite() } },
              opacity = (obj.fields["symbolOpacity"] as? VegaValue.Num)?.value,
            ),
      )

    if (spec.scale == null) {
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "A legend needs at least one of 'fill', 'stroke', 'size', 'shape' or 'opacity' to say " +
          "which scale it describes",
        jsonPath = path,
      )
      return null
    }

    own.reportUnhandled(
      "Legend",
      path,
      LEGEND_CONSUMED,
      mapOf(
        "formatType" to "Legend label format types are not implemented",
        "symbolLimit" to "Legend entry limits are not implemented; every entry is shown",
        "gradientOpacity" to "Legend gradient opacity is not implemented",
        "titleAnchor" to "Legend title anchoring is not implemented",
      ),
    )
    return spec
  }

  /**
   * Reports every property of this object the parser neither consumed nor already explained.
   *
   * Enumerating the gap by hand is how the gap grew. The axis honoured fifteen of upstream's 74
   * properties and dropped the other fifty-nine without a word, each one arriving at a moment when
   * nobody was looking at the whole list. Naming what *is* consumed and reporting the remainder
   * inverts that: a property nobody thought about becomes a diagnostic rather than a silence, and
   * so does one upstream adds after this was written.
   *
   * [explained] comes first and says something specific about what will be drawn instead. Anything
   * left over gets the generic message, which is worth less than a tailored one and much more than
   * nothing.
   */
  private fun VegaValue.Obj.reportUnhandled(
    kind: String,
    path: String,
    consumed: Set<String>,
    explained: Map<String, String> = emptyMap(),
  ) {
    for ((key, reason) in explained) {
      if (fields[key] == null) continue
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "$reason; '$key' was ignored",
        jsonPath = "$path.$key",
      )
    }
    for (key in fields.keys) {
      if (key in consumed || key in explained) continue
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "$kind property '$key' is not implemented and was ignored",
        jsonPath = "$path.$key",
      )
    }
  }

  /**
   * Reads an enumerated property, reporting an unrecognized value instead of quietly defaulting.
   *
   * Returns `null` both for absent and for unrecognized, so the caller applies its own default; the
   * difference between the two is already in the diagnostics.
   */
  private fun <T> VegaValue.Obj.enumOrNull(
    key: String,
    path: String,
    what: String,
    parse: (String) -> T?,
  ): T? {
    val text = fields[key]?.asString() ?: return null
    val parsed = parse(text)
    if (parsed == null) {
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Unknown $what '$text'",
        jsonPath = "$path.$key",
      )
    }
    return parsed
  }

  // ---- marks ----------------------------------------------------------------

  private fun parseMark(value: VegaValue, path: String): MarkSpec? {
    val obj = value as? VegaValue.Obj ?: return unexpected("a mark definition", path)
    val typeName = obj.fields["type"]?.asString()
    if (typeName.isNullOrEmpty()) {
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "A mark needs a type",
        jsonPath = path,
      )
      return null
    }
    val type = MarkType.fromName(typeName)
    if (type == null) {
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_MARK,
        "Unknown mark type '$typeName'",
        jsonPath = "$path.type",
      )
      return null
    }

    val from = obj.fields["from"] as? VegaValue.Obj
    val facet = from?.fields?.get("facet")?.let { parseFacet(it, "$path.from.facet", type) }
    if (obj.fields["transform"] != null) {
      diagnostics.warn(
        DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
        "Mark-level transforms are not implemented",
        jsonPath = "$path.transform",
      )
    }
    val sort =
      (obj.fields["sort"] as? VegaValue.Obj)?.let { block ->
        val fields =
          when (val f = block.fields["field"]) {
            is VegaValue.Arr -> f.values.map { it.asString() }
            null -> emptyList()
            else -> listOf(f.asString())
          }.filter { it.isNotEmpty() }
        val orders =
          when (val o = block.fields["order"]) {
            is VegaValue.Arr -> o.values.map { it.asString() }
            null -> emptyList()
            else -> listOf(o.asString())
          }
        if (fields.isEmpty()) {
          diagnostics.warn(
            DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
            "Mark sort needs a 'field'; data order is preserved",
            jsonPath = "$path.sort",
          )
          null
        } else {
          MarkSort(fields, orders)
        }
      }

    if (type == MarkType.GROUP) reportUnsupportedGroupScope(obj, path)
    obj.reportUnhandled("Mark", path, MARK_CONSUMED)

    val (below, above) = config.markDefaults(typeName.lowercase(), markStyles(obj))

    return MarkSpec(
      type = type,
      name = obj.fields["name"]?.asString(),
      role = obj.fields["role"]?.takeIf { it is VegaValue.Str }?.asString(),
      from = from?.let { FromSpec(data = it.fields["data"]?.asString(), facet = facet) },
      sort = sort,
      encode = parseEncode(obj.fields["encode"], "$path.encode"),
      marks = parseArray(obj, "marks", path) { child, childPath -> parseMark(child, childPath) },
      axes = parseArray(obj, "axes", path) { child, childPath -> parseAxis(child, childPath) },
      data = parseArray(obj, "data", path) { child, childPath -> parseData(child, childPath) },
      signals =
        parseArray(obj, "signals", path) { child, childPath ->
          parseSignal(child, childPath, subscope = true)
        },
      scales = parseArray(obj, "scales", path) { child, childPath -> parseScale(child, childPath) },
      legends =
        parseArray(obj, "legends", path) { child, childPath -> parseLegend(child, childPath) },
      layout = obj.fields["layout"]?.let { parseLayout(it, "$path.layout") },
      title = obj.fields["title"]?.let { parseTitle(it, "$path.title") },
      zindex = (obj.fields["zindex"] as? VegaValue.Num)?.value?.toInt() ?: 0,
      interactive = obj.fields["interactive"]?.asBoolean() ?: true,
      aria = obj.fields["aria"]?.asBoolean() ?: true,
      clip = obj.fields["clip"]?.asBoolean() ?: false,
      configBelowDefaults = below.fields,
      configAboveDefaults = above.fields,
    )
  }

  /** A mark's `style`, which names `config.style` blocks and accepts one or several. */
  private fun markStyles(obj: VegaValue.Obj): List<String> =
    when (val style = obj.fields["style"]) {
      is VegaValue.Str -> listOf(style.value)
      is VegaValue.Arr -> style.values.map { it.asString() }
      else -> emptyList()
    }

  /**
   * Parses `from.facet`.
   *
   * Only the `groupby` form is modelled. The `field` form takes an array-valued field that has
   * already been partitioned upstream, which the dataflow would have to preserve through the whole
   * pipeline; it is reported rather than approximated by grouping on the field's value.
   */
  private fun parseFacet(value: VegaValue, path: String, type: MarkType): FacetSpec? {
    val obj = value as? VegaValue.Obj ?: return unexpected("a facet definition", path)
    if (type != MarkType.GROUP) {
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Only a group mark can be faceted, and this is a '${type.name.lowercase()}' mark",
        jsonPath = path,
      )
      return null
    }

    val name = obj.fields["name"]?.asString()
    val data = obj.fields["data"]?.asString()
    if (name.isNullOrEmpty() || data.isNullOrEmpty()) {
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "A facet needs both a 'name' to bind its partition to and a 'data' set to partition",
        jsonPath = path,
      )
      return null
    }

    val groupby = obj.fields["groupby"]?.let { stringList(it) }
    if (groupby.isNullOrEmpty()) {
      val reason = if (obj.fields["field"] != null) null else "A facet needs a 'groupby'"
      if (reason != null) {
        diagnostics.error(DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED, reason, jsonPath = path)
        return null
      }
    }
    val aggregate = (obj.fields["aggregate"] as? VegaValue.Obj)
    val ops =
      (aggregate?.fields?.get("ops") as? VegaValue.Arr)?.values?.map { it.asString() }.orEmpty()
    val aggFields =
      (aggregate?.fields?.get("fields") as? VegaValue.Arr)
        ?.values
        ?.map {
          it.asString().takeIf { name -> name.isNotEmpty() && it !is VegaValue.Null }
        }
        .orEmpty()
    val names =
      (aggregate?.fields?.get("as") as? VegaValue.Arr)?.values?.map { it.asString() }.orEmpty()
    val measures = ops.mapIndexed { index, op ->
      val field = aggFields.getOrNull(index)
      FacetMeasure(
        op = op,
        field = field,
        name =
          names.getOrNull(index)?.takeIf { it.isNotEmpty() }
            ?: if (field == null) op else "${'$'}{op}_${'$'}field",
      )
    }
    return FacetSpec(
      name = name,
      data = data,
      groupby = groupby.orEmpty(),
      aggregate = measures,
      field = obj.fields["field"]?.asString()?.takeIf { it.isNotEmpty() },
    )
  }

  /**
   * Parses a group's `layout`.
   *
   * Only the placement is modelled. Headers, footers and titles are separate marks upstream
   * generates around the grid, and they are reported rather than silently dropped, because a
   * trellis without its row and column labels is a chart nobody can read.
   */
  private fun parseLayout(value: VegaValue, path: String): LayoutSpec? {
    val obj = value as? VegaValue.Obj ?: return unexpected("a layout definition", path)
    val unsupported =
      mapOf(
        "headerBand" to "Layout header bands are not implemented",
        "footerBand" to "Layout footer bands are not implemented",
        "titleBand" to "Layout title bands are not implemented",
        "align" to "Only per-cell ('each') grid alignment is implemented",
        "bounds" to "Only full-bounds grid layout is implemented",
        "center" to "Centring cells within their row or column is not implemented",
        "offset" to "Layout offsets are not implemented",
      )
    for ((key, reason) in unsupported) {
      if (obj.fields[key] == null) continue
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "$reason; '$key' was ignored",
        jsonPath = "$path.$key",
      )
    }

    // `padding` is either one number for both directions or a per-direction object.
    val padding = obj.fields["padding"]
    val rowPadding: NumberValue?
    val columnPadding: NumberValue?
    if (padding is VegaValue.Obj) {
      rowPadding = padding.numberOrSignal("row", "$path.padding.row")
      columnPadding = padding.numberOrSignal("column", "$path.padding.column")
    } else {
      val both = obj.numberOrSignal("padding", "$path.padding")
      rowPadding = both
      columnPadding = both
    }

    return LayoutSpec(
      columns = obj.numberOrSignal("columns", "$path.columns"),
      rowPadding = rowPadding,
      columnPadding = columnPadding,
    )
  }

  /** Reports the parts of a group's scope that the runtime cannot build. */
  private fun reportUnsupportedGroupScope(obj: VegaValue.Obj, path: String) {
    val unsupported = mapOf("projections" to "Geographic projections are out of scope")
    for ((key, reason) in unsupported) {
      val value = obj.fields[key] ?: continue
      if (value is VegaValue.Arr && value.values.isEmpty()) continue
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "$reason; '$key' on this group was ignored",
        jsonPath = "$path.$key",
      )
    }
  }

  /** Vega accepts a single string or an array of them wherever a field list is allowed. */
  private fun stringList(value: VegaValue): List<String> =
    when (value) {
      is VegaValue.Arr ->
        value.values.mapNotNull { it.takeIf { v -> v !is VegaValue.Null }?.asString() }
      is VegaValue.Null -> emptyList()
      else -> listOf(value.asString())
    }

  private fun parseEncode(value: VegaValue?, path: String): EncodeSpec {
    val obj = value as? VegaValue.Obj ?: return EncodeSpec()
    for (key in obj.fields.keys) {
      if (key !in setOf("enter", "update", "exit", "hover")) {
        diagnostics.warn(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "Unknown encode set '$key'",
          jsonPath = "$path.$key",
        )
      }
    }
    return EncodeSpec(
      enter = parseEncodeEntry(obj.fields["enter"], "$path.enter"),
      update = parseEncodeEntry(obj.fields["update"], "$path.update"),
      exit = parseEncodeEntry(obj.fields["exit"], "$path.exit"),
      hover = parseEncodeEntry(obj.fields["hover"], "$path.hover"),
    )
  }

  private fun parseEncodeEntry(value: VegaValue?, path: String): EncodeEntry {
    val obj = value as? VegaValue.Obj ?: return emptyMap()
    obj.reportUnhandled("Encode", path, ENCODE_CONSUMED, ENCODE_UNSUPPORTED)
    val result = LinkedHashMap<String, ChannelValue>(obj.fields.size)
    for ((channel, definition) in obj.fields) {
      parseChannel(channel, definition, "$path.$channel")?.let { result[channel] = it }
    }
    return result
  }

  private fun parseChannel(channel: String, value: VegaValue, path: String): ChannelValue? {
    // Vega allows an array of conditional productions, each optionally guarded by a `test`
    // expression; the first passing entry wins and a trailing unguarded entry is the default.
    if (value is VegaValue.Arr) {
      val rules =
        value.values.mapIndexedNotNull { index, rule ->
          val ruleObj = rule as? VegaValue.Obj
          val test = ruleObj?.fields?.get("test")?.asString()
          val production =
            parseChannel(channel, rule, "$path[$index]") ?: return@mapIndexedNotNull null
          ConditionalRule(test, production)
        }
      if (rules.isEmpty()) {
        diagnostics.warn(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "Channel '$channel' has no usable production rules",
          jsonPath = path,
        )
        return null
      }
      return ChannelValue.Conditional(rules)
    }
    val obj =
      value as? VegaValue.Obj ?: return ChannelValue.Constant(value) // a bare literal is a constant

    // The scale comes first, because it *wraps* whatever supplies the value rather than competing
    // with it: upstream builds the base from `signal`, `field` or `value` and then runs it through
    // the scale. Reading `signal` first would drop the scale on `{"scale": "x", "signal": "year"}`.
    val scale = obj.fields["scale"]
    if (scale != null) {
      val scaleRef = if (scale is VegaValue.Str) null else fieldPath(scale, "$path.scale")
      if (scale !is VegaValue.Str && scaleRef == null) return null
      return adjusted(
        ChannelValue.Scaled(
          scale = (scale as? VegaValue.Str)?.value ?: "",
          scaleRef = scaleRef,
          field = fieldPath(obj.fields["field"], "$path.field"),
          value = obj.fields["value"],
          signal = obj.fields["signal"]?.asString()?.takeIf { it.isNotEmpty() },
          band = obj.optionalNumber("band", "$path.band"),
        ),
        obj,
        path,
      )
    }

    obj.fields["signal"]?.let {
      return adjusted(ChannelValue.Signal(it.asString()), obj, path)
    }

    obj.fields["value"]?.let {
      return adjusted(ChannelValue.Constant(it), obj, path)
    }
    obj.fields["field"]?.let {
      val resolved = fieldPath(it, "$path.field") ?: return null
      return adjusted(ChannelValue.Field(resolved), obj, path)
    }

    diagnostics.warn(
      DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
      "Channel '$channel' has no value, field or scale reference",
      jsonPath = path,
    )
    return null
  }

  /**
   * Wraps [base] in whatever arithmetic the value reference asked for.
   *
   * `exponent`, `mult`, `offset` and `round` are part of *every* value reference, not just a scaled
   * one — upstream appends them to the generated expression after the scale has been applied — so
   * they are read here rather than on any one form. A reference with none of them is left alone, so
   * the common case allocates nothing extra.
   */
  private fun adjusted(base: ChannelValue, obj: VegaValue.Obj, path: String): ChannelValue {
    val exponent = obj.fields["exponent"]?.let { adjustment(it, "$path.exponent") }
    val mult = obj.fields["mult"]?.let { adjustment(it, "$path.mult") }
    val offset = obj.fields["offset"]?.let { adjustment(it, "$path.offset") }
    val round = obj.fields["round"]?.asBoolean() ?: false
    if (exponent == null && mult == null && offset == null && !round) return base
    return ChannelValue.Adjusted(base, exponent, mult, offset, round)
  }

  /** One of those adjustments, which is itself a value reference and not necessarily a number. */
  private fun adjustment(value: VegaValue, path: String): ChannelValue? =
    if (value is VegaValue.Obj) parseChannel("(adjustment)", value, path)
    else ChannelValue.Constant(value)

  /**
   * Resolves a field reference, which Vega allows to be a string or `{"group": ...}` / `{"datum":
   * ...}`.
   */
  /**
   * Resolves a field reference, which Vega allows to be a string or one of four object forms.
   *
   * Each object form reads from somewhere other than the row being drawn, so none of them can be
   * flattened to a column name here — they are carried into the encoder, which is the only place
   * that knows what the enclosing group is or what a signal resolved to.
   */
  private fun fieldPath(value: VegaValue?, path: String): FieldRef? =
    when (value) {
      null -> null
      is VegaValue.Str -> FieldRef.Plain(value.value)
      is VegaValue.Obj -> {
        // `{"parent": {...}}` names the parent's column with another reference rather than a
        // literal, so it recurses before anything is read as a string.
        (value.fields["parent"] as? VegaValue.Obj)?.let { nested ->
          return fieldPath(nested, "$path.parent")?.let { FieldRef.ParentOf(it) }
        }
        val group = value.fields["group"]?.asString()
        val parent = value.fields["parent"]?.asString()
        val signal = value.fields["signal"]?.asString()
        val datum = value.fields["datum"]?.asString()
        when {
          !group.isNullOrEmpty() -> FieldRef.Group(group)
          !parent.isNullOrEmpty() -> FieldRef.Parent(parent)
          !signal.isNullOrEmpty() -> FieldRef.Signal(signal)
          !datum.isNullOrEmpty() -> FieldRef.Datum(datum)
          else -> {
            diagnostics.error(
              DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
              "A field reference object must name one of 'group', 'parent', 'signal' or 'datum'",
              jsonPath = path,
            )
            null
          }
        }
      }
      else -> {
        diagnostics.error(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "A field reference must be a string or an object",
          jsonPath = path,
        )
        null
      }
    }

  // ---- helpers --------------------------------------------------------------

  private fun <T> parseArray(
    owner: VegaValue.Obj,
    key: String,
    ownerPath: String = "$",
    parse: (VegaValue, String) -> T?,
  ): List<T> {
    val array = owner.fields[key] ?: return emptyList()
    if (array !is VegaValue.Arr) {
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "'$key' must be an array",
        jsonPath = "$ownerPath.$key",
      )
      return emptyList()
    }
    return array.values.mapIndexedNotNull { index, item ->
      parse(item, "$ownerPath.$key[$index]")
    }
  }

  private fun <T> unexpected(what: String, path: String): T? {
    diagnostics.error(
      DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
      "Expected $what to be an object",
      jsonPath = path,
    )
    return null
  }

  /**
   * Reads a numeric property that Vega allows to be a signal.
   *
   * Returning `null` for an absent property lets the caller apply its own default, which differs by
   * property, rather than baking one in here.
   */
  private fun VegaValue.Obj.numberOrSignal(key: String, path: String): NumberValue? {
    val value = fields[key] ?: return null
    if (value is VegaValue.Obj) {
      val signal = value.fields["signal"]?.asString()
      if (signal != null) return NumberValue.Signal(signal)
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "'$key' must be a number or a signal reference",
        jsonPath = path,
      )
      return null
    }
    val number = value.asDouble()
    if (number.isNaN()) {
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "'$key' must be a number, found ${value.asString()}",
        jsonPath = path,
      )
      return null
    }
    return NumberValue.Constant(number)
  }

  private fun VegaValue.Obj.optionalNumber(key: String, path: String): Double? {
    val value = fields[key] ?: return null
    val number = value.asDouble()
    if (number.isNaN()) {
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "'$key' must be a number, found ${value.asString()}",
        jsonPath = path,
      )
      return null
    }
    return number
  }
}
