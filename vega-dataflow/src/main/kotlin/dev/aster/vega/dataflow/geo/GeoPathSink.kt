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
