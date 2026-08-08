package dev.aster.vega.dataflow.transform

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.field

/**
 * `geopath`: a GeoJSON geometry written out as an SVG path.
 *
 * With **no projection**, which is the only form this engine has: upstream's `getProjectionPath`
 * falls back to `geoPath()` with no projection, and d3 then passes the coordinates straight through
 * — no spherical clipping, no adaptive resampling, no antimeridian cutting. That is exactly right
 * for the case this serves, where the "geometry" is a contour computed on a raster grid and its
 * coordinates are already in the chart's own units. A specification that names a `projection` is
 * still refused, and says so, because the maths that makes one faithful is not here.
 *
 * The generated string is upstream's own shape: `M x,y L x,y … Z` per ring, with the ring's closing
 * point dropped — GeoJSON repeats the first point at the end and `Z` says the same thing.
 */
public object GeoPathTransform : Transform {
  override val type: String = "geopath"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    if (params.fields["projection"] != null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
        "geopath with a 'projection' is not implemented; the outline would be drawn with the " +
          "wrong maths rather than merely in the wrong place. Without one the coordinates are " +
          "used as they are, which is what a contour over a raster grid needs",
        operator = type,
      )
      return input
    }
    val path = params.string("field")
    val as0 = params.string("as") ?: "path"
    return input.map { datum ->
      // `field` is written as an accessor — `"datum.contour"` — because upstream compiles it into
      // one. The leading `datum.` is the accessor's own subject and not part of the column name.
      val source =
        when {
          path == null -> datum
          path.startsWith("datum.") -> datum.field(path.removePrefix("datum."))
          else -> datum.field(path)
        }
      datum.withField(as0, VegaValue.Str(outline(source)))
    }
  }

  /** The `d` attribute for one geometry, or the empty string for anything with no coordinates. */
  private fun outline(geometry: VegaValue): String {
    val obj = geometry as? VegaValue.Obj ?: return ""
    val coordinates = obj.fields["coordinates"] ?: return ""
    val out = StringBuilder()
    when ((obj.fields["type"] as? VegaValue.Str)?.value) {
      "MultiPolygon" -> forEachArray(coordinates) { polygon -> polygon(polygon, out) }
      "Polygon" -> polygon(coordinates, out)
      "MultiLineString" -> forEachArray(coordinates) { line -> ring(line, out, close = false) }
      "LineString" -> ring(coordinates, out, close = false)
      else -> return ""
    }
    return out.toString()
  }

  private fun polygon(value: VegaValue, out: StringBuilder) {
    forEachArray(value) { ring -> ring(ring, out, close = true) }
  }

  private fun ring(value: VegaValue, out: StringBuilder, close: Boolean) {
    val points = (value as? VegaValue.Arr)?.values ?: return
    // GeoJSON closes a ring by repeating its first point; `Z` says the same thing, and emitting
    // both would put a zero-length segment on every ring.
    val count = if (close && points.size > 1) points.size - 1 else points.size
    for (index in 0 until count) {
      val point = (points[index] as? VegaValue.Arr)?.values ?: continue
      if (point.size < 2) continue
      out.append(if (index == 0) 'M' else 'L')
      out.append(number(point[0].asDouble()))
      out.append(',')
      out.append(number(point[1].asDouble()))
    }
    if (close && count > 0) out.append('Z')
  }

  private inline fun forEachArray(value: VegaValue, action: (VegaValue) -> Unit) {
    (value as? VegaValue.Arr)?.values?.forEach(action)
  }

  /** Whole numbers without a trailing `.0`, which is how upstream's path strings read. */
  private fun number(value: Double): String =
    if (value == kotlin.math.floor(value) && value.isFinite()) value.toLong().toString()
    else value.toString()
}
