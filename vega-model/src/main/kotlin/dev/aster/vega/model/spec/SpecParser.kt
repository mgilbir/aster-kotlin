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
    // `config.range` is not a guide block: its entries are what a *named* range stands for.
    "range",
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
    "labelBound",
    "tickMinStep",
    "labelOffset",
    "aria",
    "description",
    "tickBand",
    "position",
    "translate",
    "tickRound",
    "titleLimit",
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
    "tickOffset",
    "tickExtra",
    "gridScale",
    "labelFlush",
    "labelFlushOffset",
    "minExtent",
    "maxExtent",
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
      listOf(
          "Color",
          "Width",
          "Dash",
          // `Stroke` has carried both of these since it was written and no guide passed either, so
          // every tick was butt-capped and every dash pattern started at the line's end.
          "DashOffset",
          "Cap",
          "Opacity",
          "Font",
          "FontWeight",
          "FontStyle",
          // Only meaningful on a text part, and a guide that has no such property simply never
          // writes one — consuming a name upstream does not define costs nothing.
          "Align",
          "Baseline",
          "LineHeight",
        )
        .map { "$prefix$it" }
    }
    .toSet()

private val AXIS_UNSUPPORTED = emptyMap<String, String>()

/**
 * What a title's text falls back to once a `style` has taken the `group-title` slot.
 *
 * These are `vega-scenegraph`'s own defaults, not the title's: `fontSize(item)` answers 11 for a
 * text item that names no size, and nothing supplies a weight. Materialising them means the
 * runtime's `group-title` defaults — 13 point, bold — stop applying, which is the whole effect of
 * naming a style.
 *
 * The **subtitle** is not here. Its own style slot is `group-subtitle`, which a title's `style`
 * never takes, so a subtitle under a styled heading keeps its 12 point.
 */
private val TITLE_RENDERER_FALLBACKS: VegaValue.Obj =
  VegaValue.Obj(
    linkedMapOf("fontSize" to VegaValue.Num(11.0), "fontWeight" to VegaValue.Str("normal"))
  )

/** Scale properties this engine reads. */
private val SCALE_CONSUMED =
  setOf(
    "domainImplicit",
    "domainRaw",
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

/**
 * Scale properties this engine parses but cannot honour.
 *
 * Empty. `domainMin`, `domainMax`, `domainMid`, `bins`, `domainRaw` and `domainImplicit` were the
 * six that used to be here; all 23 of upstream's scale properties are read.
 */
private val SCALE_UNSUPPORTED = emptyMap<String, String>()

/** Legend properties this engine reads. */
private val LEGEND_CONSUMED =
  setOf(
    "formatType",
    "tickMinStep",
    "clipHeight",
    "symbolLimit",
    "aria",
    "description",
    "symbolDashOffset",
    "symbolFillColor",
    "symbolOffset",
    "gradientStrokeColor",
    "gradientStrokeWidth",
    "gradientOpacity",
    // The legend's own background.
    "fillColor",
    "strokeColor",
    "cornerRadius",
    "fill",
    "stroke",
    "size",
    "shape",
    "opacity",
    // On a legend these two name **scales**, not the outline round the legend: that one takes its
    // width and dash from `config.legend` alone.
    "strokeWidth",
    "strokeDash",
    "gridAlign",
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
    "titleAnchor",
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
    "aria",
    "name",
    "interactive",
    "color",
    "subtitleColor",
    "subtitleFont",
    "subtitleFontWeight",
    "lineHeight",
    "subtitleLineHeight",
    "align",
    "angle",
    "baseline",
    "limit",
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
    "style",
    "on",
  )

/** The formats a loaded document can be read as. */
private val READABLE_FORMATS = setOf("json", "csv", "tsv", "dsv", "topojson")

/** Projection properties this engine reads. */
private val PROJECTION_CONSUMED =
  setOf(
    "name",
    "type",
    "scale",
    "translate",
    "center",
    "rotate",
    "angle",
    "precision",
    "clipAngle",
    "clipExtent",
    "parallels",
    "pointRadius",
    "reflectX",
    "reflectY",
    "fit",
    "extent",
    "size",
  )

/** Layout properties this engine reads; the rest are named in [SpecParser.parseLayout]. */
private val LAYOUT_CONSUMED =
  setOf(
    "columns",
    "padding",
    "align",
    "bounds",
    "center",
    "headerBand",
    "footerBand",
    "titleBand",
    "titleAnchor",
    "offset",
  )

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
    // The channels that transform an item about its own anchor rather than moving it.
    "scaleX",
    "scaleY",
    "aspect",
    "smooth",
    "dir",
    "lineBreak",
    "lineHeight",
    "strokeDashOffset",
    "strokeMiterLimit",
    "strokeOffset",
    "strokeForeground",
    "cornerRadiusTopLeft",
    "cornerRadiusTopRight",
    "cornerRadiusBottomLeft",
    "cornerRadiusBottomRight",
    "clip",
    "blend",
    "tension",
    "theta",
    "radius",
    "limit",
    "ellipsis",
    "cursor",
    "tooltip",
    "zindex",
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

/**
 * Encode channels this engine parses but cannot draw.
 *
 * Empty, and worth keeping as the place the next gap goes rather than deleting: every channel in
 * Vega's encoding vocabulary now reaches the scene. What used to be here — per-corner radii, curve
 * tension, polar `theta`/`radius`, `blend`, `limit`/`ellipsis` and `clip` — is implemented and
 * covered by [dev.aster.vega.model.spec] and the differential fixtures.
 */
private val ENCODE_UNSUPPORTED = emptyMap<String, String>()

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
        projections =
          parseArray(root, "projections") { value, path -> parseProjection(value, path) },
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
    val unsupported = mapOf("usermeta" to "usermeta is ignored")
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

    val declared = obj.fields["values"]
    val values = (declared as? VegaValue.Arr)?.values
    // An inline `values` that is an object is the document itself, not a row: a GeoJSON
    // `FeatureCollection` or a TopoJSON topology, read through this dataset's own `format`.
    val document = declared?.takeIf { it is VegaValue.Obj }
    val urlValue = obj.fields["url"]
    val urlSignal = (urlValue as? VegaValue.Obj)?.fields?.get("signal")?.asString()
    val url = if (urlSignal == null) urlValue?.asString() else null
    val format = obj.fields["format"] as? VegaValue.Obj
    val parse = LinkedHashMap<String, String>()
    var parseAuto = false
    if (format != null) {
      for ((key, value) in format.fields) {
        if (key == "type" || key == "property" || key == "delimiter") continue
        // The TopoJSON three: which object in the file, and — for a mesh — which of its arcs.
        if (key == "feature" || key == "mesh" || key == "filter") continue
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
      document = document,
      url = url,
      urlSignal = urlSignal,
      formatType = formatType,
      property = format?.fields?.get("property")?.asString()?.takeIf { it.isNotEmpty() },
      feature = format?.fields?.get("feature")?.asString()?.takeIf { it.isNotEmpty() },
      mesh = format?.fields?.get("mesh")?.asString()?.takeIf { it.isNotEmpty() },
      meshFilter = format?.fields?.get("filter")?.asString()?.takeIf { it.isNotEmpty() },
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

  /**
   * A `projections` entry.
   *
   * Almost every property may be a signal, and the ones that are lists may be lists *of* signals —
   * `"rotate": [{"signal": "r0"}, {"signal": "r1"}]` is how a map lets a reader turn the globe, and
   * it is the common form rather than an exotic one.
   */
  private fun parseProjection(value: VegaValue, path: String): ProjectionSpec? {
    val obj = value as? VegaValue.Obj ?: return unexpected("a projection definition", path)
    val name = obj.fields["name"]?.asString()
    if (name.isNullOrEmpty()) {
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "A projection needs a name",
        jsonPath = path,
      )
      return null
    }
    val typeValue = obj.fields["type"]
    val typeSignal = (typeValue as? VegaValue.Obj)?.fields?.get("signal")?.asString()
    obj.reportUnhandled("Projection", path, PROJECTION_CONSUMED)
    return ProjectionSpec(
      name = name,
      type = if (typeSignal == null) typeValue?.asString()?.takeIf { it.isNotEmpty() } else null,
      typeSignal = typeSignal,
      scale = obj.numberOrSignal("scale", "$path.scale"),
      translate = numberList(obj.fields["translate"], "$path.translate"),
      center = numberList(obj.fields["center"], "$path.center"),
      rotate = numberList(obj.fields["rotate"], "$path.rotate"),
      angle = obj.numberOrSignal("angle", "$path.angle"),
      precision = obj.numberOrSignal("precision", "$path.precision"),
      clipAngle = obj.numberOrSignal("clipAngle", "$path.clipAngle"),
      clipExtent = numberPairs(obj.fields["clipExtent"], "$path.clipExtent"),
      parallels = numberList(obj.fields["parallels"], "$path.parallels"),
      pointRadius = obj.numberOrSignal("pointRadius", "$path.pointRadius"),
      reflectX = obj.numberOrSignal("reflectX", "$path.reflectX"),
      reflectY = obj.numberOrSignal("reflectY", "$path.reflectY"),
      fit = obj.fields["fit"],
      extent = numberPairs(obj.fields["extent"], "$path.extent"),
      size = numberList(obj.fields["size"], "$path.size"),
    )
  }

  /** A list written out, or one signal that produces the whole thing. */
  private fun numberList(value: VegaValue?, path: String): NumberList {
    if (value == null) return NumberList.None
    (value as? VegaValue.Obj)?.fields?.get("signal")?.asString()?.let {
      return NumberList.Signal(it)
    }
    val array = value as? VegaValue.Arr ?: return NumberList.None
    return NumberList.Items(
      array.values.mapIndexedNotNull { index, entry ->
        val holder = VegaValue.Obj(linkedMapOf("v" to entry))
        holder.numberOrSignal("v", "$path[$index]")
      }
    )
  }

  private fun numberPairs(value: VegaValue?, path: String): List<NumberList> {
    val array = value as? VegaValue.Arr ?: return emptyList()
    return array.values.mapIndexed { index, entry -> numberList(entry, "$path[$index]") }
  }

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
      domainRaw =
        obj.fields["domainRaw"]?.let {
          parseDomain(VegaValue.Obj(mapOf("domain" to it)), "$path.domainRaw")
        },
      domainImplicit = obj.fields["domainImplicit"]?.asBoolean() ?: false,
      domainMin = obj.numberOrSignal("domainMin", "$path.domainMin"),
      domainMax = obj.numberOrSignal("domainMax", "$path.domainMax"),
      domainMid = obj.numberOrSignal("domainMid", "$path.domainMid"),
      range = parseRange(obj.fields["range"], "$path.range"),
      reverse = obj.fields["reverse"]?.takeIf { it !is VegaValue.Obj }?.asBoolean() ?: false,
      reverseSignal = (obj.fields["reverse"] as? VegaValue.Obj)?.fields?.get("signal")?.asString(),
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
      interpolate = interpolationSpace(obj.fields["interpolate"], "$path.interpolate"),
      interpolateGamma =
        ((obj.fields["interpolate"] as? VegaValue.Obj)?.fields?.get("gamma") as? VegaValue.Num)
          ?.value
          ?.takeIf { it.isFinite() && it > 0.0 },
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

  /**
   * A colour interpolation space, written as a name or as `{"type": ..., "gamma": ...}`.
   *
   * The object form is Vega's and its `gamma` is reported rather than silently dropped: in d3 only
   * `interpolateRgb` has one, and it bends the ramp's middle without moving either end — so a chart
   * that asked for it and got the plain ramp would look composed and be wrong in the middle.
   */
  private fun interpolationSpace(value: VegaValue?, path: String): String? =
    when (value) {
      null -> null
      is VegaValue.Str -> value.value.takeIf { it.isNotEmpty() }
      is VegaValue.Obj -> {
        value.fields["type"]?.asString()?.takeIf { it.isNotEmpty() }
      }
      else -> null
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
      // A named range is a **key into `config.range`** before it is anything else, and upstream
      // substitutes and re-reads: `"range": "category"` under a theme that sets
      // `config.range.category` to its own palette means that palette, not `tableau10`. Only when
      // the
      // configuration says nothing does the name fall through to `width`, `height` or the built-in
      // defaults. Substituting here rather than in the resolver is upstream's own arrangement, and
      // it
      // is what lets a theme's `category` be a scheme where the default is a literal list.
      is VegaValue.Str ->
        config.rangeDefault(value.value)?.let { parseRange(it, path) }
          ?: RangeSpec.Named(value.value)
      is VegaValue.Arr -> RangeSpec.Literal(value.values)
      is VegaValue.Obj -> {
        val scheme = value.fields["scheme"]
        val signal = value.fields["signal"]?.asString()
        val step = value.fields["step"]
        val count = value.fields["count"]
        val data = value.fields["data"]
        val field = value.fields["field"]?.asString()
        val countSignal = (count as? VegaValue.Obj)?.fields?.get("signal")?.asString()
        if (count != null && count !is VegaValue.Num && countSignal == null) {
          diagnostics.warn(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "A scheme 'count' must be a number or a signal; the whole scheme will be used",
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
              countSignal,
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

    val band =
      obj.fields["tickBand"]?.asString()?.takeIf { it == "extent" || it == "center" }
        ?: run {
          obj.fields["tickBand"]?.asString()?.let { stated ->
            diagnostics.error(
              DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
              "Unknown tickBand '$stated'; the only values are 'center' and 'extent'",
              jsonPath = "$path.tickBand",
            )
          }
          null
        }

    return AxisSpec(
      scale = scale,
      orient = orient,
      title = guideTitleText(obj.fields["title"]),
      titleExpression = (obj.fields["title"] as? VegaValue.Obj)?.fields?.get("signal")?.asString(),
      titlePadding = obj.numberOrSignal("titlePadding", "$path.titlePadding"),
      titleFontSize = obj.numberOrSignal("titleFontSize", "$path.titleFontSize"),
      titleAnchor = obj.enumOrNull("titleAnchor", path, "title anchor") { Anchor.fromName(it) },
      titleLimit = obj.numberOrSignal("titleLimit", "$path.titleLimit"),
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
      // `tickBand` is a shorthand for these three, and it wins: upstream's `tickBand()` reads the
      // others only when it is absent. `"extent"` puts a band scale's ticks on the band edges.
      bandPosition =
        when (band) {
          "extent" -> NumberValue.Constant(1.0)
          "center" -> NumberValue.Constant(0.5)
          else -> obj.numberOrSignal("bandPosition", "$path.bandPosition")
        },
      // Only `"extent"` zeroes the offset. `"center"` sets the band position and the extra tick and
      // leaves `tickOffset` alone — so a band axis keeps the `-0.5` that `config.axisBand` gives
      // it,
      // which is what corrects the half pixel the axis group's own translation adds.
      tickOffset =
        if (band == "extent") NumberValue.Constant(0.0)
        else obj.numberOrSignal("tickOffset", "$path.tickOffset"),
      tickExtra =
        if (band != null) band == "extent" else obj.fields["tickExtra"]?.asBoolean() ?: false,
      tickBand = band,
      labelOffset = obj.numberOrSignal("labelOffset", "$path.labelOffset"),
      tickMinStep = obj.numberOrSignal("tickMinStep", "$path.tickMinStep"),
      // The same shape as `labelFlush`: `true` is one unit, a number is itself, `false` is nothing.
      labelBound =
        when (val bound = obj.fields["labelBound"]) {
          is VegaValue.Bool -> if (bound.value) 1.0 else null
          is VegaValue.Num -> bound.value.takeIf { it.isFinite() }
          else -> null
        },
      aria = obj.fields["aria"]?.asBoolean() ?: true,
      description = obj.fields["description"]?.asString()?.takeIf { it.isNotBlank() },
      position = obj.numberOrSignal("position", "$path.position"),
      translate = obj.numberOrSignal("translate", "$path.translate"),
      tickRound = obj.fields["tickRound"]?.asBoolean(),
      gridScale = obj.fields["gridScale"]?.takeIf { it is VegaValue.Str }?.asString(),
      labelFlush = flushThreshold(obj.fields["labelFlush"]),
      labelFlushOffset = obj.numberOrSignal("labelFlushOffset", "$path.labelFlushOffset"),
      minExtent = obj.numberOrSignal("minExtent", "$path.minExtent"),
      maxExtent = obj.numberOrSignal("maxExtent", "$path.maxExtent"),
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
  /**
   * A guide's `title`, which upstream also lets be an **array** of lines.
   *
   * Axis and legend titles took the string form only, and an array was dropped without a word — a
   * legend headed with two lines came out with no heading at all. Joined with the newline this
   * engine lays lines out on, the same way a chart title's is.
   */
  private fun guideTitleText(value: VegaValue?): String? =
    when (value) {
      is VegaValue.Arr -> value.values.joinToString("\n") { it.asString() }.ifEmpty { null }
      is VegaValue.Str -> value.value
      else -> null
    }

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
      dashOffset = (fields["${prefix}DashOffset"] as? VegaValue.Num)?.value,
      cap = fields["${prefix}Cap"]?.takeIf { it is VegaValue.Str }?.asString(),
      opacity = (fields["${prefix}Opacity"] as? VegaValue.Num)?.value,
      font = fields["${prefix}Font"]?.takeIf { it is VegaValue.Str }?.asString(),
      align = fields["${prefix}Align"]?.takeIf { it is VegaValue.Str }?.asString(),
      baseline = fields["${prefix}Baseline"]?.takeIf { it is VegaValue.Str }?.asString(),
      lineHeight = (fields["${prefix}LineHeight"] as? VegaValue.Num)?.value,
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
  /**
   * A title's `style`, which **replaces** the layer its own look comes from.
   *
   * Upstream builds the title's text mark with `style: "group-title"` and lets a specification's
   * `style` take that slot instead — so naming one does not decorate the heading, it removes the
   * 13-point bold and leaves whatever the named block says. What it does not say falls through to
   * the *renderer's* defaults, which are 11 point and unweighted, not the title's.
   *
   * Written as two configuration layers beneath `config.title` so the ordinary precedence still
   * holds: a property on the title beats the theme, which beats the style, which beats the
   * renderer. The names are translated because a style block speaks in mark channels — `fill` —
   * where a title speaks in its own — `color`.
   */
  private fun titleStyleLayers(own: VegaValue.Obj): List<VegaValue.Obj> {
    val names = markStyles(own)
    if (names.isEmpty()) return emptyList()
    val translated = LinkedHashMap<String, VegaValue>()
    for (name in names) {
      for ((key, field) in config.styleBlock(name).fields) {
        translated[if (key == "fill") "color" else key] = field
      }
    }
    return listOf(TITLE_RENDERER_FALLBACKS, VegaValue.Obj(translated))
  }

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
    val obj = GuideConfig.merge(own, titleStyleLayers(own) + config.titleDefaults())

    val textField = obj.fields["text"]
    val expression = (textField as? VegaValue.Obj)?.fields?.get("signal")?.asString()
    // An **array** is upstream's multi-line form, for a title and for a subtitle alike, and it was
    // rejected outright: a heading written as two lines produced "A title needs a 'text'" and no
    // chart at all. Joined with the newline this engine lays lines out on.
    val text =
      when (textField) {
        is VegaValue.Arr -> textField.values.joinToString("\n") { it.asString() }
        else -> textField?.takeIf { it is VegaValue.Str }?.asString() ?: ""
      }
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
        "encode" to "Only 'dx' and 'dy' are read from a title's encode block; the rest was ignored"
      ),
    )

    return TitleSpec(
      text = text,
      textExpression = expression,
      subtitle =
        when (val sub = obj.fields["subtitle"]) {
          is VegaValue.Arr -> sub.values.joinToString("\n") { it.asString() }
          else -> sub?.takeIf { it is VegaValue.Str }?.asString()
        },
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
      color = obj.fields["color"]?.asString()?.takeIf { it.isNotEmpty() },
      font = obj.fields["font"]?.asString()?.takeIf { it.isNotEmpty() },
      subtitleColor = obj.fields["subtitleColor"]?.asString()?.takeIf { it.isNotEmpty() },
      subtitleFont = obj.fields["subtitleFont"]?.asString()?.takeIf { it.isNotEmpty() },
      subtitleFontWeight =
        when (val weight = obj.fields["subtitleFontWeight"]) {
          is VegaValue.Str -> weight.value
          is VegaValue.Num -> weight.value.takeIf { it.isFinite() }?.toInt()?.toString()
          else -> null
        },
      lineHeight = obj.numberOrSignal("lineHeight", "$path.lineHeight"),
      subtitleLineHeight = obj.numberOrSignal("subtitleLineHeight", "$path.subtitleLineHeight"),
      align = obj.fields["align"]?.asString()?.takeIf { it.isNotEmpty() },
      angle = obj.numberOrSignal("angle", "$path.angle"),
      baseline = obj.fields["baseline"]?.asString()?.takeIf { it.isNotEmpty() },
      limit = obj.numberOrSignal("limit", "$path.limit"),
      aria = obj.fields["aria"]?.asBoolean() ?: true,
      name = obj.fields["name"]?.asString()?.takeIf { it.isNotEmpty() },
      interactive = obj.fields["interactive"]?.asBoolean() ?: true,
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
  /** A `config.legend` property, without the legend's own value layered over it. */
  private fun legendConfig(key: String): VegaValue? =
    config.legendDefaults().firstNotNullOfOrNull { it.fields[key] }

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
        // Read from the legend's **own** object and not from the config-layered one: this is the
        // one
        // property name whose two meanings collide. `config.legend.strokeWidth` is the width of the
        // outline drawn round the legend, and layering it in would turn a themed border into a
        // channel naming a scale called "2".
        strokeWidthScale = own.fields["strokeWidth"]?.asString(),
        strokeDashScale = own.fields["strokeDash"]?.asString(),
        gridAlign = obj.fields["gridAlign"]?.asString(),
        type = obj.enumOrNull("type", path, "legend type") { LegendType.fromName(it) },
        orient =
          obj.enumOrNull("orient", path, "legend orientation") { LegendOrient.fromName(it) }
            ?: LegendOrient.RIGHT,
        direction =
          obj.enumOrNull("direction", path, "legend direction") { Direction.fromName(it) },
        title = guideTitleText(obj.fields["title"]),
        titleExpression =
          (obj.fields["title"] as? VegaValue.Obj)?.fields?.get("signal")?.asString(),
        values = (obj.fields["values"] as? VegaValue.Arr)?.values,
        format = obj.fields["format"]?.takeIf { it is VegaValue.Str }?.asString(),
        tickCount = obj.numberOrSignal("tickCount", "$path.tickCount"),
        offset = obj.numberOrSignal("offset", "$path.offset"),
        padding = obj.numberOrSignal("padding", "$path.padding"),
        titlePadding = obj.numberOrSignal("titlePadding", "$path.titlePadding"),
        titleOrient = legendTitleOrient(obj.fields["titleOrient"], "$path.titleOrient"),
        titleAnchor =
          obj.enumOrNull("titleAnchor", path, "legend title anchor") { Anchor.fromName(it) },
        titleLimit = obj.numberOrSignal("titleLimit", "$path.titleLimit"),
        titleFontSize = obj.numberOrSignal("titleFontSize", "$path.titleFontSize"),
        labelFontSize = obj.numberOrSignal("labelFontSize", "$path.labelFontSize"),
        labelOffset = obj.numberOrSignal("labelOffset", "$path.labelOffset"),
        symbolType = obj.fields["symbolType"]?.asString(),
        symbolSize = obj.numberOrSignal("symbolSize", "$path.symbolSize"),
        symbolStrokeWidth = obj.numberOrSignal("symbolStrokeWidth", "$path.symbolStrokeWidth"),
        clipHeight = obj.numberOrSignal("clipHeight", "$path.clipHeight"),
        gradientLength = obj.numberOrSignal("gradientLength", "$path.gradientLength"),
        gradientThickness = obj.numberOrSignal("gradientThickness", "$path.gradientThickness"),
        rowPadding = obj.numberOrSignal("rowPadding", "$path.rowPadding"),
        columnPadding = obj.numberOrSignal("columnPadding", "$path.columnPadding"),
        columns = obj.numberOrSignal("columns", "$path.columns"),
        legendX = obj.numberOrSignal("legendX", "$path.legendX"),
        legendY = obj.numberOrSignal("legendY", "$path.legendY"),
        fillColor = obj.fields["fillColor"]?.asString()?.takeIf { it.isNotEmpty() },
        strokeColor = obj.fields["strokeColor"]?.asString()?.takeIf { it.isNotEmpty() },
        cornerRadius = obj.numberOrSignal("cornerRadius", "$path.cornerRadius"),
        symbolFillColor = obj.fields["symbolFillColor"]?.asString()?.takeIf { it.isNotEmpty() },
        symbolOffset = obj.numberOrSignal("symbolOffset", "$path.symbolOffset"),
        gradientStrokeColor =
          obj.fields["gradientStrokeColor"]?.asString()?.takeIf { it.isNotEmpty() },
        gradientStrokeWidth =
          obj.numberOrSignal("gradientStrokeWidth", "$path.gradientStrokeWidth"),
        gradientOpacity = obj.numberOrSignal("gradientOpacity", "$path.gradientOpacity"),
        symbolLimit = obj.numberOrSignal("symbolLimit", "$path.symbolLimit"),
        formatType = obj.fields["formatType"]?.asString()?.takeIf { it.isNotEmpty() },
        tickMinStep = obj.numberOrSignal("tickMinStep", "$path.tickMinStep"),
        aria = obj.fields["aria"]?.asBoolean() ?: true,
        description = obj.fields["description"]?.asString()?.takeIf { it.isNotBlank() },
        // From the configuration alone; see [LegendSpec.fillColor] for why the legend's own value
        // is deliberately not consulted.
        backgroundStrokeWidth = legendConfig("strokeWidth")?.asDouble()?.takeIf { !it.isNaN() },
        backgroundStrokeDash =
          (legendConfig("strokeDash") as? VegaValue.Arr)
            ?.values
            ?.map { it.asDouble() }
            ?.takeIf { list -> list.isNotEmpty() && list.all { it.isFinite() && it >= 0.0 } },
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
              // `symbolDashOffset`, matching `symbolDash` — the `symbolStroke` prefix would look
              // for `symbolStrokeDashOffset`, which is not a property upstream has.
              dashOffset = (obj.fields["symbolDashOffset"] as? VegaValue.Num)?.value,
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

    own.reportUnhandled("Legend", path, LEGEND_CONSUMED)
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
    val markTransforms = (obj.fields["transform"] as? VegaValue.Arr)?.values.orEmpty()
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

    obj.reportUnhandled("Mark", path, MARK_CONSUMED)

    val (below, above) = config.markDefaults(typeName.lowercase(), markStyles(obj))

    return MarkSpec(
      type = type,
      name = obj.fields["name"]?.asString(),
      role = obj.fields["role"]?.takeIf { it is VegaValue.Str }?.asString(),
      from = from?.let { FromSpec(data = it.fields["data"]?.asString(), facet = facet) },
      sort = sort,
      transform = markTransforms,
      encode = parseEncode(obj.fields["encode"], "$path.encode"),
      marks = parseArray(obj, "marks", path) { child, childPath -> parseMark(child, childPath) },
      projections =
        parseArray(obj, "projections", path) { child, childPath ->
          parseProjection(child, childPath)
        },
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
            ?: if (field == null) op else "${op}_$field",
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
  /** The six labels a layout `offset` can name, when it names one number for all of them. */
  private val OFFSET_PARTS =
    listOf("rowHeader", "columnHeader", "rowFooter", "columnFooter", "rowTitle", "columnTitle")

  /** One direction of a layout band, written either as one number or as `{row, column}`. */
  private fun layoutBand(value: VegaValue?, direction: String): Double? =
    when (value) {
      is VegaValue.Obj -> (value.fields[direction] as? VegaValue.Num)?.value
      is VegaValue.Num -> value.value
      else -> null
    }?.takeIf { it.isFinite() }

  /** The same for a layout property whose value is a word. */
  private fun layoutText(value: VegaValue?, direction: String): String? =
    when (value) {
      is VegaValue.Obj -> value.fields[direction]?.asString()
      is VegaValue.Str -> value.value
      else -> null
    }?.takeIf { it.isNotEmpty() }

  /** One direction of a layout flag written either as one value or as `{row, column}`. */
  private fun layoutFlag(value: VegaValue?, direction: String): Boolean =
    when (value) {
      null -> false
      is VegaValue.Obj -> value.fields[direction]?.asBoolean() ?: false
      else -> value.asBoolean()
    }

  private fun parseLayout(value: VegaValue, path: String): LayoutSpec? {
    val obj = value as? VegaValue.Obj ?: return unexpected("a layout definition", path)
    // Named rather than listed by exception, like every other block: a layout property nobody
    // thought about becomes a diagnostic instead of a silence. `titleAnchor` was the one this
    // caught
    // — it had neither an entry in the table below nor a reader, so a trellis that anchored its
    // cell
    // titles was told nothing at all.
    obj.reportUnhandled(
      "Layout",
      path,
      LAYOUT_CONSUMED,
      emptyMap(),
    )

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

    // `align` and `bounds` take the same shape as `padding`: one value for both directions, or a
    // per-direction object. `bounds` has no per-direction form upstream, so it is read as one.
    val align = obj.fields["align"]
    return LayoutSpec(
      columns = obj.numberOrSignal("columns", "$path.columns"),
      rowPadding = rowPadding,
      columnPadding = columnPadding,
      alignRow = layoutAlign(align, "row"),
      alignColumn = layoutAlign(align, "column"),
      bounds = obj.fields["bounds"]?.takeIf { it is VegaValue.Str }?.asString()?.lowercase(),
      centerColumn = layoutFlag(obj.fields["center"], "column"),
      centerRow = layoutFlag(obj.fields["center"], "row"),
      headerBandRow = layoutBand(obj.fields["headerBand"], "row"),
      headerBandColumn = layoutBand(obj.fields["headerBand"], "column"),
      footerBandRow = layoutBand(obj.fields["footerBand"], "row"),
      footerBandColumn = layoutBand(obj.fields["footerBand"], "column"),
      titleBandRow = layoutBand(obj.fields["titleBand"], "row"),
      titleBandColumn = layoutBand(obj.fields["titleBand"], "column"),
      titleAnchorRow = layoutText(obj.fields["titleAnchor"], "row"),
      titleAnchorColumn = layoutText(obj.fields["titleAnchor"], "column"),
      // One number for all six, or an object naming any of them. Upstream reads each key on demand,
      // so a partial object leaves the rest at zero rather than at the single value.
      offsets =
        (obj.fields["offset"] as? VegaValue.Obj)
          ?.fields
          ?.mapNotNull { (key, value) ->
            (value as? VegaValue.Num)?.value?.takeIf { it.isFinite() }?.let { key to it }
          }
          ?.toMap()
          ?: (obj.fields["offset"] as? VegaValue.Num)
            ?.value
            ?.takeIf { it.isFinite() }
            ?.let { one ->
              OFFSET_PARTS.associateWith { one }
            }
          ?: emptyMap(),
    )
  }

  /**
   * `labelFlush` as the distance it really is: `true` is one pixel, a number is itself.
   *
   * Upstream's own test is `flush === 0 || !!flush`, so **zero counts** and `false` does not — a
   * zero threshold still flushes a label that lands exactly on the range's end, where `false`
   * flushes nothing.
   */
  private fun flushThreshold(value: VegaValue?): Double? =
    when (value) {
      null -> null
      is VegaValue.Bool -> if (value.value) 1.0 else null
      is VegaValue.Num -> value.value.takeIf { it.isFinite() }
      else -> null
    }

  /** One direction of `layout.align`, which is either a bare name or a `{row, column}` object. */
  private fun layoutAlign(value: VegaValue?, direction: String): String? =
    when (value) {
      null -> null
      is VegaValue.Obj -> value.fields[direction]?.asString()
      else -> value.asString()
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
