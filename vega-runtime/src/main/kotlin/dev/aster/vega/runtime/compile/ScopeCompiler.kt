package dev.aster.vega.runtime.compile

import dev.aster.vega.dataflow.transform.AggregateOp
import dev.aster.vega.dataflow.transform.ProjectionDefinition
import dev.aster.vega.dataflow.transform.TransformContext
import dev.aster.vega.dataflow.transform.TransformPipeline
import dev.aster.vega.dataflow.transform.aggregateOver
import dev.aster.vega.dataflow.transform.compareFieldValues
import dev.aster.vega.dataflow.transform.groupTuples
import dev.aster.vega.expression.Clock
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.RandomStream
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import dev.aster.vega.model.roundHalfUp
import dev.aster.vega.model.spec.AxisSpec
import dev.aster.vega.model.spec.FacetSpec
import dev.aster.vega.model.spec.LayoutSpec
import dev.aster.vega.model.spec.LegendSpec
import dev.aster.vega.model.spec.MarkSort
import dev.aster.vega.model.spec.MarkSpec
import dev.aster.vega.model.spec.MarkType
import dev.aster.vega.model.spec.ScaleType
import dev.aster.vega.model.spec.TitleSpec
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.PointD
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.Transform2D
import dev.aster.vega.scene.transformedBounds

/**
 * Everything visible from one point in a specification: the top level, or the inside of a group
 * mark.
 *
 * @param rangeSize what a `"width"` or `"height"` scale range resolves to. This is deliberately
 *   *not* the enclosing group's size. Upstream gives a group's subscope only a `parent` signal and
 *   leaves `width` and `height` inherited, so a scale declared inside a facet and ranged over
 *   `"height"` spans the whole chart, not the cell — a long-standing Vega gotcha, and
 *   specifications work around it by declaring their own `height` signal inside the group.
 *   Reproducing the gotcha is the point: a chart that renders correctly upstream has to render the
 *   same way here.
 */
internal class CompileScope(
  val data: ScopeData,
  val signals: SignalScope,
  val scales: Map<String, VegaScale>,
  val rangeSize: PlotSize,
  /**
   * Each scale's declared `type`, which its built object no longer knows.
   *
   * `sqrt` and `pow` are the same class, and a specification that wrote `sqrt` should hear "sqrt"
   * from a screen reader. Carried alongside the scales because a group scope shadows both together.
   */
  val scaleTypes: Map<String, ScaleType> = emptyMap(),
  /**
   * The cartographic projections visible here, with their signals resolved.
   *
   * Nested like everything else in a scope: a group may declare its own, and a `geoshape` inside it
   * sees those before the ones outside.
   */
  val projections: Map<String, ProjectionDefinition> = emptyMap(),
) {
  val datasets: Map<String, List<VegaValue>>
    get() = data.datasets

  /**
   * The same scope with a named mark's scene items readable as a dataset.
   *
   * Marks and datasets share one namespace in Vega, so a mark drawn from `"category-line"` gets the
   * items that mark produced. Adding them to the scope rather than to a side table is what lets a
   * group's contents see a mark declared outside it, the way every other name here nests.
   */
  fun withMarkItems(name: String, items: List<VegaValue>): CompileScope =
    CompileScope(
      data.withDataset(name, items),
      signals,
      scales,
      rangeSize,
      scaleTypes,
      projections,
    )
}

/**
 * Compiles the marks and axes of one scope into scene nodes, recursing through group marks.
 *
 * A group mark is the only construct that nests, and it nests everything: its own data, signals,
 * scales, axes and marks, each of which may shadow a same-named definition outside it. All of that
 * lives here, so [MarkEncoder] stays concerned with single marks and [SpecCompiler] with the chart
 * as a whole.
 */
internal class ScopeCompiler(
  private val ids: SceneNodeIdAllocator,
  private val textEngine: TextEngine,
  private val diagnostics: DiagnosticCollector,
  private val expressions: ExpressionCompiler,
  private val data: DataResolver,
  /**
   * The chart's one random stream and its clock, passed down rather than remade.
   *
   * A group that started a stream of its own would restart the sequence per cell, and upstream's is
   * one generator for the whole view.
   */
  private val random: RandomStream = RandomStream(),
  private val clock: Clock = Clock.Fixed,
) {

  /**
   * @param extent the size of the group being filled, which positions its bottom and right axes. At
   *   the top level this is the chart's plotting area.
   */
  /**
   * A scope's scene nodes, and how far the drawing they make up reaches.
   *
   * @param cellReach set only for a group mark's cells, which a `layout` needs in order to grid
   *   them by what they contain rather than by what they declared.
   */
  data class ScopeContent(
    val nodes: List<SceneNode>,
    val bounds: RectD,
    val cellReach: List<RectD> = emptyList(),
  ) {
    /** How far node [index] reaches in its own coordinates, falling back to its own bounds. */
    fun boxOf(index: Int): RectD = cellReach.getOrNull(index) ?: nodes[index].bounds
  }

  /**
   * Each item's appearance under the pointer, by the id of the item it replaces.
   *
   * Filled while the marks are encoded and read by the controller, which swaps one in when the
   * pointer moves. Empty for a specification with no `hover` blocks, which is most of them.
   */
  val hoverVariants: MutableMap<dev.aster.vega.scene.SceneNodeId, SceneNode> = mutableMapOf()

  /** A mark as it looks under the pointer: its `hover` block layered over `update`. */
  private fun hoverSpec(mark: MarkSpec): MarkSpec =
    mark.copy(encode = mark.encode.copy(update = mark.encode.update + mark.encode.hover))

  fun compile(
    marks: List<MarkSpec>,
    axes: List<AxisSpec>,
    legends: List<LegendSpec>,
    title: TitleSpec?,
    layout: LayoutSpec?,
    enclosing: CompileScope,
    extent: PlotSize,
  ): ScopeContent {
    // Grows as named marks are encoded: a mark drawn from another mark's output needs the items the
    // earlier one produced, and specification order is the order they become available in.
    var scope = enclosing
    // Only the marks something actually reads back are turned into items. Resolving every named
    // mark's channels a second time would be work nobody asked for, and on a chart with a
    // ten-thousand-row scatter it would be a lot of it.
    val readBack = sourceNames(marks)

    val numbers = NumberResolver(expressions, scope.signals, diagnostics)
    val encoder =
      MarkEncoder(
        scope.scales,
        ids,
        diagnostics,
        // The scales exist by now, so an encoding expression can call `scale()` — which a signal's
        // own `update` cannot, since scales are built from signals and not the reverse.
        scope.signals.withScales(scope.scales, diagnostics),
        expressions,
        textEngine,
        extent,
      )
    // The encoder goes to the axis builder as well: a label's `encode` block resolves through the
    // same channel machinery a mark's does, over the tick as its datum.
    val axisBuilder =
      AxisBuilder(scope.scales, scope.scaleTypes, ids, textEngine, diagnostics, numbers, encoder)

    val children = mutableListOf<SceneNode>()
    // How far the drawing reaches, which is what a title is placed against. It starts as the
    // plotting
    // area and grows: an axis contributes its *guide* bounds rather than its node bounds, for the
    // same
    // half-pixel reason legend placement does.
    var content = RectD(0.0, 0.0, extent.width, extent.height)

    // Vega draws axes below marks unless an axis opts into a higher zindex, and legends above both.
    val (underlay, overlay) = axes.partition { it.zindex <= 0 }
    var guides = GuideBounds.of(extent)
    // How far this scope's *group* marks reach, which a legend is pushed past along with the axes.
    //
    // Group marks only, which is not a simplification: a top-level line whose 3-unit stroke hangs
    // half a unit past the plot's right edge leaves the legend exactly where it was, and the same
    // line inside a faceted group moves it. Established by moving one line between the two and
    // watching the legend slide — upstream places a legend against the group items in its scope,
    // and a plain mark is not one.
    var markReach = RectD(0.0, 0.0, extent.width, extent.height)
    for (axis in underlay) {
      val built = axisBuilder.build(axis, extent, scope.rangeSize) ?: continue
      children += built.node
      guides = guides.including(axis, built.guideBounds)
      content = content.union(built.guideBounds)
    }
    // A `layout` cannot place anything until every group in the scope is built, because the cells
    // decide the grid and the headers are placed against it. So they are collected first.
    val trellisParts = mutableListOf<Pair<TrellisRole, ScopeContent>>()
    // Built in specification order, painted in `zindex` order. The two are not the same and cannot
    // be merged: a mark drawn from another mark's output has to be encoded after it whatever their
    // z-order says, so the nodes are collected per declaration and emitted afterwards.
    val built = arrayOfNulls<List<SceneNode>>(marks.size)
    marks.forEachIndexed { index, mark ->
      // After building, not before: the items only exist once the channels have been resolved, and
      // a mark cannot read back its own output.
      fun exposeItems(items: List<VegaValue>) {
        mark.name?.takeIf { it in readBack }?.let { scope = scope.withMarkItems(it, items) }
      }
      fun expose(rows: List<VegaValue>) = exposeItems(encoder.items(mark, rows))
      if (mark.type == MarkType.GROUP) {
        val group = group(mark, scope, encoder)
        expose(group.datums)
        if (layout != null) {
          trellisParts += TrellisRole.of(mark.role) to group.content
        } else {
          built[index] = group.content.nodes
          content = content.union(group.content.bounds)
          markReach = markReach.union(group.content.bounds)
        }
      } else {
        val rows = markData(mark, scope)
        val transformed = markTransformed(mark, rows, scope, encoder)
        scope = transformed.scope
        // Encoded twice when the mark has a `hover` block: once as it rests, once as it looks under
        // the pointer. The allocator is rewound between the two so the pair share their ids — the
        // hit index and the selection key on them, and an item that changed its id under the
        // pointer
        // would leave the pointer over nothing.
        val before = ids.mark()
        // Sorted by each item's own `zindex`, which is paint order *within* the mark: a bar the
        // specification raised draws over its neighbours and still under the axis. Stable, so items
        // sharing a `zindex` keep the order the data gave them.
        built[index] =
          encoder.encode(mark, rows, transformed.written).sortedBy { it.metadata.zindex }
        if (mark.encode.hover.isNotEmpty()) {
          val after = ids.mark()
          ids.rewind(before)
          val hovered = encoder.encode(hoverSpec(mark), rows, transformed.written)
          ids.rewind(after)
          val resting = built[index].orEmpty()
          if (hovered.size == resting.size) {
            for (i in resting.indices) hoverVariants[resting[i].id] = hovered[i]
          } else {
            // A hover block that changes *which* items exist cannot be swapped in item for item.
            diagnostics.warn(
              DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
              "The 'hover' block on mark '${mark.name ?: mark.type.name.lowercase()}' changes how " +
                "many items the mark produces, so there is no item-for-item swap to make; the mark " +
                "will not respond to the pointer",
              operator = mark.name,
            )
          }
        }
        // The items a mark's own transforms produced, not the ones its encoding alone would: a
        // label drawn from a force-directed mark reads the position the simulation settled on.
        exposeItems(transformed.items ?: encoder.items(mark, rows))
        for (node in built[index].orEmpty()) content = content.union(node.transformedBounds)
      }
    }
    for (index in paintOrder(marks)) built[index]?.let { children += it }
    if (layout != null) {
      val placed =
        trellis(layout, trellisParts, NumberResolver(expressions, scope.signals, diagnostics))
      children += placed.nodes
      content = content.union(placed.bounds)
      markReach = markReach.union(placed.bounds)
    }
    // A raised axis is built here, with the others, because a legend is placed past however far
    // every axis reaches — but it is *emitted* after the legends, because that is where `zindex`
    // puts it: everything at zero paints in declaration order, legends included, and only then the
    // raised ones. The two orders are different and both matter.
    val raised = mutableListOf<SceneNode>()
    for (axis in overlay) {
      val built = axisBuilder.build(axis, extent, scope.rangeSize) ?: continue
      raised += built.node
      guides = guides.including(axis, built.guideBounds)
      content = content.union(built.guideBounds)
    }
    val legendNodes =
      LegendBuilder(scope.scales, ids, textEngine, diagnostics, numbers, encoder)
        .build(
          legends,
          extent,
          GuideBounds(guides.horizontal.union(markReach), guides.vertical.union(markReach)),
        )
    children += legendNodes
    children += raised
    for (node in legendNodes) content = content.union(legendBox(node))

    // Last, because a title is placed against everything else: it centres over the chart *and* its
    // axes and legends, not over the plotting area.
    title?.let {
      val node = TitleBuilder(ids, textEngine, diagnostics, numbers).build(it, content, extent)
      children += node
      content = content.union(node.transformedBounds)
    }
    return ScopeContent(children, content)
  }

  /**
   * Grows the rectangles legend placement measures against by one axis.
   *
   * A vertical axis widens what a left or right legend is pushed past, and a horizontal one
   * heightens what a top or bottom legend clears. Upstream keeps them separate, so a left axis does
   * not shift a right-hand legend even though it enlarges the chart.
   */
  private fun GuideBounds.including(axis: AxisSpec, bounds: RectD): GuideBounds =
    if (axis.orient.isVertical) copy(vertical = vertical.union(bounds))
    else copy(horizontal = horizontal.union(bounds))

  // ---- group marks ------------------------------------------------------------

  /** One group item: the datum its own encode block sees, and the rows its contents see. */
  private class Partition(
    val datum: VegaValue,
    /** `null` unless the group is faceted, in which case these rows are bound to [boundName]. */
    val rows: List<VegaValue>? = null,
    val boundName: String? = null,
  )

  /**
   * A built group mark: its cells, and the datum each cell was encoded from.
   *
   * The datums are kept because a group mark is addressable by name like any other — a trellis
   * titles each cell from a text mark drawn from the group itself — and by the time the cells are
   * [GroupNode]s the tuple behind each one is gone.
   */
  private data class BuiltGroup(val content: ScopeContent, val datums: List<VegaValue>)

  /**
   * Compiles a group mark, keeping each cell's *reach* as well as its nodes.
   *
   * A cell's reach is not the same as its node bounds: the axes inside it measure by their extent,
   * so asking the finished [GroupNode] how big it is would quietly reintroduce the half-pixel crisp
   * offset that everything outside the cell is careful to exclude.
   */
  /** A clipped group's reach: what its contents cover, cut back to the window it declares. */
  private fun intersectReach(reach: RectD, clip: RectD): RectD {
    if (reach.isEmpty || clip.isEmpty) return RectD.Empty
    val cut =
      RectD(
        maxOf(reach.left, clip.left),
        maxOf(reach.top, clip.top),
        minOf(reach.right, clip.right),
        minOf(reach.bottom, clip.bottom),
      )
    return if (cut.isEmpty) RectD.Empty else cut
  }

  /**
   * The order a mark's items are taken in, as indices into the built list.
   *
   * Identity when the mark declares no `sort`. A field this cannot read leaves the order alone
   * rather than inventing one — the properties an item exposes here are its position, and a
   * specification sorting on anything else is asking for something that is not in the scene.
   */
  /**
   * A legend measures as its own box rather than as the union of what it drew.
   *
   * Upstream's `legendBounds` anchors the aggregate at the legend's padding and then *sets* the
   * item's bounds to the resulting rectangle, so anything hanging above or to the left of the
   * origin is drawn and not measured. A title beside the entries is exactly that case: it is
   * vertically centred, so its text reaches a fraction above the legend's own top edge, and
   * measuring it there pushes the whole chart down by a unit.
   */
  /**
   * How far a legend reaches: its declared extent, widened by its own outline.
   *
   * The declared extent rather than its bounds, because a legend that clips an entry still occupies
   * the box it declared. The outline has to be added back, though — a legend with a `strokeColor`
   * is drawn half a stroke width outside the box on every side, and upstream measures it that way,
   * so a chart with an outlined legend is a unit wider than one without.
   */
  private fun legendBox(node: SceneNode): RectD =
    (node as? GroupNode)?.size?.let { size ->
      val box = RectD(0.0, 0.0, size.width, size.height)
      val widened = node.stroke?.takeIf { it.isVisible }?.let { box.expand(it.width / 2.0) } ?: box
      node.transform.mapBounds(widened)
    } ?: node.transformedBounds

  private fun sortOrder(
    sort: MarkSort?,
    nodes: List<SceneNode>,
    datums: List<VegaValue>,
  ): List<Int> {
    if (sort == null || nodes.isEmpty()) return nodes.indices.toList()
    val comparator =
      Comparator<Int> { a, b ->
        for ((index, field) in sort.fields.withIndex()) {
          // Upstream's `orders[i] === 'descending' ? -1 : 1`, and exactly that: anything else,
          // "desc" included, ascends.
          val descending = sort.orders.getOrNull(index) == "descending"
          val left = itemValue(nodes[a], datums.getOrNull(a), field) ?: continue
          val right = itemValue(nodes[b], datums.getOrNull(b), field) ?: continue
          val comparison = compareFieldValues(left, right)
          if (comparison != 0) return@Comparator if (descending) -comparison else comparison
        }
        0
      }
    return nodes.indices.sortedWith(comparator)
  }

  /**
   * One sort key, read off the item.
   *
   * The fields are a **path into the scene item**, not a column of the data — which is why
   * `{"field": "y"}` sorts by where the item ended up and `{"field": "datum.year"}` reaches through
   * it to the row that produced it. Upstream has no special case for either: `vega-util`'s `field`
   * walks the path, and the item happens to carry both its geometry and its datum. A path this
   * scene graph cannot follow is reported rather than treated as a tie, because a tie leaves the
   * items in declaration order and looks exactly like a sort that worked.
   */
  private fun itemValue(node: SceneNode, datum: VegaValue?, field: String): VegaValue? =
    when {
      field == "x" -> VegaValue.Num(node.transform.apply(0.0, 0.0).x)
      field == "y" -> VegaValue.Num(node.transform.apply(0.0, 0.0).y)
      field == "datum" -> datum ?: VegaValue.Null
      field.startsWith("datum.") -> (datum ?: VegaValue.Null).field(field.removePrefix("datum."))
      else -> {
        diagnostics.warn(
          DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
          "Mark sort reads '$field' off each item, which this scene graph does not carry; " +
            "only 'x', 'y' and a 'datum.' path are available, so the declared order is kept",
        )
        null
      }
    }

  private fun group(spec: MarkSpec, outer: CompileScope, encoder: MarkEncoder): BuiltGroup {
    val partitions = partition(spec, outer)
    val inner = arrayOfNulls<RectD>(partitions.size)
    val nodes =
      encoder.encodeGroup(spec, partitions.map { it.datum }) { _, index, extent ->
        val scoped =
          compile(
            spec.marks,
            spec.axes,
            spec.legends,
            spec.title,
            spec.layout,
            nest(spec, partitions[index], outer),
            PlotSize(extent.width, extent.height),
          )
        inner[index] = scoped.bounds
        scoped.nodes
      }

    // `sort` orders the *items*, not the data, so it happens after encoding: its fields name
    // encoded
    // properties — `{"field": "y"}` is where the group ended up, not a column of the row. Upstream
    // sorts the item array in place, so the order changes what is drawn where *and* the order the
    // marks are painted in.
    val order = sortOrder(spec.sort, nodes, partitions.map { it.datum })
    val sorted = order.map { nodes[it] }
    val sortedPartitions = order.map { partitions[it] }
    val sortedInner = order.map { inner[it] }

    val reaches = sorted.mapIndexed { index, node ->
      var reach = sortedInner[index] ?: RectD.Empty
      (node as? GroupNode)?.stroke?.let { reach = reach.expand(it.halfWidth) }
      // A clipped group reaches no further than its own extent, whatever its contents do. Upstream
      // overrides the aggregated bounds the same way. Vega's platformer relies on it: the level
      // scrolls past the edge of a 400-wide window, and without the clip the chart is measured
      // around the whole level.
      val group = node as? GroupNode
      if (group?.clip != null) {
        reach = intersectReach(reach, group.clip!!)
      }
      reach
    }
    var bounds = RectD.Empty
    sorted.forEachIndexed { index, node ->
      bounds = bounds.union(node.transform.mapBounds(reaches[index]))
    }
    return BuiltGroup(ScopeContent(sorted, bounds, reaches), sortedPartitions.map { it.datum })
  }

  /**
   * Arranges a whole trellis: the cells on a grid, and the row and column labels around it.
   *
   * A header is placed against the cell it labels on one axis and against the grid's edge on the
   * other, so the labels track the grid however it wraps. A title is placed halfway along the grid,
   * just outside whatever its headers reached.
   */
  private fun trellis(
    layout: LayoutSpec,
    parts: List<Pair<TrellisRole, ScopeContent>>,
    numbers: NumberResolver,
  ): ScopeContent {
    // Every cell in the scope goes on **one** grid, whichever group mark produced it. Gridding each
    // group separately looks the same whenever there is only one — which every trellis fixture had
    // — and puts two of them on top of each other: Vega's quantile-quantile plot is two group marks
    // side by side under `columns: 2`, and both landed at the origin.
    val gridded = gridTogether(layout, parts, numbers)
    val cells = gridded.filter { it.first == TrellisRole.CELL }.map { it.second }
    val cellNodes = cells.flatMap { it.nodes }
    val cellBoxes = cells.flatMap { part -> part.nodes.indices.map { part.boxOf(it) } }
    val columns =
      numbers.resolveInt(layout.columns, "layout")?.coerceAtLeast(1)
        ?: cellNodes.size.coerceAtLeast(1)
    val rows = kotlin.math.ceil(cellNodes.size / columns.toDouble()).toInt().coerceAtLeast(1)

    var bounds = cells.fold(RectD.Empty) { acc, part -> acc.union(part.bounds) }
    // The **cells'** extent, captured before the headers widen it: a title is centred on the grid
    // it
    // titles, not on the grid plus whatever hangs off it. Upstream returns this from `gridLayout`
    // and
    // lays the headers out afterwards, which is the same thing said by construction.
    val gridBounds = bounds

    // Where the grid begins on each axis: what a header hangs off.
    val left =
      cellNodes.indices
        .filter { it % columns == 0 }
        .minOfOrNull {
          cellNodes[it].transform.e + cellBoxes[it].left
        } ?: 0.0
    val top = cellNodes.indices.minOfOrNull { cellNodes[it].transform.f + cellBoxes[it].top } ?: 0.0

    // Headers first, because a title is placed just outside whatever they reached. The results are
    // kept per declaration so the scene can be emitted in specification order, which is the order
    // upstream emits it in.
    // The far edges, which the footers hang off: the rightmost reach of the last column and the
    // lowest of the last row.
    val right =
      cellNodes.indices
        .filter { it % columns == columns - 1 || it == cellNodes.lastIndex }
        .maxOfOrNull { cellNodes[it].transform.e + cellBoxes[it].right } ?: 0.0
    val bottom =
      cellNodes.indices
        .filter { it >= cellNodes.size - columns }
        .maxOfOrNull { cellNodes[it].transform.f + cellBoxes[it].bottom } ?: 0.0

    val placed = HashMap<Int, List<SceneNode>>()
    val edges = HashMap<TrellisRole, RectD>()
    val headerRoles =
      setOf(
        TrellisRole.ROW_HEADER,
        TrellisRole.COLUMN_HEADER,
        TrellisRole.ROW_FOOTER,
        TrellisRole.COLUMN_FOOTER,
      )
    gridded.forEachIndexed { index, (role, part) ->
      if (role !in headerRoles) return@forEachIndexed
      val alongRows = role == TrellisRole.ROW_HEADER || role == TrellisRole.ROW_FOOTER
      val footer = role == TrellisRole.ROW_FOOTER || role == TrellisRole.COLUMN_FOOTER
      // A band places a header **along** the cell it labels rather than at the cell's own origin:
      // `null`, which is upstream's default, means the origin, and a fraction means that far across
      // the cell's own extent. The offset pushes it further out, away from the grid, which is why a
      // header's is negative and a footer's is not.
      val band = if (footer) layout.footerBand(alongRows) else layout.headerBand(alongRows)
      val away =
        layout.offsetFor(
          when (role) {
            TrellisRole.ROW_HEADER -> "rowHeader"
            TrellisRole.COLUMN_HEADER -> "columnHeader"
            TrellisRole.ROW_FOOTER -> "rowFooter"
            else -> "columnFooter"
          }
        )
      var edge = edges[role] ?: RectD.Empty
      val moved =
        part.nodes.mapIndexed { position, node ->
          // Header j labels the first cell of row j, or the j-th cell of the top row. A footer
          // labels the same rows and columns from the other side.
          //
          // More headers than rows is not an error and not a reason to drop one: upstream warns and
          // lays out the first `limit` of them, leaving the rest where they were. Dropping them
          // would change the mark count, which is a bigger difference than a label in the wrong
          // place.
          // The limit is the number of rows for a row label and of columns for a column one, and it
          // is not the number of *cells*: six column headers over a two-by-three grid label three
          // columns, and the other three are left alone rather than lining up under the second row.
          val limit = if (alongRows) rows else columns
          val cellIndex = if (alongRows) position * columns else position
          val cell = if (position >= limit) null else cellNodes.getOrNull(cellIndex)
          if (cell == null) {
            node
          } else {
            val extent = cellBoxes[cellIndex]
            val across =
              if (alongRows) {
                band?.let { roundHalfUp(cell.transform.f + extent.top + it * extent.height) }
                  ?: cell.transform.f
              } else {
                band?.let { roundHalfUp(cell.transform.e + extent.left + it * extent.width) }
                  ?: cell.transform.e
              }
            val at =
              when {
                alongRows && footer -> PointD(right + away, across)
                alongRows -> PointD(left - away, across)
                footer -> PointD(across, bottom + away)
                else -> PointD(across, top - away)
              }
            val node2 = moveTo(node, at)
            val drawn = node2.transform.mapBounds(part.boxOf(position))
            bounds = bounds.union(drawn)
            edge = edge.union(drawn)
            node2
          }
        }
      edges[role] = edge
      placed[index] = moved
    }

    gridded.forEachIndexed { index, (role, part) ->
      if (role != TrellisRole.ROW_TITLE && role != TrellisRole.COLUMN_TITLE) return@forEachIndexed
      val alongRows = role == TrellisRole.ROW_TITLE
      // A title sits just outside whatever the headers reached — or, under `titleAnchor: "end"`,
      // just outside the *footers* on the far side. Its own offset then pushes it further out, in
      // whichever direction that is.
      val atEnd = layout.titleAtEnd(alongRows)
      val edgeRole =
        when {
          alongRows && atEnd -> TrellisRole.ROW_FOOTER
          alongRows -> TrellisRole.ROW_HEADER
          atEnd -> TrellisRole.COLUMN_FOOTER
          else -> TrellisRole.COLUMN_HEADER
        }
      val guideEdge = edges[edgeRole] ?: RectD.Empty
      val away = layout.offsetFor(if (alongRows) "rowTitle" else "columnTitle")
      val band = layout.titleBand(alongRows)
      val moved =
        part.nodes.mapIndexed { position, node ->
          val across =
            if (alongRows) roundHalfUp(gridBounds.top + band * gridBounds.height)
            else roundHalfUp(gridBounds.left + band * gridBounds.width)
          val outward =
            if (alongRows) {
              if (atEnd) (if (guideEdge.isEmpty) right else guideEdge.right) + away
              else (if (guideEdge.isEmpty) left else guideEdge.left) - away
            } else {
              if (atEnd) (if (guideEdge.isEmpty) bottom else guideEdge.bottom) + away
              else (if (guideEdge.isEmpty) top else guideEdge.top) - away
            }
          val at = if (alongRows) PointD(outward, across) else PointD(across, outward)
          val node2 = moveTo(node, at)
          bounds = bounds.union(node2.transform.mapBounds(part.boxOf(position)))
          node2
        }
      placed[index] = moved
    }

    val nodes = gridded.flatMapIndexed { index, (_, part) -> placed[index] ?: part.nodes }
    return ScopeContent(nodes, bounds)
  }

  /**
   * Places every cell of every part on a single grid, keeping the parts separate afterwards.
   *
   * The grid has to see all the cells at once — its wrapping and its column widths are decided by
   * the whole sequence — but the caller still needs them per part, because a header is matched to a
   * cell by position within its own part and the scene is emitted in declaration order.
   */
  private fun gridTogether(
    layout: LayoutSpec,
    parts: List<Pair<TrellisRole, ScopeContent>>,
    numbers: NumberResolver,
  ): List<Pair<TrellisRole, ScopeContent>> {
    val cellParts = parts.withIndex().filter { it.value.first == TrellisRole.CELL }
    if (cellParts.isEmpty()) return parts

    // `bounds: "flush"` measures a cell by its **declared** extent rather than by how far its
    // contents reach, so an axis label hanging off to the left is allowed to collide with the cell
    // beside it instead of pushing it across. Upstream's `bboxFlush` is `(0, 0, width, height)`,
    // and a group that declared no size falls back to what it drew — there is nothing else to use.
    val flush = layout.bounds == "flush"
    val boxes = cellParts.flatMap { (_, entry) ->
      entry.second.nodes.indices.map { position ->
        val node = entry.second.nodes[position]
        val declared = (node as? GroupNode)?.size
        if (flush && declared != null) RectD(0.0, 0.0, declared.width, declared.height)
        else entry.second.boxOf(position)
      }
    }
    val options =
      GridLayout.Options(
        columns = numbers.resolveInt(layout.columns, "layout")?.coerceAtLeast(1) ?: boxes.size,
        rowPadding = numbers.resolve(layout.rowPadding, "layout") ?: 0.0,
        columnPadding = numbers.resolve(layout.columnPadding, "layout") ?: 0.0,
        alignColumn = GridLayout.Align.fromName(layout.alignColumn),
        alignRow = GridLayout.Align.fromName(layout.alignRow),
        centerColumn = layout.centerColumn,
        centerRow = layout.centerRow,
      )
    val offsets = GridLayout.place(boxes, options)

    val result = parts.toMutableList()
    var cursor = 0
    for ((index, entry) in cellParts) {
      val (role, part) = entry
      var bounds = RectD.Empty
      val placed =
        part.nodes.mapIndexed { position, node ->
          val moved = moveTo(node, offsets[cursor + position])
          bounds = bounds.union(moved.transform.mapBounds(part.boxOf(position)))
          moved
        }
      cursor += part.nodes.size
      result[index] = role to ScopeContent(placed, bounds, part.cellReach)
    }
    return result
  }

  /**
   * A mark's own `transform` block, run over its rows.
   *
   * Upstream calls these post-encoding transforms and runs them over the scene *items*, writing
   * onto each. `geopath` — the case that matters — reads the item's datum and writes the item's
   * `path`, and nothing between the encoding and the drawing reads anything it touches, so running
   * it over the rows and writing the same column draws the same picture. Doing it before the
   * encoding is also what lets the outline reach the `path` mark at all, since a scene node here
   * holds a parsed path rather than a mutable property bag.
   */
  private fun markTransformed(
    spec: MarkSpec,
    rows: List<VegaValue>,
    scope: CompileScope,
    encoder: MarkEncoder,
  ): MarkTransformResult {
    if (spec.transform.isEmpty()) return MarkTransformResult(scope, emptyList(), null)
    val context = MarkTransformScope(diagnostics, expressions, scope, textEngine)
    // The **items**, encoded: `{"field": "datum.contour"}` reaches for the row under `datum`, and
    // `{"force": "x", "x": "xfocus"}` reaches for a channel the encoding resolved. Running these
    // over the rows instead would answer the first and silently miss the second.
    val before = encoder.items(spec, rows)
    val after = TransformPipeline().run(before, spec.transform, context)
    val written = after.mapIndexed { index, item ->
      val was = (before.getOrNull(index) as? VegaValue.Obj)?.fields.orEmpty()
      (item as? VegaValue.Obj)
        ?.fields
        ?.filterKeys { it != "datum" }
        ?.filter { (name, value) -> was[name] != value }
        .orEmpty()
    }
    return MarkTransformResult(context.published(scope), written, after)
  }

  /**
   * What a mark's transforms changed: the channels they wrote, and any dataset they replaced.
   *
   * The second is not a detail. A `link` force resolves each edge's ends to the nodes it just laid
   * out, and the mark that draws the edges reads them back — upstream by mutating the shared tuple,
   * here by republishing the dataset, which is the same dependency said out loud.
   */
  private class MarkTransformResult(
    val scope: CompileScope,
    val written: List<Map<String, VegaValue>>,
    /** The items themselves, for a mark that another mark is drawn from. Null when nothing ran. */
    val items: List<VegaValue>?,
  )

  /** What a mark's own transforms may read: this scope's signals, datasets and scales. */
  private class MarkTransformScope(
    override val diagnostics: DiagnosticCollector,
    override val expressions: ExpressionCompiler,
    private val outer: CompileScope,
    private val textEngine: TextEngine,
  ) : TransformContext {
    override var tree: dev.aster.vega.dataflow.transform.TreeSource? = null

    private val replaced = LinkedHashMap<String, List<VegaValue>>()

    override val scope: dev.aster.vega.expression.ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) {
      // A mark's transform runs after every signal has settled, so there is nothing left that
      // could read one it published. Upstream has the same shape and the same silence.
    }

    override fun scopeFor(datum: VegaValue): dev.aster.vega.expression.ExpressionScope =
      Replacing(outer.signals.withScales(outer.scales, diagnostics).withDatum(datum), replaced)

    override fun projection(name: String): ProjectionDefinition? = outer.projections[name]

    /** The chart's own text engine, so a label is measured the way it will be drawn. */
    override fun measureText(text: String, fontSize: Double): Double =
      textEngine
        .layout(
          dev.aster.vega.scene.TextRun(
            text = text,
            style = dev.aster.vega.scene.TextStyle(fontSize = fontSize),
          )
        )
        .bounds
        .width

    /** The scope the marks after this one see, with any dataset a transform rewrote in it. */
    fun published(scope: CompileScope): CompileScope =
      replaced.entries.fold(scope) { current, (name, rows) -> current.withMarkItems(name, rows) }

    /** Records a `setdata`, which a [CompileScope] cannot take because it does not change. */
    private class Replacing(
      private val inner: dev.aster.vega.expression.ExpressionScope,
      private val sink: MutableMap<String, List<VegaValue>>,
    ) : dev.aster.vega.expression.ExpressionScope by inner {
      override fun setDataset(name: String, rows: List<VegaValue>) {
        sink[name] = rows
      }
    }
  }

  private fun moveTo(node: SceneNode, at: PointD): SceneNode =
    when (node) {
      is GroupNode -> node.copy(transform = Transform2D.translate(at.x, at.y))
      else -> node
    }

  /**
   * Places a group mark's cells on a grid.
   *
   * The cells are measured by how far their contents reach, not by their declared size, so a
   * trellis whose axis labels hang off to the left leaves room for them instead of overlapping the
   * cell next door.
   */
  private fun grid(
    layout: LayoutSpec,
    built: ScopeContent,
    numbers: NumberResolver,
    owner: String?,
  ): ScopeContent {
    if (built.nodes.isEmpty()) return built
    val name = owner ?: "layout"
    val options =
      GridLayout.Options(
        columns = numbers.resolveInt(layout.columns, name)?.coerceAtLeast(1) ?: built.nodes.size,
        rowPadding = numbers.resolve(layout.rowPadding, name) ?: 0.0,
        columnPadding = numbers.resolve(layout.columnPadding, name) ?: 0.0,
      )
    val offsets = GridLayout.place(built.nodes.indices.map { built.boxOf(it) }, options)
    var bounds = RectD.Empty
    val placed =
      built.nodes.mapIndexed { index, node ->
        val moved = moveTo(node, offsets[index])
        bounds = bounds.union(moved.transform.mapBounds(built.boxOf(index)))
        moved
      }
    return ScopeContent(placed, bounds, built.cellReach)
  }

  /**
   * Splits a group mark into the items it produces.
   *
   * Three shapes, all of them upstream's: a faceted group gets one item per distinct combination of
   * the groupby fields, a group with plain `from.data` gets one per datum, and a group with no data
   * at all gets exactly one whose datum is empty.
   */
  private fun partition(spec: MarkSpec, outer: CompileScope): List<Partition> {
    val facet = spec.from?.facet
    if (facet != null) return facetPartitions(spec, facet, outer)

    val dataName = spec.from?.data ?: return listOf(Partition(VegaValue.EmptyObject))
    val rows = outer.datasets[dataName]
    if (rows == null) {
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        unknownSource(dataName, "Group mark"),
        operator = spec.name,
      )
      return emptyList()
    }
    return rows.map { Partition(it) }
  }

  /**
   * Partitions a faceted group's source data.
   *
   * The datum shape — the groupby fields plus a `count` — is not invented here. Upstream implements
   * faceting by inserting an `aggregate` transform with the same `groupby`, so the group's own
   * encode block sees an aggregate tuple; verified against upstream, which yields `{cat: "A",
   * count: 2}` for a two-row partition. Group order is first appearance in the source data, not
   * sorted order.
   */
  private fun facetPartitions(
    spec: MarkSpec,
    facet: FacetSpec,
    outer: CompileScope,
  ): List<Partition> {
    val source = outer.datasets[facet.data]
    if (source == null) {
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        "Facet on group mark refers to unknown dataset '${facet.data}'",
        operator = spec.name,
      )
      return emptyList()
    }

    // Pre-faceted data: the grouping is already in the rows, and `field` names the column holding
    // each group's own list. One cell per row, and the cell's datum is that row — an edge-bundling
    // diagram draws one line per dependency along the tree path the dependency carries.
    facet.field?.let { field ->
      return source.map { row ->
        Partition(
          datum = row,
          rows = (row.field(field) as? VegaValue.Arr)?.values.orEmpty(),
          boundName = facet.name,
        )
      }
    }

    return groupTuples(source, facet.groupby).map { (key, rows) ->
      val fields = LinkedHashMap<String, VegaValue>(facet.groupby.size + 1)
      facet.groupby.forEachIndexed { index, field -> fields[field] = key[index] }
      fields["count"] = VegaValue.Num(rows.size.toDouble())
      // `facet.aggregate` measures each group and writes the answers onto the group's own datum, so
      // the marks inside read them off `parent`. A ridgeline plot scales every band by the number
      // of
      // points in it that way, and there is nowhere else the count could come from — the cell's own
      // data has been reshaped into a density curve by then.
      for (measure in facet.aggregate) {
        val op = AggregateOp.fromName(measure.op)
        if (op == null) {
          diagnostics.error(
            DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
            "'${measure.op}' is not one of Vega's aggregate operations, " +
              "so a facet cannot measure with it",
            operator = spec.name,
          )
          continue
        }
        fields[measure.name] = aggregateOver(op, measure.field, rows)
      }
      Partition(datum = VegaValue.Obj(fields), rows = rows, boundName = facet.name)
    }
  }

  /**
   * Builds the scope inside one group item.
   *
   * Order matters and mirrors the top level: data first, then signals, then scales, because a scale
   * reads data and a scale property may be a signal.
   */
  private fun nest(spec: MarkSpec, partition: Partition, outer: CompileScope): CompileScope {
    // `parent` is the group's datum, not the group item: upstream's subscope binds the tuple, so
    // `parent.width` is undefined even though `parent.cat` works. Verified against upstream.
    val signalValues = LinkedHashMap(outer.signals.values)
    signalValues["parent"] = partition.datum

    val inherited =
      if (partition.rows != null && partition.boundName != null) {
        outer.data.withDataset(partition.boundName, partition.rows)
      } else {
        outer.data
      }
    // A group still resolves its data, then its signals, then its scales: nothing in the corpus
    // needs them interleaved the way the top level does, and the enclosing scope's signals and
    // scales are all settled by the time a group is reached. Its *own* signals are not, so a
    // transform reading one of those is reported the same way the top level reports it.
    val resolved =
      data.resolve(
        spec.data,
        signalValues,
        inherited,
        deferredSignals = spec.signals.map { it.name }.toSet() - signalValues.keys,
        // The enclosing scope's scales, which exist by now — a group is reached long after the
        // chart's scales are built. That is what lets a group's own transform read
        // `domain('xscale')`
        // off an outer scale, which is how a chart sizes a cell from the axis around it.
        scales = outer.scales,
      )
    val datasets = resolved.datasets
    // The scales below are the *enclosing* scope's, and they exist by now: a group is reached long
    // after the chart's scales are built. That is what lets a cell say `bandwidth('yscale')` and
    // take the height of one band of the outer scale. The group's own scales are still pending —
    // they are built from these very signals, a few lines down — so naming one of those is
    // reported as premature rather than as a scale nobody defined.
    val signals =
      SignalResolver(diagnostics, expressions, random, clock)
        .resolve(
          spec.signals,
          datasets,
          signalValues,
          enclosingScales = outer.scales,
          pendingScales = spec.scales.mapTo(mutableSetOf()) { it.name },
        )

    // A group that declares its own width or height signal changes what a nested `"width"` range
    // means; one that does not inherits the chart's.
    val rangeSize =
      PlotSize(
        width = numberSignal(signals, "width") ?: outer.rangeSize.width,
        height = numberSignal(signals, "height") ?: outer.rangeSize.height,
      )

    val numbers = NumberResolver(expressions, signals, diagnostics)
    val scales =
      outer.scales + ScaleResolver(datasets, rangeSize, diagnostics, numbers).resolve(spec.scales)
    return CompileScope(
      resolved,
      signals,
      scales,
      rangeSize,
      outer.scaleTypes + spec.scales.associate { it.name to it.type },
      outer.projections + ProjectionResolver(numbers, diagnostics).resolve(spec.projections),
    )
  }

  private fun numberSignal(signals: SignalScope, name: String): Double? =
    (signals[name] as? VegaValue.Num)?.value?.takeIf { !it.isNaN() }

  /** The rows a non-group mark iterates. A mark with no data draws once. */
  private fun markData(mark: MarkSpec, scope: CompileScope): List<VegaValue> {
    val dataName = mark.from?.data ?: return listOf(VegaValue.EmptyObject)
    val rows = scope.datasets[dataName]
    if (rows == null) {
      diagnostics.error(
        DiagnosticCodes.PARSE_UNKNOWN_PROPERTY,
        unknownSource(dataName),
        operator = mark.name,
      )
      return emptyList()
    }
    return rows
  }

  /**
   * The order this scope's marks are painted in, as indices into the declaration list.
   *
   * Upstream's rule, and it is not the obvious one: everything with `zindex: 0` is painted first in
   * specification order, and only then the rest, sorted by `zindex` and then by declaration. So a
   * **negative** `zindex` does not sink a mark below its neighbours — it raises it above all of
   * them, and among the raised ones puts it at the bottom. Read off `zorder` in
   * `vega-scenegraph/src/util/visit.js`, which partitions on `if (item.zindex)` rather than sorting
   * the whole list; a plain sort would agree everywhere except on that sign.
   */
  private fun paintOrder(marks: List<MarkSpec>): List<Int> {
    if (marks.none { it.zindex != 0 }) return marks.indices.toList()
    val level = marks.indices.filter { marks[it].zindex == 0 }
    val raised =
      marks.indices
        .filter { marks[it].zindex != 0 }
        .sortedWith(compareBy({ marks[it].zindex }, { it }))
    return level + raised
  }

  /**
   * Every name the marks in this scope draw from, however deeply nested.
   *
   * Nested too, because a mark inside a group may source one declared outside it — the scopes nest
   * the same way every other name here does.
   */
  private fun sourceNames(marks: List<MarkSpec>): Set<String> {
    val names = mutableSetOf<String>()
    fun walk(list: List<MarkSpec>) {
      for (mark in list) {
        mark.from?.data?.let(names::add)
        mark.from?.facet?.data?.let(names::add)
        // A mark-level transform may name another mark in a *parameter* rather than in `from`:
        // `label`'s `avoidMarks` is the case, and the items it names have to be built for it.
        for (definition in mark.transform) {
          val avoid = (definition as? VegaValue.Obj)?.fields?.get("avoidMarks")
          when (avoid) {
            is VegaValue.Arr -> avoid.values.forEach { names.add(it.asString()) }
            is VegaValue.Str -> names.add(avoid.value)
            else -> Unit
          }
        }
        walk(mark.marks)
      }
    }
    walk(marks)
    return names
  }
}

/**
 * What to say about a name that resolves to nothing.
 *
 * Not "unknown dataset": marks and datasets share one namespace, so the name may have been meant as
 * a mark's output, and sending a reader to hunt through `data` for something that was never going
 * to be there costs more than the sentence saves.
 */
internal fun unknownSource(name: String, subject: String = "Mark"): String =
  "$subject refers to '$name', which is neither a dataset nor a mark drawn before it in this scope"
