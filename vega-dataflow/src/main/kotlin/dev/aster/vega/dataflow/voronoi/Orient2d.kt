package dev.aster.vega.dataflow.voronoi

import kotlin.math.abs

/**
 * Which side of a line a point is on, computed **exactly**.
 *
 * A Delaunay triangulation is a sequence of decisions of the form "is this point left of that
 * edge?", and each decision decides the shape of everything after it. Answering one of them wrongly
 * — which an ordinary cross product does whenever three points are nearly collinear, and airports
 * along a coastline are nearly collinear — does not perturb the result slightly. It produces a
 * different triangulation, or none: the incremental algorithm walks into an inconsistent hull and
 * loops.
 *
 * So the cross product is computed with a floating-point *expansion*: a list of doubles whose exact
 * sum is the true value, extended only as far as the answer's sign is still in doubt. Shewchuk's
 * adaptive predicate, ported from `robust-predicates` line for line, error bounds included. Nothing
 * here can be simplified — the whole point is which order the roundings happen in.
 */
internal object Orient2d {

  private const val EPSILON = 1.1102230246251565e-16
  private const val SPLITTER = 134217729.0
  private val RESULT_ERRBOUND = (3 + 8 * EPSILON) * EPSILON
  private val CCW_ERRBOUND_A = (3 + 16 * EPSILON) * EPSILON
  private val CCW_ERRBOUND_B = (2 + 12 * EPSILON) * EPSILON
  private val CCW_ERRBOUND_C = (9 + 64 * EPSILON) * EPSILON * EPSILON

  /**
   * Positive when `c` is left of the line `a → b`, negative when right, zero when collinear.
   *
   * The fast path is the whole point: for points in general position the ordinary cross product's
   * error bound already settles the sign, and only the doubtful cases pay for the expansion.
   */
  fun orient(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Double {
    val detLeft = (ay - cy) * (bx - cx)
    val detRight = (ax - cx) * (by - cy)
    val det = detLeft - detRight
    val detSum = abs(detLeft + detRight)
    if (abs(det) >= CCW_ERRBOUND_A * detSum) return det
    return -adapt(ax, ay, bx, by, cx, cy, detSum)
  }

  /** A scratch buffer per expansion, reused across calls as upstream's module-level arrays are. */
  private class Buffers {
    val b = DoubleArray(4)
    val c1 = DoubleArray(8)
    val c2 = DoubleArray(12)
    val d = DoubleArray(16)
    val u = DoubleArray(4)
  }

  private val buffers = Buffers()

  @Suppress("LongMethod", "LongParameterList")
  private fun adapt(
    ax: Double,
    ay: Double,
    bx: Double,
    by: Double,
    cx: Double,
    cy: Double,
    detSum: Double,
  ): Double {
    val b = buffers.b
    val u = buffers.u

    val acx = ax - cx
    val bcx = bx - cx
    val acy = ay - cy
    val bcy = by - cy

    twoProduct(acx, bcy, acy, bcx, b)
    var det = estimate(4, b)
    var errbound = CCW_ERRBOUND_B * detSum
    if (det >= errbound || -det >= errbound) return det

    // The parts of each subtraction that the subtraction itself threw away.
    var bvirt = ax - acx
    val acxtail = ax - (acx + bvirt) + (bvirt - cx)
    bvirt = bx - bcx
    val bcxtail = bx - (bcx + bvirt) + (bvirt - cx)
    bvirt = ay - acy
    val acytail = ay - (acy + bvirt) + (bvirt - cy)
    bvirt = by - bcy
    val bcytail = by - (bcy + bvirt) + (bvirt - cy)

    if (acxtail == 0.0 && acytail == 0.0 && bcxtail == 0.0 && bcytail == 0.0) return det

    errbound = CCW_ERRBOUND_C * detSum + RESULT_ERRBOUND * abs(det)
    det += (acx * bcytail + bcy * acxtail) - (acy * bcxtail + bcx * acytail)
    if (det >= errbound || -det >= errbound) return det

    twoProduct(acxtail, bcy, acytail, bcx, u)
    val c1Length = sum(4, b, 4, u, buffers.c1)

    twoProduct(acx, bcytail, acy, bcxtail, u)
    val c2Length = sum(c1Length, buffers.c1, 4, u, buffers.c2)

    twoProduct(acxtail, bcytail, acytail, bcxtail, u)
    val dLength = sum(c2Length, buffers.c2, 4, u, buffers.d)

    // The last component of an expansion is its dominant term, and its sign is the answer.
    return buffers.d[dLength - 1]
  }

  /**
   * `a1 * a2 - b1 * b2` as a four-component expansion, exactly.
   *
   * Each product is split into a high and a low half so that the multiplication is exact in two
   * doubles; the difference of two such pairs is then four doubles whose sum is the true value.
   */
  private fun twoProduct(a1: Double, a2: Double, b1: Double, b2: Double, out: DoubleArray) {
    val s1 = a1 * a2
    var c = SPLITTER * a1
    var ahi = c - (c - a1)
    var alo = a1 - ahi
    c = SPLITTER * a2
    var bhi = c - (c - a2)
    var blo = a2 - bhi
    val s0 = alo * blo - (s1 - ahi * bhi - alo * bhi - ahi * blo)

    val t1 = b1 * b2
    c = SPLITTER * b1
    ahi = c - (c - b1)
    alo = b1 - ahi
    c = SPLITTER * b2
    bhi = c - (c - b2)
    blo = b2 - bhi
    val t0 = alo * blo - (t1 - ahi * bhi - alo * bhi - ahi * blo)

    var i = s0 - t0
    var bvirt = s0 - i
    out[0] = s0 - (i + bvirt) + (bvirt - t0)
    val j = s1 + i
    bvirt = j - s1
    val zero = s1 - (j - bvirt) + (i - bvirt)
    i = zero - t1
    bvirt = zero - i
    out[1] = zero - (i + bvirt) + (bvirt - t1)
    val u3 = j + i
    bvirt = u3 - j
    out[2] = j - (u3 - bvirt) + (i - bvirt)
    out[3] = u3
  }

  /**
   * Shewchuk's `fast_expansion_sum_zeroelim`: two expansions merged into one, exactly.
   *
   * Both inputs are in increasing order of magnitude, so this is a merge that carries the rounding
   * error of each addition forward into the next component rather than losing it.
   */
  private fun sum(
    elen: Int,
    e: DoubleArray,
    flen: Int,
    f: DoubleArray,
    h: DoubleArray,
  ): Int {
    // Reading one past the end is not an error here, it is the algorithm: the merge advances a
    // cursor and only *then* asks whether it is still in range, and JavaScript answers `undefined`
    // for the read that is never used. Kotlin throws instead, so the read is guarded — a set of
    // **collinear** points is what reaches it, which is exactly what a selection projected onto one
    // channel produces, every cell's other coordinate being zero.
    fun at(values: DoubleArray, index: Int): Double =
      if (index < values.size) values[index] else 0.0
    var enow = e[0]
    var fnow = f[0]
    var eindex = 0
    var findex = 0
    var q: Double
    if ((fnow > enow) == (fnow > -enow)) {
      q = enow
      enow = at(e, ++eindex)
    } else {
      q = fnow
      fnow = at(f, ++findex)
    }
    var hindex = 0
    var hh: Double
    var qnew: Double
    var bvirt: Double
    if (eindex < elen && findex < flen) {
      if ((fnow > enow) == (fnow > -enow)) {
        qnew = enow + q
        hh = q - (qnew - enow)
        enow = at(e, ++eindex)
      } else {
        qnew = fnow + q
        hh = q - (qnew - fnow)
        fnow = at(f, ++findex)
      }
      q = qnew
      if (hh != 0.0) h[hindex++] = hh
      while (eindex < elen && findex < flen) {
        if ((fnow > enow) == (fnow > -enow)) {
          qnew = q + enow
          bvirt = qnew - q
          hh = q - (qnew - bvirt) + (enow - bvirt)
          enow = at(e, ++eindex)
        } else {
          qnew = q + fnow
          bvirt = qnew - q
          hh = q - (qnew - bvirt) + (fnow - bvirt)
          fnow = at(f, ++findex)
        }
        q = qnew
        if (hh != 0.0) h[hindex++] = hh
      }
    }
    while (eindex < elen) {
      qnew = q + enow
      bvirt = qnew - q
      hh = q - (qnew - bvirt) + (enow - bvirt)
      eindex++
      enow = if (eindex < e.size) e[eindex] else 0.0
      q = qnew
      if (hh != 0.0) h[hindex++] = hh
    }
    while (findex < flen) {
      qnew = q + fnow
      bvirt = qnew - q
      hh = q - (qnew - bvirt) + (fnow - bvirt)
      findex++
      fnow = if (findex < f.size) f[findex] else 0.0
      q = qnew
      if (hh != 0.0) h[hindex++] = hh
    }
    if (q != 0.0 || hindex == 0) h[hindex++] = q
    return hindex
  }

  /** An expansion's value in one double, which is its ordinary sum. */
  private fun estimate(elen: Int, e: DoubleArray): Double {
    var q = e[0]
    for (i in 1 until elen) q += e[i]
    return q
  }
}
