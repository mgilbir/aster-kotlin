package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * One view being compiled: its specification, and the parts of the whole chart it has to agree
 * with.
 *
 * The scale types are the whole chart's, not this view's, which matters in a layer: two layers
 * share one `y`, and a mark asks the *merged* scale what kind it is before deciding how to place
 * itself.
 */
internal class UnitView(
  val spec: UnitSpec,
  val config: Config,
  /** `layer_0` in a layered chart, empty for a chart that is a single view. */
  val name: String,
  /**
   * The name of the composition child this view belongs to, which may not be the view's own.
   *
   * A layer normalizing into several views — a line that draws its own points — names them
   * `layer_1_layer_0` and `layer_1_layer_1`, but the layer `resolve` speaks about is still
   * `layer_1`: what a composition resolves is *its children*, and a nested layer inside one of them
   * is below the level being resolved. Naming an independent scale from the expanded view instead
   * gives a line and its points a scale each and draws them apart.
   */
  val childName: String = name,
  /**
   * Whether this view is one member of a **layer**, which decides where its binning happens.
   *
   * `parseData` bins a layer's member *before* that member's own transforms rather than after them
   * — the comment upstream calls it a hack "equivalent for merging bin extent for union scale", and
   * it is what makes two layers over one binned field share a single bin. Left after the
   * transforms, one layer's filter stands between the source and its bin, the two bins are no
   * longer siblings, and each layer buckets the rows it can see: two histograms drawn on bins of
   * different widths, over a domain that is the union of both.
   */
  val parentIsLayer: Boolean = false,
) {
  val markDef: MarkDef = spec.markDef

  val stack: StackProperties? = Stack.of(spec)

  /**
   * `normalizeInvalidDataMode`: what this view does with a value no scale can place.
   *
   * The default reads as two rules in one — a path *breaks* at the gap, everything else *drops* the
   * row — because a line with a hole in it says something a scatter with a hole in it cannot. The
   * two consumers are the mark's `defined` and the data flow's filter, and they have to agree:
   * filtering a row the path was going to break at removes the break along with the row.
   */
  val invalidDataMode: String
    get() {
      val stated = markDef.raw.fields["invalid"] ?: config.markInvalid
      val isPath = spec.mark in PATH_MARKS
      val forPathOrNot = if (isPath) "break-paths-show-domains" else "filter"
      if (stated == null) return forPathOrNot
      if (stated is VegaValue.Null) return "show"
      return when (val named = (stated as? VegaValue.Str)?.value) {
        null,
        "break-paths-show-path-domains" -> forPathOrNot
        else -> named
      }
    }

  /**
   * `getDataSourcesForHandlingInvalidValues`: which rows the **marks** draw and which the *scales*
   * measure, which are not always the same rows.
   *
   * A path that breaks at a gap needs the row the gap is at — the break is drawn from it — while
   * the domain may or may not want it. `break-paths-filter-domains` says draw the break and leave
   * the gap out of the extent; `break-paths-show-domains` says draw it and keep it. Where the two
   * answers differ the flow needs two named points rather than one: the marks read the main output
   * and the scales read a filtered copy below it, or an unfiltered one above.
   */
  val marksExcludeInvalid: Boolean
    get() =
      when (invalidDataMode) {
        "filter" -> true
        "break-paths-show-domains",
        "break-paths-filter-domains" -> spec.mark !in PATH_MARKS
        else -> false
      }

  val scalesExcludeInvalid: Boolean
    get() =
      when (invalidDataMode) {
        "filter",
        "break-paths-filter-domains" -> true
        else -> false
      }

  /** Merged scale type per channel, filled in once every view has contributed. */
  var scaleTypes: Map<String, String> = emptyMap()

  /** The merged scale components themselves, which a baseline consults for its zero. */
  var scaleComponents: Map<String, ScaleComponent> = emptyMap()

  /**
   * The fields this view is faceted by, which every grouping in its data flow has to carry.
   *
   * A stack accumulated without them would run across the cells rather than within each, which is
   * the difference between a trellis and one chart drawn several times over.
   */
  var facetFields: List<String> = emptyList()

  /**
   * The definitions the facet channels were lifted out of, for the steps that still need them.
   *
   * The cell's encoding no longer mentions them — a facet says nothing about what a cell looks like
   * — but the column it breaks the chart down by may still have to be bucketed or binned, and
   * upstream does that on the facet's own model, above the cell's.
   */
  var facetDefs: List<ChannelDef> = emptyList()

  /**
   * Whether this view's marks are clipped because a selection drives one of its position scales.
   *
   * `scaleClip`: a pan or a zoom moves the domain, and the rows that fall outside it are still
   * drawn — outside the plotting area — unless the mark is clipped.
   */
  var clippedByScale: Boolean = false

  /** The chart's selections, which decide whether this view's marks can be interacted with. */
  var selections: List<Selection> = emptyList()

  /**
   * The signals this view measures itself against.
   *
   * `width` and `height` for a plain chart; `child_width`/`child_height` inside a facet, where
   * `width` is the whole grid and the plotting area is one cell of it; and inside a concatenation
   * whatever the sizes merged into — `childWidth` where every plot is the same width, and
   * `concat_1_width` where they are not. Everything that mentions a size goes through here: a
   * scale's range, an axis's tick count, a mark's midpoint.
   */
  var widthSignal: String = "width"

  var heightSignal: String = "height"

  fun sizeSignal(channel: String): String =
    if (channel == "x" || channel == "width") widthSignal else heightSignal

  /**
   * What each of this view's scales is called.
   *
   * A shared scale keeps the channel's own name; one a composition resolves **independently** takes
   * the name of the child that owns it — `concat_0_x` beside `concat_1_x`, because two plots side
   * by side measure separate things, or `layer_0_y` beside `layer_1_y`, which is the whole of what
   * makes a chart dual-axis. Everything that mentions a scale goes through here.
   */
  var scaleNames: Map<String, String> = emptyMap()

  fun scale(channel: String): String = scaleNames[channel] ?: channel

  /** The dataset a mark reads, once the data flow has been assembled and named. */
  var mainData: String = ""

  /**
   * The dataset the *marks* read, which inside a facet is the cell's own partition.
   *
   * Everything else — a scale domain, the facet's own value list — still reads the whole table, so
   * every cell is scaled alike and the grid holds one column per value rather than per cell.
   */
  var markData: String = ""
    get() = field.ifEmpty { mainData }

  /** The dataset before aggregation, which a sorted domain reads. */
  var rawData: String = ""

  /**
   * A name the whole specification can refer to — `getName` in `compile/model.ts`.
   *
   * The name is run through `varName`, which is not cosmetic: these become **signal** names, and a
   * signal is an identifier in Vega's expression language. A column called `IMDB Rating` gives a
   * bin signal `bin_maxbins_10_IMDB Rating_bins`, which every expression reading it parses as two
   * words and fails on. Anything outside `[A-Za-z0-9_]` becomes an underscore, and a leading digit
   * takes one in front of it.
   */
  fun prefixed(suffix: String): String =
    Fields.varName(if (name.isEmpty()) suffix else "${name}_$suffix")

  /**
   * The model each of this view's transforms was written on, in the order they run.
   *
   * A layered chart's transforms are copied down into every member — the parent's chain and then
   * the member's own — but they still *belong* to the model they were written on, and that is what
   * names the signals they publish. A `bin` above the layers is the layer model's, so both members
   * read one `bin_maxbins_10_x_bins`; a `bin` inside one member is that member's.
   */
  var transformOwners: List<String> = emptyList()

  /** [prefixed], under the model that owns the transform at [index] rather than this view. */
  fun prefixedForTransform(index: Int, suffix: String): String {
    val owner = transformOwners.getOrNull(index) ?: name
    return Fields.varName(if (owner.isEmpty()) suffix else "${owner}_$suffix")
  }

  fun scaleType(channel: String): String? = if (hasScale(channel)) scaleTypes[channel] else null

  /**
   * Whether a position channel has an offset scale nested inside it.
   *
   * This is what turns one band per category into several bars side by side, and it changes more
   * than the mark: the outer band takes a wider padding, its step is computed from how many bars
   * have to fit, and the bar's width comes from the *inner* scale.
   */
  fun hasNestedOffset(channel: String): Boolean =
    offsetChannelFor(channel)?.let { hasScale(it) } == true

  /**
   * Whether *this* view scales a channel, as against the chart as a whole.
   *
   * The distinction only shows up in a layer, and it decides where a mark goes: a rule that encodes
   * only `y` has no `x` scale of its own even when the layer beside it does, so it spans the plot
   * rather than being placed at some value of the other layer's scale.
   */
  fun hasScale(channel: String): Boolean {
    val def = scaledDef(spec.encoding[channel] ?: return false) ?: return false
    return !def.scaleDisabled
  }

  /** The channels that own a scale in this view, in specification order. */
  fun scaledChannels(): List<Pair<String, ChannelDef>> =
    spec.encoding.entries
      .mapNotNull { (channel, def) ->
        if (channel !in Channels.SCALE_CHANNELS) null else scaledDef(def)?.let { channel to it }
      }
      .filter { (_, def) -> !def.scaleDisabled }

  /**
   * The definition a channel's scale is built from — `getFieldOrDatumDef`, which reads a condition.
   *
   * A channel written *only* as a test still names a field, and that field still needs a scale: a
   * median tick that is its category's colour unless its box has no height says so conditionally,
   * and reading the unconditional part alone left it with a colour it had no scale to look up.
   */
  fun scaledDef(def: ChannelDef): ChannelDef? =
    when {
      def.isFieldDef || def.datum != null -> def
      else -> def.conditions.firstOrNull { it.isFieldDef || it.datum != null }
    }
}
