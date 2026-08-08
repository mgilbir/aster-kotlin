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
) {
  val markDef: MarkDef = spec.markDef

  val stack: StackProperties? = Stack.of(spec)

  /** Merged scale type per channel, filled in once every view has contributed. */
  var scaleTypes: Map<String, String> = emptyMap()

  /** The merged scale components themselves, which a baseline consults for its zero. */
  var scaleComponents: Map<String, ScaleComponent> = emptyMap()

  /** The dataset a mark reads, once the data flow has been assembled and named. */
  var mainData: String = ""

  /** The dataset before aggregation, which a sorted domain reads. */
  var rawData: String = ""

  fun prefixed(suffix: String): String = if (name.isEmpty()) suffix else "${name}_$suffix"

  fun scaleType(channel: String): String? = if (hasScale(channel)) scaleTypes[channel] else null

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
