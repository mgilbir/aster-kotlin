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
  /** Cartographic projections this scope defines, by name. */
  val projections: List<ProjectionSpec> = emptyList(),
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
    /**
     * **Zero**, which is upstream's `config.padding`, and not the 5 that every example writes.
     *
     * Nearly every specification in Vega's own gallery sets `"padding": 5` for itself, which is why
     * a wrong default here survived so long: it only shows on a chart that leaves it out, and then
     * it shows as ten units of surface nobody asked for.
     */
    public val Default: Padding = Padding(0.0, 0.0, 0.0, 0.0)

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
  NONE;

  /**
   * The three types whose plotting area is decided by measuring the drawing, not by the
   * declaration.
   *
   * They are the only ones that need a chart compiled twice: once to find out how far it reaches,
   * and once at the size that leaves.
   */
  public val isFit: Boolean
    get() = this == FIT || this == FIT_X || this == FIT_Y
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
  /** Inline values, when they are written as an array of rows. */
  val values: List<VegaValue>? = null,
  /**
   * Inline values written as a whole *document* rather than as rows.
   *
   * A GeoJSON `FeatureCollection` or a TopoJSON topology, reached through the same [format] a url
   * would use. Kept separate from [values] because the two need different handling and because a
   * dataset has one or the other, never both.
   */
  val document: VegaValue? = null,
  val url: String? = null,
  /**
   * `{"url": {"signal": "..."}}` — the address itself comes from a signal.
   *
   * How a chart lets a control choose its dataset: a dropdown of distributions, a year picker that
   * swaps the file. Resolved when the data is, so the signal has to be one that does not itself
   * depend on data — which is the same rule every other pre-data signal follows.
   */
  val urlSignal: String? = null,
  /** Raw transform definitions, resolved by the runtime so it can report unsupported operators. */
  val transform: List<VegaValue> = emptyList(),
  /**
   * The dataset or datasets this one starts from, in order.
   *
   * A list because upstream accepts one: `"source": ["a", "b"]` concatenates both, in the order
   * written, and everything after it — a transform pipeline, a scale domain — sees the whole. A
   * labelled donut builds its label positions that way, joining a left-hand run and a right-hand
   * one into a single dataset to place.
   */
  val sources: List<String> = emptyList(),
  /**
   * `format.parse`: how to read each named field, e.g. `{"when": "date"}`.
   *
   * Only the coercion matters here, not the input syntax — JSON has no date type, so a date arrives
   * as a string or a number and something has to say which fields to convert.
   */
  val parse: Map<String, String> = emptyMap(),
  /**
   * `format.parse: "auto"` — work out each column's type from its values.
   *
   * Upstream tries boolean, then integer, then number, then date, and keeps the first that holds
   * for every value in the column; anything else stays a string. A CSV has no types at all and a
   * JSON log writes its dates as text, so without this a time axis is a column of strings.
   */
  val parseAuto: Boolean = false,
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
  /** `format.feature`: which object of a TopoJSON file to decode, as one feature per geometry. */
  val feature: String? = null,
  /** `format.mesh`: the same object's arcs as a single line string, each drawn once. */
  val mesh: String? = null,
  /** `format.filter` for a mesh: `interior` for the shared borders, `exterior` for the outline. */
  val meshFilter: String? = null,
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

  /**
   * `{"data": "table", "field": "amount"}` — resolved from a dataset.
   *
   * The column is a [FieldRef] rather than a name because upstream's `Scope.fieldRef` accepts
   * `{"signal": ...}` here, and that is how a chart offers a measure picker: one scale over
   * whichever column the control selected. It is the *name* the signal supplies, so it is one
   * lookup, not two — the same distinction a `{"scale": {...}}` reference makes.
   */
  public data class FromField(
    val data: String,
    val field: FieldRef,
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
  public data class Scheme(
    val scheme: SchemeRef,
    val count: Int? = null,
    /** `{"count": {"signal": "levels"}}` — a chart whose reader chooses how many buckets. */
    val countSignal: String? = null,
  ) : RangeSpec

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

  /**
   * `{"data": "clusters", "field": "name"}` — the range values come from a column.
   *
   * The mirror image of a data-driven *domain*, and it is how an ordinal scale becomes a lookup
   * table the data itself defines: `id` in, `name` out, both read from the same rows. A chart that
   * labels a cluster by its number does exactly this, and there is no array to write down because
   * the values are only known once the data has loaded.
   */
  public data class FromField(val data: String, val field: String) : RangeSpec

  public data object Unset : RangeSpec
}

/**
 * `bins` — the boundaries a scale's ticks and labels land on.
 *
 * Three forms, because upstream accepts three: the boundaries written out, a `{start, stop, step}`
 * description they are generated from, and a signal holding either. The `bin` transform publishes
 * exactly the middle one, so a histogram points its axis straight at what the binning chose.
 *
 * Setting it does more than move the ticks: upstream's `includeZero` is `!scale.bins && ...`, so a
 * scale with bins loses the `zero` default a linear scale otherwise has.
 */
public sealed interface BinsSpec {
  /** `[1, 2, 3]` — the boundaries themselves. An element may be a signal reference. */
  public data class Values(val values: List<VegaValue>) : BinsSpec

  /** `{"start": 1, "stop": 10, "step": 1}`, each of which may be signal-valued. */
  public data class Steps(
    val start: NumberValue? = null,
    val stop: NumberValue? = null,
    val step: NumberValue? = null,
  ) : BinsSpec

  /** `{"signal": "bins"}` — resolves to either of the other two once the signal has a value. */
  public data class Signal(val expression: String) : BinsSpec
}

public data class ScaleSpec(
  val name: String,
  val type: ScaleType,
  val domain: DomainSpec,
  /**
   * A domain to use exactly as given, whatever the rest of the scale says: `domainRaw`.
   *
   * It short-circuits `zero`, the three `domain*` overrides and `nice` — which is what makes an
   * interactive zoom work, since a brush publishes the interval it wants and nothing may round it
   * outwards. Almost always a signal, and almost always null until a reader touches the chart, so
   * an unresolvable one means "no override" and not "empty domain".
   */
  val domainRaw: DomainSpec? = null,
  /**
   * `domainImplicit`: an ordinal value nobody declared **joins** the domain instead of being
   * unknown.
   *
   * d3 spells it by setting the scale's `unknown` to its `implicit` sentinel, and the effect is
   * that the domain grows as the scale is used. Off by default because order of use then decides
   * which colour a value gets; it is for a domain nobody can write down in advance.
   */
  val domainImplicit: Boolean = false,
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
  /**
   * `reverse: {"signal": "..."}` — a control flips the scale.
   *
   * A timeline that can run right-to-left is written this way, and there is nothing constant to
   * write down. Resolved when the scale is built, like every other signal-valued scale property.
   */
  val reverseSignal: String? = null,
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
  /**
   * Colour interpolation space for a colour range: `rgb`, `lab`, `hcl`, `hsl`, `cubehelix`, and the
   * `-long` variant of the last three.
   *
   * Vega also accepts the object form `{"type": "rgb", "gamma": 2.2}`, whose type is read here and
   * whose gamma is reported: only `rgb` has one in d3, and it changes the ramp's midpoint rather
   * than its ends.
   */
  val interpolate: String? = null,
  /**
   * `interpolate: {"type": "rgb", "gamma": y}` — the only interpolator d3 gives a gamma.
   *
   * It bends the ramp's **middle** and leaves both ends where they were, which is why a chart that
   * asked for one and got the plain ramp looks composed and is wrong exactly where nobody checks.
   */
  val interpolateGamma: Double? = null,
  /** Explicit bin boundaries; see [BinsSpec]. */
  val bins: BinsSpec? = null,
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
  /**
   * `fontWeight`, which a theme sets far more often than a title does.
   *
   * It changes the *measurement*, not only the look: a heading set at 500 rather than the default
   * bold is narrower, and on a chart wide enough for its title to decide the surface that is the
   * difference between matching upstream and being a unit out.
   */
  val fontWeight: String? = null,
  /**
   * `fontStyle`/`subtitleFontStyle` — `"italic"` or `"normal"`.
   *
   * Vega's own subtitle style in several examples, and it is not decoration alone: an italic face
   * is measured as well as drawn, so a chart whose subtitle is its widest line is a different size
   * without it.
   */
  val fontStyle: String? = null,
  val subtitleFontStyle: String? = null,
  /**
   * The colour of the words, `color`, and of the subtitle's, `subtitleColor`.
   *
   * Separate properties rather than one inherited: a chart that greys its subtitle sets only the
   * second, and reading the first for both would darken it.
   */
  val color: String? = null,
  /** The face the heading is set in. Claimed as consumed since the theme work and never read. */
  val font: String? = null,
  val subtitleColor: String? = null,
  val subtitleFont: String? = null,
  val subtitleFontWeight: String? = null,
  /** The gap between lines, for a heading long enough to have more than one. */
  val lineHeight: NumberValue? = null,
  val subtitleLineHeight: NumberValue? = null,
  /**
   * An explicit `align`, `angle` and `baseline`, each overriding what [anchor] and [orient] imply.
   *
   * Upstream writes the derived values into the title's `enter` block and these into `update`, so
   * an explicit one wins — which is how a left-hand title is made to read up the page rather than
   * down.
   */
  val align: String? = null,
  val angle: NumberValue? = null,
  val baseline: String? = null,
  /** How wide the words may be drawn before they are truncated. */
  val limit: NumberValue? = null,
  /**
   * `aria: false` takes the heading out of the accessibility tree, `name` names its mark, and
   * `interactive: false` makes it ignore the pointer.
   *
   * A decorative heading — a watermark, a chart drawn twice with one copy labelled — is exactly
   * what `aria: false` is for, and it is the only way to say it.
   */
  val aria: Boolean = true,
  val name: String? = null,
  val interactive: Boolean = true,
  /**
   * `dx`/`dy` — a nudge applied after the anchor has placed the title.
   *
   * Written either as a property or inside the title's own `encode.update`, which is where a
   * specification puts it when it wants a heading optically aligned with the axis beneath it. One
   * unit, and it moves the edge of the whole surface with it.
   */
  val dx: NumberValue? = null,
  val dy: NumberValue? = null,
  val subtitleFontSize: NumberValue? = null,
  val zindex: Int = 0,
  /**
   * `encode`, keyed by the part it addresses: `group`, `title` or `subtitle`.
   *
   * Upstream also accepts the **deprecated** form, where a block naming none of those three applies
   * to the title's *text* — which is the form a specification writing `encode.update.dx` is using,
   * and it is normalised to `title` here so there is one shape to read.
   */
  val encode: Map<String, EncodeSpec> = emptyMap(),
)

public data class AxisSpec(
  val scale: String,
  val orient: Orient,
  val title: String? = null,
  /**
   * `title: {"signal": "..."}` — the axis names itself from a signal.
   *
   * A chart offering a choice of measure retitles its axis with the choice, and there is nothing
   * constant to write down.
   */
  val titleExpression: String? = null,
  val titlePadding: NumberValue? = null,
  val titleFontSize: NumberValue? = null,
  val titleAnchor: Anchor? = null,
  /** How wide the axis title may be drawn before it is truncated. */
  val titleLimit: NumberValue? = null,
  /**
   * Where the axis sits **along its own direction**, `position`.
   *
   * Not the same as [offset], which moves it away from the plot: a bottom axis's `position` slides
   * it left and right, and `offset` slides it down.
   */
  val position: NumberValue? = null,
  /**
   * The whole axis group's nudge onto the pixel grid, `translate`. `null` means Vega's half pixel.
   *
   * Zero is a real value here and not the absence of one, which is why this is nullable: a chart
   * exported for print sets `translate: 0` so the lines land on whole coordinates.
   */
  val translate: NumberValue? = null,
  /**
   * Whether a tick's coordinate is rounded to a whole unit. Vega's default is `true`.
   *
   * `false` leaves it where the scale put it, which is what a chart drawn at a fractional device
   * ratio wants — rounding to whole units there moves a tick by up to half a device pixel.
   */
  val tickRound: Boolean? = null,
  /**
   * `tickBand`, which is three properties in one: `"extent"` sets [bandPosition] to 1, turns
   * [tickExtra] on and zeroes `tickOffset`, so a band scale's ticks land on the band **edges**
   * instead of their centres. `"center"` sets the three back to their defaults.
   */
  val tickBand: String? = null,
  /**
   * How far a label is nudged **along** the axis, `labelOffset`.
   *
   * Not `labelPadding`, which moves it away from the axis. Upstream extends the shared band offset
   * with this one, so a band axis's label keeps its centring and slides.
   */
  val labelOffset: NumberValue? = null,
  /**
   * A floor on the gap between ticks, `tickMinStep`.
   *
   * Applied by reducing the tick *count* until the step d3 would choose reaches it — there is no
   * way to ask d3 for a step directly, and asking for fewer ticks is how upstream does it too.
   */
  val tickMinStep: NumberValue? = null,
  /**
   * `labelBound`: how far a label may hang past the scale's range before it is dropped.
   *
   * `true` means upstream's one unit and a number means itself; `false` and absence both mean no
   * bounding at all. Zero is a real value — bound exactly to the range — which is why this is
   * nullable rather than defaulting to zero.
   */
  val labelBound: Double? = null,
  /**
   * `aria: false` hides the whole guide from a screen reader, and `description` replaces the
   * caption this engine would otherwise generate for it.
   *
   * Both belong to the guide as a whole rather than to any part of it, which is why they are here
   * and not in a [GuideStroke].
   */
  val aria: Boolean = true,
  val description: String? = null,
  val grid: Boolean = false,
  val ticks: Boolean = true,
  val labels: Boolean = true,
  val domainLine: Boolean = true,
  val tickCount: NumberValue? = null,
  val tickSize: NumberValue? = null,
  val labelPadding: NumberValue? = null,
  val labelFontSize: NumberValue? = null,
  val offset: NumberValue? = null,
  /**
   * `offset` written as a full value reference rather than a number — `{"scale": "ord", "value":
   * "Cylinders", "mult": -1}`.
   *
   * That is how a parallel-coordinates plot places one axis per column: every axis is `orient:
   * "left"` and each is pushed across by the position its own name scales to. There is no number to
   * write down, because the positions come from the data.
   */
  val offsetChannel: ChannelValue? = null,
  /**
   * Absolute placement and orientation for the axis title, overriding what the anchor would choose.
   *
   * All five are in the axis group's own coordinates, so `titleX: -2` on a left axis pushed to x =
   * 2.5 draws the title at 0.5. A parallel-coordinates plot sets them in `config.axisY` to lay
   * every column's title flat along the bottom instead of turned up the side.
   */
  val titleX: NumberValue? = null,
  val titleY: NumberValue? = null,
  val titleAngle: NumberValue? = null,
  val titleAlign: String? = null,
  val titleBaseline: String? = null,
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
   * Only meaningful on a numeric scale unless [formatType] says otherwise: a discrete domain's
   * labels are its own values and upstream coerces them to strings without consulting this.
   */
  val format: String? = null,
  /**
   * `format: {"signal": "..."}` — the specifier is chosen while the chart runs.
   *
   * A chart whose time granularity is bound to a control has no constant to write down: the format
   * follows the units, and `timeUnitSpecifier` is what turns one into the other.
   */
  val formatExpression: String? = null,
  /**
   * `formatType`: which grammar [format] is written in — `number`, `time` or `utc`.
   *
   * It overrides what the scale would have chosen, and that is the point: a `band` scale over
   * instants has no formatter of its own, so `time` is the only thing that makes its labels read as
   * dates rather than as epoch milliseconds.
   */
  val formatType: String? = null,
  /**
   * Where along a band a tick and its gridline sit, as a fraction: 0 the start, 0.5 the centre.
   *
   * Only ticks and gridlines. A *label* is always at the band's centre upstream — `axis-labels.js`
   * writes `band: 0.5` outright — so a chart that wants its labels moved as well says so in an
   * `encode` block, which is what Vega's own budget example does.
   */
  val bandPosition: NumberValue? = null,
  /**
   * How far a tick and its gridline are nudged along the axis, after [bandPosition] has placed it.
   *
   * Zero on an ordinary axis and **-0.5 on a band one**, which is upstream's `config.axisBand`
   * correcting the half-pixel the axis group's own translation adds. A specification that wants
   * ticks on the band boundaries sets `bandPosition: 1` *and* `tickOffset: 0`, because the
   * correction would otherwise pull them half a pixel off the edge they were aimed at.
   */
  val tickOffset: NumberValue? = null,
  /**
   * `tickExtra` — one more tick, pegged to the *start* of the first band.
   *
   * A band axis draws one tick per band, so ticks placed at the band ends leave the very first edge
   * unmarked. Upstream appends a datum carrying only `{extra: {value: <first tick's value>}}`,
   * which the scaled-value codegen reads as "that value's band start, with no bandwidth added". Its
   * **label** is empty and lands nowhere: the label mark does not pass `extra` on, so it scales a
   * value the datum does not have, and upstream's scene records a `NaN` position for it.
   */
  val tickExtra: Boolean = false,
  /**
   * `gridScale` — a second scale whose **range** the gridlines span, instead of the plotting area.
   *
   * How a chart draws a grid across a cell that is not the size of its own plot: the gridline runs
   * the length of the *other* axis's scale rather than the group's `width` or `height`. Upstream
   * spans `range(gridScale)[0]` to `range(gridScale)[1]`, which is why the two ends come out in the
   * other scale's own order and not always low-to-high.
   */
  val gridScale: String? = null,
  /**
   * `labelFlush` — how close to an end of the scale's range a label has to be to be pushed inwards.
   *
   * A threshold in pixels, and `true` means one. A label within it of the range's start is aligned
   * to its *left* rather than centred, and one within it of the end to its right, so the first and
   * last labels sit inside the plot instead of hanging off the corners. Null is upstream's "off",
   * and **zero is not**: a zero threshold still flushes a label that lands exactly on an end.
   */
  val labelFlush: Double? = null,
  /**
   * `labelFlushOffset` — how far a flushed label is nudged along the axis, once flushed.
   *
   * Signed the way upstream signs it, which is *outwards*: a label flushed to the start moves back
   * towards it and one flushed to the end moves past it. Applied only where the flush rule decided
   * the alignment — an explicit `labelAlign` on a horizontal axis, or `labelBaseline` on a vertical
   * one, means the label is not being flushed and there is nothing to nudge.
   */
  val labelFlushOffset: NumberValue? = null,
  /**
   * `minExtent`/`maxExtent` — how deep the axis is allowed to be, whatever its contents measure.
   *
   * Upstream clamps its measured depth into this range with defaults of 0 and 200, so a chart with
   * one very long label does not lose half its plot to the axis.
   */
  val minExtent: NumberValue? = null,
  val maxExtent: NumberValue? = null,
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
  /** Where in the dash pattern the line starts; see `Stroke.dashOffset`. */
  val dashOffset: Double? = null,
  /** `butt`, `round` or `square` — how the ends of a tick or a gridline are finished. */
  val cap: String? = null,
  val opacity: Double? = null,
  val font: String? = null,
  val fontWeight: String? = null,
  val fontStyle: String? = null,
  /** For a text part: an explicit alignment, overriding whatever the guide's geometry implies. */
  val align: String? = null,
  val baseline: String? = null,
  val lineHeight: Double? = null,
  /**
   * The fields a specification wrote as a `{"signal": ...}`, keyed by the field's own name.
   *
   * Kept beside the constants rather than folded into their types, so nothing downstream changes
   * shape: the builders substitute the resolved values into a copy of this block once, before
   * anything reads it. The keys are the property names of this class — `"color"`, `"width"` —
   * because one map is easier to keep complete than a dozen parallel nullable fields, and being
   * complete is the point: half of these worked and half were silently dropped, which is a
   * difference a specification cannot see.
   */
  val signals: Map<String, String> = emptyMap(),
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
 * At least one of [fill], [stroke], [size], [shape], [strokeWidthScale], [strokeDashScale] or
 * [opacity] names the scale being described; a legend with none of them cannot say what it is a
 * legend for, and is rejected.
 */
public data class LegendSpec(
  val fill: String? = null,
  val stroke: String? = null,
  val size: String? = null,
  val shape: String? = null,
  val opacity: String? = null,
  /**
   * The `strokeWidth` and `strokeDash` **channels**, which on a legend name scales like the rest.
   *
   * Spelled apart from the legend background's own [backgroundStrokeWidth] and
   * [backgroundStrokeDash] because the two meanings collide on one property name: a legend's
   * `strokeWidth` names a scale, while the outline drawn round the legend takes its width from
   * `config.legend` alone. Keyed to one of these, each swatch is drawn at its own width or under
   * its own dash pattern — the natural legend for a chart that distinguishes series by line style.
   */
  val strokeWidthScale: String? = null,
  val strokeDashScale: String? = null,
  /**
   * `gridAlign` — how the entry grid lines its columns and rows up.
   *
   * `config.legend` defaults it to `each`, which is why it is not simply absent: the entry grid's
   * row **centring** is conditional on being aligned at all, so `none` both packs the columns
   * tightly and stops a short entry being centred against a tall one.
   */
  val gridAlign: String? = null,
  /** `null` means "derive from the scale type", which is what a specification usually wants. */
  val type: LegendType? = null,
  val orient: LegendOrient = LegendOrient.RIGHT,
  /** `null` means the per-orient default: vertical at the sides, horizontal above and below. */
  val direction: Direction? = null,
  val title: String? = null,
  /**
   * `title: {"signal": "..."}` — the legend names itself from a signal.
   *
   * The same shape an axis title takes, and for the same reason: a chart whose measure is chosen by
   * a control has no constant to write down.
   */
  val titleExpression: String? = null,
  /** Explicit entry values, overriding whatever the scale would generate. */
  val values: List<VegaValue>? = null,
  /**
   * A d3-format specifier for the entry labels, which is how a ramp over fractions reads `6%`.
   *
   * Resolved against the *span* the legend covers, as upstream's `formatSpan` does: a specifier
   * naming no precision takes as many decimals as the tick step needs, not d3's fixed six.
   */
  val format: String? = null,
  val tickCount: NumberValue? = null,
  val offset: NumberValue? = null,
  val padding: NumberValue? = null,
  val titlePadding: NumberValue? = null,
  /**
   * `titleOrient` — which side of the entries the title sits on.
   *
   * `"top"` by default. `"left"` turns a legend into a labelled strip: the title runs down the
   * left, vertically centred against the entries, and every entry is pushed past it. It also
   * changes the title's own anchoring — upstream reads a left or right title as `middle`-anchored
   * where a top one is `start`-anchored — so it is not only a translation.
   */
  val titleOrient: String? = null,
  /**
   * `titleAnchor` — where along the entries the title sits.
   *
   * A top title runs along their width and takes its alignment from the anchor, so `end` puts the
   * title's right edge at theirs. A left title runs down their height and takes its *baseline* from
   * the anchor instead, staying left-aligned; there a multi-line title is anchored by its last
   * line.
   */
  val titleAnchor: Anchor? = null,
  /** How wide the title may be drawn before it is truncated. Upstream's legend default is 180. */
  val titleLimit: NumberValue? = null,
  val titleFontSize: NumberValue? = null,
  val labelFontSize: NumberValue? = null,
  val labelOffset: NumberValue? = null,
  val symbolType: String? = null,
  val symbolSize: NumberValue? = null,
  val symbolStrokeWidth: NumberValue? = null,
  /**
   * `clipHeight` — a fixed row height, so entries with very different symbol sizes still line up.
   *
   * A size legend whose largest swatch is 5,000 units across would otherwise give that row seventy
   * units of height and the smallest row eight. Setting this makes every row the same and lets the
   * large symbols overflow, which is what a cartogram's legend wants.
   */
  val clipHeight: NumberValue? = null,
  val gradientLength: NumberValue? = null,
  val gradientThickness: NumberValue? = null,
  val rowPadding: NumberValue? = null,
  val columnPadding: NumberValue? = null,
  /** How many entries per row. `null` means one column vertically, one row horizontally. */
  val columns: NumberValue? = null,
  /** Absolute placement, used when [orient] is [LegendOrient.NONE]. */
  val legendX: NumberValue? = null,
  val legendY: NumberValue? = null,
  /**
   * The legend's own background: a rounded rectangle behind the entries and the title.
   *
   * `fillColor` and `strokeColor` are the legend's own properties, but the outline's **width and
   * dash pattern are read from `config.legend` alone** — upstream builds the group's encode from
   * `_('fillColor')` and `_('strokeColor')` and then from `config.strokeWidth` and
   * `config.strokeDash`, so writing `"strokeWidth": 2` on a legend does nothing whatever and
   * `config.legend.strokeWidth` does. Reproduced rather than tidied: a chart that outlines its
   * legends does it in the theme.
   */
  val fillColor: String? = null,
  val strokeColor: String? = null,
  val cornerRadius: NumberValue? = null,
  /**
   * A swatch's fill where the legend maps no colour scale of its own.
   *
   * A **fallback**, not an override: upstream sets the channel from `symbolFillColor` and then
   * overwrites it from the scale for every legend that has one, so a `fill` scale wins and only a
   * `size` or `shape` legend takes the stated colour.
   */
  val symbolFillColor: String? = null,
  /** Shifts a swatch and its label along the row, `symbolOffset`. */
  val symbolOffset: NumberValue? = null,
  /** The outline round a gradient ramp, and the ramp's own opacity. */
  val gradientStrokeColor: String? = null,
  val gradientStrokeWidth: NumberValue? = null,
  val gradientOpacity: NumberValue? = null,
  /**
   * The most entries a symbol legend shows, `symbolLimit`.
   *
   * Upstream keeps `limit - 1` and spends the last row on a summary of what it left out, so this is
   * not a plain truncation: a legend that drops entries says how many.
   */
  val symbolLimit: NumberValue? = null,
  /**
   * `formatType` — which grammar [format] is written in: `number`, `time` or `utc`.
   *
   * It decides the grammar *before* the scale gets a say, which is the only way a legend over
   * instants reads as dates: its scale is a colour ramp and knows nothing about time.
   */
  val formatType: String? = null,
  /** A floor on the gap between a gradient legend's labelled values; see the axis's own. */
  val tickMinStep: NumberValue? = null,
  /**
   * As on an axis: `aria: false` hides the legend from a screen reader, `description` renames it.
   */
  val aria: Boolean = true,
  val description: String? = null,
  val backgroundStrokeWidth: Double? = null,
  val backgroundStrokeDash: List<Double>? = null,
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
  /**
   * The `encode` blocks as written, per part, for the channels no property can express.
   *
   * The same arrangement an axis has: most of a legend's `encode` folds into the properties beside
   * it, and a few channels have no property behind them. A legend **label read through a scale** is
   * the one that matters — `{"scale": "label", "field": "value"}` turns a cluster's id into its
   * name — and there is no `legendLabelText` to fold that into.
   */
  val encode: Map<String, EncodeSpec> = emptyMap(),
) {
  /**
   * The scale this legend describes.
   *
   * Upstream's `LegendScales` order, first one present wins: **size** before shape before fill. A
   * legend encoding both `fill` and `size` therefore takes its entry *values* from the size scale,
   * and its colours follow from those values rather than the other way round. Reading `fill` first
   * gives a legend with the right number of entries at the wrong values.
   */
  public val scale: String?
    get() = size ?: shape ?: fill ?: stroke ?: strokeWidthScale ?: strokeDashScale ?: opacity

  /** How many channels this legend maps, which is what decides whether the type can be derived. */
  public val channelCount: Int
    get() =
      listOfNotNull(fill, stroke, size, shape, strokeWidthScale, strokeDashScale, opacity).size
}

/**
 * A cartographic projection: the rule that turns longitude and latitude into a place on the page.
 *
 * Named like a scale and used like one — a `geoshape` transform points at it by name — but it is
 * not a scale: it takes two numbers and returns two, its output depends on both inputs together,
 * and it cuts geometry as well as moving it. Every field here is signal-valued because a map that
 * lets a reader turn the globe is the ordinary case.
 */
public data class ProjectionSpec(
  val name: String,
  /** `mercator` by default, which is what upstream falls back to. */
  val type: String? = null,
  /** `{"type": {"signal": "..."}}` — a chart that lets a reader choose the projection. */
  val typeSignal: String? = null,
  val scale: NumberValue? = null,
  /**
   * Each of these may be written two ways, and both are common.
   *
   * `[{"signal": "a"}, {"signal": "b"}]` is a list of signals, one per component. `{"signal": "[a,
   * b]"}` is **one** signal that evaluates to the whole list — which is what a map with three
   * rotation sliders usually writes, because it is shorter. [NumberList] holds either.
   */
  val translate: NumberList = NumberList.None,
  val center: NumberList = NumberList.None,
  val rotate: NumberList = NumberList.None,
  /** Post-projection rotation of the plane, in degrees. */
  val angle: NumberValue? = null,
  /** The subdivision threshold for adaptive resampling; `0` turns it off entirely. */
  val precision: NumberValue? = null,
  val clipAngle: NumberValue? = null,
  val clipExtent: List<NumberList> = emptyList(),
  /**
   * The two standard parallels of a conic projection.
   *
   * Not a tuning knob: they change the raw formula, so `conicEqualArea` at `[20, 50]` and the same
   * projection at its default is a different map of the same world rather than the same map
   * redrawn.
   */
  val parallels: NumberList = NumberList.None,
  /** The radius a `Point` geometry is drawn as, since a projected city has no extent of its own. */
  val pointRadius: NumberValue? = null,
  val reflectX: NumberValue? = null,
  val reflectY: NumberValue? = null,
  /** `fit`/`extent`/`size`, which size the projection from the data rather than from a scale. */
  val fit: VegaValue? = null,
  val extent: List<NumberList> = emptyList(),
  val size: NumberList = NumberList.None,
)

/**
 * A list of numbers a specification may write out or hand over whole.
 *
 * The distinction is not cosmetic: `[{"signal": "a"}, {"signal": "b"}]` resolves two signals and
 * `{"signal": "[a, b]"}` resolves one that returns an array. A parser that understood only the
 * first reads a rotation as empty and draws the globe unturned, which is a plausible picture and
 * the wrong one.
 */
public sealed interface NumberList {
  public object None : NumberList

  public data class Items(val values: List<NumberValue>) : NumberList

  /** One signal for the whole list. */
  public data class Signal(val expression: String) : NumberList
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
/**
 * `sort` on a mark: the order its *items* are built and painted in.
 *
 * The fields name encoded properties of the item, not columns of the data — `{"field": "y"}` sorts
 * the group items by where they ended up. A ridgeline plot uses it to lay its bands out top to
 * bottom whatever order the categories arrived in.
 */
public data class MarkSort(val fields: List<String>, val orders: List<String> = emptyList())

public data class FacetSpec(
  val name: String,
  val data: String,
  val groupby: List<String>,
  /**
   * Extra summaries computed per group and written onto the group's own datum.
   *
   * `{"ops": ["min", "max", "count"], "fields": ["lat", "lat", "lat"], "as": [...]}` gives every
   * cell the extent of its own rows, which is how a ridgeline plot scales each band by how many
   * points are in it — the cell's marks read them off `parent`.
   */
  val aggregate: List<FacetMeasure> = emptyList(),
  /**
   * `facet.field` — the data is **already** grouped, and this names the column holding each group.
   *
   * Every row of [data] becomes one cell, and the rows of that cell are the array in this field
   * rather than something a `groupby` worked out. An edge-bundling diagram is built this way: each
   * dependency carries the path through the tree it takes, and the cell draws that path.
   */
  val field: String? = null,
)

/** One `facet.aggregate` entry: an operation, the field it reads, and the name it writes. */
public data class FacetMeasure(val op: String, val field: String?, val name: String)

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

  /**
   * `{"parent": {"datum": "which"}}` — read off the parent, under a name another reference
   * supplies.
   *
   * A parallel-coordinates plot is written exactly this way: one line per car, one point per axis,
   * and each point reads whichever column that axis stands for off the car. Neither half can be
   * written down in advance, so the name is a reference too.
   */
  public data class ParentOf(val name: FieldRef) : FieldRef

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
  /**
   * `align` — how the cells line up, per axis, as `"each"`, `"all"` or `"none"`.
   *
   * `each` is the default and the only one whose name describes what it does to a reader: every
   * column gets the same lead-in, so the columns line up with each other. `all` makes every column
   * the width of the widest cell anywhere. `none` lets each cell keep its own overhang, so nothing
   * lines up and no space is wasted — which is what a pair of square plots side by side wants.
   */
  val alignColumn: String? = null,
  val alignRow: String? = null,
  /**
   * `bounds` — which box the grid measures a cell by: `"full"` or `"flush"`.
   *
   * `full` is the cell's own bounds, so an axis label hanging off to its left is made room for.
   * `flush` is its *declared* extent, so it is not, and the cells sit at exactly their own size
   * with the overhang allowed to collide. A chart with an axis on every cell reads better flush.
   */
  val bounds: String? = null,
  /**
   * `center`: a cell narrower than its column sits in the middle of it rather than at its start.
   *
   * One value for both directions or a per-direction object, like `padding` and `align`. Only
   * meaningful alongside an alignment, and upstream guards it twice over — horizontally it needs
   * more than one row, vertically more than one column, since a single row has nothing to centre
   * against.
   */
  val centerColumn: Boolean = false,
  val centerRow: Boolean = false,
  /**
   * `headerBand`, `footerBand` and `titleBand`: how far **along** a cell or the grid a label sits.
   *
   * `null` is upstream's default for a header or a footer and means the cell's own origin; a
   * fraction means that far across the cell's extent, so `0.5` centres it. A title's default is
   * `0.5`, because a row title centred on the grid is what a trellis usually wants.
   */
  val headerBandRow: Double? = null,
  val headerBandColumn: Double? = null,
  val footerBandRow: Double? = null,
  val footerBandColumn: Double? = null,
  val titleBandRow: Double? = null,
  val titleBandColumn: Double? = null,
  /** `titleAnchor`: `"end"` puts a title past the **footers** rather than before the headers. */
  val titleAnchorRow: String? = null,
  val titleAnchorColumn: String? = null,
  /**
   * `offset`: how far outside the grid each kind of label sits.
   *
   * One number for all six, or an object naming any of `rowHeader`, `columnHeader`, `rowFooter`,
   * `columnFooter`, `rowTitle` and `columnTitle`.
   */
  val offsets: Map<String, Double> = emptyMap(),
) {
  /** The band for a header, by direction. */
  public fun headerBand(row: Boolean): Double? = if (row) headerBandRow else headerBandColumn

  public fun footerBand(row: Boolean): Double? = if (row) footerBandRow else footerBandColumn

  /** A title's band, whose default is the middle rather than the origin. */
  public fun titleBand(row: Boolean): Double = (if (row) titleBandRow else titleBandColumn) ?: 0.5

  public fun titleAtEnd(row: Boolean): Boolean =
    (if (row) titleAnchorRow else titleAnchorColumn) == "end"

  /** How far outside the grid one kind of label sits; zero when the layout says nothing. */
  public fun offsetFor(role: String): Double = offsets[role] ?: 0.0
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
  /**
   * What this mark is *for*, which only matters inside a `layout`.
   *
   * A group marked `row-header` or `column-title` is not a cell of the grid; it labels one.
   * Upstream reads the same property to decide which marks the grid places and which it arranges
   * around it.
   */
  val role: String? = null,
  val from: FromSpec? = null,
  /** `sort` — the order this mark's items are built and painted in; see [MarkSort]. */
  val sort: MarkSort? = null,
  /**
   * `transform` on a **mark**, which runs after the data and before the drawing.
   *
   * Upstream calls these post-encoding transforms and runs them over the scene *items*, writing
   * onto each one; `geopath` turning a GeoJSON feature into an outline is the case that matters,
   * and it reads the item's datum and writes the item's `path`. This engine's transforms are pure
   * functions over rows, so they run over the rows and write the same column — which draws the same
   * picture, because nothing between the two reads anything a mark transform touches.
   */
  val transform: List<VegaValue> = emptyList(),
  val encode: EncodeSpec = EncodeSpec(),
  /** Nested marks, for a group mark. */
  val marks: List<MarkSpec> = emptyList(),
  val projections: List<ProjectionSpec> = emptyList(),
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
