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
  /**
   * Registers a `lookup`'s second dataset under an output name, answering with that name.
   *
   * The name is the caller's, not the table's: see [Transforms.lookupOrdinal].
   */
  private val registerLookup: ((VegaValue, String) -> String)? = null,
  /**
   * Whether a selection remembers its rows by **identity**, which needs a column to remember.
   *
   * `requiresSelectionId`: an `identifier` transform writes `_vgsid_` onto every row at the head of
   * the flow, and again wherever new rows are *made* — after an aggregate, whose output tuples are
   * not the rows that went in and so have no identity of their own yet.
   */
  private val needsIdentity: Boolean = false,
  /**
   * Whether **this view** declares a selection that remembers rows by identity.
   *
   * The identifier at the head of the flow is the chart's — one column serving every dataset below
   * it, kept while anything in the chart needs it. The one *after an aggregate* is the view's own:
   * `requiresSelectionId(model)` asks the unit, because an aggregate's output rows are not the rows
   * that went in and have no identity yet. A layer of two bars, one of them hovered over, is where
   * it tells — the hovered one needs a `_vgsid_` after its aggregate and the other does not, which
   * is what makes the two of them two datasets rather than one chain with a stray identifier in it.
   */
  private val ownsIdentity: Boolean = false,
  /**
   * Whether the model **named** here, or anything below it, remembers its rows by identity.
   *
   * `parseTransformArray` runs once per model and asks `requiresSelectionId(model)` of that model —
   * which for a layer is `forEachSelection`, its own components and its members'. A transform
   * written on a layer is that layer's however many members carry a copy of it, so the identifier
   * below it is the layer's too.
   */
  private val identityUnder: (String) -> Boolean = { ownsIdentity },
  /**
   * Where the flow **splits into cells**, or null where the whole chain is computed once.
   *
   * Supplied by the compiler, which knows whether the cell's chain can be hoisted above the facet:
   * `moveFacetDown` stops at a named point the scales read, and the pre-aggregation table a sorted
   * domain asks for is such a point.
   */
  private val facetSplit: FacetNode? = null,
  /**
   * The partition a faceted view's cells are cut from, where the flow does **not** split at it.
   *
   * A facet is a node in the flow whether or not anything hangs below it, and a node in the flow
   * takes a name: the dataset it reads is named when the walk reaches it, so a chart with a grid in
   * it numbers the tables after that grid one higher than a chart without.
   */
  private val facetTail: FacetNode? = null,
  /**
   * Whether the partition is a **fork**: more than one view hangs below it.
   *
   * ```ts
   * if (node.numChildren() === 1 && !(node.children[0] instanceof OutputNode)) {
   *   // move down until we hit a fork or output node
   * ```
   *
   * `moveFacetDown` hoists the chain below the partition above it one node at a time, and the walk
   * runs while the partition has a **single** child. A cell of one view is that: its own steps —
   * its buckets, its instants, the columns its marks are ordered by — climb above the partition
   * until they meet the named point the scales read, and the split is there. A cell of several is
   * not: the walk stops at the fork, so every member's own steps stay below, where they are
   * computed over the rows one cell was handed.
   *
   * The fold that makes two members' identical steps one node runs **after** the walk — it is in
   * the second pass, and the facet is moved between the two — so a step both members write is
   * folded below the partition and not hoisted above it.
   */
  private val facetForked: Boolean = false,
  /** The selections some `lookup` reads as a table, which are the ones worth materialising. */
  private val materialized: Set<String> = emptySet(),
  /** Where each materialised selection's output node is recorded, for the join to be named from. */
  private val lookupOutputs: MutableMap<String, OutputNode> = mutableMapOf(),
  /**
   * The partitions standing **above** [facetSplit], outermost first.
   *
   * A grid whose cells are grids cuts the table twice: the outer level partitions the whole of it
   * and the inner level partitions each of those pieces. The chain the marks read hangs below the
   * innermost partition, which is why the split is there and these only pass through.
   */
  private val facetAbove: List<FacetNode> = emptyList(),
) {

  /** The facet fields the chain being built groups by, which the cell's own chain does not. */
  private var facetting: List<String> = emptyList()

  /** The two named points a view exposes: the table before aggregation, and the one marks read. */
  /**
   * The named points a view exposes.
   *
   * [main] is what the marks read and [scales] what the domains measure, and they are the same node
   * unless the specification asked for a path that breaks at a gap the domain does not want.
   */
  class Outputs(
    val raw: OutputNode?,
    val main: OutputNode,
    val scales: OutputNode = main,
    /**
     * What the **cell's** marks read, where the flow splits at the facet.
     *
     * The chain below a facet is computed inside each cell, over the rows that cell was handed; the
     * one beside it is the same chain with the facet's own fields added to every grouping, computed
     * once for the scales to measure. Null where nothing splits, which is the ordinary case.
     */
    val cell: OutputNode? = null,
    val facet: FacetNode? = null,
  )

  fun build(source: SourceNode): Outputs {
    // A **later** layer of a faceted cell hangs from the partition the first one put in.
    // `facetRoot` is one node with every child's chain under it, so the second layer does not get
    // a facet of its own; its own steps go below the shared one, and beside it in the copy the
    // scales measure.
    facetSplit
      ?.takeIf { it.attached }
      ?.let { shared ->
        val inside = cellChain(headChain(shared, Scope.OWN, emptyList()), emptyList())
        val outside =
          cellChain(headChain(shared.main!!, Scope.OWN, view.facetFields), view.facetFields)
        return Outputs(outside.raw, outside.main, outside.scales, inside.main, shared)
      }

    // Where the flow **splits into cells**. `moveFacetDown` hoists the cell's chain above the facet
    // wherever it can, adding the facet's own fields to every grouping it passes — which is what
    // this compiler does for every faceted chart — and stops at a named point the scales read. Then
    // the chain below stays where it is, computed per cell, and a *copy* of it with the facet's
    // fields added is hung beside the facet for the scales. The two answer different questions: a
    // cell's aggregate is over its own rows, and the scale's is over all of them.
    val facetNode = facetSplit
    if (facetNode != null) {
      // One output above the facet, not two. `moveMainDownToFacet` walks the facet model's own
      // **main** output down until the facet is its only child, and that is the whole of what
      // stands between the table and the partition — a second, raw one would spend a dataset name
      // that upstream's numbering never spends.
      val facetMain = OutputNode(view.prefixed("facet_main"))
      // At a **fork** the facet model's own pass and nothing of the cell's: what a member writes
      // for itself is written below the partition, in every cell, exactly as a *later* member's
      // is. Hoisting the first member's own steps instead put its sort index — or its buckets, or
      // its instants — above a partition the others' stayed below, which is a cell computing one
      // member's columns over rows it shares with the whole grid. Where one view hangs below, its
      // own steps do climb: that is the walk, and there is no fork to stop it. See [facetForked].
      headChain(source, if (facetForked) Scope.FACET else Scope.WHOLE, view.facetFields)
        .then(facetMain)
      // Every level's partition in turn, the outermost reading the table and each cutting the piece
      // the one above handed it.
      var above: DataNode = facetMain
      for (outer in facetAbove) {
        above.then(outer)
        above = outer
      }
      above.then(facetNode)
      facetNode.attachedTo(facetMain)
      val inside =
        cellChain(
          if (facetForked) headChain(facetNode, Scope.OWN, emptyList()) else facetNode,
          emptyList(),
        )
      val outside =
        cellChain(
          if (facetForked) headChain(facetMain, Scope.OWN, view.facetFields) else facetMain,
          view.facetFields,
        )
      return Outputs(outside.raw, outside.main, outside.scales, inside.main, facetNode)
    }

    // The partition hangs off the **end** of the chain here: nothing of the cell's is below it, so
    // everything the view states was walked past and is grouped by the grid's columns as well as
    // its own. A chart with no grid at all has no such columns, and this is the same call.
    val chain = cellChain(headChain(source, Scope.WHOLE, view.facetFields), view.facetFields)
    facetTail?.let { chain.main.then(it) }
    return chain
  }

  /**
   * Which model's steps a chain is being built from, where a partition stands between two of them.
   *
   * `parseData` runs per model: a facet's own pass writes its transforms, its buckets and the
   * columns its cells are ordered by, and the partition goes in at the end of it; its child's pass
   * then writes the child's, below the partition. Where nothing splits there is one chain and one
   * scope — [WHOLE] — because the two models' steps stand in the same table either way.
   */
  private enum class Scope {
    /** The facet model's own: what stands **above** the partition. */
    FACET,
    /** The cell's own: what hangs below it. */
    OWN,
    /** Both, in the order the two passes wrote them. */
    WHOLE;

    val facet: Boolean
      get() = this != OWN

    val own: Boolean
      get() = this != FACET
  }

  /** Everything a view does **before** it aggregates: its transforms, its buckets, its instants. */
  private fun headChain(
    parent: DataNode,
    scope: Scope = Scope.WHOLE,
    /**
     * The facet's fields, where **this** chain is one the partition was walked past.
     *
     * Empty for the copy that hangs below the partition, whose steps are computed over the rows one
     * cell was handed and have nothing to add. See [UnitView.gridTransforms].
     */
    hoistedOver: List<String> = emptyList(),
  ): DataNode {
    var head: DataNode = parent
    // ```js
    // // Default discrete selections require an identifer transform to
    // // uniquely identify data points. Add this transform at the head of
    // // the pipeline such that the identifier field is available for all
    // // subsequent datasets.
    // head = new IdentifierNode(head);
    // ```
    //
    // `parseData` runs for **every model**, so the identifier at the head of the flow is written by
    // the model above the views — one node, above the fork — and each view's own copy is taken out
    // again by `RemoveUnnecessaryIdentifierNodes`, its parent being that model's output rather than
    // a table. Written once per view here, the copies are one node only when the fold reaches them,
    // which is a round later than upstream: everything below them was still forked while the round
    // that settles which of two identical steps survives was running, and a pair of aggregates
    // upstream folds into the last folded into the first instead. Marked as the ancestor's, they
    // are folded before the optimizer loop begins, which is where upstream starts.
    if (needsIdentity && parent is SourceNode)
      head = head.then(identifierNode()).also { it.fromAncestor = true }

    // What an **ancestor** wrote — a facet's own transforms above its cell's — stands first: that
    // model's pass ran first, and a step of the cell's cannot climb above one that computes the
    // column it reads.
    if (scope.facet)
      head =
        userTransforms(
          head,
          Written.ANCESTOR,
          hoistedOver,
          // **The grid's own**, and only those, where the flow is cut here. A layer inside the cell
          // is an ancestor of its members and no part of the grid above them: its transforms were
          // written in a pass that runs below the partition, not above it. Taken for the grid's,
          // they were written above a cut they belong below — where the chain above is built from
          // one view and knows nothing of them, so they were written nowhere at all and the rows a
          // member filters for itself were drawn by every mark in the cell.
          ofGrid = if (scope == Scope.FACET) true else null,
        )
    // The **facet's** own bucketing stands with the rest of the facet model's pass, above the
    // partition: the values its cells are cut by are those buckets, so the column has to be there
    // before the cut is made — and so does the parse the bucketing reads, which `parseData` puts
    // between the transforms and the bin for every model alike.
    if (scope == Scope.FACET) {
      implicitParse(scope)?.let { head = head.then(it) }
      binNode(scope)?.let { head = head.then(it) }
    }
    // The grid's own bucketing of an **instant** belongs to the same pass, and so stands above the
    // cell's transforms rather than below them: `parseData` runs per model, top-down, and a column
    // the grid is cut by is computed before any step of the cell's. Written with the cell's, a
    // trellis whose columns are years listed its `timeunit` after a `calculate` the cell asked for.
    if (scope.facet) timeUnitNode(Scope.FACET)?.let { head = head.then(it) }
    if (scope.own) {
      // Below the partition, what a **layer inside the cell** wrote stands first: that model's pass
      // ran before its members', exactly as it does with no grid around them.
      if (scope == Scope.OWN)
        head = userTransforms(head, Written.ANCESTOR, hoistedOver, ofGrid = false)
      // A layer's member buckets its field before **its own** transforms: upstream calls it a hack
      // "equivalent for merging bin extent for union scale", and it is what lets two layers over
      // one binned field share a bin. Below a filter the two bins are no longer siblings and
      // neither the extent nor the bin width can be merged, so each layer buckets what it can see.
      // What an *ancestor* wrote still stands above it — that model's pass ran first — and a
      // bucketing of a column an ancestor computes cannot climb above the step that computes it.
      if (view.parentIsLayer) binNode(scope)?.let { head = head.then(it) }

      head = userTransforms(head, Written.OWN, hoistedOver)
      implicitParse(scope)?.let { head = head.then(it) }
      // A place on the globe is not a position on the page until a **projection** has been asked
      // where it lands. `GeoJSONNode` gathers the pairs into a feature collection the projection
      // can be fitted to, and `GeoPointNode` then asks it, writing the two pixels onto every row.
      geoJsonNodes().forEach { head = head.then(it) }
      geoPointNodes().forEach { head = head.then(it) }
      if (!view.parentIsLayer) binNode(scope)?.let { head = head.then(it) }
    }
    if (scope.own) timeUnitNode(Scope.OWN)?.let { head = head.then(it) }
    if (scope.own) binnedTimeUnitNode()?.let { head = head.then(it) }
    sortIndexNodes(scope).forEach { head = head.then(it) }
    // The key a crossed grid's cells are ordered by is written onto every row *above* the facet,
    // where every cell's rows can still be seen at once — never again below it.
    if (scope.facet)
      facetSortKeys()?.let {
        head = head.then(it)
      }
    return head
  }

  /**
   * The part of the flow that answers for one view's own rows: the aggregate and everything after.
   *
   * Built twice where a facet splits the flow — once below it without the facet's fields, once
   * beside it with them — and once otherwise.
   */
  private fun cellChain(parent: DataNode, facetFields: List<String>): Outputs {
    facetting = facetFields
    var head: DataNode = parent
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

    aggregateNode()?.let {
      head = head.then(it)
      if (ownsIdentity) head = head.then(identifierNode())
    }
    imputeNode()?.let { head = head.then(it) }
    stackNode()?.let { head = head.then(it) }

    // The scales measure the rows *before* the filter where they want the invalid ones and the
    // marks do not — a named point above the filter, which is upstream's `preFilterInvalid`.
    val preFilter =
      if (view.marksExcludeInvalid && !view.scalesExcludeInvalid) {
        OutputNode(view.prefixed("prefilter")).also {
          head.then(it)
          head = it
        }
      } else {
        null
      }
    if (view.marksExcludeInvalid) filterInvalidNode()?.let { head = head.then(it) }

    val main = OutputNode(view.prefixed("main"))
    head.then(main)
    head = main

    // `materializeSelections`: every selection this view declares can be **read as a table** — the
    // rows it has picked — which is what a `lookup` naming a parameter joins against. It hangs off
    // the view's main output as a filter of its own, and is built only where something asks for it,
    // an output nobody reads being an output upstream never assembles.
    for (selection in view.selections.filter { it.owner === view && it.name in materialized }) {
      val picked =
        PassThroughNode(
          listOf(
            obj {
              put("type", "filter")
              put("expr", selection.test())
            }
          )
        )
      main.then(picked)
      val output = OutputNode(view.prefixed("lookup_${selection.name}"))
      picked.then(output)
      lookupOutputs["lookup_${selection.name}"] = output
    }

    // And *below* the filter where the marks want the invalid rows and the scales do not: a path
    // drawn with a break at the gap, over a domain measured without it — `postFilterInvalid`.
    val post =
      if (!view.marksExcludeInvalid && view.scalesExcludeInvalid) {
        filterInvalidNode(force = true)?.let { filter ->
          head = head.then(filter)
          OutputNode(view.prefixed("postfilter")).also { head.then(it) }
        }
      } else {
        null
      }
    return Outputs(raw, main, post ?: preFilter ?: main)
  }

  /** `IdentifierNode`: the transform that gives every row a `_vgsid_` to be remembered by. */
  private fun identifierNode(): PassThroughNode =
    PassThroughNode(
      listOf(
        obj {
          put("type", "identifier")
          put("as", Selection.SELECTION_ID)
        }
      )
    )

  companion object {
    /**
     * Whether this view needs the **pre-aggregation** table named.
     *
     * A sort the specification stated may read a column the aggregation removes, and that is what
     * the pre-aggregation table is for. It is also what stops a facet from being moved down past
     * the cell's chain, so the compiler asks the same question before it decides to split.
     */
    fun needsRawTable(view: UnitView): Boolean =
      view.scaledChannels().any { (channel, def) ->
        val type = view.scaleType(channel) ?: return@any false
        Scales.readsRawTable(view, channel, def, type)
      }
  }

  /**
   * Whether anything **reads** the pre-aggregation table, which is what decides that it exists.
   *
   * A sort the *specification* stated may read a column the aggregation removes, and that is what
   * the pre-aggregation table is for. Whether it is actually read is [Scales.readsRawTable]'s
   * question, not this one's: a scale whose domain is stated outright reads no table at all,
   * however it says to sort.
   */
  private fun needsRawTable(): Boolean = needsRawTable(view)

  /**
   * A field arrives as text and has to become what the encoding says it is before anything orders
   * it.
   *
   * Upstream calls this the implicit parse, as against the `format.parse` a specification writes
   * itself. It is implicit but not optional, and every case here is a *comparison* that would
   * otherwise be made between strings: a time axis sorting its dates alphabetically, a `min` over
   * "10" and "9" answering "10", a line joining its points in the order 1, 10, 2.
   */
  private fun implicitParse(scope: Scope = Scope.WHOLE): ParseNode? {
    val parse = LinkedHashMap<String, String>()
    // A filter's comparisons say what type its column holds, and that has to be settled before the
    // filter runs — so these parses belong with the encoding's, not after them.
    if (scope.own) {
      parse.putAll(
        Transforms(diagnostics, selections = view.selections).implicitParses(view.spec.transforms)
      )
    }
    // `forEach(this.getMapping(), …)` walks a **list** channel entry by entry — a tooltip naming
    // four columns is four definitions, not one — and `getFieldDef` reaches into a `condition`.
    // Reading only the channel's own definition left every column after the first unparsed, so a
    // tooltip's second nested field was looked for under a name no row has.
    //
    // The **facet's** own channels first: `getImplicitFromEncoding` is asked of a facet model as
    // much as of a unit — `if (isUnitModel(model) || isFacetModel(model))` — and a grid split by a
    // date has to read that column as a date before it can be cut by the month it falls in. The
    // cell's encoding no longer mentions the column, so nothing here asked for it and the cut was
    // made on text. A facet model's pass runs first, which is what puts its columns first.
    val everyDefinition =
      (if (scope.facet) view.facetDeclared else emptyList()) +
        (if (!scope.own) emptyList()
        else view.spec.encoding.values.flatMap { listOf(it) + it.siblings + it.conditions })
    for (def in everyDefinition) {
      val field = def.field
      if (!def.isFieldDef || field == null) continue
      // A time unit buckets a *date*, so the column still has to be read as one first — and a time
      // unit is what makes a field an instant, whatever type the encoding gave it. A month named on
      // an ordinal scale is bucketed from a date exactly as a temporal one is.
      // No exception for an aggregate: a `mean` over a date column still needs the column read as
      // dates first, or it averages the strings' character codes.
      if (def.type == MeasureType.TEMPORAL || def.timeUnit != null) {
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
    if (scope.own && view.spec.mark in PATH_MARKS && view.spec.encoding["order"] == null) {
      val def = view.spec.encoding[if (view.markDef.orient == "horizontal") "y" else "x"]
      val field = def?.field
      if (def != null && def.isFieldDef && field != null && def.type == MeasureType.QUANTITATIVE) {
        parse.getOrPut(field) { "number" }
      }
    }
    // A column a transform computed is *derived*: it has the type its transform gave it, and the
    // loader has never seen it.
    parse.keys.removeAll(
      Transforms(diagnostics, selections = view.selections)
        .producedFields(view.spec.transforms, view.spec.data)
    )
    // The specification's own `data.format.parse` — the **explicit** half — and it comes *first*.
    //
    // `ParseNode.makeExplicit` runs before the transforms and the implicit parse after them, so
    // where the two meet the stated half is the one above: `keys(this._parse)` is insertion order,
    // and the formulas a parse writes come out in it. Adding the stated half last put them the
    // other way round, so a chart stating how to read one column and leaving another to be inferred
    // read them in the opposite order to upstream — five specifications in the wild corpus.
    //
    // An explicit parse also **wins** over an inferred one, and a stated `null` denies it
    // altogether: `ancestorParse` records the null so that nothing below adds a parse for that
    // column. Written out, a null asked Vega's loader to parse a column as `null`, which it
    // reported and then ignored.
    //
    // For a table read from a **url** as much as for one written out: `makeExplicit` asks
    // `model.data.format.parse` whatever the table is, and the node then decides where its work
    // lands — `format.parse` on the loader where it sits directly under the source, a formula where
    // it does not. The source node carries `omit(data.format, ['parse'])`, so a url's parse reaches
    // Vega through the node rather than by being copied across.
    val stated = LinkedHashMap<String, String?>()
    if (scope.own) {
      view.spec.data?.obj("format")?.obj("parse")?.fields?.forEach { (field, kind) ->
        when (kind) {
          is VegaValue.Str -> stated[field] = kind.value
          VegaValue.Null -> stated[field] = null
          else -> {}
        }
      }
    }
    if (stated.isEmpty()) return if (parse.isEmpty()) null else ParseNode(parse)
    val merged = LinkedHashMap<String, String>()
    stated.forEach { (field, kind) -> if (kind != null) merged[field] = kind }
    parse.forEach { (field, kind) -> if (field !in stated) merged[field] = kind }
    return if (merged.isEmpty()) null else ParseNode(merged)
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
  /**
   * `makeJoinAggregateFromFacet`: the key a **crossed** grid orders its cells by, written onto the
   * rows before the cells are made.
   *
   * A cell of a crossed grid is one row-value and one column-value together, so its group is keyed
   * by both — and a sort that orders the *rows* by an aggregate is grouped by the row field alone.
   * The two groupings cannot be the same aggregate, so upstream computes the sort key first, over
   * its own grouping, and the cell then takes the greatest of what its rows already carry. In a
   * grid of one direction the cell's own aggregate is that grouping, and no such column is needed.
   */
  private fun facetSortKeys(): PassThroughNode? {
    val facets = view.facetDefs.filter { it.channel == "row" || it.channel == "column" }
    if (facets.size < 2) return null
    val transforms = facets.mapNotNull { def ->
      val sort = (def.sort as? VegaValue.Obj)?.takeIf { it.has("field") } ?: return@mapNotNull null
      val source = sort.string("field") ?: return@mapNotNull null
      val op = sort.string("op") ?: "min"
      val named = if (sort.string("op") != null) "${op}_$source" else source
      obj {
        put(
          "type",
          "joinaggregate",
        )
        put("as", strings(listOf("${named}_by_${Fields.vgField(def)}")))
        put("ops", strings(listOf(op)))
        put("fields", strings(listOf(source)))
        put("groupby", strings(listOf(Fields.vgField(def))))
      }
    }
    return if (transforms.isEmpty()) null else PassThroughNode(transforms)
  }

  private fun sortIndexNodes(scope: Scope = Scope.WHOLE): List<PassThroughNode> {
    val predicates = Transforms(diagnostics, selections = view.selections)
    // The **facet's** own channels as well as the encoding's: a trellis whose rows are listed in a
    // stated order needs the same index column, and the facet channel was lifted out of the
    // encoding before this ran. `parseAllForSortIndex` is asked on the facet's model too — on that
    // model, though, so below the partition the column is already there and is not written again.
    // `forEachFieldDef` walks the facet as it was **written**, which is what orders the two
    // formulas a crossed grid needs.
    // The **grid's** own index is written first: `model.parse()` is top-down, so a facet model
    // parses its data before its child's and its sort index lands above the cell's chain. Appending
    // it instead ordered the formulas the other way about — four specifications in the wild corpus
    // are a trellis whose columns and whose marks are both listed in a stated order, and every one
    // of their three formulas came out in the wrong place.
    val channels =
      (if (scope.facet) view.facetDeclared.map { it.channel to it } else emptyList()) +
        (if (!scope.own) emptyList()
        else
          view.spec.encoding.entries.flatMap { (channel, def) ->
            (listOf(def) + def.siblings + def.conditions).map { channel to it }
          })
    val transforms = channels.mapNotNull { (channel, def) ->
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
    // One node per column, not one node carrying them all: `sortArrayIndexField` builds a
    // `CalculateNode` per channel, and a node is what the fold works on. Two members of a cell that
    // order their marks by the same list write the same calculate, and folding *those* two leaves
    // whatever else each of them writes where it was — which a single node holding both columns
    // cannot do, being equal to neither of the others.
    return transforms.map { PassThroughNode(listOf(it)) }
  }

  private fun binNode(scope: Scope = Scope.WHOLE): BinNode? {
    val bins =
      // The **facet's** own channels first, as with the time units: a trellis broken down by
      // buckets of a column has to bucket that column, and the facet's encoding was lifted out of
      // the cell's before anything else looked at it.
      ((if (scope.facet) view.facetDefs else emptyList()) +
          (if (!scope.own) emptyList()
          else view.spec.encoding.values.flatMap { listOf(it) + it.siblings + it.conditions }))
        .mapNotNull { def ->
          val bin = def.bin as? Binning.Bin ?: return@mapNotNull null
          val field = def.field ?: return@mapNotNull null
          val key = "${Fields.binToString(bin.params)}_$field"
          // A facet's bucketing belongs to the **facet** model, which sits above the cell: its
          // signals are named plainly where a cell's carry the cell's prefix. And it needs no range
          // formula — `binRequiresRange` asks about a *scale* channel, and a facet has no scale.
          val facetted = def in view.facetDefs
          // `parseSelectionExtent`: an extent naming a **selection** is not an extent Vega
          // understands. The bucketing keeps the data's own, and how wide one bucket is becomes a
          // `span` read off the brush — so dragging the brush narrower cuts finer buckets over the
          // same range. The column is the one the selection projects onto, since that is the one
          // the
          // brush's numbers are in.
          val selected = (bin.params.fields["extent"] as? VegaValue.Obj)?.string("param")
          val span = selected?.let { name ->
            val selection = view.selections.firstOrNull { it.name == name }
            val on =
              (bin.params.fields["extent"] as? VegaValue.Obj)?.string("field")
                ?: selection?.owner?.let { owner ->
                  selection.projections(owner).firstOrNull()?.second
                }
                ?: field
            "${Fields.varName(name)}[${quoted(on)}]"
          }
          BinComponent(
            field = field,
            params = bin.params,
            span = span,
            output =
              listOf(
                Fields.vgField(def, forAs = true),
                Fields.vgField(def, suffix = "end", forAs = true),
              ),
            signal = if (facetted) "${key}_bins" else view.prefixed("${key}_bins"),
            extentSignal = if (facetted) "${key}_extent" else view.prefixed("${key}_extent"),
            extent = bin.params.fields["extent"]?.takeIf { selected == null },
            // `binRequiresRange`: a binned field the specification forced onto a **discrete** scale
            // needs its range written out as text, because that text is what the axis labels and
            // the
            // legend entries then read — there is no numeric axis left to derive them from.
            rangeFormula =
              if (
                !facetted && (def.type == MeasureType.ORDINAL || def.type == MeasureType.NOMINAL)
              ) {
                val start = Fields.datumAccess(def)
                val end = Fields.datumAccess(def, suffix = "end")
                val format = (def.format as? VegaValue.Str)?.value ?: view.config.numberFormat ?: ""
                "!isValid($start) || !isFinite(+$start) ? \"null\" : " +
                  "format($start, \"$format\") + \" – \" + format($end, \"$format\")"
              } else {
                null
              },
          )
        }
    return if (bins.isEmpty()) null else BinNode(bins.distinctBy { it.signal })
  }

  /**
   * The far edge of a bucket the *data* already carries — `binnedyearmonth` and its kin.
   *
   * There is nothing to bucket, so there is no `timeunit` transform: the column stays as it is and
   * a formula computes where its bucket ends, one unit of the smallest part on from the start.
   */
  /**
   * The two columns a bucket shifted off its own edges is drawn between.
   *
   * `offsetedRectFormulas`: a `bandPosition` other than the middle moves a bucketed rect *within*
   * its bucket, and the two ends it is drawn between are then not the bucket's own — they are
   * interpolated between the previous bucket's start and this one's, and between this one's start
   * and its end. A rect marking the gaps in a series is written that way: the bar sits over the
   * missing point rather than after it.
   */
  private fun offsettedRectFormulas(
    def: ChannelDef,
    channel: String,
    startField: String? = null,
    endField: String? = null,
  ): List<VegaValue> {
    val position = view.offsettedRectPosition(def, channel) ?: return emptyList()
    val timeUnit = def.timeUnit ?: return emptyList()
    val start = startField ?: Fields.vgField(def, forAs = true)
    val end = endField ?: Fields.vgField(def, suffix = "end", forAs = true)
    val fraction = position + 0.5
    val before = Fields.expressionNumber(1 - fraction)
    val after = Fields.expressionNumber(fraction)
    val offset = if (timeUnit.contains("utc")) "utcOffset" else "timeOffset"
    val part = Fields.timeUnitParts(timeUnit).lastOrNull() ?: return emptyList()
    return listOf(
      obj {
        put("type", "formula")
        put(
          "expr",
          "$before * $offset('$part', ${Fields.datumPath(start)}, -1) + $after * ${Fields.datumPath(start)}",
        )
        put("as", "${start}_offsetted_rect_start")
      },
      obj {
        put("type", "formula")
        put(
          "expr",
          "$before * ${Fields.datumPath(start)} + $after * ${Fields.datumPath(end)}",
        )
        put("as", "${start}_offsetted_rect_end")
      },
    )
  }

  private fun rectBased(): Boolean = view.spec.mark in RECT_BASED_MARKS

  /**
   * `getBandPosition`, as far as a bucketed column needs it: the definition's, then the theme's.
   */
  private fun bandPositionOf(def: ChannelDef): Double? =
    def.raw.number("bandPosition")
      ?: view.config.markConfig(view.spec.mark).number("timeUnitBandPosition")

  /** A stated coordinate as JavaScript writes it, which is what `${def.datum}` amounts to. */
  private fun plainly(value: VegaValue): String =
    when (value) {
      is VegaValue.Str -> value.value
      is VegaValue.Num -> Fields.expressionNumber(value.value)
      else -> value.toString()
    }

  /** The coordinate pairs a view encodes, in `[longitude, latitude]` order and outermost first. */
  private fun geoPairs(): List<Pair<Int, List<VegaValue>>> =
    listOf(listOf("longitude", "latitude"), listOf("longitude2", "latitude2")).mapIndexedNotNull {
      index,
      pair ->
      val refs = pair.map { channel ->
        val def = view.spec.encoding[channel]
        when {
          def == null -> VegaValue.Null
          def.isFieldDef -> VegaValue.Str(def.field ?: "")
          // `{expr: "${def.datum}"}` — the value written out as an expression, which for a number
          // is the number itself and for a name is the name Vega will evaluate.
          def.datum != null -> obj { put("expr", plainly(def.datum)) }
          def.value != null -> obj { put("expr", plainly(def.value)) }
          else -> VegaValue.Null
        }
      }
      if (refs.all { it == VegaValue.Null }) null else index to refs
    }

  /**
   * `GeoJSONNode.parseAll`: the feature collection a projection is **fitted** to.
   *
   * Only where the projection is fitted at all — one that states its own `scale` or `translate` has
   * been placed by hand and needs nothing to measure itself against.
   */
  private fun geoJsonNodes(): List<PassThroughNode> {
    if (!view.hasProjection || !view.projectionFits) return emptyList()
    val pairs =
      geoPairs().map { (index, refs) ->
        PassThroughNode(
          listOf(
            obj {
              put("type", "geojson")
              put("fields", arr(refs))
              put("signal", view.prefixed("geojson_$index"))
            }
          )
        )
      }
    // A **shape** column of outlines is a feature collection of its own, and the rows that have no
    // outline are dropped first: `isValid` before the gathering, because a row with nothing to draw
    // would otherwise widen the extent the map is fitted to by nothing at all.
    val outlines =
      view.spec.encoding["shape"]
        ?.takeIf { it.isFieldDef && it.type == MeasureType.GEOJSON }
        ?.let { def ->
          val field = Fields.vgField(def)
          PassThroughNode(
            listOf(
              obj {
                put("type", "filter")
                put("expr", "isValid(datum[${quoted(field)}])")
              },
              obj {
                put("type", "geojson")
                put("geojson", field)
                put("signal", view.prefixed("geojson_${pairs.size}"))
              },
            )
          )
        }
    return pairs + listOfNotNull(outlines)
  }

  /** `GeoPointNode.parseAll`: the two pixels a projection puts each pair at. */
  private fun geoPointNodes(): List<PassThroughNode> {
    if (!view.hasProjection) return emptyList()
    return geoPairs().map { (index, refs) ->
      val suffix = if (index == 1) "2" else ""
      PassThroughNode(
        listOf(
          obj {
            put("type", "geopoint")
            put("projection", view.projectionName)
            put("fields", arr(refs))
            put("as", strings(listOf(view.prefixed("x$suffix"), view.prefixed("y$suffix"))))
          }
        )
      )
    }
  }

  private fun binnedTimeUnitNode(): PassThroughNode? {
    val formulas =
      view.spec.encoding.values.flatMap { def ->
        val timeUnit =
          def.timeUnit?.takeIf { Fields.isBinnedTimeUnit(it) } ?: return@flatMap emptyList()
        // The column's own name, unescaped: a formula reads and writes a *column*, where the
        // escaping is for references Vega would otherwise read as a path.
        val field =
          def.field?.let { Fields.splitAccessPath(it).joinToString(".") }
            ?: return@flatMap emptyList()
        val part = Fields.timeUnitParts(timeUnit).lastOrNull() ?: return@flatMap emptyList()
        // "For binned time unit, only produce end if the mark is a rect-based mark, which needs
        // *range*." A column that arrived bucketed already has its near edge; the far one is only
        // wanted where something is drawn *between* the two — a rect, or a mark placed anywhere in
        // the bucket but its middle. A label beside such a rect needs neither, and computing them
        // for it as well folded the two layers' steps into the table above them.
        if (!rectBased() && bandPositionOf(def).let { it == null || it == 0.0 }) {
          return@flatMap emptyList()
        }
        // A **universal** bucket is stepped in universal time: `utcOffset`, not `timeOffset`, or
        // the far edge lands an hour out wherever the viewer keeps daylight saving.
        val offset = if (timeUnit.contains("utc")) "utcOffset" else "timeOffset"
        val channel = view.spec.encoding.entries.firstOrNull { it.value === def }?.key
        listOf(
          obj {
            put("type", "formula")
            put("expr", "$offset('$part', ${Fields.datumPath(field)}, 1)")
            put("as", "${field}_end")
          }
        ) +
          // A bucket the rect sits **off the middle of** is drawn between two interpolated edges
          // here as well: a column that arrived bucketed is still a bucket, and a `bandPosition`
          // moves the rect within it exactly as it does one this compiler cut itself.
          channel?.let { offsettedRectFormulas(def, it, field, "${field}_end") }.orEmpty()
      }
    return if (formulas.isEmpty()) null else PassThroughNode(formulas, timeUnit = true)
  }

  private fun timeUnitNode(scope: Scope = Scope.WHOLE): TimeUnitNode? {
    // The facet's own channels first: their transform belongs to the facet model, which sits above
    // the cell's, so a trellis broken down by year buckets the year before it buckets the quarter.
    // `model.reduceFieldDef` spreads a **list** channel before it folds — a `tooltip` naming four
    // columns is four definitions — so an instant bucketed on the second entry of one is bucketed.
    // Reading only the channel's own definition left the transform unwritten, and the tooltip then
    // read a column no step in the flow produces.
    val defs =
      (if (scope.facet) view.facetDefs.map { null to it } else emptyList()) +
        (if (!scope.own) emptyList()
        else
          view.spec.encoding.entries.flatMap { (channel, def) ->
            (listOf(def) + def.siblings + def.conditions).map { channel to it }
          })
    val units = defs.mapNotNull { (channel, def) ->
      val timeUnit = def.timeUnit?.takeIf { !Fields.isBinnedTimeUnit(it) } ?: return@mapNotNull null
      val field = def.field ?: return@mapNotNull null
      TimeUnitComponent(
        field,
        Fields.timeUnitParts(timeUnit),
        Fields.vgField(def, forAs = true),
        step = Fields.timeUnitStep(timeUnit),
        utc = timeUnit.startsWith("utc"),
        offsettedRect = channel?.let { offsettedRectFormulas(def, it) }.orEmpty(),
      )
    }
    // Two channels bucketing one column the same way are one bucket: an x and a tooltip over
    // `yearmonthdate(date)` write the same column, and writing it twice is the same transform
    // emitted twice. `TimeUnitNode`'s components are a set upstream, keyed by what they produce.
    return if (units.isEmpty()) null else TimeUnitNode(units.distinctBy { it.output })
  }

  /**
   * The aggregate an encoding implies: every aggregated channel is a measure, every other field is
   * a grouping.
   */
  private fun aggregateNode(): AggregateNode? {
    if (
      view.spec.encoding.values.none { def ->
        (listOf(def) + def.siblings + def.conditions).any { it.aggregate != null }
      }
    ) {
      return null
    }

    val dimensions = LinkedHashSet<String>()
    val ops = mutableListOf<String>()
    val fields = mutableListOf<String?>()
    val outputs = mutableListOf<String>()

    // Every field definition, not only the channels' own: a `tooltip` written as a **list** holds
    // several, and one of them may be the only thing asking for an aggregate.
    // A channel's **conditions** hold field definitions of their own, and one may be the only
    // thing asking for an aggregate: `{"condition": {"param": …, "aggregate": "count"}}` is how a
    // chart counts the rows a reader picked.
    val everyDef = view.spec.encoding.values.flatMap { listOf(it) + it.siblings + it.conditions }
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
        // A rect shifted off the middle of its bucket is drawn between two *interpolated* edges,
        // and those are columns like any other: the grouping has to carry them, or the aggregate
        // throws away the very numbers the mark is placed by.
        val shiftedChannel = view.spec.encoding.entries.firstOrNull { it.value === def }?.key
        if (shiftedChannel != null && view.offsettedRectPosition(def, shiftedChannel) != null) {
          val stem = Fields.vgField(def, forAs = true)
          dimensions += "${stem}_offsetted_rect_start"
          dimensions += "${stem}_offsetted_rect_end"
        }
        // A binned field on a **discrete** scale groups by its label as well: the label is what
        // the axis lists, so it has to survive the aggregation alongside the two edges.
        if (
          def.bin is Binning.Bin &&
            (def.type == MeasureType.ORDINAL || def.type == MeasureType.NOMINAL)
        ) {
          dimensions += Fields.vgField(def, suffix = "range", forAs = true)
        }
      } else {
        ops += aggregate
        // An `argmin`/`argmax` is taken over the column it *names*, not the one being read.
        // The column an *encoding* aggregate reads is a **reference**, so a dot in its name is
        // escaped: an unescaped `properties.yield` tells Vega to look one level into `properties`.
        // A column named in a `transform` the specification wrote is left as the writer wrote it.
        fields +=
          when {
            aggregate == "count" -> null
            def.argumentField != null -> def.argumentField
            else -> def.field?.let { Fields.splitAccessPath(it).joinToString("\\.") }
          }
        outputs += Fields.vgField(def, forAs = true)
      }
    }

    for (field in facetting) dimensions += field
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
        // `getGroupbyFields`: only a **binned** dimension groups by both of its edges, so two bins
        // that happen to start at the same place are still two columns. A bucketed *instant* does
        // not — its `_end` is a column the time unit wrote and the stack has no use for it — and a
        // binned dimension under an **imputation** groups by the bin's *midpoint* instead, two
        // fields not being imputable at once.
        when {
          dimension.bin == null -> listOf(Fields.vgField(dimension))
          stack.impute -> listOf(Fields.vgField(dimension, suffix = "mid"))
          dimension.bin is Binning.Bin ->
            listOf(Fields.vgField(dimension), Fields.vgField(dimension, suffix = "end"))
          // A column that arrived already binned has no `_end` of its own, and upstream's
          // `vgField(def, {binSuffix: 'end'})` gives the field back unchanged.
          else -> listOf(Fields.vgField(dimension), Fields.vgField(dimension))
        }
      }
    return StackNode(
      field = Fields.vgField(def),
      // The facet's own fields group every accumulation, so a stack stays inside its cell.
      groupby = dimensions + facetting.filterNot { it in dimensions },
      // `if (!s.field.includes(field))` — the stack's sort names each field **once**. Two channels
      // over one column is one thing to sort by, and repeating it in the pair of parallel lists is
      // a comparator that reads the same column twice.
      sortFields =
        if (order == null) orderFields.map { Fields.vgField(it) }.distinct()
        else stackBy.distinct(),
      sortOrders =
        if (order == null) {
          orderFields.map { (it.sort as? VegaValue.Str)?.value ?: "ascending" }
        } else {
          stackBy.distinct().map { order }
        },
      output =
        listOf(
          Fields.vgField(def, suffix = "start", forAs = true),
          Fields.vgField(def, suffix = "end", forAs = true),
        ),
      offset = stack.offset,
      // `[...stackby, ...facetby]` — concatenated, not merged. A field that is both a series and
      // a facet is named twice, and the two lists are what upstream writes.
      imputeGroupby = stackBy + facetting,
      // A **bucketed** dimension is imputed at its midpoint, a bucket being two columns and an
      // imputation keying on one.
      imputeKeys =
        if (stack.impute) {
          stack.groupbyChannels.mapNotNull {
            view.spec.fieldDef(it)?.let { d ->
              Fields.vgField(d, suffix = if (d.bin is Binning.Bin) "mid" else null)
            }
          }
        } else {
          emptyList()
        },
      imputeFormulas =
        if (!stack.impute) emptyList()
        else
          stack.groupbyChannels.mapNotNull { channel ->
            val dimension = view.spec.fieldDef(channel) ?: return@mapNotNull null
            if (dimension.bin !is Binning.Bin) return@mapNotNull null
            val start = Fields.datumAccess(dimension)
            val end = Fields.datumAccess(dimension, suffix = "end")
            val near = dimension.raw.number("bandPosition") ?: 0.5
            obj {
              put("type", "formula")
              put(
                "expr",
                "isValid($start) && isFinite(+$start) ? " +
                  "${Fields.expressionNumber(near)}*$start+" +
                  "${Fields.expressionNumber(1 - near)}*$end : $start",
              )
              put("as", Fields.vgField(dimension, suffix = "mid", forAs = true))
            }
          },
      component =
        // `getStackByFields`: the segments are the **columns** they are keyed by, not the channels
        // that named them. A bar coloured by a series and the label drawn on it detailed by the
        // same series accumulate the same numbers the same way, and upstream stacks them once.
        "${stack.stackBy.map { Fields.vgField(it) }}|" +
          "${stack.groupbyChannels.map { view.spec.encoding[it]?.raw }}",
    )
  }

  /**
   * `FilterInvalidNode`: rows whose scaled numbers are missing are dropped before anything draws.
   *
   * A path mark is the exception and gets no filter at all — a line breaks at a gap instead,
   * through the mark's own `defined`, so that the gap is visible rather than closed over.
   */
  private fun filterInvalidNode(force: Boolean = false): FilterInvalidNode? {
    // `getDataSourcesForHandlingInvalidValues`: only a mode that *excludes* invalid values filters
    // here, and it is asked twice — once about the marks and once about the *scales*. A path that
    // breaks at the gap still needs the row, the break being drawn from it, so the filter that
    // keeps the gap out of the **domain** is made below the point the marks read rather than above
    // it. `show` draws the row outright and filters nothing.
    if (!force && !view.marksExcludeInvalid) return null

    // Keyed by the **raw** field, which is how upstream's aggregator is keyed, so two channels
    // reading one column through different buckets leave only the last of them: `d` bucketed by
    // month on x and by hour on y is filtered on the hour alone.
    val byField = LinkedHashMap<String, String>()
    // Keyed the same way, and holding what upstream's node is *identified* by: the definitions
    // themselves rather than the expression they compile to.
    val definitions = LinkedHashMap<String, String>()
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
      // A channel the *configuration* gives its own answer for an invalid value is not filtered
      // at all: whatever the mode says, `config.scale.invalid.<channel>` means the scale can show
      // one, so the row stays and the encoding's production rule paints it. Filtering it as well
      // removed the very rows that answer was written for.
      if (view.config.scaleInvalid(channel) != null) continue
      val accessor = Fields.datumAccess(def)
      definitions[field] = def.raw.toString()
      byField[field] =
        when (def.type) {
          MeasureType.TEMPORAL ->
            "(isDate($accessor) || (isValid($accessor) && isFinite(+$accessor)))"
          else -> "isValid($accessor) && isFinite(+$accessor)"
        }
    }
    val expressions = byField.values.toList()
    return if (expressions.isEmpty()) null
    else
    // Sorted by column, because upstream hashes the map with a **stable** stringify: two views
    // that drop rows for the same two columns are one node whichever channel each column is on.
    // A scatter-plot matrix is where it tells — the cell for (a, b) and the cell for (b, a) drop
    // exactly the same rows, and upstream gives them one dataset between them.
    FilterInvalidNode(expressions.distinct(), sortedIdentity(definitions))
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
  private fun text(value: VegaValue): String? = (value as? VegaValue.Str)?.value

  /** The bucketing the selections a `filter` names remember their rows by, as one node. */
  private fun selectionTimeUnits(transform: VegaValue): TimeUnitNode? {
    val named = mutableListOf<String>()
    fun scan(value: VegaValue?) {
      when (value) {
        is VegaValue.Obj -> {
          value.string("param")?.let { named += it }
          value.fields.values.forEach { scan(it) }
        }
        is VegaValue.Arr -> value.values.forEach { scan(it) }
        else -> Unit
      }
    }
    scan(transform["filter"])
    if (named.isEmpty()) return null
    val units =
      named
        .flatMap { name ->
          view.selections.firstOrNull { it.name == name }?.projectedTimeUnits().orEmpty()
        }
        .map { (def, unit) ->
          TimeUnitComponent(
            def.field!!,
            Fields.timeUnitParts(unit),
            Fields.vgField(def, forAs = true),
            step = Fields.timeUnitStep(unit),
            utc = unit.startsWith("utc"),
          )
        }
        .distinctBy { it.output }
    return if (units.isEmpty()) null else TimeUnitNode(units)
  }

  /**
   * The stated `timeUnit` as the flow's own node — `TimeUnitNode.makeFromTransform`.
   *
   * As with the aggregate, being the same node as the one an encoding implies is what lets the two
   * fold together: a composite mark writes the bucketing its encoding asked for as a *transform* on
   * the layer it expands into, and the view drawn beside it asks for the same bucketing through its
   * own encoding. Kept apart, one chart bucketed the same column twice.
   */
  private fun timeUnitFrom(transform: VegaValue): TimeUnitNode? {
    if (transform.string("type") != "timeunit") return null
    val field = transform.string("field") ?: return null
    val outputs = transform.array("as").orEmpty().mapNotNull { text(it) }
    val units = transform.array("units").orEmpty().mapNotNull { text(it) }
    if (outputs.isEmpty() || units.isEmpty()) return null
    return TimeUnitNode(
      listOf(
        TimeUnitComponent(
          field,
          units,
          outputs.first(),
          step = transform.number("step")?.toInt(),
          utc = transform.string("timezone") == "utc",
        )
      )
    )
  }

  /** The stated `aggregate` as the flow's own node, or null for anything else. */
  private fun aggregateFrom(transform: VegaValue): AggregateNode? {
    if (transform.string("type") != "aggregate") return null
    return AggregateNode(
      dimensions = transform.array("groupby").orEmpty().mapNotNull { text(it) },
      ops = transform.array("ops").orEmpty().mapNotNull { text(it) },
      fields = transform.array("fields").orEmpty().map { text(it) },
      outputs = transform.array("as").orEmpty().mapNotNull { text(it) },
    )
  }

  /**
   * A map rendered as a sorted string, which is what identifies a filter-invalid node.
   *
   * `toSortedMap().toString()` said this on the JVM and says it nowhere else, and this file is
   * compiled for five targets. The **format is reproduced exactly** rather than improved: the
   * string is compared between nodes to decide whether two of them fold, so any change to it is a
   * change to which datasets a chart comes out with.
   */
  private fun sortedIdentity(definitions: Map<String, String>): String =
    definitions.entries.sortedBy { it.key }.joinToString(", ", "{", "}") { "${it.key}=${it.value}" }

  /** Which of a view's transforms a pass writes: everything, an ancestor's, or the view's own. */
  private enum class Written {
    ALL,
    ANCESTOR,
    OWN,
  }

  /**
   * Which of its **owner's** joins each of this view's `lookup` transforms is, by index.
   *
   * `parseTransformArray` keeps one counter per call and is called once per model, over that
   * model's own transforms — so the second join a model writes is its `lookup_1` however many joins
   * the models above it wrote first. This view's array is those arrays laid end to end, an
   * ancestor's before its own, so counting within each owner reproduces the same numbers.
   */
  private fun lookupOrdinals(): Map<Int, Int> {
    val seen = mutableMapOf<String, Int>()
    val ordinals = mutableMapOf<Int, Int>()
    view.spec.transforms.forEachIndexed { index, transform ->
      if (!transform.has("lookup")) return@forEachIndexed
      val owner = view.transformOwners.getOrNull(index) ?: view.name
      ordinals[index] = seen.getOrElse(owner) { 0 }.also { seen[owner] = it + 1 }
    }
    return ordinals
  }

  /**
   * The date parse a `timeUnit` transform's input needs, inserted above the transform itself.
   *
   * ```js
   * } else if (isTimeUnit(t)) {
   *   const parsedAs = ancestorParse.getWithExplicit(t.field);
   *   if (parsedAs.value === undefined) {
   *     head = new ParseNode(head, {[t.field]: 'date'});
   *     ancestorParse.set(t.field, 'date', false);
   *   }
   *   transformNode = head = TimeUnitNode.makeFromTransform(head, t);
   * }
   * ```
   */
  private fun timeUnitInputParse(transform: VegaValue, index: Int): ParseNode? {
    val transforms = Transforms(diagnostics, selections = view.selections)
    val earlier = transforms.producedFields(view.spec.transforms.take(index), view.spec.data)
    val field = transforms.timeUnitInput(transform, earlier) ?: return null
    return ParseNode(linkedMapOf(field to "date"))
  }

  /**
   * A step the partition was walked past, grouped by the facet's own fields as well as its own.
   *
   * ```ts
   * if (child instanceof AggregateNode || child instanceof StackNode ||
   *     child instanceof WindowTransformNode || child instanceof JoinAggregateTransformNode) {
   *   child.addDimensions(node.fields);
   * }
   * ```
   *
   * Those four are the nodes that **group**, and `moveFacetDown` gives each of them the facet's
   * fields as it swaps the partition below it — a count a cell states is a count within that cell,
   * and hoisted above the grid without the grid's own columns it counted the whole table instead.
   * Every other kind of step is per-row and has nothing to group by.
   */
  private fun regrouped(transform: VegaValue, facetFields: List<String>): VegaValue {
    if (facetFields.isEmpty()) return transform
    val obj = transform as? VegaValue.Obj ?: return transform
    if (obj.string("type") !in GROUPING_TRANSFORMS) return transform
    val existing = obj.array("groupby").orEmpty()
    val added = facetFields.filterNot { field -> existing.any { text(it) == field } }
    if (added.isEmpty()) return transform
    return VegaValue.Obj(
      LinkedHashMap(obj.fields).also {
        it["groupby"] = arr(existing + added.map { field -> VegaValue.Str(field) })
      }
    )
  }

  /**
   * Whether one of this view's transforms stands **above** the partition — `moveFacetDown`.
   *
   * ```js
   * export function moveFacetDown(node: DataFlowNode) {
   *   if (isFacetNode(node)) {
   *     …
   *     if (node.numChildren() === 1 && !isOutputNode(node.children[0])) {
   *       const child = node.children[0];
   *       if (child instanceof AggregateNode || …) child.addDimensions(node.fields);
   *       child.swapWithParent();
   *       moveFacetDown(node);
   * ```
   *
   * The partition walks down one node at a time and stops where the flow **forks**, so what climbs
   * past it is what the grids above wrote and what the cell model itself wrote — one chain, no fork
   * in it. A layer *inside* the cell wrote its transforms below that fork, and they stay there:
   * that is the whole difference between a cell that is a layer of two units, whose own transforms
   * are computed once for the grid, and one whose second member is a layer of its own, whose
   * transforms are computed in every cell.
   *
   * Judged by who **owns** the transform rather than by the view that carries a copy of it: a
   * member's copy of its layer's step is that layer's, and the layer is below the cut.
   */
  private fun climbsAboveTheCut(index: Int): Boolean {
    val owner = view.transformOwners.getOrNull(index) ?: return false
    // The cell model's own, or one of the models **above** it — a chart's transforms stand above
    // every partition in it, however deep the plot that grids. A name is the model tree, so an
    // ancestor's is this one's with something taken off the end; the chart's own is empty.
    return owner == view.cellOwner || owner.isEmpty() || view.cellOwner.startsWith("${owner}_")
  }

  private fun userTransforms(
    head: DataNode,
    which: Written = Written.ALL,
    hoistedOver: List<String> = emptyList(),
    /**
     * Whether to write the transforms that climb **above** the partition or the rest, or `null` for
     * both. See [climbsAboveTheCut].
     */
    ofGrid: Boolean? = null,
  ): DataNode {
    var last = head
    val lookupOrdinals = lookupOrdinals()
    view.spec.transforms.forEachIndexed { index, transform ->
      // A transform belongs to the model it was written on, and the ones an ancestor wrote were
      // run in *that* model's pass — above everything this view does. It matters twice: below a
      // facet they are not written again at all, and a layer's member buckets its field between
      // the two, above its own steps and below its ancestors'.
      val inherited = view.transformOwners.getOrNull(index)?.let { it != view.name } == true
      if (which == Written.OWN && inherited) return@forEachIndexed
      if (which == Written.ANCESTOR && !inherited) return@forEachIndexed
      if (ofGrid != null && climbsAboveTheCut(index) != ofGrid) return@forEachIndexed
      val transforms =
        Transforms(
          diagnostics,
          registerLookup,
          { param -> "lookup_$param" },
          { suffix -> view.prefixedForTransform(index, suffix) },
          lookupOrdinals[index] ?: 0,
          view.selections,
        )
      val path = "$.transform[$index]"
      // `parseSelectionPredicate`: a filter that tests a selection is given the selection's own
      // bucketing as a parent. A brush over `month(date)` remembers a `month_date`, and a view that
      // filters on that brush has to have a `month_date` to be tested against — which it does not,
      // unless it buckets one, however little its own encoding has to do with months.
      selectionTimeUnits(transform)?.let { last = last.then(it) }
      // "Create parse node because the input to time unit is always date." The parse belongs
      // **above** the transform, which is what lets a `{"field": "ts", "timeUnit": …, "as": "ts"}`
      // work at all: read as text the bucketing has nothing to bucket, and a parse written below
      // the transform is a parse of the transform's own output.
      timeUnitInputParse(transform, index)?.let { last = last.then(it) }
      for (emitted in transforms.translateAt(transform, path)) {
        // An `aggregate` a specification *states* is the same node as one an encoding asks for —
        // `AggregateNode.makeFromTransform` beside `makeFromEncoding` — and being the same node is
        // what lets the two fold together. A composite mark is where it tells: an error bar states
        // its own aggregate and the point drawn over it asks for a `mean`, both grouped the same
        // way, and upstream computes the grouping once. Carried as an opaque transform, the
        // grouping is computed twice into two datasets.
        val grouped = if (index < view.gridTransforms) emitted else regrouped(emitted, hoistedOver)
        val node =
          aggregateFrom(grouped) ?: timeUnitFrom(grouped) ?: PassThroughNode(listOf(grouped))
        node.fromAncestor = inherited
        last = last.then(node)
        // An aggregate's output rows are not the rows that went in and have no identity yet, so a
        // view whose selection remembers rows by identity writes one again below it —
        // `requiresSelectionId(model)` in `parseTransformArray`, and the same rule that follows an
        // aggregate an *encoding* asked for. A brush over a map of aggregated places is where it
        // tells: the brush picks the circles it covers, and a circle with no `_vgsid_` cannot be
        // picked at all.
        // Asked of the **model the transform was written on**, not of the view carrying a copy of
        // it: `parseTransformArray` runs once per model and `requiresSelectionId(model)` walks that
        // model's own selections, which for a layer are its members' too. A layer that aggregates
        // once for two members, one of which declares a selection, has one aggregate and one
        // identifier below it — where asking each member left the one that declared nothing with a
        // chain of its own, identical to its neighbour's but for the missing identifier, and so
        // unable to fold with it: two copies of the same table, and a sorted domain reading the
        // union of both.
        if (
          identityUnder(view.transformOwners.getOrNull(index) ?: view.name) && node is AggregateNode
        ) {
          last = last.then(identifierNode()).also { it.fromAncestor = inherited }
        }
      }
    }
    return last
  }
}
