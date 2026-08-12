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
  /** Registers a `lookup`'s second dataset and answers with the name it was given. */
  private val registerLookup: ((VegaValue) -> String)? = null,
) {

  /** The two named points a view exposes: the table before aggregation, and the one marks read. */
  class Outputs(val raw: OutputNode?, val main: OutputNode)

  fun build(source: SourceNode): Outputs {
    var head: DataNode = source

    head = userTransforms(head)
    implicitParse()?.let { head = head.then(it) }
    binNode()?.let { head = head.then(it) }
    timeUnitNode()?.let { head = head.then(it) }
    sortIndexNode()?.let { head = head.then(it) }

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
    imputeNode()?.let { head = head.then(it) }
    stackNode()?.let { head = head.then(it) }
    filterInvalidNode()?.let { head = head.then(it) }

    val main = OutputNode(view.prefixed("main"))
    head.then(main)
    return Outputs(raw, main)
  }

  private fun needsRawTable(): Boolean =
    view.scaledChannels().any { (channel, def) ->
      val type = view.scaleType(channel) ?: return@any false
      Scales.hasDiscreteDomain(type) &&
        Scales.sortsFromRawTable(Scales.settledSort(view, channel, def, type))
    }

  /**
   * A field arrives as text and has to become what the encoding says it is before anything orders
   * it.
   *
   * Upstream calls this the implicit parse, as against the `format.parse` a specification writes
   * itself. It is implicit but not optional, and every case here is a *comparison* that would
   * otherwise be made between strings: a time axis sorting its dates alphabetically, a `min` over
   * "10" and "9" answering "10", a line joining its points in the order 1, 10, 2.
   */
  private fun implicitParse(): ParseNode? {
    val parse = LinkedHashMap<String, String>()
    // A filter's comparisons say what type its column holds, and that has to be settled before the
    // filter runs — so these parses belong with the encoding's, not after them.
    parse.putAll(Transforms(diagnostics).implicitParses(view.spec.transforms))
    for ((_, def) in view.spec.encoding) {
      val field = def.field
      if (!def.isFieldDef || field == null) continue
      // A time unit buckets a *date*, so the column still has to be read as one first — and a time
      // unit is what makes a field an instant, whatever type the encoding gave it. A month named on
      // an ordinal scale is bucketed from a date exactly as a temporal one is.
      if ((def.type == MeasureType.TEMPORAL || def.timeUnit != null) && def.aggregate == null) {
        parse[field] = "date"
      } else if (def.type == MeasureType.QUANTITATIVE && def.aggregate in MIN_MAX_OPS) {
        // Upstream's own comment: "we need to parse numbers to support correct min and max". Every
        // other aggregate arithmetic-coerces on the way; these two only compare.
        parse[field] = "number"
      } else if (Fields.splitAccessPath(field).size > 1) {
        // A field named through a path — `record.high` — is read out into a flat column of its own.
        // A date or a number was going to be flattened by its parse anyway; this covers the rest,
        // which would otherwise be looked for under a name no row has.
        parse.getOrPut(field) { "flatten" }
      }
      // The same for a field named only in a `sort`, which is compared but never drawn.
      (def.sort as? VegaValue.Obj)?.string("field")?.let { sortField ->
        if (Fields.splitAccessPath(sortField).size > 1) parse.getOrPut(sortField) { "flatten" }
      }
    }
    // A path mark joins its points in the order its rows arrive, so the dimension it runs along is
    // sorted first — and sorting numerals held as text draws the line through them in the wrong
    // order. Upstream skips this when an `order` channel says how to join them instead, which is
    // how a connected scatter plot is written (`getImplicitFromEncoding`, `data/formatparse.ts`).
    if (view.spec.mark in PATH_MARKS && view.spec.encoding["order"] == null) {
      val def = view.spec.encoding[if (view.markDef.orient == "horizontal") "y" else "x"]
      val field = def?.field
      if (def != null && def.isFieldDef && field != null && def.type == MeasureType.QUANTITATIVE) {
        parse.getOrPut(field) { "number" }
      }
    }
    // A column a transform computed is *derived*: it has the type its transform gave it, and the
    // loader has never seen it.
    parse.keys.removeAll(Transforms(diagnostics).producedFields(view.spec.transforms))
    return if (parse.isEmpty()) null else ParseNode(parse)
  }

  /**
   * `sort: ["d", "a", "e", "b"]` — a written-out order, turned into a number per row.
   *
   * Vega has no comparator that takes a list, so upstream computes each row's *place* in the list
   * as a column and lets the domain sort on the smallest place each category carries
   * (`CalculateNode.parseAllForSortIndex`). A value not in the list falls past the end, which is
   * what puts it last. It runs after the bin and the time unit and before the pre-aggregation
   * table, because the ordering is over the rows as they will be grouped.
   */
  private fun sortIndexNode(): PassThroughNode? {
    val predicates = Transforms(diagnostics)
    val transforms =
      view.spec.encoding.entries.mapNotNull { (channel, def) ->
        val order = def.sort as? VegaValue.Arr ?: return@mapNotNull null
        val field = def.field ?: return@mapNotNull null
        // Each step is the same equality a `filter` would compile, through the same compiler:
        // upstream builds it as `fieldFilterExpression({field, timeUnit, equal: value})`, and a
        // second spelling of "is this row that value" would drift the day one of them was fixed.
        val cases =
          order.values.mapIndexed { index, value ->
            val test =
              predicates.testExpression(
                obj {
                  put("field", field)
                  def.timeUnit?.let { put("timeUnit", it) }
                  put("equal", value)
                },
                "$.encoding.$channel.sort[$index]",
              )
            "$test ? $index : "
          }
        obj {
          put("type", "formula")
          // A value the list never names falls past the end, which is what puts it last.
          put("expr", cases.joinToString("") + order.values.size)
          put("as", Fields.sortIndexField(channel, def, forAs = true))
        }
      }
    return if (transforms.isEmpty()) null else PassThroughNode(transforms)
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
        TimeUnitComponent(
          field,
          Fields.timeUnitParts(timeUnit),
          Fields.vgField(def, forAs = true),
          utc = timeUnit.startsWith("utc"),
        )
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

    // Every field definition, not only the channels' own: a `tooltip` written as a **list** holds
    // several, and one of them may be the only thing asking for an aggregate.
    val everyDef = view.spec.encoding.values.flatMap { listOf(it) + it.siblings }
    for (def in everyDef) {
      if (!def.isFieldDef) continue
      val aggregate = def.aggregate
      if (aggregate == null) {
        dimensions += Fields.vgField(def)
        // A binned or bucketed dimension groups by both edges, so the span survives the
        // aggregation intact — the scale and the axis both read the end as well as the start.
        if (hasBandEnd(def)) {
          dimensions += Fields.vgField(def, suffix = "end")
        }
      } else {
        ops += aggregate
        // An `argmin`/`argmax` is taken over the column it *names*, not the one being read.
        fields +=
          when {
            aggregate == "count" -> null
            def.argumentField != null -> def.argumentField
            else -> def.field
          }
        outputs += Fields.vgField(def, forAs = true)
      }
    }

    for (field in view.facetFields) dimensions += field
    return AggregateNode(dimensions.toList(), ops, fields, outputs)
  }

  /**
   * `impute` on a position channel — the gaps in a series, filled so a path does not jump them.
   *
   * Both positions have to be fields: one of them says how to fill and the *other* is the key the
   * filling is done over, which is what makes a hole a hole. The grouping is the same set of fields
   * a path mark is split into series by, so one series' gaps are filled from its own rows.
   */
  private fun imputeNode(): ImputeNode? {
    val x = view.spec.encoding["x"]?.takeIf { it.isFieldDef }
    val y = view.spec.encoding["y"]?.takeIf { it.isFieldDef }
    if (x == null || y == null) return null
    val imputed = if (x.impute != null) x else if (y.impute != null) y else return null
    val key = if (x.impute != null) y else x
    val params = imputed.impute ?: return null
    val method = params.string("method")
    // `processSequence`: a `keyvals` written as `{start, stop, step}` is a *generated* list, which
    // Vega has an expression for and no transform property — so it becomes a signal computing it.
    val keyvals =
      when (val stated = params.fields["keyvals"]) {
        is VegaValue.Obj -> {
          val parts =
            listOfNotNull(
              Fields.expressionNumber(stated.number("start") ?: 0.0),
              stated.number("stop")?.let { Fields.expressionNumber(it) },
              stated.number("step")?.let { Fields.expressionNumber(it) },
            )
          signalRef("sequence(${parts.joinToString(",")})")
        }
        else -> stated
      }
    return ImputeNode(
      field = imputed.field ?: return null,
      key = key.field ?: return null,
      method = method,
      value = params.fields["value"],
      groupby = Marks.pathGroupingFields(view),
      keyvals = keyvals,
      frame = params.fields["frame"],
    )
  }

  /**
   * Whether a dimension is grouped by **both** of its edges — `hasBandEnd` in `channeldef.ts`.
   *
   * A bin always is: the span is what the bar covers. A **bucketed instant** only is where the mark
   * has a band to sit in, which upstream asks by looking for a `timeUnitBandPosition` — a rect and
   * a bar define one, a line and an area do not. Adding the far edge for every time unit puts a
   * column into the grouping that nothing computes, and the aggregate then groups by a name that is
   * not there.
   */
  private fun hasBandEnd(def: ChannelDef): Boolean {
    if (def.bin is Binning.Bin) return true
    if (def.timeUnit == null || def.type != MeasureType.TEMPORAL) return false
    val secondary = secondaryChannel(def.channel)?.let { view.spec.encoding[it] }
    if (secondary != null) return false
    if (def.raw.number("bandPosition") != null) return true
    return view.config.markConfig(view.spec.mark).number("timeUnitBandPosition") != null
  }

  private fun stackNode(): StackNode? {
    val stack = view.stack ?: return null
    val def = view.spec.encoding[stack.fieldChannel] ?: return null
    val stackBy = stack.stackBy.map { Fields.vgField(it) }
    // The `order` channel says how the segments are laid within a bar, and it says it in two ways.
    // A **field** def orders by that column — `sortParams`, ascending unless it says otherwise —
    // and an **order-only** def (`{"sort": "ascending"}`, no field) keeps the stacking fields and
    // changes only the direction. Without either, the segments go in field order: downwards on a
    // vertical stack so the first category ends on top, upwards on a horizontal one.
    val orderDef = view.spec.encoding["order"]
    val orderFields = listOfNotNull(orderDef) + orderDef?.siblings.orEmpty()
    val order =
      when {
        orderDef?.isFieldDef == true -> null
        orderDef != null -> (orderDef.sort as? VegaValue.Str)?.value ?: "ascending"
        stack.fieldChannel == "y" -> "descending"
        else -> "ascending"
      }
    // A **binned** dimension groups by both of its edges, so two bins that happen to start at the
    // same place are still two columns. For a column that arrived already binned the far edge has
    // no `_end` name of its own, and upstream's `vgField(def, {binSuffix: 'end'})` gives the field
    // back unchanged — so the groupby names it twice, which is what it emits.
    val dimensions =
      stack.groupbyChannels.flatMap { channel ->
        val dimension = view.spec.fieldDef(channel) ?: return@flatMap emptyList()
        if (dimension.bin != null || hasBandEnd(dimension)) {
          listOf(
            Fields.vgField(dimension),
            if (dimension.bin is Binning.Bin) Fields.vgField(dimension, suffix = "end")
            else Fields.vgField(dimension),
          )
        } else {
          listOf(Fields.vgField(dimension))
        }
      }
    return StackNode(
      field = Fields.vgField(def),
      // The facet's own fields group every accumulation, so a stack stays inside its cell.
      groupby = dimensions + view.facetFields.filterNot { it in dimensions },
      sortFields =
        if (order == null) orderFields.map { Fields.vgField(it) }.distinct() else stackBy,
      sortOrders =
        if (order == null) {
          orderFields.map { (it.sort as? VegaValue.Str)?.value ?: "ascending" }
        } else {
          stackBy.map { order }
        },
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
    // `getDataSourcesForHandlingInvalidValues`: only a mode that *excludes* invalid values from the
    // marks filters here. A path that breaks at the gap still needs the row — the break is drawn
    // from it — and `show` draws the row outright.
    if (view.invalidDataMode != "filter") return null

    // Keyed by the **raw** field, which is how upstream's aggregator is keyed, so two channels
    // reading one column through different buckets leave only the last of them: `d` bucketed by
    // month on x and by hour on y is filtered on the hour alone.
    val byField = LinkedHashMap<String, String>()
    for ((channel, def) in view.spec.encoding) {
      val field = def.field
      if (channel !in Channels.SCALE_CHANNELS || !def.isFieldDef || field == null) continue
      // A counting aggregate is never invalid: it is produced by the aggregate rather than read
      // from the data, and it counts what is there.
      if (def.aggregate in COUNTING_OPS) continue
      // A **discrete** scale can always show an invalid value as another category, so only the
      // fields feeding a continuous domain need filtering. Reading the field's own *type* instead
      // filtered a binned colour column, whose scale is `bin-ordinal` and shows every bucket.
      val type = view.scaleType(channel) ?: continue
      if (!Scales.hasContinuousDomain(type)) continue
      val accessor = Fields.datumAccess(def)
      byField[field] =
        when (def.type) {
          MeasureType.TEMPORAL ->
            "(isDate($accessor) || (isValid($accessor) && isFinite(+$accessor)))"
          else -> "isValid($accessor) && isFinite(+$accessor)"
        }
    }
    val expressions = byField.values.toList()
    return if (expressions.isEmpty()) null else FilterInvalidNode(expressions.distinct())
  }

  /**
   * The `transform` block, translated by [Transforms] — **one node per step**.
   *
   * A chain rather than a single node, because two views that begin with the same steps and then
   * differ are one flow that forks, not two flows: only a per-step node lets the shared prefix be
   * recognised as shared. It changes nothing where there is no fork, since consecutive steps land
   * in the same dataset anyway; where there is one — a box plot's outliers and its whiskers both
   * begin by finding the quartiles — it is the difference between computing them once and twice.
   */
  private fun userTransforms(head: DataNode): DataNode {
    var last = head
    for (transform in
      Transforms(diagnostics, registerLookup).translate(view.spec.transforms, "$.transform")) {
      last = last.then(PassThroughNode(listOf(transform)))
    }
    return last
  }
}
