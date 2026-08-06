package dev.aster.vega.runtime.compile

import dev.aster.vega.dataflow.transform.groupTuples
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.spec.AxisSpec
import dev.aster.vega.model.spec.FacetSpec
import dev.aster.vega.model.spec.LegendSpec
import dev.aster.vega.model.spec.MarkSpec
import dev.aster.vega.model.spec.MarkType
import dev.aster.vega.runtime.scale.VegaScale
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SceneNodeIdAllocator
import dev.aster.vega.scene.TextEngine

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
  fun compile(
    marks: List<MarkSpec>,
    axes: List<AxisSpec>,
    legends: List<LegendSpec>,
    scope: CompileScope,
    extent: PlotSize,
  ): List<SceneNode> {
    val numbers = NumberResolver(expressions, scope.signals, diagnostics)
    val axisBuilder = AxisBuilder(scope.scales, ids, textEngine, diagnostics, numbers)
    val encoder =
      MarkEncoder(scope.scales, ids, diagnostics, scope.signals, expressions, textEngine)

    val children = mutableListOf<SceneNode>()
    // Vega draws axes below marks unless an axis opts into a higher zindex, and legends above both.
    val (underlay, overlay) = axes.partition { it.zindex <= 0 }
    var guides = GuideBounds.of(extent)
    for (axis in underlay) {
      val node = axisBuilder.build(axis, extent, scope.rangeSize) ?: continue
      children += node
      guides = guides.including(axis, node)
    }
    for (mark in marks) {
      children +=
        if (mark.type == MarkType.GROUP) group(mark, scope, encoder)
        else encoder.encode(mark, markData(mark, scope))
    }
    for (axis in overlay) {
      val node = axisBuilder.build(axis, extent, scope.rangeSize) ?: continue
      children += node
      guides = guides.including(axis, node)
    }
    children +=
      LegendBuilder(scope.scales, ids, textEngine, diagnostics, numbers)
        .build(legends, extent, guides)
    return children
  }

  /**
   * Grows the rectangles legend placement measures against by one axis.
   *
   * A vertical axis widens what a left or right legend is pushed past, and a horizontal one
   * heightens what a top or bottom legend clears. Upstream keeps them separate, so a left axis does
   * not shift a right-hand legend even though it enlarges the chart.
   */
  private fun GuideBounds.including(axis: AxisSpec, node: SceneNode): GuideBounds {
    val bounds = AxisBuilder.guideBounds(node)
    return if (axis.orient.isVertical) copy(vertical = vertical.union(bounds))
    else copy(horizontal = horizontal.union(bounds))
  }

  // ---- group marks ------------------------------------------------------------

  /** One group item: the datum its own encode block sees, and the rows its contents see. */
  private class Partition(
    val datum: VegaValue,
    /** `null` unless the group is faceted, in which case these rows are bound to [boundName]. */
    val rows: List<VegaValue>? = null,
    val boundName: String? = null,
  )

  private fun group(spec: MarkSpec, outer: CompileScope, encoder: MarkEncoder): List<SceneNode> {
    val partitions = partition(spec, outer)
    return encoder.encodeGroup(spec, partitions.map { it.datum }) { _, index, extent ->
      val inner = nest(spec, partitions[index], outer)
      compile(spec.marks, spec.axes, spec.legends, inner, PlotSize(extent.width, extent.height))
    }
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
