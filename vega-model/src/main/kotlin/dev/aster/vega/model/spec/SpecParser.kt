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
        marks = parseArray(root, "marks") { value, path -> parseMark(value, path) },
      )

    reportUnsupportedTopLevel(root)
    return ParsedSpec(spec, diagnostics.diagnostics)
  }

  // ---- top level ------------------------------------------------------------

  /**
   * Reports specification sections the runtime does not implement.
   *
   * Silence here would be the worst outcome: a chart with signals or legends would render without
   * them and look merely wrong rather than unsupported.
   */
  private fun reportUnsupportedTopLevel(root: VegaValue.Obj) {
    val unsupported =
      mapOf(
        "legends" to "Legend generation is not implemented",
        "title" to "Title generation is not implemented",
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
    if (obj.fields["format"] != null) {
      diagnostics.warn(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Data format options are not implemented; values are used as parsed JSON",
        jsonPath = "$path.format",
      )
    }

    return DataSpec(
      name = name,
      values = values,
      url = url,
      transform = (obj.fields["transform"] as? VegaValue.Arr)?.values ?: emptyList(),
      source = obj.fields["source"]?.asString(),
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
      padding = obj.optionalNumber("padding", "$path.padding"),
      paddingInner = obj.optionalNumber("paddingInner", "$path.paddingInner"),
      paddingOuter = obj.optionalNumber("paddingOuter", "$path.paddingOuter"),
      align = obj.optionalNumber("align", "$path.align"),
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
      grid = obj.fields["grid"]?.asBoolean() ?: false,
      ticks = obj.fields["ticks"]?.asBoolean() ?: true,
      labels = obj.fields["labels"]?.asBoolean() ?: true,
      domainLine = obj.fields["domain"]?.asBoolean() ?: true,
      tickCount = (obj.fields["tickCount"] as? VegaValue.Num)?.value?.toInt(),
      tickSize = obj.optionalNumber("tickSize", "$path.tickSize"),
      labelPadding = obj.optionalNumber("labelPadding", "$path.labelPadding"),
      labelFontSize = obj.optionalNumber("labelFontSize", "$path.labelFontSize"),
      offset = obj.optionalNumber("offset", "$path.offset") ?: 0.0,
      zindex = (obj.fields["zindex"] as? VegaValue.Num)?.value?.toInt() ?: 0,
    )
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
    if (from?.fields?.get("facet") != null) {
      diagnostics.error(
        DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
        "Faceted group marks are not implemented; this mark will render no data",
        jsonPath = "$path.from.facet",
      )
    }
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

    return MarkSpec(
      type = type,
      name = obj.fields["name"]?.asString(),
      from =
        from?.let {
          FromSpec(data = it.fields["data"]?.asString(), facet = it.fields["facet"])
        },
      encode = parseEncode(obj.fields["encode"], "$path.encode"),
      marks = parseArray(obj, "marks", path) { child, childPath -> parseMark(child, childPath) },
      axes = parseArray(obj, "axes", path) { child, childPath -> parseAxis(child, childPath) },
      zindex = (obj.fields["zindex"] as? VegaValue.Num)?.value?.toInt() ?: 0,
      interactive = obj.fields["interactive"]?.asBoolean() ?: true,
      clip = obj.fields["clip"]?.asBoolean() ?: false,
    )
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
