package dev.aster.vega.dataflow.transform

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Circle packing and the two node-link tree layouts, ported from `d3-hierarchy`.
 *
 * These are the two hierarchy layouts that are not simply divisions of a rectangle, and both are
 * genuinely intricate algorithms rather than arithmetic. They are transcribed rather than rederived
 * for that reason: `pack` combines a front-chain sibling packer with Welzl's smallest-enclosing
 * circle, and `tidy` is Buchheim, Jünger and Leipert's linear-time form of Reingold-Tilford, which
 * threads unused pointers through each subtree's contour so two subtrees can be pushed together in
 * time proportional to the contour rather than to the subtree.
 */
internal object TreeCircles {

  /**
   * d3's seeded generator, so a packed layout is the same every time it is drawn.
   *
   * Welzl's algorithm needs its input shuffled to stay near-linear, and a real random source would
   * mean a chart that moved between renders. The constants are the Numerical Recipes LCG.
   */
  private class Lcg {
    private var state = 1L

    fun next(): Double {
      state = (1664525L * state + 1013904223L) % 4294967296L
      return state.toDouble() / 4294967296.0
    }
  }

  private class Circle(var x: Double, var y: Double, var r: Double)

  // ---- pack ---------------------------------------------------------------

  /**
   * Nested circles, each leaf sized by the square root of its value.
   *
   * The square root is the point: it is *area* that should carry the quantity, as with a treemap,
   * and a radius proportional to value would exaggerate large leaves by squaring them.
   *
   * @param radiusOf a leaf's own radius when the specification names a `radius` field. Given one,
   *   the circles are packed at their stated sizes and only translated to fit; without one they are
   *   packed twice — once unpadded to learn the natural size, once with padding scaled to it — and
   *   then rescaled so the root fills the space.
   */
  internal fun pack(
    root: TreeNode,
    width: Double,
    height: Double,
    padding: Double,
    radiusOf: ((TreeNode) -> Double)?,
  ) {
    val random = Lcg()
    root.x = width / 2
    root.y = height / 2

    if (radiusOf != null) {
      root.eachBefore { node -> if (node.children == null) node.r = max(0.0, radiusOf(node)) }
      root.eachAfter { node -> packChildren(node, padding * 0.5, random) }
      root.eachBefore { node -> translate(node, 1.0) }
    } else {
      root.eachBefore { node ->
        if (node.children == null)
          node.r = max(0.0, sqrt(node.value).takeIf { it.isFinite() } ?: 0.0)
      }
      root.eachAfter { node -> packChildren(node, 0.0, random) }
      // The padding is expressed in the finished chart's units, so it has to be converted into the
      // units of the unscaled pack — which is not known until the first pass has finished.
      val k = root.r / min(width, height)
      root.eachAfter { node -> packChildren(node, padding * k, random) }
      // Read once, before the walk: translating the root changes `root.r`, and recomputing the
      // factor per node would leave every descendant scaled by 1.
      val scale = min(width, height) / (2 * root.r)
      root.eachBefore { node -> translate(node, scale) }
    }
  }

  private fun packChildren(node: TreeNode, pad: Double, random: Lcg) {
    val children = node.children ?: return
    if (pad != 0.0) for (child in children) child.r += pad
    val enclosing = packSiblings(children, random)
    if (pad != 0.0) for (child in children) child.r -= pad
    node.r = enclosing + pad
  }

  private fun translate(node: TreeNode, k: Double) {
    node.r *= k
    val parent = node.parent ?: return
    node.x = parent.x + k * node.x
    node.y = parent.y + k * node.y
  }

  /** Places `c` tangent to both `a` and `b`, on the side that keeps the chain convex. */
  private fun place(b: TreeNode, a: TreeNode, c: TreeNode) {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val d2 = dx * dx + dy * dy
    if (d2 != 0.0) {
      var a2 = a.r + c.r
      a2 *= a2
      var b2 = b.r + c.r
      b2 *= b2
      if (a2 > b2) {
        val x = (d2 + b2 - a2) / (2 * d2)
        val y = sqrt(max(0.0, b2 / d2 - x * x))
        c.x = b.x - x * dx - y * dy
        c.y = b.y - x * dy + y * dx
      } else {
        val x = (d2 + a2 - b2) / (2 * d2)
        val y = sqrt(max(0.0, a2 / d2 - x * x))
        c.x = a.x + x * dx - y * dy
        c.y = a.y + x * dy + y * dx
      }
    } else {
      c.x = a.x + c.r
      c.y = a.y
    }
  }

  private fun intersects(a: TreeNode, b: TreeNode): Boolean {
    val dr = a.r + b.r - 1e-6
    val dx = b.x - a.x
    val dy = b.y - a.y
    return dr > 0 && dr * dr > dx * dx + dy * dy
  }

  /** A doubly-linked ring of the circles currently on the packing front. */
  private class Link(val circle: TreeNode) {
    var next: Link? = null
    var previous: Link? = null
  }

  private fun score(link: Link): Double {
    val a = link.circle
    val b = link.next!!.circle
    val ab = a.r + b.r
    val dx = (a.x * b.r + b.x * a.r) / ab
    val dy = (a.y * b.r + b.y * a.r) / ab
    return dx * dx + dy * dy
  }

  /**
   * Packs siblings around each other by a front chain, and returns the enclosing radius.
   *
   * Each new circle is placed tangent to the two circles at the front of the chain. If it overlaps
   * something else on the chain, the chain is cut back to that circle and the placement retried —
   * so a circle can be attempted several times, which is why the loop counter goes backwards rather
   * than the iteration simply advancing.
   */
  private fun packSiblings(circles: List<TreeNode>, random: Lcg): Double {
    val n = circles.size
    if (n == 0) return 0.0

    var a = circles[0]
    a.x = 0.0
    a.y = 0.0
    if (n <= 1) return a.r

    var b = circles[1]
    a.x = -b.r
    b.x = a.r
    b.y = 0.0
    if (n <= 2) return a.r + b.r

    place(b, a, circles[2])

    var linkA = Link(a)
    var linkB = Link(b)
    var linkC = Link(circles[2])
    // A three-circle ring, forward a -> b -> c -> a.
    linkA.next = linkB
    linkB.next = linkC
    linkC.next = linkA
    linkA.previous = linkC
    linkB.previous = linkA
    linkC.previous = linkB

    var i = 3
    outer@ while (i < n) {
      place(linkA.circle, linkB.circle, circles[i])
      linkC = Link(circles[i])

      var j = linkB.next
      var k = linkA.previous
      var sj = linkB.circle.r
      var sk = linkA.circle.r
      do {
        if (sj <= sk) {
          if (intersects(j!!.circle, linkC.circle)) {
            linkB = j
            linkA.next = linkB
            linkB.previous = linkA
            continue@outer
          }
          sj += j.circle.r
          j = j.next
        } else {
          if (intersects(k!!.circle, linkC.circle)) {
            linkA = k
            linkA.next = linkB
            linkB.previous = linkA
            continue@outer
          }
          sk += k.circle.r
          k = k.previous
        }
      } while (j !== k!!.next)

      linkC.previous = linkA
      linkC.next = linkB
      linkA.next = linkC
      linkB.previous = linkC
      linkB = linkC

      // The chain's front moves to whichever adjacent pair now sits nearest the centroid.
      var best = score(linkA)
      var cursor = linkC.next
      while (cursor !== linkB) {
        val candidate = score(cursor!!)
        if (candidate < best) {
          linkA = cursor
          best = candidate
        }
        cursor = cursor.next
      }
      linkB = linkA.next!!
      i++
    }

    val front = mutableListOf(linkB.circle)
    var cursor = linkB.next
    while (cursor !== linkB) {
      front += cursor!!.circle
      cursor = cursor.next
    }
    val enclosing = enclose(front, random)
    for (circle in circles) {
      circle.x -= enclosing.x
      circle.y -= enclosing.y
    }
    return enclosing.r
  }

  /**
   * Welzl's smallest enclosing circle, over a shuffled input.
   *
   * The shuffle is what makes it near-linear rather than quadratic in the worst case, and it is
   * seeded, so the answer is the same every run.
   */
  private fun enclose(nodes: List<TreeNode>, random: Lcg): Circle {
    val circles = nodes.map { Circle(it.x, it.y, it.r) }.toMutableList()
    var m = circles.size
    while (m > 0) {
      val index = (random.next() * m).toInt()
      m--
      val t = circles[m]
      circles[m] = circles[index]
      circles[index] = t
    }

    var i = 0
    var basis = listOf<Circle>()
    var result: Circle? = null
    while (i < circles.size) {
      val p = circles[i]
      if (result != null && enclosesWeak(result, p)) {
        i++
      } else {
        basis = extendBasis(basis, p)
        result = encloseBasis(basis)
        i = 0
      }
    }
    return result ?: Circle(0.0, 0.0, 0.0)
  }

  private fun extendBasis(basis: List<Circle>, p: Circle): List<Circle> {
    if (enclosesWeakAll(p, basis)) return listOf(p)
    for (b in basis) {
      if (enclosesNot(p, b) && enclosesWeakAll(encloseBasis2(b, p), basis)) return listOf(b, p)
    }
    for (i in 0 until basis.size - 1) {
      for (j in i + 1 until basis.size) {
        if (
          enclosesNot(encloseBasis2(basis[i], basis[j]), p) &&
            enclosesNot(encloseBasis2(basis[i], p), basis[j]) &&
            enclosesNot(encloseBasis2(basis[j], p), basis[i]) &&
            enclosesWeakAll(encloseBasis3(basis[i], basis[j], p), basis)
        ) {
          return listOf(basis[i], basis[j], p)
        }
      }
    }
    // d3 throws here; a basis of three circles always encloses a fourth, so reaching this means the
    // input held a NaN. Returning the point alone degrades the packing rather than the whole chart.
    return listOf(p)
  }

  private fun enclosesNot(a: Circle, b: Circle): Boolean {
    val dr = a.r - b.r
    val dx = b.x - a.x
    val dy = b.y - a.y
    return dr < 0 || dr * dr < dx * dx + dy * dy
  }

  private fun enclosesWeak(a: Circle, b: Circle): Boolean {
    val dr = a.r - b.r + max(max(a.r, b.r), 1.0) * 1e-9
    val dx = b.x - a.x
    val dy = b.y - a.y
    return dr > 0 && dr * dr > dx * dx + dy * dy
  }

  private fun enclosesWeakAll(a: Circle, basis: List<Circle>): Boolean = basis.all {
    enclosesWeak(a, it)
  }

  private fun encloseBasis(basis: List<Circle>): Circle =
    when (basis.size) {
      1 -> Circle(basis[0].x, basis[0].y, basis[0].r)
      2 -> encloseBasis2(basis[0], basis[1])
      else -> encloseBasis3(basis[0], basis[1], basis[2])
    }

  private fun encloseBasis2(a: Circle, b: Circle): Circle {
    val x21 = b.x - a.x
    val y21 = b.y - a.y
    val r21 = b.r - a.r
    val l = sqrt(x21 * x21 + y21 * y21)
    return Circle(
      (a.x + b.x + x21 / l * r21) / 2,
      (a.y + b.y + y21 / l * r21) / 2,
      (l + a.r + b.r) / 2,
    )
  }

  private fun encloseBasis3(a: Circle, b: Circle, c: Circle): Circle {
    val a2 = a.x - b.x
    val a3 = a.x - c.x
    val b2 = a.y - b.y
    val b3 = a.y - c.y
    val c2 = b.r - a.r
    val c3 = c.r - a.r
    val d1 = a.x * a.x + a.y * a.y - a.r * a.r
    val d2 = d1 - b.x * b.x - b.y * b.y + b.r * b.r
    val d3 = d1 - c.x * c.x - c.y * c.y + c.r * c.r
    val ab = a3 * b2 - a2 * b3
    val xa = (b2 * d3 - b3 * d2) / (ab * 2) - a.x
    val xb = (b3 * c2 - b2 * c3) / ab
    val ya = (a3 * d2 - a2 * d3) / (ab * 2) - a.y
    val yb = (a2 * c3 - a3 * c2) / ab
    val bigA = xb * xb + yb * yb - 1
    val bigB = 2 * (a.r + xa * xb + ya * yb)
    val bigC = xa * xa + ya * ya - a.r * a.r
    val r =
      -(if (abs(bigA) > 1e-6) (bigB + sqrt(bigB * bigB - 4 * bigA * bigC)) / (2 * bigA)
      else bigC / bigB)
    return Circle(a.x + xa + xb * r, a.y + ya + yb * r, r)
  }

  // ---- tidy and cluster ---------------------------------------------------

  /**
   * How far apart two neighbours are kept: further if they are cousins than if they are siblings.
   */
  private fun separation(a: TreeNode, b: TreeNode, enabled: Boolean): Double =
    if (!enabled) 1.0 else if (a.parent === b.parent) 1.0 else 2.0

  /** The scratch node Buchheim's algorithm needs, parallel to the real tree. */
  private class Walk(val node: TreeNode, val order: Int) {
    var parent: Walk? = null
    var children: MutableList<Walk>? = null

    /** The default ancestor for this node's children. */
    var defaultAncestor: Walk? = null

    /** This node's own ancestor, for finding the greatest uncommon one. */
    var ancestor: Walk = this
    var prelim: Double = 0.0
    var mod: Double = 0.0
    var change: Double = 0.0
    var shift: Double = 0.0

    /** The unused pointer threaded through a subtree's contour. */
    var thread: Walk? = null

    fun eachAfter(visit: (Walk) -> Unit) {
      val stack = ArrayDeque<Walk>()
      val order = ArrayDeque<Walk>()
      stack.addLast(this)
      while (stack.isNotEmpty()) {
        val w = stack.removeLast()
        order.addLast(w)
        w.children?.let { for (child in it) stack.addLast(child) }
      }
      while (order.isNotEmpty()) visit(order.removeLast())
    }

    fun eachBefore(visit: (Walk) -> Unit) {
      val stack = ArrayDeque<Walk>()
      stack.addLast(this)
      while (stack.isNotEmpty()) {
        val w = stack.removeLast()
        visit(w)
        w.children?.let { for (i in it.indices.reversed()) stack.addLast(it[i]) }
      }
    }
  }

  /**
   * Reingold-Tilford "tidy" layout, in Buchheim, Jünger and Leipert's linear-time form.
   *
   * The property it guarantees is the one a reader assumes and most simpler layouts break:
   * **identical subtrees are drawn identically wherever they appear**, and a parent sits centred
   * over its children. Getting that in linear time is what the threads and the deferred shifts are
   * for — a naïve version has to walk a whole subtree each time two are pushed together.
   */
  internal fun tidy(
    root: TreeNode,
    width: Double,
    height: Double,
    nodeSize: Pair<Double, Double>?,
    separate: Boolean,
  ) {
    val walkRoot = buildWalk(root)
    val above = Walk(TreeNode(-1, null), 0)
    above.children = mutableListOf(walkRoot)
    walkRoot.parent = above

    walkRoot.eachAfter { v -> firstWalk(v, separate) }
    above.mod = -walkRoot.prelim
    walkRoot.eachBefore { v ->
      v.node.x = v.prelim + v.parent!!.mod
      v.mod += v.parent!!.mod
    }

    if (nodeSize != null) {
      root.eachBefore { node ->
        node.x *= nodeSize.first
        node.y = node.depth * nodeSize.second
      }
    } else {
      var left = root
      var right = root
      var bottom = root
      root.eachBefore { node ->
        if (node.x < left.x) left = node
        if (node.x > right.x) right = node
        if (node.depth > bottom.depth) bottom = node
      }
      val s = if (left === right) 1.0 else separation(left, right, separate) / 2
      val tx = s - left.x
      val kx = width / (right.x + s + tx)
      val ky = height / (if (bottom.depth == 0) 1.0 else bottom.depth.toDouble())
      root.eachBefore { node ->
        node.x = (node.x + tx) * kx
        node.y = node.depth * ky
      }
    }
  }

  private fun buildWalk(root: TreeNode): Walk {
    val walkRoot = Walk(root, 0)
    val stack = ArrayDeque<Walk>()
    stack.addLast(walkRoot)
    while (stack.isNotEmpty()) {
      val w = stack.removeLast()
      w.node.children?.let { kids ->
        val list = MutableList(kids.size) { index -> Walk(kids[index], index) }
        w.children = list
        for (child in list) {
          child.parent = w
          stack.addLast(child)
        }
      }
    }
    return walkRoot
  }

  private fun nextLeft(v: Walk): Walk? = v.children?.firstOrNull() ?: v.thread

  private fun nextRight(v: Walk): Walk? = v.children?.lastOrNull() ?: v.thread

  private fun moveSubtree(wm: Walk, wp: Walk, shift: Double) {
    val change = shift / (wp.order - wm.order)
    wp.change -= change
    wp.shift += shift
    wm.change += change
    wp.prelim += shift
    wp.mod += shift
  }

  /** The deferred shifts: applied to every subtree between the two that collided, right to left. */
  private fun executeShifts(v: Walk) {
    val children = v.children ?: return
    var shift = 0.0
    var change = 0.0
    for (i in children.indices.reversed()) {
      val w = children[i]
      w.prelim += shift
      w.mod += shift
      change += w.change
      shift += w.shift + change
    }
  }

  private fun firstWalk(v: Walk, separate: Boolean) {
    val siblings = v.parent!!.children!!
    val w = if (v.order > 0) siblings[v.order - 1] else null
    val children = v.children
    if (children != null) {
      executeShifts(v)
      val midpoint = (children.first().prelim + children.last().prelim) / 2
      if (w != null) {
        v.prelim = w.prelim + separation(v.node, w.node, separate)
        v.mod = v.prelim - midpoint
      } else {
        v.prelim = midpoint
      }
    } else if (w != null) {
      v.prelim = w.prelim + separation(v.node, w.node, separate)
    }
    v.parent!!.defaultAncestor =
      apportion(v, w, v.parent!!.defaultAncestor ?: siblings[0], separate)
  }

  /**
   * Pushes the subtree at `v` clear of the one at `w`, its left neighbour.
   *
   * The two inside contours are walked in step until one runs out. Where they collide, the whole
   * subtree is moved and the shift is *recorded* rather than applied to the subtrees between them —
   * [executeShifts] settles those in one pass later, which is what keeps the whole layout linear.
   */
  private fun apportion(v: Walk, w: Walk?, defaultAncestor: Walk, separate: Boolean): Walk {
    if (w == null) return defaultAncestor
    var ancestor = defaultAncestor
    // Inside and outside contours of the right subtree (p) and the left one (m).
    var insideRight: Walk? = v
    var outsideRight: Walk = v
    var insideLeft: Walk? = w
    var outsideLeft: Walk = v.parent!!.children!![0]
    var sInsideRight = v.mod
    var sOutsideRight = outsideRight.mod
    var sInsideLeft = w.mod
    var sOutsideLeft = outsideLeft.mod

    while (true) {
      // Both advance before either is tested, so after the loop the one holding null is the
      // contour that ran out — which is exactly what the two tests below read.
      insideLeft = nextRight(insideLeft!!)
      insideRight = nextLeft(insideRight!!)
      if (insideLeft == null || insideRight == null) break
      outsideLeft = nextLeft(outsideLeft) ?: break
      outsideRight = nextRight(outsideRight) ?: break
      outsideRight.ancestor = v
      val shift =
        insideLeft.prelim + sInsideLeft - insideRight.prelim - sInsideRight +
          separation(insideLeft.node, insideRight.node, separate)
      if (shift > 0) {
        val greatestUncommon =
          if (insideLeft.ancestor.parent === v.parent) insideLeft.ancestor else ancestor
        moveSubtree(greatestUncommon, v, shift)
        sInsideRight += shift
        sOutsideRight += shift
      }
      sInsideLeft += insideLeft.mod
      sInsideRight += insideRight.mod
      sOutsideLeft += outsideLeft.mod
      sOutsideRight += outsideRight.mod
    }

    // Whichever contour ran out first gets a thread to the other, so the next comparison can walk
    // past the end of its own subtree without a parent pointer.
    if (insideLeft != null && nextRight(outsideRight) == null) {
      outsideRight.thread = insideLeft
      outsideRight.mod += sInsideLeft - sOutsideRight
    }
    if (insideRight != null && nextLeft(outsideLeft) == null) {
      outsideLeft.thread = insideRight
      outsideLeft.mod += sInsideRight - sOutsideLeft
      ancestor = v
    }
    return ancestor
  }

  /**
   * Cluster, or dendrogram: every **leaf** on the last row, whatever its depth.
   *
   * The opposite emphasis to `tidy`, which puts every node at its own depth. A dendrogram lines the
   * leaves up because the leaves are the things being compared — which is why it is the shape used
   * for a clustering result, where the interior nodes are only the joins.
   */
  internal fun cluster(
    root: TreeNode,
    width: Double,
    height: Double,
    nodeSize: Pair<Double, Double>?,
    separate: Boolean,
  ) {
    var previous: TreeNode? = null
    var x = 0.0
    root.eachAfter { node ->
      val children = node.children
      if (children != null) {
        node.x = children.sumOf { it.x } / children.size
        node.y = 1 + children.maxOf { it.y }
      } else {
        node.x =
          if (previous != null) {
            x += separation(node, previous!!, separate)
            x
          } else {
            0.0
          }
        node.y = 0.0
        previous = node
      }
    }

    var left = root
    while (left.children != null) left = left.children!!.first()
    var right = root
    while (right.children != null) right = right.children!!.last()
    val x0 = left.x - separation(left, right, separate) / 2
    val x1 = right.x + separation(right, left, separate) / 2

    if (nodeSize != null) {
      root.eachAfter { node ->
        node.x = (node.x - root.x) * nodeSize.first
        node.y = (root.y - node.y) * nodeSize.second
      }
    } else {
      root.eachAfter { node ->
        node.x = (node.x - x0) / (x1 - x0) * width
        node.y = (1 - (if (root.y != 0.0) node.y / root.y else 1.0)) * height
      }
    }
  }
}
