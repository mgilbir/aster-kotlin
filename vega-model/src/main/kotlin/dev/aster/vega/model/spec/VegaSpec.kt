package dev.aster.vega.model.spec

import dev.aster.vega.model.VegaValue

/**
 * Parsed Vega specification.
 *
 * These are plain immutable models: parsing resolves syntax and reports what it cannot represent,
 * and makes no layout or scale decisions. Anything the parser could not honour appears as a
 * diagnostic on [ParsedSpec], never as a silent default.
 *
 * The model covers the subset the runtime can currently execute. Every enum has an explicit
 * "unsupported" path so an unknown value from a specification becomes a diagnostic rather than a
 * parse failure or a wrong guess.
 */
public data class VegaSpec(
  val width: Double?,
  val height: Double?,
  val padding: Padding,
  val autosize: Autosize,
  val background: String?,
  val signals: List<SignalSpec>,
  val data: List<DataSpec>,
  val scales: List<ScaleSpec>,
  val axes: List<AxisSpec>,
  val legends: List<LegendSpec>,
  val title: TitleSpec?,
  /** Automatic grid placement for this scope's group-mark cells. */
  val layout: LayoutSpec?,
  val marks: List<MarkSpec>,
)

/**
 * A numeric property that may be written directly or supplied by a signal.
 *
 * Vega allows `{"signal": "..."}` almost anywhere a literal is accepted, so a parser that only
 * reads numbers silently drops those and the chart lays out with a default. Modelling both forms
 * keeps the substitution visible.
 */
public sealed interface NumberValue {
  public data class Constant(val value: Double) : NumberValue

  public data class Signal(val expression: String) : NumberValue
}

/**
 * A named signal.
 *
 * Vega resolves a signal's value as `update` if present, otherwise `init`, otherwise `value` —
 * verified against upstream, where `{value: 5, update: "99"}` yields 99. Resolution follows
 * dependency order, not declaration order.
 *
 * @param on event-stream handlers, which need the interaction system and are reported as
 *   unsupported.
 */
public data class SignalSpec(
  val name: String,
  val value: VegaValue? = null,
  val init: String? = null,
  val update: String? = null,
  val on: List<VegaValue> = emptyList(),
  val bind: VegaValue? = null,
) {
  /** The expression that produces this signal's value for a static render, if any. */
  public val expression: String?
    get() = update ?: init
}

/** Space around the chart. Vega accepts a single number or per-side values. */
public data class Padding(
  val left: Double = 0.0,
  val top: Double = 0.0,
  val right: Double = 0.0,
  val bottom: Double = 0.0,
) {
  public companion object {
    public val Default: Padding = Padding(5.0, 5.0, 5.0, 5.0)

    public fun uniform(value: Double): Padding = Padding(value, value, value, value)
  }
}

public enum class AutosizeType {
  /** Grow the surface so the chart plus its overflow fits. Vega's default. */
  PAD,
  /** Shrink the plotting area so the total stays at the declared width and height. */
  FIT,
  FIT_X,
  FIT_Y,
  /** Use the declared size verbatim and let content overflow. */
  NONE,
}

public data class Autosize(
  val type: AutosizeType = AutosizeType.PAD,
  val resize: Boolean = false,
  val contains: String = "content",
) {
  public companion object {
    public val Default: Autosize = Autosize()
  }
}

/** A named dataset. */
public data class DataSpec(
  val name: String,
  /** Inline values. `null` when the data comes from [url]. */
  val values: List<VegaValue>? = null,
  val url: String? = null,
  /** Raw transform definitions, resolved by the runtime so it can report unsupported operators. */
  val transform: List<VegaValue> = emptyList(),
  val source: String? = null,
  /**
   * `format.parse`: how to read each named field, e.g. `{"when": "date"}`.
   *
   * Only the coercion matters here, not the input syntax — JSON has no date type, so a date arrives
   * as a string or a number and something has to say which fields to convert.
   */
  val parse: Map<String, String> = emptyMap(),
)

public enum class ScaleType {
  LINEAR,
  LOG,
  POW,
  SQRT,
  SYMLOG,
  TIME,
  UTC,
  ORDINAL,
  BAND,
  POINT,
  SEQUENTIAL,
  QUANTILE,
  QUANTIZE,
  THRESHOLD,
  BIN_ORDINAL,
  IDENTITY;

  public val isContinuous: Boolean
    get() = this in setOf(LINEAR, LOG, POW, SQRT, SYMLOG, TIME, UTC, SEQUENTIAL)

  public val isDiscrete: Boolean
    get() = this in setOf(ORDINAL, BAND, POINT, BIN_ORDINAL)

  public companion object {
    /** Returns `null` for an unrecognized name so the caller can emit a diagnostic. */
    public fun fromName(name: String): ScaleType? =
      when (name.lowercase()) {
        "linear" -> LINEAR
        "log" -> LOG
        "pow" -> POW
        "sqrt" -> SQRT
        "symlog" -> SYMLOG
        "time" -> TIME
        "utc" -> UTC
        "ordinal" -> ORDINAL
        "band" -> BAND
        "point" -> POINT
        "sequential" -> SEQUENTIAL
        "quantile" -> QUANTILE
        "quantize" -> QUANTIZE
        "threshold" -> THRESHOLD
        "bin-ordinal" -> BIN_ORDINAL
        "identity" -> IDENTITY
        else -> null
      }
  }
}

/**
 * How a discrete domain is ordered.
 *
 * Upstream builds a discrete domain by *grouping* the dataset on the domain field, not by listing
 * its values, so `sort` orders those groups. That is why an aggregate sort may name a field the
 * domain never mentions: the aggregate is computed over each group on the way past. An explicit
 * domain array is never sorted, whatever `sort` says.
 */
public sealed interface DomainSort {
  public val descending: Boolean

  /**
   * `sort: true`, or an object naming neither `op` nor `field`: order by the domain value itself.
   */
  public data class ByValue(override val descending: Boolean = false) : DomainSort

  /**
   * `sort: {"op": "sum", "field": "amount"}`: order by an aggregate computed per distinct domain
   * value. A `count` needs no field; every other operation does.
   */
  public data class ByAggregate(
    val op: String,
    val field: String?,
    override val descending: Boolean = false,
  ) : DomainSort
}

public sealed interface DomainSpec {
  /** An explicit domain written into the specification. */
  public data class Literal(val values: List<VegaValue>) : DomainSpec

  /** `{"data": "table", "field": "amount"}` — resolved from a dataset. */
  public data class FromField(
    val data: String,
    val field: String,
    val sort: DomainSort? = null,
  ) : DomainSpec

  /** `{"data": "table", "fields": [...]}` — the union of several fields. */
  public data class FromFields(
    val data: String,
    val fields: List<String>,
    val sort: DomainSort? = null,
  ) : DomainSpec

  /**
   * `{"signal": "..."}` — an expression producing the domain array.
   *
   * Common in practice because the `extent` transform publishes exactly this: a two-element array a
   * scale can be pointed straight at.
   */
  public data class FromSignal(val expression: String) : DomainSpec

  public data object Unset : DomainSpec
}

public sealed interface RangeSpec {
  public data class Literal(val values: List<VegaValue>) : RangeSpec

  /** `"width"`, `"height"` and friends, resolved against the chart size. */
  public data class Named(val name: String) : RangeSpec

  /** A named colour scheme. */
  public data class Scheme(val name: String, val count: Int? = null) : RangeSpec

  public data object Unset : RangeSpec
}

public data class ScaleSpec(
  val name: String,
  val type: ScaleType,
  val domain: DomainSpec,
  /**
   * `domainMin`/`domainMax` **replace** an end of the resolved domain rather than clamping it, and
   * they run after `zero`, which is how `domainMin: 30` beats the zero that would otherwise have
   * pulled the domain down. Upstream does not correct a minimum above the maximum either.
   */
  val domainMin: NumberValue? = null,
  val domainMax: NumberValue? = null,
  /** Inserts a third domain value, for a diverging scale with a three-colour range. */
  val domainMid: NumberValue? = null,
  val range: RangeSpec,
  val reverse: Boolean = false,
  val round: Boolean = false,
  val clamp: Boolean = false,
  val nice: Boolean = false,
  val niceCount: Int? = null,
  /** `null` means "not stated", which matters because the default differs by scale type. */
  val zero: Boolean? = null,
  val padding: NumberValue? = null,
  val paddingInner: NumberValue? = null,
  val paddingOuter: NumberValue? = null,
  val align: NumberValue? = null,
  /** Log base; defaults to 10. */
  val base: NumberValue? = null,
  /** Power-scale exponent; defaults to 1 for `pow` and 0.5 for `sqrt`. */
  val exponent: NumberValue? = null,
  /** Symlog constant; defaults to 1. */
  val constant: NumberValue? = null,
  /** Colour interpolation space for a colour range, e.g. `"rgb"` or `"lab"`. */
  val interpolate: String? = null,
)

public enum class Orient {
  LEFT,
  RIGHT,
  TOP,
  BOTTOM;

  public val isVertical: Boolean
    get() = this == LEFT || this == RIGHT

  public companion object {
    public fun fromName(name: String): Orient? =
      when (name.lowercase()) {
        "left" -> LEFT
        "right" -> RIGHT
        "top" -> TOP
        "bottom" -> BOTTOM
        else -> null
      }
  }
}

/** Where a guide's title is anchored along the guide. */
public enum class Anchor {
  START,
  MIDDLE,
  END;

  public companion object {
    public fun fromName(name: String): Anchor? =
      when (name.lowercase()) {
        "start" -> START
        "middle" -> MIDDLE
        "end" -> END
        else -> null
      }
  }
}

/**
 * The chart title, and its subtitle.
 *
 * @param frame what the title is positioned against. `"group"` means the plotting area; anything
 *   else — including the default — means the whole drawing, so a title centres over the chart *and
 *   its axes* rather than over the plotting area alone.
 */
public data class TitleSpec(
  /** The literal text, or empty when [textExpression] supplies it instead. */
  val text: String,
  /**
   * An expression producing the text.
   *
   * A trellis header labels its row with `{"signal": "parent.r"}`, so a title whose words come from
   * the data is not an edge case — it is how the commonest use of a group title works.
   */
  val textExpression: String? = null,
  val subtitle: String? = null,
  val orient: Orient = Orient.TOP,
  val anchor: Anchor = Anchor.MIDDLE,
  val frame: String? = null,
  val offset: NumberValue? = null,
  val subtitlePadding: NumberValue? = null,
  val fontSize: NumberValue? = null,
  val subtitleFontSize: NumberValue? = null,
  val zindex: Int = 0,
)

public data class AxisSpec(
  val scale: String,
  val orient: Orient,
  val title: String? = null,
  val titlePadding: NumberValue? = null,
  val titleFontSize: NumberValue? = null,
  val titleAnchor: Anchor? = null,
  val grid: Boolean = false,
  val ticks: Boolean = true,
  val labels: Boolean = true,
  val domainLine: Boolean = true,
  val tickCount: NumberValue? = null,
  val tickSize: NumberValue? = null,
  val labelPadding: NumberValue? = null,
  val labelFontSize: NumberValue? = null,
  val offset: NumberValue? = null,
  val zindex: Int = 0,
  /** Appearance of the four parts, each defaulting to Vega's own when unstated. */
  val labelStyle: GuideStroke = GuideStroke(),
  val tickStyle: GuideStroke = GuideStroke(),
  val gridStyle: GuideStroke = GuideStroke(),
  val domainStyle: GuideStroke = GuideStroke(),
  val titleStyle: GuideStroke = GuideStroke(),
)

/**
 * The colour, weight and transparency of one part of a guide.
 *
 * One type for all five parts of an axis because upstream treats them alike: `labelColor`,
 * `tickColor`, `gridColor`, `domainColor` and `titleColor` are the same property with a different
 * prefix, and so are the widths, dashes and opacities. A label uses [color] as a fill and the rest
 * use it as a stroke, which is the only asymmetry.
 */
public data class GuideStroke(
  val color: String? = null,
  val width: Double? = null,
  val dash: List<Double>? = null,
  val opacity: Double? = null,
  val font: String? = null,
  val fontWeight: String? = null,
  val fontStyle: String? = null,
)

/**
 * Which form a legend takes.
 *
 * A specification usually leaves this unstated, and upstream then derives it from the scale: a
 * continuous colour scale gets a gradient, a discretizing one gets banded swatches, and anything
 * else gets one symbol per entry.
 */
public enum class LegendType {
  SYMBOL,
  GRADIENT,
  /** Banded swatches for a quantize, quantile or threshold scale. */
  DISCRETE;

  public companion object {
    public fun fromName(name: String): LegendType? =
      when (name.lowercase()) {
        "symbol" -> SYMBOL
        "gradient" -> GRADIENT
        "discrete" -> DISCRETE
        else -> null
      }
  }
}

/**
 * Where a legend sits.
 *
 * The four edges place the legend outside the plotting area; the four corners place it inside it.
 * [NONE] disables placement entirely, so `legendX` and `legendY` position it by hand.
 */
public enum class LegendOrient {
  LEFT,
  RIGHT,
  TOP,
  BOTTOM,
  TOP_LEFT,
  TOP_RIGHT,
  BOTTOM_LEFT,
  BOTTOM_RIGHT,
  NONE;

  public val isSide: Boolean
    get() = this == LEFT || this == RIGHT || this == TOP || this == BOTTOM

  public companion object {
    public fun fromName(name: String): LegendOrient? =
      when (name.lowercase()) {
        "left" -> LEFT
        "right" -> RIGHT
        "top" -> TOP
        "bottom" -> BOTTOM
        "top-left" -> TOP_LEFT
        "top-right" -> TOP_RIGHT
        "bottom-left" -> BOTTOM_LEFT
        "bottom-right" -> BOTTOM_RIGHT
        "none" -> NONE
        else -> null
      }
  }
}

public enum class Direction {
  VERTICAL,
  HORIZONTAL;

  public companion object {
    public fun fromName(name: String): Direction? =
      when (name.lowercase()) {
        "vertical" -> VERTICAL
        "horizontal" -> HORIZONTAL
        else -> null
      }
  }
}

/**
 * A legend.
 *
 * At least one of [fill], [stroke], [size], [shape] or [opacity] names the scale being described; a
 * legend with none of them cannot say what it is a legend for, and is rejected.
 */
public data class LegendSpec(
  val fill: String? = null,
  val stroke: String? = null,
  val size: String? = null,
  val shape: String? = null,
  val opacity: String? = null,
  /** `null` means "derive from the scale type", which is what a specification usually wants. */
  val type: LegendType? = null,
  val orient: LegendOrient = LegendOrient.RIGHT,
  /** `null` means the per-orient default: vertical at the sides, horizontal above and below. */
  val direction: Direction? = null,
  val title: String? = null,
  /** Explicit entry values, overriding whatever the scale would generate. */
  val values: List<VegaValue>? = null,
  val tickCount: NumberValue? = null,
  val offset: NumberValue? = null,
  val padding: NumberValue? = null,
  val titlePadding: NumberValue? = null,
  val titleFontSize: NumberValue? = null,
  val labelFontSize: NumberValue? = null,
  val labelOffset: NumberValue? = null,
  val symbolType: String? = null,
  val symbolSize: NumberValue? = null,
  val symbolStrokeWidth: NumberValue? = null,
  val gradientLength: NumberValue? = null,
  val gradientThickness: NumberValue? = null,
  val rowPadding: NumberValue? = null,
  val columnPadding: NumberValue? = null,
  /** How many entries per row. `null` means one column vertically, one row horizontally. */
  val columns: NumberValue? = null,
  /** Absolute placement, used when [orient] is [LegendOrient.NONE]. */
  val legendX: NumberValue? = null,
  val legendY: NumberValue? = null,
  val zindex: Int = 0,
  /**
   * Appearance of the three parts, read the same way an axis reads its own.
   *
   * A symbol's is the one that behaves differently: `symbolOpacity` becomes the item's overall
   * opacity rather than a fill or stroke opacity, which is what upstream emits.
   */
  val labelStyle: GuideStroke = GuideStroke(),
  val titleStyle: GuideStroke = GuideStroke(),
  val symbolStyle: GuideStroke = GuideStroke(),
) {
  /**
   * The scale this legend describes.
   *
   * Upstream picks the first channel present in this order and calls it the canonical scale, so a
   * legend encoding both `fill` and `size` is titled and sized from the fill scale.
   */
  public val scale: String?
    get() = fill ?: stroke ?: size ?: shape ?: opacity

  /** How many channels this legend maps, which is what decides whether the type can be derived. */
  public val channelCount: Int
    get() = listOfNotNull(fill, stroke, size, shape, opacity).size
}

public enum class MarkType {
  ARC,
  AREA,
  GROUP,
  IMAGE,
  LINE,
  PATH,
  RECT,
  RULE,
  SHAPE,
  SYMBOL,
  TEXT,
  TRAIL;

  public companion object {
    public fun fromName(name: String): MarkType? =
      when (name.lowercase()) {
        "arc" -> ARC
        "area" -> AREA
        "group" -> GROUP
        "image" -> IMAGE
        "line" -> LINE
        "path" -> PATH
        "rect" -> RECT
        "rule" -> RULE
        "shape" -> SHAPE
        "symbol" -> SYMBOL
        "text" -> TEXT
        "trail" -> TRAIL
        else -> null
      }
  }
}

/** Where a mark's data comes from. */
public data class FromSpec(val data: String? = null, val facet: FacetSpec? = null)

/**
 * A group mark's facet definition: `{"facet": {"name": "cell", "data": "table", "groupby":
 * "cat"}}`.
 *
 * One group is produced per distinct combination of the [groupby] fields, in the order those
 * combinations first appear in [data]. Each group's datum is the groupby fields plus a `count`, and
 * its partition of the rows is bound to [name] as a dataset the nested marks can read.
 *
 * That datum shape is not a convention this engine chose: upstream implements faceting by inserting
 * an `aggregate` transform with the same `groupby`, so the group sees an aggregate tuple.
 */
public data class FacetSpec(val name: String, val data: String, val groupby: List<String>)

/**
 * One channel's value in an encode block.
 *
 * Vega allows a constant, a datum field, a scaled value, a band offset, or an expression.
 * Expressions are modelled but not evaluated yet, so the runtime reports them rather than guessing.
 */
public sealed interface ChannelValue {
  public data class Constant(val value: VegaValue) : ChannelValue

  public data class Field(val path: String) : ChannelValue

  /**
   * A scaled channel.
   *
   * Exactly one of [field], [value] or [signal] supplies the input. [band] adds a fraction of the
   * scale's bandwidth, which is how Vega expresses `{"scale": "x", "band": 1}` for a bar's width.
   */
  public data class Scaled(
    val scale: String,
    val field: String? = null,
    val value: VegaValue? = null,
    val band: Double? = null,
    val offset: Double? = null,
  ) : ChannelValue

  public data class Signal(val expression: String) : ChannelValue

  /**
   * An array of production rules, as in `[{"test": "...", "value": 1}, {"value": 0}]`.
   *
   * The first rule whose test passes wins; a rule with no test always passes, so a trailing
   * unguarded rule acts as the default.
   */
  public data class Conditional(val rules: List<ConditionalRule>) : ChannelValue
}

/** One entry in a [ChannelValue.Conditional]. A `null` [test] always passes. */
public data class ConditionalRule(val test: String?, val production: ChannelValue)

public typealias EncodeEntry = Map<String, ChannelValue>

public data class EncodeSpec(
  val enter: EncodeEntry = emptyMap(),
  val update: EncodeEntry = emptyMap(),
  val exit: EncodeEntry = emptyMap(),
  val hover: EncodeEntry = emptyMap(),
) {
  /**
   * The channels in effect for a static render: `enter` overridden by `update`.
   *
   * Vega applies `enter` once and `update` on every pass, so for a single render the effective set
   * is the merge with `update` winning.
   */
  public val effective: EncodeEntry
    get() = if (update.isEmpty()) enter else enter + update
}

/**
 * A group mark's automatic grid placement.
 *
 * Given a `layout`, a group's cells are positioned by the grid rather than by their own `x` and
 * `y`, which is how small multiples avoid computing cell positions by hand.
 */
public data class LayoutSpec(
  /** Cells per row. `null` puts them all in one row, as upstream does. */
  val columns: NumberValue? = null,
  val rowPadding: NumberValue? = null,
  val columnPadding: NumberValue? = null,
)

/**
 * A mark definition.
 *
 * A `group` mark is also a scope: [data], [signals], [scales], [axes] and [marks] declared on it
 * are visible to its nested content and nowhere else, and may shadow same-named definitions outside
 * it.
 */
public data class MarkSpec(
  val type: MarkType,
  val name: String? = null,
  /**
   * What this mark is *for*, which only matters inside a `layout`.
   *
   * A group marked `row-header` or `column-title` is not a cell of the grid; it labels one.
   * Upstream reads the same property to decide which marks the grid places and which it arranges
   * around it.
   */
  val role: String? = null,
  val from: FromSpec? = null,
  val encode: EncodeSpec = EncodeSpec(),
  /** Nested marks, for a group mark. */
  val marks: List<MarkSpec> = emptyList(),
  val axes: List<AxisSpec> = emptyList(),
  /** Datasets scoped to this group. */
  val data: List<DataSpec> = emptyList(),
  /** Signals scoped to this group. */
  val signals: List<SignalSpec> = emptyList(),
  /** Scales scoped to this group, resolved against the group's own data. */
  val scales: List<ScaleSpec> = emptyList(),
  val legends: List<LegendSpec> = emptyList(),
  /** Automatic grid placement for this group's cells. */
  val layout: LayoutSpec? = null,
  /** A title for this group, which is how a trellis header labels its row or column. */
  val title: TitleSpec? = null,
  val zindex: Int = 0,
  val interactive: Boolean = true,
  val clip: Boolean = false,
)
