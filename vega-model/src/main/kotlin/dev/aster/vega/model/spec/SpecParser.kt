package dev.aster.vega.model.spec

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaDiagnostic
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asBoolean
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString

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

    val spec =
      VegaSpec(
        width = root.optionalNumber("width", "$.width"),
        height = root.optionalNumber("height", "$.height"),
        padding = parsePadding(root.fields["padding"], "$.padding"),
        autosize = parseAutosize(root.fields["autosize"], "$.autosize"),
        background = root.fields["background"]?.takeIf { it is VegaValue.Str }?.asString(),
        signals = parseArray(root, "signals") { value, path -> parseSignal(value, path) },
        data = parseArray(root, "data") { value, path -> parseData(value, path) },
        scales = parseArray(root, "scales") { value, path -> parseScale(value, path) },
        axes = parseArray(root, "axes") { value, path -> parseAxis(value, path) },
        legends = parseArray(root, "legends") { value, path -> parseLegend(value, path) },
        title = root.fields["title"]?.let { parseTitle(it, "$.title") },
        marks = parseArray(root, "marks") { value, path -> parseMark(value, path) },
      )

    reportUnsupportedTopLevel(root)
    return ParsedSpec(spec, diagnostics.diagnostics)
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
        "layout" to "Layout specifications are not implemented",
        "config" to "Configuration overrides are not implemented; built-in defaults are used",
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

    return ScaleSpec(
      name = name,
      type = type,
      domain = parseDomain(obj, "$path.domain"),
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
            DomainSpec.FromField(data, field, domain.fields["sort"]?.asBoolean() ?: false)
          data != null && fields != null -> DomainSpec.FromFields(data, fields)
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
    val obj = value as? VegaValue.Obj ?: return unexpected("an axis definition", path)
    val scale = obj.fields["scale"]?.asString()
    if (scale.isNullOrEmpty()) {
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "An axis needs a scale",
        jsonPath = path,
      )
      return null
    }
    val orientName = obj.fields["orient"]?.asString() ?: "bottom"
    val orient = Orient.fromName(orientName)
    if (orient == null) {
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Unknown axis orientation '$orientName'",
        jsonPath = "$path.orient",
      )
      return null
    }

    if (obj.fields["encode"] != null) {
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Axis encode overrides are not implemented",
        jsonPath = "$path.encode",
      )
    }
    if (obj.fields["format"] != null) {
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Axis label format strings are not implemented; default formatting is used",
        jsonPath = "$path.format",
      )
    }

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
    )
  }

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

    val text = obj.fields["text"]?.takeIf { it is VegaValue.Str }?.asString()
    if (text.isNullOrEmpty()) {
      diagnostics.error(
        DiagnosticCodes.PARSE_MISSING_PROPERTY,
        "A title needs a 'text'",
        jsonPath = path,
      )
      return null
    }

    val unsupported =
      mapOf(
        "encode" to "Title encode overrides are not implemented",
        "style" to "Title styles are not implemented",
        "limit" to "Title text limits are not implemented",
        "dx" to "Title dx is not implemented",
        "dy" to "Title dy is not implemented",
        "align" to "Title alignment follows 'anchor'; an explicit align is not implemented",
        "angle" to "Title rotation follows 'orient'; an explicit angle is not implemented",
      )
    for ((key, reason) in unsupported) {
      if (obj.fields[key] == null) continue
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "$reason; '$key' was ignored",
        jsonPath = "$path.$key",
      )
    }

    return TitleSpec(
      text = text,
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
    val obj = value as? VegaValue.Obj ?: return unexpected("a legend definition", path)

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
        legendX = obj.numberOrSignal("legendX", "$path.legendX"),
        legendY = obj.numberOrSignal("legendY", "$path.legendY"),
        zindex = (obj.fields["zindex"] as? VegaValue.Num)?.value?.toInt() ?: 0,
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

    val unsupported =
      mapOf(
        "encode" to "Legend encode overrides are not implemented",
        "format" to "Legend label format specifiers are not implemented",
        "formatType" to "Legend label format types are not implemented",
        "columns" to "Multi-column legend layout is not implemented; entries run in a single line",
        "labelOverlap" to "Legend label overlap removal is not implemented",
        "symbolLimit" to "Legend entry limits are not implemented; every entry is shown",
        "titleOrient" to "Only a legend title above the entries is implemented",
        "gradientOpacity" to "Legend gradient opacity is not implemented",
        "titleAnchor" to "Legend title anchoring is not implemented",
      )
    for ((key, reason) in unsupported) {
      if (obj.fields[key] == null) continue
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "$reason; '$key' was ignored",
        jsonPath = "$path.$key",
      )
    }
    return spec
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

    return MarkSpec(
      type = type,
      name = obj.fields["name"]?.asString(),
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
      zindex = (obj.fields["zindex"] as? VegaValue.Num)?.value?.toInt() ?: 0,
      interactive = obj.fields["interactive"]?.asBoolean() ?: true,
      clip = obj.fields["clip"]?.asBoolean() ?: false,
    )
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

  /** Reports the parts of a group's scope that the runtime cannot build. */
  private fun reportUnsupportedGroupScope(obj: VegaValue.Obj, path: String) {
    val unsupported =
      mapOf(
        "layout" to
          "Group layout is not implemented; position each group from its own encode block instead",
        "projections" to "Geographic projections are out of scope",
      )
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
