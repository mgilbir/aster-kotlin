package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * Axes and legends: the guides Vega-Lite writes for you.
 *
 * These are where most of a Vega-Lite chart's apparent intelligence lives. A specification names a
 * field and gets a grid behind the marks, a tick count that follows the plot's size, a label angle
 * that keeps categories readable and a title that says `Mean of b` rather than `mean_b`. All of it
 * is defaulted, and none of it is guessed here — the rules are ported from `compile/axis/` and
 * `compile/legend/`.
 */
internal object Guides {

  /**
   * An axis is emitted twice: once as gridlines and once as the axis proper.
   *
   * Upstream splits them because they belong at different depths — the grid is painted behind the
   * marks and carries no labels or domain, while the axis is painted in front. Each property knows
   * which half it belongs to.
   */
  private val MAIN_ONLY =
    setOf(
      "aria",
      "description",
      "domain",
      "domainCap",
      "domainColor",
      "domainDash",
      "domainDashOffset",
      "domainOpacity",
      "domainWidth",
      "format",
      "formatType",
      "labelAlign",
      "labelAngle",
      "labelBaseline",
      "labelBound",
      "labelColor",
      "labelFlush",
      "labelFlushOffset",
      "labelFont",
      "labelFontSize",
      "labelFontStyle",
      "labelFontWeight",
      "labelLimit",
      "labelLineHeight",
      "labelOffset",
      "labelOpacity",
      "labelOverlap",
      "labelPadding",
      "labels",
      "labelSeparation",
      "orient",
      "tickCap",
      "tickColor",
      "tickDash",
      "tickDashOffset",
      "tickExtra",
      "tickOffset",
      "tickOpacity",
      "tickRound",
      "tickSize",
      "tickWidth",
      "ticks",
      "title",
      "titleAlign",
      "titleAnchor",
      "titleAngle",
      "titleBaseline",
      "titleColor",
      "titleFont",
      "titleFontSize",
      "titleFontStyle",
      "titleFontWeight",
      "titleLimit",
      "titleLineHeight",
      "titleOpacity",
      "titlePadding",
      "titleX",
      "titleY",
    )

  private val GRID_ONLY =
    setOf(
      "grid",
      "gridCap",
      "gridColor",
      "gridDash",
      "gridDashOffset",
      "gridOpacity",
      "gridScale",
      "gridWidth",
    )

  /** One axis component per position channel, built from every view that encodes that channel. */
  class AxisComponent(val channel: String) {
    val properties: LinkedHashMap<String, VegaValue> = LinkedHashMap()
    val titles: MutableList<String> = mutableListOf()

    /**
     * Whether the specification named this axis's title itself.
     *
     * An **explicit** title short-circuits the merge, across layers as well as across the two ends
     * of a ranged position — `mergeTitleComponent` keeps the explicit one and drops the derived.
     * Joining them instead titles an axis `Value, PM2.5 Value`, which names the column twice.
     */
    var explicitTitle: Boolean = false
    var disabled: Boolean = false

    /**
     * Whether the specification named the side itself.
     *
     * Two independent axes on one channel are moved apart, and one the specification placed is left
     * where it was put — upstream's `!explicit` guard.
     */
    var explicitOrient: Boolean = false

    fun set(name: String, value: VegaValue?) {
      if (value != null && !properties.containsKey(name)) properties[name] = value
    }

    /** Overwrites what was already decided, which only the layer-level merge ever needs to do. */
    fun override(name: String, value: VegaValue) {
      properties[name] = value
    }
  }

  fun parseAxis(
    view: UnitView,
    channel: String,
    def: ChannelDef,
    type: String,
    hasOtherPosition: Boolean,
  ): AxisComponent? {
    if (def.axisDisabled) return null
    val axis = AxisComponent(channel)
    val user = def.axis

    axis.set("scale", str(view.scale(channel)))
    axis.explicitOrient = user?.fields?.get("orient") != null
    axis.set("orient", str(if (channel == "x") "bottom" else "left"))

    // The gridlines belong to *this* scale but are drawn across the other one's extent. Whether
    // there are any is a *default* — a continuous field-driven axis has them — and a theme saying
    // `config.axis.grid: false` settles it for every axis at once, which is how a chart turns them
    // all off in one line. Reading only the channel's own `axis` block left them on.
    val grid =
      (def.axis?.fields?.get("grid") ?: view.config.axisConfig("grid"))?.let {
        (it as? VegaValue.Bool)?.value == true
      } ?: (!Scales.hasDiscreteDomain(type) && def.isFieldDef && def.bin == null)
    if (grid && hasOtherPosition)
      axis.set("gridScale", str(view.scale(if (channel == "x") "y" else "x")))
    axis.set("grid", bool(grid))

    // A ranged position is titled by *both* of its fields — `start, end` — because the axis is
    // measuring the span rather than either end of it. Unless one of them says what it is called:
    // an **explicit** title short-circuits the merge (`getFieldDefTitle`), which is how a summary
    // of `v` is titled `v` rather than `lower_v, upper_v`.
    val secondary = secondaryChannel(channel)?.let { view.spec.fieldDef(it) }
    val stated = listOfNotNull(def.explicitTitle, secondary?.explicitTitle)
    if (stated.isNotEmpty()) {
      axis.explicitTitle = true
      stated.mapNotNull { (it as? VegaValue.Str)?.value }.forEach { axis.titles += it }
    } else {
      for (channelDef in listOfNotNull(def, secondary)) {
        val title = Fields.title(channelDef, view.config) as? VegaValue.Str ?: continue
        if (title.value !in axis.titles) axis.titles += title.value
      }
    }

    // A nominal category on the horizontal axis is turned on its side, because side-by-side labels
    // collide as soon as there are more than a handful of them.
    val labelAngle =
      user?.number("labelAngle")
        ?: if (channel == "x" && def.type?.isDiscrete == true && def.timeUnit == null) 270.0
        else null
    if (labelAngle != null) {
      axis.set("labelAngle", num(labelAngle))
      labelAlign(labelAngle, channel)?.let { axis.set("labelAlign", str(it)) }
      labelBaseline(labelAngle, channel)?.let { axis.set("labelBaseline", str(it)) }
    }

    if (
      channel == "x" && (def.type == MeasureType.QUANTITATIVE || def.type == MeasureType.TEMPORAL)
    ) {
      axis.set("labelFlush", bool(true))
    }

    // A continuous axis may drop labels that would overlap; a nominal one may not, because a reader
    // cannot infer the category that went missing. A log axis drops them *greedily* rather than by
    // parity: its labels are unevenly spaced, so hiding every other one thins the dense end and
    // leaves the sparse end untouched.
    // A bucketed instant is the exception among discrete labels: a reader who sees Jan, Mar, May
    // supplies February, so `defaultLabelOverlap` lets a time unit thin its own labels — unless the
    // specification stated an order, where a gap would leave the reader guessing what was skipped.
    val timeUnitLabels = def.timeUnit != null && def.sort !is VegaValue.Obj
    if (timeUnitLabels || (def.type != MeasureType.NOMINAL && def.type != MeasureType.ORDINAL)) {
      val greedy = type == "log" || type == "symlog"
      axis.set("labelOverlap", if (greedy) str("greedy") else bool(true))
    }

    // Labels for a bucketed instant, and a tick step no finer than the bucket.
    if (def.timeUnit != null) {
      axis.set("format", signalRef(Fields.timeUnitSpecifier(def.timeUnit)))
      Fields.timeUnitDuration(def.timeUnit)?.let { axis.set("tickMinStep", signalRef(it)) }
    }
    // `guideFormatType`: a specifier is a *time* specifier, and Vega has to be told so wherever the
    // scale itself does not already say it. A time or utc scale formats instants by nature; a band
    // scale of month names does not, and without this its labels come out as raw numbers.
    formatType(def, type)?.let { axis.set("formatType", str(it)) }

    // A normalized stack is a proportion, so its axis is a percentage —
    // `config.normalizedNumberFormat`,
    // which defaults to `.0%`. Left off, the labels read 0, 0.2, 0.4 for what the chart draws as
    // fifths of a whole.
    if (view.stack?.offset == "normalize" && channel == view.stack.fieldChannel) {
      axis.set(
        "format",
        str(view.config.normalizedNumberFormat),
      )
    }

    tickCount(view, channel, def, type)?.let { axis.set("tickCount", it) }

    // A heatmap's axis is drawn *over* its cells: the rects fill their bands completely, so an axis
    // painted underneath would be hidden by them.
    if (view.spec.mark == "rect" && def.type?.isDiscrete == true) axis.set("zindex", num(1))

    user?.fields?.forEach { (key, value) -> axis.properties[key] = value }
    conditionalToEncode(axis)

    // `defaultTickMinStep`: a `d` format asks for whole numbers, so no tick may be closer than one.
    // Read *after* the specification's own block, because that is where the format usually comes
    // from — and `set` leaves a stated `tickMinStep` alone.
    if ((axis.properties["format"] as? VegaValue.Str)?.value == "d") axis.set("tickMinStep", num(1))
    return axis
  }

  /**
   * `guideFormatType`: whether a guide's format string is a *time* specifier.
   *
   * A time or utc scale already labels instants as instants, so it says nothing. Everything else —
   * a band of month names, an ordinal of quarters — needs telling, or Vega reads the specifier as a
   * number format and prints the bucket's milliseconds.
   */
  private fun formatType(def: ChannelDef, scaleType: String): String? {
    if (scaleType == "time" || scaleType == "utc") return null
    if (def.type != MeasureType.TEMPORAL && def.timeUnit == null) return null
    // `normalizeTimeUnit` reads the `utc` out of the unit's *name*, wherever it sits: `utcmonth`
    // and `binnedutcyearmonth` are both universal time.
    return if (def.timeUnit?.contains("utc") == true) "utc" else "time"
  }

  /**
   * How many ticks to ask for: one per forty units of plot, or one per ten when the data is binned
   * — a binned axis wants a tick per bin edge and no more.
   */
  private fun tickCount(
    view: UnitView,
    channel: String,
    def: ChannelDef,
    type: String,
  ): VegaValue? {
    if (Scales.hasDiscreteDomain(type) || type == "log") return null
    // An axis told which ticks to show has been told how many, and asking for a count beside the
    // list is a second answer to a settled question — `if (!vals && ...)` in `defaultTickCount`.
    if (def.axis?.fields?.get("values") != null) return null
    val size = view.sizeSignal(channel)
    if (def.bin is Binning.Bin) return signalRef("ceil($size/10)")
    // A bucket that cycles has a known, small number of values — twelve months, twenty-four hours
    // — so asking for a tick per forty units would ask for ticks between them. Upstream compares
    // the *normalized* unit, which is the name with any `utc` taken off the front: `utcmonth` is a
    // month, and `yearmonth` is not one of these, being unbounded.
    if (def.timeUnit?.removePrefix("utc") in setOf("month", "hours", "day", "quarter")) return null
    return signalRef("ceil($size/40)")
  }

  /**
   * Which end of a rotated label sits against its tick.
   *
   * Both of these are `defaultLabelAlign`/`defaultLabelBaseline` with the axis on its default side
   * — the bottom for `x`, the left for `y` — which is the only case this compiler emits. A label
   * turned to 270° on the bottom axis reads upwards, so its *right* end is the one at the tick.
   */
  private fun labelAlign(angle: Double, channel: String): String? {
    val startAngle = if (channel == "x") 0.0 else 90.0
    if ((angle + startAngle) % 180.0 == 0.0) return if (channel == "x") null else "center"
    return if (startAngle < angle && angle < 180 + startAngle) "left" else "right"
  }

  private fun labelBaseline(angle: Double, channel: String): String? {
    if (channel == "x") {
      if ((45 < angle && angle < 135) || (225 < angle && angle < 315)) return "middle"
      return if (angle <= 45 || 315 <= angle) "top" else "bottom"
    }
    if (angle <= 45 || 315 <= angle || (135 <= angle && angle <= 225)) return null
    return if (45 <= angle && angle <= 135) "top" else "bottom"
  }

  /**
   * The Vega encode channel a **conditional** axis property becomes —
   * `CONDITIONAL_AXIS_PROP_INDEX`.
   *
   * Vega has no conditional guide properties, so a `gridColor` written as a condition has to become
   * a `stroke` on the grid *part*'s encode block, as an array of refs ending in the unconditional
   * one. Left where it was written, Vega reads the whole object as a colour and paints nothing.
   */
  private val CONDITIONAL_AXIS_PARTS: Map<String, Pair<String, String>> =
    mapOf(
      "labelAlign" to ("labels" to "align"),
      "labelBaseline" to ("labels" to "baseline"),
      "labelColor" to ("labels" to "fill"),
      "labelFont" to ("labels" to "font"),
      "labelFontSize" to ("labels" to "fontSize"),
      "labelFontStyle" to ("labels" to "fontStyle"),
      "labelFontWeight" to ("labels" to "fontWeight"),
      "labelOpacity" to ("labels" to "opacity"),
      "gridColor" to ("grid" to "stroke"),
      "gridDash" to ("grid" to "strokeDash"),
      "gridDashOffset" to ("grid" to "strokeDashOffset"),
      "gridOpacity" to ("grid" to "opacity"),
      "gridWidth" to ("grid" to "strokeWidth"),
      "tickColor" to ("ticks" to "stroke"),
      "tickDash" to ("ticks" to "strokeDash"),
      "tickDashOffset" to ("ticks" to "strokeDashOffset"),
      "tickOpacity" to ("ticks" to "opacity"),
      "tickWidth" to ("ticks" to "strokeWidth"),
    )

  /** Moves every conditional property onto the encode block of the part it paints. */
  private fun conditionalToEncode(axis: AxisComponent) {
    val moved = LinkedHashMap<String, LinkedHashMap<String, VegaValue>>()
    for ((property, mapping) in CONDITIONAL_AXIS_PARTS) {
      val value = axis.properties[property] as? VegaValue.Obj ?: continue
      val condition = value["condition"] ?: continue
      val (part, vgProp) = mapping
      val otherwise = obj { value.fields.forEach { (k, v) -> if (k != "condition") put(k, v) } }
      val conditions =
        when (condition) {
          is VegaValue.Arr -> condition.values
          else -> listOf(condition)
        }
      moved.getOrPut(part) { LinkedHashMap() }[vgProp] = arr(conditions + otherwise)
      axis.properties.remove(property)
    }
    if (moved.isEmpty()) return
    val existing = axis.properties["encode"] as? VegaValue.Obj
    axis.properties["encode"] = obj {
      existing?.fields?.forEach { (k, v) -> put(k, v) }
      moved.forEach { (part, channels) ->
        put(part, obj { put("update", obj { channels.forEach { (k, v) -> put(k, v) } }) })
      }
    }
  }

  /**
   * The half of an `encode` block that belongs to one of the two axes a component splits into.
   *
   * The gridlines are drawn by the grid axis and everything else by the axis proper, so a `grid`
   * part carried onto the main axis encodes a mark that is not there — and one left off the grid
   * axis leaves the gridlines unpainted.
   */
  private fun encodeFor(encode: VegaValue, kind: String): VegaValue? {
    val parts = (encode as? VegaValue.Obj)?.fields ?: return null
    val kept = parts.filterKeys { (it == "grid") == (kind == "grid") }
    return if (kept.isEmpty()) null else obj { kept.forEach { (k, v) -> put(k, v) } }
  }

  /**
   * `labelExpr` becomes the labels' **text**, and only on the axis that draws labels.
   *
   * It is not a Vega axis property at all: `assembleAxis` destructures it out and writes
   * `encode.labels.update.text` from it. Passed through as a property it was silently ignored, and
   * passed through on the *gridline* axis it named an encode block for a mark that is not drawn.
   * Where an encode already states the text — a conditional label — `datum.label` in the expression
   * means that text rather than the axis's own, so the two compose instead of one replacing the
   * other.
   */
  private fun withLabelText(encode: VegaValue?, labelExpr: String): VegaValue {
    val parts = (encode as? VegaValue.Obj)?.fields.orEmpty()
    val labels = (parts["labels"] as? VegaValue.Obj)?.fields.orEmpty()
    val update = (labels["update"] as? VegaValue.Obj)?.fields.orEmpty()
    val stated = (update["text"] as? VegaValue.Obj)?.string("signal")
    val expression = if (stated == null) labelExpr else labelExpr.replace("datum.label", stated)
    return obj {
      parts.forEach { (key, value) -> if (key != "labels") put(key, value) }
      put(
        "labels",
        obj {
          labels.forEach { (key, value) -> if (key != "update") put(key, value) }
          put(
            "update",
            obj {
              update.forEach { (key, value) -> if (key != "text") put(key, value) }
              put("text", signalRef(expression))
            },
          )
        },
      )
    }
  }

  /** Splits one component into the gridline axis and the axis proper, in that order. */
  fun assembleAxis(axis: AxisComponent, kind: String): VegaValue? {
    val grid = (axis.properties["grid"] as? VegaValue.Bool)?.value == true
    if (kind == "grid" && !grid) return null
    val labelExpr = (axis.properties["labelExpr"] as? VegaValue.Str)?.value

    return obj {
      put("scale", axis.properties["scale"])
      put("orient", axis.properties["orient"])
      val zindex = axis.properties["zindex"] ?: num(0)
      if (kind == "grid") {
        axis.properties.forEach { (key, value) ->
          if (key == "encode") encodeFor(value, kind)?.let { put(key, it) }
          else if (key !in setOf("scale", "orient", "zindex", "labelExpr") && key !in MAIN_ONLY)
            put(key, value)
        }
        put("domain", false)
        put("labels", false)
        put("aria", false)
        // Zero extents keep a gridline axis from reserving any room of its own.
        put("maxExtent", 0)
        put("minExtent", 0)
        put("ticks", false)
        put("zindex", zindex)
      } else {
        put("grid", false)
        // Two layers over one axis contribute two titles, and upstream joins them with a comma
        // rather than picking one, so a shared axis says what it is showing.
        // `assembleTitle`: a falsy title is not written at all — `titleString ? {title} : {}`. An
        // axis the specification titled `""` has *no* caption, which is not the same as one
        // captioned with nothing.
        axis.titles
          .distinct()
          .filter { it.isNotEmpty() }
          .takeIf { it.isNotEmpty() }
          ?.let { put("title", str(it.joinToString(", "))) }
        var wroteEncode = false
        axis.properties.forEach { (key, value) ->
          if (key == "encode") {
            val own = encodeFor(value, kind)
            val withText = if (labelExpr == null) own else withLabelText(own, labelExpr)
            withText?.let {
              put(key, it)
              wroteEncode = true
            }
          } else if (
            key !in setOf("scale", "orient", "grid", "title", "zindex", "labelExpr") &&
              key !in GRID_ONLY
          ) {
            put(key, value)
          }
        }
        if (labelExpr != null && !wroteEncode) put("encode", withLabelText(null, labelExpr))
        put("zindex", zindex)
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Legends
  // ---------------------------------------------------------------------------------------------

  /** The legend a scaled non-position channel produces, or null when it produces none. */
  fun legend(view: UnitView, channel: String, def: ChannelDef, type: String): VegaValue? {
    if (def.legendDisabled) return null
    val filled = view.markDef.filled
    // `getLegendDefWithScale`: a trail's legend names two channels differently from every other
    // mark's. Its swatch is a short stroke, so colour goes on the `stroke` however the mark is
    // filled, and its `size` — a width along the path — is a `strokeWidth` rather than an area.
    val scaleChannel =
      when {
        view.spec.mark == "trail" && channel == "color" -> "stroke"
        view.spec.mark == "trail" && channel == "size" -> "strokeWidth"
        channel == "color" -> if (filled) "fill" else "stroke"
        else -> channel
      }

    val continuous =
      type == "linear" || type == "log" || type == "pow" || type == "sqrt" || type == "symlog"
    val gradient = channel in setOf("color", "fill", "stroke") && continuous

    return obj {
      put(scaleChannel, view.scale(channel))
      // A legend labels a bucketed instant the same way an axis does, and for the same reason: the
      // swatch beside a colour ramp of months should read `Jan`, not the month's number.
      if (def.timeUnit != null) put("format", signalRef(Fields.timeUnitSpecifier(def.timeUnit)))
      formatType(def, type)?.let { put("formatType", it) }
      // `defaultLabelOverlap` for a legend, which is a shorter list than an axis's: a scale whose
      // entries are unevenly spaced drops labels *greedily*, keeping the first of each collision
      // rather than every other one, because parity would thin the crowded end alone.
      if (type in setOf("quantile", "threshold", "log", "symlog")) put("labelOverlap", "greedy")
      if (gradient) {
        // A colour ramp is drawn as a bar whose length follows the plot, within Vega's own limits.
        put("gradientLength", signalRef("clamp(height, 64, 200)"))
      } else {
        put("symbolType", defaultSymbolType(view, channel))
      }
      Fields.title(def, view.config)?.let { put("title", it) }
      // A legend along the top or bottom of a chart runs **horizontally**; Vega's own default is
      // vertical, and every other orientation keeps it — `defaultDirection`, with the inner
      // corners taking it only for a gradient.
      when (def.legend?.string("orient")) {
        "top",
        "bottom" -> put("direction", "horizontal")
        "left",
        "right",
        "none",
        null -> Unit
        else -> if (gradient) put("direction", "horizontal")
      }
      def.legend?.fields?.forEach { (key, value) -> put(key, value) }
      if (gradient) {
        // A ramp is painted at the mark's own opacity, so a legend beside a chart of translucent
        // points is as translucent as they are — `gradient` in `legend/encode.ts`. Zero and absent
        // both mean "say nothing", since a legend drawn at zero opacity is not a legend.
        symbolOpacity(view)
          ?.takeIf { it != 1.0 && it != 0.0 }
          ?.let { opacity ->
            put(
              "encode",
              obj {
                put(
                  "gradient",
                  obj { put("update", obj { put("opacity", obj { put("value", opacity) }) }) },
                )
              },
            )
          }
      } else {
        symbolEncode(view, channel)?.let {
          put("encode", obj { put("symbols", obj { put("update", it) }) })
        }
      }
    }
  }

  /** The glyph a legend entry draws: a line's legend shows a stroke, a bar's shows a square. */
  private fun defaultSymbolType(view: UnitView, channel: String): String {
    if (channel != "shape") {
      view.spec.encoding["shape"]?.value?.let { shape ->
        (shape as? VegaValue.Str)?.let {
          return it.value
        }
      }
      view.markDef.string("shape")?.let {
        return it
      }
    }
    return when (view.spec.mark) {
      "bar",
      "rect",
      "image",
      "square" -> "square"
      "line",
      "trail",
      "rule" -> "stroke"
      else -> "circle"
    }
  }

  /**
   * The symbol's own colours.
   *
   * The swatch starts as *the mark's own* colour encoding, and upstream then takes away whatever
   * would say the wrong thing (`compile/legend/encode.ts`):
   *
   * - the channel this legend explains, because a colour legend does not repaint its own swatch —
   *   which is how the size legend for a hollow point comes out hollow;
   * - a colour that is a **scaled field**, because the swatch has no datum to scale. A stroke is
   *   simply dropped and a fill becomes black at the mark's opacity. Building the swatch from the
   *   mark's *default* colour instead looks identical whenever the mark has no colour encoding, and
   *   paints a size legend's swatches in the default blue on every chart that does.
   */
  private fun symbolEncode(view: UnitView, channel: String): VegaValue.Obj? {
    // `symbols` in `legend/encode.ts` opens with `markDef.filled && mark !== 'trail'`. A trail is
    // filled as a mark — it is a solid ribbon — but its swatch is a *stroke*, so its legend is read
    // as an unfilled one and needs no fill painted into the symbol at all.
    val filled = view.markDef.filled && view.spec.mark != "trail"
    val colors = Marks.colorEncode(view, filledOverride = filled)

    val fields = LinkedHashMap<String, VegaValue>()
    val fill = colors["fill"]
    if (fill != null && !(channel == "fill" || (filled && channel == "color"))) {
      if (scaled(fill)) {
        fields["fill"] = obj { put("value", "black") }
        fields["fillOpacity"] = obj { put("value", symbolOpacity(view) ?: 1.0) }
      } else {
        fields["fill"] = fill
      }
    }
    val stroke = colors["stroke"]
    if (stroke != null && !(channel == "stroke" || (!filled && channel == "color"))) {
      if (!scaled(stroke)) fields["stroke"] = stroke
    }

    if (channel != "opacity") {
      symbolOpacity(view)?.let { fields["opacity"] = obj { put("value", it) } }
    }

    // A swatch that is filled and not stroked is stroked *transparently*, so that Vega's own legend
    // configuration cannot outline it. Only where the legend is not the stroke's own legend, and
    // only where the fill is a real colour — a hollow point's transparent fill is left alone.
    val painted = fields["fill"]
    if (
      painted != null &&
        painted["value"] != VegaValue.Str("transparent") &&
        !fields.containsKey("stroke") &&
        channel != "stroke"
    ) {
      fields["stroke"] = obj { put("value", "transparent") }
    }

    return if (fields.isEmpty()) null else VegaValue.Obj(fields)
  }

  /** Whether a colour reference reads the data, in which case a swatch cannot resolve it. */
  private fun scaled(ref: VegaValue?): Boolean =
    when (ref) {
      is VegaValue.Obj -> ref.fields.containsKey("field")
      // A conditional colour is a rule array; upstream takes the first condition's own value, and
      // it can only do that when the condition names one rather than a field.
      is VegaValue.Arr -> ref.values.any { scaled(it) }
      else -> false
    }

  /** How solid a swatch is drawn, which is the mark's own opacity where it has one. */
  private fun symbolOpacity(view: UnitView): Double? =
    (view.spec.encoding["opacity"]?.value as? VegaValue.Num)?.value
      ?: view.markDef.number("opacity")
      ?: if (
        view.spec.mark in setOf("point", "tick", "circle", "square") &&
          !Stack.isAggregate(view.spec)
      ) {
        0.7
      } else {
        null
      }
}
