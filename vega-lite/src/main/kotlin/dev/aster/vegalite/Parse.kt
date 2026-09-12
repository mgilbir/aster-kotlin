package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.canonicalNumberString

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

    // The mark **type** is read before the encoding is, because which channels mean anything is the
    // mark's answer: `const mark = markDef.type` above `initEncoding(spec.encoding, mark, …)`. The
    // rest of the mark's definition still comes after, that part depending on the encoding.
    val markType =
      when (markValue) {
        is VegaValue.Str -> markValue.value
        is VegaValue.Obj -> markValue.string("type")
        else -> null
      }
    val graticule = spec.obj("data")?.fields?.containsKey("graticule") == true
    val encoding =
      encoding(
        spec.obj("encoding") ?: VegaValue.EmptyObject,
        "$path.encoding",
        markType,
        markType?.let {
          filled(markValue as? VegaValue.Obj ?: VegaValue.EmptyObject, it, graticule)
        } ?: false,
      )
    val markDef = markDef(markValue, encoding, path, graticule) ?: return null

    // `alignStackOrderWithColorDomain`: a chart whose colours are listed in a stated order is drawn
    // in that order too, and the rule reaches into the encoding to say so.
    //
    // **Asked of the stack, not guessed from the encoding.** Upstream computes `this.stack` and
    // then
    // aligns — `stack(markDef, encoding)` on line 118 of `unit.ts`, the alignment on line 128 — so
    // the question is answered by the same code that decides whether anything stacks at all. This
    // used to approximate it as "a quantitative position that aggregates", which misses a chart
    // that stacks because it *said* `stack: true` and nothing else: 62 charts in the wild corpus
    // differed by exactly the one formula this produces.
    //
    // The provisional spec is the pre-alignment one, which is also upstream's: the alignment adds
    // an
    // `order` channel afterwards and the stack is never recomputed against it.
    val provisional =
      UnitSpec(
        markDef = markDef,
        encoding = encoding,
        data = spec.fields["data"],
        transforms = spec.array("transform") ?: emptyList(),
        width = spec.fields["width"],
        height = spec.fields["height"],
        params = spec.array("params").orEmpty(),
        projection = spec.obj("projection"),
      )
    val aligned = alignStackOrderWithColorDomain(encoding, markDef, Stack.of(provisional) != null)

    return UnitSpec(
      markDef = markDef,
      encoding = aligned.encoding,
      data = spec.fields["data"],
      transforms = (spec.array("transform") ?: emptyList()) + aligned.transforms,
      width = spec.fields["width"],
      height = spec.fields["height"],
      params = spec.array("params").orEmpty(),
      // A **projection** belongs to the unit that draws through it. A layer may state one of its
      // own, and a chart's own is handed down to the members that did not.
      projection = spec.obj("projection"),
      viewBackground = spec.obj("view"),
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
    /**
     * Whether this view stacks, which is `this.stack` in `unit.ts` and decides the second branch.
     */
    stacked: Boolean,
  ): Aligned {
    if (encoding.containsKey("order")) return Aligned(encoding)
    val colour = encoding["fill"] ?: encoding["color"] ?: return Aligned(encoding)
    if (colour.type != MeasureType.NOMINAL) return Aligned(encoding)
    val field = colour.field ?: return Aligned(encoding)
    val domain = colour.scale?.array("domain") ?: return Aligned(encoding)

    val offsetChannel =
      listOf("xOffset", "yOffset").firstOrNull { encoding[it]?.isFieldDef == true }
    // Upstream: `if (offsetEncoding && !offsetEncoding.sort) … else { … the stack branch … }`. An
    // offset that already states a `sort` therefore **falls through** to the stack branch rather
    // than ending the rule, which is what this used to do.
    //
    // No observable difference has been found for it: a chart dodged by an offset channel does not
    // stack, so the branch returns on `!stacked` either way. Aligned regardless, because a rule
    // that
    // agrees by accident stops agreeing as soon as anything around it moves.
    if (offsetChannel != null && encoding.getValue(offsetChannel).sort == null) {
      val offset = encoding.getValue(offsetChannel)
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
    // nothing whose order this could be. Upstream's test is `if (!this.stack) return`.
    if (!stacked) return Aligned(encoding)
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
    // horizontal one, which counts up.
    //
    // **The mark's *resolved* orientation.** Upstream reads
    // `this.markDef?.orient === 'horizontal' ? 'ascending' : 'descending'`, and `this.markDef` is
    // the initialised definition: `initMarkDef` has already run
    // `markDef.orient = orient(type, encoding, specifiedOrient)`, so a chart that states no
    // `orient`
    // still has the one inferred from its encoding. Reading the *stated* value instead ordered
    // `stacked_bar_h_custom_color_domain` the wrong way about, which the gallery gate caught on the
    // first run — the reason this rule is checked against those 627 before the wild corpus.
    val direction = if (markDef.orient == "horizontal") "ascending" else "descending"
    val orderDef = obj {
      put("field", order)
      put("type", "quantitative")
      put("sort", direction)
    }
    return Aligned(
      encoding +
        ("order" to
          channelDef("order", orderDef, "$.encoding.order")!!.copy(addedAfterStack = true)),
      listOf(calculate),
    )
  }

  fun markDef(
    value: VegaValue,
    encoding: Map<String, ChannelDef>,
    path: String,
    /** Whether the view's rows are the globe's own grid, which is drawn rather than filled. */
    graticule: Boolean = false,
  ): MarkDef? {
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
    val filled = filled(raw, type, graticule)

    return MarkDef(
      type = type,
      raw = raw,
      filled = filled,
      // A **style** block outranks every other configuration of a mark property —
      // `getMarkConfig` puts it first — and the styles a mark has are its own type followed by
      // whatever its `style` names, the last one that says anything winning. A parallel-coordinate
      // plot turning its ticks on their side in `config.style.tick` is where it tells.
      orient =
        raw.string("orient")
          ?: styleOrient(config, type, raw)
          ?: markConfig.string("orient")
          ?: defaultOrient(type, encoding),
    )
  }

  /**
   * `getStyleConfig`: the mark's own type first, then each style it names, the last one winning.
   */
  private fun styleOrient(config: Config, type: String, raw: VegaValue.Obj): String? {
    val named =
      when (val style = raw.fields["style"]) {
        is VegaValue.Str -> listOf(style.value)
        is VegaValue.Arr -> style.values.mapNotNull { (it as? VegaValue.Str)?.value }
        else -> emptyList()
      }
    return (listOf(type) + named).mapNotNull { config.style(it)?.string("orient") }.lastOrNull()
  }

  /**
   * The encoding, re-ordered into upstream's channel order rather than the order it was written in.
   *
   * This is `initEncoding`, and the order is not cosmetic: it decides the order of the scales, the
   * axes and the fields in the spoken description, so a specification that happens to list `y`
   * before `x` still produces the same chart as one that does not.
   */
  fun encoding(
    block: VegaValue.Obj,
    path: String,
    mark: String? = null,
    filled: Boolean = false,
  ): Map<String, ChannelDef> {
    val result = LinkedHashMap<String, ChannelDef>()
    val ordered =
      Channels.UNIT_CHANNELS.filter { block.fields.containsKey(it) } +
        block.fields.keys.filter { it !in Channels.UNIT_CHANNELS }
    val known = mark != null && mark in Channels.MARKS
    for (written in ordered) {
      val value = block.fields.getValue(written)
      // An **offset nested inside a continuous position** is dropped: offsetting a band is moving
      // the mark within its own slot, and a continuous position has no slot to move within.
      // Upstream's own note says the right behaviour would be to offset in *data* space, and until
      // it does, the encoding goes. A position bucketed by a time unit is exempt — that one has
      // bands after all — and so is an offset given as a plain value.
      if (known && (written == "xOffset" || written == "yOffset")) {
        // `normalizedEncoding[mainChannel]`: the position **as parsed**, so a column that names no
        // type but aggregates counts as the continuous position it will be drawn as.
        val position = result[if (written == "xOffset") "x" else "y"]
        val continuous =
          position?.field != null &&
            (position.type == MeasureType.QUANTITATIVE || position.type == MeasureType.TEMPORAL) &&
            position.timeUnit == null
        if (continuous && namesColumn(value as? VegaValue.Obj)) {
          diagnostics.warn(
            VegaLiteDiagnostics.UNSUPPORTED_CHANNEL,
            "`$written` offsets a mark within its band, and a continuous " +
              "`${if (written == "xOffset") "x" else "y"}` has no band to offset within, so the " +
              "encoding is dropped — upstream does the same.",
            jsonPath = "$path.$written",
          )
          continue
        }
      }
      // An `angle` on a pie is the **slice**, not the rotation of a glyph that has none — upstream
      // reads it as `theta` and says so, and it has to happen before the channel is asked whether
      // an `arc` supports it, which an `angle` is not.
      val channel =
        if (written == "angle" && mark == "arc" && block.fields["theta"] == null) "theta"
        else written
      // `markChannelCompatible`: a channel the mark has no use for is dropped, and dropping it is
      // not cosmetic. It would otherwise group an aggregate, name a scale of its own and be spoken
      // in the chart's description — a line whose layer states the `text` its sibling label draws
      // described every point by a column the line does not show.
      if (mark != null && known && !compatible(channel, mark, block)) {
        diagnostics.warn(
          VegaLiteDiagnostics.UNSUPPORTED_CHANNEL,
          "A `$mark` has nothing to set from `$channel`, so its encoding is dropped — upstream " +
            "does the same, and reports `$channel dropped as it is incompatible with \"$mark\"`.",
          jsonPath = "$path.$written",
        )
        continue
      }
      // A **line of varying thickness** is not a line Vega can draw: one `line` mark is one path,
      // and a path has one `strokeWidth`. A size that varies per row is therefore dropped where it
      // would vary — an aggregate is one value per group, and a group is what a line joins.
      if (known && channel == "size" && mark == "line" && aggregates(value)) {
        diagnostics.warn(
          VegaLiteDiagnostics.UNSUPPORTED_CHANNEL,
          "A `line` is one path of one thickness, so a `size` that aggregates is dropped — " +
            "upstream does the same. Draw the varying thickness with a `trail`.",
          jsonPath = "$path.$written",
        )
        continue
      }
      // `color` is the channel that means *whichever of fill and stroke this mark paints with*, so
      // stating that one **as well** leaves nothing for the colour to set. Which one it collides
      // with is the mark's own answer: a filled mark is painted by its fill, and an outline by its
      // stroke.
      if (known && channel == "color" && block.fields[if (filled) "fill" else "stroke"] != null) {
        diagnostics.warn(
          VegaLiteDiagnostics.UNSUPPORTED_CHANNEL,
          "A `${if (filled) "fill" else "stroke"}` is what a `$mark` would have taken its " +
            "`color` from, so the `color` encoding is dropped — upstream does the same.",
          jsonPath = "$path.$written",
        )
        continue
      }
      if (channel in UNSUPPORTED_CHANNELS) {
        diagnostics.error(
          VegaLiteDiagnostics.UNSUPPORTED_CHANNEL,
          "The `$channel` channel is not implemented; its encoding is ignored. Express the view " +
            "with the position, colour, size, shape, text and detail channels instead.",
          jsonPath = "$path.$written",
        )
        continue
      }
      // **Not a channel at all**, which upstream drops with `"<name>-encoding is dropped as <name>
      // is not a valid encoding channel"`. This engine kept it: it went into `result`, and from
      // there into an aggregate's `groupby`, the spoken description and the tooltip's field list.
      // So `"colour"` — the spelling half the English-speaking world uses — produced a chart that
      // was grouped by a column nothing was coloured with, and said nothing about it.
      if (channel !in Channels.UNIT_CHANNELS && channel !in Channels.FACET_CHANNELS) {
        diagnostics.warn(
          VegaLiteDiagnostics.UNSUPPORTED_CHANNEL,
          "`$channel` is not an encoding channel, so its encoding is dropped — upstream does the " +
            "same. A near miss for one that is, such as `colour` for `color`, is worth checking.",
          jsonPath = "$path.$written",
        )
        continue
      }
      // A multi-definition channel may hold an array. The first entry is the definition proper,
      // because everything that reads a channel reads one; the others are kept beside it, and
      // losing them loses every field but the first from a tooltip.
      val entries = (value as? VegaValue.Arr)?.values ?: listOf(value)
      val parsed = entries.mapIndexedNotNull { index, entry ->
        val at = if (value is VegaValue.Arr) "$path.$written[$index]" else "$path.$written"
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
   * `defaultFilled`, asked **before** anything else about the mark.
   *
   * ```js
   * // Need to init filled before other mark properties because encoding depends on filled but
   * // other mark properties depend on types inside encoding
   * ```
   *
   * The encoding's colour channel resolves to a `fill` or a `stroke` depending on it — and so does
   * whether a `color` beside one of them is dropped — while the mark's own properties depend on the
   * encoding, so the order of those three questions is fixed.
   *
   * A **graticule** is not filled. It is the globe's grid of meridians and parallels — lines,
   * whatever mark draws them — and filling each cell of it would paint over the map underneath.
   */
  private fun filled(raw: VegaValue.Obj, type: String, graticule: Boolean): Boolean =
    raw.boolean("filled")
      ?: config.markConfig(type).boolean("filled")
      ?: if (graticule) false else (type != "point" && type != "line" && type != "rule")

  /**
   * `markChannelCompatible`: whether this mark has anything to set from this channel.
   *
   * ```js
   * const markSupported = supportMark(channel, mark);
   * if (!markSupported) return false;
   * else if (markSupported === 'binned') {
   *   const primaryFieldDef = encoding[channel === X2 ? X : Y];
   *   if (isFieldDef(primaryFieldDef) && isFieldDef(encoding[channel]) && isBinned(primaryFieldDef.bin))
   *     return true;
   *   return false;
   * }
   * ```
   *
   * The second edge of an interval is the only conditional case: a mark that draws a **point**
   * takes an `x2` to say where the bin the point sits in ends, so the primary channel has to be one
   * whose data arrived already binned. The primary channel it looks at is `x` for an `x2` and `y`
   * for everything else, latitudes included — upstream's own reading, and not a simplification of
   * it.
   */
  private fun compatible(channel: String, mark: String, encoding: VegaValue.Obj): Boolean {
    val supported = Channels.supportsMark(channel, mark) ?: return false
    if (supported != "binned") return true
    val primary = encoding.obj(if (channel == "x2") "x" else "y") ?: return false
    if (!namesColumn(primary) || !namesColumn(encoding.obj(channel))) return false
    // `isBinned`: the string, and an object saying so — the two ways a specification says its data
    // was binned before it arrived.
    val bin = primary.fields["bin"]
    return bin == VegaValue.Str("binned") ||
      (bin as? VegaValue.Obj)?.fields?.get("binned") == VegaValue.Bool(true)
  }

  /** `isFieldDef`: a definition naming a column, or counting the rows. */
  private fun namesColumn(def: VegaValue.Obj?): Boolean =
    def != null &&
      (def.fields["field"] != null || def.fields["aggregate"] == VegaValue.Str("count"))

  /** `getFieldDef(…)?.aggregate` — which reaches into a `condition` for its definition. */
  private fun aggregates(value: VegaValue?): Boolean {
    val def = value as? VegaValue.Obj ?: return false
    return def.fields["aggregate"] != null || def.obj("condition")?.fields?.get("aggregate") != null
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

  /**
   * A facet level's own channels, parsed on their own rather than as part of an encoding.
   *
   * A grid whose cells are grids has a level that never reaches any encoding: only the outermost
   * one is folded down, the rest being kept apart so that two `row` facets do not collide. Each is
   * parsed here when its turn to be lifted comes.
   */
  fun facetChannels(facet: VegaValue.Obj, path: String): Map<String, ChannelDef> {
    val out = LinkedHashMap<String, ChannelDef>()
    // A single field is the wrapped form and is the `facet` channel itself; `row` and `column` are
    // the crossed one. Read in the order written, as `forEachFieldDef` walks them.
    if (facet.has("row") || facet.has("column")) {
      facet.fields.forEach { (channel, value) ->
        if (channel != "row" && channel != "column") return@forEach
        channelDef(channel, value, "$path.$channel")?.let { out[channel] = it }
      }
    } else {
      channelDef("facet", facet, path)?.let { out["facet"] = it }
    }
    return out
  }

  /**
   * The column a definition names, read as **JavaScript** reads it.
   *
   * `field` is a string in the grammar and nothing upstream checks that: it is spelled into a
   * template — `` `${expr}["${channelDef.field}"]` `` — and into `vgField`'s regular expressions,
   * both of which coerce whatever they are given. So `"field": ["2021"]` names the column `2021`, a
   * one-element array stringifying to its element, and `"field": 2021` names it too.
   *
   * This read the property as a string and answered nothing for anything else, which makes the
   * definition not a field definition at all: the channel then had no scale, and a map coloured by
   * a column written that way was drawn in one flat colour. Two specifications in the wild corpus
   * write the array form.
   *
   * An object or a `null` is refused rather than coerced — `[object Object]` and `null` are columns
   * no table has, and a chart naming one is a chart with a mistake in it worth reporting.
   */
  private fun fieldName(value: VegaValue.Obj, path: String): String? {
    val stated = value.fields["field"] ?: return null
    val coerced = jsString(stated)
    if (coerced == null) {
      diagnostics.warn(
        VegaLiteDiagnostics.INVALID_ENCODING,
        "A `field` names a column, so it has to be text; this one is neither text nor a number, " +
          "and the channel is read as naming no column at all.",
        jsonPath = "$path.field",
      )
      return null
    }
    if (stated !is VegaValue.Str) {
      diagnostics.warn(
        VegaLiteDiagnostics.INVALID_ENCODING,
        "A `field` names a column, so it should be written as text. Upstream reads this one as " +
          "`$coerced` — JavaScript's own string coercion — and so does this compiler.",
        jsonPath = "$path.field",
      )
    }
    return coerced
  }

  /** `String(value)` for the values a `field` may have been written as, and null for the rest. */
  private fun jsString(value: VegaValue): String? =
    when (value) {
      is VegaValue.Str -> value.value
      is VegaValue.Num -> canonicalNumberString(value.value)
      is VegaValue.Bool -> value.value.toString()
      // `Array.prototype.toString`: the elements coerced in turn and joined with a comma, which is
      // why a **one-element** array is indistinguishable from its element.
      is VegaValue.Arr -> {
        val parts = value.values.map { jsString(it) ?: return null }
        parts.joinToString(",")
      }
      else -> null
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

    val field = fieldName(value, path)
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
      raw = withHeaderOrients(value),
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
   * `normalizeFieldDef`: a header's `orient` is a **shortcut** for both of its orients.
   *
   * ```js
   * const {orient, ...rest} = header;
   * if (orient) {
   *   return {...fieldDef, header: {...rest, labelOrient: header.labelOrient || orient,
   *                                          titleOrient: header.titleOrient || orient}};
   * }
   * ```
   *
   * Expanding it once, here, is what lets everything downstream ask for the part it is drawing —
   * the caption's side or the heading's — rather than each reader remembering the shortcut. And the
   * expansion is what a *reader* of the header sees: the `orient` itself is dropped, so the
   * property rename that carries a header's styling onto the caption carries the side with it.
   */
  private fun withHeaderOrients(value: VegaValue.Obj): VegaValue.Obj {
    val header = value.obj("header") ?: return value
    val orient = header.fields["orient"] ?: return value
    return obj {
      putAll(value)
      put(
        "header",
        obj {
          header.fields.forEach { (key, entry) -> if (key != "orient") put(key, entry) }
          put("labelOrient", header.fields["labelOrient"] ?: orient)
          put("titleOrient", header.fields["titleOrient"] ?: orient)
        },
      )
    }
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
      // The **second** place is a place too: a longitude is a number whichever end of a line it
      // is, and read as a name it is spoken as text rather than formatted as a coordinate.
      channel in Channels.GEO_POSITION_CHANNELS -> MeasureType.QUANTITATIVE
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
        // A picture placed where a point would be: a rect-based mark whose `url` names the file.
        "image",
        // An outline on the globe, drawn through a projection rather than through two scales.
        "geoshape",
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
        "geojson",
        "xError",
        "yError",
      )
  }
}
