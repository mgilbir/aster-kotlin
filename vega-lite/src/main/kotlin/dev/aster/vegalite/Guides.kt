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

    Fields.title(def, view.config)?.let { title ->
      (title as? VegaValue.Str)?.let { axis.titles += it.value }
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
    // cannot infer the category that went missing.
    if (def.type != MeasureType.NOMINAL && def.type != MeasureType.ORDINAL) {
      axis.set("labelOverlap", bool(true))
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
    val size = if (channel == "x") "width" else "height"
    if (def.bin is Binning.Bin) return signalRef("ceil($size/10)")
    if (def.timeUnit in setOf("month", "hours", "day", "quarter")) return null
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
   * The channel being explained is removed — a colour legend does not repaint its own swatch — and
   * what is left is whatever the mark would have looked like otherwise, which is how the size
   * legend for a hollow point comes out hollow.
   */
  private fun symbolEncode(view: UnitView, channel: String): VegaValue.Obj? {
    val filled = view.markDef.filled
    val markConfig = view.config.markConfig(view.spec.mark)
    val markColor = view.markDef.raw.fields["color"] ?: markConfig.fields["color"]
    val transparent = view.spec.mark in setOf("bar", "point", "circle", "square")

    val fields = LinkedHashMap<String, VegaValue>()
    if (filled) {
      if (channel != "fill" && channel != "color") fields["fill"] = obj { put("value", markColor) }
    } else {
      if (transparent) fields["fill"] = obj { put("value", "transparent") }
      if (channel != "stroke" && channel != "color") {
        markColor?.let { fields["stroke"] = obj { put("value", it) } }
      }
    }

    if (channel != "opacity") {
      val opacity =
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
      if (opacity != null) fields["opacity"] = obj { put("value", opacity) }
    }

    return if (fields.isEmpty()) null else VegaValue.Obj(fields)
  }
}
