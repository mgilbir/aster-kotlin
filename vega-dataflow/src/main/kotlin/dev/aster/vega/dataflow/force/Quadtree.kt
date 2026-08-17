package dev.aster.vega.dataflow.force

/**
 * d3-quadtree, as much of it as a force simulation uses.
 *
 * Two of the four forces are only affordable because of this tree — `nbody` approximates a whole
 * quadrant by its centre of mass, and `collide` skips any quadrant no node can reach — so the
 * tree's *shape* is part of the arithmetic, not an implementation detail. A tree built differently
 * gives different sums in a different order, and a chart whose nodes are all somewhere else.
 *
 * Ported from `d3-quadtree/src/{quadtree,add,cover,visit,visitAfter}.js`. The parts a simulation
 * never calls — `remove`, `find`, `copy`, `size` — are not here.
 *
 * The one thing worth knowing before reading it: the extent is grown by **doubling**, and starts on
 * integer bounds. That is deliberate upstream ("integer extent are necessary so that if we later
 * double the extent, the existing quadrant boundaries don't change due to floating point error"),
 * and copying the trick is what keeps two engines' trees identical.
 */
internal class Quadtree(
  private val xOf: (ForceNode) -> Double,
  private val yOf: (ForceNode) -> Double,
) {
  var root: QuadNode? = null
    private set

  private var x0 = Double.NaN
  private var y0 = Double.NaN
  private var x1 = Double.NaN
  private var y1 = Double.NaN

  fun addAll(data: List<ForceNode>): Quadtree {
    val n = data.size
    val xz = DoubleArray(n)
    val yz = DoubleArray(n)
    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY

    for (index in 0 until n) {
      val x = xOf(data[index])
      val y = yOf(data[index])
      if (x.isNaN() || y.isNaN()) continue
      xz[index] = x
      yz[index] = y
      if (x < minX) minX = x
      if (x > maxX) maxX = x
      if (y < minY) minY = y
      if (y > maxY) maxY = y
    }

    if (minX > maxX || minY > maxY) return this
    cover(minX, minY)
    cover(maxX, maxY)
    for (index in 0 until n) add(xz[index], yz[index], data[index])
    return this
  }

  /** Grows the extent until it contains the point, doubling each time. */
  private fun cover(x: Double, y: Double) {
    if (x.isNaN() || y.isNaN()) return

    if (x0.isNaN()) {
      x0 = kotlin.math.floor(x)
      x1 = x0 + 1
      y0 = kotlin.math.floor(y)
      y1 = y0 + 1
      return
    }

    var z = (x1 - x0).takeIf { it != 0.0 } ?: 1.0
    var node = root
    while (x0 > x || x >= x1 || y0 > y || y >= y1) {
      val quadrant = (if (y < y0) 2 else 0) or (if (x < x0) 1 else 0)
      val parent = QuadNode.internalNode()
      parent.children!![quadrant] = node
      node = parent
      z *= 2
      when (quadrant) {
        0 -> {
          x1 = x0 + z
          y1 = y0 + z
        }
        1 -> {
          x0 = x1 - z
          y1 = y0 + z
        }
        2 -> {
          x1 = x0 + z
          y0 = y1 - z
        }
        else -> {
          x0 = x1 - z
          y0 = y1 - z
        }
      }
    }
    // Only when the old root was itself a branch: a lone leaf stays the root however far the
    // extent has grown, because there is nothing yet to divide.
    if (root?.isBranch == true) root = node
  }

  private fun add(x: Double, y: Double, d: ForceNode) {
    if (x.isNaN() || y.isNaN()) return

    val leaf = QuadNode.leafNode(d)
    var node = root
    if (node == null) {
      root = leaf
      return
    }

    var nx0 = x0
    var ny0 = y0
    var nx1 = x1
    var ny1 = y1
    var parent: QuadNode? = null
    var quadrant = 0
    var xm: Double
    var ym: Double

    // Down to the leaf this point shares a cell with, or into an empty quadrant.
    while (node!!.isBranch) {
      xm = (nx0 + nx1) / 2
      val right = x >= xm
      if (right) nx0 = xm else nx1 = xm
      ym = (ny0 + ny1) / 2
      val bottom = y >= ym
      if (bottom) ny0 = ym else ny1 = ym
      parent = node
      quadrant = (if (bottom) 2 else 0) or (if (right) 1 else 0)
      val child = node.children!![quadrant]
      if (child == null) {
        parent.children[quadrant] = leaf
        return
      }
      node = child
    }

    val xp = xOf(node.data!!)
    val yp = yOf(node.data)
    // Exactly coincident: the leaves chain rather than splitting forever.
    if (x == xp && y == yp) {
      leaf.next = node
      if (parent != null) parent.children!![quadrant] = leaf else root = leaf
      return
    }

    // Otherwise split until the two points fall in different quadrants.
    var mine: Int
    var theirs: Int
    do {
      val branch = QuadNode.internalNode()
      if (parent != null) parent.children!![quadrant] = branch else root = branch
      parent = branch
      xm = (nx0 + nx1) / 2
      val right = x >= xm
      if (right) nx0 = xm else nx1 = xm
      ym = (ny0 + ny1) / 2
      val bottom = y >= ym
      if (bottom) ny0 = ym else ny1 = ym
      mine = (if (bottom) 2 else 0) or (if (right) 1 else 0)
      theirs = (if (yp >= ym) 2 else 0) or (if (xp >= xm) 1 else 0)
      quadrant = mine
    } while (mine == theirs)
    parent.children!![theirs] = node
    parent.children[mine] = leaf
  }

  /**
   * Pre-order, stopping wherever the visitor says so.
   *
   * The children are pushed in reverse so quadrant 0 is popped first — which decides the order the
   * forces accumulate in, and therefore the low bits of every sum.
   */
  fun visit(callback: (QuadNode, Double, Double, Double, Double) -> Boolean) {
    val stack = ArrayDeque<Quad>()
    root?.let { stack.addLast(Quad(it, x0, y0, x1, y1)) }
    while (stack.isNotEmpty()) {
      val q = stack.removeLast()
      val node = q.node
      if (!callback(node, q.x0, q.y0, q.x1, q.y1) && node.isBranch) {
        val xm = (q.x0 + q.x1) / 2
        val ym = (q.y0 + q.y1) / 2
        val children = node.children!!
        children[3]?.let { stack.addLast(Quad(it, xm, ym, q.x1, q.y1)) }
        children[2]?.let { stack.addLast(Quad(it, q.x0, ym, xm, q.y1)) }
        children[1]?.let { stack.addLast(Quad(it, xm, q.y0, q.x1, ym)) }
        children[0]?.let { stack.addLast(Quad(it, q.x0, q.y0, xm, ym)) }
      }
    }
  }

  /** Post-order: every child is visited before its parent, which is what lets a parent sum them. */
  fun visitAfter(callback: (QuadNode) -> Unit): Quadtree {
    val stack = ArrayDeque<Quad>()
    val ordered = ArrayDeque<Quad>()
    root?.let { stack.addLast(Quad(it, x0, y0, x1, y1)) }
    while (stack.isNotEmpty()) {
      val q = stack.removeLast()
      val node = q.node
      if (node.isBranch) {
        val xm = (q.x0 + q.x1) / 2
        val ym = (q.y0 + q.y1) / 2
        val children = node.children!!
        children[0]?.let { stack.addLast(Quad(it, q.x0, q.y0, xm, ym)) }
        children[1]?.let { stack.addLast(Quad(it, xm, q.y0, q.x1, ym)) }
        children[2]?.let { stack.addLast(Quad(it, q.x0, ym, xm, q.y1)) }
        children[3]?.let { stack.addLast(Quad(it, xm, ym, q.x1, q.y1)) }
      }
      ordered.addLast(q)
    }
    while (ordered.isNotEmpty()) callback(ordered.removeLast().node)
    return this
  }
}

/** A node with the cell it occupies, which a visitor needs and the node itself does not carry. */
internal class Quad(
  val node: QuadNode,
  val x0: Double,
  val y0: Double,
  val x1: Double,
  val y1: Double,
)

/**
 * A quadtree node: either a branch of four quadrants or a leaf holding one or more coincident
 * nodes.
 *
 * The accumulator fields are the forces' scratch space, exactly as they are upstream — d3 hangs `r`
 * on the quad for `collide` and `value`/`x`/`y` for `nbody`, on the same object. Keeping them here
 * rather than in a side map matters for the same reason: a `visitAfter` pass writes them and the
 * `visit` pass straight after reads them, per tick, for every node.
 */
internal class QuadNode
private constructor(
  val children: Array<QuadNode?>?,
  val data: ForceNode?,
) {
  /** The next coincident node at exactly this point; leaves chain rather than splitting forever. */
  var next: QuadNode? = null

  /** `collide`: the largest radius anywhere under this quadrant. */
  var r: Double = 0.0

  /** `nbody`: the total charge under this quadrant, and where its centre of mass sits. */
  var value: Double = 0.0
  var cx: Double = 0.0
  var cy: Double = 0.0

  val isBranch: Boolean
    get() = children != null

  companion object {
    fun internalNode(): QuadNode = QuadNode(arrayOfNulls(4), null)

    fun leafNode(data: ForceNode): QuadNode = QuadNode(null, data)
  }
}
