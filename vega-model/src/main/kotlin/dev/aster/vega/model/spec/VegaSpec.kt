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

public sealed interface DomainSpec {
  /** An explicit domain written into the specification. */
  public data class Literal(val values: List<VegaValue>) : DomainSpec

  /** `{"data": "table", "field": "amount"}` — resolved from a dataset. */
  public data class FromField(val data: String, val field: String, val sort: Boolean = false) :
    DomainSpec

  /** `{"data": "table", "fields": [...]}` — the union of several fields. */
  public data class FromFields(val data: String, val fields: List<String>) : DomainSpec

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

public data class AxisSpec(
  val scale: String,
  val orient: Orient,
  val title: String? = null,
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
)

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
 * A mark definition.
 *
 * A `group` mark is also a scope: [data], [signals], [scales], [axes] and [marks] declared on it
 * are visible to its nested content and nowhere else, and may shadow same-named definitions outside
 * it.
 */
public data class MarkSpec(
  val type: MarkType,
  val name: String? = null,
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
  val zindex: Int = 0,
  val interactive: Boolean = true,
  val clip: Boolean = false,
)
