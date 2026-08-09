package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue

/**
 * Reads a Vega-Lite specification into [UnitSpec], filling in what the grammar leaves implicit.
 *
 * Two of those are worth naming because they change the output rather than tidy the input: a mark
 * given as a bare string becomes a mark definition with its `filled` and `orient` resolved, and a
 * channel definition with no `type` gets the one upstream would infer. Everything this compiler
 * does not implement is reported here by name rather than dropped.
 */
internal class Parse(private val config: Config, private val diagnostics: DiagnosticCollector) {

  fun unit(spec: VegaValue.Obj, path: String): UnitSpec? {
    val markValue = spec.fields["mark"]
    if (markValue == null) {
      diagnostics.error(
        VegaLiteDiagnostics.MISSING_MARK,
        "A view needs a `mark`; this one has none, so nothing can be drawn for it.",
        jsonPath = path,
      )
      return null
    }

    val encoding = encoding(spec.obj("encoding") ?: VegaValue.EmptyObject, "$path.encoding")
    val markDef = markDef(markValue, encoding, path) ?: return null

    return UnitSpec(
      markDef = markDef,
      encoding = encoding,
      data = spec.fields["data"],
      transforms = spec.array("transform") ?: emptyList(),
      width = spec.fields["width"],
      height = spec.fields["height"],
    )
  }

  fun markDef(value: VegaValue, encoding: Map<String, ChannelDef>, path: String): MarkDef? {
    val raw =
      when (value) {
        is VegaValue.Str -> obj { put("type", value.value) }
        is VegaValue.Obj -> value
        else -> {
          diagnostics.error(
            VegaLiteDiagnostics.MISSING_MARK,
            "`mark` must be a name or an object; found ${value::class.simpleName}.",
            jsonPath = "$path.mark",
          )
          return null
        }
      }
    val type = raw.string("type")
    if (type == null) {
      diagnostics.error(
        VegaLiteDiagnostics.MISSING_MARK,
        "A mark object needs a `type`.",
        jsonPath = "$path.mark",
      )
      return null
    }
    if (type !in SUPPORTED_MARKS) {
      diagnostics.error(
        VegaLiteDiagnostics.UNSUPPORTED_MARK,
        "The `$type` mark is not implemented. Supported marks are: " +
          SUPPORTED_MARKS.sorted().joinToString(", ") +
          ".",
        jsonPath = "$path.mark",
      )
      return null
    }

    val markConfig = config.markConfig(type)
    // `filled` before anything else: the encoding's colour channel resolves to `fill` or `stroke`
    // depending on it, and the mark's own properties then depend on the encoding.
    val filled =
      raw.boolean("filled")
        ?: markConfig.boolean("filled")
        ?: (type != "point" && type != "line" && type != "rule")

    return MarkDef(
      type = type,
      raw = raw,
      filled = filled,
      orient = raw.string("orient") ?: markConfig.string("orient") ?: defaultOrient(type, encoding),
    )
  }

  /**
   * The encoding, re-ordered into upstream's channel order rather than the order it was written in.
   *
   * This is `initEncoding`, and the order is not cosmetic: it decides the order of the scales, the
   * axes and the fields in the spoken description, so a specification that happens to list `y`
   * before `x` still produces the same chart as one that does not.
   */
  fun encoding(block: VegaValue.Obj, path: String): Map<String, ChannelDef> {
    val result = LinkedHashMap<String, ChannelDef>()
    val ordered =
      Channels.UNIT_CHANNELS.filter { block.fields.containsKey(it) } +
        block.fields.keys.filter { it !in Channels.UNIT_CHANNELS }
    for (channel in ordered) {
      val value = block.fields.getValue(channel)
      if (channel in UNSUPPORTED_CHANNELS) {
        diagnostics.error(
          VegaLiteDiagnostics.UNSUPPORTED_CHANNEL,
          "The `$channel` channel is not implemented; its encoding is ignored. Express the view " +
            "with the position, colour, size, shape, text and detail channels instead.",
          jsonPath = "$path.$channel",
        )
        continue
      }
      // A multi-definition channel may hold an array. The first entry is the definition proper,
      // because everything that reads a channel reads one; the others are kept beside it, and
      // losing them loses every field but the first from a tooltip.
      val entries = (value as? VegaValue.Arr)?.values ?: listOf(value)
      val parsed = entries.mapIndexedNotNull { index, entry ->
        val at = if (value is VegaValue.Arr) "$path.$channel[$index]" else "$path.$channel"
        channelDef(channel, entry, at)
      }
      val def = parsed.firstOrNull() ?: continue
      result[channel] = def.copy(siblings = parsed.drop(1))
    }
    // A secondary channel takes its type from the channel it bounds. `{"x2": {"field": "end"}}` is
    // how every ranged mark is written, and reading it as an untyped — therefore nominal — field
    // would format the number as a category and put a discrete scale under it.
    for ((channel, def) in result.toList()) {
      val main = mainChannel(channel)
      if (main == channel || def.type != null) continue
      result[channel] = def.copy(type = result[main]?.type)
    }
    return result
  }

  private fun channelDef(channel: String, value: VegaValue, path: String): ChannelDef? {
    if (value !is VegaValue.Obj) {
      diagnostics.error(
        VegaLiteDiagnostics.INVALID_ENCODING,
        "A channel definition must be an object.",
        jsonPath = path,
      )
      return null
    }

    val field = value.string("field")
    val aggregate = value.string("aggregate")
    val timeUnit = value.string("timeUnit")
    val bin = binning(value.fields["bin"], path)

    val conditions = conditions(channel, value.fields["condition"], "$path.condition")

    val declaredType = MeasureType.from(value.string("type"))
    val type =
      declaredType
        ?: inferType(channel, field, aggregate, timeUnit, bin, value.fields["datum"], path)

    return ChannelDef(
      channel = channel,
      raw = value,
      field = field,
      datum = value.fields["datum"],
      value = value.fields["value"],
      type = type,
      aggregate = aggregate,
      bin = bin,
      timeUnit = timeUnit,
      sort = value.fields["sort"],
      stack = value.fields["stack"],
      explicitTitle = value.fields["title"],
      conditions = conditions,
    )
  }

  /**
   * `condition` — one definition, or a list of them, each gated on its own `test`.
   *
   * A condition is an ordinary channel definition: it may name a field, a datum or a value, and it
   * is compiled by exactly the code the unconditional part is, with the test put in front of it.
   * The test itself is a `filter`'s grammar — an expression or a field predicate — so it goes
   * through the same compiler, which is what keeps `oneOf` spelled one way.
   *
   * A condition naming a `param` is a *selection*, and that is still refused by name: it needs the
   * signal a selection publishes, and there is nothing to gate on without it.
   */
  private fun conditions(channel: String, value: VegaValue?, path: String): List<ChannelDef> {
    if (value == null || value is VegaValue.Null) return emptyList()
    val entries = (value as? VegaValue.Arr)?.values ?: listOf(value)
    return entries.mapIndexedNotNull { index, entry ->
      val at = if (value is VegaValue.Arr) "$path[$index]" else path
      val obj = entry as? VegaValue.Obj
      if (obj == null) {
        diagnostics.error(
          VegaLiteDiagnostics.INVALID_ENCODING,
          "A condition must be an object.",
          jsonPath = at,
        )
        return@mapIndexedNotNull null
      }
      if (obj.fields["param"] != null) {
        diagnostics.error(
          VegaLiteDiagnostics.UNSUPPORTED_ENCODING_PROPERTY,
          "A condition on a selection parameter needs `params`, which are not implemented; this " +
            "condition is dropped and the unconditional part of the definition is used.",
          jsonPath = at,
        )
        return@mapIndexedNotNull null
      }
      val test =
        Transforms(diagnostics).testExpression(obj.fields["test"], "$at.test")
          ?: return@mapIndexedNotNull null
      channelDef(channel, obj, at)?.copy(test = test)
    }
  }

  /**
   * `bin: true` normalizes to `{maxbins: 10}` — and the normalized parameters are what the field
   * name is built from, so `bin_maxbins_10_v` appears even where the specification said only
   * `true`.
   */
  private fun binning(value: VegaValue?, path: String): Binning? =
    when {
      value == null || value == VegaValue.Bool(false) || value == VegaValue.Null -> null
      value == VegaValue.Bool(true) -> Binning.Bin(obj { put("maxbins", 10) })
      value == VegaValue.Str("binned") -> Binning.PreBinned
      value is VegaValue.Obj ->
        if (value.fields.isEmpty()) {
          Binning.Bin(obj { put("maxbins", 10) })
        } else {
          Binning.Bin(value)
        }
      else -> {
        diagnostics.error(
          VegaLiteDiagnostics.INVALID_ENCODING,
          "`bin` must be true, \"binned\" or an object of bin parameters.",
          jsonPath = path,
        )
        null
      }
    }

  /** `defaultType` from `channeldef.ts`, for the cases a specification is allowed to leave out. */
  private fun inferType(
    channel: String,
    field: String?,
    aggregate: String?,
    timeUnit: String?,
    bin: Binning?,
    datum: VegaValue?,
    path: String,
  ): MeasureType? {
    if (field == null && aggregate == null && datum == null) return null
    return when {
      aggregate == "count" || bin != null -> MeasureType.QUANTITATIVE
      timeUnit != null -> MeasureType.TEMPORAL
      channel in setOf("shape", "row", "column", "facet", "strokeDash") -> MeasureType.NOMINAL
      channel in setOf("latitude", "longitude") -> MeasureType.QUANTITATIVE
      datum is VegaValue.Num -> MeasureType.QUANTITATIVE
      datum != null -> MeasureType.NOMINAL
      channel != mainChannel(channel) -> null
      else -> {
        diagnostics.warn(
          VegaLiteDiagnostics.INFERRED_TYPE,
          "No `type` on this channel; treating the field as nominal, which is what Vega-Lite " +
            "falls back to. State the type to be sure of the scale.",
          jsonPath = path,
        )
        MeasureType.NOMINAL
      }
    }
  }

  /**
   * `orient` from `compile/mark/init.ts`, reduced to the marks this compiler emits.
   *
   * It decides which way a bar grows and which axis a rule spans, and it is derived from the
   * encoding rather than declared: a quantitative y against a discrete x is a vertical bar, and the
   * same pair on a tick is a *horizontal* one, because a tick marks the position it measures.
   */
  private fun defaultOrient(mark: String, encoding: Map<String, ChannelDef>): String? {
    if (mark in setOf("point", "circle", "square", "rect", "image", "arc", "text")) return null
    val x = encoding["x"]
    val y = encoding["y"]

    if (mark == "bar") {
      if (x?.bin != null) return "vertical"
      if (y?.bin != null) return "horizontal"
      if (x?.isFieldDef == true && y?.aggregate != null && x.aggregate == null) return "vertical"
      if (y?.isFieldDef == true && x?.aggregate != null && y.aggregate == null) return "horizontal"
    }

    if (mark == "rule") {
      if (encoding["x2"] != null && encoding["y2"] != null) return null
      if (x != null && y == null) return "vertical"
      if (y != null && x == null) return "horizontal"
    }

    if (mark == "area" || mark == "bar") {
      if (encoding["y2"] != null) return "vertical"
      if (encoding["x2"] != null) return "horizontal"
    }

    val xIsMeasure = x?.isUnbinnedQuantitative == true || x?.datum is VegaValue.Num
    val yIsMeasure = y?.isUnbinnedQuantitative == true || y?.datum is VegaValue.Num
    return when {
      xIsMeasure && !yIsMeasure -> if (mark != "tick") "horizontal" else "vertical"
      !xIsMeasure && yIsMeasure -> if (mark != "tick") "vertical" else "horizontal"
      xIsMeasure && yIsMeasure -> "vertical"
      x?.type == MeasureType.TEMPORAL && y?.type != MeasureType.TEMPORAL -> "vertical"
      x?.type != MeasureType.TEMPORAL && y?.type == MeasureType.TEMPORAL -> "horizontal"
      else -> null
    }
  }

  companion object {
    /** The primitive marks this compiler emits. Composite marks normalize into these upstream. */
    val SUPPORTED_MARKS =
      setOf(
        "arc",
        "area",
        "bar",
        "circle",
        "line",
        "point",
        "rect",
        "rule",
        "square",
        "text",
        "tick",
      )

    /**
     * Channels a specification may legitimately use that this compiler does not implement. Named
     * individually so a report says which one stopped it, rather than "unsupported encoding".
     */
    val UNSUPPORTED_CHANNELS =
      setOf(
        "latitude",
        "longitude",
        "latitude2",
        "longitude2",
        "geojson",
        "xError",
        "yError",
        "angle",
        "url",
        "href",
        "time",
      )
  }
}
