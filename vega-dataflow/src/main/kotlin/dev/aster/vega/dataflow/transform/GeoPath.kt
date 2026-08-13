package dev.aster.vega.dataflow.transform

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
    val projectionName = params.string("projection")
    if (projectionName != null) {
      // With a projection this is `geoshape` under another name, so it is the same code.
      return GeoShapeTransform.apply(
        input,
        VegaValue.Obj(
          LinkedHashMap(params.fields).apply {
            put("as", VegaValue.Str(params.string("as") ?: "path"))
          }
        ),
        context,
        // `geopath` declares no default field, so the geometry is the row itself — which is what a
        // dataset of decoded TopoJSON features is.
        defaultField = null,
      )
    }
    val path = params.string("field")
    val as0 = params.string("as") ?: "path"
    return input.map { datum ->
      // `field` is an accessor path — `"datum.contour"` on a mark transform, where the row is the
      // scene item's own `datum`, and a plain column name on a dataset transform.
      val source = if (path == null) datum else datum.field(path)
      // **Null**, not an empty string, when the geometry produced nothing: d3's path generator
      // returns null, and upstream's path mark measures a null path as `(0, 0, 0, 0)` where a
      // string that draws nothing leaves the bounds empty. The two look identical and are not.
      val drawn = outline(source)
      datum.withField(as0, if (drawn.isEmpty()) VegaValue.Null else VegaValue.Str(drawn))
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
