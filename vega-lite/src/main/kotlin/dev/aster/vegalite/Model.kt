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
  val bin: Binning? = null,
  val timeUnit: String? = null,
  val sort: VegaValue? = null,
  val stack: VegaValue? = null,
  val explicitTitle: VegaValue? = null,
) {
  val isFieldDef: Boolean = field != null || aggregate == "count"

  val isValueDef: Boolean = value != null

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
) {
  val mark: String
    get() = markDef.type

  fun channel(name: String): ChannelDef? = encoding[name]

  /** The definition that carries a field, ignoring value-only entries. */
  fun fieldDef(name: String): ChannelDef? = encoding[name]?.takeIf { it.isFieldDef }
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
      "angle",
      "theta",
      "radius",
    )

  val POSITION_CHANNELS = listOf("x", "y")

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
      "angle",
    )

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

  /** Channels whose definition may be an array, so the parser has to keep every entry. */
  val MULTI_DEF_CHANNELS = setOf("detail", "order", "tooltip")
}

internal fun channelIsPosition(channel: String): Boolean = channel == "x" || channel == "y"

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
