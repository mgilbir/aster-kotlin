package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import kotlin.math.floor
import kotlin.math.pow

/**
 * A raster grid: values row by row, with the width and height that shape them.
 *
 * The shape a `kde2d` or a loaded grid arrives in, and what `isocontour` reads. `x1`/`y1` are the
 * grid's own origin, which the coordinate transform subtracts before scaling.
 */
internal class Grid(
  val width: Int,
  val height: Int,
  val values: DoubleArray,
  val x1: Double = 0.0,
  val y1: Double = 0.0,
  val scale: Double? = null,
  val translate: List<Double>? = null,
) {
  companion object {
    fun from(value: VegaValue): Grid? {
      val obj = value as? VegaValue.Obj ?: return null
      val width = obj.fields["width"]?.asDouble()?.toInt() ?: return null
      val height = obj.fields["height"]?.asDouble()?.toInt() ?: return null
      val values = (obj.fields["values"] as? VegaValue.Arr)?.values ?: return null
      if (width <= 0 || height <= 0 || values.size < width * height) return null
      return Grid(
        width = width,
        height = height,
        values = DoubleArray(values.size) { JsSemantics.toNumber(values[it]) },
        x1 = obj.fields["x1"]?.asDouble() ?: 0.0,
        y1 = obj.fields["y1"]?.asDouble() ?: 0.0,
        scale = obj.fields["scale"]?.asDouble()?.takeIf { !it.isNaN() },
        translate = (obj.fields["translate"] as? VegaValue.Arr)?.values?.map { it.asDouble() },
      )
    }
  }
}

/**
 * Marching squares over a raster grid, ported from `vega-geo/src/util/contours.js`.
 *
 * The port is line-for-line rather than a reimplementation, because the two fiddly parts are not
 * things a fresh implementation would arrive at. Rings are **stitched** from isolines by index — a
 * fragment table keyed on `x * 2 + y * (dx + 1) * 4` — so a contour that closes on itself is
 * recognised as the same fragment arriving from both ends. And a ring is classified by its
 * **signed** area: positive is an exterior ring, negative is a hole, and each hole is then assigned
 * to the first polygon whose outer ring contains it.
 *
 * Upstream's own comment credits d3-contour and topojson's stitcher; this credits upstream.
 */
internal object MarchingSquares {

  /** The isoline segments each of the sixteen corner patterns contributes, in upstream's order. */
  private val CASES: Array<Array<Array<DoubleArray>>> =
    arrayOf(
      arrayOf(),
      arrayOf(arrayOf(doubleArrayOf(1.0, 1.5), doubleArrayOf(0.5, 1.0))),
      arrayOf(arrayOf(doubleArrayOf(1.5, 1.0), doubleArrayOf(1.0, 1.5))),
      arrayOf(arrayOf(doubleArrayOf(1.5, 1.0), doubleArrayOf(0.5, 1.0))),
      arrayOf(arrayOf(doubleArrayOf(1.0, 0.5), doubleArrayOf(1.5, 1.0))),
      arrayOf(
        arrayOf(doubleArrayOf(1.0, 1.5), doubleArrayOf(0.5, 1.0)),
        arrayOf(doubleArrayOf(1.0, 0.5), doubleArrayOf(1.5, 1.0)),
      ),
      arrayOf(arrayOf(doubleArrayOf(1.0, 0.5), doubleArrayOf(1.0, 1.5))),
      arrayOf(arrayOf(doubleArrayOf(1.0, 0.5), doubleArrayOf(0.5, 1.0))),
      arrayOf(arrayOf(doubleArrayOf(0.5, 1.0), doubleArrayOf(1.0, 0.5))),
      arrayOf(arrayOf(doubleArrayOf(1.0, 1.5), doubleArrayOf(1.0, 0.5))),
      arrayOf(
        arrayOf(doubleArrayOf(0.5, 1.0), doubleArrayOf(1.0, 0.5)),
        arrayOf(doubleArrayOf(1.5, 1.0), doubleArrayOf(1.0, 1.5)),
      ),
      arrayOf(arrayOf(doubleArrayOf(1.5, 1.0), doubleArrayOf(1.0, 0.5))),
      arrayOf(arrayOf(doubleArrayOf(0.5, 1.0), doubleArrayOf(1.5, 1.0))),
      arrayOf(arrayOf(doubleArrayOf(1.0, 1.5), doubleArrayOf(1.5, 1.0))),
      arrayOf(arrayOf(doubleArrayOf(0.5, 1.0), doubleArrayOf(1.0, 1.5))),
      arrayOf(),
    )

  /** One fragment of a ring under construction, keyed by the indices of its two open ends. */
  private class Fragment(var start: Int, var end: Int, val ring: MutableList<DoubleArray>)

  /**
   * The polygons of one level set, as a GeoJSON `MultiPolygon`'s coordinate list.
   *
   * Each entry is a polygon: its exterior ring first, then any holes assigned to it.
   */
  fun contour(
    values: DoubleArray,
    width: Int,
    height: Int,
    value: Double,
    smooth: Boolean,
  ): List<List<List<DoubleArray>>> {
    val polygons = mutableListOf<MutableList<List<DoubleArray>>>()
    val holes = mutableListOf<List<DoubleArray>>()

    isorings(values, width, height, value) { ring ->
      if (smooth) smoothLinear(ring, values, width, height, value)
      if (area(ring) > 0) polygons.add(mutableListOf(ring)) else holes.add(ring)
    }

    for (hole in holes) {
      for (polygon in polygons) {
        if (contains(polygon[0], hole) != -1) {
          polygon.add(hole)
          break
        }
      }
    }
    return polygons
  }

  private fun isorings(
    values: DoubleArray,
    dx: Int,
    dy: Int,
    value: Double,
    callback: (MutableList<DoubleArray>) -> Unit,
  ) {
    val fragmentByStart = HashMap<Int, Fragment>()
    val fragmentByEnd = HashMap<Int, Fragment>()
    var x: Int
    var y: Int
    var t0: Boolean
    var t1: Boolean
    var t2: Boolean
    var t3: Boolean

    fun index(point: DoubleArray): Int = (point[0] * 2 + point[1] * (dx + 1) * 4).toInt()

    fun stitch(line: Array<DoubleArray>, ox: Int, oy: Int) {
      val start = doubleArrayOf(line[0][0] + ox, line[0][1] + oy)
      val end = doubleArrayOf(line[1][0] + ox, line[1][1] + oy)
      val startIndex = index(start)
      val endIndex = index(end)
      val byEnd = fragmentByEnd[startIndex]
      if (byEnd != null) {
        val byStart = fragmentByStart[endIndex]
        if (byStart != null) {
          fragmentByEnd.remove(byEnd.end)
          fragmentByStart.remove(byStart.start)
          if (byEnd === byStart) {
            byEnd.ring.add(end)
            callback(byEnd.ring)
          } else {
            val joined =
              Fragment(byEnd.start, byStart.end, (byEnd.ring + byStart.ring).toMutableList())
            fragmentByStart[joined.start] = joined
            fragmentByEnd[joined.end] = joined
          }
        } else {
          fragmentByEnd.remove(byEnd.end)
          byEnd.ring.add(end)
          byEnd.end = endIndex
          fragmentByEnd[endIndex] = byEnd
        }
      } else {
        val byStart = fragmentByStart[endIndex]
        if (byStart != null) {
          val other = fragmentByEnd[startIndex]
          if (other != null) {
            fragmentByStart.remove(byStart.start)
            fragmentByEnd.remove(other.end)
            if (byStart === other) {
              byStart.ring.add(end)
              callback(byStart.ring)
            } else {
              val joined =
                Fragment(other.start, byStart.end, (other.ring + byStart.ring).toMutableList())
              fragmentByStart[joined.start] = joined
              fragmentByEnd[joined.end] = joined
            }
          } else {
            fragmentByStart.remove(byStart.start)
            byStart.ring.add(0, start)
            byStart.start = startIndex
            fragmentByStart[startIndex] = byStart
          }
        } else {
          val fresh = Fragment(startIndex, endIndex, mutableListOf(start, end))
          fragmentByStart[startIndex] = fresh
          fragmentByEnd[endIndex] = fresh
        }
      }
    }

    fun emit(pattern: Int, ox: Int, oy: Int) {
      for (line in CASES[pattern]) stitch(line, ox, oy)
    }

    // The first row, where the two upper corners are outside the grid and so always below.
    x = -1
    y = -1
    t1 = values[0] >= value
    emit(if (t1) 2 else 0, x, y)
    while (++x < dx - 1) {
      t0 = t1
      t1 = values[x + 1] >= value
      emit((if (t0) 1 else 0) or (if (t1) 2 else 0), x, y)
    }
    emit(if (t1) 1 else 0, x, y)

    // The intermediate rows.
    while (++y < dy - 1) {
      x = -1
      t1 = values[y * dx + dx] >= value
      t2 = values[y * dx] >= value
      emit((if (t1) 2 else 0) or (if (t2) 4 else 0), x, y)
      while (++x < dx - 1) {
        t0 = t1
        t1 = values[y * dx + dx + x + 1] >= value
        t3 = t2
        t2 = values[y * dx + x + 1] >= value
        emit(
          (if (t0) 1 else 0) or (if (t1) 2 else 0) or (if (t2) 4 else 0) or (if (t3) 8 else 0),
          x,
          y,
        )
      }
      emit((if (t1) 1 else 0) or (if (t2) 8 else 0), x, y)
    }

    // The last row, where the two lower corners are outside the grid.
    x = -1
    t2 = values[y * dx] >= value
    emit(if (t2) 4 else 0, x, y)
    while (++x < dx - 1) {
      t3 = t2
      t2 = values[y * dx + x + 1] >= value
      emit((if (t2) 4 else 0) or (if (t3) 8 else 0), x, y)
    }
    emit(if (t2) 8 else 0, x, y)
  }

  /**
   * Linear interpolation along the cell edge a point sits on.
   *
   * Without it a contour is a staircase on cell boundaries. The test `xt == x` is what selects the
   * points that lie on a *vertical* edge — a whole number in x — and there is no `else`, so a point
   * on a corner is interpolated in both directions.
   */
  private fun smoothLinear(
    ring: MutableList<DoubleArray>,
    values: DoubleArray,
    dx: Int,
    dy: Int,
    value: Double,
  ) {
    for (point in ring) {
      val x = point[0]
      val y = point[1]
      val xt = floor(x).toInt()
      val yt = floor(y).toInt()
      // Read the way JavaScript reads it: a point on the grid's far edge indexes past the end,
      // where JS gives `undefined` and the guards below then skip the interpolation that would have
      // used it. Kotlin would throw, so the absence is spelled out.
      val v1 = values.getOrElse(yt * dx + xt) { Double.NaN }
      if (x > 0 && x < dx && xt.toDouble() == x) {
        val v0 = values[yt * dx + xt - 1]
        point[0] = x + (value - v0) / (v1 - v0) - 0.5
      }
      if (y > 0 && y < dy && yt.toDouble() == y) {
        val v0 = values[(yt - 1) * dx + xt]
        point[1] = y + (value - v0) / (v1 - v0) - 0.5
      }
    }
  }

  /** Twice the signed area: positive for an exterior ring, negative for a hole. */
  private fun area(ring: List<DoubleArray>): Double {
    val n = ring.size
    var total = ring[n - 1][1] * ring[0][0] - ring[n - 1][0] * ring[0][1]
    for (i in 1 until n) {
      total += ring[i - 1][1] * ring[i][0] - ring[i - 1][0] * ring[i][1]
    }
    return total
  }

  private fun contains(ring: List<DoubleArray>, hole: List<DoubleArray>): Int {
    for (point in hole) {
      val c = ringContains(ring, point)
      if (c != 0) return c
    }
    return 0
  }

  private fun ringContains(ring: List<DoubleArray>, point: DoubleArray): Int {
    val x = point[0]
    val y = point[1]
    var contains = -1
    var j = ring.size - 1
    for (i in ring.indices) {
      val pi = ring[i]
      val pj = ring[j]
      if (segmentContains(pi, pj, point)) return 0
      if (
        (pi[1] > y) != (pj[1] > y) && x < (pj[0] - pi[0]) * (y - pi[1]) / (pj[1] - pi[1]) + pi[0]
      ) {
        contains = -contains
      }
      j = i
    }
    return contains
  }

  private fun segmentContains(a: DoubleArray, b: DoubleArray, c: DoubleArray): Boolean {
    if (!collinear(a, b, c)) return false
    val i = if (a[0] == b[0]) 1 else 0
    return within(a[i], c[i], b[i])
  }

  private fun collinear(a: DoubleArray, b: DoubleArray, c: DoubleArray): Boolean =
    (b[0] - a[0]) * (c[1] - a[1]) == (c[0] - a[0]) * (b[1] - a[1])

  private fun within(p: Double, q: Double, r: Double): Boolean =
    (p <= q && q <= r) || (r <= q && q <= p)
}

/**
 * `isocontour`: level sets of a raster grid, as GeoJSON `MultiPolygon` features.
 *
 * One output row per grid per threshold, carrying the source row's own columns — so a faceted grid
 * keeps whatever identified it. The coordinates are in *grid* space unless `scale` or `translate`
 * says otherwise, which is how a contour computed on a 61 x 87 grid is drawn across a 960-unit
 * chart: `{"scale": {"expr": "width / datum.width"}}`.
 */
public object IsocontourTransform : Transform {
  override val type: String = "isocontour"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val path = params.string("field")
    // `"as": null` is upstream's "put the feature *in place of* the row" — a null there is a
    // deliberate value rather than an omission, which is why this cannot use a plain default.
    val named = params.fields.containsKey("as")
    val as0 =
      if (!named) "contour"
      else (params.fields["as"] as? VegaValue.Str)?.value?.takeIf { it.isNotEmpty() }
    val smooth = params.boolean("smooth") ?: true
    val explicit =
      (params.fields["thresholds"] as? VegaValue.Arr)?.values?.map { JsSemantics.toNumber(it) }

    val output = mutableListOf<VegaValue>()
    for (datum in input) {
      val source = if (path == null) datum else datum.field(path)
      val grid = Grid.from(source)
      if (grid == null) {
        context.diagnostics.error(
          DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
          "isocontour needs a raster grid — an object with 'width', 'height' and 'values' — " +
            if (path == null) "as the row itself" else "in field '$path'",
          operator = type,
        )
        return input
      }
      val thresholds = explicit ?: levels(grid, params)
      for (value in thresholds) {
        val polygons = MarchingSquares.contour(grid.values, grid.width, grid.height, value, smooth)
        val placed = transformed(polygons, grid, datum, params, context)
        val feature =
          VegaValue.Obj(
            linkedMapOf(
              "type" to VegaValue.Str("MultiPolygon"),
              "value" to VegaValue.Num(value),
              "coordinates" to placed,
            )
          )
        output.add(if (as0 == null) feature else datum.withField(as0, feature))
      }
    }
    return output
  }

  /**
   * `levels`, `nice` and `zero` when no `thresholds` array is given.
   *
   * Upstream's `quantize`: the step is `span / (k + 1)` unless `nice`, and the levels run from
   * `start + step` up to but **not including** `stop` — so ten levels are ten interior contours,
   * not ten boundaries.
   */
  private fun levels(grid: Grid, params: VegaValue.Obj): List<Double> {
    val count = params.number("levels")?.toInt() ?: 10
    val nice = params.boolean("nice") ?: false
    val zero = params.boolean("zero") ?: true
    val usable = grid.values.filter { !it.isNaN() }
    if (usable.isEmpty()) return emptyList()
    val start = if (zero) minOf(usable.min(), 0.0) else usable.min()
    val stop = usable.max()
    val step =
      if (nice) {
        niceStep(start, stop, count)
      } else {
        (stop - start) / (count + 1)
      }
    if (step <= 0.0 || !step.isFinite()) return emptyList()
    val result = mutableListOf<Double>()
    var at = start + step
    var guard = 0
    while (at < stop && guard < 10_000) {
      result.add(at)
      guard++
      at = start + step * (guard + 1)
    }
    return result
  }

  /**
   * `scale` and `translate`, applied to every coordinate.
   *
   * A negative scale flips the ring, so upstream reverses the winding to keep exterior rings
   * positive — the same rule the polygon/hole classification depends on.
   */
  private fun transformed(
    polygons: List<List<List<DoubleArray>>>,
    grid: Grid,
    datum: VegaValue,
    params: VegaValue.Obj,
    context: TransformContext,
  ): VegaValue.Arr {
    val scaleValue =
      params.fields["scale"]?.let { resolveNumeric(it, datum, context) } ?: grid.scale
    val translate =
      (params.fields["translate"] as? VegaValue.Arr)?.values?.map { it.asDouble() }
        ?: grid.translate
    val sx = scaleValue ?: 1.0
    val sy = scaleValue ?: 1.0
    val tx = translate?.getOrNull(0) ?: 0.0
    val ty = translate?.getOrNull(1) ?: 0.0
    val flip = sx * sy < 0

    return VegaValue.Arr(
      polygons.map { polygon ->
        VegaValue.Arr(
          polygon.map { ring ->
            val ordered = if (flip) ring.reversed() else ring
            VegaValue.Arr(
              ordered.map { point ->
                VegaValue.Arr(
                  listOf(
                    VegaValue.Num((point[0] - grid.x1) * sx + tx),
                    VegaValue.Num((point[1] - grid.y1) * sy + ty),
                  )
                )
              }
            )
          }
        )
      }
    )
  }

  /** A parameter that may be a number or an `{"expr": ...}` evaluated against the row. */
  private fun resolveNumeric(
    value: VegaValue,
    datum: VegaValue,
    context: TransformContext,
  ): Double? {
    val expression = (value as? VegaValue.Obj)?.fields?.get("expr")?.asString()
    if (expression != null) {
      val compiled = TupleExpression(expression, context, type)
      if (!compiled.isUsable) return null
      return compiled.evaluate(datum)?.let { JsSemantics.toNumber(it) }?.takeIf { !it.isNaN() }
    }
    return JsSemantics.toNumber(value).takeIf { !it.isNaN() }
  }
}

/** d3's `tickStep`, for `isocontour`'s `nice` levels. */
internal fun niceStep(start: Double, stop: Double, count: Int): Double {
  val step0 = kotlin.math.abs(stop - start) / maxOf(1, count)
  var step1 = 10.0.pow(floor(kotlin.math.log10(step0)))
  val error = step0 / step1
  if (error >= kotlin.math.sqrt(50.0)) step1 *= 10.0
  else if (error >= kotlin.math.sqrt(10.0)) step1 *= 5.0
  else if (error >= kotlin.math.sqrt(2.0)) step1 *= 2.0
  return if (stop < start) -step1 else step1
}
