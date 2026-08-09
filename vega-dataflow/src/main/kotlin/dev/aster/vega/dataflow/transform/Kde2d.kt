package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

/**
 * `kde2d`: a two-dimensional kernel density over points, as a raster grid.
 *
 * The grid it produces is the one [IsocontourTransform] and `heatmap` read — `{width, height,
 * values}` plus the origin and scale that put it back on the chart.
 *
 * Three things about the arithmetic are upstream's and cannot be improved on without changing the
 * picture. The accumulator is a **32-bit float** array, so the blur's running sums lose the bits a
 * double would keep. The kernel is three passes of a box blur in each direction, which approximates
 * a Gaussian — not a Gaussian. And the coordinates are **shifted right** by `log2(cellSize)` before
 * accumulation, which is an integer truncation towards zero rather than a division: a point at x =
 * -3 with a cell size of 4 lands in cell 0, not cell -1.
 */
public object Kde2dTransform : Transform {
  override val type: String = "kde2d"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val size = (params.fields["size"] as? VegaValue.Arr)?.values
    if (size == null || size.size < 2) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "kde2d needs a 'size' of [width, height] in pixels",
        operator = type,
      )
      return input
    }
    val dx = JsSemantics.toNumber(size[0])
    val dy = JsSemantics.toNumber(size[1])
    if (!(dx >= 0) || !(dy >= 0)) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "kde2d's 'size' must be two non-negative numbers",
        operator = type,
      )
      return input
    }

    val xs = accessor(params.fields["x"], context) ?: return reportMissing(context, "x", input)
    val ys = accessor(params.fields["y"], context) ?: return reportMissing(context, "y", input)
    val weights = accessor(params.fields["weight"], context)
    val groupBy = params.stringList("groupby")
    val counts = params.boolean("counts") ?: false
    val as0 = params.string("as") ?: "grid"

    // `cellSize` is a *power of two* however it is written: upstream keeps `log2(cellSize)` and
    // shifts by it, so 3 and 4 both mean 4.
    val cellSize = params.number("cellSize") ?: 4.0
    if (cellSize < 1) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "kde2d's 'cellSize' must be at least 1",
        operator = type,
      )
      return input
    }
    val k = floor(ln(cellSize) / ln(2.0)).toInt()

    val bandwidth =
      when (val declared = params.fields["bandwidth"]) {
        is VegaValue.Arr ->
          declared.values
            .map { JsSemantics.toNumber(it) }
            .let {
              if (it.size == 1) listOf(it[0], it[0]) else it
            }
        null -> listOf(-1.0, -1.0)
        else -> JsSemantics.toNumber(declared).let { listOf(it, it) }
      }
    if (bandwidth.size != 2) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "kde2d's 'bandwidth' is one number or two",
        operator = type,
      )
      return input
    }

    val groups = partition(input, groupBy)
    return groups.map { (key, rows) ->
      val grid = density(rows, xs, ys, weights, dx, dy, k, bandwidth, counts)
      val fields = LinkedHashMap<String, VegaValue>()
      groupBy.forEachIndexed { index, name -> fields[name] = key[index] }
      fields[as0] = grid
      VegaValue.Obj(fields)
    }
  }

  private fun reportMissing(
    context: TransformContext,
    name: String,
    input: List<VegaValue>,
  ): List<VegaValue> {
    context.diagnostics.error(
      DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
      "kde2d needs a '$name' accessor",
      operator = type,
    )
    return input
  }

  /** A `{"expr": ...}` evaluated per row, or a plain field name. */
  private fun accessor(
    value: VegaValue?,
    context: TransformContext,
  ): ((VegaValue) -> Double)? {
    if (value == null) return null
    val expression = (value as? VegaValue.Obj)?.fields?.get("expr")?.asString()
    if (expression != null) {
      val compiled = TupleExpression(expression, context, type)
      if (!compiled.isUsable) return null
      return { datum -> compiled.evaluate(datum)?.let { JsSemantics.toNumber(it) } ?: Double.NaN }
    }
    val path = value.asString()
    if (path.isEmpty()) return null
    return { datum -> JsSemantics.toNumber(datum.field(path)) }
  }

  private fun partition(
    rows: List<VegaValue>,
    groupBy: List<String>,
  ): List<Pair<List<VegaValue>, List<VegaValue>>> {
    if (groupBy.isEmpty()) return listOf(emptyList<VegaValue>() to rows)
    val order = LinkedHashMap<List<String>, Pair<List<VegaValue>, MutableList<VegaValue>>>()
    for (row in rows) {
      val values = groupBy.map { row.field(it) }
      val key = values.map { it.asString() }
      order.getOrPut(key) { values to mutableListOf() }.second.add(row)
    }
    return order.values.map { it.first to it.second }
  }

  /**
   * The blurred grid, in upstream's own order of operations.
   *
   * The blur count is not symmetric between the two-dimensional and one-dimensional cases: with
   * both radii positive it is three x-passes *interleaved* with three y-passes and the result lands
   * back in the first buffer; with only one radius it is three passes of that one and the result is
   * in the second. Reading the buffers the other way round gives a picture that is blurred and is
   * not this blur.
   */
  private fun density(
    rows: List<VegaValue>,
    xs: (VegaValue) -> Double,
    ys: (VegaValue) -> Double,
    weights: ((VegaValue) -> Double)?,
    dx: Double,
    dy: Double,
    k: Int,
    bandwidth: List<Double>,
    counts: Boolean,
  ): VegaValue {
    val rx = radius(bandwidth[0], rows, xs) shr k
    val ry = radius(bandwidth[1], rows, ys) shr k
    val ox = if (rx != 0) rx + 2 else 0
    val oy = if (ry != 0) ry + 2 else 0
    val n = 2 * ox + (dx.toInt() shr k)
    val m = 2 * oy + (dy.toInt() shr k)

    val values0 = FloatArray(n * m)
    val values1 = FloatArray(n * m)
    var values = values0

    for (row in rows) {
      val xi = ox + (shiftRight(xs(row), k))
      val yi = oy + (shiftRight(ys(row), k))
      if (xi in 0 until n && yi in 0 until m) {
        values0[xi + yi * n] += (weights?.invoke(row) ?: 1.0).toFloat()
      }
    }

    if (rx > 0 && ry > 0) {
      blurX(n, m, values0, values1, rx)
      blurY(n, m, values1, values0, ry)
      blurX(n, m, values0, values1, rx)
      blurY(n, m, values1, values0, ry)
      blurX(n, m, values0, values1, rx)
      blurY(n, m, values1, values0, ry)
    } else if (rx > 0) {
      blurX(n, m, values0, values1, rx)
      blurX(n, m, values1, values0, rx)
      blurX(n, m, values0, values1, rx)
      values = values1
    } else if (ry > 0) {
      blurY(n, m, values0, values1, ry)
      blurY(n, m, values1, values0, ry)
      blurY(n, m, values0, values1, ry)
      values = values1
    }

    // Points per square pixel, or a probability density that sums to one.
    val total = values.fold(0.0) { acc, v -> acc + v }
    val s = if (counts) 2.0.pow(-2.0 * k) else 1.0 / total
    for (i in values.indices) values[i] = (values[i] * s).toFloat()

    return VegaValue.Obj(
      linkedMapOf(
        "values" to VegaValue.Arr(values.map { VegaValue.Num(it.toDouble()) }),
        "scale" to VegaValue.Num((1 shl k).toDouble()),
        "width" to VegaValue.Num(n.toDouble()),
        "height" to VegaValue.Num(m.toDouble()),
        "x1" to VegaValue.Num(ox.toDouble()),
        "y1" to VegaValue.Num(oy.toDouble()),
        "x2" to VegaValue.Num((ox + (dx.toInt() shr k)).toDouble()),
        "y2" to VegaValue.Num((oy + (dy.toInt() shr k)).toDouble()),
      )
    )
  }

  /**
   * JavaScript's `x >> k` on a possibly fractional number.
   *
   * `ToInt32` truncates **towards zero** first, so -3 >> 2 is -1 rather than the -1 a floor would
   * give for -0.75 — the two agree here and would not for -5 >> 2, which is -2 either way only
   * because the shift itself floors. Written out because the composition is easy to get wrong.
   */
  private fun shiftRight(value: Double, k: Int): Int {
    if (!value.isFinite()) return 0
    val truncated = if (value < 0) kotlin.math.ceil(value) else floor(value)
    return truncated.toInt() shr k
  }

  /** Upstream's blur radius: a bandwidth in pixels turned into a box-blur half-width. */
  private fun radius(bandwidth: Double, rows: List<VegaValue>, of: (VegaValue) -> Double): Int {
    val v = if (bandwidth >= 0) bandwidth else bandwidthNRD(rows, of)
    return round((sqrt(4 * v * v + 1) - 1) / 2).toInt()
  }

  /**
   * Scott's rule for a kernel bandwidth, as `vega-statistics` writes it.
   *
   * `min(deviation, iqr / 1.34)`, falling back through the deviation and then the first quartile's
   * magnitude — the chain of `||`s matters, because a column with no spread has a deviation of zero
   * and would otherwise give a bandwidth of zero and a grid of single points.
   */
  private fun bandwidthNRD(rows: List<VegaValue>, of: (VegaValue) -> Double): Double {
    val values = rows.map(of).filter { !it.isNaN() }
    if (values.isEmpty()) return 1.0
    val n = rows.size
    val d = deviation(values)
    val q = quartiles(values.sorted())
    val h = (q[2] - q[0]) / 1.34
    val v = listOf(min(d, h), d, abs(q[0]), 1.0).firstOrNull { it.isFinite() && it != 0.0 } ?: 1.0
    return 1.06 * v * n.toDouble().pow(-0.2)
  }

  /** d3's sample standard deviation; NaN for fewer than two values, as d3 returns undefined. */
  private fun deviation(values: List<Double>): Double {
    if (values.size < 2) return Double.NaN
    val mean = values.average()
    val sum = values.sumOf { (it - mean) * (it - mean) }
    return sqrt(sum / (values.size - 1))
  }

  /**
   * The three quartiles by d3's `quantile`, which the bandwidth rule reads the first and last of.
   */
  private fun quartiles(sorted: List<Double>): List<Double> =
    listOf(quantile(sorted, 0.25), quantile(sorted, 0.5), quantile(sorted, 0.75))

  private fun quantile(sorted: List<Double>, p: Double): Double {
    val n = sorted.size
    if (n == 0) return Double.NaN
    if (n < 2 || p <= 0) return sorted[0]
    if (p >= 1) return sorted[n - 1]
    val position = (n - 1) * p
    val low = position.toInt()
    return sorted[low] + (sorted[low + 1] - sorted[low]) * (position - low)
  }

  private fun blurX(n: Int, m: Int, source: FloatArray, target: FloatArray, r: Int) {
    val w = (r shl 1) + 1
    for (j in 0 until m) {
      var sr = 0.0f
      for (i in 0 until n + r) {
        if (i < n) sr += source[i + j * n]
        if (i >= r) {
          if (i >= w) sr -= source[i - w + j * n]
          target[i - r + j * n] = sr / minOf(i + 1, n - 1 + w - i, w)
        }
      }
    }
  }

  private fun blurY(n: Int, m: Int, source: FloatArray, target: FloatArray, r: Int) {
    val w = (r shl 1) + 1
    for (i in 0 until n) {
      var sr = 0.0f
      for (j in 0 until m + r) {
        if (j < m) sr += source[i + j * n]
        if (j >= r) {
          if (j >= w) sr -= source[i + (j - w) * n]
          target[i + (j - r) * n] = sr / minOf(j + 1, m - 1 + w - j, w)
        }
      }
    }
  }
}
