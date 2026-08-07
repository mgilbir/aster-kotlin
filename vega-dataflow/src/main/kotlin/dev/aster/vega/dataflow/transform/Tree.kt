package dev.aster.vega.dataflow.transform

import dev.aster.vega.model.VegaValue
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A node of the tree the hierarchy transforms build, ported from `d3-hierarchy`.
 *
 * The tree is **not** part of the data model. A specification's `stratify` returns exactly the rows
 * it was given, unchanged and in order, and a layout after it writes coordinates back onto those
 * same rows — so nothing downstream ever sees a nested structure. The tree exists only between the
 * two, which is why it rides on the pipeline ([TransformContext.tree]) rather than on the tuples.
 * Upstream does the same thing, hanging it off the source array as `source.root`.
 *
 * [index] is the position of this node's row in the list the transform was given, or -1 for a node
 * that has no row — an interior node `nest` invented, or the placeholder root of an empty tree.
 */
internal class TreeNode(index: Int, val datum: VegaValue?) : TreeSource {
  /** Set late by `nest`, whose interior nodes only get a row if `generate` asked for one. */
  var index: Int = index
    private set

  fun assignIndex(value: Int) {
    index = value
  }

  var parent: TreeNode? = null
  var children: MutableList<TreeNode>? = null
  var depth: Int = 0
  var height: Int = 0

  /** The summed measure a layout divides space by. */
  var value: Double = 0.0

  /** Where a layout put this node. Which of these it filled depends on the layout. */
  var x0: Double = 0.0
  var y0: Double = 0.0
  var x1: Double = 0.0
  var y1: Double = 0.0
  var x: Double = 0.0
  var y: Double = 0.0
  var r: Double = 0.0

  val childCount: Int
    get() = children?.size ?: 0

  /**
   * Pre-order: a node before its children. Uses an explicit stack, as deep trees would overflow.
   */
  fun eachBefore(visit: (TreeNode) -> Unit) {
    val stack = ArrayDeque<TreeNode>()
    stack.addLast(this)
    while (stack.isNotEmpty()) {
      val node = stack.removeLast()
      visit(node)
      node.children?.let { for (i in it.indices.reversed()) stack.addLast(it[i]) }
    }
  }

  /** Post-order: every child before its parent, which is how a subtree's total is accumulated. */
  fun eachAfter(visit: (TreeNode) -> Unit) {
    val stack = ArrayDeque<TreeNode>()
    val order = ArrayDeque<TreeNode>()
    stack.addLast(this)
    while (stack.isNotEmpty()) {
      val node = stack.removeLast()
      order.addLast(node)
      node.children?.let { for (child in it) stack.addLast(child) }
    }
    while (order.isNotEmpty()) visit(order.removeLast())
  }

  fun descendants(): List<TreeNode> {
    val out = mutableListOf<TreeNode>()
    eachBefore { out += it }
    return out
  }

  /** Each node's value is its own measure plus its children's, so a parent is never smaller. */
  fun sum(measure: (VegaValue?) -> Double): TreeNode {
    eachAfter { node ->
      var total = measure(node.datum).let { if (it.isNaN()) 0.0 else it }
      node.children?.let { for (child in it) total += child.value }
      node.value = total
    }
    return this
  }

  /** A leaf counts as one, so a layout without a measure sizes by how many rows a branch holds. */
  fun count(): TreeNode {
    eachAfter { node ->
      val kids = node.children
      node.value = if (kids.isNullOrEmpty()) 1.0 else kids.sumOf { it.value }
    }
    return this
  }

  fun sortChildren(compare: Comparator<TreeNode>): TreeNode {
    eachBefore { node -> node.children?.sortWith(compare) }
    return this
  }

  companion object {
    /** Heights are filled in from each node upward, stopping once a parent already knows better. */
    internal fun computeHeight(node: TreeNode) {
      var height = 0
      var current: TreeNode? = node
      while (current != null) {
        current.height = height
        val parent = current.parent ?: break
        height++
        if (parent.height >= height) break
        current = parent
      }
    }
  }
}

/** The six coordinates a layout may report, in the order upstream's `as` names them. */
internal enum class TreeField {
  X0,
  Y0,
  X1,
  Y1,
  X,
  Y,
  R,
  DEPTH,
}

internal object TreeLayouts {

  internal fun read(node: TreeNode, field: TreeField): Double =
    when (field) {
      TreeField.X0 -> node.x0
      TreeField.Y0 -> node.y0
      TreeField.X1 -> node.x1
      TreeField.Y1 -> node.y1
      TreeField.X -> node.x
      TreeField.Y -> node.y
      TreeField.R -> node.r
      TreeField.DEPTH -> node.depth.toDouble()
    }

  // ---- treemap ------------------------------------------------------------

  /** The golden ratio, which is the aspect ratio `squarify` aims each rectangle at. */
  internal val PHI = (1 + sqrt(5.0)) / 2

  internal fun dice(parent: TreeNode, x0: Double, y0: Double, x1: Double, y1: Double) {
    val nodes = parent.children ?: return
    val k = if (parent.value == 0.0) 0.0 else (x1 - x0) / parent.value
    var x = x0
    for (node in nodes) {
      node.y0 = y0
      node.y1 = y1
      node.x0 = x
      x += node.value * k
      node.x1 = x
    }
  }

  internal fun slice(parent: TreeNode, x0: Double, y0: Double, x1: Double, y1: Double) {
    val nodes = parent.children ?: return
    val k = if (parent.value == 0.0) 0.0 else (y1 - y0) / parent.value
    var y = y0
    for (node in nodes) {
      node.x0 = x0
      node.x1 = x1
      node.y0 = y
      y += node.value * k
      node.y1 = y
    }
  }

  /**
   * Squarified treemap tiling: rows of children, each row as close to square as it can be got.
   *
   * The reason to prefer it is that a long thin rectangle's *area* is hard to judge by eye, so a
   * treemap made of slivers does not communicate the quantity it encodes. The cost is that sibling
   * order is not preserved in any readable way, which `dice`, `slice` and `binary` do keep.
   */
  internal fun squarify(
    ratio: Double,
    parent: TreeNode,
    left: Double,
    top: Double,
    right: Double,
    bottom: Double,
  ) {
    val nodes = parent.children ?: return
    val n = nodes.size
    var x0 = left
    var y0 = top
    val x1 = right
    val y1 = bottom
    var value = parent.value
    var i0 = 0
    var i1 = 0

    while (i0 < n) {
      val dx = x1 - x0
      val dy = y1 - y0

      // Skip past any zero-valued children: they take no space and would divide by zero below.
      var sumValue: Double
      do {
        sumValue = nodes[i1++].value
      } while (sumValue == 0.0 && i1 < n)
      var minValue = sumValue
      var maxValue = sumValue
      val alpha = max(dy / dx, dx / dy) / (value * ratio)
      var beta = sumValue * sumValue * alpha
      var minRatio = max(maxValue / beta, beta / minValue)

      // Grow the row while doing so makes its worst aspect ratio no worse.
      while (i1 < n) {
        val nodeValue = nodes[i1].value
        sumValue += nodeValue
        if (nodeValue < minValue) minValue = nodeValue
        if (nodeValue > maxValue) maxValue = nodeValue
        beta = sumValue * sumValue * alpha
        val newRatio = max(maxValue / beta, beta / minValue)
        if (newRatio > minRatio) {
          sumValue -= nodeValue
          break
        }
        minRatio = newRatio
        i1++
      }

      // The row is laid out as a miniature parent of its own slice of the children.
      val row = TreeNode(-1, null)
      row.value = sumValue
      row.children = nodes.subList(i0, i1).toMutableList()
      if (dx < dy) {
        val edge = if (value != 0.0) y0 + dy * sumValue / value else y1
        dice(row, x0, y0, x1, edge)
        y0 = edge
      } else {
        val edge = if (value != 0.0) x0 + dx * sumValue / value else x1
        slice(row, x0, y0, edge, y1)
        x0 = edge
      }
      value -= sumValue
      i0 = i1
    }
  }

  /**
   * Binary tiling: split the children into two halves of near-equal value and recurse, cutting
   * across whichever side is longer. Keeps sibling order, unlike `squarify`.
   */
  internal fun binary(parent: TreeNode, x0: Double, y0: Double, x1: Double, y1: Double) {
    val nodes = parent.children ?: return
    val n = nodes.size
    val sums = DoubleArray(n + 1)
    for (i in 0 until n) sums[i + 1] = sums[i] + nodes[i].value

    fun split(i: Int, j: Int, value: Double, ax0: Double, ay0: Double, ax1: Double, ay1: Double) {
      if (i >= j - 1) {
        val node = nodes[i]
        node.x0 = ax0
        node.y0 = ay0
        node.x1 = ax1
        node.y1 = ay1
        return
      }
      val offset = sums[i]
      val target = value / 2 + offset
      var k = i + 1
      var hi = j - 1
      while (k < hi) {
        val mid = (k + hi) ushr 1
        if (sums[mid] < target) k = mid + 1 else hi = mid
      }
      if ((target - sums[k - 1]) < (sums[k] - target) && i + 1 < k) k--

      val left = sums[k] - offset
      val right = value - left
      if ((ax1 - ax0) > (ay1 - ay0)) {
        val xk = if (value != 0.0) (ax0 * right + ax1 * left) / value else ax1
        split(i, k, left, ax0, ay0, xk, ay1)
        split(k, j, right, xk, ay0, ax1, ay1)
      } else {
        val yk = if (value != 0.0) (ay0 * right + ay1 * left) / value else ay1
        split(i, k, left, ax0, ay0, ax1, yk)
        split(k, j, right, ax0, yk, ax1, ay1)
      }
    }
    split(0, n, parent.value, x0, y0, x1, y1)
  }

  internal fun roundNode(node: TreeNode) {
    node.x0 = node.x0.roundToInt().toDouble()
    node.y0 = node.y0.roundToInt().toDouble()
    node.x1 = node.x1.roundToInt().toDouble()
    node.y1 = node.y1.roundToInt().toDouble()
  }

  internal class Padding(
    val inner: Double = 0.0,
    val top: Double = 0.0,
    val right: Double = 0.0,
    val bottom: Double = 0.0,
    val left: Double = 0.0,
  )

  internal fun treemap(
    root: TreeNode,
    width: Double,
    height: Double,
    padding: Padding,
    round: Boolean,
    tile: (TreeNode, Double, Double, Double, Double) -> Unit,
  ) {
    root.x0 = 0.0
    root.y0 = 0.0
    root.x1 = width
    root.y1 = height
    // Half the inner padding is taken off each side of a parent before its children are tiled, so
    // that a gap between two siblings is one inner padding rather than two.
    val stack = HashMap<Int, Double>()
    stack[0] = 0.0
    root.eachBefore { node ->
      val p = stack[node.depth] ?: 0.0
      var nx0 = node.x0 + p
      var ny0 = node.y0 + p
      var nx1 = node.x1 - p
      var ny1 = node.y1 - p
      if (nx1 < nx0) {
        nx0 = (nx0 + nx1) / 2
        nx1 = nx0
      }
      if (ny1 < ny0) {
        ny0 = (ny0 + ny1) / 2
        ny1 = ny0
      }
      node.x0 = nx0
      node.y0 = ny0
      node.x1 = nx1
      node.y1 = ny1
      if (node.children != null) {
        val half = padding.inner / 2
        stack[node.depth + 1] = half
        var cx0 = nx0 + padding.left - half
        var cy0 = ny0 + padding.top - half
        var cx1 = nx1 - padding.right + half
        var cy1 = ny1 - padding.bottom + half
        if (cx1 < cx0) {
          cx0 = (cx0 + cx1) / 2
          cx1 = cx0
        }
        if (cy1 < cy0) {
          cy0 = (cy0 + cy1) / 2
          cy1 = cy0
        }
        tile(node, cx0, cy0, cx1, cy1)
      }
    }
    if (round) root.eachBefore { roundNode(it) }
  }

  /**
   * Partition: an icicle plot, where depth is one axis and value the other.
   *
   * Every level gets an equal band of the height whatever its values are, and each node dices its
   * width among its children. So a partition shows *structure* at a glance where a treemap shows
   * *quantity* — the same tree reads very differently through the two.
   */
  internal fun partition(
    root: TreeNode,
    width: Double,
    height: Double,
    padding: Double,
    round: Boolean,
  ) {
    val levels = root.height + 1
    root.x0 = padding
    root.y0 = padding
    root.x1 = width
    root.y1 = height / levels
    root.eachBefore { node ->
      if (node.children != null) {
        dice(
          node,
          node.x0,
          height * (node.depth + 1) / levels,
          node.x1,
          height * (node.depth + 2) / levels,
        )
      }
      var x0 = node.x0
      var y0 = node.y0
      var x1 = node.x1 - padding
      var y1 = node.y1 - padding
      if (x1 < x0) {
        x0 = (x0 + x1) / 2
        x1 = x0
      }
      if (y1 < y0) {
        y0 = (y0 + y1) / 2
        y1 = y0
      }
      node.x0 = x0
      node.y0 = y0
      node.x1 = x1
      node.y1 = y1
    }
    if (round) root.eachBefore { roundNode(it) }
  }
}
