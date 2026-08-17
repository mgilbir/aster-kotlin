package dev.aster.vega.dataflow.transform

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * The regression fits, ported one for one from `vega-statistics/src/regression/`.
 *
 * Two things about these are worth saying once rather than at each method.
 *
 * **The arithmetic is copied, not derived.** Every fit accumulates its moments as running means —
 * `X += (x - X) / n` — rather than as sums divided at the end, and the non-linear ones work in
 * mean-centred coordinates and transform the coefficients back afterwards. Both choices are about
 * conditioning, and both change the answer in the last two bits of a double. That is enough to miss
 * a reference vector, so the order of operations here is deliberate and should not be tidied.
 *
 * **A row is dropped only when it is not a number**, which is not the same as not being finite:
 * upstream's filter is `(u = +u) >= u`, false for NaN and true for an infinity. So an infinite
 * value reaches the fit and poisons it, rather than being quietly skipped — and a chart drawn from
 * it shows that, which is the point.
 */
internal object RegressionFits {

  /** A fitted model: its coefficients in the form upstream reports, and how to evaluate it. */
  internal class Model(
    val coef: List<Double>,
    val rSquared: Double,
    val predict: (Double) -> Double,
  )

  /** How many parameters a method spends, which is how many points a group needs to exceed. */
  internal fun degreesOfFreedom(method: String, order: Int): Int =
    when (method) {
      "poly" -> order
      "quad" -> 2
      else -> 1
    }

  internal fun fit(method: String, points: List<DoubleArray>, order: Int): Model? =
    when (method) {
      "constant" -> constant(points)
      "linear" -> linear(points)
      "log" -> log(points)
      "exp" -> exponential(points)
      "pow" -> power(points)
      "quad" -> quadratic(points)
      "poly" -> polynomial(points, order)
      else -> null
    }

  /** Ordinary least squares from four moments, with upstream's guard against a vertical fit. */
  private fun ols(uX: Double, uY: Double, uXY: Double, uX2: Double): DoubleArray {
    val delta = uX2 - uX * uX
    val slope = if (abs(delta) < 1e-24) 0.0 else (uXY - uX * uY) / delta
    return doubleArrayOf(uY - slope * uX, slope)
  }

  private fun rSquared(points: List<DoubleArray>, uY: Double, predict: (Double) -> Double): Double {
    var sse = 0.0
    var sst = 0.0
    for (p in points) {
      val e = p[1] - predict(p[0])
      val t = p[1] - uY
      sse += e * e
      sst += t * t
    }
    return 1.0 - sse / sst
  }

  /** The mean-centred coordinates the non-linear fits work in, with the two means. */
  private class Centred(val x: DoubleArray, val y: DoubleArray, val ux: Double, val uy: Double)

  private fun centre(points: List<DoubleArray>, sort: Boolean = false): Centred {
    val rows = if (sort) points.sortedBy { it[0] } else points
    val n = rows.size
    val x = DoubleArray(n)
    val y = DoubleArray(n)
    var ux = 0.0
    var uy = 0.0
    for ((i, p) in rows.withIndex()) {
      x[i] = p[0]
      y[i] = p[1]
      ux += (p[0] - ux) / (i + 1)
      uy += (p[1] - uy) / (i + 1)
    }
    for (i in 0 until n) {
      x[i] -= ux
      y[i] -= uy
    }
    return Centred(x, y, ux, uy)
  }

  /** The mean of y, drawn as a horizontal line. `rSquared` is reported as 0, not computed. */
  private fun constant(points: List<DoubleArray>): Model {
    var mean = 0.0
    var n = 0
    for (p in points) mean += (p[1] - mean) / ++n
    return Model(listOf(mean), 0.0, { mean })
  }

  private fun linear(points: List<DoubleArray>): Model {
    var mx = 0.0
    var my = 0.0
    var mxy = 0.0
    var mx2 = 0.0
    var n = 0
    for (p in points) {
      n++
      mx += (p[0] - mx) / n
      my += (p[1] - my) / n
      mxy += (p[0] * p[1] - mxy) / n
      mx2 += (p[0] * p[0] - mx2) / n
    }
    val coef = ols(mx, my, mxy, mx2)
    val predict = { x: Double -> coef[0] + coef[1] * x }
    return Model(coef.toList(), rSquared(points, my, predict), predict)
  }

  /** `y = a + b·ln(x)`: the linear fit with x taken through a logarithm first. */
  private fun log(points: List<DoubleArray>): Model {
    var mx = 0.0
    var my = 0.0
    var mxy = 0.0
    var mx2 = 0.0
    var n = 0
    for (p in points) {
      n++
      val dx = ln(p[0])
      mx += (dx - mx) / n
      my += (p[1] - my) / n
      mxy += (dx * p[1] - mxy) / n
      mx2 += (dx * dx - mx2) / n
    }
    val coef = ols(mx, my, mxy, mx2)
    val predict = { x: Double -> coef[0] + coef[1] * ln(x) }
    return Model(coef.toList(), rSquared(points, my, predict), predict)
  }

  /**
   * `y = a·e^(bx)`, fitted by **weighting each residual by y** rather than by taking logs of y.
   *
   * Taking logs would be simpler and is what most implementations do, but it minimises the error of
   * the logarithm, which weights small y far more heavily than large ones. Upstream's form fits the
   * curve to the data as drawn.
   */
  private fun exponential(points: List<DoubleArray>): Model {
    val c = centre(points)
    var yl = 0.0
    var xy = 0.0
    var xyl = 0.0
    var x2y = 0.0
    var n = 0
    for (p in points) {
      val dx = c.x[n++]
      val ly = ln(p[1])
      val product = dx * p[1]
      yl += (p[1] * ly - yl) / n
      xy += (product - xy) / n
      xyl += (product * ly - xyl) / n
      x2y += (dx * product - x2y) / n
    }
    val fitted = ols(xy / c.uy, yl / c.uy, xyl / c.uy, x2y / c.uy)
    val c0 = fitted[0]
    val c1 = fitted[1]
    val predict = { x: Double -> exp(c0 + c1 * (x - c.ux)) }
    return Model(
      listOf(exp(c0 - c1 * c.ux), c1),
      rSquared(points, c.uy, predict),
      predict,
    )
  }

  /** `y = a·x^b`: linear in both logarithms, but scored against the untransformed y. */
  private fun power(points: List<DoubleArray>): Model {
    var mx = 0.0
    var my = 0.0
    var mxy = 0.0
    var mx2 = 0.0
    var ys = 0.0
    var n = 0
    for (p in points) {
      val lx = ln(p[0])
      val ly = ln(p[1])
      n++
      mx += (lx - mx) / n
      my += (ly - my) / n
      mxy += (lx * ly - mxy) / n
      mx2 += (lx * lx - mx2) / n
      ys += (p[1] - ys) / n
    }
    val coef = ols(mx, my, mxy, mx2)
    // The fit is linear in log-space, so its intercept is `ln(a)`; exponentiating it turns the
    // pair into the multiplier and exponent a reader expects. Upstream does this by mutating the
    // array `predict` has already closed over, which is easy to misread as predicting with the
    // logarithm's intercept — it does not.
    val a = exp(coef[0])
    val b = coef[1]
    val predict = { x: Double -> a * x.pow(b) }
    return Model(listOf(a, b), rSquared(points, ys, predict), predict)
  }

  private fun quadratic(points: List<DoubleArray>): Model {
    val c = centre(points)
    val n = c.x.size
    var x2 = 0.0
    var x3 = 0.0
    var x4 = 0.0
    var xy = 0.0
    var x2y = 0.0
    for (i in 0 until n) {
      val dx = c.x[i]
      val dy = c.y[i]
      val sq = dx * dx
      val k = i + 1
      x2 += (sq - x2) / k
      x3 += (sq * dx - x3) / k
      x4 += (sq * sq - x4) / k
      xy += (dx * dy - xy) / k
      x2y += (sq * dy - x2y) / k
    }
    val x2x2 = x4 - x2 * x2
    val d = x2 * x2x2 - x3 * x3
    val a = (x2y * x2 - xy * x3) / d
    val b = (xy * x2x2 - x2y * x3) / d
    val cc = -a * x2
    val predict = { input: Double ->
      val x = input - c.ux
      a * x * x + b * x + cc + c.uy
    }
    return Model(
      listOf(cc - b * c.ux + a * c.ux * c.ux + c.uy, b - 2 * a * c.ux, a),
      rSquared(points, c.uy, predict),
      predict,
    )
  }

  /** A polynomial of any order, solved by Gaussian elimination on the normal equations. */
  private fun polynomial(points: List<DoubleArray>, order: Int): Model {
    if (order == 0) return constant(points)
    if (order == 1) return linear(points)
    if (order == 2) return quadratic(points)

    val c = centre(points)
    val n = c.x.size
    val k = order + 1
    // `k` rows of the normal-equation matrix, then the right-hand side as one more row.
    val matrix = ArrayList<DoubleArray>(k + 1)
    val lhs = DoubleArray(k)
    for (i in 0 until k) {
      var v = 0.0
      for (l in 0 until n) v += c.x[l].pow(i) * c.y[l]
      lhs[i] = v
      val row = DoubleArray(k)
      for (j in 0 until k) {
        var s = 0.0
        for (l in 0 until n) s += c.x[l].pow(i + j)
        row[j] = s
      }
      matrix += row
    }
    matrix += lhs

    val coef = gaussianElimination(matrix, k)
    val predict = { input: Double ->
      val x = input - c.ux
      var y = c.uy + coef[0] + coef[1] * x + coef[2] * x * x
      for (i in 3 until k) y += coef[i] * x.pow(i)
      y
    }
    return Model(uncentre(k, coef, -c.ux, c.uy), rSquared(points, c.uy, predict), predict)
  }

  /**
   * Rewrites a polynomial in `(x - ux)` as one in `x`, by binomial expansion.
   *
   * The fit is computed centred because the powers of a large x overflow the conditioning of the
   * matrix; the coefficients are reported uncentred because that is what a reader means by them.
   */
  private fun uncentre(k: Int, a: DoubleArray, x: Double, y: Double): List<Double> {
    val z = DoubleArray(k)
    for (i in k - 1 downTo 0) {
      val v = a[i]
      var c = 1.0
      z[i] += v
      for (j in 1..i) {
        c *= (i + 1 - j).toDouble() / j
        z[i - j] += v * x.pow(j) * c
      }
    }
    z[0] += y
    return z.toList()
  }

  private fun gaussianElimination(matrix: List<DoubleArray>, n: Int): DoubleArray {
    val coef = DoubleArray(n)
    for (i in 0 until n) {
      var r = i
      for (j in i + 1 until n) {
        if (abs(matrix[i][j]) > abs(matrix[i][r])) r = j
      }
      for (row in i..n) {
        val t = matrix[row][i]
        matrix[row][i] = matrix[row][r]
        matrix[row][r] = t
      }
      for (j in i + 1 until n) {
        for (row in n downTo i) {
          matrix[row][j] -= matrix[row][i] * matrix[i][j] / matrix[i][i]
        }
      }
    }
    for (j in n - 1 downTo 0) {
      var t = 0.0
      for (row in j + 1 until n) t += matrix[row][j] * coef[row]
      coef[j] = (matrix[n][j] - t) / matrix[j][j]
    }
    return coef
  }

  /**
   * `loess`: a fit with no equation, made of one weighted straight line per point.
   *
   * Each point gets its own regression over the `bandwidth` fraction of its nearest neighbours,
   * weighted by a tricube kernel so a neighbour's influence falls to nothing at the edge of the
   * window. Three robustness passes then follow, re-weighting by each point's residual against the
   * median residual, so an outlier stops dragging its neighbourhood towards it.
   *
   * The output is one point per **distinct x**, not one per row — rows sharing an x are averaged,
   * because a local fit gives them all the same answer and the line would otherwise double back on
   * itself.
   */
  internal fun loess(points: List<DoubleArray>, bandwidth: Double): List<DoubleArray> {
    val c = centre(points, sort = true)
    val n = c.x.size
    val bw = maxOf(2, (bandwidth * n).toInt())
    val yhat = DoubleArray(n)
    val residuals = DoubleArray(n)
    val weights = DoubleArray(n) { 1.0 }
    val maxIters = 2
    val epsilon = 1e-12

    var iter = 0
    while (iter <= maxIters) {
      var i0 = 0
      var i1 = bw - 1
      for (i in 0 until n) {
        val dx = c.x[i]
        val edge = if ((dx - c.x[i0]) > (c.x[i1] - dx)) i0 else i1
        var w = 0.0
        var sx = 0.0
        var sy = 0.0
        var sxy = 0.0
        var sx2 = 0.0
        // A window whose edge sits exactly on the point would divide by zero; upstream falls back
        // to a distance of one rather than special-casing it.
        val reach = c.x[edge] - dx
        val denom = 1.0 / abs(if (reach == 0.0) 1.0 else reach)
        for (k in i0..i1) {
          val xk = c.x[k]
          val yk = c.y[k]
          val wk = tricube(abs(dx - xk) * denom) * weights[k]
          val xkw = xk * wk
          w += wk
          sx += xkw
          sy += yk * wk
          sxy += yk * xkw
          sx2 += xk * xkw
        }
        val fitted = ols(sx / w, sy / w, sxy / w, sx2 / w)
        yhat[i] = fitted[0] + fitted[1] * dx
        residuals[i] = abs(c.y[i] - yhat[i])

        // Slide the window right while doing so brings it no further from the next point.
        val next = i + 1
        if (next < n) {
          val value = c.x[next]
          var left = i0
          var right = i1 + 1
          while (right < n && next > left && (c.x[right] - value) <= (value - c.x[left])) {
            i0 = ++left
            i1 = right
            right++
          }
        }
      }

      if (iter == maxIters) break
      val median = medianOf(residuals)
      if (abs(median) < epsilon) break
      for (i in 0 until n) {
        val arg = residuals[i] / (6 * median)
        // A wildly deviant point is given epsilon rather than zero: a zero weight would make a
        // sparse window singular.
        weights[i] = if (arg >= 1) epsilon else (1 - arg * arg).let { it * it }
      }
      iter++
    }

    val out = mutableListOf<DoubleArray>()
    var count = 0
    var prev: DoubleArray? = null
    for (i in 0 until n) {
      val v = c.x[i] + c.ux
      if (prev != null && prev[0] == v) {
        prev[1] += (yhat[i] - prev[1]) / (++count)
      } else {
        count = 0
        prev?.let { it[1] += c.uy }
        prev = doubleArrayOf(v, yhat[i])
        out += prev
      }
    }
    prev?.let { it[1] += c.uy }
    return out
  }

  /** `(1 - x³)³`: full weight at the centre of the window, falling to nothing at its edge. */
  private fun tricube(x: Double): Double {
    val a = 1 - x * x * x
    return a * a * a
  }

  private fun medianOf(values: DoubleArray): Double {
    val sorted = values.filterNot { it.isNaN() }.sorted()
    if (sorted.isEmpty()) return Double.NaN
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
  }
}
