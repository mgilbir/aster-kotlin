package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.canonicalNumberString

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

  /**
   * `SCALE_CATEGORY_INDEX`: which scale types can stand for one another.
   *
   * Two views share a channel's scale only when their types belong to the same family — a colour
   * ramp and a set of named colours are both `color`, and neither can be the other.
   */
  private fun category(type: String): String =
    when (type) {
      "linear",
      "log",
      "pow",
      "sqrt",
      "symlog",
      "identity",
      "sequential" -> "numeric"
      "time",
      "utc" -> "time"
      "ordinal" -> "ordinal"
      "bin-ordinal" -> "bin-ordinal"
      "point",
      "band" -> "ordinal-position"
      "quantile",
      "quantize",
      "threshold" -> "discretizing"
      else -> type
    }

  /**
   * `scaleCompatible`: whether two views' scales for one channel can be merged into one.
   *
   * A band and a time scale are the exception that is written out: an ordinal *position* can stand
   * for instants, since a category is a place and so is a date. Anything else has to match family,
   * and where it does not the channel resolves **independently** — which is a layer of counts over
   * a layer of labels ending up with a colour scale each rather than one that is neither.
   */
  fun compatible(first: String, second: String): Boolean {
    val a = category(first)
    val b = category(second)
    return a == b ||
      (a == "ordinal-position" && b == "time") ||
      (b == "ordinal-position" && a == "time")
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
  fun scaleType(
    channel: String,
    def: ChannelDef,
    mark: String,
    /** Whether an offset scale is nested inside this position, which makes it a band. */
    hasOffset: Boolean = false,
  ): String {
    def.scale?.string("type")?.let {
      return it
    }
    return when (def.type) {
      MeasureType.NOMINAL,
      MeasureType.ORDINAL -> {
        if (channel in COLOR_CHANNELS || channel in DISCRETE_RANGE_CHANNELS) return "ordinal"
        // An offset scale divides a band between the marks nested in it, and *how* it divides
        // follows the mark: a bar takes a band of its own inside the group, a point sits at a
        // place in it. The same rule the position channels use, one level in.
        if (channel == "xOffset" || channel == "yOffset") {
          return if (mark in setOf("rect", "bar", "image", "rule", "tick")) "band" else "point"
        }
        if (channelIsPosition(channel)) {
          // A rect, a bar, a rule or a tick occupies a band; anything else is placed at a point,
          // which is why a scatter plot's categories sit on the tick and a bar spans between them.
          if (mark in setOf("rect", "bar", "image", "rule", "tick")) return "band"
          // A position with an **offset** nested in it is a band whatever the mark: the band is
          // the span the nested marks divide between them, and a point scale has no span to
          // divide.
          if (hasOffset) return "band"
        }
        // An **arc** occupies a band on its polar positions for the same reason a bar does on its
        // Cartesian ones: a slice spans an angle, it does not sit at one.
        if (mark == "arc" && channel in setOf("theta", "radius")) return "band"
        "point"
      }
      MeasureType.TEMPORAL ->
        when {
          channel in DISCRETE_RANGE_CHANNELS -> "ordinal"
          // The `utc` sits anywhere in the unit's name — `utcmonth`, `binnedutcyearmonth` — so
          // reading only the prefix left a universally bucketed column on a local scale.
          def.timeUnit?.contains("utc") == true -> "utc"
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
  /** The same definition with one scale property taken off, for a rule that has to ask again. */
  private fun stripped(def: ChannelDef, property: String): ChannelDef {
    val scale = def.scale ?: return def
    val without = VegaValue.Obj(scale.fields.filterKeys { it != property })
    return def.copy(
      raw = VegaValue.Obj(LinkedHashMap(def.raw.fields).also { it["scale"] = without })
    )
  }

  /** Whether a channel's values are *instants*, which is what turns a domain into expressions. */
  private fun measuresTime(def: ChannelDef): Boolean =
    def.type == MeasureType.TEMPORAL || def.timeUnit != null

  /**
   * `valueExpr`: one end of a temporal domain, as the expression that builds it.
   *
   * A stated object is a `datetime()` of its parts; a number or a string is a `datetime()` of
   * itself, epoch milliseconds and a parseable date being the two ways of writing one down.
   */
  private fun instantExpression(value: VegaValue): String =
    when {
      value is VegaValue.Obj && looksLikeADateTime(value) ->
        Transforms(DiagnosticCollector()).dateTimeExpression(value)
      value is VegaValue.Num -> "datetime(${canonicalNumberString(value.value)})"
      value is VegaValue.Str -> "datetime(\"${value.value}\")"
      else -> canonicalNumberString((value as? VegaValue.Num)?.value ?: 0.0)
    }

  fun domain(
    view: UnitView,
    channel: String,
    def: ChannelDef,
    type: String,
    dataName: String,
  ): List<VegaValue> {
    // `{"domain": {"unionWith": [...]}}` widens the domain the data would have given rather than
    // replacing it: the stated values come first and the derived domain follows them, both in the
    // one union. Emitting `unionWith` as a property of its own left Vega with a domain it does not
    // read and the extra values unaccounted for.
    (def.scale?.obj("domain")?.array("unionWith"))?.let { widened ->
      val derived =
        domain(view, channel, stripped(def, "domain"), type, dataName).filterNot { it in widened }
      // The stated values stay **one** entry of the union rather than becoming one each: Vega
      // takes a literal array as a domain in its own right, and splitting it hands the scale two
      // domains of a single value.
      return listOf(arr(widened)) + derived
    }
    def.scale?.fields?.get("domain")?.let { stated ->
      // `{"domain": {"param": "brush"}}` does not *replace* the domain: `parseSelectionDomain`
      // records it as the scale's selection extent and the domain stays whatever the data gives,
      // because the two are assembled to different properties — the computed one to `domain` and
      // the selection to `domainRaw`, which Vega prefers only while the selection holds something.
      if (stated is VegaValue.Obj && stated.has("param")) {
        return domain(view, channel, stripped(def, "domain"), type, dataName)
      }
      // `{"domain": {"expr": …}}` is a **signal**, which is what Vega calls the same thing: a
      // domain computed from the chart's own parameters rather than from its rows. Passed through
      // as written, Vega read an object where it wanted a domain and scaled nothing.
      if (stated is VegaValue.Obj && stated.fields.keys == setOf("expr")) {
        return listOf(signalRef(stated.string("expr").orEmpty()))
      }
      val values = (stated as? VegaValue.Arr)?.values ?: return listOf(stated)
      // `convertDomainIfItIsDateTime`: on a scale that measures **time**, every end of a stated
      // domain becomes the expression that builds the instant, wrapped in `{data: …}` so Vega
      // reads it as a datum of the domain rather than as a signal to be scaled. It is the
      // *channel* that decides — a temporal field, or one cut to a time unit — not whether the
      // value happens to look like a date: a domain of two epoch milliseconds is two instants.
      if (!measuresTime(def)) return listOf(stated)
      return values.map { signalRef("{data: ${instantExpression(it)}}") }
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

    if (def.datum != null) {
      // A datum on a scale that measures **time** joins the domain as the expression that builds
      // the instant, wrapped so Vega reads it as a datum of the domain — the same rule a stated
      // domain follows, and the same one that puts a rule at a year on the time axis beside it.
      val datum = def.datum
      if (measuresTime(def) && datum is VegaValue.Obj && looksLikeADateTime(datum)) {
        return listOf(signalRef("{data: ${instantExpression(datum)}}"))
      }
      return listOf(arr(listOf(datum)))
    }

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
    val secondaryChannel = secondaryChannel(channel)
    val secondary = secondaryChannel?.let { view.spec.fieldDef(it) }
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
    // A ranged position whose far end is a **datum** contributes that constant, not a column: an
    // area drawn down to zero has to cover zero whether or not any row holds it.
    val secondaryDatum = secondaryChannel?.let { view.spec.encoding[it] }?.datum
    if (secondaryDatum != null) {
      return listOf(
        obj {
          put("data", dataName)
          put("field", Fields.vgField(def))
        },
        arr(listOf(secondaryDatum)),
      )
    }

    // A `timeUnit` buckets an instant into a span, and the scale covers the span: the bucket's
    // start and the end the transform computed beside it — but only for a mark that *occupies* the
    // span. Upstream decides that by whether the mark has a `timeUnitBandPosition`, which only the
    // rect-shaped configurations define, so a bar over months reaches the end of December and a
    // point over the same months sits on the first of it.
    if (def.timeUnit != null && (type == "time" || type == "utc") && bandEnd(view, def)) {
      // A rect shifted off the middle of its bucket covers the *interpolated* edges instead, so
      // those are the columns the scale has to reach.
      val shifted = view.offsettedRectPosition(def, channel) != null
      val stem = Fields.vgField(def, forAs = true)
      return listOf(
        obj {
          put("data", dataName)
          put("field", if (shifted) "${stem}_offsetted_rect_start" else Fields.vgField(def))
        },
        obj {
          put("data", dataName)
          put(
            "field",
            if (shifted) "${stem}_offsetted_rect_end" else Fields.vgField(def, suffix = "end"),
          )
        },
      )
    }

    val sort = domainSort(view, channel, def, type)
    // Sorting by an aggregate of some *other* field has to be computed independently of the
    // aggregation being drawn, so upstream reads the pre-aggregation table for it.
    val source = if (sortsFromRawTable(sort)) view.rawData else dataName
    // A binned field forced onto a discrete scale is a domain of *labels*, not of bin starts: the
    // `_range` column the bin wrote is what the axis reads, and it is what has to be listed.
    val binnedLabels =
      def.bin is Binning.Bin && (def.type == MeasureType.ORDINAL || def.type == MeasureType.NOMINAL)
    return listOf(
      obj {
        put("data", source)
        put("field", Fields.vgField(def, suffix = if (binnedLabels) "range" else null))
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
    /** The sort to read instead of the definition's own, once a shorthand has been expanded. */
    override: VegaValue? = null,
  ): VegaValue? {
    if (!hasDiscreteDomain(type)) return null
    // A binned field on a discrete scale is a domain of *labels*, and labels do not sort
    // themselves into numeric order — `"1.0 – 2.0"` sorts before `"9.0 – 10.0"` alphabetically.
    // The bin's own start is what orders them.
    if (
      override == null &&
        def.sort == null &&
        def.bin is Binning.Bin &&
        (def.type == MeasureType.ORDINAL || def.type == MeasureType.NOMINAL)
    ) {
      return obj {
        put("field", Fields.vgField(def))
        put("op", "min")
      }
    }
    return when (val sort = override ?: def.sort) {
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
          // `isSortByChannel`: `"-x"` names *another channel* to sort by, with the minus for
          // descending — the commonest way to write "put the tallest bar first". Read as an
          // unknown string it silently became the default alphabetical order, which is a chart
          // sorted the wrong way rather than one that failed.
          else -> {
            val descending = sort.value.startsWith("-")
            val channelName = sort.value.removePrefix("-")
            if (channelName in Channels.SORT_BY_CHANNELS) {
              domainSort(
                view,
                channel,
                def,
                type,
                obj {
                  put("encoding", channelName)
                  if (descending) put("order", "descending")
                },
              )
            } else {
              bool(true)
            }
          }
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
    def.scale?.fields?.get("range")?.let { stated ->
      // A range read from a **column** — `{"range": {"field": "c"}}` — is a lookup rather than a
      // list: the rows carry the colours, and the scale reads them in the order the domain lists
      // its categories. Which is what the `sort` is for: one row per category, taken by the
      // smallest value of the column the domain is built from.
      if (stated is VegaValue.Obj && stated.has("field") && !stated.has("data")) {
        return obj {
          put("data", view.mainData)
          put("field", stated.string("field"))
          put(
            "sort",
            obj {
              put("op", "min")
              put("field", Fields.vgField(def))
            },
          )
        }
      }
      return stated
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
    // `rangeMin`/`rangeMax` **replace the ends** of whatever range the channel would take, rather
    // than being properties of their own: they are how a radial chart says "start the rings at
    // twenty" without writing out the expression for the other end.
    val ends = listOf(def.scale?.fields?.get("rangeMin"), def.scale?.fields?.get("rangeMax"))
    if (ends.any { it != null }) {
      val derived = defaultRange(view, channel, def, type) as? VegaValue.Arr
      return arr(
        listOf(
          ends[0] ?: derived?.values?.firstOrNull() ?: num(0),
          ends[1] ?: derived?.values?.lastOrNull() ?: num(0),
        )
      )
    }
    return defaultRange(view, channel, def, type)
  }

  private fun defaultRange(
    view: UnitView,
    channel: String,
    def: ChannelDef,
    type: String,
  ): VegaValue? {
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
        val positionScale = view.scaleType(position)
        // `fullWidthOrHeightRange({center: true})`: an offset with **no** position beside it has no
        // band to sit inside, so it spans the whole plot, measured from the middle.
        if (positionScale == null) {
          val size = view.sizeSignal(position)
          return arr(listOf(signalRef("-$size/2"), signalRef("$size/2")))
        }
        // A *continuous* position bucketed by a time unit has a band after all — one bucket wide —
        // and the offset divides that, inset by half the nested padding at each end. The duration
        // is measured through the scale, since a bucket's width in pixels is what is being divided.
        if (!hasDiscreteDomain(positionScale)) {
          val positionDef = view.spec.fieldDef(position)
          val timeUnit = positionDef?.timeUnit
          val duration =
            timeUnit?.let {
              Fields.timeUnitDuration(it) { expr -> "scale('${view.scale(position)}', $expr)" }
            } ?: return null
          val padding = config.scaleConfig("bandWithNestedOffsetPaddingInner") ?: 0.0
          if (padding == 0.0) return arr(listOf(num(0.0), signalRef(duration)))
          return arr(
            listOf(
              signalRef("${canonicalNumberString(padding / 2)} * ($duration)"),
              signalRef("${canonicalNumberString(1 - padding / 2)} * ($duration)"),
            )
          )
        }
        val declared = if (position == "x") view.spec.width else view.spec.height
        // `getDiscretePositionSize`: an undeclared size is *already* a step — the configured one —
        // so the ordinary grouped bar takes that branch, and only a size stated as a **number**
        // leaves the offset nothing to grow by.
        val stated = declared as? VegaValue.Obj
        val step =
          stated?.number("step") ?: (declared as? VegaValue.Num)?.let { null } ?: view.config.step
        // `getStepFor`: a stated step is the **offset's** only where the offset scale is discrete.
        // A continuous one — a jitter over `random()` — has no bands to be one step each, so the
        // step sizes the outer band and the offset fills whatever that came out as.
        val offsetIsDiscrete = hasDiscreteDomain(type)
        val stepFor = if (offsetIsDiscrete) stated?.string("for") ?: "offset" else "position"
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
            // The step signal is named after the *scale*, not the channel: inside a concatenation
            // each plot counts its own categories, so a row of band charts reads
            // `concat_1_x_step` rather than every plot taking the first one's width.
            return obj { put("step", signalRef("${view.scale(channel)}_step")) }
          }
        }
        if (channel == "y" && hasContinuousDomain(type)) {
          arr(signalRef(view.sizeSignal("y")), num(0))
        } else {
          arr(num(0), signalRef(view.sizeSignal(channel)))
        }
      }
      "size" -> {
        val minimum = num(sizeRangeMin(view))
        val maximum = sizeRangeMax(view)
        // `interpolateRange`: a scale that maps a *continuum* onto buckets needs one size per
        // bucket, not two ends to interpolate between — Vega has no way to interpolate a
        // discretizing range itself, so upstream writes the sequence out as an expression.
        if (type in setOf("quantile", "quantize", "threshold")) {
          val count =
            when (type) {
              "quantile" -> config.scaleConfig("quantileCount") ?: 4.0
              "quantize" -> config.scaleConfig("quantizeCount") ?: 4.0
              else -> ((def.scale?.array("domain")?.size ?: 2) + 1).toDouble()
            }
          val low = Fields.expressionNumber(sizeRangeMin(view))
          val high =
            (maximum as? VegaValue.Num)?.let { Fields.expressionNumber(it.value) }
              ?: "(" + (maximum as VegaValue.Obj).string("signal") + ")"
          val step = "($high - $low) / (${Fields.expressionNumber(count)} - 1)"
          signalRef("sequence($low, $high + $step, $step)")
        } else {
          arr(minimum, maximum)
        }
      }
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
          // A scale with a **midpoint** is a diverging one: the reader is being shown which side
          // of a value each datum falls, and a one-ended ramp cannot say that.
          when {
            def.scale?.has("domainMid") == true -> str("diverging")
            view.spec.mark == "rect" || view.spec.mark == "geoshape" -> str("heatmap")
            else -> str("ramp")
          }
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
  /**
   * `sizeRangeMax`: the largest a mark may be drawn, which is a *step* and not a constant.
   *
   * The step is the plot's own — one band's worth — unless a position is **binned**, where a bin's
   * width is not known until the bin transform has chosen its boundaries. Then the step is an
   * expression reading the signal the bin published, and everything computed from it is an
   * expression too: a chart's largest circle cannot be settled at compile time.
   */
  private fun sizeRangeMax(view: UnitView): VegaValue {
    val config = view.config
    val binSteps = listOf("x", "y").map { binStepExpression(view, it) }
    val step =
      if (binSteps.any { it != null }) {
        val parts =
          listOf("width", "height").mapIndexed { index, size ->
            binSteps[index] ?: Fields.expressionNumber(stepFor(view, size))
          }
        "min(${parts.joinToString(", ")})"
      } else {
        null
      }
    val number = minOf(stepFor(view, "width"), stepFor(view, "height"))
    return when (view.spec.mark) {
      "bar",
      "tick" -> if (step == null) num(number - 1) else signalRef("$step - 1")
      "line",
      "trail",
      "rule" -> num(config.scaleConfig("maxStrokeWidth")!!)
      "text" -> num(config.scaleConfig("maxFontSize")!!)
      else ->
        if (step == null)
          num((MAX_SIZE_RANGE_STEP_RATIO * number) * (MAX_SIZE_RANGE_STEP_RATIO * number))
        else signalRef("pow(${Fields.expressionNumber(MAX_SIZE_RANGE_STEP_RATIO)} * $step, 2)")
    }
  }

  /** `getBinStepSignal`: how wide one bin is, in units of the plot, once Vega has binned. */
  private fun binStepExpression(view: UnitView, channel: String): String? {
    val def = view.spec.fieldDef(channel) ?: return null
    val size = view.sizeSignal(channel)
    return when (val bin = def.bin) {
      is Binning.Bin -> {
        val signal = binSignal(view, def)
        "$size / (($signal.stop - $signal.start) / $signal.step)"
      }
      Binning.PreBinned -> {
        val step = def.raw.obj("bin")?.number("step") ?: return null
        val scale = view.scale(channel)
        "$size / ((domain(\"$scale\")[1] - domain(\"$scale\")[0]) / ${Fields.expressionNumber(step)})"
      }
      else -> null
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
    // A stated domain suppresses it only where it is an **array**: `isArray(specifiedDomain)`. A
    // domain that names a selection is not a pair of bounds — it is empty until something is
    // picked — so the scale still rounds the domain the data gave it.
    if (
      def.bin == null &&
        (specifiedDomain == null || (specifiedDomain as? VegaValue.Obj)?.has("param") == true) &&
        channelIsPosition(channel) &&
        type != "time" &&
        type != "utc"
    ) {
      set("nice", bool(true))
    }

    // A **point** offset scale is padded at its ends like any other point scale, and by the same
    // number: `pointPadding` becomes its outer padding, its marks having no width to pad within.
    if ((channel == "xOffset" || channel == "yOffset") && type == "point") {
      set("paddingOuter", num(config.scaleConfig("pointPadding")!!))
    }
    if (channelIsPosition(channel)) {
      // A stated `padding` settles both ends of a band at once and passes through as it stands;
      // the derived inner and outer paddings are for a scale that said nothing, and writing them
      // beside a stated one gives Vega three numbers where the specification gave it one.
      if (type == "point") {
        set("padding", num(config.scaleConfig("pointPadding")!!))
      } else if (type == "band" && def.scale?.has("padding") != true) {
        // A **stated** inner padding is the resolved one, and the outer is half of *that*: the two
        // are one decision, and deriving the outer from the configured inner beside a stated one
        // pads the ends against a gap the bands do not have.
        val inner =
          def.scale?.number("paddingInner")
            // A configured `bandPaddingInner` is the chart's own answer for **every** band scale
            // and beats the per-mark defaults below it — `getFirstDefined(bandPaddingInner, …)`.
            // It does not beat the nested-offset padding, which is about groups rather than marks.
            ?: config.scaleConfig("bandPaddingInner").takeIf { !view.hasNestedOffset(channel) }
            ?: if (view.hasNestedOffset(channel)) {
              // A band holding several bars is padded more generously than one holding a single
              // bar,
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
      // …except a **bound** on a temporal domain, which is an instant like any other end of one
      // and has to be written as the expression that builds it.
      if ((key == "domainMin" || key == "domainMax") && measuresTime(def)) {
        component.properties[key] = signalRef(instantExpression(value))
        return@forEach
      }
      if (key !in setOf("type", "domain", "range", "scheme", "rangeMin", "rangeMax")) {
        // `{"expr": …}` is a signal to Vega, which has no `expr` — a `domainRaw` written that way
        // was read as an object and the scale left at its own domain.
        val expression = (value as? VegaValue.Obj)?.takeIf { it.fields.keys == setOf("expr") }
        component.properties[key] =
          if (expression != null) signalRef(expression.string("expr").orEmpty()) else value
      }
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

  /** The parts a `DateTime` is written from, which is what tells one from an ordinary object. */
  private val DATE_TIME_PARTS =
    setOf(
      "year",
      "quarter",
      "month",
      "date",
      "day",
      "hours",
      "minutes",
      "seconds",
      "milliseconds",
      "utc",
    )

  fun looksLikeADateTime(value: VegaValue.Obj): Boolean =
    value.fields.isNotEmpty() && value.fields.keys.all { it in DATE_TIME_PARTS }

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

    // A **discretizing** size scale does not start at zero: its range is a list of sizes to choose
    // between rather than a span to measure along, and starting it at zero spends the first of them
    // on nothing.
    if (
      channel == "size" &&
        def.type == MeasureType.QUANTITATIVE &&
        type !in setOf("quantile", "quantize", "threshold", "bin-ordinal")
    ) {
      return true
    }

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
  private fun bandEnd(view: UnitView, def: ChannelDef): Boolean =
    def.raw.number("bandPosition") != null ||
      view.config.markConfig(view.spec.mark).fields["timeUnitBandPosition"] != null
}
