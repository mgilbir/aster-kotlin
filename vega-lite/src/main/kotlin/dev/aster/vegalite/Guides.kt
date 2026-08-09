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
    var disabled: Boolean = false

    fun set(name: String, value: VegaValue?) {
      if (value != null && !properties.containsKey(name)) properties[name] = value
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

    axis.set("scale", str(channel))
    axis.set("orient", str(if (channel == "x") "bottom" else "left"))

    // The gridlines belong to *this* scale but are drawn across the other one's extent.
    val grid = !Scales.hasDiscreteDomain(type) && def.isFieldDef && def.bin == null
    if (grid && hasOtherPosition) axis.set("gridScale", str(if (channel == "x") "y" else "x"))
    axis.set("grid", bool(grid))

    // A ranged position is titled by *both* of its fields — `start, end` — because the axis is
    // measuring the span rather than either end of it.
    for (channelDef in
      listOfNotNull(def, secondaryChannel(channel)?.let { view.spec.fieldDef(it) })) {
      val title = Fields.title(channelDef, view.config) as? VegaValue.Str ?: continue
      if (title.value !in axis.titles) axis.titles += title.value
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
    if (def.type != MeasureType.NOMINAL && def.type != MeasureType.ORDINAL) {
      val greedy = type == "log" || type == "symlog"
      axis.set("labelOverlap", if (greedy) str("greedy") else bool(true))
    }

    // Labels for a bucketed instant, and a tick step no finer than the bucket.
    if (def.timeUnit != null) {
      axis.set("format", signalRef(Fields.timeUnitSpecifier(def.timeUnit)))
      Fields.timeUnitDuration(def.timeUnit)?.let { axis.set("tickMinStep", signalRef(it)) }
    }

    tickCount(view, channel, def, type)?.let { axis.set("tickCount", it) }

    // A heatmap's axis is drawn *over* its cells: the rects fill their bands completely, so an axis
    // painted underneath would be hidden by them.
    if (view.spec.mark == "rect" && def.type?.isDiscrete == true) axis.set("zindex", num(1))

    user?.fields?.forEach { (key, value) -> axis.properties[key] = value }
    return axis
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

  /** Splits one component into the gridline axis and the axis proper, in that order. */
  fun assembleAxis(axis: AxisComponent, kind: String): VegaValue? {
    val grid = (axis.properties["grid"] as? VegaValue.Bool)?.value == true
    if (kind == "grid" && !grid) return null

    return obj {
      put("scale", axis.properties["scale"])
      put("orient", axis.properties["orient"])
      val zindex = axis.properties["zindex"] ?: num(0)
      if (kind == "grid") {
        axis.properties.forEach { (key, value) ->
          if (key !in setOf("scale", "orient", "zindex") && key !in MAIN_ONLY) put(key, value)
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
        if (axis.titles.isNotEmpty()) put("title", str(axis.titles.distinct().joinToString(", ")))
        axis.properties.forEach { (key, value) ->
          if (key !in setOf("scale", "orient", "grid", "title", "zindex") && key !in GRID_ONLY) {
            put(key, value)
          }
        }
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
    val scaleChannel =
      when (channel) {
        "color" -> if (filled) "fill" else "stroke"
        else -> channel
      }

    val continuous =
      type == "linear" || type == "log" || type == "pow" || type == "sqrt" || type == "symlog"
    val gradient = channel in setOf("color", "fill", "stroke") && continuous

    return obj {
      put(scaleChannel, channel)
      if (gradient) {
        // A colour ramp is drawn as a bar whose length follows the plot, within Vega's own limits.
        put("gradientLength", signalRef("clamp(height, 64, 200)"))
      } else {
        put("symbolType", defaultSymbolType(view, channel))
      }
      Fields.title(def, view.config)?.let { put("title", it) }
      def.legend?.fields?.forEach { (key, value) -> put(key, value) }
      if (!gradient) {
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
    val filled = view.markDef.filled
    val colors = Marks.colorEncode(view)

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
