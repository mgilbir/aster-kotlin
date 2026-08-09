package dev.aster.vegalite

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
) {
  val markDef: MarkDef = spec.markDef

  val stack: StackProperties? = Stack.of(spec)

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

  fun prefixed(suffix: String): String = if (name.isEmpty()) suffix else "${name}_$suffix"

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
    val def = spec.encoding[channel] ?: return false
    return (def.isFieldDef || def.datum != null) && !def.scaleDisabled
  }

  /** The channels that own a scale in this view, in specification order. */
  fun scaledChannels(): List<Pair<String, ChannelDef>> =
    spec.encoding.entries
      .filter {
        it.key in Channels.SCALE_CHANNELS && (it.value.isFieldDef || it.value.datum != null)
      }
      .filter { !it.value.scaleDisabled }
      .map { it.key to it.value }
}
