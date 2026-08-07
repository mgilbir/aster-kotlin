package dev.aster.vega.runtime.compile

import dev.aster.vega.dataflow.transform.groupTuples
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.roundHalfUp
import dev.aster.vega.model.spec.AxisSpec
import dev.aster.vega.model.spec.FacetSpec
import dev.aster.vega.model.spec.LayoutSpec
import dev.aster.vega.model.spec.LegendSpec
import dev.aster.vega.model.spec.MarkSpec
import dev.aster.vega.model.spec.MarkType
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
  val datasets: Map<String, List<VegaValue>>,
  val signals: SignalScope,
  val scales: Map<String, VegaScale>,
  val rangeSize: PlotSize,
)

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

  fun compile(
    marks: List<MarkSpec>,
    axes: List<AxisSpec>,
    legends: List<LegendSpec>,
    title: TitleSpec?,
    layout: LayoutSpec?,
    scope: CompileScope,
    extent: PlotSize,
  ): ScopeContent {
    val numbers = NumberResolver(expressions, scope.signals, diagnostics)
    val axisBuilder = AxisBuilder(scope.scales, ids, textEngine, diagnostics, numbers)
    val encoder =
      MarkEncoder(scope.scales, ids, diagnostics, scope.signals, expressions, textEngine)

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
    for (mark in marks) {
      if (mark.type == MarkType.GROUP) {
        val built = group(mark, scope, encoder)
        if (layout != null) {
          trellisParts += TrellisRole.of(mark.role) to built
        } else {
          children += built.nodes
          content = content.union(built.bounds)
          markReach = markReach.union(built.bounds)
        }
      } else {
        val nodes = encoder.encode(mark, markData(mark, scope))
        children += nodes
        for (node in nodes) content = content.union(node.transformedBounds)
      }
    }
    if (layout != null) {
      val placed =
        trellis(layout, trellisParts, NumberResolver(expressions, scope.signals, diagnostics))
      children += placed.nodes
      content = content.union(placed.bounds)
      markReach = markReach.union(placed.bounds)
    }
    for (axis in overlay) {
      val built = axisBuilder.build(axis, extent, scope.rangeSize) ?: continue
      children += built.node
      guides = guides.including(axis, built.guideBounds)
      content = content.union(built.guideBounds)
    }
    val legendNodes =
      LegendBuilder(scope.scales, ids, textEngine, diagnostics, numbers)
        .build(
          legends,
          extent,
          GuideBounds(guides.horizontal.union(markReach), guides.vertical.union(markReach)),
        )
    children += legendNodes
    for (node in legendNodes) content = content.union(node.transformedBounds)

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
   * Compiles a group mark, keeping each cell's *reach* as well as its nodes.
   *
   * A cell's reach is not the same as its node bounds: the axes inside it measure by their extent,
   * so asking the finished [GroupNode] how big it is would quietly reintroduce the half-pixel crisp
   * offset that everything outside the cell is careful to exclude.
   */
  private fun group(spec: MarkSpec, outer: CompileScope, encoder: MarkEncoder): ScopeContent {
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

    val reaches = nodes.mapIndexed { index, node ->
      var reach = inner[index] ?: RectD.Empty
      (node as? GroupNode)?.stroke?.let { reach = reach.expand(it.halfWidth) }
      reach
    }
    var bounds = RectD.Empty
    nodes.forEachIndexed { index, node ->
      bounds = bounds.union(node.transform.mapBounds(reaches[index]))
    }
    return ScopeContent(nodes, bounds, reaches)
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
    val gridded = parts.map { (role, part) ->
      role to if (role == TrellisRole.CELL) grid(layout, part, numbers, null) else part
    }
    val cells = gridded.filter { it.first == TrellisRole.CELL }.map { it.second }
    val cellNodes = cells.flatMap { it.nodes }
    val cellBoxes = cells.flatMap { part -> part.nodes.indices.map { part.boxOf(it) } }
    val columns =
      numbers.resolveInt(layout.columns, "layout")?.coerceAtLeast(1)
        ?: cellNodes.size.coerceAtLeast(1)

    var bounds = cells.fold(RectD.Empty) { acc, part -> acc.union(part.bounds) }

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
    val placed = HashMap<Int, List<SceneNode>>()
    val edges = HashMap<TrellisRole, RectD>()
    gridded.forEachIndexed { index, (role, part) ->
      if (role != TrellisRole.ROW_HEADER && role != TrellisRole.COLUMN_HEADER) return@forEachIndexed
      val alongRows = role == TrellisRole.ROW_HEADER
      var edge = edges[role] ?: RectD.Empty
      val moved =
        part.nodes.mapIndexedNotNull { position, node ->
          // Header j labels the first cell of row j, or the j-th cell of the top row.
          val cell = cellNodes.getOrNull(if (alongRows) position * columns else position)
          if (cell == null) null
          else {
            val at =
              if (alongRows) PointD(left, cell.transform.f) else PointD(cell.transform.e, top)
            val node2 = moveTo(node, at)
            val box = node2.transform.mapBounds(part.boxOf(position))
            bounds = bounds.union(box)
            edge = edge.union(box)
            node2
          }
        }
      edges[role] = edge
      placed[index] = moved
    }

    val gridBounds = bounds
    gridded.forEachIndexed { index, (role, part) ->
      if (role != TrellisRole.ROW_TITLE && role != TrellisRole.COLUMN_TITLE) return@forEachIndexed
      val alongRows = role == TrellisRole.ROW_TITLE
      val headerEdge =
        edges[if (alongRows) TrellisRole.ROW_HEADER else TrellisRole.COLUMN_HEADER] ?: RectD.Empty
      val moved =
        part.nodes.mapIndexed { position, node ->
          val at =
            if (alongRows) {
              PointD(
                if (headerEdge.isEmpty) left else headerEdge.left,
                roundHalfUp(gridBounds.top + 0.5 * gridBounds.height),
              )
            } else {
              PointD(
                roundHalfUp(gridBounds.left + 0.5 * gridBounds.width),
                if (headerEdge.isEmpty) top else headerEdge.top,
              )
            }
          val node2 = moveTo(node, at)
          bounds = bounds.union(node2.transform.mapBounds(part.boxOf(position)))
          node2
        }
      placed[index] = moved
    }

    val nodes = gridded.flatMapIndexed { index, (_, part) -> placed[index] ?: part.nodes }
    return ScopeContent(nodes, bounds)
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
        "Group mark refers to unknown dataset '$dataName'",
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

    return groupTuples(source, facet.groupby).map { (key, rows) ->
      val fields = LinkedHashMap<String, VegaValue>(facet.groupby.size + 1)
      facet.groupby.forEachIndexed { index, field -> fields[field] = key[index] }
      fields["count"] = VegaValue.Num(rows.size.toDouble())
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
        outer.datasets + (partition.boundName to partition.rows)
      } else {
        outer.datasets
      }
    val datasets = data.resolve(spec.data, signalValues, inherited)
    val signals =
      SignalResolver(diagnostics, expressions).resolve(spec.signals, datasets, signalValues)

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
    return CompileScope(datasets, signals, scales, rangeSize)
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
        "Mark refers to unknown dataset '$dataName'",
        operator = mark.name,
      )
      return emptyList()
    }
    return rows
  }
}
