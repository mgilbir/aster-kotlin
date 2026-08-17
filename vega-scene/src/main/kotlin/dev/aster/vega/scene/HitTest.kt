package dev.aster.vega.scene

/**
 * A hit-test result: the node that was hit plus the chain of groups above it, outermost first, so
 * event propagation can bubble without re-walking the scene.
 */
public data class HitResult(
  val node: SceneNode,
  val ancestors: List<GroupNode>,
  /** Absolute transform of the hit node, including its own. */
  val worldTransform: Transform2D,
  /** The query point expressed in the hit node's local coordinates. */
  val localPoint: PointD,
)

/**
 * Tuning for hit testing. Touch input needs a larger tolerance than a mouse, and the tolerance must
 * not change a node's visual bounds (PROJECT_BRIEF.md 11.2).
 */
public data class HitTestOptions(
  /** Extra radius added to thin geometry (rules, lines, unfilled paths). */
  val strokeTolerance: Double = 4.0,
  /** Extra radius added to every candidate's bounds during the broad phase. */
  val boundsTolerance: Double = 0.0,
  /** Non-interactive nodes are skipped when `true`. */
  val requireInteractive: Boolean = true,
  /** Above this node count, [SceneHitIndex] builds a grid instead of scanning linearly. */
  val spatialIndexThreshold: Int = 512,
) {
  public companion object {
    public val Mouse: HitTestOptions = HitTestOptions(strokeTolerance = 2.0, boundsTolerance = 0.0)
    public val Touch: HitTestOptions = HitTestOptions(strokeTolerance = 8.0, boundsTolerance = 6.0)
  }
}

/**
 * Precomputed hit-test index for one scene revision.
 *
 * Small scenes are scanned linearly, which is exactly correct and fast enough. Above
 * [HitTestOptions.spatialIndexThreshold] nodes a uniform grid narrows the candidate set first; a
 * grid rather than an R-tree because chart marks are usually distributed evenly, and it is far
 * simpler to verify (PROJECT_BRIEF.md 4.6, 11.2).
 */
public class SceneHitIndex(
  private val scene: Scene,
  private val options: HitTestOptions = HitTestOptions(),
) {

  private class Entry(
    val node: SceneNode,
    val ancestors: List<GroupNode>,
    val worldTransform: Transform2D,
    val worldBounds: RectD,
    /** Position in paint order; higher draws later, so it wins a hit test. */
    val paintOrder: Int,
  )

  private val entries: List<Entry> = buildEntries()
  private val grid: UniformGrid? =
    if (entries.size >= options.spatialIndexThreshold) UniformGrid(entries, scene) else null

  public val indexedNodeCount: Int
    get() = entries.size

  public val usesSpatialIndex: Boolean
    get() = grid != null

  /** Topmost node containing [point] in scene coordinates, or `null`. */
  public fun hitTest(point: PointD): HitResult? {
    val candidates = grid?.candidates(point, options.boundsTolerance) ?: entries
    var best: Entry? = null
    for (entry in candidates) {
      if (best != null && entry.paintOrder < best.paintOrder) continue
      if (!entry.worldBounds.expand(options.boundsTolerance).contains(point)) continue
      val inverse = entry.worldTransform.invert() ?: continue
      val local = inverse.apply(point)
      if (hitsPrecisely(entry.node, local, options)) best = entry
    }
    return best?.let {
      val inverse = it.worldTransform.invert() ?: Transform2D.Identity
      HitResult(
        node = it.node,
        ancestors = it.ancestors,
        worldTransform = it.worldTransform,
        localPoint = inverse.apply(point),
      )
    }
  }

  /**
   * Every node whose scene-space bounds intersect [rect], in paint order. Used by interval
   * selection.
   */
  public fun nodesIntersecting(rect: RectD): List<SceneNode> =
    entries.filter { it.worldBounds.intersects(rect) }.map { it.node }

  private fun buildEntries(): List<Entry> {
    val result = mutableListOf<Entry>()
    var order = 0
    fun visit(node: SceneNode, parentTransform: Transform2D, ancestors: List<GroupNode>) {
      if (!node.visible || node.opacity <= 0.0) return
      val world = parentTransform.concat(node.transform)
      // Pre-order numbering mirrors paint order: a group is drawn before its children, so its
      // children get higher numbers and win the hit test over their own ancestors.
      if (!options.requireInteractive || node.metadata.interactive) {
        result.add(Entry(node, ancestors, world, world.mapBounds(node.bounds), order++))
      }
      if (node is GroupNode) {
        val nextAncestors = ancestors + node
        for (child in node.children) visit(child, world, nextAncestors)
      }
    }
    visit(scene.root, Transform2D.Identity, emptyList())
    return result
  }

  /** Uniform grid over the scene's viewport, sized so cells hold a handful of nodes each. */
  private class UniformGrid(entries: List<Entry>, scene: Scene) {
    private val origin: PointD
    private val cellSize: Double
    private val columns: Int
    private val rows: Int
    private val cells: Array<MutableList<Entry>>

    init {
      var extent = scene.viewport
      for (entry in entries) extent = extent.union(entry.worldBounds)
      origin = PointD(extent.left, extent.top)
      val targetCells = (entries.size / 4).coerceIn(1, 4096)
      val area = (extent.width * extent.height).coerceAtLeast(1.0)
      cellSize = kotlin.math.sqrt(area / targetCells).coerceAtLeast(1.0)
      columns = ((extent.width / cellSize).toInt() + 1).coerceAtLeast(1)
      rows = ((extent.height / cellSize).toInt() + 1).coerceAtLeast(1)
      cells = Array(columns * rows) { mutableListOf() }

      for (entry in entries) {
        if (entry.worldBounds.isEmpty) continue
        val minCol = column(entry.worldBounds.left)
        val maxCol = column(entry.worldBounds.right)
        val minRow = row(entry.worldBounds.top)
        val maxRow = row(entry.worldBounds.bottom)
        for (r in minRow..maxRow) {
          for (c in minCol..maxCol) cells[r * columns + c].add(entry)
        }
      }
    }

    private fun column(x: Double): Int =
      (((x - origin.x) / cellSize).toInt()).coerceIn(0, columns - 1)

    private fun row(y: Double): Int = (((y - origin.y) / cellSize).toInt()).coerceIn(0, rows - 1)

    fun candidates(point: PointD, tolerance: Double): List<Entry> {
      if (tolerance <= 0.0) return cells[row(point.y) * columns + column(point.x)]
      val minCol = column(point.x - tolerance)
      val maxCol = column(point.x + tolerance)
      val minRow = row(point.y - tolerance)
      val maxRow = row(point.y + tolerance)
      if (minCol == maxCol && minRow == maxRow) return cells[minRow * columns + minCol]
      val result = mutableListOf<Entry>()
      for (r in minRow..maxRow) {
        for (c in minCol..maxCol) result.addAll(cells[r * columns + c])
      }
      return result
    }
  }
}

/**
 * Precise per-node-type containment test, with [point] already in the node's local coordinates.
 *
 * Filled geometry uses containment; stroke-only geometry uses distance to the outline plus
 * [HitTestOptions.strokeTolerance], because a one-pixel line is otherwise impossible to tap.
 */
public fun hitsPrecisely(node: SceneNode, point: PointD, options: HitTestOptions): Boolean =
  when (node) {
    is RectNode -> node.bounds.contains(point)
    is ImageNode -> node.rect.contains(point)
    is TextNode -> node.bounds.contains(point)
    is GroupNode -> node.bounds.contains(point)
    is RuleNode -> {
      val tolerance =
        (if (node.stroke.isVisible) node.stroke.halfWidth else 0.0) + options.strokeTolerance
      distanceToSegment(point, PointD(node.x1, node.y1), PointD(node.x2, node.y2)) <= tolerance
    }
    is SymbolNode ->
      if (node.shape == SymbolShape.STROKE || (node.fill == null && node.stroke != null)) {
        node.outline.distanceToOutline(point) <=
          (node.stroke?.halfWidth ?: 0.0) + options.strokeTolerance
      } else {
        node.outline.containsEvenOdd(point)
      }
    is PathNode ->
      if (node.fill != null && node.fill.isVisible) {
        node.path.containsEvenOdd(point) ||
          node.path.distanceToOutline(point) <= (node.stroke?.halfWidth ?: 0.0)
      } else {
        node.path.distanceToOutline(point) <=
          (node.stroke?.halfWidth ?: 0.0) + options.strokeTolerance
      }
  }
