package dev.aster.vega.dataflow.geo

import dev.aster.vega.dataflow.geo.GeoMath.EPSILON
import dev.aster.vega.model.VegaValue
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The grid of meridians and parallels a map is drawn over, `d3-geo/src/graticule.js`.
 *
 * Two step sizes, not one: the **major** lines run the full height of the globe every 90 degrees of
 * longitude and the full width every 360 of latitude — which is to say, the frame — while the
 * **minor** ones fill in every 10 degrees and stop 10 degrees short of each pole, because meridians
 * converging on a point are noise rather than information. A minor line that coincides with a major
 * one is dropped, which is what the `% DX` filter is for.
 *
 * The lines themselves are drawn at 2.5-degree resolution so that a projection has something to
 * curve; adaptive resampling refines them further from there.
 */
internal class Graticule {
  private var extentMajor = doubleArrayOf(-180.0, -90 + EPSILON, 180.0, 90 - EPSILON)
  private var extentMinor = doubleArrayOf(-180.0, -80 - EPSILON, 180.0, 80 + EPSILON)
  private var stepMajor = doubleArrayOf(90.0, 360.0)
  private var stepMinor = doubleArrayOf(10.0, 10.0)
  private var precision = 2.5

  fun extentMajor(x0: Double, y0: Double, x1: Double, y1: Double): Graticule {
    extentMajor = ordered(x0, y0, x1, y1)
    return this
  }

  fun extentMinor(x0: Double, y0: Double, x1: Double, y1: Double): Graticule {
    extentMinor = ordered(x0, y0, x1, y1)
    return this
  }

  fun stepMajor(dx: Double, dy: Double): Graticule {
    stepMajor = doubleArrayOf(dx, dy)
    return this
  }

  fun stepMinor(dx: Double, dy: Double): Graticule {
    stepMinor = doubleArrayOf(dx, dy)
    return this
  }

  fun precision(value: Double): Graticule {
    precision = value
    return this
  }

  /** The whole grid as one `MultiLineString`, which is what the transform publishes. */
  fun multiLineString(): VegaValue =
    VegaValue.Obj(
      linkedMapOf(
        "type" to VegaValue.Str("MultiLineString"),
        "coordinates" to
          VegaValue.Arr(
            lines().map { line ->
              VegaValue.Arr(
                line.map { p -> VegaValue.Arr(listOf(VegaValue.Num(p[0]), VegaValue.Num(p[1]))) }
              )
            }
          ),
      )
    )

  /** Each line on its own, as `LineString` features — d3's `graticule.lines()`. */
  fun lineStrings(): VegaValue =
    VegaValue.Arr(
      lines().map { line ->
        VegaValue.Obj(
          linkedMapOf(
            "type" to VegaValue.Str("LineString"),
            "coordinates" to VegaValue.Arr(line.map { point(it) }),
          )
        )
      }
    )

  /**
   * The major extent's boundary as one closed ring — d3's `graticule.outline()`.
   *
   * Down the western meridian, east along the northern parallel, back up the eastern meridian and
   * west along the southern one. Each leg drops its first point because the previous leg already
   * ended there, which is what keeps the ring from repeating a vertex at every corner.
   */
  fun outline(): VegaValue {
    val (bigX0, bigY0, bigX1, bigY1) = extentMajor
    val meridian = graticuleX(bigY0, bigY1, 90.0)
    val parallel = graticuleY(bigX0, bigX1, precision)
    val ring =
      meridian(bigX0) +
        parallel(bigY1).drop(1) +
        meridian(bigX1).reversed().drop(1) +
        parallel(bigY0).reversed().drop(1)
    return VegaValue.Obj(
      linkedMapOf(
        "type" to VegaValue.Str("Polygon"),
        "coordinates" to VegaValue.Arr(listOf(VegaValue.Arr(ring.map { point(it) }))),
      )
    )
  }

  /** The configuration read back, which d3 answers when a setter is called with no argument. */
  fun extentMajorValue(): DoubleArray = extentMajor

  fun extentMinorValue(): DoubleArray = extentMinor

  fun stepMajorValue(): DoubleArray = stepMajor

  fun stepMinorValue(): DoubleArray = stepMinor

  fun precisionValue(): Double = precision

  private fun point(p: DoubleArray): VegaValue =
    VegaValue.Arr(listOf(VegaValue.Num(p[0]), VegaValue.Num(p[1])))

  private fun lines(): List<List<DoubleArray>> {
    val (bigX0, bigY0, bigX1, bigY1) = extentMajor
    val (smallX0, smallY0, smallX1, smallY1) = extentMinor
    val bigDx = stepMajor[0]
    val bigDy = stepMajor[1]
    val smallDx = stepMinor[0]
    val smallDy = stepMinor[1]

    val meridianMajor = graticuleX(bigY0, bigY1, 90.0)
    val parallelMajor = graticuleY(bigX0, bigX1, precision)
    val meridianMinor = graticuleX(smallY0, smallY1, 90.0)
    val parallelMinor = graticuleY(smallX0, smallX1, precision)

    val out = mutableListOf<List<DoubleArray>>()
    for (x in range(ceil(bigX0 / bigDx) * bigDx, bigX1, bigDx)) out += meridianMajor(x)
    for (y in range(ceil(bigY0 / bigDy) * bigDy, bigY1, bigDy)) out += parallelMajor(y)
    for (x in range(ceil(smallX0 / smallDx) * smallDx, smallX1, smallDx)) {
      if (abs(x % bigDx) > EPSILON) out += meridianMinor(x)
    }
    for (y in range(ceil(smallY0 / smallDy) * smallDy, smallY1, smallDy)) {
      if (abs(y % bigDy) > EPSILON) out += parallelMinor(y)
    }
    return out
  }

  /** A meridian: one longitude, sampled down the range of latitudes. */
  private fun graticuleX(y0: Double, y1: Double, dy: Double): (Double) -> List<DoubleArray> {
    val ys = range(y0, y1 - EPSILON, dy) + y1
    return { x -> ys.map { doubleArrayOf(x, it) } }
  }

  /** A parallel: one latitude, sampled across the range of longitudes. */
  private fun graticuleY(x0: Double, x1: Double, dx: Double): (Double) -> List<DoubleArray> {
    val xs = range(x0, x1 - EPSILON, dx) + x1
    return { y -> xs.map { doubleArrayOf(it, y) } }
  }

  /**
   * `d3.range`, and the count is computed rather than accumulated.
   *
   * `start + i * step` for a count fixed up front, not a running total — with a step of 2.5 the two
   * differ by the last bits after a few hundred iterations, and a graticule has a few hundred.
   */
  private fun range(start: Double, stop: Double, step: Double): List<Double> {
    val n = maxOf(0, ceil((stop - start) / step).toInt())
    return (0 until n).map { start + it * step }
  }

  private fun ordered(x0: Double, y0: Double, x1: Double, y1: Double): DoubleArray =
    doubleArrayOf(minOf(x0, x1), minOf(y0, y1), maxOf(x0, x1), maxOf(y0, y1))

  private operator fun DoubleArray.component1(): Double = this[0]

  private operator fun DoubleArray.component2(): Double = this[1]

  private operator fun DoubleArray.component3(): Double = this[2]

  private operator fun DoubleArray.component4(): Double = this[3]
}
