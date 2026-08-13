package dev.aster.vega.dataflow.voronoi

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * An incremental Delaunay triangulation, ported from `delaunator` line for line.
 *
 * The algorithm: pick a seed triangle near the middle of the points, sort every other point by
 * distance from that triangle's circumcentre, and add them one at a time to an advancing convex
 * hull, flipping edges behind each addition until the Delaunay condition holds again. Because the
 * points arrive in order of distance, each one is visible from a short run of hull edges, and the
 * run is found through an **angular hash** rather than by walking the hull.
 *
 * Three things make this a port rather than an implementation.
 *
 * Every geometric decision goes through [Orient2d], not an ordinary cross product. Points along a
 * coastline are nearly collinear, and a single wrong sign does not perturb the triangulation — it
 * produces a different one, or sends the hull walk into a loop.
 *
 * The point order is the *exact* order `quicksort` leaves them in, median-of-three and insertion
 * cutoff at twenty included. Delaunay triangulations are not unique on cocircular points, so the
 * insertion order decides which one comes out.
 *
 * And the arrays are the same fixed-size arrays: `_hullNext[e] == e` marks an edge as removed,
 * which only works because the arrays are indexed by point rather than by position.
 */
internal class Delaunator(private val coords: DoubleArray) {

  private val n = coords.size shr 1

  private val trianglesBuffer = IntArray(maxOf(2 * n - 5, 0) * 3)
  private val halfedgesBuffer = IntArray(maxOf(2 * n - 5, 0) * 3)

  private val hashSize = ceil(sqrt(n.toDouble())).toInt().coerceAtLeast(1)
  private val hullPrev = IntArray(n)
  private val hullNext = IntArray(n)
  private val hullTri = IntArray(n)
  private val hullHash = IntArray(hashSize)

  private val ids = IntArray(n)
  private val dists = DoubleArray(n)

  private var trianglesLen = 0
  private var cx = 0.0
  private var cy = 0.0
  private var hullStart = 0

  /** Triangle vertex indices, three per triangle, each triangle counter-clockwise. */
  var triangles: IntArray = IntArray(0)
    private set

  /** The twin of each half-edge, or `-1` where the edge is on the hull. */
  var halfedges: IntArray = IntArray(0)
    private set

  /** The convex hull as point indices, counter-clockwise. */
  var hull: IntArray = IntArray(0)
    private set

  /** The coordinates this triangulation was built from, for a caller that needs to measure them. */
  val coordsView: DoubleArray
    get() = coords

  private val edgeStack = IntArray(512)

  init {
    update()
  }

  @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
  private fun update() {
    if (n == 0) return

    var minX = Double.POSITIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxX = Double.NEGATIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY
    for (i in 0 until n) {
      val x = coords[2 * i]
      val y = coords[2 * i + 1]
      if (x < minX) minX = x
      if (y < minY) minY = y
      if (x > maxX) maxX = x
      if (y > maxY) maxY = y
      ids[i] = i
    }
    val centreX = (minX + maxX) / 2
    val centreY = (minY + maxY) / 2

    var i0 = 0
    var i1 = 0
    var i2 = 0

    // The point nearest the middle of the input, which starts the seed triangle.
    var minDist = Double.POSITIVE_INFINITY
    for (i in 0 until n) {
      val d = dist(centreX, centreY, coords[2 * i], coords[2 * i + 1])
      if (d < minDist) {
        i0 = i
        minDist = d
      }
    }
    val i0x = coords[2 * i0]
    val i0y = coords[2 * i0 + 1]

    minDist = Double.POSITIVE_INFINITY
    for (i in 0 until n) {
      if (i == i0) continue
      val d = dist(i0x, i0y, coords[2 * i], coords[2 * i + 1])
      if (d < minDist && d > 0) {
        i1 = i
        minDist = d
      }
    }
    var i1x = coords[2 * i1]
    var i1y = coords[2 * i1 + 1]

    // The third point is whichever makes the *smallest* circumcircle with the first two, which is
    // what keeps the seed triangle compact and the insertion order meaningful.
    var minRadius = Double.POSITIVE_INFINITY
    for (i in 0 until n) {
      if (i == i0 || i == i1) continue
      val r = circumradius(i0x, i0y, i1x, i1y, coords[2 * i], coords[2 * i + 1])
      if (r < minRadius) {
        i2 = i
        minRadius = r
      }
    }
    var i2x = coords[2 * i2]
    var i2y = coords[2 * i2 + 1]

    if (minRadius == Double.POSITIVE_INFINITY) {
      // Every point is collinear: there are no triangles, and the hull is the points in order.
      for (i in 0 until n) {
        dists[i] =
          (coords[2 * i] - coords[0]).takeIf { it != 0.0 } ?: (coords[2 * i + 1] - coords[1])
      }
      quicksort(ids, dists, 0, n - 1)
      val collinear = IntArray(n)
      var j = 0
      var d0 = Double.NEGATIVE_INFINITY
      for (i in 0 until n) {
        val id = ids[i]
        val d = dists[id]
        if (d > d0) {
          collinear[j++] = id
          d0 = d
        }
      }
      hull = collinear.copyOf(j)
      triangles = IntArray(0)
      halfedges = IntArray(0)
      return
    }

    // Counter-clockwise, so every triangle added later has a consistent winding.
    if (Orient2d.orient(i0x, i0y, i1x, i1y, i2x, i2y) < 0) {
      val i = i1
      val x = i1x
      val y = i1y
      i1 = i2
      i1x = i2x
      i1y = i2y
      i2 = i
      i2x = x
      i2y = y
    }

    val centre = circumcenter(i0x, i0y, i1x, i1y, i2x, i2y)
    cx = centre[0]
    cy = centre[1]

    for (i in 0 until n) {
      dists[i] = dist(coords[2 * i], coords[2 * i + 1], centre[0], centre[1])
    }
    quicksort(ids, dists, 0, n - 1)

    hullStart = i0
    var hullSize = 3

    hullNext[i0] = i1
    hullPrev[i2] = i1
    hullNext[i1] = i2
    hullPrev[i0] = i2
    hullNext[i2] = i0
    hullPrev[i1] = i0

    hullTri[i0] = 0
    hullTri[i1] = 1
    hullTri[i2] = 2

    hullHash.fill(-1)
    hullHash[hashKey(i0x, i0y)] = i0
    hullHash[hashKey(i1x, i1y)] = i1
    hullHash[hashKey(i2x, i2y)] = i2

    trianglesLen = 0
    addTriangle(i0, i1, i2, -1, -1, -1)

    var xp = 0.0
    var yp = 0.0
    for (k in ids.indices) {
      val i = ids[k]
      val x = coords[2 * i]
      val y = coords[2 * i + 1]

      // Near-duplicates are skipped: two points a hair apart give a degenerate triangle whose
      // circumcentre is somewhere off at infinity.
      if (k > 0 && abs(x - xp) <= EPSILON && abs(y - yp) <= EPSILON) continue
      xp = x
      yp = y

      if (i == i0 || i == i1 || i == i2) continue

      // The angular hash gives a hull edge near the right place; the walk below finds the exact
      // one.
      var start = 0
      val key = hashKey(x, y)
      for (j in 0 until hashSize) {
        start = hullHash[(key + j) % hashSize]
        if (start != -1 && start != hullNext[start]) break
      }

      start = hullPrev[start]
      var e = start
      var q: Int
      while (true) {
        q = hullNext[e]
        if (
          Orient2d.orient(
            x,
            y,
            coords[2 * e],
            coords[2 * e + 1],
            coords[2 * q],
            coords[2 * q + 1],
          ) < 0
        ) {
          break
        }
        e = q
        if (e == start) {
          e = -1
          break
        }
      }
      if (e == -1) continue

      var t = addTriangle(e, i, hullNext[e], -1, -1, hullTri[e])
      hullTri[i] = legalize(t + 2)
      hullTri[e] = t
      hullSize++

      // Forward along the hull, adding a triangle for every edge the new point can see.
      var next = hullNext[e]
      while (true) {
        q = hullNext[next]
        if (
          Orient2d.orient(
            x,
            y,
            coords[2 * next],
            coords[2 * next + 1],
            coords[2 * q],
            coords[2 * q + 1],
          ) >= 0
        ) {
          break
        }
        t = addTriangle(next, i, q, hullTri[i], -1, hullTri[next])
        hullTri[i] = legalize(t + 2)
        hullNext[next] = next
        hullSize--
        next = q
      }

      // And backward, but only from the edge the hash landed on.
      if (e == start) {
        while (true) {
          q = hullPrev[e]
          if (
            Orient2d.orient(
              x,
              y,
              coords[2 * q],
              coords[2 * q + 1],
              coords[2 * e],
              coords[2 * e + 1],
            ) >= 0
          ) {
            break
          }
          t = addTriangle(q, i, e, -1, hullTri[e], hullTri[q])
          legalize(t + 2)
          hullTri[q] = t
          hullNext[e] = e
          hullSize--
          e = q
        }
      }

      hullStart = e
      hullPrev[i] = e
      hullNext[e] = i
      hullPrev[next] = i
      hullNext[i] = next

      hullHash[hashKey(x, y)] = i
      hullHash[hashKey(coords[2 * e], coords[2 * e + 1])] = e
    }

    hull = IntArray(hullSize)
    var e = hullStart
    for (i in 0 until hullSize) {
      hull[i] = e
      e = hullNext[e]
    }

    triangles = trianglesBuffer.copyOf(trianglesLen)
    halfedges = halfedgesBuffer.copyOf(trianglesLen)
  }

  /** An angle-like key with no trigonometry in it, which is all the hash needs. */
  private fun hashKey(x: Double, y: Double): Int {
    val angle = pseudoAngle(x - cx, y - cy)
    return floor(angle * hashSize).toInt().mod(hashSize)
  }

  /**
   * Flips edges from `a` outwards until every pair of triangles satisfies the Delaunay condition.
   *
   * The recursion is a fixed stack upstream and here: the flips cascade, and a real recursion on
   * pathological input would run out of frames where the stack simply stops growing.
   */
  @Suppress("NestedBlockDepth")
  private fun legalize(start: Int): Int {
    var a = start
    var i = 0
    var ar = 0

    while (true) {
      val b = halfedgesBuffer[a]
      val a0 = a - a % 3
      ar = a0 + (a + 2) % 3

      if (b == -1) {
        if (i == 0) break
        a = edgeStack[--i]
        continue
      }

      val b0 = b - b % 3
      val al = a0 + (a + 1) % 3
      val bl = b0 + (b + 2) % 3

      val p0 = trianglesBuffer[ar]
      val pr = trianglesBuffer[a]
      val pl = trianglesBuffer[al]
      val p1 = trianglesBuffer[bl]

      val illegal =
        inCircle(
          coords[2 * p0],
          coords[2 * p0 + 1],
          coords[2 * pr],
          coords[2 * pr + 1],
          coords[2 * pl],
          coords[2 * pl + 1],
          coords[2 * p1],
          coords[2 * p1 + 1],
        )

      if (illegal) {
        trianglesBuffer[a] = p1
        trianglesBuffer[b] = p0

        val hbl = halfedgesBuffer[bl]
        // The flipped edge was on the far side of the hull, which happens rarely and leaves a hull
        // triangle pointing at the wrong half-edge.
        if (hbl == -1) {
          var e = hullStart
          do {
            if (hullTri[e] == bl) {
              hullTri[e] = a
              break
            }
            e = hullPrev[e]
          } while (e != hullStart)
        }
        link(a, hbl)
        link(b, halfedgesBuffer[ar])
        link(ar, bl)

        val br = b0 + (b + 1) % 3
        if (i < edgeStack.size) edgeStack[i++] = br
      } else {
        if (i == 0) break
        a = edgeStack[--i]
      }
    }
    return ar
  }

  private fun link(a: Int, b: Int) {
    halfedgesBuffer[a] = b
    if (b != -1) halfedgesBuffer[b] = a
  }

  @Suppress("LongParameterList")
  private fun addTriangle(i0: Int, i1: Int, i2: Int, a: Int, b: Int, c: Int): Int {
    val t = trianglesLen
    trianglesBuffer[t] = i0
    trianglesBuffer[t + 1] = i1
    trianglesBuffer[t + 2] = i2
    link(t, a)
    link(t + 1, b)
    link(t + 2, c)
    trianglesLen += 3
    return t
  }

  internal companion object {
    /** `2^-52`, the tolerance below which two points count as the same one. */
    val EPSILON: Double = 2.0.pow(-52)

    /** Monotonic in the real angle, without the cost of `atan2`. */
    fun pseudoAngle(dx: Double, dy: Double): Double {
      val p = dx / (abs(dx) + abs(dy))
      return (if (dy > 0) 3 - p else 1 + p) / 4
    }

    fun dist(ax: Double, ay: Double, bx: Double, by: Double): Double {
      val dx = ax - bx
      val dy = ay - by
      return dx * dx + dy * dy
    }

    /** Is `p` inside the circle through `a`, `b`, `c`? The Delaunay condition itself. */
    @Suppress("LongParameterList")
    fun inCircle(
      ax: Double,
      ay: Double,
      bx: Double,
      by: Double,
      cx: Double,
      cy: Double,
      px: Double,
      py: Double,
    ): Boolean {
      val dx = ax - px
      val dy = ay - py
      val ex = bx - px
      val ey = by - py
      val fx = cx - px
      val fy = cy - py

      val ap = dx * dx + dy * dy
      val bp = ex * ex + ey * ey
      val cp = fx * fx + fy * fy

      return dx * (ey * cp - bp * fy) - dy * (ex * cp - bp * fx) + ap * (ex * fy - ey * fx) < 0
    }

    @Suppress("LongParameterList")
    fun circumradius(
      ax: Double,
      ay: Double,
      bx: Double,
      by: Double,
      cx: Double,
      cy: Double,
    ): Double {
      val dx = bx - ax
      val dy = by - ay
      val ex = cx - ax
      val ey = cy - ay
      val bl = dx * dx + dy * dy
      val cl = ex * ex + ey * ey
      val d = 0.5 / (dx * ey - dy * ex)
      val x = (ey * bl - dy * cl) * d
      val y = (dx * cl - ex * bl) * d
      return x * x + y * y
    }

    @Suppress("LongParameterList")
    fun circumcenter(
      ax: Double,
      ay: Double,
      bx: Double,
      by: Double,
      cx: Double,
      cy: Double,
    ): DoubleArray {
      val dx = bx - ax
      val dy = by - ay
      val ex = cx - ax
      val ey = cy - ay
      val bl = dx * dx + dy * dy
      val cl = ex * ex + ey * ey
      val d = 0.5 / (dx * ey - dy * ex)
      return doubleArrayOf(ax + (ey * bl - dy * cl) * d, ay + (dx * cl - ex * bl) * d)
    }

    /**
     * Upstream's quicksort, which is part of the answer rather than an implementation detail.
     *
     * A Delaunay triangulation is not unique when four points are cocircular, so the order the
     * points are inserted in decides which of the possible triangulations comes out. Median of
     * three, insertion sort below twenty-one elements, and the same partition loop.
     */
    fun quicksort(ids: IntArray, dists: DoubleArray, left: Int, right: Int) {
      if (right - left <= 20) {
        for (i in left + 1..right) {
          val temp = ids[i]
          val tempDist = dists[temp]
          var j = i - 1
          while (j >= left && dists[ids[j]] > tempDist) {
            ids[j + 1] = ids[j]
            j--
          }
          ids[j + 1] = temp
        }
      } else {
        val median = (left + right) shr 1
        var i = left + 1
        var j = right
        swap(ids, median, i)
        if (dists[ids[left]] > dists[ids[right]]) swap(ids, left, right)
        if (dists[ids[i]] > dists[ids[right]]) swap(ids, i, right)
        if (dists[ids[left]] > dists[ids[i]]) swap(ids, left, i)

        val temp = ids[i]
        val tempDist = dists[temp]
        while (true) {
          do i++ while (dists[ids[i]] < tempDist)
          do j-- while (dists[ids[j]] > tempDist)
          if (j < i) break
          swap(ids, i, j)
        }
        ids[left + 1] = ids[j]
        ids[j] = temp

        if (right - i + 1 >= j - left) {
          quicksort(ids, dists, i, right)
          quicksort(ids, dists, left, j - 1)
        } else {
          quicksort(ids, dists, left, j - 1)
          quicksort(ids, dists, i, right)
        }
      }
    }

    private fun swap(array: IntArray, i: Int, j: Int) {
      val held = array[i]
      array[i] = array[j]
      array[j] = held
    }
  }
}
