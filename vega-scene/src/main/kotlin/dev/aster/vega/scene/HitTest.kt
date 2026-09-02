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
 * not change a node's visual bounds (ADR 0007).
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
 * simpler to verify (ADR 0009, ADR 0007).
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

  /**
   * How far outside a node's own bounds the broad phase still has to look.
   *
   * The **larger** of the two tolerances, and it has to be: the narrow phase reaches
   * [HitTestOptions.strokeTolerance] past a thin outline, and a broad phase gated on
   * [HitTestOptions.boundsTolerance] alone threw those points away before it could. `Mouse` has a
   * `boundsTolerance` of 0, so its 2 px stroke tolerance was reachable only where a node's bounds
   * happened to be fatter than its geometry — on an axis-aligned rule, whose bounds are its stroke
   * width, it was effectively zero. Widening the broad phase costs candidates and decides nothing:
   * `hitsPrecisely` still answers.
   */
  private val searchTolerance: Double = maxOf(options.boundsTolerance, options.strokeTolerance)

  /** Topmost node containing [point] in scene coordinates, or `null`. */
  public fun hitTest(point: PointD): HitResult? {
    val candidates = grid?.candidates(point, searchTolerance) ?: entries
    var best: Entry? = null
    for (entry in candidates) {
      if (best != null && entry.paintOrder < best.paintOrder) continue
      if (!entry.worldBounds.expand(searchTolerance).contains(point)) continue
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
    /**
     * @param window the clip every enclosing group has imposed, in **scene** coordinates, or null
     *   where nothing clips. Upstream returns from `pick` before testing a clipped group's contents
     *   at all, so a mark clipped away is not merely invisible: it cannot be touched. Carrying the
     *   window down and narrowing each entry's bounds with it says the same thing in a flat index —
     *   without it, a line running past the plotting area was still tappable in the padding, which
     *   is a tap that hits a mark the reader cannot see.
     */
    fun visit(
      node: SceneNode,
      parentTransform: Transform2D,
      ancestors: List<GroupNode>,
      window: RectD?,
    ) {
      // A fully transparent **group** still contributes its children. Every canvas renderer draws
      // them — a group's opacity applies to its own panel and is not inherited, which is written
      // down in each of them — so pruning the whole subtree here made marks that are visible on
      // screen impossible to tap. Anything else at zero opacity paints nothing and is correctly
      // skipped.
      if (!node.visible || (node.opacity <= 0.0 && node !is GroupNode)) return
      val world = parentTransform.concat(node.transform)
      val worldBounds = world.mapBounds(node.bounds)
      val visible = window?.let { intersectRects(worldBounds, it) } ?: worldBounds
      if (visible.isEmpty) return
      // Pre-order numbering mirrors paint order: a group is drawn before its children, so its
      // children get higher numbers and win the hit test over their own ancestors.
      if (!options.requireInteractive || node.metadata.interactive) {
        result.add(Entry(node, ancestors, world, visible, order++))
      }
      if (node is GroupNode) {
        val nextAncestors = ancestors + node
        // **An axis-aligned window, and it is a window rather than the clip.** `mapBounds` answers
        // the bounding box of the mapped rectangle, so under a rotated or sheared group transform
        // the window is *larger* than the region that is actually drawn — a point in a corner the
        // clip cuts away still reaches the children beneath it, and a tap there hits a mark the
        // reader cannot see.
        //
        // Left as a bounding box on purpose: an exact answer means carrying the clip as a polygon
        // and testing each candidate against it, which is real work on every node of every hit
        // test, and nothing this engine compiles produces a rotated *group* — the transforms in a
        // published scene are translations and uniform scales, under which a bounding box **is**
        // the region. What was missing is the sentence saying so, since the code reads as though it
        // handles the general case.
        val nextWindow =
          node.clip?.let { clip ->
            val mapped = world.mapBounds(clip)
            window?.let { intersectRects(mapped, it) } ?: mapped
          } ?: window
        // `paintOrder`, not `children`: an item's `zindex` reorders what is painted, and this
        // index numbers its entries in the order it walks them. Walking the raw children made the
        // hit test disagree with the picture — a tap landed on the mark drawn *underneath* the one
        // a `zindex` had raised. `paintOrder`'s own comment says every renderer has to use it; the
        // hit index is the reader's other half of the same question.
        for (child in paintOrder(node.children)) visit(child, world, nextAncestors, nextWindow)
      }
    }
    visit(scene.root, Transform2D.Identity, emptyList(), null)
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
    is RectNode -> hitsRect(node, point, options)
    is ImageNode -> node.rect.contains(point)
    is TextNode -> hitsText(node, point)
    is GroupNode -> hitsGroup(node, point, options)
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
        // Nonzero winding, because that is the rule the fill was **painted** with. The even-odd
        // rule calls the centre of a self-intersecting outline — a pentagram, a figure-of-eight —
        // outside, so a tap on the visibly solid middle of one missed.
        node.outline.containsNonZero(point)
      }
    // `node.fill != null` rather than `fill.isVisible`, which is the rule `hitsRect` already
    // states and cites upstream for: `isPointInPath` never looks at alpha, so `fillOpacity: 0` is
    // the idiom for an invisible tap target and a `path` mark using it lost its interior while the
    // `rect` beside it kept one.
    is PathNode ->
      if (node.fill != null) {
        node.path.containsNonZero(point) ||
          node.path.distanceToOutline(point) <= (node.stroke?.halfWidth ?: 0.0)
      } else {
        node.path.distanceToOutline(point) <=
          (node.stroke?.halfWidth ?: 0.0) + options.strokeTolerance
      }
  }

/**
 * Whether a **rect** is the hit, which is the same question upstream asks of a `path` mark.
 *
 * `marks/rect.js` picks through `pickPath(rectangle)`, so the test is `isPointInPath` over the
 * rounded rectangle it draws, or `isPointInStroke` over that outline. Two consequences, and this
 * used to have neither: the **cut corners** of a rounded bar are not part of it, and an
 * **unfilled** rect — a frame, a brush outline, a `strokeWidth`-only cell border — is picked on its
 * edge rather than across its middle.
 *
 * A stroke-only rect keeps [HitTestOptions.strokeTolerance], as every other stroke-only mark here
 * does: upstream is a mouse and this has to answer a finger. A filled one is exact, which is what a
 * bar chart wants — the neighbouring bar is a pixel away and guessing between them is worse than a
 * miss.
 */
private fun hitsRect(node: RectNode, point: PointD, options: HitTestOptions): Boolean {
  val rounded = node.roundedPath
  // `node.fill != null` rather than `fill.isVisible`: upstream asks whether the item *has* a fill
  // and
  // `isPointInPath` never looks at alpha, so `"fill": "transparent"` is the idiom for an invisible
  // tap
  // target and specifications in the wild use it. A stroke is different — a zero-width one has no
  // outline to be near — so that one keeps `isVisible`.
  if (node.fill != null) {
    if (rounded?.containsNonZero(point) ?: node.rect.contains(point)) return true
  }
  val stroke = node.stroke
  if (stroke == null || !stroke.isVisible) return false
  val reach = stroke.halfWidth + options.strokeTolerance
  val distance = rounded?.distanceToOutline(point) ?: node.rect.distanceToBoundary(point)
  return distance <= reach
}

/**
 * Whether a **label** is the hit, which for a rotated one is not a question about its box.
 *
 * Upstream's `marks/text.js` `hit` refuses a label of zero size, accepts any unrotated one whose
 * bounds contain the point — the broad phase has already established that — and for a rotated one
 * turns the *point* back about the label's own anchor and tests the **unrotated** box. A label's
 * bounds are the axis-aligned reach of the turned box, so a 45° tick label's bounds hold nearly
 * twice its area; testing them picks the label from a corner where nothing is drawn, which on a
 * crowded rotated axis means picking the wrong one.
 */
private fun hitsText(node: TextNode, point: PointD): Boolean {
  if (node.layout.run.style.fontSize <= 0.0) return false
  if (node.angleDegrees == 0.0) return node.bounds.contains(point)
  val anchorX = if (node.x.isNaN()) 0.0 else node.x
  val anchorY = if (node.y.isNaN()) 0.0 else node.y
  val radians = -node.angleDegrees * kotlin.math.PI / 180.0
  val cos = kotlin.math.cos(radians)
  val sin = kotlin.math.sin(radians)
  // Upstream's own arithmetic, anchor-absolute rather than offset-then-translated, for the reason
  // `TextNode.rotatedAbout` gives: the crumb in `cos(-90°)` is absorbed by the larger term.
  val px = cos * point.x - sin * point.y + (anchorX - cos * anchorX + sin * anchorY)
  val py = sin * point.x + cos * point.y + (anchorY - sin * anchorX - cos * anchorY)
  return node.layout.bounds.translate(anchorX, anchorY).normalized().contains(PointD(px, py))
}

/**
 * Whether a **group** is the hit, which is not the same question as whether the point is inside it.
 *
 * Upstream's rule is in `vega-scenegraph`'s `marks/group.js`: a group is picked only where it
 * *paints*. Its background counts when `group.fill || (!strokeForeground && group.stroke)`, and its
 * foreground stroke counts on its own when the stroke is drawn over the children. A group that
 * paints nothing is never picked — only its children are.
 *
 * This tested `bounds.contains(point)`, so **every** group swallowed every tap inside it. The
 * visible form of that: a compiled chart wraps its marks in a group whose bounds are the whole
 * plotting area, so a tap on blank space selected that group and a host reported "1 mark selected"
 * with nothing under the finger. Found by writing a Swift test that expected "nothing" and reading
 * what came back instead.
 *
 * The rectangle is the group's own **paint rect** — its declared extent, or its clip — rather than
 * its bounds, which include everything its children reach. Corner radius is honoured through
 * `roundedPaintPath`, as upstream honours it through `hitCorner`.
 */
private fun hitsGroup(node: GroupNode, point: PointD, options: HitTestOptions): Boolean {
  // A clip is a hard boundary. Upstream returns before testing anything at all when the point is
  // outside a clipped group's own rectangle, children included — which is what [SceneHitIndex]
  // reproduces by narrowing every descendant's bounds as it walks.
  node.clip?.let { if (!it.contains(point)) return false }
  val rect = node.paintRect ?: return false
  val rounded = node.roundedPaintPath
  val stroke = node.stroke

  // The foreground stroke: a group drawn over its children is grabbed by that outline, which is how
  // a
  // cell border stays clickable when its own bars run up to it.
  if (node.strokeForeground && stroke != null && stroke.isVisible) {
    val reach = stroke.halfWidth + options.strokeTolerance
    val onOutline =
      rounded?.let { it.distanceToOutline(point) <= reach }
        ?: (rect.distanceToBoundary(point) <= reach)
    if (onOutline) return true
  }

  // The background: a fill, or a stroke that is *not* drawn in the foreground.
  val background = node.fill != null || (!node.strokeForeground && stroke != null)
  if (!background) return false
  return rounded?.containsNonZero(point) ?: rect.contains(point)
}

/**
 * Distance from [point] to this rectangle's **boundary**: zero on it, positive both inside and out.
 *
 * The outline of a rectangle rather than its area, which is what a stroke covers.
 */
private fun RectD.distanceToBoundary(point: PointD): Double {
  val outsideX = maxOf(left - point.x, point.x - right, 0.0)
  val outsideY = maxOf(top - point.y, point.y - bottom, 0.0)
  if (outsideX > 0.0 || outsideY > 0.0) return kotlin.math.hypot(outsideX, outsideY)
  return minOf(point.x - left, right - point.x, point.y - top, bottom - point.y)
}

/** The overlap of two rectangles, or empty where they do not meet. */
private fun intersectRects(a: RectD, b: RectD): RectD {
  if (a.isEmpty || b.isEmpty) return RectD.Empty
  val left = maxOf(a.left, b.left)
  val top = maxOf(a.top, b.top)
  val right = minOf(a.right, b.right)
  val bottom = minOf(a.bottom, b.bottom)
  return if (right <= left || bottom <= top) RectD.Empty else RectD(left, top, right, bottom)
}
