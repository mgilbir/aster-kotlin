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
  /**
   * The chart's own group item: the frame every mark is drawn inside.
   *
   * Upstream wraps a specification's marks in a root group and encodes it from this block, which is
   * how a polar chart moves the origin to the middle of the plot — `{"x": radius, "y": radius}` and
   * every coordinate afterwards is measured from the centre. Without it a radar chart draws around
   * (0,0) with three of its four quadrants off the surface.
   */
  val encode: EncodeSpec = EncodeSpec(),
  /**
   * What the chart *is*, for a reader who cannot see it.
   *
   * Every fixture in this repository already carries one and nothing read it. A screen reader
   * meeting a chart with none announces its marks and never says what they are marks of, which is
   * the difference between a list of numbers and a chart.
   */
  val description: String? = null,
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
  val on: List<SignalHandler> = emptyList(),
  val bind: VegaValue? = null,
) {
  /** The expression that produces this signal's value for a static render, if any. */
  public val expression: String?
    get() = update ?: init
}

/**
 * One `on` entry of a signal: what makes it fire, and what it becomes when it does.
 *
 * A handler is driven by an **event** or by another **signal or scale changing**, and upstream
 * treats those as the same kind of thing — both are sources that push a new value. That is why
 * `events` accepts `{"signal": "width"}` alongside `"click"`, and why a chart can be made reactive
 * without any events at all.
 */
public data class SignalHandler(
  val streams: List<EventStream> = emptyList(),
  /** Signals whose change fires this handler, from `{"signal": "..."}` entries in `events`. */
  val signalSources: List<String> = emptyList(),
  /** Scales whose change fires this handler, from `{"scale": "..."}` entries in `events`. */
  val scaleSources: List<String> = emptyList(),
  val update: SignalUpdate? = null,
  /** `encode` sets properties on the event's item instead of producing a value. */
  val encode: VegaValue? = null,
  /**
   * Re-runs everything downstream even when the value has not changed.
   *
   * Needed when the value is an object that was mutated in place rather than replaced — without it
   * the equality check upstream performs would decide nothing had happened.
   */
  val force: Boolean = false,
)

/** What a fired handler sets the signal to. */
public sealed interface SignalUpdate {
  /** An expression, either written bare or as `{"expr": "..."}`. */
  public data class Expression(val expr: String) : SignalUpdate

  /** A literal, from `{"value": ...}`. */
  public data class Constant(val value: VegaValue) : SignalUpdate

  /** Another signal's current value, from `{"signal": "..."}`. */
  public data class Reference(val name: String) : SignalUpdate
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
  /**
   * `format.type`: how to read what the URL returned — `json`, `csv`, `tsv` or `dsv`.
   *
   * Defaults to `json`, which is also what upstream infers from a `.json` extension. A tabular
   * format is not a lesser JSON: every cell arrives as a string, so `format.parse` is the only
   * thing that makes a CSV column numeric, and a specification that forgets it gets a scale over
   * strings rather than a wrong chart.
   */
  val formatType: String? = null,
  /** `format.property`: the field inside a JSON document that actually holds the rows. */
  val property: String? = null,
  /** `format.delimiter` for a `dsv` file. */
  val delimiter: String? = null,
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
   * `{"fields": [{"data": …, "field": …}, …]}` — the union of parts from **different** datasets.
   *
   * Distinct from [FromFields], which unions several columns of one dataset. This is how a chart
   * puts two independently-computed series on one axis, and how a histogram shares an axis with the
   * count of its own null values. A part may also be a literal array, so a specification can widen
   * a data-driven domain to include a fixed range.
   */
  public data class Union(val parts: List<DomainSpec>, val sort: DomainSort? = null) : DomainSpec

  /**
   * `{"signal": "..."}` — an expression producing the domain array.
   *
   * Common in practice because the `extent` transform publishes exactly this: a two-element array a
   * scale can be pointed straight at.
   */
  public data class FromSignal(val expression: String) : DomainSpec

  public data object Unset : DomainSpec
}

/**
 * How a specification says *which* colours a scheme supplies.
 *
 * Three forms, because upstream accepts three: a name, a signal holding a name — how a chart offers
 * a palette picker — and the stops written out inline, which is a scheme in every respect except
 * that nobody named it.
 */
public sealed interface SchemeRef {
  public data class Named(val name: String) : SchemeRef

  public data class Signal(val expression: String) : SchemeRef

  /** `{"scheme": ["#67000d", ...]}` — the stops themselves, interpolated like any other ramp. */
  public data class Colors(val values: List<VegaValue>) : SchemeRef
}

public sealed interface RangeSpec {
  public data class Literal(val values: List<VegaValue>) : RangeSpec

  /** `"width"`, `"height"` and friends, resolved against the chart size. */
  public data class Named(val name: String) : RangeSpec

  /** A colour scheme, named outright, chosen by a signal, or written out as its stops. */
  public data class Scheme(val scheme: SchemeRef, val count: Int? = null) : RangeSpec

  /**
   * `{"signal": "..."}` — the whole range comes from a signal.
   *
   * Resolved when the scale is built rather than when it is parsed, because a signal may itself be
   * derived from the data the scale is over.
   */
  public data class Signal(val expression: String) : RangeSpec

  /**
   * `{"step": 20}` — a band scale sized by its band rather than by the space available.
   *
   * The chart's width then follows from the number of categories, which is what a specification
   * wants when the bars must stay a fixed width however many there are.
   */
  public data class Step(val step: NumberValue) : RangeSpec

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
  /**
   * Explicit tick values, replacing the ones the scale would generate.
   *
   * Not a filter and not a suggestion: whatever survives being mapped into the scale's range is
   * what the axis draws, and the gridlines follow it.
   */
  val values: List<VegaValue>? = null,
  /**
   * `labelOverlap`: `"parity"`, `"greedy"`, `true` (which means parity) or `false`.
   *
   * Absent on an axis means no removal, which is upstream's default — the `labelOverlap: true` in
   * its config sits in the `legend` block, not the `axis` one.
   */
  val labelOverlap: String? = null,
  val labelSeparation: NumberValue? = null,
  /**
   * `labelAngle`, in degrees, turning each label about its own anchor.
   *
   * Only the angle: upstream leaves the alignment and baseline at whatever the orientation gives
   * them, so a 45-degree label on a bottom axis is still centred and top-baselined and therefore
   * hangs to the left of its tick. [labelAlign] and [labelBaseline] are how that is corrected, and
   * they are what Vega-Lite emits alongside an angle.
   */
  val labelAngle: NumberValue? = null,
  val labelAlign: String? = null,
  val labelBaseline: String? = null,
  /** How wide a label may be drawn before it is truncated. Vega's axis default is 180. */
  val labelLimit: NumberValue? = null,
  /**
   * A d3-format specifier for the tick labels, which is how a price axis reads `$1.50`.
   *
   * Only meaningful on a numeric scale: a discrete domain's labels are its own values and upstream
   * coerces them to strings without consulting this.
   */
  val format: String? = null,
  /**
   * Where along a band a tick and its gridline sit, as a fraction: 0 the start, 0.5 the centre.
   *
   * Only ticks and gridlines. A *label* is always at the band's centre upstream — `axis-labels.js`
   * writes `band: 0.5` outright — so a chart that wants its labels moved as well says so in an
   * `encode` block, which is what Vega's own budget example does.
   */
  val bandPosition: NumberValue? = null,
  /**
   * The `encode` blocks as written, per part, for the channels that are not another spelling of a
   * property.
   *
   * Most of a guide's `encode` folds into the properties beside it — `encode.grid.enter.strokeDash`
   * *is* `gridDash` — and folding is what lets it take part in measurement. A few channels have no
   * property behind them, and a label's **position** is the one that matters: Vega's own budget
   * example moves its labels off the band centre with `{"scale": "x", "field": "value"}`, which no
   * property can say. Those are kept here and resolved against the tick they belong to.
   */
  val encode: Map<String, EncodeSpec> = emptyMap(),
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
  /** Unlike an axis, a legend removes overlapping labels by default; `false` switches it off. */
  val labelOverlap: String? = null,
  val labelSeparation: NumberValue? = null,
  /** As for an axis, but Vega's legend default is 160. */
  val labelLimit: NumberValue? = null,
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
/**
 * Where a channel's input comes from, which is not always the row being drawn.
 *
 * Vega lets a `field` be a string or an object, and the object forms reach outside the current
 * datum. They are not exotic: `{"group": "height"}` is how a mark spans the group it is drawn in,
 * and it appears in six of the official examples. Reading them all as "a field of this row" would
 * look up a column that does not exist and silently draw nothing.
 */
public sealed interface FieldRef {
  /** `"field": "amount"` — a column of the row being drawn. */
  public data class Plain(val path: String) : FieldRef

  /** `{"group": "height"}` — a property of the enclosing group, usually its size. */
  public data class Group(val path: String) : FieldRef

  /** `{"parent": "key"}` — a column of the facet datum that produced this group. */
  public data class Parent(val path: String) : FieldRef

  /** `{"signal": "..."}` — the *name* of the column comes from a signal. */
  public data class Signal(val expression: String) : FieldRef

  /** `{"datum": "which"}` — the name of the column is itself held in a column. */
  public data class Datum(val path: String) : FieldRef

  /** The literal column name, for the plain case that most callers only ever see. */
  public val plainPath: String?
    get() = (this as? Plain)?.path
}

public sealed interface ChannelValue {
  public data class Constant(val value: VegaValue) : ChannelValue

  public data class Field(val ref: FieldRef) : ChannelValue

  /**
   * A scaled channel.
   *
   * Exactly one of [field], [value] or [signal] supplies the input. [band] adds a fraction of the
   * scale's bandwidth, which is how Vega expresses `{"scale": "x", "band": 1}` for a bar's width.
   */
  public data class Scaled(
    val scale: String,
    /**
     * *Which* scale to use, when the specification does not name it outright.
     *
     * Takes the same four forms as a field reference, and for the same reasons: a control may
     * switch an axis between a linear and a log scale (`signal`), a faceted cell may use the scale
     * its parent chose (`parent`), and a parallel-coordinates plot picks a different scale per row
     * (`datum`). Resolved in the encoder, since none of those is known until then.
     */
    val scaleRef: FieldRef? = null,
    val field: FieldRef? = null,
    val value: VegaValue? = null,
    /**
     * An expression supplying the value the scale is applied to.
     *
     * `{"scale": "x", "signal": "currentYear"}` is how a chart puts a mark where a *control* says,
     * rather than where a datum says — a draggable handle, a threshold rule. Upstream builds the
     * base value from `signal`, `field` or `value` and then wraps whichever it found in the scale,
     * so a signal beside a scale is scaled like anything else; reading the signal and stopping
     * there puts the mark at the raw domain value, which on a band scale over years is 2010 pixels
     * from the left.
     */
    val signal: String? = null,
    val band: Double? = null,
  ) : ChannelValue

  /**
   * A channel with Vega's arithmetic applied on top of it.
   *
   * Every value reference — scaled or not — accepts `exponent`, `mult`, `offset` and `round`, and
   * upstream applies them in that order after the scale, as `round(pow(v, e) * m + o)`. Modelling
   * them as a wrapper rather than as fields on the scaled form is what lets `{"field": "x2",
   * "offset": -5}` work, which is how a label is placed just inside the end of the bar it belongs
   * to and which was previously read as a plain field with the offset dropped.
   *
   * Each of the three is itself a value reference, so `{"field": "y", "offset": {"field": "height",
   * "mult": 0.5}}` centres a label in a band whose height only the datum knows.
   */
  public data class Adjusted(
    val base: ChannelValue,
    val exponent: ChannelValue? = null,
    val mult: ChannelValue? = null,
    val offset: ChannelValue? = null,
    val round: Boolean = false,
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
  /**
   * `aria: false` hides this mark from a screen reader entirely.
   *
   * The escape hatch for anything decorative — a background, a rule that only guides the eye. A
   * chart with no way to say "ignore this" makes a reader listen to its scaffolding.
   */
  val aria: Boolean = true,
  val clip: Boolean = false,
  /**
   * Appearance defaults from `config`, either side of the engine's own built-in per-type block.
   *
   * Two maps rather than one because the built-ins sit between them: `config.mark` loses to a
   * rect's blue and `config.rect` beats it, which is upstream's ordering and is not what the names
   * suggest.
   */
  val configBelowDefaults: Map<String, VegaValue> = emptyMap(),
  val configAboveDefaults: Map<String, VegaValue> = emptyMap(),
)
