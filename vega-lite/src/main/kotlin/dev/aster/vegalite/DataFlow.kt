package dev.aster.vegalite

import dev.aster.vega.model.VegaValue

/**
 * The data pipeline: a tree of nodes per view, flattened into Vega's `data` array.
 *
 * The tree, the walk that flattens it and the naming all follow `compile/data/assemble.ts`, and the
 * naming is the reason to follow it rather than invent something simpler. Whether a view's
 * transforms land on `source_0` or on a derived `data_0` is decided by the walk — inline data is
 * pushed as its own dataset and derived from, a URL is transformed in place — and every scale
 * domain and mark in the compiled specification refers to whichever name came out. A different name
 * is not a cosmetic difference; it is a specification that reads from the wrong dataset.
 */
internal sealed class DataNode {
  val children: MutableList<DataNode> = mutableListOf()

  fun then(child: DataNode): DataNode {
    children += child
    return child
  }

  /**
   * Hoists the parse every branch agrees on above the fork — upstream's `MergeParse` optimizer.
   *
   * Each view builds its own chain, so two layers over one dataset each ask for their own parse and
   * the tree forks straight after the source. Upstream inserts a single parse below the fork and
   * re-parents **every** branch onto it, including a branch that asked for no parse of its own:
   * parsing a column twice is the same column, and a branch that did not ask still gets a value it
   * can read. That last part is what places a layer's own transforms after the shared parse rather
   * than beside it, and the dataset numbering follows from the shape.
   *
   * A field two branches want parsed *differently* stays where it was, in both.
   */
  /**
   * Folds sibling aggregates that group by the same fields into one — upstream's `MergeAggregates`.
   *
   * Two layers over one table, each aggregating it, ask for the same grouping twice; computing it
   * twice is not only wasted work but a different *shape* of output, with each layer reading a
   * dataset of its own where upstream has them sharing one. The last of the siblings is the one
   * kept, and the others' remaining steps hang below it, which is what decides the numbering.
   */
  /**
   * Folds identical sibling branches into one — upstream's `MergeIdenticalNodes`.
   *
   * Two layers that ask the same thing of one table are one question. Left apart they each get a
   * dataset, and every scale domain then reads a *union* of two names where upstream reads one —
   * the same rows, described twice. Identity is by the transforms a node emits, which is what the
   * comparison is over anyway.
   */
  /** The tree's shape and content, for telling whether an optimizer pass changed anything. */
  fun signature(): String =
    "${this::class.simpleName}:${identity() ?: ""}(${children.joinToString(",") { it.signature() }})"

  fun mergeIdentical() {
    children.forEach { it.mergeIdentical() }
    if (children.size <= 1) return
    val kept = LinkedHashMap<String, DataNode>()
    val folded = mutableListOf<DataNode>()
    for (child in children) {
      val key = child.identity() ?: continue
      val first = kept.putIfAbsent(key, child)
      if (first != null) {
        first.children += child.children
        folded += child
      }
    }
    children.removeAll(folded)
    // A branch that just gained children may now have identical ones of its own.
    if (folded.isNotEmpty()) children.forEach { it.mergeIdentical() }
  }

  /**
   * What makes two nodes the same question, or null for a node that is never merged.
   *
   * Upstream gives *every* node a `hash`, and it is over the transform the node emits — `Stack
   * ${hash(this._stack)}`, `Impute ${hash(this.transform)}`. Every kind that emits one is compared
   * on the same basis here, which matters most for the derived steps: a line drawn over its own
   * area is two views asking one table for the same stack, and leaving them apart gave each a
   * dataset of its own where upstream has both marks reading the source.
   *
   * An [OutputNode] is excluded on purpose: it is a *name* something else reads by, and merging two
   * of them would leave one of the readers pointing at a name that is no longer there. A
   * [SourceNode] is excluded because a root is nobody's sibling.
   */
  private fun identity(): String? =
    when (this) {
      is ParseNode -> "parse:$parse"
      is FilterInvalidNode -> "filter-invalid:$definitions"
      // A time-unit step is merged by `mergeTimeUnits` instead, which keeps a different one.
      is PassThroughNode -> if (timeUnit) null else "transforms:${transforms.map { it.toString() }}"
      is BinNode -> "bin:${transforms()}"
      is ImputeNode -> "impute:${transforms()}"
      is StackNode -> "stack:${transforms()}|$component"
      else -> null
    }

  /**
   * Collapses a fork that has a **named output** in it — upstream's `MergeOutputs`.
   *
   * An output node is only a name: it adds no transform, so a branch that is one has nothing of its
   * own between it and the table. Everything else at the fork therefore belongs *below* it rather
   * than beside it, and the fork disappears. It matters because a fork spends a dataset name on the
   * table it splits, so a chart whose second layer reads the raw rows — a rule at a constant, say —
   * had every one of its datasets numbered one too high.
   *
   * Where several outputs meet, they chain in reverse: the last declared sits above, and the first
   * is the one everything else hangs from.
   */
  /**
   * Lifts a parse above the steps that do not depend on it — upstream's `MoveParseUp`.
   *
   * Parsing is reading a column as what it is, and nothing downstream of it changes that column, so
   * it belongs as early as the flow allows: at the top, where every branch can share it. A step
   * that *produces* a field the parse reads is the one thing it cannot climb past, since there
   * would be nothing there to parse yet.
   *
   * Seen from here, the node being climbed past is a *child*: this is `swapWithParent` written from
   * the grandparent, which is where the pointers are.
   */
  fun moveParseUp() {
    children.forEach { it.moveParseUp() }
    var moved = true
    while (moved) {
      moved = false
      for ((index, below) in children.withIndex()) {
        if (below is ParseNode) continue
        val parse = below.children.singleOrNull() as? ParseNode ?: continue
        // A parse cannot climb past a step that produces what it reads — and a *nested* parse
        // reads the whole path, so a step producing `argmax_US_Gross` blocks a parse of
        // `argmax_US_Gross['Production Budget']` even though the two names differ.
        val roots = parse.parse.keys.map { Fields.splitAccessPath(it).first() }.toSet()
        if (below.producedFields().any { it in parse.parse.keys || it in roots }) continue
        val above = parse.children.toList()
        parse.children.clear()
        below.children.clear()
        below.children += above
        parse.children += below
        children[index] = parse
        moved = true
      }
    }
  }

  /** The columns a step writes, which is what a parse cannot climb past. */
  private fun producedFields(): Set<String> =
    when (this) {
      is ParseNode -> parse.keys
      is BinNode -> bins.flatMap { it.output }.toSet()
      is TimeUnitNode -> units.flatMap { listOf(it.output, "${it.output}_end") }.toSet()
      is AggregateNode -> outputs.toSet()
      is StackNode -> output.toSet()
      is ImputeNode -> setOf(field)
      is PassThroughNode ->
        transforms.mapNotNull { (it as? VegaValue.Obj)?.string("as") }.toSet() +
          transforms
            .flatMap { (it as? VegaValue.Obj)?.array("as").orEmpty() }
            .mapNotNull { (it as? VegaValue.Str)?.value }
      else -> emptySet()
    }

  fun mergeOutputs() {
    children.forEach { it.mergeOutputs() }
    if (children.size <= 1) return
    val outputs = children.filterIsInstance<OutputNode>()
    if (outputs.isEmpty()) return

    // The bottom of each branch that is nothing but outputs; what hangs below them moves.
    val tails = outputs.map { start ->
      var last = start
      while (last.children.size == 1 && last.children.single() is OutputNode) {
        last = last.children.single() as OutputNode
      }
      last
    }
    val below = mutableListOf<DataNode>()
    for (tail in tails) {
      below += tail.children
      tail.children.clear()
    }
    for (child in children) if (child !is OutputNode) below += child

    val main = tails.first()
    children.clear()
    // `out_n → … → out_2 → out_1`, with the first declared at the bottom holding everything else.
    var head: DataNode? = null
    for (index in outputs.indices.reversed()) {
      val output = outputs[index]
      if (head == null) children += output else head.children += output
      head = tails[index]
    }
    main.children += below
  }

  /**
   * Folds sibling time-unit steps into one — upstream's `MergeTimeUnits`.
   *
   * It keeps the **last** of the siblings, not the first: `timeUnitChildren.pop()` takes the end of
   * the list and every earlier one is merged into it, so the earlier branches' subtrees end up
   * *after* the last branch's own. That is not a detail — the walk names datasets in the order it
   * meets them, so a chart whose layers all bucket one column has its second layer numbered
   * `data_0` and its first `data_1`, and every scale domain and mark reads accordingly.
   */
  fun mergeTimeUnits() {
    children.forEach { it.mergeTimeUnits() }
    if (children.size <= 1) return
    val units = children.filterIsInstance<TimeUnitNode>()
    if (units.size > 1) {
      val kept = units.last()
      for (unit in units.dropLast(1)) {
        kept.merge(unit)
        children.remove(unit)
      }
    }
    val formulas = children.filterIsInstance<PassThroughNode>().filter { it.timeUnit }
    if (formulas.size > 1) {
      val kept = formulas.last()
      for (formula in formulas.dropLast(1)) {
        kept.merge(formula)
        children.remove(formula)
      }
    }
  }

  /**
   * `MergeBins`: two views bucketing one column the same way are one bucketing — `optimizers.ts`.
   *
   * The **last** sibling survives, as with the time units, and the ones folded into it hand over
   * their signal names: a bin publishes `…_bins` and `…_extent` signals named after the view that
   * asked for it, and everything that read the folded view's names has to read the survivor's
   * instead. The renames are collected here and applied to the whole specification once it is
   * assembled, which is what upstream's `signalNameMap` does at every reference.
   */
  /**
   * `RemoveDuplicateTimeUnits`: a bucket already cut **above** in the chain is not cut again.
   *
   * The merge folds *siblings*; this drops a unit whose output some ancestor already wrote, which
   * is what a layer reading its parent's bucketed column produces. Cutting it twice is harmless to
   * the numbers and wrong in the output — upstream emits one transform and so must this.
   */
  fun removeDuplicateTimeUnits(above: Set<String> = emptySet()) {
    val here = mutableSetOf<String>()
    if (this is TimeUnitNode) {
      dropUnits(above)
      here += units.map { it.output }
    }
    val seen = above + here
    children.toList().forEach { it.removeDuplicateTimeUnits(seen) }
    // A node left with nothing to write is no node at all, and its children move up to its parent.
    // After the recursion, not before: it is the recursion that empties it.
    children
      .filterIsInstance<TimeUnitNode>()
      .filter { it.units.isEmpty() }
      .toList()
      .forEach { empty ->
        children.remove(empty)
        children += empty.children
      }
  }

  fun mergeBins(renames: MutableMap<String, String>) {
    children.forEach { it.mergeBins(renames) }
    if (children.size <= 1) return
    val grouped = LinkedHashMap<String, MutableList<BinNode>>()
    for (child in children.filterIsInstance<BinNode>()) {
      grouped.getOrPut(child.key()) { mutableListOf() } += child
    }
    for (group in grouped.values) {
      if (group.size < 2) continue
      val kept = group.removeAt(group.size - 1)
      for (folded in group) {
        kept.merge(folded, renames)
        children.remove(folded)
        kept.children += folded.children
      }
    }
  }

  fun mergeAggregates() {
    children.forEach { it.mergeAggregates() }
    if (children.size <= 1) return
    val grouped = LinkedHashMap<List<String>, MutableList<AggregateNode>>()
    for (child in children.filterIsInstance<AggregateNode>()) {
      grouped.getOrPut(child.dimensions.sorted()) { mutableListOf() } += child
    }
    for (group in grouped.values) {
      if (group.size < 2) continue
      val kept = group.removeAt(group.size - 1)
      for (folded in group) {
        kept.merge(folded)
        children.remove(folded)
        kept.children += folded.children
      }
    }
  }

  fun mergeParse() {
    // Bottom up, so a fork nested inside a branch is settled before the branch above it.
    children.forEach { it.mergeParse() }
    if (children.size <= 1) return
    val common = LinkedHashMap<String, String>()
    val conflicting = mutableSetOf<String>()
    for (child in children.filterIsInstance<ParseNode>()) {
      for ((field, type) in child.parse) {
        val seen = common.put(field, type)
        if (seen != null && seen != type) conflicting += field
      }
    }
    conflicting.forEach { common.remove(it) }
    if (common.isEmpty()) return

    val merged = ParseNode(common)
    val branches = children.toList()
    children.clear()
    children += merged
    for (branch in branches) {
      if (branch is ParseNode) {
        common.keys.forEach { branch.parse.remove(it) }
        // A parse with nothing left in it is not a step, so its own children take its place.
        if (branch.parse.isEmpty()) {
          merged.children += branch.children
          continue
        }
      }
      merged.children += branch
    }
  }
}

internal class SourceNode(val data: VegaValue, val name: String? = null) : DataNode() {
  val isUrl: Boolean = data.string("url") != null

  /**
   * A **generated** table: `{"sequence": {...}}`, which is a transform rather than a table.
   *
   * It matters twice over. The dataset holds a `sequence` transform instead of `values`, and the
   * flow does *not* fork below it — a generator is not something Vega might overwrite, so the
   * view's own transforms belong in the same dataset rather than in a derived one.
   */
  val isGenerator: Boolean = data["sequence"] != null

  val isNamed: Boolean =
    data.string("name") != null && !isUrl && !isGenerator && data["values"] == null
}

/**
 * Parses field values into the types the encoding claims they have.
 *
 * A temporal field arriving as text is the common case, and where the parse lands matters: on a
 * dataset read from a URL it becomes a `format.parse` entry, and on inline values — which Vega has
 * already ingested — it becomes a `toDate` formula instead.
 */
internal class ParseNode(val parse: MutableMap<String, String>) : DataNode() {
  /**
   * `assembleFormatParse`: only a **top-level** column can be named in `format.parse`.
   *
   * Vega parses a loaded table field by field and has no path syntax there — a nested `record.high`
   * named here would be looked for as a column called exactly that. Nested ones are flattened by a
   * formula instead, which is why this may come out empty and still be written.
   */
  fun formatParse(): VegaValue.Obj = obj {
    parse.forEach { (field, type) ->
      if (Fields.splitAccessPath(field).size == 1) put(field, type)
    }
  }

  /**
   * The formulas that do the parsing `format.parse` could not.
   *
   * `onlyNested` is where a *loaded* table differs from one written out in the specification: the
   * loader has already parsed every flat column by the time these run, so only the nested ones are
   * left to flatten. Inline values had no loader, and parse everything here.
   */
  fun transforms(onlyNested: Boolean = false): List<VegaValue> =
    parse
      .filterKeys { !onlyNested || Fields.splitAccessPath(it).size > 1 }
      .map { (field, type) ->
        obj {
          put("type", "formula")
          put("expr", parseExpression(field, type))
          // Vega's output is always flat: `record.high` is a column's *name*, not a path into one.
          put("as", Fields.splitAccessPath(field).joinToString("."))
        }
      }

  /**
   * `parseExpression` from `data/formatparse.ts`: the call that turns text into what it says it is.
   *
   * The names are **camel-cased** — `toNumber`, not `tonumber` — and a dated parse names a
   * *specifier* rather than a type, so `date:'%d/%m'` becomes `timeParse` and `utc:` its UTC twin.
   * Building the name by concatenation instead gives an expression Vega has no function for.
   */
  private fun parseExpression(field: String, type: String): String {
    // `accessPathWithDatum`: a nested read is guarded step by step, so a row missing the outer
    // object yields nothing rather than failing the whole formula.
    val access =
      Fields.splitAccessPath(field).let { steps ->
        (1..steps.size).joinToString(" && ") { depth ->
          "datum" + steps.take(depth).joinToString("") { "[${quoted(it)}]" }
        }
      }
    return when {
      type.startsWith("date:") -> "timeParse($access,'${unquote(type.removePrefix("date:"))}')"
      type.startsWith("utc:") -> "utcParse($access,'${unquote(type.removePrefix("utc:"))}')"
      type == "flatten" -> access
      else -> "to${type.replaceFirstChar { it.uppercase() }}($access)"
    }
  }

  /** A specifier may arrive quoted, and the quotes are not part of it. */
  private fun unquote(text: String): String =
    if (
      text.length >= 2 &&
        (text.first() == '\'' || text.first() == '"') &&
        text.last() == text.first()
    )
      text.substring(1, text.length - 1)
    else text
}

internal class BinNode(bins: List<BinComponent>) : DataNode() {
  var bins: List<BinComponent> = bins
    private set

  /** What makes two bin nodes the same one: the column, bucketed by the same parameters. */
  fun key(): String = bins.joinToString("|") { "${it.field}:${it.params}:${it.extent}" }

  /**
   * Folds another node's bins into this one, recording the signals that changed their names.
   *
   * The output fields are **unioned** rather than replaced: two views may write the bucket under
   * different names — one wanting a `_range` column and the other not — and both readers still have
   * to find their own.
   */
  fun merge(other: BinNode, renames: MutableMap<String, String>) {
    bins = bins.mapIndexed { index, mine ->
      val theirs = other.bins.getOrNull(index) ?: return@mapIndexed mine
      renames[theirs.signal] = mine.signal
      renames[theirs.extentSignal] = mine.extentSignal
      mine.copy(
        output = (mine.output + theirs.output).distinct(),
        rangeFormula = mine.rangeFormula ?: theirs.rangeFormula,
      )
    }
  }

  fun transforms(): List<VegaValue> = bins.flatMap { bin ->
    val transforms = mutableListOf<VegaValue>()
    // Without an explicit extent the data's own is measured first and passed by signal, which is
    // also what makes the bin boundaries available to the scale domain.
    if (bin.extent == null) {
      transforms += obj {
        put("type", "extent")
        put("field", bin.field)
        put("signal", bin.extentSignal)
      }
    }
    transforms += obj {
      put("type", "bin")
      put("field", bin.field)
      put("as", strings(bin.output))
      put("signal", bin.signal)
      put("extent", bin.extent ?: signalRef(bin.extentSignal))
      bin.params.fields.forEach { (key, value) -> if (key != "extent") put(key, value) }
    }
    if (bin.rangeFormula != null) {
      transforms += obj {
        put("type", "formula")
        put("expr", bin.rangeFormula)
        put("as", "${bin.output[0]}_range")
      }
    }
    transforms
  }
}

internal data class BinComponent(
  val field: String,
  val params: VegaValue.Obj,
  val output: List<String>,
  val signal: String,
  val extentSignal: String,
  val extent: VegaValue?,
  val rangeFormula: String?,
)

internal class TimeUnitNode(units: List<TimeUnitComponent>) : DataNode() {
  var units: List<TimeUnitComponent> = units
    private set

  /** Drops the units an ancestor has already written, which is `removeFormulas` upstream. */
  fun dropUnits(written: Set<String>) {
    units = units.filterNot { it.output in written }
  }

  /** `TimeUnitNode.merge`: the other's units are taken over, and its children hang below this. */
  fun merge(other: TimeUnitNode) {
    // Compared by what they **write**, not by the whole component: two views may cut one column the
    // same way and differ in the interpolated edges one of them also wants, and that is still one
    // bucketing. Comparing the components whole emitted the same `timeunit` transform twice.
    val written = units.map { it.output }.toSet()
    units = units + other.units.filterNot { it.output in written }
    children += other.children
    other.children.clear()
  }

  fun transforms(): List<VegaValue> = units.flatMap {
    listOf(
      obj {
        put("type", "timeunit")
        put("field", it.field)
        put("units", strings(it.units))
        // Which calendar the bucket is cut against. A `utcmonth` says so and a `month` says
        // nothing, taking the viewer's own zone — and the two put a midnight instant in
        // different months.
        if (it.utc) put("timezone", "utc")
        put("as", strings(listOf(it.output, "${it.output}_end")))
      }
    ) +
      // A bucket the rect sits **off the middle of** is drawn between two interpolated edges,
      // and they belong to this bucket rather than to the step after it: upstream emits them
      // from the same loop, one pair per unit, so a chart bucketing a column twice keeps each
      // pair beside the bucket it interpolates.
      it.offsettedRect
  }
}

internal data class TimeUnitComponent(
  val field: String,
  val units: List<String>,
  val output: String,
  val utc: Boolean = false,
  /** The two interpolated edges a rect off the middle of this bucket is drawn between. */
  val offsettedRect: List<VegaValue> = emptyList(),
)

internal class AggregateNode(
  val dimensions: List<String>,
  ops: List<String>,
  fields: List<String?>,
  outputs: List<String>,
) : DataNode() {
  /**
   * The measures, keyed by the field they read and then by the operation.
   *
   * Kept this way because merging two aggregates is a *union of measures*, and the emitted order
   * follows the fields' first appearance rather than the order the operations were asked for —
   * which is what puts a `count`, whose field is nothing at all, after every measure of a column.
   */
  private val measures = LinkedHashMap<String?, LinkedHashMap<String, String>>()

  init {
    for (index in ops.indices) {
      measures.getOrPut(fields.getOrNull(index)) { LinkedHashMap() }[ops[index]] =
        outputs.getOrElse(index) { "" }
    }
  }

  val ops: List<String>
    get() = measures.values.flatMap { it.keys }

  val fields: List<String?>
    get() = measures.entries.flatMap { (field, byOp) -> byOp.keys.map { field } }

  val outputs: List<String>
    get() = measures.values.flatMap { it.values }

  /**
   * Folds another aggregate's measures into this one — upstream's `mergeMeasures`.
   *
   * Only ever called where the two group by the same fields, which is the whole condition for the
   * merge: two aggregates over one table with one grouping are one aggregate, and leaving them
   * apart means the same grouping computed twice into two datasets.
   */
  fun merge(other: AggregateNode) {
    for ((field, byOp) in other.measures) {
      val into = measures.getOrPut(field) { LinkedHashMap() }
      for ((op, output) in byOp) into.putIfAbsent(op, output)
    }
  }

  fun transform(): VegaValue = obj {
    put("type", "aggregate")
    put("groupby", strings(dimensions))
    put("ops", strings(ops))
    put("fields", arr(fields.map { if (it == null) VegaValue.Null else str(it) }))
    put("as", strings(outputs))
  }
}

internal class StackNode(
  val field: String,
  val groupby: List<String>,
  val sortFields: List<String>,
  val sortOrders: List<String>,
  val output: List<String>,
  val offset: String,
  /**
   * The dimension fields a stacked *path* mark imputes over, empty for anything else.
   *
   * A stacked area has to have a value at every dimension position or the band above it steps into
   * the gap; a stacked bar simply has no bar there. So upstream fills the gaps with zero first, and
   * only for a path mark.
   */
  val imputeKeys: List<String> = emptyList(),
  /**
   * What the imputation is done *within* — `facetby.concat(stackby)` upstream.
   *
   * The series being filled are the stack's own segments, and inside a trellis each cell's segments
   * are its own: a gap filled across the cells would draw one cell's rows into another.
   */
  val imputeGroupby: List<String> = emptyList(),
  /**
   * The parts of upstream's `StackComponent` that never reach the transform, kept for the merge.
   *
   * Two stacks are the same question when their whole component is, and the component holds more
   * than it emits: the channels that *segment* the column, and the dimension's field definitions
   * rather than only their names. A pie chart's arcs and its labels stack the same column and
   * segment it differently; a quantitative axis and an ordinal one over the same column emit the
   * same transform from different definitions. Both pairs are two datasets upstream, and folding
   * either into one drew the second plot from the first plot's accumulation.
   */
  val component: String = "",
  /**
   * The midpoint columns an imputation over a **binned** dimension has to write first.
   *
   * An imputation fills the gaps in one column keyed on another, and a bucket is two columns — so
   * upstream computes the point between them and keys on that. Without it the impute keyed on the
   * bucket's near edge, which is a different set of keys from the one the stack groups by.
   */
  val imputeFormulas: List<VegaValue> = emptyList(),
) : DataNode() {
  fun transforms(): List<VegaValue> =
    imputeFormulas +
      imputeKeys.map { key ->
        obj {
          put("type", "impute")
          put("field", field)
          put("groupby", strings(imputeGroupby))
          put("key", key)
          put("method", "value")
          put("value", 0)
        }
      } +
      transform()

  private fun transform(): VegaValue = obj {
    put("type", "stack")
    put("groupby", strings(groupby))
    put("field", field)
    put(
      "sort",
      obj {
        put("field", strings(sortFields))
        put("order", strings(sortOrders))
      },
    )
    put("as", strings(output))
    put("offset", offset)
  }
}

/**
 * Fills the gaps in a series, so that every key has a row — `compile/data/impute.ts`.
 *
 * A line or an area drawn over a series with a hole in it joins straight across the hole, which
 * reads as a value that was never measured. `impute` puts a row there instead, and the `groupby` is
 * what keeps one series' gaps out of another's.
 *
 * The Vega transform's `method` is always `value`: anything else is computed by a `window` beside
 * it and then written back over the nulls, because Vega's own impute methods work over the keys
 * rather than over a frame of neighbours.
 */
internal class ImputeNode(
  val field: String,
  val key: String,
  val method: String?,
  val value: VegaValue?,
  val groupby: List<String>,
  val keyvals: VegaValue?,
  val frame: VegaValue?,
) : DataNode() {
  fun transforms(): List<VegaValue> {
    val impute = obj {
      put("type", "impute")
      put("field", field)
      put("key", key)
      keyvals?.let { put("keyvals", it) }
      put("method", "value")
      if (groupby.isNotEmpty()) put("groupby", strings(groupby))
      put("value", if (method == null || method == "value") value else VegaValue.Null)
    }
    if (method == null || method == "value") return listOf(impute)
    return listOf(
      impute,
      obj {
        put("type", "window")
        put("as", strings(listOf("imputed_${field}_value")))
        put("ops", strings(listOf(method)))
        put("fields", strings(listOf(field)))
        put("frame", frame ?: arr(VegaValue.Null, VegaValue.Null))
        put("ignorePeers", VegaValue.Bool(false))
        if (groupby.isNotEmpty()) put("groupby", strings(groupby))
      },
      obj {
        put("type", "formula")
        put("expr", "datum.$field === null ? datum.imputed_${field}_value : datum.$field")
        put("as", field)
      },
    )
  }
}

/** Drops rows whose scaled fields are not finite numbers. See `compile/data/filterinvalid.ts`. */
internal class FilterInvalidNode(
  val expressions: List<String>,
  /**
   * The field **definitions** the filter was built from, which is what makes two of these the same.
   *
   * Upstream hashes the map it filters by — `hash(this.filter)`, field to the whole channel
   * definition — and not the expression it emits. So two views that drop the same rows for the same
   * columns are still two nodes when the definitions differ, which is why a detail plot whose x
   * scale is driven by a brush gets a dataset of its own rather than sharing the overview's.
   */
  val definitions: String = "",
) : DataNode() {
  fun transform(): VegaValue = obj {
    put("type", "filter")
    put("expr", expressions.joinToString(" && "))
  }
}

/** A transform written by the specification, already in Vega's form. */
internal class PassThroughNode(
  transforms: List<VegaValue>,
  /**
   * Whether this is a **time unit** step written as formulas — a bucket that arrived pre-cut.
   *
   * It is a `TimeUnitNode` upstream even though it emits `formula`, and that matters for the merge
   * rather than for the output: time units are folded by their own optimizer, which keeps the
   * *last* of the siblings where the general one keeps the first, and which of them survives is
   * what decides the order the branches below the fork are walked — and so their names.
   */
  val timeUnit: Boolean = false,
) : DataNode() {
  var transforms: List<VegaValue> = transforms
    private set

  /** `TimeUnitNode.merge`: the other's units are taken over, and its children hang below this. */
  fun merge(other: PassThroughNode) {
    transforms = transforms + other.transforms.filterNot { it in transforms }
    children += other.children
    other.children.clear()
  }
}

/** A named point in the flow that something else reads: a mark's source, a scale's domain. */
internal class OutputNode(val key: String) : DataNode() {
  var source: String? = null
    private set

  fun setSource(name: String) {
    source = name
  }
}

/**
 * Flattens the tree into Vega datasets, assigning `source_N` and `data_N` on the way.
 *
 * This is `makeWalkTree` from upstream, kept structurally identical: the branching rules decide the
 * numbering, and the numbering is observable in the output.
 */
internal class DataAssembler {
  private val datasets = mutableListOf<MutableDataset>()
  private var sourceIndex = 0
  private var datasetIndex = 0

  private class MutableDataset(
    var name: String?,
    var source: String? = null,
    val values: VegaValue? = null,
    val url: String? = null,
    var format: VegaValue.Obj? = null,
    val transform: MutableList<VegaValue> = mutableListOf(),
    /** Rows named at the top level, which stand in for the `values` this source did not state. */
    val inline: VegaValue? = null,
  ) {
    fun build(): VegaValue.Obj = obj {
      put("name", name)
      put("url", url)
      put("values", values ?: inline)
      put("format", format)
      put("source", source)
      if (transform.isNotEmpty()) put("transform", arr(transform))
    }
  }

  /**
   * @param roots every table the chart reads, in the order that named them: a [SourceNode] where
   *   something derives from it, and the table itself where nothing does — a `lookup`'s second
   *   dataset stands beside a view's source rather than below it, a join reading a second table
   *   rather than deriving from the first.
   */
  /** The rows a top-level `datasets` block holds, by the name each is handed in under. */
  var named: Map<String, VegaValue> = emptyMap()

  fun assemble(roots: List<Any>): List<VegaValue> {
    for (root in roots) {
      val data = if (root is SourceNode) root.data else root as VegaValue
      // A dataset the specification **named** keeps that name — `if (!root.hasName())` guards the
      // numbering upstream, so a named root does not consume a `source_n` either. The name is how
      // a chart hands its own data in at run time, and renaming it breaks the hand-off.
      val stated = data.string("name")
      val dataset =
        MutableDataset(
          name = stated ?: "source_${sourceIndex++}",
          // A dataset named at the top level in `datasets` is *handed in* by name and has no
          // `values` of its own; the specification's own block is where the rows are, and Vega
          // wants them written out beside the name.
          inline = stated?.let { named[it] },
          values = data["values"],
          url = data.string("url"),
          format = sourceFormat(data),
          transform = generatorTransform(data),
        )
      if (root is SourceNode) walk(root, dataset) else datasets += dataset
    }
    // "Move sources without transforms to the beginning" — `assembleRootData`. A dataset that
    // derives from nothing and does nothing is a table the chart was handed, and Vega has to have
    // it before whatever joins against it. Stable, so the numbering still reads in order.
    val (plain, derived) = datasets.partition { it.source == null && it.transform.isEmpty() }
    return (plain + derived).map { it.build() }
  }

  /** `{"sequence": {...}}` — the one data source that is a transform. */
  private fun generatorTransform(data: VegaValue): MutableList<VegaValue> {
    val sequence = data.obj("sequence") ?: return mutableListOf()
    return mutableListOf(
      obj {
        put("type", "sequence")
        sequence.fields.forEach { (key, value) -> put(key, value) }
      }
    )
  }

  private fun sourceFormat(data: VegaValue): VegaValue.Obj? {
    // A stated `parse` is not the *loader's* work on a table written out in the specification:
    // Vega has already ingested those rows, so the parse joins the flow's own and becomes a
    // formula there. Only what is left of the format block belongs on the source.
    val declared =
      data.obj("format")?.let { format ->
        if (data.string("url") != null) format
        else
          VegaValue.Obj(format.fields.filterKeys { it != "parse" }).takeIf {
            it.fields.isNotEmpty()
          }
      }
    val url = data.string("url") ?: return declared
    val type =
      declared.string("type")
        ?: when {
          url.endsWith(".csv") -> "csv"
          url.endsWith(".tsv") -> "tsv"
          url.endsWith(".json") -> "json"
          else -> "json"
        }
    return obj {
      put("type", type)
      declared?.fields?.forEach { (key, value) -> if (key != "type") put(key, value) }
    }
  }

  private fun walk(node: DataNode, incoming: MutableDataset, parent: DataNode? = null) {
    var dataset = incoming

    if (node is SourceNode && !node.isUrl && !node.isGenerator) {
      // Inline or named data becomes a dataset of its own so that Vega does not overwrite it; the
      // transforms then belong to a derived one.
      datasets += dataset
      dataset = MutableDataset(name = null, source = dataset.name)
    }

    when (node) {
      is ParseNode ->
        // `node.parent instanceof SourceNode`: a parse at the top of the flow is the *loader's*
        // work and becomes `format.parse`; one below a transform is a formula, the columns by then
        // being ones the flow itself computed, which the loader never saw.
        if (dataset.source == null && parent is SourceNode) {
          val existing = dataset.format
          dataset.format = obj {
            putAll(existing)
            put("parse", node.formatParse())
          }
          // What `format.parse` could not name is done here instead, on the loaded table.
          dataset.transform += node.transforms(onlyNested = true)
        } else {
          dataset.transform += node.transforms()
        }
      is BinNode -> dataset.transform += node.transforms()
      is TimeUnitNode -> dataset.transform += node.transforms()
      is AggregateNode -> dataset.transform += node.transform()
      is StackNode -> dataset.transform += node.transforms()
      is ImputeNode -> dataset.transform += node.transforms()
      is FilterInvalidNode -> dataset.transform += node.transform()
      is PassThroughNode -> dataset.transform += node.transforms
      is OutputNode -> {
        if (dataset.source != null && dataset.transform.isEmpty()) {
          node.setSource(dataset.source!!)
        } else {
          if (dataset.name == null) dataset.name = "data_${datasetIndex++}"
          node.setSource(dataset.name!!)
          if (node.children.size == 1) {
            datasets += dataset
            dataset = MutableDataset(name = null, source = dataset.name)
          }
        }
      }
      is SourceNode -> Unit
    }

    when (node.children.size) {
      0 ->
        if (node is OutputNode && (dataset.source == null || dataset.transform.isNotEmpty())) {
          datasets += dataset
        }
      1 -> walk(node.children[0], dataset, node)
      else -> {
        if (dataset.name == null) dataset.name = "data_${datasetIndex++}"
        var source = dataset.name
        if (dataset.source == null || dataset.transform.isNotEmpty()) {
          datasets += dataset
        } else {
          source = dataset.source
        }
        for (child in node.children) {
          walk(child, MutableDataset(name = null, source = source), node)
        }
      }
    }
  }
}
