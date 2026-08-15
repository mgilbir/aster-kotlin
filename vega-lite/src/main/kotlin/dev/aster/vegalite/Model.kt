package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * The parsed Vega-Lite pieces this compiler works from.
 *
 * Upstream keeps a class hierarchy of models with mutable component maps; this keeps plain data and
 * builds the Vega specification in one pass, because the subset here is a single view or a layer of
 * them rather than an arbitrarily nested composition. What is *not* simplified is naming: a channel
 * definition resolves to the same Vega field name upstream would give it, since that name appears
 * in the data transforms, the scale domains and the mark encodings alike, and one deviation shows
 * up everywhere at once.
 */
internal enum class MeasureType(val jsonName: String) {
  QUANTITATIVE("quantitative"),
  TEMPORAL("temporal"),
  ORDINAL("ordinal"),
  NOMINAL("nominal");

  val isDiscrete: Boolean
    get() = this == ORDINAL || this == NOMINAL

  companion object {
    fun from(name: String?): MeasureType? = entries.firstOrNull { it.jsonName == name }
  }
}

/** How a field is binned: not at all, by this compiler, or already by the data. */
internal sealed interface Binning {
  /** `"bin": true` or `"bin": {...}` — the transform is ours to emit. */
  data class Bin(val params: VegaValue.Obj) : Binning

  /** `"bin": "binned"` — the data arrives binned and only the scale changes. */
  data object PreBinned : Binning
}

/**
 * One entry of an `encoding` block, after the shorthand forms are resolved.
 *
 * A definition is one of three kinds and they are mutually exclusive upstream: a *field* def reads
 * a column, a *datum* def pins one data value, and a *value* def sets a literal graphic property
 * that never touches a scale.
 */
internal data class ChannelDef(
  val channel: String,
  val raw: VegaValue.Obj,
  val field: String? = null,
  val datum: VegaValue? = null,
  val value: VegaValue? = null,
  val type: MeasureType? = null,
  val aggregate: String? = null,
  /**
   * The column an `argmin`/`argmax` is taken over, which is not the column being read.
   *
   * `{"aggregate": {"argmax": "US Gross"}, "field": "Production Budget"}` asks for the production
   * budget *of* the highest-grossing row: the aggregate produces a whole row under `argmax_US
   * Gross`, and the field names which of its columns to read.
   */
  val argumentField: String? = null,
  val bin: Binning? = null,
  val timeUnit: String? = null,
  val sort: VegaValue? = null,
  val stack: VegaValue? = null,
  val explicitTitle: VegaValue? = null,
  /**
   * `condition` — the definitions that apply only when their own test passes, in order.
   *
   * Each is an ordinary channel definition with a [test] beside it, because a condition may name a
   * field, a datum or a value exactly as the unconditional part does; upstream builds both through
   * the same function and only prepends the test (`compile/mark/encode/conditional.ts`).
   */
  val conditions: List<ChannelDef> = emptyList(),
  /**
   * Whether the channel was written as a **list**, however many entries it held.
   *
   * It is the writing that decides a tooltip's shape, not the count: `[{"field": "a"}]` builds the
   * `{title: expression}` object a multi-field tooltip does, where the same definition written bare
   * builds the expression alone. One entry is where the two spellings disagree.
   */
  val isList: Boolean = false,
  /** The expression this definition is gated on, when it is one of a channel's [conditions]. */
  val test: String? = null,
  /**
   * The rest of a channel written as a *list* — `tooltip`, `detail` and `order` take one.
   *
   * The first entry is the definition proper, because everything that reads a channel reads one
   * definition; these are the others, and dropping them loses every field but the first from a
   * tooltip and every grouping but the first from a series.
   */
  val siblings: List<ChannelDef> = emptyList(),
) {
  val isFieldDef: Boolean = field != null || aggregate == "count"

  val isValueDef: Boolean = value != null

  /**
   * Whether this definition says nothing about what to draw.
   *
   * It is what is left of a **shared** encoding a layer never filled in: a chart that writes
   * `{"type": "quantitative", "axis": {…}}` above its layers so that each need only name its field
   * leaves that behind on any layer that names none. Upstream's `getFieldDef` answers nothing for
   * it, and everything downstream reads the channel as unencoded — which is what makes a rule with
   * no `y` span the plot rather than sit halfway up it.
   */
  val isBlank: Boolean =
    field == null &&
      datum == null &&
      value == null &&
      aggregate == null &&
      // `isOrderOnlyDef`: an `order` that states only how to sort is a definition in its own right,
      // and the only thing it has to say is the thing it says.
      sort == null &&
      stack == null &&
      conditions.isEmpty() &&
      siblings.isEmpty()

  /**
   * `impute` — how the gaps in this series are filled, when the specification says to fill them.
   *
   * Kept as written: the parameters go straight into Vega's own `impute` transform, and the one
   * thing decided here is which of the two positions is being filled and which is the key.
   */
  val impute: VegaValue.Obj?
    get() = raw["impute"] as? VegaValue.Obj

  /** The `scale` a user wrote, or null when they wrote none. `scale: null` disables it entirely. */
  val scale: VegaValue.Obj?
    get() = raw["scale"] as? VegaValue.Obj

  val scaleDisabled: Boolean
    get() = raw.fields["scale"] == VegaValue.Null

  val axis: VegaValue.Obj?
    get() = raw["axis"] as? VegaValue.Obj

  val axisDisabled: Boolean
    get() = raw.fields["axis"] == VegaValue.Null

  val legend: VegaValue.Obj?
    get() = raw["legend"] as? VegaValue.Obj

  val legendDisabled: Boolean
    get() = raw.fields["legend"] == VegaValue.Null

  val format: VegaValue?
    get() = raw["format"] ?: axis?.get("format") ?: legend?.get("format")

  val formatType: String?
    get() = raw.string("formatType") ?: axis.string("formatType") ?: legend.string("formatType")

  /** `type: "quantitative"` and not binned — the shape that decides stacking and zero-baselines. */
  val isUnbinnedQuantitative: Boolean =
    isFieldDef && type == MeasureType.QUANTITATIVE && bin == null
}

/** The mark, with the defaults upstream fills in before anything else reads them. */
internal data class MarkDef(
  val type: String,
  val raw: VegaValue.Obj,
  val filled: Boolean,
  val orient: String? = null,
) {
  fun prop(name: String): VegaValue? = raw.fields[name]

  fun string(name: String): String? = raw.string(name)

  fun number(name: String): Double? = raw.number(name)

  fun boolean(name: String): Boolean? = raw.boolean(name)
}

/** A single view: one mark and its encoding, plus whatever data and size it was given. */
internal class UnitSpec(
  val markDef: MarkDef,
  val encoding: Map<String, ChannelDef>,
  val data: VegaValue?,
  val transforms: List<VegaValue>,
  val width: VegaValue?,
  val height: VegaValue?,
  /**
   * The parameters this **unit** declares, which is where a selection is defined.
   *
   * `unit.ts` reads `spec.params` off the unit model and nowhere else: a selection belongs to the
   * one view it was declared in, and that view's name is the `unit` every picked tuple records.
   */
  val params: List<VegaValue> = emptyList(),
) {
  val mark: String
    get() = markDef.type

  fun channel(name: String): ChannelDef? = encoding[name]

  /** The definition that carries a field, ignoring value-only entries. */
  /**
   * `getFieldDef`: the definition a channel names a column in, **condition included**.
   *
   * A channel written entirely as a test still names a column — a colour that is the weather only
   * where a row was picked, and grey otherwise — and everything that asks a channel what column it
   * reads has to find it: the scale, and the selection projected onto that channel. Reading only
   * the unconditional part left a click over a colour legend remembering nothing at all.
   */
  fun fieldDef(name: String): ChannelDef? =
    encoding[name]?.let { def ->
      if (def.isFieldDef) def else def.conditions.firstOrNull { it.isFieldDef }
    }
}

internal object Channels {
  const val X = "x"
  const val Y = "y"
  const val X2 = "x2"
  const val Y2 = "y2"

  /**
   * Every channel, in the order upstream normalizes an encoding into (`UNIT_CHANNEL_INDEX`).
   *
   * The order is observable: scales, axes and the accessibility description all come out in it.
   */
  val UNIT_CHANNELS =
    listOf(
      "x",
      "y",
      "x2",
      "y2",
      "theta",
      "theta2",
      "radius",
      "radius2",
      "longitude",
      "latitude",
      "longitude2",
      "latitude2",
      "xOffset",
      "yOffset",
      "color",
      "fill",
      "stroke",
      "time",
      "opacity",
      "fillOpacity",
      "strokeOpacity",
      "strokeWidth",
      "strokeDash",
      "size",
      "angle",
      "shape",
      "order",
      "text",
      "detail",
      "key",
      "tooltip",
      "href",
      "url",
      "description",
    )

  /** Every channel that can own a scale, in the order upstream iterates them. */
  val SCALE_CHANNELS =
    listOf(
      "x",
      "y",
      "xOffset",
      "yOffset",
      "color",
      "fill",
      "stroke",
      "opacity",
      "fillOpacity",
      "strokeOpacity",
      "strokeWidth",
      "size",
      "shape",
      "strokeDash",
      "angle",
      "theta",
      "radius",
      // The clock a chart is **animated** by is a scale like any other: a band over the column the
      // frames run through, stepped at the frame rate. Nothing is drawn with it — it is read by the
      // signals that advance the frame — which is why it is last, and why it has no guide.
      "time",
    )

  /**
   * The channels a `sort` may name as a shorthand — `"-x"`, `"color"`.
   *
   * `SORT_BY_CHANNELS` upstream. Two of the scale channels are missing on purpose: a `sort` can
   * name neither offset, since an offset already sits inside the order being decided.
   */
  val SORT_BY_CHANNELS =
    setOf(
      "x",
      "y",
      "color",
      "fill",
      "stroke",
      "opacity",
      "fillOpacity",
      "strokeOpacity",
      "strokeWidth",
      "size",
      "shape",
      "strokeDash",
      "text",
    )

  val POSITION_CHANNELS = listOf("x", "y")

  /**
   * The channels a concatenation scales separately for each of its plots.
   *
   * `defaultScaleResolve` in `compile/resolve.ts`: two plots side by side measure separate things
   * along their own axes, so `x`, `y` and their polar equivalents become `concat_0_x` beside
   * `concat_1_x` — but everything a legend stands for stays shared, so one colour key covers the
   * whole chart rather than one appearing under every plot.
   */
  val POSITION_SCALE_CHANNELS = listOf("x", "y", "theta", "radius")

  /** Channels that draw a legend when they are scaled. `shape` included, `detail` never scaled. */
  val LEGEND_CHANNELS =
    listOf(
      "color",
      "fill",
      "stroke",
      "size",
      "shape",
      "opacity",
      "strokeWidth",
      "strokeDash",
    )

  /**
   * The channels a legend can *be* — `LEGEND_SCALE_CHANNELS` in `legend.ts`.
   *
   * A swatch the legend paints by scale must not also be painted by a value, so anything named here
   * is taken out of the symbol encoding once the legend has been assembled.
   */
  val LEGEND_SCALE_CHANNELS =
    setOf("size", "shape", "fill", "stroke", "strokeDash", "strokeWidth", "opacity")

  val NONPOSITION_CHANNELS =
    listOf(
      "color",
      "fill",
      "stroke",
      "opacity",
      "fillOpacity",
      "strokeOpacity",
      "strokeWidth",
      "size",
      "shape",
      "angle",
      "detail",
      "key",
      "text",
      "tooltip",
      "href",
      "url",
      "description",
      "order",
    )

  /** The channels that grid a chart into cells rather than encoding anything within one. */
  /**
   * The channels that split a chart into cells rather than placing anything inside one.
   *
   * `facet` is the wrapped form's channel: a facet operator over a single field is written above
   * the view, and moves into the encoding here so that everything downstream sees one construct.
   */
  val FACET_CHANNELS = listOf("row", "column", "facet")

  /** Channels whose definition may be an array, so the parser has to keep every entry. */
  val MULTI_DEF_CHANNELS = setOf("detail", "order", "tooltip")
}

/**
 * The marks that join their rows into one shape, which is why the *order* of those rows matters to
 * them and to nothing else.
 */
internal val PATH_MARKS = setOf("line", "area", "trail")

/**
 * How far off the middle of its bucket a rect sits, or null where it sits in the middle.
 *
 * `getBandPosition` for a bucketed field with no far end of its own; anything but a half means the
 * rect is drawn between two *interpolated* edges rather than the bucket's own.
 */
internal fun UnitView.offsettedRectPosition(def: ChannelDef, channel: String): Double? {
  if (channel != "x" && channel != "y") return null
  // A column that arrived **already bucketed** is a bucket like any other: `offsetedRectFormulas`
  // runs on both branches of `timeunit.ts`, and a `bandPosition` moves the rect within its bucket
  // whether this compiler cut it or the data came that way.
  if (def.timeUnit == null) return null
  // `rectBandPosition` is set only for a **rect-based** mark: it is what is drawn *between* two
  // interpolated edges, and a label beside one is placed at a single position. The columns are not
  // computed for anything else, so nothing else may name them either.
  if (spec.mark !in RECT_BASED_MARKS) return null
  if (secondaryChannel(channel)?.let { spec.encoding[it] } != null) return null
  val stated =
    def.raw.number("bandPosition")
      ?: config.markConfig(spec.mark).number("timeUnitBandPosition")
      ?: return null
  return stated.takeIf { it != 0.5 }
}

/** `isRectBasedMark`: the marks drawn *between* two positions rather than at one. */
internal val RECT_BASED_MARKS = setOf("rect", "bar", "image", "arc", "tick")

/**
 * The aggregates that compare their input rather than accumulating it.
 *
 * Every other operation coerces on the way through; these two would answer with the alphabetically
 * smallest of a column of numerals still held as text.
 */
internal val MIN_MAX_OPS = setOf("min", "max")

/**
 * The aggregates that count rather than measure, and so cannot produce an invalid value.
 *
 * `COUNTING_OPS` in `aggregate.ts`. Each answers with how many rows met a condition, which is a
 * number whatever the column held.
 */
internal val COUNTING_OPS = setOf("count", "valid", "missing", "distinct")

internal fun channelIsPosition(channel: String): Boolean = channel == "x" || channel == "y"

/** `xOffset` nests inside `x`, which is what puts several bars inside one band. */
internal fun offsetChannelFor(channel: String): String? =
  when (channel) {
    "x" -> "xOffset"
    "y" -> "yOffset"
    else -> null
  }

/** The polar pair, which positions an arc the way `x`/`y` position a rect. */
internal fun channelIsPolar(channel: String): Boolean = channel == "theta" || channel == "radius"

/** `x` pairs with `x2`, `y` with `y2` — the secondary channel that makes a mark ranged. */
internal fun secondaryChannel(channel: String): String? =
  when (channel) {
    "x" -> "x2"
    "y" -> "y2"
    "theta" -> "theta2"
    "radius" -> "radius2"
    else -> null
  }

internal fun mainChannel(channel: String): String =
  when (channel) {
    "x2" -> "x"
    "y2" -> "y"
    "theta2" -> "theta"
    "radius2" -> "radius"
    else -> channel
  }
