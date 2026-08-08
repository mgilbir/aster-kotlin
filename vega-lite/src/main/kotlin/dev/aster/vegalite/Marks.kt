package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * Turns a view's mark and encoding into a Vega mark, port of the files under `compile/mark`.
 *
 * The shape of this is upstream's: a base entry that every mark shares — colour, opacity, the
 * accessibility description — and then per-mark position rules, because where a mark goes is the
 * part that differs. A bar spans a band and stacks; a point sits at a midpoint; a tick is a rect
 * one unit thick across its measure. Getting these wrong produces a chart that is subtly, plausibly
 * wrong rather than obviously broken, which is why each rule below cites the file it came from.
 */
internal object Marks {

  /** The Vega mark type each Vega-Lite mark becomes. */
  private val VG_MARK =
    mapOf(
      "bar" to "rect",
      "rect" to "rect",
      "tick" to "rect",
      "point" to "symbol",
      "circle" to "symbol",
      "square" to "symbol",
      "line" to "line",
      "area" to "area",
      "rule" to "rule",
      "text" to "text",
    )

  /**
   * Marks Vega already names in its own accessibility vocabulary, so no role description is added.
   */
  private val VG_MARK_NAMES =
    setOf(
      "arc",
      "area",
      "image",
      "group",
      "line",
      "path",
      "rect",
      "rule",
      "shape",
      "symbol",
      "text",
      "trail",
    )

  private val PATH_MARKS = setOf("line", "area", "trail")

  fun marks(view: UnitView): List<VegaValue> {
    val mark = markGroup(view)
    // A path mark split by a category is one line per group, which Vega draws by faceting the data
    // into a group mark — without it every series would join into a single zigzag.
    val details = pathGroupingFields(view)
    if (view.spec.mark in PATH_MARKS && details.isNotEmpty()) {
      val facetName = "faceted_path_${view.prefixed("main")}"
      return listOf(
        obj {
          put("name", view.prefixed("pathgroup"))
          put("type", "group")
          put(
            "from",
            obj {
              put(
                "facet",
                obj {
                  put("name", facetName)
                  put("data", view.mainData)
                  put("groupby", strings(details))
                },
              )
            },
          )
          put(
            "encode",
            obj {
              put(
                "update",
                obj {
                  put("width", obj { put("field", obj { put("group", "width") }) })
                  put("height", obj { put("field", obj { put("group", "height") }) })
                },
              )
            },
          )
          put("marks", arr(listOf(withSource(mark, facetName))))
        }
      )
    }
    return listOf(mark)
  }

  private fun withSource(mark: VegaValue.Obj, dataName: String): VegaValue.Obj = obj {
    mark.fields.forEach { (key, value) ->
      if (key == "from") put("from", obj { put("data", dataName) }) else put(key, value)
    }
  }

  /** The fields a path mark is grouped by: every non-position field that is not aggregated. */
  private fun pathGroupingFields(view: UnitView): List<String> =
    view.spec.encoding.entries
      .filter { (channel, def) ->
        channel in
          setOf(
            "color",
            "fill",
            "stroke",
            "opacity",
            "size",
            "shape",
            "detail",
            "strokeDash",
            "order",
          ) && def.isFieldDef && def.aggregate == null
      }
      .mapNotNull { (channel, def) -> if (channel == "order") null else Fields.vgField(def) }
      .distinct()

  private fun markGroup(view: UnitView): VegaValue.Obj {
    val mark = view.spec.mark
    return obj {
      put("name", view.prefixed("marks"))
      put("type", VG_MARK[mark])
      put("style", strings(styles(view)))
      // A line or an area is drawn in the order its points arrive, so the dimension has to be
      // sorted or the path doubles back on itself.
      sortOrder(view)?.let { put("sort", it) }
      put("from", obj { put("data", view.mainData) })
      put("encode", obj { put("update", encodeEntry(view)) })
    }
  }

  private fun styles(view: UnitView): List<String> {
    val declared = view.markDef.raw.fields["style"]
    return when (declared) {
      is VegaValue.Str -> listOf(declared.value)
      is VegaValue.Arr -> declared.values.mapNotNull { (it as? VegaValue.Str)?.value }
      else -> listOf(view.spec.mark)
    }
  }

  private fun sortOrder(view: UnitView): VegaValue? {
    if (view.spec.mark !in PATH_MARKS) return null
    val orient = view.markDef.orient
    val dimension = if (orient == "horizontal") "y" else "x"
    return obj { put("field", dimension) }
  }

  private fun encodeEntry(view: UnitView): VegaValue.Obj {
    val mark = view.spec.mark
    return obj {
      putAll(baseEncode(view))
      when (mark) {
        "bar",
        "rect" -> {
          putAll(rectPosition(view, "x"))
          putAll(rectPosition(view, "y"))
        }
        "point",
        "circle",
        "square" -> {
          putAll(pointPosition(view, "x", "mid", null))
          putAll(pointPosition(view, "y", "mid", null))
          putAll(nonPosition(view, "size", "size"))
          when (mark) {
            "circle" -> put("shape", obj { put("value", "circle") })
            "square" -> put("shape", obj { put("value", "square") })
            else -> putAll(nonPosition(view, "shape", "shape"))
          }
        }
        "line" -> {
          putAll(pointPosition(view, "x", "mid", null))
          putAll(pointPosition(view, "y", "mid", null))
          putAll(nonPosition(view, "size", "strokeWidth"))
          defined(view)?.let { put("defined", it) }
        }
        "area" -> {
          putAll(pointOrRangePosition(view, "x", "zeroOrMin", view.markDef.orient == "horizontal"))
          putAll(pointOrRangePosition(view, "y", "zeroOrMin", view.markDef.orient == "vertical"))
          defined(view)?.let { put("defined", it) }
        }
        "rule" -> {
          val orient = view.markDef.orient
          if (view.spec.encoding["x"] != null || view.spec.encoding["y"] != null) {
            putAll(
              pointOrRangePosition(
                view,
                "x",
                if (orient == "horizontal") "zeroOrMax" else "mid",
                orient != "vertical",
              )
            )
            putAll(
              pointOrRangePosition(
                view,
                "y",
                if (orient == "vertical") "zeroOrMax" else "mid",
                orient != "horizontal",
              )
            )
            putAll(nonPosition(view, "size", "strokeWidth"))
          }
        }
        "tick" -> {
          // A tick is a thin rect: it spans its measure and is one unit thick across it.
          val horizontal = view.markDef.orient == "horizontal"
          val sizeAxis = if (horizontal) "x" else "y"
          val thicknessAxis = if (horizontal) "y" else "x"
          putAll(rectPosition(view, sizeAxis))
          putAll(
            pointPosition(view, thicknessAxis, "mid", if (thicknessAxis == "y") "yc" else "xc")
          )
          val thickness =
            view.markDef.number("thickness") ?: view.config.markConfig("tick").number("thickness")
          put(if (horizontal) "height" else "width", obj { put("value", thickness) })
        }
        "text" -> {
          putAll(pointPosition(view, "x", "mid", null))
          putAll(pointPosition(view, "y", "mid", null))
          textChannel(view)?.let { put("text", it) }
          putAll(nonPosition(view, "size", "fontSize"))
          if (view.markDef.string("align") == null) put("align", obj { put("value", "center") })
          if (view.markDef.string("baseline") == null)
            put("baseline", obj { put("value", "middle") })
        }
      }
    }
  }

  /** `baseEncodeEntry`: the properties every mark shares, in upstream's order. */
  private fun baseEncode(view: UnitView): VegaValue.Obj = obj {
    putAll(markDefProperties(view))
    putAll(color(view))
    putAll(nonPosition(view, "opacity", "opacity"))
    putAll(nonPosition(view, "fillOpacity", "fillOpacity"))
    putAll(nonPosition(view, "strokeOpacity", "strokeOpacity"))
    putAll(nonPosition(view, "strokeWidth", "strokeWidth"))
    putAll(aria(view))
  }

  /**
   * Mark properties written straight through to the Vega encoding.
   *
   * `orient` is passed on only by an area, which uses it to decide which way it is filled;
   * everywhere else it has already done its work in choosing the position rules.
   */
  private fun markDefProperties(view: UnitView): VegaValue.Obj = obj {
    if (view.spec.mark == "area" && view.markDef.orient != null) {
      put("orient", obj { put("value", view.markDef.orient) })
    }
    // The reduced opacity a scatter of unaggregated points is drawn with, so overlaps read.
    if (
      view.spec.mark in setOf("point", "tick", "circle", "square") &&
        !Stack.isAggregate(view.spec) &&
        view.markDef.raw.fields["opacity"] == null &&
        view.spec.encoding["opacity"] == null
    ) {
      put("opacity", obj { put("value", 0.7) })
    }
    for ((key, value) in view.markDef.raw.fields) {
      if (key in VL_ONLY_MARK_PROPERTIES) continue
      put(key, obj { put("value", value) })
    }
  }

  private val VL_ONLY_MARK_PROPERTIES =
    setOf(
      "type",
      "style",
      "clip",
      "filled",
      "orient",
      "color",
      "fill",
      "stroke",
      "size",
      "thickness",
      "binSpacing",
      "continuousBandSize",
      "discreteBandSize",
      "minBandSize",
      "timeUnitBandSize",
      "timeUnitBandPosition",
      "invalid",
      "tooltip",
      "x",
      "y",
      "x2",
      "y2",
      "width",
      "height",
      "aria",
      "description",
    )

  /**
   * `color()`: which of `fill` and `stroke` carries the colour.
   *
   * A filled mark takes its colour in the fill and a hollow one in the stroke, and the *other*
   * channel is set to transparent on a bar or a point so that a hollow point still has a hit area.
   */
  private fun color(view: UnitView): VegaValue.Obj {
    val filled = view.markDef.filled
    val markConfig = view.config.markConfig(view.spec.mark)
    val declaredColor =
      view.markDef.raw.fields["color"] ?: view.markDef.raw.fields[if (filled) "fill" else "stroke"]
    val defaultColor = declaredColor ?: markConfig.fields["color"]

    val transparentIfNeeded =
      if (view.spec.mark in setOf("bar", "point", "circle", "square")) VegaValue.Str("transparent")
      else null

    val defaultFill = if (filled) defaultColor else transparentIfNeeded
    val defaultStroke = if (!filled) defaultColor else null

    return obj {
      if (defaultFill != null) put("fill", obj { put("value", defaultFill) })
      if (defaultStroke != null) put("stroke", obj { put("value", defaultStroke) })
      val colorChannel = if (filled) "fill" else "stroke"
      putAll(nonPosition(view, "color", colorChannel))
      putAll(nonPosition(view, "fill", "fill"))
      putAll(nonPosition(view, "stroke", "stroke"))
    }
  }

  /** A non-position channel: scaled when it has a field, a literal when it has a value. */
  private fun nonPosition(view: UnitView, channel: String, vgChannel: String): VegaValue.Obj {
    val def = view.spec.encoding[channel] ?: return VegaValue.EmptyObject
    val ref =
      when {
        def.isValueDef -> obj { put("value", def.value) }
        def.datum != null ->
          obj {
            put("scale", scaleName(view, channel))
            put("value", def.datum)
          }
        def.isFieldDef ->
          obj {
            put("scale", scaleName(view, channel))
            put("field", Fields.vgField(def))
          }
        else -> return VegaValue.EmptyObject
      }
    return obj { put(vgChannel, ref) }
  }

  private fun scaleName(view: UnitView, channel: String): String? =
    if (view.hasScale(channel)) channel else null

  /**
   * `aria()`: the role description and the spoken summary of a mark.
   *
   * The summary is assembled from every encoded field, formatted the way that field's own guide
   * would format it, which is why it is built here rather than left to the renderer.
   */
  private fun aria(view: UnitView): VegaValue.Obj = obj {
    val mark = view.spec.mark
    if (mark !in VG_MARK_NAMES) put("ariaRoleDescription", obj { put("value", mark) })
    val description = descriptionSignal(view)
    if (description != null) put("description", signalRef(description))
  }

  private fun descriptionSignal(view: UnitView): String? {
    val parts = tooltipData(view)
    if (parts.isEmpty()) return null
    return parts.entries
      .filterNot { it.key.startsWith("_") }
      .mapIndexed { index, (key, value) ->
        "\"${if (index > 0) "; " else ""}$key: \" + ($value)"
      }
      .joinToString(" + ")
  }

  /** Title-to-expression pairs for every encoded field, in specification order. */
  private fun tooltipData(view: UnitView): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for ((channel, def) in view.spec.encoding) {
      if (!def.isFieldDef) continue
      if (channel == "tooltip" || channel == "description") continue
      val title = Fields.title(def, view.config)
      val key = (title as? VegaValue.Str)?.value ?: continue
      if (out.containsKey(key)) continue
      out[key] = fieldExpression(view, def)
    }
    return out
  }

  /**
   * How one field's value reads as text: a number through `format`, a date through `timeFormat`, a
   * category through a validity test that also copes with an array of values.
   */
  private fun fieldExpression(view: UnitView, def: ChannelDef): String {
    val accessor = Fields.datumAccess(def)
    return when {
      def.bin is Binning.Bin -> {
        val end = Fields.datumAccess(def, suffix = "end")
        val format = view.config.numberFormat ?: ""
        "!isValid($accessor) || !isFinite(+$accessor) ? \"null\" : " +
          "format($accessor, \"$format\") + \" $BIN_RANGE_DELIMITER \" + format($end, \"$format\")"
      }
      def.type == MeasureType.TEMPORAL -> {
        val timeUnit = def.timeUnit
        if (timeUnit != null) {
          // A bucketed instant is spoken with the specifier Vega chooses at render time, the same
          // one its axis labels use — so the description and the axis never disagree.
          val utc = timeUnit.startsWith("utc")
          "${if (utc) "utc" else "time"}Format($accessor, ${Fields.timeUnitSpecifier(timeUnit)})"
        } else {
          "timeFormat($accessor, \"${view.config.timeFormat}\")"
        }
      }
      def.type == MeasureType.QUANTITATIVE ->
        "format($accessor, \"${view.config.numberFormat ?: ""}\")"
      else ->
        "isValid($accessor) ? isArray($accessor) ? join($accessor, ' ') : $accessor : \"\"+$accessor"
    }
  }

  /** The en dash upstream puts between a bin's two edges. */
  private const val BIN_RANGE_DELIMITER = "–"

  /** `defined`: what breaks a path, rather than filtering the row out of the data. */
  private fun defined(view: UnitView): VegaValue? {
    val tests =
      listOf("x", "y").mapNotNull { channel ->
        val def = view.spec.fieldDef(channel) ?: return@mapNotNull null
        if (def.type != MeasureType.QUANTITATIVE && def.type != MeasureType.TEMPORAL)
          return@mapNotNull null
        if (def.aggregate == "count") return@mapNotNull null
        val accessor = Fields.datumAccess(def)
        "isValid($accessor) && isFinite(+$accessor)"
      }
    if (tests.isEmpty()) return null
    return signalRef(tests.joinToString(" && "))
  }

  private fun textChannel(view: UnitView): VegaValue? {
    val def = view.spec.encoding["text"] ?: return null
    if (def.isValueDef) return obj { put("value", def.value) }
    if (!def.isFieldDef) return null
    return signalRef(fieldExpression(view, def))
  }

  // ---------------------------------------------------------------------------------------------
  // Position
  // ---------------------------------------------------------------------------------------------

  /** `pointPosition`: one coordinate, at the middle of whatever the field resolves to. */
  private fun pointPosition(
    view: UnitView,
    channel: String,
    defaultPos: String?,
    vgChannel: String?,
  ): VegaValue.Obj {
    val ref = positionRef(view, channel, defaultPos) ?: return VegaValue.EmptyObject
    return obj { put(vgChannel ?: channel, ref) }
  }

  private fun positionRef(view: UnitView, channel: String, defaultPos: String?): VegaValue? {
    val def = view.spec.encoding[channel]
    val stack = view.stack
    val scaleType = view.scaleType(channel)

    if (def != null && stack != null && channel == stack.fieldChannel) {
      // A stacked value is drawn at the accumulated end, so the segments sit on one another.
      return obj {
        put("scale", scaleName(view, channel))
        put("field", Fields.vgField(def, suffix = "end"))
      }
    }

    if (def != null && (def.isFieldDef || def.datum != null || def.isValueDef)) {
      return midPoint(view, channel, def, scaleType)
    }

    return defaultPositionRef(view, channel, defaultPos)
  }

  private fun midPoint(
    view: UnitView,
    channel: String,
    def: ChannelDef,
    scaleType: String?,
  ): VegaValue {
    if (def.isValueDef) {
      val value = def.value
      if (value == VegaValue.Str("width") && (channel == "x" || channel == "x2")) {
        return obj { put("field", obj { put("group", "width") }) }
      }
      if (value == VegaValue.Str("height") && (channel == "y" || channel == "y2")) {
        return obj { put("field", obj { put("group", "height") }) }
      }
      return obj { put("value", value) }
    }
    if (def.datum != null) {
      return obj {
        put("scale", scaleName(view, mainChannel(channel)))
        put("value", def.datum)
      }
    }
    if (def.bin is Binning.Bin && scaleType != null && !Scales.hasDiscreteDomain(scaleType)) {
      // The middle of a bin has to be computed from both edges, since only the edges are fields.
      val start = Fields.datumAccess(def)
      val end = Fields.datumAccess(def, suffix = "end")
      return signalRef("scale(\"${mainChannel(channel)}\", 0.5 * $start + 0.5 * $end)")
    }
    return obj {
      put("scale", scaleName(view, mainChannel(channel)))
      put("field", Fields.vgField(def))
      // On a band scale a point belongs in the middle of its band rather than on its edge.
      if (scaleType == "band") put("band", num(0.5))
    }
  }

  /**
   * `pointPositionDefaultRef`: where a mark goes when the channel says nothing.
   *
   * `zeroOrMin` is the baseline a bar grows from — the scaled zero if the domain contains it, and
   * the near end of the domain if it does not, which is what keeps a bar chart of positive values
   * from starting off the plot.
   */
  private fun defaultPositionRef(view: UnitView, channel: String, defaultPos: String?): VegaValue? {
    val main = mainChannel(channel)
    view.markDef.raw.fields[channel]?.let {
      if (it == VegaValue.Str("width")) return obj { put("field", obj { put("group", "width") }) }
      if (it == VegaValue.Str("height")) return obj { put("field", obj { put("group", "height") }) }
      return obj { put("value", it) }
    }
    return when (defaultPos) {
      "zeroOrMin",
      "zeroOrMax" -> {
        if (!view.hasScale(main)) {
          when (main) {
            "x" -> if (defaultPos == "zeroOrMin") obj { put("value", 0) } else groupField("width")
            "y" -> if (defaultPos == "zeroOrMin") groupField("height") else obj { put("value", 0) }
            else -> null
          }
        } else {
          scaledZeroOrMinOrMax(view, main, defaultPos)
        }
      }
      "mid" -> {
        val size = if (main == "x") "width" else "height"
        obj {
          put("field", obj { put("group", size) })
          put("mult", num(0.5))
        }
      }
      else -> null
    }
  }

  private fun groupField(name: String): VegaValue = obj { put("field", obj { put("group", name) }) }

  private fun scaledZeroOrMinOrMax(view: UnitView, channel: String, mode: String): VegaValue {
    val component = view.scaleComponents[channel]
    val domain = "domain('$channel')"
    return when {
      component?.domainHasZero == true ->
        obj {
          put("scale", channel)
          put("value", 0)
        }
      else ->
        signalRef(
          "scale('$channel', ${if (mode == "zeroOrMin") "$domain[0]" else "peek($domain)"})"
        )
    }
  }

  /** `rangePosition`: a position and its opposite end, for a bar's measure axis. */
  private fun rangePosition(
    view: UnitView,
    channel: String,
    defaultPos: String,
    defaultPos2: String,
  ): VegaValue.Obj {
    val channel2 = secondaryChannel(channel)!!
    val pos2 = position2Ref(view, channel, channel2, defaultPos2)
    return obj {
      putAll(pointPosition(view, channel, defaultPos, null))
      if (pos2 != null) put(channel2, pos2)
    }
  }

  private fun pointOrRangePosition(
    view: UnitView,
    channel: String,
    defaultPos: String,
    range: Boolean,
  ): VegaValue.Obj =
    if (range) rangePosition(view, channel, defaultPos, "zeroOrMin")
    else pointPosition(view, channel, defaultPos, null)

  private fun position2Ref(
    view: UnitView,
    channel: String,
    channel2: String,
    defaultPos2: String,
  ): VegaValue? {
    val stack = view.stack
    val def = view.spec.encoding[channel]
    if (def != null && stack != null && channel == stack.fieldChannel) {
      return obj {
        put("scale", scaleName(view, channel))
        put("field", Fields.vgField(def, suffix = "start"))
      }
    }
    view.spec.encoding[channel2]?.let {
      return midPoint(view, channel2, it, view.scaleType(channel))
    }
    return defaultPositionRef(view, channel, defaultPos2)
  }

  /**
   * `rectPosition`: the rules that give a bar or a rect its extent.
   *
   * Three cases, and which one applies is what decides whether a bar is a band wide, a bin wide, or
   * drawn between two positions.
   */
  private fun rectPosition(view: UnitView, channel: String): VegaValue.Obj {
    val mark = view.spec.mark
    val orient = view.markDef.orient
    val def = view.spec.encoding[channel]
    val channel2 = secondaryChannel(channel)!!
    val def2 = view.spec.encoding[channel2]
    val scaleType = view.scaleType(channel)
    val sizeChannel = if (channel == "x") "width" else "height"

    val isBarOrTickBand =
      (mark == "bar" && (if (channel == "x") orient == "vertical" else orient == "horizontal")) ||
        (mark == "tick" && (if (channel == "y") orient == "vertical" else orient == "horizontal"))

    val hasSize = view.spec.encoding["size"] != null || view.markDef.raw.fields["size"] != null

    if (
      def != null &&
        def.isFieldDef &&
        (def.bin != null || (def.timeUnit != null && def2 == null)) &&
        !hasSize &&
        scaleType != null &&
        !Scales.hasDiscreteDomain(scaleType)
    ) {
      return rectBinPosition(view, channel, def)
    }

    if (
      (def != null && scaleType != null && Scales.hasDiscreteDomain(scaleType) ||
        isBarOrTickBand) && def2 == null
    ) {
      return positionAndSize(view, channel, def, sizeChannel)
    }

    return rangePosition(view, channel, "zeroOrMax", "zeroOrMin")
  }

  /**
   * A rect on a discrete scale: placed at the band and made as wide as the band.
   *
   * `max(0.25, bandwidth(...))` is upstream's, and the floor is the point: a band narrower than a
   * quarter of a unit would otherwise disappear at the same moment the axis still claims it is
   * there.
   */
  private fun positionAndSize(
    view: UnitView,
    channel: String,
    def: ChannelDef?,
    sizeChannel: String,
  ): VegaValue.Obj {
    val scaleType = view.scaleType(channel)
    val markConfig = view.config.markConfig(view.spec.mark)
    val minBandSize = markConfig.number("minBandSize")

    val declaredSize = view.spec.encoding["size"]
    val markSize = view.markDef.raw.fields["size"]

    val useVlSizeChannel =
      view.spec.mark == "tick" ||
        (view.markDef.orient == "horizontal" && channel == "y") ||
        (view.markDef.orient == "vertical" && channel == "x")

    val sizeRef: VegaValue =
      when {
        declaredSize != null && useVlSizeChannel ->
          nonPosition(view, "size", sizeChannel).fields[sizeChannel] ?: VegaValue.EmptyObject
        markSize != null && useVlSizeChannel -> obj { put("value", markSize) }
        scaleType == "band" -> {
          val bandwidth = "bandwidth('$channel')"
          signalRef(if (minBandSize != null) "max($minBandSize, $bandwidth)" else bandwidth)
        }
        else -> {
          val discreteBandSize = markConfig.number("discreteBandSize")
          if (discreteBandSize != null) {
            obj { put("value", discreteBandSize) }
          } else {
            obj { put("value", view.config.step - 2) }
          }
        }
      }

    val centred = scaleType != "band" || (declaredSize != null || markSize != null)
    val vgChannel = if (centred) if (channel == "x") "xc" else "yc" else channel

    val posRef =
      if (def != null) {
        midPointForRect(view, channel, def, scaleType, centred)
      } else {
        defaultPositionRef(view, channel, "mid")
      }

    return obj {
      if (posRef != null) put(vgChannel, posRef)
      put(sizeChannel, sizeRef)
    }
  }

  private fun midPointForRect(
    view: UnitView,
    channel: String,
    def: ChannelDef,
    scaleType: String?,
    centred: Boolean,
  ): VegaValue {
    if (view.stack != null && channel == view.stack.fieldChannel) {
      return obj {
        put("scale", scaleName(view, channel))
        put("field", Fields.vgField(def, suffix = "end"))
      }
    }
    if (!def.isFieldDef && def.datum == null) return midPoint(view, channel, def, scaleType)
    return obj {
      put("scale", scaleName(view, channel))
      put("field", Fields.vgField(def))
      // A bar starts at the band's edge and fills it; a centred mark asks for the middle instead.
      if (scaleType == "band" && centred) put("band", num(0.5))
    }
  }

  /**
   * A rect over a binned field: drawn from one bin edge to the other.
   *
   * The offsets are the fiddly part and they are upstream's exactly. Half a unit of spacing is
   * taken off each side so neighbouring bars do not touch, *unless* the bin is narrower than the
   * mark's minimum width, in which case the offset instead widens the bar back up to that minimum.
   */
  private fun rectBinPosition(view: UnitView, channel: String, def: ChannelDef): VegaValue.Obj {
    val channel2 = secondaryChannel(channel)!!
    val markConfig = view.config.markConfig(view.spec.mark)
    val spacing = view.markDef.number("binSpacing") ?: markConfig.number("binSpacing") ?: 0.0
    val minBandSize = markConfig.number("minBandSize")
    val axisTranslate = 0.5
    val sizeExpression =
      "abs(scale(\"$channel\", ${Fields.datumAccess(def, suffix = "end")}) - " +
        "scale(\"$channel\", ${Fields.datumAccess(def)}))"

    fun offset(isEnd: Boolean): VegaValue {
      val spacingOffset = if (isEnd) -spacing / 2 else spacing / 2
      if (minBandSize == null) return num(axisTranslate + spacingOffset)
      val sign = if (isEnd) "" else "-"
      return signalRef(
        "$axisTranslate + ($sizeExpression < $minBandSize ? " +
          "${sign}0.5 * ($minBandSize - ($sizeExpression)) : $spacingOffset)"
      )
    }

    // `x2` takes the bin's start and `x` its end, which is what makes the rect span the bin.
    val startIsEnd = channel == "x" || channel == "y2"
    return obj {
      put(
        channel2,
        obj {
          put("scale", channel)
          put("field", Fields.vgField(def))
          put("offset", offset(!startIsEnd))
        },
      )
      put(
        channel,
        obj {
          put("scale", channel)
          put("field", Fields.vgField(def, suffix = "end"))
          put("offset", offset(startIsEnd))
        },
      )
    }
  }
}
