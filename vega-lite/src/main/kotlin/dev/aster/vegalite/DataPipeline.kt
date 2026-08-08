package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue

/**
 * Builds one view's chain of data nodes, in the order `compile/data/parse.ts` builds it.
 *
 * The order is the whole content of this file and it is not interchangeable: parsing before binning
 * (a date has to be a date before it can be bucketed), binning before aggregating (the bin is what
 * the count groups by), aggregating before stacking (the stack accumulates the aggregates), and the
 * invalid-value filter last of all, so that everything upstream of it still sees the rows it drops.
 */
internal class DataPipeline(
  private val view: UnitView,
  private val diagnostics: DiagnosticCollector,
) {

  /** The two named points a view exposes: the table before aggregation, and the one marks read. */
  class Outputs(val raw: OutputNode?, val main: OutputNode)

  fun build(source: SourceNode): Outputs {
    var head: DataNode = source

    userTransforms()?.let { head = head.then(it) }
    implicitParse()?.let { head = head.then(it) }
    binNode()?.let { head = head.then(it) }
    timeUnitNode()?.let { head = head.then(it) }

    // The pre-aggregation table, named only when something reads it. A domain sorted by an
    // aggregate of another field is that something: the ordering has to be computed from the rows
    // themselves, independently of the aggregation being drawn. Upstream always creates this node
    // and lets its optimizer drop it again; creating it only when it is used comes to the same
    // output, and the output is what is being compared.
    val raw =
      if (needsRawTable()) {
        OutputNode(view.prefixed("raw")).also {
          head.then(it)
          head = it
        }
      } else {
        null
      }

    aggregateNode()?.let { head = head.then(it) }
    stackNode()?.let { head = head.then(it) }
    filterInvalidNode()?.let { head = head.then(it) }

    val main = OutputNode(view.prefixed("main"))
    head.then(main)
    return Outputs(raw, main)
  }

  private fun needsRawTable(): Boolean =
    view.scaledChannels().any { (channel, def) ->
      val type = view.scaleType(channel) ?: return@any false
      Scales.hasDiscreteDomain(type) && def.sort is VegaValue.Obj
    }

  /**
   * A temporal field arrives as text and has to become a date before a time scale can read it.
   *
   * Upstream calls this the implicit parse, as against the `format.parse` a specification writes
   * itself. It is implicit but not optional: without it a time axis sorts its dates as strings.
   */
  private fun implicitParse(): ParseNode? {
    val parse = LinkedHashMap<String, String>()
    // A filter's comparisons say what type its column holds, and that has to be settled before the
    // filter runs — so these parses belong with the encoding's, not after them.
    parse.putAll(Transforms(diagnostics).implicitParses(view.spec.transforms))
    for ((_, def) in view.spec.encoding) {
      if (!def.isFieldDef || def.field == null) continue
      // A time unit buckets a *date*, so the column still has to be read as one first.
      if (def.type == MeasureType.TEMPORAL && def.aggregate == null) parse[def.field] = "date"
    }
    return if (parse.isEmpty()) null else ParseNode(parse)
  }

  private fun binNode(): BinNode? {
    val bins =
      view.spec.encoding.values.mapNotNull { def ->
        val bin = def.bin as? Binning.Bin ?: return@mapNotNull null
        val field = def.field ?: return@mapNotNull null
        val key = "${Fields.binToString(bin.params)}_$field"
        BinComponent(
          field = field,
          params = bin.params,
          output =
            listOf(
              Fields.vgField(def, forAs = true),
              Fields.vgField(def, suffix = "end", forAs = true),
            ),
          signal = view.prefixed("${key}_bins"),
          extentSignal = view.prefixed("${key}_extent"),
          extent = bin.params.fields["extent"],
          // A binned field on a discrete scale needs its range as text, because that is what the
          // axis labels and the legend entries read.
          rangeFormula = null,
        )
      }
    return if (bins.isEmpty()) null else BinNode(bins.distinctBy { it.signal })
  }

  private fun timeUnitNode(): TimeUnitNode? {
    val units =
      view.spec.encoding.values.mapNotNull { def ->
        val timeUnit = def.timeUnit ?: return@mapNotNull null
        val field = def.field ?: return@mapNotNull null
        TimeUnitComponent(field, Fields.timeUnitParts(timeUnit), Fields.vgField(def, forAs = true))
      }
    return if (units.isEmpty()) null else TimeUnitNode(units)
  }

  /**
   * The aggregate an encoding implies: every aggregated channel is a measure, every other field is
   * a grouping.
   */
  private fun aggregateNode(): AggregateNode? {
    if (view.spec.encoding.values.none { it.aggregate != null }) return null

    val dimensions = LinkedHashSet<String>()
    val ops = mutableListOf<String>()
    val fields = mutableListOf<String?>()
    val outputs = mutableListOf<String>()

    for ((_, def) in view.spec.encoding) {
      if (!def.isFieldDef) continue
      val aggregate = def.aggregate
      if (aggregate == null) {
        dimensions += Fields.vgField(def)
        // A binned or bucketed dimension groups by both edges, so the span survives the
        // aggregation intact — the scale and the axis both read the end as well as the start.
        if (def.bin is Binning.Bin || def.timeUnit != null) {
          dimensions += Fields.vgField(def, suffix = "end")
        }
      } else {
        ops += aggregate
        fields += if (aggregate == "count") null else def.field
        outputs += Fields.vgField(def, forAs = true)
      }
    }

    return AggregateNode(dimensions.toList(), ops, fields, outputs)
  }

  private fun stackNode(): StackNode? {
    val stack = view.stack ?: return null
    val def = view.spec.encoding[stack.fieldChannel] ?: return null
    val stackBy = stack.stackBy.map { Fields.vgField(it) }
    // Without an explicit order the segments are stacked in field order — downwards on a vertical
    // stack so that the first category ends up on top, and upwards on a horizontal one.
    val order = if (stack.fieldChannel == "y") "descending" else "ascending"
    return StackNode(
      field = Fields.vgField(def),
      groupby = stack.groupbyFields,
      sortFields = stackBy,
      sortOrders = stackBy.map { order },
      output =
        listOf(
          Fields.vgField(def, suffix = "start", forAs = true),
          Fields.vgField(def, suffix = "end", forAs = true),
        ),
      offset = stack.offset,
      imputeKeys =
        if (stack.impute) {
          stack.groupbyChannels.mapNotNull {
            view.spec.fieldDef(it)?.let { d -> Fields.vgField(d) }
          }
        } else {
          emptyList()
        },
    )
  }

  /**
   * `FilterInvalidNode`: rows whose scaled numbers are missing are dropped before anything draws.
   *
   * A path mark is the exception and gets no filter at all — a line breaks at a gap instead,
   * through the mark's own `defined`, so that the gap is visible rather than closed over.
   */
  private fun filterInvalidNode(): FilterInvalidNode? {
    if (view.spec.mark in setOf("line", "area", "trail")) return null

    val expressions =
      view.spec.encoding.entries.mapNotNull { (channel, def) ->
        if (channel !in Channels.SCALE_CHANNELS || !def.isFieldDef) return@mapNotNull null
        // A count is never invalid: it is produced by the aggregate, not read from the data.
        if (def.aggregate == "count") return@mapNotNull null
        val accessor = Fields.datumAccess(def)
        when (def.type) {
          MeasureType.QUANTITATIVE -> "isValid($accessor) && isFinite(+$accessor)"
          MeasureType.TEMPORAL ->
            "(isDate($accessor) || (isValid($accessor) && isFinite(+$accessor)))"
          else -> null
        }
      }
    return if (expressions.isEmpty()) null else FilterInvalidNode(expressions.distinct())
  }

  /** The `transform` block, translated by [Transforms]. */
  private fun userTransforms(): PassThroughNode? {
    val transforms = Transforms(diagnostics).translate(view.spec.transforms, "$.transform")
    return if (transforms.isEmpty()) null else PassThroughNode(transforms)
  }
}
