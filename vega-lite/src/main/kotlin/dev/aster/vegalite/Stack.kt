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

    // ```js
    // if (channel !== 'tooltip' && channelHasField(encoding, channel)) {
    //   const channelDef = encoding[channel];
    //   for (const cDef of array(channelDef)) {
    //     const fieldDef = getFieldDef(cDef);
    // ```
    //
    // `channelHasField` counts a **conditional** field def, and `getFieldDef` then reaches into the
    // condition for it: a colour that names a column only where a row was picked, and a value
    // otherwise, is a dimension of the stack like any other and orders it. Reading the
    // unconditional part alone left such a chart's stack sorted by nothing.
    //
    // Every entry of a channel written as a **list**, not the first alone — a series split by two
    // columns is split by both. The guard reads those entries differently from a lone definition,
    // and faithfully so: `some(channelDef, fieldDef => !!fieldDef.field)` asks each entry for a
    // field of its **own**, so a list of conditions names no column at all where a single one does.
    val stackBy =
      Channels.NONPOSITION_CHANNELS.flatMap { channel ->
        if (channel == "tooltip") return@flatMap emptyList()
        val written = spec.encoding[channel] ?: return@flatMap emptyList()
        val entries = listOf(written) + written.siblings
        val namesAColumn =
          if (written.isList) entries.any { it.field != null }
          else written.isFieldDef || written.conditions.any { it.isFieldDef }
        if (!namesAColumn) return@flatMap emptyList()
        entries.mapNotNull inner@{ entry ->
          val def =
            (if (entry.isFieldDef) entry else entry.conditions.firstOrNull { it.isFieldDef })
              ?: return@inner null
          // `stack()` runs before `alignStackOrderWithColorDomain`, so a channel that rule added is
          // not one of the stack's own dimensions — see [ChannelDef.addedAfterStack]. Counting it
          // put the sort-index column into the `impute` a stacked area is given, and every colour's
          // missing values were then filled per index rather than per colour.
          if (entry.addedAfterStack) return@inner null
          if (def.aggregate != null) return@inner null
          val name = Fields.vgField(def)
          if (name.isEmpty() || name !in groupbyFields) def else null
        }
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
  fun isAggregate(spec: UnitSpec): Boolean =
    // `isAggregate` spreads a list channel like every other reader of an encoding:
    //
    //     if (isArray(channelDef)) {
    //       return some(channelDef, (fieldDef) => !!fieldDef.aggregate);
    //     }
    //
    // so a `tooltip` whose second entry asks for a mean makes the view an aggregating one.
    spec.encoding.values.any { def ->
      (listOf(def) + def.siblings + def.conditions).any { it.aggregate != null }
    }

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
