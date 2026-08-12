package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * A scale as it is being built: its type, the domains contributed by each view, and its properties.
 *
 * Kept separate from the emitted object because a layered chart merges one channel's scale across
 * its layers — the domains union while the type and properties come from the first view that
 * declared them, which is why a bar and a rule drawn together share one y axis.
 */
internal class ScaleComponent(val channel: String, val type: String, private val name: String) {
  val domains: MutableList<VegaValue> = mutableListOf()
  val properties: LinkedHashMap<String, VegaValue> = LinkedHashMap()

  /**
   * Whether the domain includes zero — `definitely`, `definitely-not`, or `maybe`.
   *
   * Three answers rather than two, because the third is the common one and it is *not* the same as
   * "no": a domain read from a column is not known until the data is. A baseline that must be
   * decided at compile time asks Vega instead, with `inrange(0, domain('y')) ? 0 : domain('y')[0]`,
   * and treating `maybe` as "no" put a bar's baseline at the bottom of the data rather than at the
   * origin whenever the column happened to straddle it.
   */
  var domainHasZero: String = "maybe"

  fun set(name: String, value: VegaValue?) {
    if (value != null) properties[name] = value
  }

  fun name(): String = name
}

internal object Scales {

  private val COLOR_CHANNELS = setOf("color", "fill", "stroke")
  private val DISCRETE_RANGE_CHANNELS = setOf("shape", "strokeDash")

  /**
   * Scale types with a discrete domain, where a band or a point is looked up rather than mapped.
   */
  fun hasDiscreteDomain(type: String): Boolean =
    type == "ordinal" || type == "band" || type == "point" || type == "bin-ordinal"

  /**
   * How capable a scale type is, for deciding which of two a shared channel takes.
   *
   * `SCALE_PRECEDENCE_INDEX` upstream, with its own reasons written in: the ordinal positions rank
   * above the continuous ones "as they support more types of data", and `band` above `point`
   * because it "is better for interaction".
   */
  fun precedence(type: String): Int =
    when (type) {
      "band" -> 11
      "point" -> 10
      "log",
      "pow",
      "sqrt",
      "symlog",
      "identity",
      "sequential" -> 1
      else -> 0
    }

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

    if (def.bin is Binning.Bin) {
      // A `bin-ordinal` scale states no domain: Vega infers one from the `bins` property, and
      // writing a data reference beside it names a column of bin *starts* where the entries are
      // buckets.
      if (type == "bin-ordinal") return emptyList()
      if (!hasDiscreteDomain(type)) {
        val signal = binSignal(view, def)
        return listOf(signalRef("[$signal.start, $signal.stop]"))
      }
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
    // start and the end the transform computed beside it — but only for a mark that *occupies* the
    // span. Upstream decides that by whether the mark has a `timeUnitBandPosition`, which only the
    // rect-shaped configurations define, so a bar over months reaches the end of December and a
    // point over the same months sits on the first of it.
    if (def.timeUnit != null && (type == "time" || type == "utc") && bandEnd(view)) {
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
    val source = if (sortsFromRawTable(sort)) view.rawData else dataName
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
  /**
   * Whether a discrete domain is read from the **pre-aggregation** table.
   *
   * Upstream's test is `isBoolean(sort)` on the *settled* sort, not on what the specification
   * wrote: a plain `true` orders the values as they come and reads the table being drawn, and
   * anything else — an aggregate of another column, or the `{"order": "descending"}` a bare
   * `"descending"` settles into — orders them independently of the aggregation and so has to read
   * the rows themselves. Testing the written form instead misses the string spelling, which is the
   * one a population pyramid uses to run its ages downwards.
   */
  fun sortsFromRawTable(sort: VegaValue?): Boolean =
    sort != null && sort != VegaValue.Null && sort !is VegaValue.Bool

  fun settledSort(view: UnitView, channel: String, def: ChannelDef, type: String): VegaValue? =
    domainSort(view, channel, def, type)

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
      // A written-out order is not a comparator Vega has; the order is turned into a *number* per
      // row by a formula, and the domain then sorts on the smallest number each category carries.
      is VegaValue.Arr ->
        obj {
          put("op", "min")
          put("field", Fields.sortIndexField(channel, def))
          put("order", "ascending")
        }
      is VegaValue.Obj -> {
        // Sorting by another channel's aggregate reads the pre-aggregation table, so the ordering
        // is computed independently of the values being drawn.
        val encoding = sort.string("encoding")
        val field = sort.string("field") ?: encoding?.let { view.spec.fieldDef(it)?.field }
        val op = sort.string("op") ?: encoding?.let { view.spec.fieldDef(it)?.aggregate }
        obj {
          put("op", op ?: defaultSortOp(view, field))
          put("field", field)
          put("order", sort.string("order"))
        }
      }
      else -> bool(true)
    }
  }

  /**
   * The aggregate a sort falls back to: `min`, or `sum` over a **stacked measure**.
   *
   * The distinction is upstream's and it is the right one. Sorting bars by a field that is one of
   * the stack's own dimensions means picking a value each category already has once, so the
   * smallest is the value; sorting them by a field the stack accumulates means asking which column
   * is tallest, and the smallest segment of a column says nothing about that.
   */
  private fun defaultSortOp(view: UnitView, field: String?): String {
    val stack = view.stack ?: return "min"
    val dimensions = stack.groupbyFields + stack.stackBy.mapNotNull { it.field }
    return if (field != null && field !in dimensions) "sum" else "min"
  }

  /** `defaultRange()` in `compile/scale/range.ts`. */
  fun range(view: UnitView, channel: String, def: ChannelDef, type: String): VegaValue? {
    def.scale?.fields?.get("range")?.let {
      return it
    }
    // `parseScheme`: a named colour scheme is a **range**, not a property beside one. Written as a
    // property it sat next to the `"category"` range this would otherwise default to, and Vega read
    // the range — so a chart that asked for `category20` got the ten-colour scheme.
    def.scale?.fields?.get("scheme")?.let { scheme ->
      return when (scheme) {
        is VegaValue.Obj ->
          obj {
            put("scheme", scheme.fields["name"])
            scheme.fields.forEach { (key, value) -> if (key != "name") put(key, value) }
          }
        else -> obj { put("scheme", scheme) }
      }
    }
    val config = view.config
    return when (channel) {
      // An offset scale's range is the *inner* band, and what it spans depends on whether the
      // outer one was given a step to grow by. `getOffsetRange`: a declared `{step}` on the
      // position — unless it says `for: "position"` — makes the step the offset's own, and the
      // outer band is then computed from it; anything else, a fixed width included, means the
      // offset fills whatever band the outer scale ended up with, `[0, bandwidth('x')]`.
      "xOffset",
      "yOffset" -> {
        val position = if (channel == "xOffset") "x" else "y"
        val declared = if (position == "x") view.spec.width else view.spec.height
        // `getDiscretePositionSize`: an undeclared size is *already* a step — the configured one —
        // so the ordinary grouped bar takes that branch, and only a size stated as a **number**
        // leaves the offset nothing to grow by.
        val stated = declared as? VegaValue.Obj
        val step =
          stated?.number("step") ?: (declared as? VegaValue.Num)?.let { null } ?: view.config.step
        val stepFor = stated?.string("for") ?: "offset"
        if (declared is VegaValue.Num || stepFor != "offset") {
          arr(listOf(num(0.0), signalRef("bandwidth('${view.scale(position)}')")))
        } else {
          obj { put("step", num(step)) }
        }
      }
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
      // Five patterns, written out rather than named: Vega has no `range.strokeDash` in its own
      // configuration, so upstream carries the list itself and so must this. Solid first, because
      // the first series should not look dashed.
      "strokeDash" ->
        arr(
          arr(num(1), num(0)),
          arr(num(4), num(2)),
          arr(num(2), num(1)),
          arr(num(1), num(1)),
          arr(num(1), num(2), num(4), num(2)),
        )
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

    // The boundaries the `bin` transform chose, on **any** scale that takes them — a `bin-ordinal`
    // most of all, since that is the only thing it has: its domain is left out entirely and read
    // back from here.
    if (def.bin is Binning.Bin) {
      set("bins", signalRef(binSignal(view, def)))
    } else if (def.bin == Binning.PreBinned) {
      // A column that arrived binned brings no boundary signal with it, but it may say what its
      // **step** was — and that is enough: the ends come from the scale's own domain, so a `step`
      // is the whole of what upstream writes here.
      def.raw.obj("bin")?.number("step")?.let { set("bins", obj { put("step", it) }) }
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

    // A **continuous** domain cannot be sorted — Vega has no such thing — so a `sort: "descending"`
    // on one reverses the *range* instead. A discrete domain sorts itself and needs none of this.
    if (hasContinuousDomain(type) && (def.sort as? VegaValue.Str)?.value == "descending") {
      set("reverse", bool(true))
    }

    zero(view, channel, def, type, specifiedDomain)?.let { set("zero", bool(it)) }

    // Anything else the specification stated on the scale passes through untouched.
    user?.fields?.forEach { (key, value) ->
      if (key !in setOf("type", "domain", "range", "scheme")) component.properties[key] = value
    }
  }

  /**
   * `domainHasZero()` in `scale/component.ts`, once the component is settled.
   *
   * A log scale cannot hold zero and a time scale's zero is an arbitrary instant, so neither ever
   * does. A stated `zero: true` — or the default zero a linear, sqrt or pow scale takes — settles
   * it the other way. Failing both, a domain written out as numbers answers for itself, and one
   * read from a column cannot answer at all.
   */
  fun domainHasZero(component: ScaleComponent): String {
    val type = component.type
    if (type == "log" || type == "time" || type == "utc") return "definitely-not"
    val zero = (component.properties["zero"] as? VegaValue.Bool)?.value
    if (zero == true) return "definitely"
    if (zero == null && type in setOf("linear", "sqrt", "pow")) return "definitely"

    var explicitWithZero = false
    var explicitWithoutZero = false
    var fromField = false
    for (domain in component.domains) {
      val values = (domain as? VegaValue.Arr)?.values
      val first = (values?.firstOrNull() as? VegaValue.Num)?.value
      val last = (values?.lastOrNull() as? VegaValue.Num)?.value
      when {
        first == null || last == null -> fromField = true
        first <= 0.0 && last >= 0.0 -> explicitWithZero = true
        else -> explicitWithoutZero = true
      }
    }
    return when {
      explicitWithZero -> "definitely"
      explicitWithoutZero && !fromField -> "definitely-not"
      else -> "maybe"
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
      // A bar or an area *measures from* zero, and that is not a preference: the length of the
      // mark is the value. Everything else falls through to the theme, which is what lets
      // `config.scale.zero: false` free a gantt chart's ranged bars without freeing these.
      if (mark in setOf("bar", "area") && !hasSecondary) return true
      return view.config.scaleFlag("zero") ?: true
    }
    return false
  }

  /**
   * The signal the `bin` transform publishes its chosen boundaries as.
   *
   * Named through the **view**, exactly as the transform names it. Inside a facet every view is
   * `child_`, so a scale reading the unprefixed name reads a signal that is not there and a binned
   * trellis loses its domain; the two spellings agreed for only as long as the prefix was empty.
   */
  private fun binSignal(view: UnitView, def: ChannelDef): String =
    view.prefixed("${Fields.binToString((def.bin as Binning.Bin).params)}_${def.field}_bins")

  /**
   * Whether this mark occupies the *span* a bucket covers rather than a point in it.
   *
   * Upstream asks for a `timeUnitBandPosition`, which only the rect-shaped mark configurations
   * define (`defaultRectConfig`), so the question answers itself by mark type without a list of
   * mark types anywhere.
   */
  private fun bandEnd(view: UnitView): Boolean =
    view.config.markConfig(view.spec.mark).fields["timeUnitBandPosition"] != null
}
