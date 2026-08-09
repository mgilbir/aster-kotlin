package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/** What stacking a view does, or null when it does none. A port of `stack()` in `stack.ts`. */
internal data class StackProperties(
  /** The measure channel whose values are accumulated — `y` for the usual vertical bar. */
  val fieldChannel: String,
  /** The channels the stack is grouped by: the dimension, and any offset on it. */
  val groupbyChannels: List<String>,
  val groupbyFields: List<String>,
  /** The channels that split one column into segments — colour, detail. */
  val stackBy: List<ChannelDef>,
  val offset: String,
  val impute: Boolean,
)

internal object Stack {

  /** Marks that *can* stack. A rule or a point stacks only when asked; a bar does by default. */
  private val STACKABLE =
    setOf("arc", "bar", "area", "rule", "point", "circle", "square", "line", "text", "tick")

  private val STACK_BY_DEFAULT = setOf("bar", "area", "arc")

  private val SUMMATIVE_OPS = setOf("count", "sum", "distinct", "valid", "missing")

  fun of(spec: UnitSpec): StackProperties? {
    val mark = spec.mark
    if (mark !in STACKABLE) return null

    // Cartesian first, then polar: a text mark can be stacked in either, and an arc only in polar.
    val fieldChannel =
      potentialStackedChannel(spec, "x") ?: potentialStackedChannel(spec, "theta") ?: return null
    val stackedDef = spec.encoding[fieldChannel] ?: return null
    val stackedField = if (stackedDef.isFieldDef) Fields.vgField(stackedDef) else null

    val dimensionChannel =
      when (fieldChannel) {
        "x" -> "y"
        "y" -> "x"
        "theta" -> "radius"
        else -> "theta"
      }
    val groupbyChannels = mutableListOf<String>()
    val groupbyFields = mutableListOf<String>()
    val dimensionDef = spec.encoding[dimensionChannel]
    if (dimensionDef != null) {
      val dimensionField = if (dimensionDef.isFieldDef) Fields.vgField(dimensionDef) else null
      // Grouping by the stacked field itself would put every value in its own group and stack
      // nothing, so upstream skips it when the two coincide.
      if (dimensionField != null && dimensionField != stackedField) {
        groupbyChannels += dimensionChannel
        groupbyFields += dimensionField
      }
    }

    // The offset divides a band, so it groups the stack too: without it every bar in a group would
    // accumulate onto the one beside it.
    val dimensionOffset = offsetChannelFor(dimensionChannel)?.let { spec.fieldDef(it) }
    if (dimensionOffset != null) {
      val offsetField = Fields.vgField(dimensionOffset)
      if (offsetField != stackedField) {
        groupbyChannels += offsetChannelFor(dimensionChannel)!!
        groupbyFields += offsetField
      }
    }

    val stackBy =
      Channels.NONPOSITION_CHANNELS.mapNotNull { channel ->
        if (channel == "tooltip") return@mapNotNull null
        val def = spec.encoding[channel]?.takeIf { it.isFieldDef } ?: return@mapNotNull null
        if (def.aggregate != null) return@mapNotNull null
        val name = Fields.vgField(def)
        if (name.isEmpty() || name !in groupbyFields) def else null
      }

    val offset =
      when (val declared = stackedDef.stack) {
        null -> if (mark in STACK_BY_DEFAULT) "zero" else null
        is VegaValue.Bool -> if (declared.value) "zero" else null
        is VegaValue.Str -> declared.value
        VegaValue.Null -> null
        else -> null
      }
    if (offset == null || offset !in setOf("zero", "center", "normalize")) return null

    // An aggregate plot with nothing to split the columns by has one value per column, so stacking
    // it would be a no-op — upstream drops the transform rather than emit an identity.
    if (isAggregate(spec) && stackBy.isEmpty()) return null

    // A ranged mark already spans two positions; there is no free end to accumulate onto.
    val secondary = secondaryChannel(fieldChannel)
    if (secondary != null && spec.encoding[secondary] != null) return null

    return StackProperties(
      fieldChannel = fieldChannel,
      groupbyChannels = groupbyChannels,
      groupbyFields = groupbyFields,
      stackBy = stackBy,
      offset = offset,
      impute = stackedDef.raw.fields["impute"] != VegaValue.Null && isPathMark(mark),
    )
  }

  /**
   * Whether the **encoding** aggregates — the transforms are not asked.
   *
   * Upstream's `isAggregate(encoding)`, and the distinction shows on a composite mark: its parts
   * carry the aggregate in a `transform` and encode the summarised columns directly, so they are
   * not aggregating encodings and a tick among them still takes a scatter's reduced opacity.
   */
  fun isAggregate(spec: UnitSpec): Boolean = spec.encoding.values.any { it.aggregate != null }

  private fun isPathMark(mark: String): Boolean =
    mark == "line" || mark == "area" || mark == "trail"

  /**
   * Which of `x` and `y` carries the measure being stacked.
   *
   * The rules are asymmetric on purpose. With a measure on both, whichever one is aggregated wins,
   * because that is the one with several rows behind a single position; with neither aggregated, a
   * bar or an area falls back to its orientation.
   */
  private fun potentialStackedChannel(spec: UnitSpec, first: String): String? {
    val mark = spec.mark
    val orient = spec.markDef.orient
    val second = if (first == "x") "y" else "radius"
    val isCartesianBarOrArea = first == "x" && (mark == "bar" || mark == "area")
    val x = spec.encoding[first]
    val y = spec.encoding[second]

    if (x?.isFieldDef == true && y?.isFieldDef == true) {
      if (x.isUnbinnedQuantitative && y.isUnbinnedQuantitative) {
        if (x.stack != null) return first
        if (y.stack != null) return second
        val xAggregate = x.aggregate != null
        val yAggregate = y.aggregate != null
        if (xAggregate != yAggregate) return if (xAggregate) first else second
        if (isCartesianBarOrArea) {
          if (orient == "vertical") return second
          if (orient == "horizontal") return first
        }
        return null
      }
      if (x.isUnbinnedQuantitative) return first
      if (y.isUnbinnedQuantitative) return second
      return null
    }
    if (x?.isUnbinnedQuantitative == true) {
      if (isCartesianBarOrArea && orient == "vertical") return null
      return first
    }
    if (y?.isUnbinnedQuantitative == true) {
      if (isCartesianBarOrArea && orient == "horizontal") return null
      return second
    }
    return null
  }
}
