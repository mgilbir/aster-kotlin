package dev.aster.vegalite

import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.canonicalNumberString

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
      "arc" to "arc",
      // A trail is Vega's own mark: a line whose thickness follows the data, so its `size` channel
      // becomes a width along the path rather than an area.
      "trail" to "trail",
    )

  /**
   * The Vega channel a Vega-Lite position channel is written as.
   *
   * A polar position has no channel of its own in Vega: an arc is a *rectangle in polar
   * coordinates*, so its two angles are `startAngle`/`endAngle` and its two radii are
   * `outerRadius`/`innerRadius`. Keeping the mapping in one place is what lets the same rect
   * positioning rules serve both coordinate systems, which is how upstream compiles an arc too.
   */
  private fun vgPositionChannel(channel: String): String =
    when (channel) {
      "theta" -> "startAngle"
      "theta2" -> "endAngle"
      "radius" -> "outerRadius"
      "radius2" -> "innerRadius"
      else -> channel
    }

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
                  // Inside a facet the series are faceted out of the *cell's* rows, not the whole
                  // table's: `markData` is the cell's own partition, and reading the table instead
                  // drew every cell's series in every cell.
                  put("data", view.markData)
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

  /**
   * The fields a path mark is grouped by: every non-position field that is not aggregated.
   *
   * `pathGroupingFields` in `encoding.ts` reads channel by channel, and two of them are not the
   * rule they look like. **`size`** groups a line, because a line has one width and a change of
   * width means a new line; it does not group a *trail*, whose whole point is a width that varies
   * along one path. **`order`** groups an area, because a stacked area needs its slices in a stated
   * order, but not a line or a trail, where it only says which way to walk.
   */
  fun pathGroupingFields(view: UnitView): List<String> {
    val mark = view.spec.mark
    val grouping =
      setOf(
        "color",
        "fill",
        "stroke",
        "opacity",
        "fillOpacity",
        "strokeOpacity",
        "strokeDash",
        "strokeWidth",
        "size",
        "detail",
        "key",
        "order",
      )
    return view.spec.encoding.entries
      .filter { (channel, def) -> channel in grouping && def.isFieldDef && def.aggregate == null }
      .filterNot { (channel, _) ->
        (channel == "size" && mark == "trail") || (channel == "order" && mark != "area")
      }
      .mapNotNull { (_, def) -> Fields.vgField(def) }
      .distinct()
  }

  private fun markGroup(view: UnitView): VegaValue.Obj {
    val mark = view.spec.mark
    return obj {
      put("name", view.prefixed("marks"))
      put("type", VG_MARK[mark])
      // `clip` is a Vega mark property, not a Vega-Lite-only one: it goes straight through, and it
      // is what keeps a line inside its declared domain instead of running off the plot.
      view.markDef.raw.fields["clip"]?.let { put("clip", it) }
      put("style", strings(styles(view)))
      if (view.markDef.raw.fields["aria"] == VegaValue.Bool(false)) {
        put("aria", VegaValue.Bool(false))
      }
      // A line or an area is drawn in the order its points arrive, so the dimension has to be
      // sorted or the path doubles back on itself.
      sortOrder(view)?.let { put("sort", it) }
      put("from", obj { put("data", view.markData) })
      put("encode", obj { put("update", encodeEntry(view)) })
    }
  }

  /**
   * The `style` list a mark carries, which is its **type first** and then whatever it named.
   *
   * `getStyles` upstream is `[].concat(mark.type, mark.style ?? [])`, and the order is what decides
   * which block wins: a later style overrides an earlier one, so a mark that names a style is
   * styled by its own type *and then* by the name — not by the name alone. A composite mark's parts
   * are the case that shows it, each being `["rule", "errorbar-rule"]`.
   */
  private fun styles(view: UnitView): List<String> {
    val declared = view.markDef.raw.fields["style"]
    val named =
      when (declared) {
        is VegaValue.Str -> listOf(declared.value)
        is VegaValue.Arr -> declared.values.mapNotNull { (it as? VegaValue.Str)?.value }
        else -> emptyList()
      }
    return listOf(view.spec.mark) + named
  }

  /**
   * `getSort`: the order a mark's items are drawn in.
   *
   * An **`order` channel** names it outright, and then the sort is by that column rather than by
   * the position — which is the whole of what makes a *connected* scatter plot connected, its line
   * running through the years rather than left to right. A stacked mark is the exception: there
   * `order` says how the segments stack, not how the path runs. Failing both, a path is drawn along
   * its own dimension, or nothing would keep it from doubling back.
   */
  private fun sortOrder(view: UnitView): VegaValue? {
    val order = view.spec.encoding["order"]
    if (order != null && order.isValueDef && order.value == VegaValue.Null) return null
    val ordering = listOfNotNull(order) + order?.siblings.orEmpty()
    if (ordering.any { it.isFieldDef } && view.stack == null) {
      return obj {
        put("field", strings(ordering.map { Fields.datumAccess(it) }))
        put("order", strings(ordering.map { (it.sort as? VegaValue.Str)?.value ?: "ascending" }))
      }
    }
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
        // A trail differs from a line in one property: its `size` is Vega's own `size`, a width
        // that varies point by point, rather than one stroke width for the whole path.
        "trail" -> {
          putAll(pointPosition(view, "x", "mid", null))
          putAll(pointPosition(view, "y", "mid", null))
          putAll(nonPosition(view, "size", "size"))
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
        "arc" -> {
          // An arc is a rect in polar coordinates: its centre comes from the plotting area and its
          // extent from the two polar channel pairs.
          putAll(pointPosition(view, "x", "mid", null))
          putAll(pointPosition(view, "y", "mid", null))
          putAll(rectPosition(view, "radius"))
          putAll(rectPosition(view, "theta"))
        }
        "text" -> {
          putAll(pointPosition(view, "x", "mid", null))
          putAll(pointPosition(view, "y", "mid", null))
          textChannel(view)?.let { put("text", it) }
          putAll(nonPosition(view, "size", "fontSize"))
          // `getMarkPropOrConfig` reads the mark's **styles** as well as the mark: a text mark
          // that names a style whose block sets `align` has already been aligned, and writing the
          // default beside it overrides the very thing the style was for.
          if (styled(view, "align") == null) put("align", obj { put("value", "center") })
          if (styled(view, "baseline") == null) put("baseline", obj { put("value", "middle") })
        }
      }
    }
  }

  /**
   * A mark property as the mark, its style blocks and the configuration between them settle it.
   *
   * The mark's own wins; failing that, the *last* style block that names it, styles being applied
   * in order; failing that, the mark type's own configuration.
   */
  private fun styled(view: UnitView, property: String): VegaValue? {
    view.markDef.raw.fields[property]?.let {
      return it
    }
    for (name in styles(view).reversed()) {
      view.config.style(name)?.fields?.get(property)?.let {
        return it
      }
    }
    return view.config.markConfig(view.spec.mark).fields[property]
  }

  /** `baseEncodeEntry`: the properties every mark shares, in upstream's order. */
  private fun baseEncode(view: UnitView): VegaValue.Obj = obj {
    putAll(markDefProperties(view))
    putAll(colorEncode(view))
    putAll(nonPosition(view, "opacity", "opacity"))
    putAll(nonPosition(view, "fillOpacity", "fillOpacity"))
    putAll(nonPosition(view, "strokeOpacity", "strokeOpacity"))
    putAll(nonPosition(view, "strokeWidth", "strokeWidth"))
    putAll(nonPosition(view, "strokeDash", "strokeDash"))
    tooltipChannel(view)?.let { put("tooltip", it) }
    // `href` is a link the mark carries, written the way a text channel is — and a mark that links
    // somewhere says so with the pointer, since nothing else about it looks clickable.
    hrefChannel(view)?.let { put("href", it) }
    putAll(aria(view))
  }

  /**
   * Mark properties written straight through to the Vega encoding.
   *
   * `orient` is passed on only by an area, which uses it to decide which way it is filled;
   * everywhere else it has already done its work in choosing the position rules.
   */
  private fun markDefProperties(view: UnitView): VegaValue.Obj = obj {
    // A mark that links somewhere shows the pointer, there being nothing else about it that looks
    // clickable — `baseEncodeEntry`'s `cursor` rule, which is about the *encoding* and not a style.
    if (view.spec.encoding["href"] != null && view.markDef.raw.fields["cursor"] == null) {
      put("cursor", obj { put("value", "pointer") })
    }
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

  /** `isRectBasedMark`: the marks whose size along a channel is a *band* rather than a symbol. */
  private val RECT_BASED_MARKS = setOf("rect", "bar", "image", "arc", "tick")

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
      // Consumed by the overlay normalizer before a mark is built. A `point: false` that reached
      // here would be emitted as an encode channel Vega has never heard of.
      "point",
      "line",
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
   * `colorEncode()`: which of `fill` and `stroke` carries the colour.
   *
   * A legend's swatch starts from this too, so it is shared rather than approximated — the swatch
   * has to know whether the mark's colour is a constant or a scaled field, and only this knows.
   *
   * A filled mark takes its colour in the fill and a hollow one in the stroke, and the *other*
   * channel is set to transparent on a bar or a point so that a hollow point still has a hit area.
   */
  fun colorEncode(view: UnitView, filledOverride: Boolean? = null): VegaValue.Obj {
    val filled = filledOverride ?: view.markDef.filled
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
      // The mark's own colour is what a *conditional* colour falls through to — upstream passes it
      // as `defaultValue` into `nonPosition`, so the production rule ends in it. Setting it above
      // and then overwriting the whole property left a rule with no unconditional arm, and every
      // mark the condition did not catch was drawn in Vega's own default rather than the chart's.
      val fallback = if (filled) defaultFill else defaultStroke
      putAll(
        nonPosition(
          view,
          "color",
          colorChannel,
          fallback?.let { obj { put("value", it) } },
        )
      )
      putAll(nonPosition(view, "fill", "fill"))
      putAll(nonPosition(view, "stroke", "stroke"))
    }
  }

  /**
   * A non-position channel: scaled when it has a field, a literal when it has a value.
   *
   * A channel with `condition`s becomes a Vega **production rule** — an array whose entries are
   * tried in order and whose last has no test. Each condition is built by the same reference
   * builder as the unconditional part, because a condition may name a field, a datum or a value
   * just as freely (`wrapCondition`, `compile/mark/encode/conditional.ts`).
   *
   * With no encoding at all the *mark definition* still speaks: `{"type": "rule", "size": 2}` is
   * how a rule is thickened, and `size` is the Vega-Lite name for what Vega calls `strokeWidth`.
   * That renaming is the reason it cannot simply pass through with the mark's other properties —
   * and being Vega-Lite-only, it was dropped on the floor instead.
   */
  private fun nonPosition(
    view: UnitView,
    channel: String,
    vgChannel: String,
    defaultRef: VegaValue? = null,
  ): VegaValue.Obj {
    val def = view.spec.encoding[channel] ?: return markDefault(view, channel, vgChannel)
    val rules =
      def.conditions.mapNotNull { condition ->
        valueRef(view, channel, condition)?.let { ref ->
          obj {
            put("test", condition.test)
            putAll(ref)
          }
        }
      }
    // With conditions but no unconditional part, the *mark* supplies the fallback — a median tick
    // that is white unless its box has no height says only when it is not white, and the white has
    // to come from somewhere for the rule to have anything to fall through to.
    val main =
      valueRef(view, channel, def) ?: markDefault(view, channel, vgChannel)[vgChannel] ?: defaultRef
    // A non-position channel gets the same invalid arm a position does under the `show` mode —
    // `nonposition.ts` asks for one too. A size scaled from a column with nulls in it draws those
    // rows at the scale's own output for an invalid value rather than leaving them unsized.
    val invalid = invalidPositionRef(view, channel)
    if (rules.isEmpty() && invalid == null)
      return if (main == null) VegaValue.EmptyObject else obj { put(vgChannel, main) }
    if (invalid != null) return obj { put(vgChannel, arr(rules + invalid + listOfNotNull(main))) }
    // The array form is used even for a single entry with a test, or Vega has no rule to fall back
    // to when the test fails.
    return obj { put(vgChannel, arr(rules + listOfNotNull(main))) }
  }

  /**
   * What a channel is worth when nothing encodes it — `getMarkPropOrConfig`.
   *
   * The mark's own property wins, under the *Vega* name if it uses one, and the configuration is
   * consulted only where the two names differ. That last condition is upstream's `ignoreVgConfig`
   * and it is what keeps the output concise: `config.point.size` is already the `point` style block
   * Vega applies itself, so restating it here would be the same number written twice.
   */
  private fun markDefault(view: UnitView, channel: String, vgChannel: String): VegaValue.Obj {
    val own = view.markDef.raw.fields[vgChannel] ?: view.markDef.raw.fields[channel]
    val value =
      own
        ?: if (vgChannel != channel) {
          view.config.markConfig(view.spec.mark).fields[vgChannel]
        } else {
          null
        }
    return if (value == null) VegaValue.EmptyObject
    else obj { put(vgChannel, obj { put("value", value) }) }
  }

  /**
   * A literal channel value, or the **signal** an `{"expr": …}` stands for.
   *
   * `{"value": {"expr": "tint"}}` is how a chart reads a parameter into a graphic property, and it
   * is not a literal object: upstream turns the expression into a signal reference in the same
   * value ref, so a datum written that way still passes through its scale. Writing it out as a
   * value paints the mark with an object, which the renderer reads as nothing at all.
   */
  private fun literalRef(value: VegaValue?): Pair<String, VegaValue>? {
    if (value == null) return null
    val expr = (value as? VegaValue.Obj)?.takeIf { it.fields.keys == setOf("expr") }?.get("expr")
    return if (expr != null) "signal" to expr else "value" to value
  }

  /** One entry of a channel's encoding: a value, a datum through the scale, or a scaled field. */
  private fun valueRef(view: UnitView, channel: String, def: ChannelDef): VegaValue.Obj? =
    when {
      def.isValueDef -> obj { literalRef(def.value)?.let { (key, it) -> put(key, it) } }
      def.datum != null ->
        obj {
          put("scale", scaleName(view, channel))
          literalRef(def.datum)?.let { (key, it) -> put(key, it) }
        }
      def.isFieldDef ->
        obj {
          put("scale", scaleName(view, channel))
          put("field", Fields.vgField(def))
        }
      else -> null
    }

  private fun scaleName(view: UnitView, channel: String): String? =
    if (view.hasScale(channel)) view.scale(channel) else null

  /**
   * `aria()`: the role description and the spoken summary of a mark.
   *
   * The summary is assembled from every encoded field, formatted the way that field's own guide
   * would format it, which is why it is built here rather than left to the renderer.
   */
  private fun aria(view: UnitView): VegaValue.Obj = obj {
    // `aria: false` takes the mark out of the accessibility tree, so there is nothing to say about
    // it: no role description and no spoken summary. It is a *mark* property rather than an encode
    // channel, and it is how a composite mark hides its own scaffolding — an error bar's two caps
    // are read as part of the bar, not as three separate objects.
    if (view.markDef.raw.fields["aria"] == VegaValue.Bool(false)) return@obj
    val mark = view.spec.mark
    // A mark may say what it *is* rather than what it is drawn with: a box plot's box is a rect,
    // and calling it a rect to a screen reader is naming the tool instead of the thing.
    val stated = view.markDef.raw.fields["ariaRoleDescription"]
    if (stated != null) put("ariaRoleDescription", obj { put("value", stated) })
    else if (mark !in VG_MARK_NAMES) put("ariaRoleDescription", obj { put("value", mark) })
    val description = descriptionSignal(view)
    if (description != null) put("description", signalRef(description))
  }

  /**
   * `tooltip` — what is shown when the pointer rests on a mark.
   *
   * Three forms, and they produce different things. A **list** of fields becomes an object of
   * title-to-value pairs, which is what a reader sees as a small table. A **single** field becomes
   * that field's own value, formatted the way its guide would format it — except that a discrete
   * one joins an array with *line breaks* rather than spaces, this being a tooltip and not a
   * sentence. And `tooltip: true` on the mark asks for every encoded field, which is the same
   * object the first form builds by hand.
   */
  private fun tooltipChannel(view: UnitView): VegaValue? {
    val def = view.spec.encoding["tooltip"]
    if (def != null && view.spec.encoding["tooltip"]?.raw?.fields?.isEmpty() != true) {
      if (def.isValueDef) return obj { literalRef(def.value)?.let { (key, it) -> put(key, it) } }
      val defs = listOf(def) + def.siblings
      if (defs.size > 1) return tooltipObject(view, defs)
      if (def.isFieldDef) return signalRef(fieldExpression(view, def, separator = "\\n"))
      return null
    }
    // `{"type": "bar", "tooltip": true}` asks for the whole encoding — and so does
    // `config.mark.tooltip`, which is `getMarkPropOrConfig` rather than a look at the mark alone:
    // a theme that turns tooltips on turns them on for every mark in the chart.
    val asked =
      view.markDef.raw.fields["tooltip"]
        ?: view.config.markConfig(view.spec.mark).fields["tooltip"]
        ?: return null
    if (asked == VegaValue.Bool(true)) return tooltipObject(view, null)
    if (asked is VegaValue.Obj && asked.string("content") == "encoding") {
      return tooltipObject(view, null)
    }
    if (asked is VegaValue.Obj && asked.string("content") == "data") return signalRef("datum")
    if (asked is VegaValue.Str) return obj { put("value", asked.value) }
    return null
  }

  /** `{"title": expression, …}` — the object a tooltip of several fields is. */
  private fun tooltipObject(view: UnitView, defs: List<ChannelDef>?): VegaValue? {
    val pairs =
      if (defs == null) {
        tooltipData(view, separator = "\\n")
      } else {
        val out = LinkedHashMap<String, String>()
        for (def in defs) {
          if (!def.isFieldDef) continue
          val title =
            def.explicitTitle?.takeIf { it != VegaValue.Str("") }
              ?: Fields.defaultTitle(def, view.config)?.let(VegaValue::Str)
          val key = (title as? VegaValue.Str)?.value ?: continue
          if (out.containsKey(key)) continue
          out[key] = fieldExpression(view, def, separator = "\\n")
        }
        out
      }
    if (pairs.isEmpty()) return null
    return signalRef(pairs.entries.joinToString(", ", "{", "}") { "\"${it.key}\": ${it.value}" })
  }

  private fun descriptionSignal(view: UnitView): String? {
    val parts = tooltipData(view)
    if (parts.isEmpty()) return null
    return parts.entries
      .filterNot { it.key.startsWith("_") }
      .mapIndexed { index, (key, value) ->
        // The title goes *inside* a JSON string in an expression, so a quotation mark in it has to
        // be escaped or the expression ends early and the rest is a syntax error.
        "\"${if (index > 0) "; " else ""}${key.replace("\"", "\\\"")}: \" + ($value)"
      }
      .joinToString(" + ")
  }

  /** Title-to-expression pairs for every encoded field, in specification order. */
  private fun tooltipData(view: UnitView, separator: String = " "): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    val skipped =
      view.spec.encoding.entries
        .mapNotNull { (channel, def) ->
          if (def.bin == Binning.PreBinned) secondaryChannel(channel) else null
        }
        .filter { view.spec.encoding[it]?.isFieldDef == true }
        .toSet()
    for ((channel, first) in view.spec.encoding) {
      if (channel == "description") continue
      // Every entry of a channel written as a list, not only the first: a tooltip naming three
      // fields describes three, and the mark's spoken description names the same three.
      for (def in listOf(first) + first.siblings) {
        if (!def.isFieldDef) continue
        // A pre-binned column is announced as the *span* it covers, and the channel naming the far
        // edge is then not announced separately — upstream's `toSkip`. Without it a bar over a bin
        // reads "lo: 0; n: 4; hi: 10", which names three things where there are two.
        if (channel in skipped) continue
        // The *field's* own title, not its guide's: upstream reads `fieldDef.title ||
        // defaultTitle(fieldDef)` here, so hiding an axis title with `axis: {title: null}` restyles
        // the chart and leaves what a screen reader says about it alone. Reading the guide's title
        // dropped the channel from the description entirely.
        // A `title: null` hides the *guide's* caption; it does not take the field out of what a
        // screen reader says, so the description falls back to the field's own name.
        // `fieldDef.title || defaultTitle(...)` is an `||`, so an **empty** title falls through to
        // the field's own name rather than announcing a channel with no name at all.
        val title =
          def.explicitTitle?.takeIf { it != VegaValue.Null && it != VegaValue.Str("") }
            ?: Fields.defaultTitle(def, view.config)?.let(VegaValue::Str)
        val key = (title as? VegaValue.Str)?.value ?: continue
        if (out.containsKey(key)) continue
        // A **normalized** stack is announced as the share it takes, not the number behind it: the
        // position channel carrying the stack reads `end - start`, which is the fraction, and takes
        // `config.normalizedNumberFormat` — a percentage. Reading the raw field there says 3 where
        // the bar plainly shows three quarters.
        val stack = view.stack
        val normalized =
          stack != null &&
            stack.offset == "normalize" &&
            channel == stack.fieldChannel &&
            channel in NORMALIZABLE_CHANNELS
        out[key] =
          fieldExpression(
            view,
            def,
            normalized,
            binEnd =
              when (def.bin) {
                is Binning.Bin -> Fields.datumAccess(def, suffix = "end")
                // A pre-binned column's far edge is the secondary channel's own field.
                Binning.PreBinned ->
                  secondaryChannel(channel)
                    ?.let { view.spec.encoding[it] }
                    ?.takeIf { it.isFieldDef }
                    ?.let { Fields.datumAccess(it) }
                else -> null
              },
            separator = separator,
          )
      }
    }
    return out
  }

  /** The position channels a stack can accumulate along, and so the ones a share is read from. */
  private val NORMALIZABLE_CHANNELS = setOf("x", "y", "theta", "radius")

  /**
   * How one field's value reads as text: a number through `format`, a date through `timeFormat`, a
   * category through a validity test that also copes with an array of values.
   *
   * A `format` the definition states itself beats the configured default, which is `numberFormat` —
   * or `normalizedNumberFormat` where the value being read is a *share* of a normalized stack
   * rather than a quantity.
   */
  private fun fieldExpression(
    view: UnitView,
    def: ChannelDef,
    normalizeStack: Boolean = false,
    /** Where the far edge of a bin is read from, when this definition is a binned one. */
    binEnd: String? = null,
    /**
     * What an array of values is joined with.
     *
     * A **tooltip** joins with line breaks, being a small table; a spoken description joins with
     * spaces, being a sentence. Upstream builds one and rewrites it into the other, which comes to
     * the same thing said once.
     */
    separator: String = " ",
    /**
     * Whether an array value is joined rather than printed.
     *
     * A **tooltip** and a spoken description join one, being a list of things; a mark's own `text`
     * does not — `formatSignalRef` has no array branch at all, so a text mark drawn from an array
     * column prints what Vega prints for one. Sharing the tooltip's expression here put a `join`
     * into every text mark in the gallery.
     */
    arrays: Boolean = true,
  ): String {
    val stated = (def.format as? VegaValue.Str)?.value
    val accessor =
      if (normalizeStack) {
        "${Fields.datumAccess(def, suffix = "end")}-${Fields.datumAccess(def, suffix = "start")}"
      } else {
        Fields.datumAccess(def)
      }
    val number =
      stated
        ?: if (normalizeStack) view.config.normalizedNumberFormat
        else view.config.numberFormat ?: ""
    return when {
      // `isFieldOrDatumDefForTimeFormat` in `channeldef.ts`: an instant is one a *time unit*
      // buckets
      // as much as one typed temporal. A month named on an ordinal scale is still a month, and
      // upstream speaks it as a date; reading only the type printed the bucket's raw number.
      // A binned field the specification forced onto a **discrete** scale is spoken as the plain
      // column it came from: there is no numeric axis left, so upstream reads it as a category
      // rather than as the range its label spells out.
      def.bin is Binning.Bin &&
        (def.type == MeasureType.ORDINAL || def.type == MeasureType.NOMINAL) -> {
        val plain = "datum[${quoted(def.field.orEmpty())}]"
        "isValid($plain) ? isArray($plain) ? join($plain, '$separator') : $plain : \"\"+$plain"
      }
      def.type == MeasureType.TEMPORAL || def.timeUnit != null -> {
        val timeUnit = def.timeUnit
        // `normalizeTimeUnit` reads the `utc` out of the unit's name wherever it sits, so
        // `binnedutcyearmonth` is universal time as much as `utcmonth` is.
        val utc = timeUnit?.contains("utc") == true || def.scale.string("type") == "utc"
        val prefix = if (utc) "utc" else "time"
        when {
          // `timeFormatExpression`: a stated format wins over the unit, because the reader asked
          // for it by name — on the guide for a positioned field, on the definition for a text one.
          stated != null -> "${prefix}Format($accessor, \"$stated\")"
          // A bucketed instant is otherwise spoken with the specifier Vega chooses at render time,
          // the same one its axis labels use — so the description and the axis never disagree.
          timeUnit != null -> "${prefix}Format($accessor, ${Fields.timeUnitSpecifier(timeUnit)})"
          else -> "${prefix}Format($accessor, \"${view.config.timeFormat}\")"
        }
      }
      def.bin != null && binEnd != null -> {
        "!isValid($accessor) || !isFinite(+$accessor) ? \"null\" : " +
          "format($accessor, \"$number\") + \" $BIN_RANGE_DELIMITER \" + format($binEnd, \"$number\")"
      }
      def.type == MeasureType.QUANTITATIVE || stated != null -> "format($accessor, \"$number\")"
      !arrays -> "isValid($accessor) ? $accessor : \"\"+$accessor"
      else ->
        "isValid($accessor) ? isArray($accessor) ? join($accessor, '$separator') : $accessor : " +
          "\"\"+$accessor"
    }
  }

  /** The en dash upstream puts between a bin's two edges. */
  private const val BIN_RANGE_DELIMITER = "–"

  /**
   * `defined`: what breaks a path, rather than filtering the row out of the data.
   *
   * `defined.ts` asks the question once per *scaled* channel, not once for x and y: a line coloured
   * by a continuous field breaks where that colour is missing too. A channel answers "always valid"
   * when its scale has a discrete domain — a null is simply another category — or when it counts,
   * because a count has no missing answer.
   */
  private fun defined(view: UnitView): VegaValue? {
    if (!shouldBreakPath(view)) return null
    // `binSuffix: 'mid'` under an imputed stack: the imputation writes the bin's midpoint, and it
    // is that column the path is drawn from.
    val imputed = view.stack?.impute == true
    val fields = LinkedHashSet<String>()
    for ((channel, def) in view.spec.encoding) {
      if (!def.isFieldDef) continue
      val scaleType = view.scaleType(channel) ?: continue
      if (!Scales.hasContinuousDomain(scaleType)) continue
      if (def.aggregate in COUNTING_OPS) continue
      if (view.config.scaleInvalid(channel) != null) continue
      // A bin suffix names a *bin's* column, so it only reaches a binned field: `vgField` ignores
      // it otherwise, and appending it here invented a `value_mid` no transform ever wrote.
      fields += Fields.datumAccess(def, suffix = if (imputed && def.bin != null) "mid" else null)
    }
    if (fields.isEmpty()) return null
    return signalRef(fields.joinToString(" && ") { "isValid($it) && isFinite(+$it)" })
  }

  /**
   * `normalizeInvalidDataMode` in `compile/invalid`: whether an invalid value breaks the path or is
   * dealt with in the data flow.
   *
   * The default — `break-paths-show-path-domains` — reads as "break the path" for a path mark and
   * "filter the row out" for everything else, which is why only a line, an area and a trail ask.
   * `filter` and `show` both answer no: one has already removed the row, the other draws it.
   */
  private fun shouldBreakPath(view: UnitView): Boolean =
    view.invalidDataMode == "break-paths-filter-domains" ||
      view.invalidDataMode == "break-paths-show-domains"

  /** `href`: where a mark links to, read as text and never joined. */
  private fun hrefChannel(view: UnitView): VegaValue? {
    val def = view.spec.encoding["href"] ?: return null
    if (def.isValueDef) return obj { literalRef(def.value)?.let { (key, it) -> put(key, it) } }
    if (!def.isFieldDef) return null
    return signalRef(fieldExpression(view, def, arrays = false))
  }

  private fun textChannel(view: UnitView): VegaValue? {
    val def = view.spec.encoding["text"] ?: return null
    if (def.isValueDef) return obj { literalRef(def.value)?.let { (key, it) -> put(key, it) } }
    if (!def.isFieldDef) return null
    return signalRef(fieldExpression(view, def, arrays = false))
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
    val invalid = invalidPositionRef(view, channel)
    return obj {
      put(
        vgChannel ?: vgPositionChannel(channel),
        if (invalid == null) ref else arr(listOf(invalid, ref)),
      )
    }
  }

  /**
   * Where a value the scale cannot place is drawn, under the `show` mode.
   *
   * `getConditionalValueRefForIncludingInvalidValue`: the channel gets a *production rule* whose
   * first arm tests for the invalid value and answers with the scale's own output for one —
   * `config.scale.invalid` where a specification named it, and otherwise the same zero-or-minimum a
   * bar measures from. Every other mode has already dealt with the row, by dropping it or by
   * breaking the path at it, so this is the only one that reaches the encoding.
   */
  private fun invalidPositionRef(view: UnitView, channel: String): VegaValue? {
    if (view.invalidDataMode != "show") return null
    if (channel !in Channels.SCALE_CHANNELS) return null
    val main = mainChannel(channel)
    // A **stacked** position is not read from the row at all: it is the accumulated end the stack
    // transform wrote, which is a number whatever the row held. Only the refs that go through
    // `midPointRefWithPositionInvalidTest` get the test, and a stacked one does not.
    if (view.stack != null && main == view.stack.fieldChannel) return null
    val def = view.spec.fieldDef(main) ?: return null
    val scaleType = view.scaleType(main) ?: return null
    if (!Scales.hasContinuousDomain(scaleType)) return null
    if (def.aggregate in COUNTING_OPS) return null
    val accessor = Fields.datumAccess(def)
    val stated = view.config.scaleInvalid(main)
    val output =
      when {
        stated is VegaValue.Obj && stated.has("value") ->
          obj { literalRef(stated.fields["value"])?.let { (key, it) -> put(key, it) } }
        else -> scaledZeroOrMinOrMax(view, main, "zeroOrMin")
      }
    return obj {
      put("test", "!isValid($accessor) || !isFinite(+$accessor)")
      (output as? VegaValue.Obj)?.fields?.forEach { (key, value) -> put(key, value) }
    }
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
      return obj { literalRef(value)?.let { (key, it) -> put(key, it) } }
    }
    if (def.datum != null) {
      return obj {
        put("scale", scaleName(view, mainChannel(channel)))
        literalRef(def.datum)?.let { (key, it) -> put(key, it) }
      }
    }
    if (def.bin is Binning.Bin && scaleType != null && !Scales.hasDiscreteDomain(scaleType)) {
      // The middle of a bin has to be computed from both edges, since only the edges are fields.
      val start = Fields.datumAccess(def)
      val end = Fields.datumAccess(def, suffix = "end")
      return signalRef("scale(\"${view.scale(mainChannel(channel))}\", 0.5 * $start + 0.5 * $end)")
    }
    return obj {
      put("scale", scaleName(view, mainChannel(channel)))
      // A binned field on a discrete scale is placed by its **label**: that is what the domain
      // lists, so the bin's start would name a category the scale has never heard of.
      val binnedLabels =
        def.bin is Binning.Bin &&
          (def.type == MeasureType.ORDINAL || def.type == MeasureType.NOMINAL)
      put("field", Fields.vgField(def, suffix = if (binnedLabels) "range" else null))
      // `positionOffset` runs for every position, not only a rect's: a label over a grouped bar
      // has to move into the same lane the bar did, or it sits over the middle of the group.
      val offset = offsetRef(view, mainChannel(channel), centred = true)
      // On a band scale a point belongs in the middle of its band rather than on its edge — unless
      // an offset has already put it in the middle of a *lane* inside that band, where centring
      // twice would move it half a group to the right. `bandPosition` is 0 when the offset comes
      // from an encoding.
      if (scaleType == "band" && offset == null) put("band", num(0.5))
      offset?.let { put("offset", it) }
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
            // A radius runs from the centre to whichever half-extent fits, and an angle from the
            // twelve o'clock position all the way round.
            "radius" ->
              if (defaultPos == "zeroOrMin") obj { put("value", 0) }
              else signalRef("min(${view.sizeSignal("x")},${view.sizeSignal("y")})/2")
            "theta" -> if (defaultPos == "zeroOrMin") obj { put("value", 0) } else signalRef("2*PI")
            else -> null
          }
        } else {
          scaledZeroOrMinOrMax(view, main, defaultPos)
        }
      }
      "mid" -> {
        // The size signal halved — not the enclosing group's width, which is the same number here
        // and a different one inside a facet.
        val size = view.sizeSignal(main)
        obj {
          put("signal", size)
          put("mult", num(0.5))
        }
      }
      else -> null
    }
  }

  private fun groupField(name: String): VegaValue = obj { put("field", obj { put("group", name) }) }

  private fun scaledZeroOrMinOrMax(view: UnitView, channel: String, mode: String): VegaValue {
    val component = view.scaleComponents[channel]
    val scale = view.scale(channel)
    val domain = "domain('$scale')"
    val other = if (mode == "zeroOrMin") "$domain[0]" else "peek($domain)"
    return when (component?.domainHasZero ?: "maybe") {
      "definitely" ->
        obj {
          put("scale", scale)
          put("value", 0)
        }
      // Not knowable here, so the question is passed to Vega, which has the data.
      "maybe" -> signalRef("scale('$scale', inrange(0, $domain) ? 0 : $other)")
      else -> signalRef("scale('$scale', $other)")
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
      if (pos2 != null) put(vgPositionChannel(channel2), pos2)
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
    // The mark's own property, named the way *Vega* names the channel: a donut states its hole as
    // `innerRadius`, which is `radius2` here and has no Vega-Lite name of its own.
    view.markDef.raw.fields[vgPositionChannel(channel2)]?.let {
      return obj { put("value", it) }
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
    // Only a Cartesian position has a size channel; a polar one is bounded by its second angle or
    // radius instead, so it always takes the ranged path below.
    val sizeChannel = if (channel == "x") "width" else if (channel == "y") "height" else null

    val isBarOrTickBand =
      (mark == "bar" && (if (channel == "x") orient == "vertical" else orient == "horizontal")) ||
        (mark == "tick" && (if (channel == "y") orient == "vertical" else orient == "horizontal"))

    val hasSize = view.spec.encoding["size"] != null || view.markDef.raw.fields["size"] != null

    if (
      def != null &&
        def.isFieldDef &&
        (def.bin != null || (def.timeUnit != null && def2 == null)) &&
        !hasSize &&
        // An **offset** encoding takes the rect off the bucket's edges and puts it in a lane of
        // its own inside them, so the bucket is no longer what the rect spans and the positioning
        // is the ordinary banded one — `!encoding[offsetScaleChannel]` in `rectPosition`.
        !view.hasNestedOffset(channel) &&
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
    /**
     * The Vega channel the mark's *extent* is written on, or null for a polar position.
     *
     * Vega has no `thetaWidth`, so a slice's extent is simulated: the second angle is the first
     * plus the size, written as an `offset` on the same reference. `positionAndSize` ends in that
     * branch, and it is what makes an arc over categories span its band instead of sitting on it.
     */
    sizeChannel: String?,
  ): VegaValue.Obj {
    val scaleType = view.scaleType(channel)
    // `(scale || offsetScale)?.get('type')`: where a channel has an offset scale nested in it, the
    // band the mark fills is the *offset's*, not the position's. A grouped bar over a continuous
    // axis has no band of its own and would otherwise be measured as a lone continuous rect.
    val offsetChannel = offsetChannelFor(channel)?.takeIf { view.hasScale(it) }
    val bandingType = scaleType ?: offsetChannel?.let { view.scaleType(it) }
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
        declaredSize != null && useVlSizeChannel && sizeChannel != null ->
          nonPosition(view, "size", sizeChannel).fields[sizeChannel] ?: VegaValue.EmptyObject
        markSize != null && useVlSizeChannel -> obj { put("value", markSize) }
        offsetChannel != null || bandingType == "band" -> {
          // The width of one *nested* mark where there is an offset scale, and of the whole band
          // where there is not.
          val band = offsetChannel ?: channel
          val bandwidth = "bandwidth('${view.scale(band)}')"
          signalRef(
            if (minBandSize != null) "max(${canonicalNumberString(minBandSize)}, $bandwidth)"
            else bandwidth
          )
        }
        // A rect-based mark on a **continuous** scale is `continuousBandSize` wide — five units for
        // a bar — not a step less two. `getBandSize` asks the scale's kind first and only reaches
        // `discreteBandSize` where the domain is discrete, so a bar against a quantitative axis had
        // been coming out nearly four times too wide.
        scaleType != null &&
          !Scales.hasDiscreteDomain(scaleType) &&
          view.spec.mark in RECT_BASED_MARKS &&
          markConfig.number("continuousBandSize") != null ->
          obj { put("value", markConfig.number("continuousBandSize")) }
        else -> {
          val discreteBandSize = markConfig.number("discreteBandSize")
          when {
            discreteBandSize != null -> obj { put("value", discreteBandSize) }
            // A rect-based mark with *nothing* encoded on this channel spans the plot rather than
            // sitting somewhere in it at a default width — `defaultSizeRef`'s `!hasFieldDef`
            // branch. It keeps back exactly what a band scale's inner padding would have kept
            // back, so a lone row of ticks is as thick as one row of a trellis of them.
            def == null -> {
              val padding =
                view.config.scaleConfig(
                  when (view.spec.mark) {
                    "bar" -> "barBandPaddingInner"
                    "tick" -> "tickBandPaddingInner"
                    else -> "rectBandPaddingInner"
                  }
                )!!
              // The *plain* `width` or `height`, not this plot's own name for it: a plot with
              // nothing on the other channel has no gridline scale, so its group already defines
              // the plain name as an alias — `assembleAxisSignals` — and upstream writes the
              // expression against that.
              signalRef("${canonicalNumberString(1 - padding)} * $sizeChannel")
            }
            else -> obj { put("value", view.config.step - 2) }
          }
        }
      }

    // `defaultBandAlign`: a rect filling a *relative* band starts at the band's leading edge; one
    // given a size of its own is centred in it. The band in question may be the offset's.
    val centred = bandingType != "band" || (declaredSize != null || markSize != null)
    val vgChannel = if (centred) if (channel == "x") "xc" else "yc" else channel

    val posRef =
      if (def != null) {
        // A rect's position takes the invalid arm too — `midPointRefWithPositionInvalidTest` is
        // what builds it, whichever shape the position takes.
        val main = midPointForRect(view, channel, def, scaleType, centred)
        invalidPositionRef(view, channel)?.let { arr(listOf(it, main)) } ?: main
      } else {
        // A default position takes the offset too — `positionOffset` runs whether or not the
        // channel has a definition, so a group of bars with no shared category still fans out
        // across the plot instead of stacking in its middle.
        defaultPositionRef(view, channel, "mid")?.let { base ->
          val offset = offsetRef(view, channel, centred) ?: return@let base
          obj {
            (base as? VegaValue.Obj)?.fields?.forEach { (key, value) -> put(key, value) }
            put("offset", offset)
          }
        }
      }

    if (sizeChannel != null) {
      return obj {
        if (posRef != null) put(vgChannel, posRef)
        put(sizeChannel, sizeRef)
      }
    }
    // A polar position has no size channel: the far end is the near one plus the extent.
    val extent = (sizeRef as? VegaValue.Obj)?.fields?.get("signal") ?: sizeRef
    return obj {
      if (posRef != null) put(vgPositionChannel(channel), posRef)
      if (posRef is VegaValue.Obj) {
        put(
          vgPositionChannel(secondaryChannel(channel)!!),
          obj {
            posRef.fields.forEach { (key, value) -> put(key, value) }
            put("offset", if (extent is VegaValue.Str) signalRef(extent.value) else sizeRef)
          },
        )
      }
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
      // A binned field on a discrete scale is placed by its **label**, that being what the domain
      // lists; the bin's start would name a category the scale has never heard of.
      val binnedLabels =
        def.bin is Binning.Bin &&
          (def.type == MeasureType.ORDINAL || def.type == MeasureType.NOMINAL)
      put("field", Fields.vgField(def, suffix = if (binnedLabels) "range" else null))
      // A bar starts at the band's edge and fills it; a centred mark asks for the middle instead.
      if (scaleType == "band" && centred) put("band", num(0.5))
      // A nested offset moves the mark within its band, which is what puts the second bar of a
      // group beside the first rather than on top of it.
      offsetRef(view, channel, centred)?.let { put("offset", it) }
    }
  }

  /**
   * `positionOffset`: where a nested offset scale places the mark inside its lane.
   *
   * A centred mark sits in the middle of the lane — `bandPosition: 0.5` — and one aligned to the
   * band's leading edge sits at the lane's start, which is `bandPosition: 0` and written by leaving
   * the band off.
   */
  private fun offsetRef(view: UnitView, channel: String, centred: Boolean): VegaValue? {
    val offsetChannel = offsetChannelFor(channel)?.takeIf { view.hasScale(it) } ?: return null
    val offsetDef = view.spec.encoding.getValue(offsetChannel)
    return obj {
      put("scale", view.scale(offsetChannel))
      // An offset may name a **datum** rather than a field, which is how a repeated layer puts
      // each copy in a lane of its own: there is no column to read, only the value to look up.
      if (offsetDef.isFieldDef) put("field", Fields.vgField(offsetDef))
      else literalRef(offsetDef.datum)?.let { (key, value) -> put(key, value) }
      if (centred) put("band", num(0.5))
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
      "abs(scale(\"${view.scale(channel)}\", ${Fields.datumAccess(def, suffix = "end")}) - " +
        "scale(\"${view.scale(channel)}\", ${Fields.datumAccess(def)}))"

    // A **reversed** scale runs the other way, so the half-spacing that pulls a bin's edge inward
    // has to pull the other way with it — `getBinSpacing`'s `(reverse ? -1 : 1) *`. Upstream writes
    // the condition out rather than folding it, since a `reverse` may itself be a signal.
    val reversed =
      (view.scaleComponents[channel]?.properties?.get("reverse") as? VegaValue.Bool)?.value == true
    fun offset(isEnd: Boolean): VegaValue {
      val spacingOffset = if (isEnd) -spacing / 2 else spacing / 2
      if (minBandSize == null) {
        return num(axisTranslate + if (reversed) -spacingOffset else spacingOffset)
      }
      val sign = if (isEnd) "" else "-"
      // Every number here is written the way JavaScript writes it — `2`, not `2.0`. A Kotlin
      // `Double` interpolated straight into an expression carries a decimal point Vega-Lite's own
      // output never has, and the two specifications then differ on a string neither engine reads
      // as a number.
      val minimum = canonicalNumberString(minBandSize)
      val turn = if (reversed) "(true ? -1 : 1) * " else ""
      return signalRef(
        "${Fields.expressionNumber(axisTranslate)} + $turn($sizeExpression < $minimum ? " +
          "${sign}0.5 * ($minimum - ($sizeExpression)) : ${Fields.expressionNumber(spacingOffset)})"
      )
    }

    // `x2` takes the bin's start and `x` its end, which is what makes the rect span the bin.
    val startIsEnd = channel == "x" || channel == "y2"
    // Where the bin's far edge is read from. A bin *this* compiler asked for has an `_end` column
    // beside it; a column that arrived **already binned** does not — its far edge is a second
    // column of its own, named by the secondary channel, and that is the whole reason `bin:
    // "binned"` requires an `x2`.
    val endField =
      if (def.bin == Binning.PreBinned) {
        val def2 = view.spec.encoding[channel2]
        if (def2?.isFieldDef == true) Fields.vgField(def2) else Fields.vgField(def, suffix = "end")
      } else {
        Fields.vgField(def, suffix = "end")
      }
    // `bandPositionForBandSize`: a mark that asks for a *fraction* of its bucket is drawn inside
    // it rather than across it, so both edges move in by half of what is left over — `(1 - band)/2`
    // and its complement. A full band leaves them at 0 and 1, which is the bucket's own edges and
    // the plain field references below.
    val band = relativeBandSize(view, channel)
    val near = (1 - band) / 2
    return obj {
      put(
        channel2,
        interpolated(view, channel, Fields.vgField(def), endField, near, offset(!startIsEnd)),
      )
      put(
        channel,
        interpolated(view, channel, Fields.vgField(def), endField, 1 - near, offset(startIsEnd)),
      )
    }
  }

  /**
   * How much of its bucket a rect fills, where the mark states it as a fraction.
   *
   * `isRelativeBandSize`: `{"width": {"band": 0.7}}` on the mark, or the same shape in the
   * configured band size. Anything else — a number of units, a signal, nothing at all — leaves the
   * rect spanning the whole bucket.
   */
  private fun relativeBandSize(view: UnitView, channel: String): Double {
    val sizeChannel = if (channel == "x" || channel == "theta") "width" else "height"
    val markConfig = view.config.markConfig(view.spec.mark)
    val stated =
      view.markDef.raw.obj(sizeChannel)
        ?: markConfig.obj("continuousBandSize")
        ?: markConfig.obj("discreteBandSize")
    stated?.number("band")?.let {
      return it
    }
    // `timeUnitBandSize` is the same fraction written as a bare number, and it applies to a
    // *bucketed* dimension only: it is how a theme narrows every month's bar at once.
    if (view.spec.fieldDef(channel)?.timeUnit != null) {
      markConfig.number("timeUnitBandSize")?.let {
        return it
      }
    }
    return 1.0
  }

  /**
   * `interpolatedSignalRef`: a point *between* a bucket's two edges.
   *
   * At 0 or 1 it is one of the edges, and Vega scales the column itself; anywhere between, the
   * interpolation happens in data space and the scale is applied to the result — which is not the
   * same as interpolating the two scaled positions once the scale is not linear.
   */
  private fun interpolated(
    view: UnitView,
    channel: String,
    startField: String,
    endField: String,
    position: Double,
    offset: VegaValue,
  ): VegaValue = obj {
    if (position == 0.0 || position == 1.0) {
      put("scale", view.scale(channel))
      put("field", if (position == 0.0) startField else endField)
    } else {
      val start = "datum[${quoted(startField)}]"
      val end = "datum[${quoted(endField)}]"
      put(
        "signal",
        "scale(\"${view.scale(channel)}\", " +
          "${Fields.expressionNumber(1 - position)} * $start + " +
          "${Fields.expressionNumber(position)} * $end)",
      )
    }
    put("offset", offset)
  }
}
