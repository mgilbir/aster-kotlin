package dev.aster.vega.dataflow.voronoi

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin

/**
 * The Delaunay triangulation plus the index a Voronoi diagram needs, `d3-delaunay`'s `Delaunay`.
 *
 * On top of the triangulation it keeps, for each point, *one* half-edge arriving at it — preferring
 * an exterior one where there is a choice, which is what lets a cell on the hull be walked
 * outwards.
 *
 * The jitter is not a workaround. A set of exactly collinear points has no triangles at all, so
 * there are no circumcentres and no cells; upstream perturbs every point by a ten-millionth of the
 * span and triangulates again. Reproducing the perturbation exactly — `sin(x + y)` and `cos(x -
 * y)`, not a random nudge — is what keeps such a diagram comparable.
 */
internal class Delaunay(points: DoubleArray) {

  var coords: DoubleArray = points
    private set

  private var delaunator = Delaunator(coords)

  var triangles: IntArray = IntArray(0)
    private set

  var halfedges: IntArray = IntArray(0)
    private set

  var hull: IntArray = IntArray(0)
    private set

  /** One incoming half-edge per point, exterior for preference; `-1` for a coincident point. */
  val inedges = IntArray(coords.size / 2)

  private val hullIndex = IntArray(coords.size / 2)

  init {
    build()
  }

  private fun build() {
    if (delaunator.hull.size > 2 && isCollinear(delaunator)) {
      val order =
        (0 until coords.size / 2).sortedWith(compareBy({ coords[2 * it] }, { coords[2 * it + 1] }))
      val first = order.first()
      val last = order.last()
      val r =
        1e-8 *
          hypot(
            coords[2 * last + 1] - coords[2 * first + 1],
            coords[2 * last] - coords[2 * first],
          )
      for (i in 0 until coords.size / 2) {
        val x = coords[2 * i]
        val y = coords[2 * i + 1]
        coords[2 * i] = x + sin(x + y) * r
        coords[2 * i + 1] = y + cos(x - y) * r
      }
      delaunator = Delaunator(coords)
    }

    halfedges = delaunator.halfedges
    hull = delaunator.hull
    triangles = delaunator.triangles
    inedges.fill(-1)
    hullIndex.fill(-1)

    for (e in halfedges.indices) {
      val p = triangles[if (e % 3 == 2) e - 2 else e + 1]
      if (halfedges[e] == -1 || inedges[p] == -1) inedges[p] = e
    }
    for (i in hull.indices) hullIndex[hull[i]] = i

    // One or two distinct points: there is no triangle, and the single cell is the whole box.
    if (hull.size in 1..2) {
      triangles = IntArray(3) { -1 }
      halfedges = IntArray(3) { -1 }
      triangles[0] = hull[0]
      inedges[hull[0]] = 1
      if (hull.size == 2) {
        inedges[hull[1]] = 0
        triangles[1] = hull[1]
        triangles[2] = hull[1]
      }
    }
  }

  /** Which point is nearest `(x, y)`, walked from `i` through the triangulation. */
  fun find(x: Double, y: Double, from: Int = 0): Int {
    if (x.isNaN() || y.isNaN()) return -1
    var i = from
    var c: Int
    while (true) {
      c = step(i, x, y)
      if (c < 0 || c == i || c == from) break
      i = c
    }
    return c
  }

  /** One step downhill towards `(x, y)`: the nearest of `i`'s neighbours, or `i` itself. */
  private fun step(i: Int, x: Double, y: Double): Int {
    if (inedges[i] == -1 || coords.isEmpty()) return (i + 1) % (coords.size shr 1)
    var c = i
    var dc = square(x - coords[i * 2]) + square(y - coords[i * 2 + 1])
    val e0 = inedges[i]
    var e = e0
    do {
      val t = triangles[e]
      val dt = square(x - coords[t * 2]) + square(y - coords[t * 2 + 1])
      if (dt < dc) {
        dc = dt
        c = t
      }
      e = if (e % 3 == 2) e - 2 else e + 1
      if (triangles[e] != i) break // a triangulation this walk cannot trust
      e = halfedges[e]
      if (e == -1) {
        // Off the hull: the next hull point is the only neighbour left to consider.
        val next = hull[(hullIndex[i] + 1) % hull.size]
        if (next != t) {
          if (square(x - coords[next * 2]) + square(y - coords[next * 2 + 1]) < dc) return next
        }
        break
      }
    } while (e != e0)
    return c
  }

  private fun square(v: Double): Double = v * v

  private companion object {
    /**
     * Every triangle has zero area, so the points lie on one line and there is nothing to divide.
     */
    fun isCollinear(d: Delaunator): Boolean {
      val triangles = d.triangles
      val coords = d.coordsView
      var i = 0
      while (i < triangles.size) {
        val a = 2 * triangles[i]
        val b = 2 * triangles[i + 1]
        val c = 2 * triangles[i + 2]
        val cross =
          (coords[c] - coords[a]) * (coords[b + 1] - coords[a + 1]) -
            (coords[b] - coords[a]) * (coords[c + 1] - coords[a + 1])
        if (cross > 1e-10) return false
        i += 3
      }
      return true
    }
  }
}

/**
 * The Voronoi diagram of a triangulation, clipped to a rectangle.
 *
 * A cell is the polygon of circumcentres around one point, walked through the half-edges that
 * arrive at it. Two things make it more than that.
 *
 * A cell on the **hull** is unbounded, so it has no closing circumcentre. Upstream stores an
 * outward-pointing vector per hull point and projects the open ends onto the clip rectangle.
 *
 * And clipping a polygon to a rectangle is not clipping each edge independently: where a cell
 * leaves one side of the box and re-enters through another, the *corner* between them belongs to
 * the cell — but only if the cell actually contains that corner, which is a nearest-point query.
 * That is what `_edge` walks round the box for, and getting it wrong leaves a bite out of a cell
 * rather than an obviously broken shape.
 *
 * Ported from `d3-delaunay/src/voronoi.js`.
 */
internal class VoronoiDiagram(
  private val delaunay: Delaunay,
  private val xmin: Double,
  private val ymin: Double,
  private val xmax: Double,
  private val ymax: Double,
) {
  private val circumcenters: DoubleArray
  private val vectors = DoubleArray(delaunay.coords.size * 2)

  init {
    val points = delaunay.coords
    val triangles = delaunay.triangles
    circumcenters = DoubleArray(triangles.size / 3 * 2)

    var barycentreX = Double.NaN
    var barycentreY = Double.NaN
    var i = 0
    var j = 0
    while (i < triangles.size) {
      val t1 = triangles[i] * 2
      val t2 = triangles[i + 1] * 2
      val t3 = triangles[i + 2] * 2
      val x1 = points[t1]
      val y1 = points[t1 + 1]
      val x2 = points[t2]
      val y2 = points[t2 + 1]
      val x3 = points[t3]
      val y3 = points[t3 + 1]

      val dx = x2 - x1
      val dy = y2 - y1
      val ex = x3 - x1
      val ey = y3 - y1
      val ab = (dx * ey - dy * ex) * 2

      val x: Double
      val y: Double
      if (abs(ab) < 1e-9) {
        // A degenerate triangle's circumcentre is at infinity. Upstream sends it a billion units
        // away, perpendicular to the edge and *away* from the hull's centre of mass, so the cell
        // still closes on the right side of the diagram.
        if (barycentreX.isNaN()) {
          var bx = 0.0
          var by = 0.0
          for (h in delaunay.hull) {
            bx += points[h * 2]
            by += points[h * 2 + 1]
          }
          barycentreX = bx / delaunay.hull.size
          barycentreY = by / delaunay.hull.size
        }
        val a = 1e9 * sign((barycentreX - x1) * ey - (barycentreY - y1) * ex)
        x = (x1 + x3) / 2 - a * ey
        y = (y1 + y3) / 2 + a * ex
      } else {
        val d = 1 / ab
        val bl = dx * dx + dy * dy
        val cl = ex * ex + ey * ey
        x = x1 + (ey * bl - dy * cl) * d
        y = y1 + (dx * cl - ex * bl) * d
      }
      circumcenters[j] = x
      circumcenters[j + 1] = y
      i += 3
      j += 2
    }

    // The outward normal of each hull edge, shared by the two points it joins.
    val hull = delaunay.hull
    if (hull.isNotEmpty()) {
      var h = hull[hull.size - 1]
      var p1 = h * 4
      var x1 = points[2 * h]
      var y1 = points[2 * h + 1]
      for (index in hull.indices) {
        h = hull[index]
        val p0 = p1
        val x0 = x1
        val y0 = y1
        p1 = h * 4
        x1 = points[2 * h]
        y1 = points[2 * h + 1]
        vectors[p0 + 2] = y0 - y1
        vectors[p1] = y0 - y1
        vectors[p0 + 3] = x1 - x0
        vectors[p1 + 1] = x1 - x0
      }
    }
  }

  /** One cell as a flat `[x, y, x, y, …]` ring, or null where the point has no cell. */
  fun cellPolygon(i: Int): DoubleArray? {
    val points = clip(i) ?: return null
    if (points.isEmpty()) return null
    // The renderer drops a repeated closing point and any vertex equal to the one before it.
    val out = ArrayList<Double>(points.size + 2)
    out.add(points[0])
    out.add(points[1])
    var n = points.size
    while (n > 1 && points[0] == points[n - 2] && points[1] == points[n - 1]) n -= 2
    var k = 2
    while (k < n) {
      if (points[k] != points[k - 2] || points[k + 1] != points[k - 1]) {
        out.add(points[k])
        out.add(points[k + 1])
      }
      k += 2
    }
    // `closePath` returns to the first point, which a polygon records explicitly.
    out.add(points[0])
    out.add(points[1])
    return out.toDoubleArray()
  }

  /** The circumcentres around point `i`, walked through the half-edges arriving at it. */
  private fun cell(i: Int): DoubleArray? {
    val e0 = delaunay.inedges[i]
    if (e0 == -1) return null
    val out = ArrayList<Double>()
    var e = e0
    do {
      val t = e / 3
      out.add(circumcenters[t * 2])
      out.add(circumcenters[t * 2 + 1])
      e = if (e % 3 == 2) e - 2 else e + 1
      if (delaunay.triangles[e] != i) break
      e = delaunay.halfedges[e]
    } while (e != e0 && e != -1)
    return out.toDoubleArray()
  }

  private fun clip(i: Int): DoubleArray? {
    // One point and nothing else: its cell is the whole box.
    if (i == 0 && delaunay.hull.size == 1) {
      return doubleArrayOf(xmax, ymin, xmax, ymax, xmin, ymax, xmin, ymin)
    }
    val points = cell(i) ?: return null
    val v = i * 4
    val clipped =
      if (vectors[v] != 0.0 || vectors[v + 1] != 0.0) {
        clipInfinite(i, points, vectors[v], vectors[v + 1], vectors[v + 2], vectors[v + 3])
      } else {
        clipFinite(i, points)
      }
    return simplify(clipped)
  }

  @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LongMethod")
  private fun clipFinite(i: Int, points: DoubleArray): DoubleArray? {
    val n = points.size
    var p: MutableList<Double>? = null
    var x1 = points[n - 2]
    var y1 = points[n - 1]
    var c1 = regionCode(x1, y1)
    var e1 = 0
    var e0: Int
    var j = 0
    while (j < n) {
      val x0 = x1
      val y0 = y1
      x1 = points[j]
      y1 = points[j + 1]
      val c0 = c1
      c1 = regionCode(x1, y1)
      if (c0 == 0 && c1 == 0) {
        e1 = 0
        if (p != null) {
          p.add(x1)
          p.add(y1)
        } else {
          p = mutableListOf(x1, y1)
        }
      } else {
        val segment: DoubleArray?
        val sx0: Double
        val sy0: Double
        val sx1: Double
        val sy1: Double
        if (c0 == 0) {
          segment = clipSegment(x0, y0, x1, y1, c0, c1)
          if (segment == null) {
            j += 2
            continue
          }
          sx0 = segment[0]
          sy0 = segment[1]
          sx1 = segment[2]
          sy1 = segment[3]
        } else {
          segment = clipSegment(x1, y1, x0, y0, c1, c0)
          if (segment == null) {
            j += 2
            continue
          }
          sx1 = segment[0]
          sy1 = segment[1]
          sx0 = segment[2]
          sy0 = segment[3]
          e0 = e1
          e1 = edgeCode(sx0, sy0)
          if (e0 != 0 && e1 != 0) edge(i, e0, e1, p, p?.size ?: 0)
          if (p != null) {
            p.add(sx0)
            p.add(sy0)
          } else {
            p = mutableListOf(sx0, sy0)
          }
        }
        e0 = e1
        e1 = edgeCode(sx1, sy1)
        if (e0 != 0 && e1 != 0) edge(i, e0, e1, p, p?.size ?: 0)
        if (p != null) {
          p.add(sx1)
          p.add(sy1)
        } else {
          p = mutableListOf(sx1, sy1)
        }
      }
      j += 2
    }
    if (p != null) {
      e0 = e1
      e1 = edgeCode(p[0], p[1])
      if (e0 != 0 && e1 != 0) edge(i, e0, e1, p, p.size)
      return p.toDoubleArray()
    }
    // Nothing of the cell is inside the box, but the box may be inside the cell.
    if (contains(i, (xmin + xmax) / 2, (ymin + ymax) / 2)) {
      return doubleArrayOf(xmax, ymin, xmax, ymax, xmin, ymax, xmin, ymin)
    }
    return null
  }

  @Suppress("LongParameterList")
  private fun clipSegment(
    x0In: Double,
    y0In: Double,
    x1In: Double,
    y1In: Double,
    c0In: Int,
    c1In: Int,
  ): DoubleArray? {
    // Always considered in the same order, so the arithmetic is the same whichever end came first.
    val flip = c0In < c1In
    var x0 = if (flip) x1In else x0In
    var y0 = if (flip) y1In else y0In
    var x1 = if (flip) x0In else x1In
    var y1 = if (flip) y0In else y1In
    var c0 = if (flip) c1In else c0In
    var c1 = if (flip) c0In else c1In
    while (true) {
      if (c0 == 0 && c1 == 0) {
        return if (flip) doubleArrayOf(x1, y1, x0, y0) else doubleArrayOf(x0, y0, x1, y1)
      }
      if (c0 and c1 != 0) return null
      val c = if (c0 != 0) c0 else c1
      val x: Double
      val y: Double
      when {
        c and 0b1000 != 0 -> {
          x = x0 + (x1 - x0) * (ymax - y0) / (y1 - y0)
          y = ymax
        }
        c and 0b0100 != 0 -> {
          x = x0 + (x1 - x0) * (ymin - y0) / (y1 - y0)
          y = ymin
        }
        c and 0b0010 != 0 -> {
          y = y0 + (y1 - y0) * (xmax - x0) / (x1 - x0)
          x = xmax
        }
        else -> {
          y = y0 + (y1 - y0) * (xmin - x0) / (x1 - x0)
          x = xmin
        }
      }
      if (c0 != 0) {
        x0 = x
        y0 = y
        c0 = regionCode(x0, y0)
      } else {
        x1 = x
        y1 = y
        c1 = regionCode(x1, y1)
      }
    }
  }

  @Suppress("LongParameterList")
  private fun clipInfinite(
    i: Int,
    points: DoubleArray,
    vx0: Double,
    vy0: Double,
    vxn: Double,
    vyn: Double,
  ): DoubleArray? {
    val p = ArrayList<Double>(points.size + 4)
    for (value in points) p.add(value)
    project(p[0], p[1], vx0, vy0)?.let {
      p.add(0, it[1])
      p.add(0, it[0])
    }
    project(p[p.size - 2], p[p.size - 1], vxn, vyn)?.let {
      p.add(it[0])
      p.add(it[1])
    }
    var clipped = clipFinite(i, p.toDoubleArray())
    if (clipped != null) {
      val list = clipped.toMutableList()
      var n = list.size
      var c1 = edgeCode(list[n - 2], list[n - 1])
      var j = 0
      while (j < n) {
        val c0 = c1
        c1 = edgeCode(list[j], list[j + 1])
        if (c0 != 0 && c1 != 0) {
          j = edge(i, c0, c1, list, j)
          n = list.size
        }
        j += 2
      }
      clipped = list.toDoubleArray()
    } else if (contains(i, (xmin + xmax) / 2, (ymin + ymax) / 2)) {
      clipped = doubleArrayOf(xmin, ymin, xmax, ymin, xmax, ymax, xmin, ymax)
    }
    return clipped
  }

  /**
   * Walks the box's corners between two edges, inserting the ones this cell contains.
   *
   * The cell left the box on one side and came back on another, so the corners between belong to it
   * — and only if the cell really is the nearest one to each corner, which the nearest-point query
   * decides. Upstream's state machine walks anticlockwise through eight codes: four sides and four
   * corners.
   */
  private fun edge(i: Int, e0In: Int, e1: Int, p: MutableList<Double>?, jIn: Int): Int {
    var e0 = e0In
    var j = jIn
    if (p == null) return j
    while (e0 != e1) {
      var x = Double.NaN
      var y = Double.NaN
      when (e0) {
        0b0101 -> {
          e0 = 0b0100
          continue
        }
        0b0100 -> {
          e0 = 0b0110
          x = xmax
          y = ymin
        }
        0b0110 -> {
          e0 = 0b0010
          continue
        }
        0b0010 -> {
          e0 = 0b1010
          x = xmax
          y = ymax
        }
        0b1010 -> {
          e0 = 0b1000
          continue
        }
        0b1000 -> {
          e0 = 0b1001
          x = xmin
          y = ymax
        }
        0b1001 -> {
          e0 = 0b0001
          continue
        }
        0b0001 -> {
          e0 = 0b0101
          x = xmin
          y = ymin
        }
        else -> return j
      }
      // Out of bounds counts as "not already there", which is upstream's implicit check.
      val hereX = p.getOrNull(j)
      val hereY = p.getOrNull(j + 1)
      if ((hereX != x || hereY != y) && contains(i, x, y)) {
        p.add(j, y)
        p.add(j, x)
        j += 2
      }
    }
    return j
  }

  /** Where an open cell edge meets the box, following its outward vector. */
  private fun project(x0: Double, y0: Double, vx: Double, vy: Double): DoubleArray? {
    var t = Double.POSITIVE_INFINITY
    var x = Double.NaN
    var y = Double.NaN
    if (vy < 0) {
      if (y0 <= ymin) return null
      val c = (ymin - y0) / vy
      if (c < t) {
        t = c
        y = ymin
        x = x0 + t * vx
      }
    } else if (vy > 0) {
      if (y0 >= ymax) return null
      val c = (ymax - y0) / vy
      if (c < t) {
        t = c
        y = ymax
        x = x0 + t * vx
      }
    }
    if (vx > 0) {
      if (x0 >= xmax) return null
      val c = (xmax - x0) / vx
      if (c < t) {
        t = c
        x = xmax
        y = y0 + t * vy
      }
    } else if (vx < 0) {
      if (x0 <= xmin) return null
      val c = (xmin - x0) / vx
      if (c < t) {
        t = c
        x = xmin
        y = y0 + t * vy
      }
    }
    return doubleArrayOf(x, y)
  }

  /** Is this the nearest point to `(x, y)`? What decides whether a corner belongs to a cell. */
  private fun contains(i: Int, x: Double, y: Double): Boolean {
    if (x.isNaN() || y.isNaN()) return false
    return delaunay.find(x, y, i) == i
  }

  /** Which side of the box a point lies **on**, exactly; zero for one strictly inside. */
  private fun edgeCode(x: Double, y: Double): Int =
    (if (x == xmin) 0b0001 else if (x == xmax) 0b0010 else 0) or
      (if (y == ymin) 0b0100 else if (y == ymax) 0b1000 else 0)

  /** Which sides of the box a point lies outside of; zero for one inside. */
  private fun regionCode(x: Double, y: Double): Int =
    (if (x < xmin) 0b0001 else if (x > xmax) 0b0010 else 0) or
      (if (y < ymin) 0b0100 else if (y > ymax) 0b1000 else 0)

  /** Drops a vertex that lies between two others on the same horizontal or vertical run. */
  private fun simplify(input: DoubleArray?): DoubleArray? {
    if (input == null || input.size <= 4) return input
    val p = input.toMutableList()
    var i = 0
    while (i < p.size) {
      val j = (i + 2) % p.size
      val k = (i + 4) % p.size
      if ((p[i] == p[j] && p[j] == p[k]) || (p[i + 1] == p[j + 1] && p[j + 1] == p[k + 1])) {
        p.removeAt(j)
        p.removeAt(j)
        i -= 2
      }
      i += 2
    }
    return if (p.isEmpty()) null else p.toDoubleArray()
  }
}
