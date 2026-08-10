package dev.aster.vega.dataflow.geo

import kotlin.math.round

/**
 * The stream that finally writes an SVG path, `d3-geo/src/path/string.js`.
 *
 * Two details are upstream's and both are visible. Coordinates are rounded to **three** decimals —
 * d3-geo's default `digits`, not a tidying choice, and it is what a map's `d` attribute actually
 * contains. And a bare `point` between `lineStart`s draws a *circle* of the current point radius,
 * because that is how d3 renders a `Point` geometry; a projected city is a dot, not a move-to.
 */
internal class PathStringSink(private val digits: Int = 3) : GeoStream() {
  private val out = StringBuilder()
  private var pointState = -1
  private var line = Double.NaN
  private var radius = 4.5

  fun pointRadius(value: Double) {
    radius = value
  }

  override fun polygonStart() {
    line = 0.0
  }

  override fun polygonEnd() {
    line = Double.NaN
  }

  override fun lineStart() {
    pointState = 0
  }

  override fun lineEnd() {
    if (line == 0.0) out.append('Z')
    pointState = -1
  }

  override fun point(x: Double, y: Double) {
    when (pointState) {
      0 -> {
        out.append('M').append(number(x)).append(',').append(number(y))
        pointState = 1
      }
      1 -> out.append('L').append(number(x)).append(',').append(number(y))
      else -> {
        out.append('M').append(number(x)).append(',').append(number(y))
        val r = number(radius)
        val d = number(-2 * radius)
        val u = number(2 * radius)
        out
          .append("m0,")
          .append(r)
          .append('a')
          .append(r)
          .append(',')
          .append(r)
          .append(" 0 1,1 0,")
          .append(d)
          .append('a')
          .append(r)
          .append(',')
          .append(r)
          .append(" 0 1,1 0,")
          .append(u)
          .append('z')
      }
    }
  }

  /** The path so far, or null when nothing was drawn — which is not the same as an empty string. */
  fun result(): String? {
    val result = out.toString()
    out.setLength(0)
    return result.ifEmpty { null }
  }

  private fun number(value: Double): String {
    if (!value.isFinite()) return "NaN"
    var k = 1.0
    repeat(digits) { k *= 10 }
    val rounded = round(value * k) / k
    // JavaScript renders a whole number without a decimal point, and a path string is compared as
    // text, so `10` and `10.0` are not the same attribute.
    return if (rounded == kotlin.math.floor(rounded) && kotlin.math.abs(rounded) < 1e21) {
      rounded.toLong().toString()
    } else {
      rounded.toString()
    }
  }
}

/** The bounding box of whatever is streamed through it, `d3-geo/src/path/bounds.js`. */
internal class PathBoundsSink : GeoStream() {
  private var x0 = Double.POSITIVE_INFINITY
  private var y0 = Double.POSITIVE_INFINITY
  private var x1 = Double.NEGATIVE_INFINITY
  private var y1 = Double.NEGATIVE_INFINITY

  override fun point(x: Double, y: Double) {
    if (x < x0) x0 = x
    if (x > x1) x1 = x
    if (y < y0) y0 = y
    if (y > y1) y1 = y
  }

  /** `[[x0, y0], [x1, y1]]`, or null when nothing was drawn. */
  fun result(): DoubleArray? = if (x0 > x1) null else doubleArrayOf(x0, y0, x1, y1)
}

/**
 * Where a drawn shape balances, `d3-geo/src/path/centroid.js`.
 *
 * Three accumulators run at once and the answer is the highest-dimensional one that has any weight
 * — area, then length, then a plain count of points. A polygon therefore balances on its area even
 * when its outline is much longer than its neighbour's, and a shape with no area at all still gives
 * an answer rather than a NaN.
 */
internal class PathCentroidSink : GeoStream() {
  private var x0Sum = 0.0
  private var y0Sum = 0.0
  private var count = 0.0
  private var x1Sum = 0.0
  private var y1Sum = 0.0
  private var length = 0.0
  private var x2Sum = 0.0
  private var y2Sum = 0.0
  private var area = 0.0

  private var firstX = 0.0
  private var firstY = 0.0
  private var previousX = 0.0
  private var previousY = 0.0

  private var inPolygon = false
  private var inLine = false
  private var atLineStart = false

  override fun point(x: Double, y: Double) {
    when {
      !inLine -> plain(x, y)
      atLineStart -> {
        atLineStart = false
        previousX = x
        previousY = y
        if (inPolygon) {
          firstX = x
          firstY = y
        }
        plain(x, y)
      }
      inPolygon -> ringPoint(x, y)
      else -> linePoint(x, y)
    }
  }

  override fun lineStart() {
    inLine = true
    atLineStart = true
  }

  override fun lineEnd() {
    // A ring is closed by returning to where it started, which is the segment that closes the area.
    if (inPolygon && !atLineStart) ringPoint(firstX, firstY)
    inLine = false
    atLineStart = false
  }

  override fun polygonStart() {
    inPolygon = true
  }

  override fun polygonEnd() {
    inPolygon = false
  }

  private fun plain(x: Double, y: Double) {
    x0Sum += x
    y0Sum += y
    count += 1
  }

  private fun linePoint(x: Double, y: Double) {
    val dx = x - previousX
    val dy = y - previousY
    val z = kotlin.math.sqrt(dx * dx + dy * dy)
    x1Sum += z * (previousX + x) / 2
    y1Sum += z * (previousY + y) / 2
    length += z
    previousX = x
    previousY = y
    plain(x, y)
  }

  private fun ringPoint(x: Double, y: Double) {
    val dx = x - previousX
    val dy = y - previousY
    val z = kotlin.math.sqrt(dx * dx + dy * dy)
    x1Sum += z * (previousX + x) / 2
    y1Sum += z * (previousY + y) / 2
    length += z

    val cross = previousY * x - previousX * y
    x2Sum += cross * (previousX + x)
    y2Sum += cross * (previousY + y)
    area += cross * 3
    previousX = x
    previousY = y
    plain(x, y)
  }

  fun result(): DoubleArray? =
    when {
      area != 0.0 -> doubleArrayOf(x2Sum / area, y2Sum / area)
      length != 0.0 -> doubleArrayOf(x1Sum / length, y1Sum / length)
      count != 0.0 -> doubleArrayOf(x0Sum / count, y0Sum / count)
      else -> null
    }
}

/**
 * The area a drawn shape covers, `d3-geo/src/path/area.js`.
 *
 * Each ring contributes its own signed area and the **absolute** value of that is added, so a hole
 * counts as much as the shape around it — which is upstream's choice and not obviously the right
 * one, but it is what a chart sizing a circle by `geoArea` is calibrated against.
 */
internal class PathAreaSink : GeoStream() {
  private var total = 0.0
  private var ring = Adder()
  private var firstX = 0.0
  private var firstY = 0.0
  private var previousX = 0.0
  private var previousY = 0.0
  private var inPolygon = false
  private var atRingStart = false

  override fun point(x: Double, y: Double) {
    if (!inPolygon) return
    if (atRingStart) {
      atRingStart = false
      firstX = x
      firstY = y
      previousX = x
      previousY = y
      return
    }
    step(x, y)
  }

  override fun lineStart() {
    if (inPolygon) atRingStart = true
  }

  override fun lineEnd() {
    // The ring closes back to where it started, which is the segment that shuts the area.
    if (inPolygon && !atRingStart) step(firstX, firstY)
  }

  override fun polygonStart() {
    inPolygon = true
  }

  override fun polygonEnd() {
    inPolygon = false
    total += kotlin.math.abs(ring.value())
    ring = Adder()
  }

  private fun step(x: Double, y: Double) {
    ring.add(previousY * x - previousX * y)
    previousX = x
    previousY = y
  }

  /** Half the summed cross products, which is the shoelace area. */
  fun result(): Double = total / 2
}
