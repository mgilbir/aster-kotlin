package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
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
   * `hasAxisOrientSignalRef`: whether any axis takes its side from an expression.
   *
   * An axis that moves from the bottom of a chart to the top re-lays the whole drawing out, and a
   * surface that was padded to the old extent keeps it — so the chart has to be told it may resize.
   */
  /** The expression behind `{"expr": …}` or `{"signal": …}`, where a property is written as one. */
  private fun signalOf(value: VegaValue?): String? =
    (value as? VegaValue.Obj)?.let { it.string("expr") ?: it.string("signal") }

  fun hasSignalOrient(view: UnitView): Boolean =
    listOf("x", "y").any { channel ->
      val orient = view.spec.encoding[channel]?.axis?.fields?.get("orient")
      orient is VegaValue.Obj && (orient.has("expr") || orient.has("signal"))
    }

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
      "maxExtent",
      "minExtent",
      "orient",
      "position",
      "tickCap",
      "tickColor",
      "tickDash",
      "tickDashOffset",
      "tickOpacity",
      "tickSize",
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
     * What each title came from, which is what makes two of them one.
     *
     * `mergeTitleFieldDefs` folds the **definitions**, not the words they render to: two layers
     * naming the same column in the same way contribute one title, and two naming it differently —
     * one bucketed and one not — contribute two, which read alike and are still two. Deduplicating
     * the rendered words instead quietly dropped the second.
     */
    val titleKeys: MutableList<String> = mutableListOf()

    fun addTitle(key: String, text: String) {
      if (key in titleKeys) return
      titleKeys += key
      titles += text
    }

    /**
     * Whether some view **stated** that this axis has no caption.
     *
     * ```js
     * if (v1Val == null || v2Val === null) {
     *   return {explicit: v1.explicit, value: null};
     * }
     * ```
     *
     * A stated title outranks a derived one — `mergeValuesWithExplicit` prefers the explicit side —
     * and a stated `null` outranks another stated title as well: `mergeTitleComponent` answers
     * `null` for either side being it, whatever the other says. So one layer of a chart saying its
     * axis has no caption takes the caption off the axis, and not merely off its own contribution
     * to it, which is how a layer added to a titled chart leaves the titling to the chart.
     */
    var nulledTitle: Boolean = false

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
     * A title written as **several lines**, which is a list of strings rather than one.
     *
     * `assembleTitle` passes such a title through untouched — `isArray(title) && !isText(title)` is
     * what picks out the *other* kind of array, a list of field definitions to be joined with
     * commas. A list of strings is already text, and Vega draws it one line per entry. It cannot be
     * folded into [titles], which are the definitions the merge counts.
     */
    var lines: VegaValue.Arr? = null

    /**
     * Whether the specification named the side itself.
     *
     * Two independent axes on one channel are moved apart, and one the specification placed is left
     * where it was put — upstream's `!explicit` guard.
     */
    var explicitOrient: Boolean = false

    /**
     * Whether the specification asked for these gridlines by name.
     *
     * Only a **derived** grid is taken off the second of two independent axes — upstream's test is
     * `!axisCmpt.explicit.grid`, and a property is explicit when the value matches what the axis
     * block itself stated. Two layers that each ask for gridlines get two sets, however oddly that
     * reads; two that merely happen to have them get one.
     */
    var explicitGrid: Boolean = false

    /**
     * The properties some view **stated**, which settle them for the merged axis.
     *
     * `mergeAxisComponent` folds a shared axis property by property with `mergeValuesWithExplicit`,
     * and an explicit value beats a derived one whichever layer it arrives on. Filling only the
     * gaps meant a layer that turns its gridlines off lost to an earlier layer that never mentioned
     * them — a quantitative position has them by default, so the earlier layer's silence became a
     * decision.
     */
    val explicitProperties: MutableSet<String> = mutableSetOf()

    fun set(name: String, value: VegaValue?) {
      if (value != null && !properties.containsKey(name)) properties[name] = value
    }

    /** Overwrites what was already decided, which only the layer-level merge ever needs to do. */
    fun override(name: String, value: VegaValue) {
      properties[name] = value
    }

    /** A property that has to be a *rule* rather than a constant, on the labels' own encode. */
    fun encodeLabel(channel: String, value: VegaValue) {
      val existing = properties["encode"] as? VegaValue.Obj
      val labels = (existing?.fields?.get("labels") as? VegaValue.Obj)?.fields.orEmpty()
      val update = (labels["update"] as? VegaValue.Obj)?.fields.orEmpty()
      properties["encode"] = obj {
        existing?.fields?.forEach { (key, part) -> if (key != "labels") put(key, part) }
        put(
          "labels",
          obj {
            labels.forEach { (key, part) -> if (key != "update") put(key, part) }
            put(
              "update",
              obj {
                update.forEach { (key, part) -> put(key, part) }
                put(channel, value)
              },
            )
          },
        )
      }
    }
  }

  fun parseAxis(
    view: UnitView,
    channel: String,
    def: ChannelDef,
    type: String,
    hasOtherPosition: Boolean,
    diagnostics: DiagnosticCollector,
  ): AxisComponent? {
    // A disabled axis is still a **component**, and still takes part in the merge:
    //
    //     const disable = axis !== undefined ? !axis : getAxisConfig('disable', …).configValue;
    //     axisComponent.set('disable', disable, axis !== undefined);
    //     if (disable) { return axisComponent; }
    //
    // `axis !== undefined` makes a stated `"axis": null` an *explicit* decision, and an explicit
    // value beats every sibling's. One layer of a chart turning an axis off turns it off for the
    // scale, and returning nothing at all here let the other layers put it back.
    if (def.axisDisabled) return AxisComponent(channel).also { it.disabled = true }
    val user = def.axis
    // The blocks a theme may write this axis in, most specific first — `config.axisX` as much as
    // `config.axis`. `getAxisConfig` asks the same chain for **every** axis property, not only the
    // ones Vega has never heard of, so a theme that turns its horizontal labels upright or takes
    // every caption off is read here and not just where a conditional value is.
    val configuredSide = user?.string("orient") ?: if (channel == "x") "bottom" else "left"
    val (vegaLiteOnlyConfigs, vegaConfigs) =
      view.config.axisConfigFamilies(channel, type, configuredSide)
    // `getStyleConfig(property, axis.style, config.style)`: an axis may **name style blocks**, and
    // they outrank every configuration family. That is how a document keeps its axis styling in one
    // place and points an axis at it by name, and it is the only way to reach the properties a
    // family cannot state — a `labelExpr` in a style block writes the labels of every axis that
    // names it.
    val styleConfigs = namedStyles(user).mapNotNull { view.config.style(it) }
    // `getAxisConfigStyle`: a configuration **family** may name style blocks too, and those are the
    // last word rather than the first — `[...vgConfigTypes, ...vlOnlyConfigTypes]` merged in order,
    // which is behind everything the families themselves state.
    val familyStyles =
      (vegaConfigs + vegaLiteOnlyConfigs)
        .flatMap { namedStyles(it) }
        .mapNotNull {
          view.config.style(it)
        }
    val axisConfigs = styleConfigs + vegaLiteOnlyConfigs + vegaConfigs + familyStyles
    fun configured(name: String): VegaValue? = axisConfigs.firstNotNullOfOrNull { it.fields[name] }
    // `configFrom === 'vgAxisConfig'`: a property the theme states in a block **Vega** knows is
    // left off the axis, so that Vega applies it from its own config block — writing a *derived*
    // value here as well would settle it for this axis alone, and settle it with a default. One
    // stated in a Vega-Lite-only block has to be written out instead, there being nothing else to
    // apply it, and so does one of `propsToAlwaysIncludeConfig`.
    // A style block is not a Vega config block: a property found in one has to be written **out**,
    // as a Vega-Lite-only family's is.
    fun themedByVega(name: String): Boolean =
      styleConfigs.none { it.fields.containsKey(name) } &&
        vegaLiteOnlyConfigs.none { it.fields.containsKey(name) } &&
        vegaConfigs.any { it.fields.containsKey(name) }
    // `config.axis.disable` turns every axis off at once, which is how a chart made of shapes
    // rather
    // than of measurements says it has no axes at all. A channel's own `axis` block is the explicit
    // statement and outranks it either way.
    if (def.axis == null && configured("disable") == VegaValue.Bool(true)) return null
    val axis = AxisComponent(channel)

    /**
     * A value this compiler worked out, which a theme Vega can read outranks — see [themedByVega].
     */
    fun derived(name: String, value: VegaValue) {
      val themed = configured(name)
      when {
        themed == null -> axis.set(name, value)
        // `else if (!(configFrom === 'vgAxisConfig')) axisComponent.set(property, configValue,
        // false)`: a block only Vega-Lite knows has to be written **out**, there being nothing
        // downstream that would apply it.
        !themedByVega(name) -> axis.set(name, asSignal(themed))
        else -> Unit
      }
    }

    axis.set("scale", str(view.scale(channel)))
    axis.explicitOrient = user?.fields?.get("orient") != null
    axis.set("orient", str(if (channel == "x") "bottom" else "left"))

    // The gridlines belong to *this* scale but are drawn across the other one's extent. Whether
    // there are any is a *default* — a continuous field-driven axis has them — and a theme saying
    // `config.axis.grid: false` settles it for every axis at once, which is how a chart turns them
    // all off in one line. Reading only the channel's own `axis` block left them on.
    val grid =
      (def.axis?.fields?.get("grid") ?: configured("grid"))?.let {
        (it as? VegaValue.Bool)?.value == true
      } ?: (!Scales.hasDiscreteDomain(type) && def.isFieldDef && def.bin == null)
    if (grid && hasOtherPosition)
      axis.set("gridScale", str(view.scale(if (channel == "x") "y" else "x")))
    axis.set("grid", bool(grid))
    // `isExplicit` ends in `value === axis[property]`: stating `"grid": true` on the channel's own
    // axis block is asking for the gridlines, and a `config.axis.grid` that happens to produce the
    // same answer is not.
    axis.explicitGrid = def.axis?.fields?.get("grid") == VegaValue.Bool(grid)

    // A ranged position is titled by *both* of its fields — `start, end` — because the axis is
    // measuring the span rather than either end of it. Unless one of them says what it is called:
    // an **explicit** title short-circuits the merge (`getFieldDefTitle`), which is how a summary
    // of `v` is titled `v` rather than `lower_v, upper_v`.
    val secondary = secondaryChannel(channel)?.let { view.spec.fieldDef(it) }
    // The **guide's** own title is asked for first — `axis.title !== undefined` opens the rule —
    // and it settles the question outright. A ranged position joins its two fields' names only
    // where nothing has said what the axis is measuring, so an axis captioned `Temperature (F)`
    // stays that and does not gain `, record.high, normal.high` from the layers under it.
    // `axis.title` first, then the field's own, then the pair a ranged position names — and the
    // first of those that answers is the whole answer. A layer whose *guide* names the axis has
    // said what the axis measures, so the fields' own titles add nothing after it.
    val guideTitle = def.axis?.fields?.get("title") ?: def.legend?.fields?.get("title")
    // `config.axis.title` settles it for every axis in the chart that has not been titled itself,
    // and **null** is the useful value: it is how a chart whose columns explain themselves takes
    // every caption off at once, rather than writing `"title": null` on each of them.
    val themeTitle = if (guideTitle != null) null else configured("title")
    val stated =
      if (guideTitle != null) listOf(guideTitle)
      else if (themeTitle != null) listOfNotNull(themeTitle.takeIf { it !is VegaValue.Null })
      else listOfNotNull(def.explicitTitle, secondary?.explicitTitle)
    if (guideTitle is VegaValue.Null) axis.nulledTitle = true
    if (themeTitle is VegaValue.Null) {
      axis.explicitTitle = true
    } else if (stated.isNotEmpty()) {
      axis.explicitTitle = true
      // `isText`: a list whose first entry is a string is a caption over several lines, and goes
      // out as it stands. Reading only a single string dropped such a caption altogether.
      stated
        .firstOrNull { it is VegaValue.Arr && it.values.firstOrNull() is VegaValue.Str }
        ?.let {
          axis.lines = it as VegaValue.Arr
        }
      stated.mapNotNull { (it as? VegaValue.Str)?.value }.forEach { axis.addTitle(it, it) }
    } else {
      for (channelDef in listOfNotNull(def, secondary)) {
        val title = Fields.title(channelDef, view.config) as? VegaValue.Str ?: continue
        // Keyed by what makes the definition the one it is, not by the JSON it arrived as: the
        // parse settles a `"bin": "binned"` and a stated type into properties of their own, so two
        // definitions that differ only there arrive looking alike.
        // `toFieldDefBase` keeps the field, the bucketing, the instant and the aggregate — and not
        // the **type**. Two layers over one column, one of them calling it ordinal and the other
        // saying nothing, are the same field to a title, and keying by the type as well titled the
        // axis "age, age".
        val key =
          listOf(
              channelDef.field,
              channelDef.bin,
              channelDef.timeUnit,
              channelDef.aggregate,
              channelDef.explicitTitle,
            )
            .joinToString("|")
        axis.addTitle(key, title.value)
      }
    }

    // A nominal category on the horizontal axis is turned on its side, because side-by-side labels
    // collide as soon as there are more than a handful of them.
    val statedAngle = user?.number("labelAngle")
    // A theme's own angle still decides which way the labels are aligned, but it is **not written
    // back onto the axis**: Vega knows `labelAngle` and applies the configuration itself, and
    // writing it here would say the same thing twice. Only a stated angle, or the default this
    // compiler supplies, is written.
    val themeAngle = (configured("labelAngle") as? VegaValue.Num)?.value
    val labelAngle =
      statedAngle
        ?: themeAngle
        ?: if (channel == "x" && def.type?.isDiscrete == true && def.timeUnit == null) 270.0
        else null
    val side = user?.string("orient") ?: if (channel == "x") "bottom" else "left"
    // An angle a *signal* supplies cannot be compared here, so the comparison is written out and
    // handed to Vega: `defaultLabelAlign`'s signal branch. The two answers then have to live on the
    // labels' own `encode`, an axis property taking a constant rather than a rule.
    val angleSignal =
      (user?.fields?.get("labelAngle") as? VegaValue.Obj)?.let {
        it.string("expr") ?: it.string("signal")
      }
    if (angleSignal != null) {
      val turned = "((($angleSignal % 360) + 360) % 360)"
      turnedAlign(turned, channel, side)?.let { axis.encodeLabel("align", signalRef(it)) }
      turnedBaseline(turned, channel, side)?.let { axis.encodeLabel("baseline", signalRef(it)) }
    } else if (labelAngle != null) {
      // `normalizeAngle`: an angle is a turn from zero, so a label at minus forty-five degrees is
      // a label at three hundred and fifteen — the two draw alike and compare as different numbers.
      val angle = ((labelAngle % 360) + 360) % 360
      if (statedAngle != null) axis.set("labelAngle", num(angle))
      else derived("labelAngle", num(angle))
      // An **orient** the specification drives from a parameter cannot be compared here either: the
      // side the axis will be drawn on is not known until the reader picks it, so the alignment is
      // written as the comparison and handed to Vega, on the labels' own encode block.
      val orientSignal = signalOf(user?.fields?.get("orient"))
      if (orientSignal != null) {
        val start = if (channel == "x") 0.0 else 90.0
        val main = if (channel == "x") "bottom" else "left"
        val turned = start < angle && angle < 180 + start
        val comparison = if (turned) "===" else "!=="
        axis.encodeLabel(
          "align",
          signalRef("$orientSignal $comparison \"$main\" ? \"left\" : \"right\""),
        )
      } else {
        // A **null** alignment is a decision, not a gap: an x axis whose labels sit level takes
        // Vega's own default so that `labelFlush` still works, and upstream records that null on
        // the component. It is what stops a layer beside it — one whose labels are turned, and so
        // aligned to their right — from supplying an alignment for the whole axis. `assembleAxis`
        // then drops it rather than writing it out.
        derived("labelAlign", labelAlign(angle, channel, side)?.let { str(it) } ?: VegaValue.Null)
      }
      // The **normalised** angle, as `defaultLabelAngle` hands it to both of these: the label at
      // minus ninety degrees that is written out as two hundred and seventy has to be *compared* as
      // two hundred and seventy too. `225 < angle && angle < 315` is how a vertical label on the
      // bottom axis earns `baseline: "middle"`, and minus ninety satisfies neither that nor the
      // arm above it, so such a label was anchored by its top instead.
      labelBaseline(angle, channel, side)?.let { derived("labelBaseline", str(it)) }
    }

    if (
      channel == "x" && (def.type == MeasureType.QUANTITATIVE || def.type == MeasureType.TEMPORAL)
    ) {
      derived("labelFlush", bool(true))
    }

    // A continuous axis may drop labels that would overlap; a nominal one may not, because a reader
    // cannot infer the category that went missing. A log axis drops them *greedily* rather than by
    // parity: its labels are unevenly spaced, so hiding every other one thins the dense end and
    // leaves the sparse end untouched.
    // A bucketed instant is the exception among discrete labels: a reader who sees Jan, Mar, May
    // supplies February, so `defaultLabelOverlap` lets a time unit thin its own labels — unless the
    // specification stated an order, where a gap would leave the reader guessing what was skipped.
    // `!isObject(sort)` — and in JavaScript an **array** is an object, so a written-out order
    // suppresses the thinning as much as a sort object does: the reader is being shown a stated
    // sequence, and a gap in a stated sequence is a question rather than an inference.
    val timeUnitLabels =
      def.timeUnit != null && def.sort !is VegaValue.Obj && def.sort !is VegaValue.Arr
    if (timeUnitLabels || (def.type != MeasureType.NOMINAL && def.type != MeasureType.ORDINAL)) {
      val greedy = type == "log" || type == "symlog"
      derived("labelOverlap", if (greedy) str("greedy") else bool(true))
    }

    // Labels for a bucketed instant, and a tick step no finer than the bucket.
    if (def.timeUnit != null) {
      axis.set("format", signalRef(Fields.timeUnitSpecifier(def.timeUnit, view.config.locale)))
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
    // `isDiscrete` counts a **binned** field too: its buckets are categories, and a binned heatmap
    // fills them as completely as a categorical one does.
    if (view.spec.mark == "rect" && (def.type?.isDiscrete == true || def.bin != null)) {
      derived("zindex", num(1))
    }

    // `replaceExprRef`: an `{"expr": …}` written on a guide is a *signal* to Vega, which has no
    // `expr`. Passing it through left the property unread and the guide at its default.
    // What the specification stated on the axis, **asked for by name** — `for (const property of
    // AXIS_COMPONENT_PROPERTIES) { … isAxisProperty(property) ? axis[property] : undefined }`. A
    // block holding anything else is never looked at, so a word from an older Vega-Lite — an
    // `axisWidth`, which version 1 had — is not forwarded to a Vega that has never heard of it.
    // Reading the block's own keys did forward it: the same fault the mark's and the legend's
    // property lists were fixed for, and a denylist cannot be finished because what it has to
    // exclude is every word nobody has written yet.
    for (key in AXIS_PROPERTIES) {
      val value = user?.fields?.get(key) ?: continue
      // `normalizeAngle`: a turn is measured from zero, so a label the specification wrote at
      // minus forty-five degrees is a label at three hundred and fifteen.
      axis.properties[key] =
        if (key == "labelAngle") {
          (value as? VegaValue.Num)?.let { num(((it.value % 360) + 360) % 360) } ?: asSignal(value)
        } else {
          asSignal(value)
        }
    }
    // A property Vega has no name for cannot be left in the configuration for Vega to apply: it
    // has to be resolved here, onto this axis, or nothing acts on it at all. That is upstream's
    // `propsToAlwaysIncludeConfig` together with its conditional-value case, over the blocks a
    // theme may write an axis in — `config.axisX` as much as `config.axis`.
    for (property in VL_ONLY_AXIS_PROPERTIES) {
      if (user?.fields?.containsKey(property) == true) continue
      val value = configured(property) ?: continue
      if (
        property !in CONDITIONAL_AXIS_PARTS || (value as? VegaValue.Obj)?.has("condition") == true
      ) {
        axis.properties[property] = asSignal(value)
      }
    }
    // ```js
    // const {configValue = undefined, configFrom = undefined} =
    //   isAxisProperty(property) && property !== 'values'
    //     ? getAxisConfig(property, config.style, axis.style, axisConfigs)
    //     : {};
    // ...
    // } else if (hasConfigValue && configFrom !== 'vgAxisConfig') {
    //   // Add config value to axis component if there is no explicit value and the value is not
    //   // from a Vega config
    //   axisComponent.set(property, configValue, false);
    // }
    // ```
    //
    // A configuration Vega cannot apply has to be resolved **here**, onto this axis, or nothing
    // acts on it at all — and that is every source but a Vega one: a style block this compiler has
    // already resolved, and a family named after a *scale* (`config.axisQuantitative`,
    // `config.axisTemporal`) which Vega has never heard of. This engine wrote out only the
    // properties it had a rule for, so a theme colouring every measured axis orange or turning its
    // labels to a stated font was read and dropped. Five specifications in the wild corpus theme
    // their axes that way. A family Vega *does* know is still left to Vega: writing a derived value
    // beside it would settle the property for this axis alone and beat the theme with a default.
    for (property in AXIS_PROPERTIES) {
      if (axis.properties.containsKey(property)) continue
      if (themedByVega(property)) continue
      axis.properties[property] = asSignal(configured(property) ?: continue)
    }
    conditionalToEncode(axis, diagnostics)

    // `guideFormat` ends at `numberFormat`, which for a quantitative field with nothing stated is
    // `config.numberFormat`: a chart that asks for whole numbers everywhere asks for them on its
    // axes too. Read after the specification's own block, so a stated format still wins.
    if (def.type == MeasureType.QUANTITATIVE && def.timeUnit == null) {
      // A **custom** format type is a function the page registered, not a specifier Vega can read,
      // so `guideFormat` answers nothing for it and the label becomes an expression calling the
      // function — written into the labels' own encode block, where a `datum.value` exists to pass.
      val custom = view.config.numberFormatType.takeIf { def.format == null }
      if (custom != null) {
        axis.encodeLabel(
          "text",
          signalRef("$custom(datum.value, \"${view.config.numberFormat.orEmpty()}\")"),
        )
      } else {
        view.config.numberFormat?.let { axis.set("format", str(it)) }
      }
    }

    // `defaultTickMinStep`: a `d` format asks for whole numbers, so no tick may be closer than one.
    // Read *after* the specification's own block, because that is where the format usually comes
    // from — and `set` leaves a stated `tickMinStep` alone.
    if ((axis.properties["format"] as? VegaValue.Str)?.value == "d") axis.set("tickMinStep", num(1))
    return axis
  }

  /**
   * Which way a legend runs — `getFirstDefined(legend.direction, legendConfig.direction, …)`.
   *
   * Stated on the legend, then on the configuration, and only then derived from where the legend
   * sits: along the top or the bottom it runs horizontally, and a **gradient** does so in the inner
   * corners too. Everywhere else it is vertical, which is Vega's own default and so goes unsaid.
   */
  private fun legendDirection(
    view: UnitView,
    def: ChannelDef,
    gradient: Boolean,
    orient: String,
  ): String? =
    def.legend?.string("direction")
      // `legendConfig[legendType ? 'gradientDirection' : 'symbolDirection']` — and `legendType` is
      // `'symbol'` or `'gradient'`, **both truthy**, so the ternary always reads
      // `gradientDirection` and `symbolDirection` is never consulted at all. A theme that turns its
      // swatch columns sideways has to say `gradientDirection` to do it. Reproduced rather than
      // repaired, this being what upstream emits.
      //
      // `config.legend.direction` is *not* one of the places asked: it settles the direction Vega
      // draws the legend in, from Vega's own config block, and takes no part in the length measured
      // here.
      ?: view.config.raw.obj("legend")?.string("gradientDirection")
      // `defaultDirection`: along the top or the bottom of a chart a legend runs horizontally, and
      // everywhere else it keeps Vega's own vertical — which is why this answers **null** rather
      // than `"vertical"`. An *inner* legend, one at a corner, is laid out compactly "like
      // Tableau", but only as a ramp: a column of swatches is compact already.
      ?: when (orient) {
        "top",
        "bottom" -> "horizontal"
        "left",
        "right",
        "none" -> null
        else -> if (gradient) "horizontal" else null
      }

  /**
   * `defaultLabelAlign`, written out for an angle nobody can read yet.
   *
   * The rule is the same as the constant one — is the label turned past the axis's own side? — but
   * every branch of it has to be an expression, because the angle arrives at render time.
   */
  private fun turnedAlign(turned: String, channel: String, orient: String): String? {
    val isX = channel == "x"
    val startAngle = if (isX) 0 else 90
    val main = if (isX) "bottom" else "left"
    val forward = "($startAngle < $turned && $turned < ${180 + startAngle})"
    val flat = if (isX) "null" else "\"center\""
    val shifted = if (startAngle == 0) turned else "($turned + $startAngle)"
    return "($shifted % 180 === 0) ? $flat :" +
      "$forward === ${orient == main} ? \"left\" : \"right\""
  }

  private fun turnedBaseline(turned: String, channel: String, orient: String): String? {
    if (channel == "x") {
      return "(45 < $turned && $turned < 135) || (225 < $turned && $turned < 315) ? \"middle\" :" +
        "($turned <= 45 || 315 <= $turned) === ${orient == "top"} ? \"bottom\" : \"top\""
    }
    return "$turned <= 45 || 315 <= $turned || (135 <= $turned && $turned <= 225) ? null : " +
      "(45 <= $turned && $turned <= 135) === ${orient == "left"} ? \"top\" : \"bottom\""
  }

  /** `{"expr": …}` is Vega-Lite's way of writing a signal, and Vega's way is `{"signal": …}`. */
  private fun asSignal(value: VegaValue): VegaValue {
    val expression = (value as? VegaValue.Obj)?.takeIf { it.fields.keys == setOf("expr") }
    return expression?.string("expr")?.let { signalRef(it) } ?: value
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
  /**
   * Which way a turned label runs, which depends on the side the axis is on.
   *
   * `defaultLabelAlign` compares the angle against the axis's **main** orientation — the bottom for
   * x, the left for y — and flips the answer when the axis has been moved to the other side. A
   * label hanging off the top of a chart reads the other way round from the same label underneath
   * it, so ignoring the side anchored every moved axis's labels at the wrong end.
   */
  fun labelAlign(angle: Double, channel: String, orient: String): String? {
    val startAngle = if (channel == "x") 0.0 else 90.0
    val main = if (channel == "x") "bottom" else "left"
    if ((angle + startAngle) % 180.0 == 0.0) return if (channel == "x") null else "center"
    val forward = startAngle < angle && angle < 180 + startAngle
    return if (forward == (orient == main)) "left" else "right"
  }

  fun labelBaseline(
    angle: Double,
    channel: String,
    orient: String,
    alwaysIncludeMiddle: Boolean = false,
  ): String? {
    if (channel == "x") {
      if ((45 < angle && angle < 135) || (225 < angle && angle < 315)) return "middle"
      return if ((angle <= 45 || 315 <= angle) == (orient == "top")) "bottom" else "top"
    }
    if (angle <= 45 || 315 <= angle || (135 <= angle && angle <= 225)) {
      return if (alwaysIncludeMiddle) "middle" else null
    }
    return if ((45 <= angle && angle <= 135) == (orient == "left")) "top" else "bottom"
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

  /**
   * The axis properties a configuration cannot simply hand to Vega.
   *
   * `labelExpr` becomes the labels' text and the conditional ones become an `encode` block, so a
   * theme that writes either of them has written something Vega will not read. Upstream's list is
   * longer — `grid`, `format`, `tickCount` and the rest are here as rules of their own instead.
   */
  /**
   * `AXIS_PROPERTIES`: the properties an axis is **asked** for, in `AXIS_COMPONENT_PROPERTIES`
   * order.
   *
   * `COMMON_AXIS_PROPERTIES_INDEX` together with the two of Vega-Lite's own that are written out —
   * `labelExpr` and `encoding` — and the loop that reads a stated axis walks exactly this list. It
   * is an allowlist for the reason the mark's and the legend's are: a `gridCap` this compiler has
   * never heard of still reaches Vega, and an `axisWidth` from Vega-Lite version 1 does not.
   *
   * `style` is **not** here. It is a property an axis may state — `isAxisProperty` accepts it, and
   * `getAxisConfig` reads the blocks it names — and it is not one of `AXIS_COMPONENT_PROPERTIES`,
   * so it is never written onto the axis. Forwarding it handed Vega a style block reference beside
   * the properties this compiler had already resolved out of it, which is the same styling applied
   * twice.
   */
  private val AXIS_PROPERTIES =
    listOf(
      "orient",
      "aria",
      "bandPosition",
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
      "grid",
      "gridCap",
      "gridColor",
      "gridDash",
      "gridDashOffset",
      "gridOpacity",
      "gridWidth",
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
      "maxExtent",
      "minExtent",
      "offset",
      "position",
      "tickBand",
      "tickCap",
      "tickColor",
      "tickCount",
      "tickDash",
      "tickDashOffset",
      "tickExtra",
      "tickMinStep",
      "tickOffset",
      "tickOpacity",
      "tickRound",
      "ticks",
      "tickSize",
      "tickWidth",
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
      "translate",
      "values",
      "zindex",
      "labelExpr",
      "encoding",
    )

  private val VL_ONLY_AXIS_PROPERTIES = listOf("labelExpr") + CONDITIONAL_AXIS_PARTS.keys

  /**
   * The style blocks a `style` property names, **last first**.
   *
   * ```js
   * for (const style of styles) {
   *   const styleConfig = styleConfigIndex[style];
   *   if (hasProperty(styleConfig, p)) value = styleConfig[p];
   * }
   * ```
   *
   * A later style overrides an earlier one, and this list is read front to back by whoever asks it
   * for a property — so the order is reversed here, once, rather than at each reader.
   */
  private fun namedStyles(block: VegaValue.Obj?): List<String> =
    when (val stated = block?.fields?.get("style")) {
      is VegaValue.Str -> listOf(stated.value)
      is VegaValue.Arr -> stated.values.mapNotNull { (it as? VegaValue.Str)?.value }.reversed()
      else -> emptyList()
    }

  /** Moves every conditional property onto the encode block of the part it paints. */
  private fun conditionalToEncode(axis: AxisComponent, diagnostics: DiagnosticCollector) {
    val predicates = Transforms(diagnostics)
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
      // A guide's `test` is a **predicate**, written in the same grammar a `filter` is, and Vega
      // takes only an expression. Passing the object through left the test unread, so a gridline
      // told to dash everywhere but January dashed nowhere at all.
      val tested = conditions.mapIndexed { index, entry ->
        val test = (entry as? VegaValue.Obj)?.fields?.get("test") ?: return@mapIndexed entry
        if (test is VegaValue.Str) return@mapIndexed entry
        val expression =
          predicates.testExpression(test, "$.axis.$property.condition[$index].test")
            ?: return@mapIndexed entry
        obj { entry.fields.forEach { (k, v) -> put(k, if (k == "test") str(expression) else v) } }
      }
      moved.getOrPut(part) { LinkedHashMap() }[vgProp] = arr(tested + otherwise)
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
  fun withLabelText(encode: VegaValue?, labelExpr: String): VegaValue {
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
  /** `labelAlign`/`labelBaseline` of null are decisions the axis keeps and Vega is not shown. */
  private val NULLABLE_LABEL_PROPERTIES = setOf("labelAlign", "labelBaseline")

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
          else if (
            key !in setOf("scale", "orient", "zindex", "labelExpr") &&
              key !in MAIN_ONLY &&
              !(key in NULLABLE_LABEL_PROPERTIES && value is VegaValue.Null)
          )
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
        // Not deduplicated here: `assembleTitle` joins the definitions the merge kept, and the
        // merge is where two of them become one — by *definition*, not by the words they render
        // to. Two layers naming one column differently, one bucketed and one not, say its name
        // twice, and upstream writes it twice.
        val lines = axis.lines
        if (axis.nulledTitle) Unit
        else if (lines != null) put("title", lines)
        else
          axis.titles
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
              key !in GRID_ONLY &&
              !(key in NULLABLE_LABEL_PROPERTIES && value is VegaValue.Null)
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

  /**
   * `LEGEND_COMPONENT_PROPERTIES`: every property a legend has, less the three that never reach
   * Vega.
   *
   * `parseLegendForChannel` walks this list and asks the `legend` block for each entry, so a block
   * holding anything else — a word from a newer Vega-Lite, or a misspelling such as `labxelExpr`
   * for `labelExpr` — is never looked at. Reading the block's own keys instead forwarded them, and
   * Vega reported `PARSE_UNKNOWN_PROPERTY` two stages downstream.
   *
   * `disable`, `selections` and `labelExpr` are component-internal and destructured away by
   * `assembleLegend` — `const {disable, labelExpr, selections, ...legend} = legendCmpt.combine()`.
   * `labelExpr` is applied further down, after the encode parts are assembled.
   */
  private val LEGEND_PROPERTIES =
    setOf(
      "aria",
      "clipHeight",
      "columnPadding",
      "columns",
      "cornerRadius",
      "description",
      "direction",
      "fillColor",
      "format",
      "formatType",
      "gradientLength",
      "gradientOpacity",
      "gradientStrokeColor",
      "gradientStrokeWidth",
      "gradientThickness",
      "gridAlign",
      "labelAlign",
      "labelBaseline",
      "labelColor",
      "labelFont",
      "labelFontSize",
      "labelFontStyle",
      "labelFontWeight",
      "labelLimit",
      "labelOffset",
      "labelOpacity",
      "labelOverlap",
      "labelPadding",
      "labelSeparation",
      "legendX",
      "legendY",
      "offset",
      "orient",
      "padding",
      "rowPadding",
      "strokeColor",
      "symbolDash",
      "symbolDashOffset",
      "symbolFillColor",
      "symbolLimit",
      "symbolOffset",
      "symbolOpacity",
      "symbolSize",
      "symbolStrokeColor",
      "symbolStrokeWidth",
      "symbolType",
      "tickCount",
      "tickMinStep",
      "title",
      "titleAlign",
      "titleAnchor",
      "titleBaseline",
      "titleColor",
      "titleFont",
      "titleFontSize",
      "titleFontStyle",
      "titleFontWeight",
      "titleLimit",
      "titleLineHeight",
      "titleOpacity",
      "titleOrient",
      "titlePadding",
      "type",
      "values",
      "zindex",
      // The channel scales a legend may be driven by, and its own encode block.
      "opacity",
      "shape",
      "stroke",
      "fill",
      "size",
      "strokeWidth",
      "strokeDash",
      "encode",
    )

  /**
   * Whether this channel's legend is switched off, and whether the specification **said so**.
   *
   * ```js
   * const disable = legend !== undefined ? !legend : legendConfig.disable;
   * legendCmpt.set('disable', disable, legend !== undefined);
   * if (disable) return legendCmpt;
   * ```
   *
   * The channel's own `legend` settles it either way, and where the channel says nothing the theme
   * decides — `config: {"legend": {"disable": true}}` is how a chart drops every legend at once. A
   * `disable` written **inside** the block settles it too: it is one of
   * `LEGEND_COMPONENT_PROPERTIES`, read off the block like any other and then destructured away by
   * `assembleLegend`, which answers nothing for a disabled component.
   *
   * The second half of the pair is what makes this more than a local question. A disabled component
   * is still a component, and its `disable` is *explicit* whenever the channel wrote a `legend` at
   * all — so it beats a derived one through `mergeValuesWithExplicit`, and a layer writing
   * `"legend": null` takes the **merged** legend away rather than only its own. By the same rule a
   * layer writing `"legend": {}` says explicitly that it is *not* disabled, which is how one layer
   * brings a key back that `config.legend.disable` had switched off.
   */
  fun legendDisable(view: UnitView, def: ChannelDef): Pair<Boolean, Boolean> {
    val stated =
      def.raw.fields["legend"]
        ?: return (view.config.raw.obj("legend")?.fields?.get("disable") == VegaValue.Bool(true)) to
          false
    if (!stated.isTruthy()) return true to true
    return (stated as? VegaValue.Obj)?.fields?.get("disable").isTruthy() to true
  }

  /**
   * `getLegendDefWithScale`: the property a legend draws this channel's swatches from.
   *
   * A trail's legend names two channels differently from every other mark's. Its swatch is a short
   * stroke, so colour goes on the `stroke` however the mark is filled, and its `size` — a width
   * along the path — is a `strokeWidth` rather than an area.
   *
   * It is asked of a **disabled** channel too. `new LegendComponent({},
   * getLegendDefWithScale(model, channel))` runs before the disable is read, so a channel switched
   * off still tells the legend it merges into which scale draws its swatches: a chart whose lines
   * are told apart by colour *and* by dash pattern keeps one key, and that key shows both however
   * the dashes' own legend was switched off.
   */
  fun legendScaleChannel(view: UnitView, channel: String): String {
    val filled = view.markDef.filled
    return when {
      view.spec.mark == "trail" && channel == "color" -> "stroke"
      view.spec.mark == "trail" && channel == "size" -> "strokeWidth"
      channel == "color" -> if (filled) "fill" else "stroke"
      else -> channel
    }
  }

  /** The legend a scaled non-position channel produces, or null when it produces none. */
  fun legend(view: UnitView, channel: String, def: ChannelDef, type: String): VegaValue? {
    if (legendDisable(view, def).first) return null
    val filled = view.markDef.filled
    val scaleChannel = legendScaleChannel(view, channel)

    // `isContinuousToContinuous` includes the two **time** scales: a colour ramp over instants is
    // still a ramp, and reading it as discrete drew a row of square swatches over a continuum.
    val continuous =
      type == "linear" ||
        type == "log" ||
        type == "pow" ||
        type == "sqrt" ||
        type == "symlog" ||
        type == "time" ||
        type == "utc"
    // `defaultType`: a colour ramp explains a continuous scale — except over a **quarter**, a
    // **month** or a **day**, which are continuous in the data and a short list to the reader. Four
    // quarters are four swatches, not a bar with a gradient along it.
    val namedUnits = def.timeUnit in setOf("quarter", "month", "day")
    val gradient = channel in setOf("color", "fill", "stroke") && continuous && !namedUnits

    // `if (explicit || config.legend[property] === undefined)`: a property the **theme** states is
    // not written onto the component at all. It is already in the Vega `config.legend` block that
    // goes out beside the chart, and Vega applies it from there to every legend at once; writing a
    // *derived* value here as well would settle it for this legend alone and beat the theme with a
    // default. A value the specification stated on the channel is explicit and still wins.
    val themed = view.config.raw.obj("legend")?.fields.orEmpty()
    // The side the legend sits on — `legend.orient || config.legend.orient || 'right'`, which is
    // what `getDirection` and `defaultGradientLength` are both measured against.
    val legendOrient =
      def.legend?.string("orient") ?: view.config.raw.obj("legend")?.string("orient") ?: "right"
    /** Which way the legend runs — [legendDirection], asked once for both the uses it has. */
    val legendRuns = legendDirection(view, def, gradient, legendOrient)
    return obj {
      /** A value this compiler worked out, which the theme outranks. */
      fun derived(key: String, value: VegaValue) {
        if (key !in themed) put(key, value)
      }
      put(scaleChannel, view.scale(channel))
      // `config.aria: false` takes the whole chart out of the accessibility tree, and a guide says
      // so on itself: there is nothing to read a key out to. A legend that states its own `aria`
      // has already answered the question.
      if (
        view.config.raw.fields["aria"] == VegaValue.Bool(false) &&
          def.legend?.fields?.get("aria") == null
      ) {
        put("aria", VegaValue.Bool(false))
      }
      // A legend labels a bucketed instant the same way an axis does, and for the same reason: the
      // swatch beside a colour ramp of months should read `Jan`, not the month's number.
      if (def.timeUnit != null) {
        derived("format", signalRef(Fields.timeUnitSpecifier(def.timeUnit, view.config.locale)))
      } else if (def.type == MeasureType.TEMPORAL) {
        // `omitTimeFormatConfig` is true for an axis and **false** for a legend: an axis chooses
        // its own granularity from the span it is showing, where a legend's entries stand alone
        // and take the configured date format.
        derived("format", str(view.config.timeFormat))
      }
      formatType(def, type)?.let { derived("formatType", str(it)) }
      // `defaultLabelOverlap` for a legend, which is a shorter list than an axis's: a scale whose
      // entries are unevenly spaced drops labels *greedily*, keeping the first of each collision
      // rather than every other one, because parity would thin the crowded end alone.
      if (type in setOf("quantile", "threshold", "log", "symlog")) {
        derived("labelOverlap", str("greedy"))
      }
      // A **custom** format type is a function the page registered rather than a specifier, so the
      // entry's label is written out as an expression calling it — the same rule the axes follow.
      val customLabel =
        view.config.numberFormatType
          ?.takeIf { def.type == MeasureType.QUANTITATIVE && def.format == null }
          ?.let { signalRef("$it(datum.value, \"${view.config.numberFormat.orEmpty()}\")") }
      val parts = LinkedHashMap<String, VegaValue>()
      customLabel?.let {
        parts["labels"] = obj { put("update", obj { put("text", it) }) }
      }
      if (gradient) {
        // A colour ramp is drawn as a bar whose length follows the plot, within Vega's own limits —
        // and **which** measure of the plot depends on which way the ramp runs. A horizontal one is
        // as long as the plot is wide and no shorter than a hundred units; a vertical one follows
        // the height and may be as short as sixty-four.
        // The *view's* own size signal, not the plain name: inside a concatenation the plotting
        // area is `concat_0_childHeight` and `height` is either something else or nothing at all,
        // so a ramp measured against it came out the wrong length or not at all.
        // And only where the ramp lies **along** the measure it would follow:
        //
        //     if (direction === 'horizontal') {
        //       if (orient === 'top' || orient === 'bottom') {
        //         return gradientLengthSignal(model, 'width', min, max);
        //       } else {
        //         return gradientHorizontalMinLength;
        //       }
        //     } else {
        //       return gradientLengthSignal(model, 'height', min, max);
        //     }
        //
        // A horizontal ramp beside the plot, or placed by hand with `orient: "none"`, has no width
        // to follow — it is simply the shortest a horizontal ramp may be. A vertical one follows
        // the height wherever it sits, there being a height either way.
        val horizontal = legendRuns == "horizontal"
        val alongThePlot = !horizontal || legendOrient == "top" || legendOrient == "bottom"
        val measure = if (horizontal) view.sizeSignal("x") else view.sizeSignal("y")
        val shortest = if (horizontal) 100 else 64
        if (alongThePlot) derived("gradientLength", signalRef("clamp($measure, $shortest, 200)"))
        else derived("gradientLength", num(shortest.toDouble()))
      } else {
        // The type is written only where it *disagrees* with what Vega would pick: a symbol legend
        // over a continuous colour scale has to say so, and everywhere else a symbol is already
        // the default and saying it again is noise.
        if (namedUnits && continuous && channel in setOf("color", "fill", "stroke")) {
          derived("type", str("symbol"))
        }
        derived("symbolType", str(defaultSymbolType(view, channel)))
      }
      // `if (property === 'title' && value === fieldDef?.title) { explicit = true }` — a caption
      // the *definition* wrote is explicit as much as one its `legend` block wrote, so it beats a
      // theme that turns captions off.
      val titled = def.legend?.fields?.get("title") != null || def.explicitTitle != null
      Fields.title(def, view.config)?.let { if (titled) put("title", it) else derived("title", it) }
      // `getDirection`:
      //
      //     legend.direction
      //       ?? legendConfig[legendType ? 'gradientDirection' : 'symbolDirection']
      //       ?? defaultDirection(orient, legendType)
      //
      // A legend along the top or bottom of a chart runs **horizontally**; Vega's own default is
      // vertical, and left, right and `none` keep it. An *inner* legend — one at a corner — is laid
      // out compactly "like Tableau", but only as a ramp, a column of swatches being compact
      // already.
      //
      // The side is [legendOrient]: the channel's, then the **theme's**, then Vega's own right.
      // Reading only the channel's left a chart whose theme says `config.legend.orient: "top"` with
      // its keys stacked vertically along the top edge, which is four specifications in the wild
      // corpus.
      if (def.legend?.fields?.get("direction") == null) {
        legendRuns?.let { derived("direction", str(it)) }
      }
      // `labelExpr` is **not** a Vega legend property, exactly as it is not a Vega axis property:
      // upstream's `assembleLegend` destructures it out of the component and writes
      // `encode.labels.update.text` from it. Passed through, it survived into the emitted Vega and
      // the Vega parser reported `PARSE_UNKNOWN_PROPERTY at $.legends[0].labelExpr` two stages
      // downstream, while the labels were drawn from the scale's domain unchanged. `labelExpr` is
      // how a document shortens labels that would otherwise not fit, so a colour legend over
      // sixty-character categories was drawn at full width and overflowed the chart around it — and
      // a host cannot compensate, because the untruncated labels are what the layout is computed
      // from. Applied below, after the encode parts are assembled, which is where upstream applies
      // it too.
      def.legend?.fields?.forEach { (key, value) ->
        if (key in LEGEND_PROPERTIES) put(key, asSignal(value))
      }
      if (gradient) {
        // A ramp is painted at the mark's own opacity, so a legend beside a chart of translucent
        // points is as translucent as they are — `gradient` in `legend/encode.ts`. Zero and absent
        // both mean "say nothing", since a legend drawn at zero opacity is not a legend.
        // `gradient` tests the opacity for **truth**, not for absence: a ramp is drawn at full
        // opacity where the mark is, and only a zero — which would be no ramp at all — is dropped.
        symbolOpacity(view)
          ?.takeIf { it != 0.0 }
          ?.let { opacity ->
            parts["gradient"] = obj {
              put("update", obj { put("opacity", obj { put("value", opacity) }) })
            }
          }
      } else {
        // A legend a selection is **bound to** is the control: its parts are named so the signals
        // can listen on them, made interactive, and faded where the category is not picked.
        val bound =
          view.selections.firstOrNull { selection ->
            selection.legendStreams.isNotEmpty() &&
              selection.legendField(view) == view.spec.fieldDef(channel)?.let { Fields.vgField(it) }
          }
        val swatches = symbolEncode(view, channel)
        if (bound == null) {
          swatches?.let { parts["symbols"] = obj { put("update", it) } }
        } else {
          (legendBindingEncode(view, bound, channel, swatches) as? VegaValue.Obj)
            ?.fields
            ?.forEach { (key, value) -> parts[key] = value }
        }
      }
      // Last, as `assembleLegend` does it, and for a reason: where an encode block already states
      // the labels' text — a custom number format type writes one — `datum.label` in the expression
      // means *that* text rather than the scale's, so the two compose instead of one replacing the
      // other. `withLabelText` is the axis's own function; the rule is the same on both guides.
      val own =
        if (parts.isEmpty()) null else obj { parts.forEach { (key, value) -> put(key, value) } }
      own?.let { put("encode", it) }
      // Carried rather than applied. `assembleLegend` destructures `labelExpr` off the component
      // and applies it to the **merged** legend, and the difference is not cosmetic: a line with a
      // point overlay is two layers, and only the point's legend has a swatch encode. Applied per
      // layer, the line's `{labels: …}` encode reached the merge first and the point's `{symbols:
      // …}` was dropped behind it — the swatch lost the overlay's white fill.
      def.legend?.string("labelExpr")?.let { put("labelExpr", str(it)) }
    }
  }

  /**
   * `parseInteractiveLegend`: the parts of a legend a selection picks through.
   *
   * Each of the three — the swatches, their labels and the rows they sit in — is **named**, so the
   * selection's signal can listen for a click on it, and made interactive, so the click reaches it
   * at all. The picked ones are drawn solid and the rest at `unselectedOpacity`, which is what
   * makes a legend read as a set of switches rather than as a key.
   */
  private fun legendBindingEncode(
    view: UnitView,
    selection: Selection,
    channel: String,
    swatches: VegaValue.Obj?,
  ): VegaValue {
    val field = selection.legendField(view) ?: return VegaValue.EmptyObject
    val prefix = selection.legendPartPrefix(field)
    val faded = view.config.raw.obj("legend")?.number("unselectedOpacity") ?: 0.35
    val test =
      "(!length(data(${quoted(selection.store)})) || " +
        "(${selection.name}[${quoted(field)}] && " +
        "indexof(${selection.name}[${quoted(field)}], datum.value) >= 0))"
    // A picked **swatch** is drawn at the mark's own opacity — a legend beside a chart of
    // translucent points is as translucent as they are — and a picked *label* is drawn solid, since
    // a word at seven tenths is only harder to read. `symbols` reads `opacity ?? 1` and `labels`
    // reads 1 outright.
    fun picked(selected: Double) =
      arr(
        listOf(
          obj {
            put("test", test)
            put("value", selected)
          },
          obj { put("value", faded) },
        )
      )
    val stated =
      view.spec.fieldDef(channel)?.legend?.number("symbolOpacity")
        ?: view.config.raw.obj("legend")?.number("symbolOpacity")
    val swatchOpacity = if (stated != null) 1.0 else symbolOpacity(view) ?: 1.0
    return obj {
      put(
        "labels",
        obj {
          put("name", "${prefix}_labels")
          put("interactive", VegaValue.Bool(true))
          put(
            "update",
            obj {
              put("opacity", picked(1.0))
              put("cursor", obj { put("value", "pointer") })
            },
          )
        },
      )
      put(
        "symbols",
        obj {
          put("name", "${prefix}_symbols")
          put("interactive", VegaValue.Bool(true))
          put(
            "update",
            obj {
              swatches?.fields?.forEach { (key, value) -> put(key, value) }
              put("opacity", picked(swatchOpacity))
              put("cursor", obj { put("value", "pointer") })
            },
          )
        },
      )
      put(
        "entries",
        obj {
          put("name", "${prefix}_entries")
          put("interactive", VegaValue.Bool(true))
          // A row is transparent and still catches the pointer, which is what makes the whole
          // entry clickable rather than only the swatch and its label.
          put(
            "update",
            obj {
              put("fill", obj { put("value", "transparent") })
              put("cursor", obj { put("value", "pointer") })
            },
          )
        },
      )
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
  /** `FILL_STROKE_CONFIG`: the paint properties a mark's configuration carries into its swatch. */
  private val FILL_STROKE_CONFIG =
    listOf(
      "stroke",
      "strokeWidth",
      "strokeDash",
      "strokeDashOffset",
      "strokeOpacity",
      "strokeJoin",
      "strokeMiterLimit",
      "fill",
      "fillOpacity",
    )

  private fun symbolEncode(view: UnitView, channel: String): VegaValue.Obj? {
    // `symbols` in `legend/encode.ts` opens with `markDef.filled && mark !== 'trail'`. A trail is
    // filled as a mark — it is a solid ribbon — but its swatch is a *stroke*, so its legend is read
    // as an unfilled one and needs no fill painted into the symbol at all.
    val filled = view.markDef.filled && view.spec.mark != "trail"
    val colors = Marks.colorEncode(view, filledOverride = filled)

    val fields = LinkedHashMap<String, VegaValue>()
    // `applyMarkConfig({}, model, FILL_STROKE_CONFIG)` comes first: the paint a *theme* settles for
    // every mark of this type settles the swatch too, so a bar outlined two units thick has a
    // swatch outlined two units thick. Only the properties Vega names, and only from the
    // configuration — the mark's own are already in the colour encoding below.
    val markConfig = view.config.markConfig(view.spec.mark)
    for (property in FILL_STROKE_CONFIG) {
      // The two colours are dropped again where *this* legend is the one explaining them: a swatch
      // cannot show a scale it is itself the key to. Upstream deletes them from the same block.
      if (property == "fill" && (channel == "fill" || (filled && channel == "color"))) continue
      if (property == "stroke" && (channel == "stroke" || (!filled && channel == "color"))) continue
      markConfig.fields[property]?.let { fields[property] = obj { put("value", it) } }
    }
    val fill = colors["fill"]
    if (fill != null && !(channel == "fill" || (filled && channel == "color"))) {
      when {
        // A swatch cannot resolve a *scaled* paint, so it is drawn in the legend's own base colour
        // at the mark's opacity.
        fill is VegaValue.Obj && fill.fields.containsKey("field") -> {
          fields["fill"] = obj { put("value", "black") }
          fields["fillOpacity"] = obj { put("value", symbolOpacity(view) ?: 1.0) }
        }
        // A **conditional** paint is a rule array, and the swatch takes the arm that is a plain
        // colour: a chart whose points are their category's colour only while picked is grey the
        // rest of the time, and grey is what the other legend's swatches are.
        fill is VegaValue.Arr ->
          firstConditionValue(view, "fill")?.let { fields["fill"] = obj { put("value", it) } }
        else -> fields["fill"] = fill
      }
    }
    val stroke = colors["stroke"]
    if (stroke != null && !(channel == "stroke" || (!filled && channel == "color"))) {
      when {
        stroke is VegaValue.Obj && stroke.fields.containsKey("field") -> Unit
        stroke is VegaValue.Arr ->
          firstConditionValue(view, "stroke")?.let { fields["stroke"] = obj { put("value", it) } }
        else -> fields["stroke"] = stroke
      }
    }

    // `const opacity = getMaxValue(encoding.opacity) ?? markDef.opacity; if (opacity) {…}` — the
    // opacity is tested for **truth**, so a mark drawn at zero has no swatch opacity written at all
    // rather than a swatch drawn at nothing. `point: "transparent"` on a line is exactly that: the
    // overlay is `{opacity: 0}`, and its legend is the line's own key.
    if (channel != "opacity") {
      symbolOpacityValue(view)
        ?.takeIf { it.isTruthy() }
        ?.let { fields["opacity"] = obj { put("value", it) } }
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

  /**
   * `getFirstConditionValue`: the plain colour a conditional paint falls back to.
   *
   * The fold starts at the channel's **unconditional** value and keeps the first thing defined, so
   * a colour written as `{"condition": {"param": …, "field": …}, "value": "grey"}` answers grey —
   * the arm a swatch can actually be painted in. The channel's own definition is consulted first
   * and `color` after it, a mark painted by `color` being painted on whichever of the two Vega
   * names it fills or strokes with.
   */
  private fun firstConditionValue(view: UnitView, channel: String): VegaValue? {
    val def = view.spec.encoding[channel] ?: view.spec.encoding["color"] ?: return null
    return def.value ?: def.conditions.firstNotNullOfOrNull { it.value }
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

  /**
   * How solid a swatch is drawn, which is the mark's own opacity where it has one.
   *
   * `getMaxValue` folds `Math.max` over the channel's conditions, **starting from the channel's own
   * unconditional value** — and a channel written as conditions alone has none, so the fold starts
   * at nothing and answers with nothing. Upstream then draws no swatch opacity at all rather than
   * falling back to the mark's: a legend cannot show a condition, and showing one arm of it as if
   * it were the whole would be a swatch that lies about half the marks.
   */
  private fun symbolOpacityValue(view: UnitView): VegaValue? {
    val def = view.spec.encoding["opacity"]
    if (def != null && def.conditions.isNotEmpty()) {
      val stated = (def.value as? VegaValue.Num)?.value ?: return null
      val conditioned =
        def.conditions.mapNotNull { (it.value as? VegaValue.Num)?.value }.maxOrNull()
      return maxOf(stated, conditioned ?: stated).takeIf { it != 0.0 }?.let { num(it) }
    }
    // Whatever the value **is**: `out.opacity = {value: opacity}` writes it through, so a mark that
    // says `"opacity": "1"` — a string, which a specification written by hand may well hold — gives
    // a swatch drawn at the string. Reading it as a number answered nothing and left the swatch
    // undrawn.
    return def?.value
      ?: view.markDef.raw.fields["opacity"]
      // The reduced scatter opacity, which is `markDef.opacity` by the time a legend reads it:
      // `initMarkDef` has already settled it, and it settles it from **both** opacities —
      // `specifiedOpacity === undefined && specifiedFillOpacity === undefined`. A mark that says
      // how solid its fill is has answered the question, so its swatch is not faded either, and
      // the two are read through the mark, its styles and the configuration alike. Asking a
      // narrower question here than the mark asks is how a swatch came out fainter than the mark
      // beside it.
      ?: if (
        view.spec.mark in setOf("point", "tick", "circle", "square") &&
          !Stack.isAggregate(view.spec) &&
          Marks.styled(view, "opacity") == null &&
          Marks.styled(view, "fillOpacity") == null
      ) {
        num(0.7)
      } else {
        null
      }
  }

  /** [symbolOpacityValue] as a number, for the places that compute with it rather than write it. */
  private fun symbolOpacity(view: UnitView): Double? =
    (symbolOpacityValue(view) as? VegaValue.Num)?.value
}
