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
internal class Parse(
  private val config: Config,
  private val diagnostics: DiagnosticCollector,
  /** The chart's selections, which a `{"param": …}` condition is a test against. */
  private val selections: List<Selection> = emptyList(),
) {

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

    // `alignStackOrderWithColorDomain`: a chart whose colours are listed in a stated order is drawn
    // in that order too, and the rule reaches into the encoding to say so.
    val aligned = alignStackOrderWithColorDomain(encoding, markDef)

    return UnitSpec(
      markDef = markDef,
      encoding = aligned.encoding,
      data = spec.fields["data"],
      transforms = (spec.array("transform") ?: emptyList()) + aligned.transforms,
      width = spec.fields["width"],
      height = spec.fields["height"],
      params = spec.array("params").orEmpty(),
    )
  }

  /** The encoding and the transforms `alignStackOrderWithColorDomain` produced between them. */
  private class Aligned(
    val encoding: Map<String, ChannelDef>,
    val transforms: List<VegaValue> = emptyList(),
  )

  /**
   * `alignStackOrderWithColorDomain` in `unit.ts` — a stated colour order orders the marks too.
   *
   * A chart that lists its colour domain has said what order the categories come in, and a reader
   * expects to see them in that order: the bars of a group left to right, the segments of a stack
   * bottom to top. Nothing else says so, since a domain orders the *legend*.
   *
   * Which of the two it does depends on how the chart is drawn. A **grouped** chart carries the
   * order on its offset channel, as a `sort` list, and the offset scale's domain then reads the
   * place each row holds in it. A **stacked** one has no such channel, so the place is computed as
   * a column of its own and the `order` channel is pointed at it — descending, because a stack is
   * accumulated from the bottom and the first listed colour belongs at the top.
   *
   * Only where the chart states no `order` of its own, and only for a *nominal* colour: an ordered
   * or a measured one already has an order of its own to be drawn in.
   */
  private fun alignStackOrderWithColorDomain(
    encoding: Map<String, ChannelDef>,
    markDef: MarkDef,
  ): Aligned {
    if (encoding.containsKey("order")) return Aligned(encoding)
    val colour = encoding["fill"] ?: encoding["color"] ?: return Aligned(encoding)
    if (colour.type != MeasureType.NOMINAL) return Aligned(encoding)
    val field = colour.field ?: return Aligned(encoding)
    val domain = colour.scale?.array("domain") ?: return Aligned(encoding)

    val offsetChannel =
      listOf("xOffset", "yOffset").firstOrNull { encoding[it]?.isFieldDef == true }
    if (offsetChannel != null) {
      val offset = encoding.getValue(offsetChannel)
      if (offset.sort != null) return Aligned(encoding)
      val listed = arr(domain)
      return Aligned(
        encoding +
          (offsetChannel to
            offset.copy(
              sort = listed,
              raw = VegaValue.Obj(LinkedHashMap(offset.raw.fields).also { it["sort"] = listed }),
            ))
      )
    }
    // A stack, and only a stack: with neither an offset channel nor an accumulation there is
    // nothing whose order this could be. An accumulation is an aggregated measure against a
    // discrete other position, which is what `Stack.of` decides from the whole view — but the mark
    // is not built yet here, so the question is asked of the encoding: a quantitative position
    // that aggregates.
    val accumulating =
      listOf("x", "y").firstOrNull { channel ->
        val def = encoding[channel] ?: return@firstOrNull false
        def.aggregate != null && def.type == MeasureType.QUANTITATIVE
      }
    if (accumulating == null) return Aligned(encoding)
    val order = "_${field}_sort_index"
    // Written as Vega writes it, since it is the *text* of the list that reaches the expression:
    // `indexof(["sun","fog"], datum['weather'])`.
    val listedValues =
      domain.joinToString(",") { value ->
        when (value) {
          is VegaValue.Str -> quoted(value.value)
          else -> value.toString()
        }
      }
    val calculate = obj {
      put("calculate", "indexof([$listedValues], datum['$field'])")
      put("as", order)
    }
    // A stack is accumulated from the origin outwards, so the *first* listed colour is the one
    // nearest it: at the bottom of a vertical stack, which counts down, and at the left of a
    // horizontal one, which counts up. The orientation is the mark's where it states one and the
    // accumulating channel's otherwise — a bar measured along x is a horizontal bar.
    val horizontal =
      markDef.raw.string("orient")?.let { it == "horizontal" } ?: (accumulating == "x")
    val direction = if (horizontal) "ascending" else "descending"
    val orderDef = obj {
      put("field", order)
      put("type", "quantitative")
      put("sort", direction)
    }
    return Aligned(
      encoding + ("order" to channelDef("order", orderDef, "$.encoding.order")!!),
      listOf(calculate),
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
      // A definition that names no column, no datum and no value is not an encoding: it is the
      // part of a shared one a layer never filled in — `{"type": "quantitative", "axis": {…}}`
      // written above the layers so that each of them need only name its field. The layer that
      // names none has nothing on that channel, and upstream's `getFieldDef` answers accordingly:
      // a rule with no `y` spans the plot rather than sitting halfway up it.
      if (def.isBlank) continue
      result[channel] = def.copy(siblings = parsed.drop(1), isList = value is VegaValue.Arr)
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

  /**
   * `timeUnitToString`: a time unit written as an **object** spelled back into a name.
   *
   * `{"unit": "year", "step": 2}` buckets two years at a time, and the column it writes is called
   * `year_step_2_date` — the unit, then every other parameter as `_<name>_<value>`. Keeping the
   * name is what lets everything downstream go on treating a time unit as a word: the parts are
   * still read off the front of it, and the step is read back out where the transform needs it.
   */
  private fun timeUnitName(params: VegaValue.Obj?): String? {
    val unit = params?.string("unit") ?: return null
    return buildString {
      append(unit)
      params.fields.forEach { (key, value) ->
        if (key != "unit" && key != "utc" && key != "binned") {
          append(
            Fields.varName(
              "_${key}_${(value as? VegaValue.Num)?.value?.let {
            if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
          } ?: (value as? VegaValue.Str)?.value ?: value.toString()}"
            )
          )
        }
      }
    }
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
    // `{"aggregate": {"argmax": "US Gross"}}` — an aggregate that answers with a whole *row*
    // rather than a number, named by the column it maximises. The op and that column are two
    // separate things and everything downstream needs both.
    val aggregateObject = value.obj("aggregate")
    val aggregate =
      value.string("aggregate")
        ?: aggregateObject?.fields?.keys?.firstOrNull { it == "argmin" || it == "argmax" }
    val argumentField = aggregate?.let { aggregateObject?.string(it) }
    val timeUnit = value.string("timeUnit") ?: timeUnitName(value.obj("timeUnit"))
    val bin = binning(value.fields["bin"], path, channel)

    val conditions = conditions(channel, value.fields["condition"], "$path.condition")

    val declaredType = MeasureType.from(value.string("type"))
    val type = declaredType ?: inferType(channel, field, aggregate, timeUnit, bin, value, path)

    return ChannelDef(
      channel = channel,
      raw = value,
      field = field,
      datum = value.fields["datum"],
      value = value.fields["value"],
      type = type,
      aggregate = aggregate,
      argumentField = argumentField,
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
      // `{"param": "brush"}` — and `{"param": "brush", "empty": false}`, which turns the
      // before-anything-is-picked case around: an empty store normally means *every* row passes,
      // and `empty: false` means none does.
      val parameter = (obj.fields["param"] as? VegaValue.Str)?.value
      val test =
        if (parameter != null) {
          val selection = selections.firstOrNull { it.name == parameter }
          // A parameter that is **not** a selection is a variable, and a condition on one is a
          // condition on its truth: `parseSelectionPredicate` falls back to `!!name` rather than
          // reporting, which is how a checkbox turns an encoding on and off.
          if (selection == null) "!!${Fields.varName(parameter)}"
          else selection.test(emptyPasses = obj.fields["empty"] != VegaValue.Bool(false))
        } else {
          Transforms(diagnostics, selections = selections)
            .testExpression(obj.fields["test"], "$at.test") ?: return@mapIndexedNotNull null
        }
      channelDef(channel, obj, at)?.copy(test = test)
    }
  }

  /**
   * `bin: true` normalizes to `{maxbins: 10}` — and the normalized parameters are what the field
   * name is built from, so `bin_maxbins_10_v` appears even where the specification said only
   * `true`.
   */
  private fun binning(value: VegaValue?, path: String, channel: String): Binning? =
    when {
      value == null || value == VegaValue.Bool(false) || value == VegaValue.Null -> null
      value == VegaValue.Bool(true) -> Binning.Bin(obj { put("maxbins", autoMaxBins(channel)) })
      value == VegaValue.Str("binned") -> Binning.PreBinned
      // `isBinned` is two spellings, not one: the string, **and** an object saying `binned: true`,
      // which is how a specification states the step its data was already binned at. Reading only
      // the string binned an already-binned column a second time, which put a whole `bin` transform
      // and its extent signal into the data flow and shifted everything after it.
      (value as? VegaValue.Obj)?.fields?.get("binned") == VegaValue.Bool(true) -> Binning.PreBinned
      value is VegaValue.Obj ->
        if (value.fields.isEmpty()) {
          Binning.Bin(obj { put("maxbins", autoMaxBins(channel)) })
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

  /**
   * `defaultType` from `channeldef.ts`, for the cases a specification is allowed to leave out.
   *
   * Vega-Lite lets a channel omit its `type` and works one out, and the rules are **not** "look at
   * the data" — nothing here has read a row yet. They are read off the definition itself, in
   * upstream's own order, and every one of them matters:
   *
   * - some channels have only one sensible type whatever they carry: a latitude is a number, and a
   *   shape or a facet is a category;
   * - a `sort` written out as a **list** makes the field ordinal, the list being the order;
   * - a `timeUnit` makes it temporal, and a `bin` or an **aggregate** makes it quantitative — any
   *   aggregate except `argmin`/`argmax`, which answer with a whole row rather than a number;
   * - a `scale.type` the specification stated answers by category: a numeric or discretizing scale
   *   wants a quantitative field, a time scale a temporal one.
   *
   * Falling straight through to nominal — which is where this stopped before — turns a summed
   * measure into a category per distinct total, and draws a bar chart as a scatter of squares along
   * a diagonal. That is what a population pyramid pasted into the demo came out as.
   */
  private fun inferType(
    channel: String,
    field: String?,
    aggregate: String?,
    timeUnit: String?,
    bin: Binning?,
    def: VegaValue.Obj,
    path: String,
  ): MeasureType? {
    val datum = def.fields["datum"]
    if (field == null && aggregate == null && datum == null) return null
    return when {
      channel in setOf("latitude", "longitude") -> MeasureType.QUANTITATIVE
      channel in setOf("shape", "row", "column", "facet", "strokeDash") -> MeasureType.NOMINAL
      channel == "order" -> MeasureType.ORDINAL
      def.fields["sort"] is VegaValue.Arr -> MeasureType.ORDINAL
      timeUnit != null -> MeasureType.TEMPORAL
      bin != null || (aggregate != null && aggregate !in ARGMINMAX) -> MeasureType.QUANTITATIVE
      datum is VegaValue.Num -> MeasureType.QUANTITATIVE
      // A datum written as a **date** is an instant, not a category: `initFieldDef` reads
      // `isDateTime` and types it temporal, which is what puts a rule at a year on a time axis
      // rather than in a band of its own.
      datum is VegaValue.Obj && Scales.looksLikeADateTime(datum) -> MeasureType.TEMPORAL
      datum != null -> MeasureType.NOMINAL
      scaleCategory(def) != null -> scaleCategory(def)
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

  /** What a stated `scale.type` says the field must be — `SCALE_CATEGORY_INDEX`. */
  private fun scaleCategory(def: VegaValue.Obj): MeasureType? =
    when (def.obj("scale")?.string("type")) {
      "linear",
      "log",
      "pow",
      "sqrt",
      "symlog",
      "identity",
      "sequential",
      "quantile",
      "quantize",
      "threshold" -> MeasureType.QUANTITATIVE
      "time",
      "utc" -> MeasureType.TEMPORAL
      else -> null
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

    val x2 = encoding["x2"]
    val y2 = encoding["y2"]

    // The *second position* decides before anything else does — but only where **one** of the two
    // is ranged. A mark ranged along both axes has no orientation at all: it runs from one point to
    // another and neither axis is the one it measures along, which is as true of a bar drawn as a
    // lane between four coordinates as it is of a line segment. Upstream falls the ranged bar
    // through to the rule's own rule for exactly that.
    if (mark == "rule" || mark == "area" || mark == "bar") {
      if (y2 != null || x2 != null) {
        if (x2 == null) {
          // A *pre-binned* first position turns the answer around: the pair of edges the data
          // arrived with is the extent of the bar's own band, not the direction it grows in.
          val xIsNumber = x?.isUnbinnedQuantitative == true || x?.datum is VegaValue.Num
          return if (xIsNumber && y?.bin == Binning.PreBinned) "horizontal" else "vertical"
        }
        if (y2 == null) {
          val yIsNumber = y?.isUnbinnedQuantitative == true || y?.datum is VegaValue.Num
          return if (yIsNumber && x?.bin == Binning.PreBinned) "vertical" else "horizontal"
        }
      }
      if (x2 != null && x?.bin != Binning.PreBinned && y2 != null && y?.bin != Binning.PreBinned) {
        return null
      }
      if (mark == "rule") {
        if (x != null && y == null) return "vertical"
        if (y != null && x == null) return "horizontal"
      }
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
        "trail",
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
    /** The two aggregates that answer with a whole row rather than a number. */
    private val ARGMINMAX = setOf("argmin", "argmax")

    /**
     * `autoMaxBins`: how many buckets a `bin: true` asks for, which depends on the channel.
     *
     * Ten along an axis, where a reader can follow a fine grid; **six** on a colour, a size or a
     * facet, where more than a handful of steps stop being tellable apart — upstream picks six "to
     * simplify the rule", matching the six shapes Vega has; and four on a stroke dash, there being
     * five patterns and four reading better. The number is in the field's own name, so getting it
     * wrong renames every column the bin produces.
     */
    fun autoMaxBins(channel: String): Int =
      when (channel) {
        "row",
        "column",
        "size",
        "color",
        "fill",
        "stroke",
        "strokeWidth",
        "opacity",
        "fillOpacity",
        "strokeOpacity",
        "shape" -> 6
        "strokeDash" -> 4
        else -> 10
      }

    val UNSUPPORTED_CHANNELS =
      setOf(
        "latitude",
        "longitude",
        "latitude2",
        "longitude2",
        "geojson",
        "xError",
        "yError",
        "url",
        "time",
      )
  }
}
