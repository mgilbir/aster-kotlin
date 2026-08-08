package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * A scale as it is being built: its type, the domains contributed by each view, and its properties.
 *
 * Kept separate from the emitted object because a layered chart merges one channel's scale across
 * its layers — the domains union while the type and properties come from the first view that
 * declared them, which is why a bar and a rule drawn together share one y axis.
 */
internal class ScaleComponent(val channel: String, val type: String) {
  val domains: MutableList<VegaValue> = mutableListOf()
  val properties: LinkedHashMap<String, VegaValue> = LinkedHashMap()

  /** True when the domain is certain to include zero, which is what a baseline needs to know. */
  var domainHasZero: Boolean = false

  fun set(name: String, value: VegaValue?) {
    if (value != null) properties[name] = value
  }

  fun name(): String = channel
}

internal object Scales {

  private val COLOR_CHANNELS = setOf("color", "fill", "stroke")
  private val DISCRETE_RANGE_CHANNELS = setOf("shape", "strokeDash")

  /**
   * Scale types with a discrete domain, where a band or a point is looked up rather than mapped.
   */
  fun hasDiscreteDomain(type: String): Boolean =
    type == "ordinal" || type == "band" || type == "point" || type == "bin-ordinal"

  fun hasContinuousDomain(type: String): Boolean =
    type in
      setOf(
        "linear",
        "log",
        "pow",
        "sqrt",
        "symlog",
        "time",
        "utc",
        "quantile",
        "quantize",
        "threshold",
      )

  /** `scaleType()` in `compile/scale/type.ts`, for the channels this compiler scales. */
  fun scaleType(channel: String, def: ChannelDef, mark: String): String {
    def.scale?.string("type")?.let {
      return it
    }
    return when (def.type) {
      MeasureType.NOMINAL,
      MeasureType.ORDINAL -> {
        if (channel in COLOR_CHANNELS || channel in DISCRETE_RANGE_CHANNELS) return "ordinal"
        // An offset scale is always a band: it is the span the nested marks divide between them.
        if (channel == "xOffset" || channel == "yOffset") return "band"
        if (channelIsPosition(channel)) {
          // A rect, a bar, a rule or a tick occupies a band; anything else is placed at a point,
          // which is why a scatter plot's categories sit on the tick and a bar spans between them.
          if (mark in setOf("rect", "bar", "image", "rule", "tick")) return "band"
        }
        "point"
      }
      MeasureType.TEMPORAL ->
        when {
          channel in DISCRETE_RANGE_CHANNELS -> "ordinal"
          def.timeUnit?.startsWith("utc") == true -> "utc"
          else -> "time"
        }
      MeasureType.QUANTITATIVE ->
        when {
          channel in COLOR_CHANNELS && def.bin is Binning.Bin -> "bin-ordinal"
          channel in DISCRETE_RANGE_CHANNELS -> "ordinal"
          else -> "linear"
        }
      null -> "linear"
    }
  }

  /**
   * The domain a view contributes to a channel's scale.
   *
   * A stacked measure contributes both ends of the stack rather than the raw field, and a binned
   * field contributes the bin signal's extent, which is how the axis lands on bin boundaries
   * instead of on the data's own minimum and maximum.
   */
  fun domain(
    view: UnitView,
    channel: String,
    def: ChannelDef,
    type: String,
    dataName: String,
  ): List<VegaValue> {
    def.scale?.fields?.get("domain")?.let {
      return listOf(it)
    }

    val stack = view.stack
    if (stack != null && channel == stack.fieldChannel) {
      if (stack.offset == "normalize") return listOf(arr(num(0), num(1)))
      return listOf(
        obj {
          put("data", dataName)
          put("field", Fields.vgField(def, suffix = "start"))
        },
        obj {
          put("data", dataName)
          put("field", Fields.vgField(def, suffix = "end"))
        },
      )
    }

    if (def.datum != null) return listOf(arr(listOf(def.datum)))

    if (def.bin is Binning.Bin && !hasDiscreteDomain(type)) {
      val signal = "${Fields.binToString(def.bin.params)}_${def.field}_bins"
      return listOf(signalRef("[$signal.start, $signal.stop]"))
    }

    // A ranged position contributes *both* of its fields: the scale has to cover the whole span,
    // not the ends the first channel happens to name.
    val secondary = secondaryChannel(channel)?.let { view.spec.fieldDef(it) }
    if (secondary != null) {
      return listOf(
        obj {
          put("data", dataName)
          put("field", Fields.vgField(def))
        },
        obj {
          put("data", dataName)
          put("field", Fields.vgField(secondary))
        },
      )
    }

    // A `timeUnit` buckets an instant into a span, and the scale covers the span: the bucket's
    // start
    // and the end the transform computed beside it.
    if (def.timeUnit != null && (type == "time" || type == "utc")) {
      return listOf(
        obj {
          put("data", dataName)
          put("field", Fields.vgField(def))
        },
        obj {
          put("data", dataName)
          put("field", Fields.vgField(def, suffix = "end"))
        },
      )
    }

    val sort = domainSort(view, channel, def, type)
    // Sorting by an aggregate of some *other* field has to be computed independently of the
    // aggregation being drawn, so upstream reads the pre-aggregation table for it.
    val source = if (sort is VegaValue.Obj) view.rawData else dataName
    return listOf(
      obj {
        put("data", source)
        put("field", Fields.vgField(def))
        put("sort", sort)
      }
    )
  }

  /**
   * `true` on a discrete domain, which is Vega's request to sort it naturally.
   *
   * A `sort` of `null` leaves the domain in data order, and a descending sort becomes an explicit
   * minimum-of-field comparator rather than a flag.
   */
  private fun domainSort(
    view: UnitView,
    channel: String,
    def: ChannelDef,
    type: String,
  ): VegaValue? {
    if (!hasDiscreteDomain(type)) return null
    return when (val sort = def.sort) {
      null -> bool(true)
      is VegaValue.Str ->
        when (sort.value) {
          "descending" ->
            obj {
              put("op", "min")
              put("field", Fields.vgField(def))
              put("order", "descending")
            }
          "ascending" -> bool(true)
          else -> bool(true)
        }
      VegaValue.Null -> null
      is VegaValue.Obj -> {
        // Sorting by another channel's aggregate reads the pre-aggregation table, so the ordering
        // is computed independently of the values being drawn.
        val encoding = sort.string("encoding")
        val field = sort.string("field") ?: encoding?.let { view.spec.fieldDef(it)?.field }
        val op = sort.string("op") ?: encoding?.let { view.spec.fieldDef(it)?.aggregate }
        obj {
          put("op", op ?: "min")
          put("field", field)
          put("order", sort.string("order"))
        }
      }
      else -> bool(true)
    }
  }

  /** `defaultRange()` in `compile/scale/range.ts`. */
  fun range(view: UnitView, channel: String, def: ChannelDef, type: String): VegaValue? {
    def.scale?.fields?.get("range")?.let {
      return it
    }
    val config = view.config
    return when (channel) {
      // An offset scale's range is a step per nested mark, stated plainly rather than by signal:
      // it is the *inner* band, and the outer one's step is computed from it.
      "xOffset",
      "yOffset" -> obj { put("step", num(view.config.step)) }
      "x",
      "y" -> {
        if (type == "point" || type == "band") {
          val declared = if (channel == "x") view.spec.width else view.spec.height
          val step = (declared as? VegaValue.Obj)?.number("step")
          if (declared == null || step != null) {
            return obj { put("step", signalRef("${channel}_step")) }
          }
        }
        if (channel == "y" && hasContinuousDomain(type)) {
          arr(signalRef(view.sizeSignal("y")), num(0))
        } else {
          arr(num(0), signalRef(view.sizeSignal(channel)))
        }
      }
      "size" -> arr(num(sizeRangeMin(view)), num(sizeRangeMax(view)))
      "opacity",
      "fillOpacity",
      "strokeOpacity" ->
        arr(num(config.scaleConfig("minOpacity")!!), num(config.scaleConfig("maxOpacity")!!))
      "strokeWidth" ->
        arr(
          num(config.scaleConfig("minStrokeWidth")!!),
          num(config.scaleConfig("maxStrokeWidth")!!),
        )
      // A full turn, and a radius that fits whichever half-extent is smaller.
      "theta" -> arr(num(0), num(2 * kotlin.math.PI))
      "radius" -> arr(num(0), signalRef("min(${view.sizeSignal("x")},${view.sizeSignal("y")})/2"))
      "shape" -> str("symbol")
      "color",
      "fill",
      "stroke" ->
        if (type == "ordinal") {
          // Only a nominal field gets the categorical palette; an ordinal one gets the ordered
          // ramp.
          str(if (def.type == MeasureType.NOMINAL) "category" else "ordinal")
        } else {
          str(if (view.spec.mark == "rect" || view.spec.mark == "geoshape") "heatmap" else "ramp")
        }
      else -> null
    }
  }

  /** The smallest mark a size scale produces, which depends on what the mark is made of. */
  private fun sizeRangeMin(view: UnitView): Double {
    val config = view.config
    return when (view.spec.mark) {
      "bar",
      "tick" -> config.scaleConfig("minBandSize")!!
      "line",
      "trail",
      "rule" -> config.scaleConfig("minStrokeWidth")!!
      "text" -> config.scaleConfig("minFontSize")!!
      else -> config.scaleConfig("minSize")!!
    }
  }

  /**
   * The largest, which is bounded by the step so that neighbouring marks do not collide: a point's
   * size is an *area*, so the step is squared after being scaled down by a twentieth.
   */
  private fun sizeRangeMax(view: UnitView): Double {
    val config = view.config
    val step = minOf(stepFor(view, "width"), stepFor(view, "height"))
    return when (view.spec.mark) {
      "bar",
      "tick" -> step - 1
      "line",
      "trail",
      "rule" -> config.scaleConfig("maxStrokeWidth")!!
      "text" -> config.scaleConfig("maxFontSize")!!
      else -> (MAX_SIZE_RANGE_STEP_RATIO * step) * (MAX_SIZE_RANGE_STEP_RATIO * step)
    }
  }

  private const val MAX_SIZE_RANGE_STEP_RATIO = 0.95

  private fun stepFor(view: UnitView, size: String): Double {
    val declared = if (size == "width") view.spec.width else view.spec.height
    return (declared as? VegaValue.Obj)?.number("step") ?: view.config.step
  }

  /**
   * Whether a scale type takes a property at all — `scaleTypeSupportProperty` upstream.
   *
   * This gate is what keeps a `nice` off a band scale and a `zero` off a time scale. Without it the
   * output grows properties that Vega ignores on some scales and honours on others, which is the
   * worst of both: harmless here, wrong there, and invisible until it is wrong.
   */
  private fun supportsProperty(type: String, property: String): Boolean {
    val continuous = type in setOf("linear", "log", "pow", "sqrt", "symlog", "time", "utc")
    return when (property) {
      "interpolate",
      "scheme" -> type !in setOf("point", "band", "identity")
      "bins" -> type !in setOf("point", "band", "identity", "ordinal")
      "round" -> continuous || type == "band" || type == "point"
      "padding" -> continuous || type in setOf("point", "band")
      "paddingOuter",
      "align" -> type in setOf("point", "band")
      "paddingInner" -> type == "band"
      "nice" -> continuous || type == "quantize" || type == "threshold"
      "zero" ->
        hasContinuousDomain(type) && type !in setOf("log", "time", "utc", "threshold", "quantile")
      else -> true
    }
  }

  /** The property rules from `compile/scale/properties.ts`, in the order upstream writes them. */
  fun properties(
    view: UnitView,
    channel: String,
    def: ChannelDef,
    type: String,
    component: ScaleComponent,
  ) {
    val config = view.config
    val user = def.scale
    val specifiedDomain = user?.fields?.get("domain")

    fun set(name: String, value: VegaValue?) {
      if (supportsProperty(type, name)) component.set(name, value)
    }

    if (def.bin is Binning.Bin && !hasDiscreteDomain(type)) {
      set("bins", signalRef("${Fields.binToString(def.bin.params)}_${def.field}_bins"))
    }

    if (channel in COLOR_CHANNELS && def.type != MeasureType.NOMINAL) {
      set("interpolate", str("hcl"))
    }

    // `nice` rounds a domain outwards to readable bounds, but only where the reader reads bounds:
    // a position axis, with no binning (which already picked its edges), no stated domain, and not
    // a time scale — d3's time ticks already land on calendar boundaries.
    if (
      def.bin == null &&
        specifiedDomain == null &&
        channelIsPosition(channel) &&
        type != "time" &&
        type != "utc"
    ) {
      set("nice", bool(true))
    }

    if (channelIsPosition(channel)) {
      if (type == "point") {
        set("padding", num(config.scaleConfig("pointPadding")!!))
      } else if (type == "band") {
        val inner =
          if (view.hasNestedOffset(channel)) {
            // A band holding several bars is padded more generously than one holding a single bar,
            // because the gap now separates *groups* rather than bars.
            config.scaleConfig("bandWithNestedOffsetPaddingInner")!!
          } else {
            when (view.spec.mark) {
              "bar" -> config.scaleConfig("barBandPaddingInner")!!
              "tick" -> config.scaleConfig("tickBandPaddingInner")!!
              else -> config.scaleConfig("rectBandPaddingInner")!!
            }
          }
        set("paddingInner", num(inner))
        // Half the inner padding, so that a band's step stays a whole number of units — except
        // around a nested group, where upstream pads both sides alike.
        val outer =
          if (view.hasNestedOffset(channel))
            config.scaleConfig("bandWithNestedOffsetPaddingOuter")!!
          else inner / 2
        set("paddingOuter", num(outer))
      } else if (
        view.spec.mark == "bar" &&
          def.bin == null &&
          def.timeUnit == null &&
          ((view.markDef.orient == "vertical" && channel == "x") ||
            (view.markDef.orient == "horizontal" && channel == "y"))
      ) {
        // A bar against a continuous dimension has no band to fill, so its width comes from here.
        config.markConfig("bar").number("continuousBandSize")?.let { set("padding", num(it)) }
      }
    }

    zero(view, channel, def, type, specifiedDomain)?.let { set("zero", bool(it)) }

    // Anything else the specification stated on the scale passes through untouched.
    user?.fields?.forEach { (key, value) ->
      if (key !in setOf("type", "domain", "range")) component.properties[key] = value
    }
  }

  /** `zero()` in `properties.ts`: whether the baseline is forced into the domain. */
  private fun zero(
    view: UnitView,
    channel: String,
    def: ChannelDef,
    type: String,
    specifiedDomain: VegaValue?,
  ): Boolean? {
    if (specifiedDomain != null && specifiedDomain != VegaValue.Str("unaggregated")) {
      if (hasContinuousDomain(type)) {
        val values = (specifiedDomain as? VegaValue.Arr)?.values
        if (values != null && values.size >= 2) {
          val first = (values.first() as? VegaValue.Num)?.value
          val last = (values.last() as? VegaValue.Num)?.value
          if (first != null && first <= 0 && last != null && last >= 0) return true
        }
        return false
      }
    }

    if (channel == "size" && def.type == MeasureType.QUANTITATIVE) return true

    if (def.bin == null && (channelIsPosition(channel) || channelIsPolar(channel))) {
      val mark = view.spec.mark
      val orient = view.markDef.orient
      // The dimension of a bar or a line is not forced through zero — only its measure is.
      if (mark in setOf("bar", "area", "line", "trail")) {
        if (
          (orient == "horizontal" && channel == "y") || (orient == "vertical" && channel == "x")
        ) {
          return false
        }
      }
      val hasSecondary = secondaryChannel(channel)?.let { view.spec.encoding[it] != null } == true
      if (mark in setOf("bar", "area") && !hasSecondary) return true
      return true
    }
    return false
  }
}
