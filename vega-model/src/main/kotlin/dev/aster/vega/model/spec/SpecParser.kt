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
    "format" to "Axis label format strings are not implemented; default formatting is used",
    "formatType" to "Axis label format types are not implemented; default formatting is used",
    "encode" to "Axis encode overrides are not implemented",
    "labelLimit" to "Axis label truncation is not implemented; labels are drawn in full",
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
    "bandPosition" to "Moving a label off a band's centre is not implemented",
    "position" to "Positioning an axis along its own dimension is not implemented",
    "translate" to "Overriding the axis's half-pixel translation is not implemented",
    "minExtent" to "A minimum axis extent is not implemented; the axis is measured by its contents",
    "maxExtent" to "A maximum axis extent is not implemented; the axis is measured by its contents",
    "titleAlign" to "Explicit axis title alignment is not implemented; the anchor decides it",
    "titleBaseline" to "Explicit axis title baselines are not implemented; the orientation sets it",
    "titleAngle" to "Explicit axis title angles are not implemented; a vertical axis turns its own",
    "titleLimit" to "Axis title truncation is not implemented; the title is drawn in full",
    "titleLineHeight" to "Multi-line axis titles are not implemented",
    "titleX" to "Absolute axis title placement is not implemented",
    "titleY" to "Absolute axis title placement is not implemented",
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
  )

private val SCALE_UNSUPPORTED =
  mapOf(
    "domainRaw" to "Overriding a resolved domain with 'domainRaw' is not implemented",
    "domainImplicit" to "Extending an ordinal domain with unseen values is not implemented",
    "bins" to "Scale bin boundaries are not implemented",
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
    "orient",
    "direction",
    "title",
    "values",
    "tickCount",
    "offset",
    "padding",
    "titlePadding",
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
    "subtitleFontSize",
    "zindex",
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
    "clip",
    // Both are read only to be reported, which reportUnhandled would otherwise duplicate.
    "transform",
    "sort",
    // Reported by reportUnsupportedGroupScope, for the same reason.
    "style",
    "on",
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
    "startAngle",
    "endAngle",
    "innerRadius",
    "outerRadius",
    "strokeDash",
    "strokeCap",
    "strokeJoin",
    // Read in order to be reported by name, which is more useful than the generic message.
    "padAngle",
  )

private val ENCODE_UNSUPPORTED =
  mapOf(
    "xc" to
      "Positioning by centre is not implemented; give 'x' with 'width', or 'x' with 'x2', " +
        "since a mark encoded only by its centre cannot be placed at all",
    "yc" to
      "Positioning by centre is not implemented; give 'y' with 'height', or 'y' with 'y2', " +
        "since a mark encoded only by its centre cannot be placed at all",
    "limit" to "Text truncation is not implemented; the text is drawn in full",
    "ellipsis" to "Text truncation is not implemented, so its ellipsis has nothing to mark",
    "tooltip" to
      "Tooltip content from an encode channel is not implemented; a tooltip is built from the " +
        "mark's own fields instead",
    "url" to "Image marks have no encoder yet, so a URL channel has nothing to load into",
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
    "path" to "SVG path strings on a mark are not implemented",
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
        "encode" to "Top-level encode blocks are not implemented",
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

  private fun parseSignal(value: VegaValue, path: String): SignalSpec? {
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

    val on = (obj.fields["on"] as? VegaValue.Arr)?.values ?: emptyList()
    if (on.isNotEmpty()) {
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Event-stream handlers need the interaction system; signal '$name' will keep its " +
          "initial value and never update",
        jsonPath = "$path.on",
      )
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
          "padding must be a number or an object; using the default of 5",
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
    val url = obj.fields["url"]?.asString()
    if (url != null) {
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Loading data from a URL is not implemented; dataset '$name' will be empty",
        jsonPath = "$path.url",
      )
    }
    val format = obj.fields["format"] as? VegaValue.Obj
    val parse = LinkedHashMap<String, String>()
    if (format != null) {
      for ((key, value) in format.fields) {
        if (key == "parse") {
          val fields = value as? VegaValue.Obj
          if (fields == null) {
            diagnostics.warn(
              DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
              "'format.parse' must name each field and how to read it",
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

    return DataSpec(
      name = name,
      values = values,
      url = url,
      transform = (obj.fields["transform"] as? VegaValue.Arr)?.values ?: emptyList(),
      source = obj.fields["source"]?.asString(),
      parse = parse,
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
    )
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
        val field = domain.fields["field"]?.asString()
        val fields = (domain.fields["fields"] as? VegaValue.Arr)?.values?.map { it.asString() }
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
        val scheme = value.fields["scheme"]?.asString()
        if (scheme != null) {
          RangeSpec.Scheme(scheme, (value.fields["count"] as? VegaValue.Num)?.value?.toInt())
        } else {
          diagnostics.warn(
            DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
            "Only '{scheme: ...}' range objects are supported",
            jsonPath = path,
          )
          RangeSpec.Unset
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

    return AxisSpec(
      scale = scale,
      orient = orient,
      title = obj.fields["title"]?.takeIf { it is VegaValue.Str }?.asString(),
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
      zindex = (obj.fields["zindex"] as? VegaValue.Num)?.value?.toInt() ?: 0,
      values = (obj.fields["values"] as? VegaValue.Arr)?.values,
      labelOverlap = obj.fields["labelOverlap"]?.asString(),
      labelSeparation = obj.numberOrSignal("labelSeparation", "$path.labelSeparation"),
      labelAngle = obj.numberOrSignal("labelAngle", "$path.labelAngle"),
      labelAlign = obj.fields["labelAlign"]?.takeIf { it is VegaValue.Str }?.asString(),
      labelBaseline = obj.fields["labelBaseline"]?.takeIf { it is VegaValue.Str }?.asString(),
      labelStyle = obj.guideStroke("label"),
      tickStyle = obj.guideStroke("tick"),
      gridStyle = obj.guideStroke("grid"),
      domainStyle = obj.guideStroke("domain"),
      titleStyle = obj.guideStroke("title"),
    )
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
  private fun parseTitle(value: VegaValue, path: String): TitleSpec? {
    if (value is VegaValue.Str) return TitleSpec(text = value.value)
    val obj = value as? VegaValue.Obj ?: return unexpected("a title definition", path)

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

    obj.reportUnhandled(
      "Title",
      path,
      TITLE_CONSUMED,
      mapOf(
        "encode" to "Title encode overrides are not implemented",
        "style" to "Title styles are not implemented",
        "limit" to "Title text limits are not implemented",
        "dx" to "Title dx is not implemented",
        "dy" to "Title dy is not implemented",
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
      subtitleFontSize = obj.numberOrSignal("subtitleFontSize", "$path.subtitleFontSize"),
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
    val obj = GuideConfig.merge(own, config.legendDefaults())

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
        tickCount = obj.numberOrSignal("tickCount", "$path.tickCount"),
        offset = obj.numberOrSignal("offset", "$path.offset"),
        padding = obj.numberOrSignal("padding", "$path.padding"),
        titlePadding = obj.numberOrSignal("titlePadding", "$path.titlePadding"),
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
        "encode" to "Legend encode overrides are not implemented",
        "format" to "Legend label format specifiers are not implemented",
        "formatType" to "Legend label format types are not implemented",
        "symbolLimit" to "Legend entry limits are not implemented; every entry is shown",
        "titleOrient" to "Only a legend title above the entries is implemented",
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
    if (obj.fields["sort"] != null) {
      diagnostics.warn(
        DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
        "Mark sort is not implemented; data order is preserved",
        jsonPath = "$path.sort",
      )
    }

    if (type == MarkType.GROUP) reportUnsupportedGroupScope(obj, path)
    obj.reportUnhandled("Mark", path, MARK_CONSUMED)

    val (below, above) = config.markDefaults(typeName.lowercase(), markStyles(obj))

    return MarkSpec(
      type = type,
      name = obj.fields["name"]?.asString(),
      role = obj.fields["role"]?.takeIf { it is VegaValue.Str }?.asString(),
      from = from?.let { FromSpec(data = it.fields["data"]?.asString(), facet = facet) },
      encode = parseEncode(obj.fields["encode"], "$path.encode"),
      marks = parseArray(obj, "marks", path) { child, childPath -> parseMark(child, childPath) },
      axes = parseArray(obj, "axes", path) { child, childPath -> parseAxis(child, childPath) },
      data = parseArray(obj, "data", path) { child, childPath -> parseData(child, childPath) },
      signals =
        parseArray(obj, "signals", path) { child, childPath -> parseSignal(child, childPath) },
      scales = parseArray(obj, "scales", path) { child, childPath -> parseScale(child, childPath) },
      legends =
        parseArray(obj, "legends", path) { child, childPath -> parseLegend(child, childPath) },
      layout = obj.fields["layout"]?.let { parseLayout(it, "$path.layout") },
      title = obj.fields["title"]?.let { parseTitle(it, "$path.title") },
      zindex = (obj.fields["zindex"] as? VegaValue.Num)?.value?.toInt() ?: 0,
      interactive = obj.fields["interactive"]?.asBoolean() ?: true,
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
      val reason =
        if (obj.fields["field"] != null) {
          "Pre-faceted data ('facet.field') is not implemented; use 'groupby' instead"
        } else {
          "A facet needs a 'groupby'"
        }
      diagnostics.error(DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED, reason, jsonPath = path)
      return null
    }
    if (obj.fields["aggregate"] != null) {
      diagnostics.warn(
        DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
        "Extra facet aggregates are not implemented; each group's datum carries only the " +
          "groupby fields and 'count'",
        jsonPath = "$path.aggregate",
      )
    }
    return FacetSpec(name = name, data = data, groupby = groupby)
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

    obj.fields["signal"]?.let {
      return ChannelValue.Signal(it.asString())
    }

    val scale = obj.fields["scale"]
    if (scale != null) {
      if (scale !is VegaValue.Str) {
        diagnostics.error(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "Only a named scale reference is supported",
          jsonPath = "$path.scale",
        )
        return null
      }
      return ChannelValue.Scaled(
        scale = scale.value,
        field = fieldPath(obj.fields["field"], "$path.field"),
        value = obj.fields["value"],
        band = obj.optionalNumber("band", "$path.band"),
        offset = obj.optionalNumber("offset", "$path.offset"),
      )
    }

    obj.fields["value"]?.let {
      return ChannelValue.Constant(it)
    }
    obj.fields["field"]?.let {
      val resolved = fieldPath(it, "$path.field") ?: return null
      return ChannelValue.Field(resolved)
    }

    diagnostics.warn(
      DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
      "Channel '$channel' has no value, field or scale reference",
      jsonPath = path,
    )
    return null
  }

  /**
   * Resolves a field reference, which Vega allows to be a string or `{"group": ...}` / `{"datum":
   * ...}`.
   */
  private fun fieldPath(value: VegaValue?, path: String): String? =
    when (value) {
      null -> null
      is VegaValue.Str -> value.value
      is VegaValue.Obj -> {
        diagnostics.error(
          DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
          "Only plain string field references are supported",
          jsonPath = path,
        )
        null
      }
      else -> value.asString()
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
