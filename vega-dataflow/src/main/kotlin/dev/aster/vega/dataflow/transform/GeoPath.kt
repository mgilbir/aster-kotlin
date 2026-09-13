package dev.aster.vega.dataflow.transform

import dev.aster.vega.dataflow.geo.GeoJsonStream
import dev.aster.vega.dataflow.geo.PathStringSink
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field

/**
 * `geopath`: a GeoJSON geometry written out as an SVG path.
 *
 * With **no projection**, which is the case this branch serves: upstream's `getProjectionPath`
 * falls back to `geoPath()` with no projection, and d3 then passes the coordinates straight through
 * — no spherical clipping, no adaptive resampling, no antimeridian cutting. That is exactly right
 * where the "geometry" is a contour computed on a raster grid and its coordinates are already in
 * the chart's own units.
 *
 * The geometry is walked by the **same** reader the projected path uses, [GeoJsonStream], into the
 * same [PathStringSink]. It was a second, shorter reader that understood four geometry types, and
 * the four it did not understand are the ones a specification is most likely to hand it: a `Point`
 * draws a **circle** of the current `pointRadius` rather than nothing, a `MultiPoint` draws one per
 * coordinate, and a `Feature` or a `GeometryCollection` is unwrapped rather than skipped. Sharing
 * the reader is what stops the two from parting company again.
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
    val radius = pointRadiusOf(params, context, type)
    return input.map { datum ->
      // `field` is an accessor path — `"datum.contour"` on a mark transform, where the row is the
      // scene item's own `datum`, and a plain column name on a dataset transform.
      val source = if (path == null) datum else datum.field(path)
      val sink = PathStringSink(digits = null)
      radius?.invoke(source)?.let { sink.pointRadius(it) }
      GeoJsonStream.stream(source, sink)
      // **Null**, not an empty string, when the geometry produced nothing: d3's path generator
      // returns null, and upstream's path mark measures a null path as `(0, 0, 0, 0)` where a
      // string that draws nothing leaves the bounds empty. The two look identical and are not.
      datum.withField(as0, sink.result()?.let { VegaValue.Str(it) } ?: VegaValue.Null)
    }
  }
}

/**
 * `pointRadius`: how large a dot a `Point` geometry is drawn as, for `geopath` and `geoshape`.
 *
 * Upstream declares it `'type': 'number', 'expr': true`, and the second half is not decoration: a
 * `{"expr": …}` becomes a **function**, and d3 calls it with whatever was passed to the path
 * generator — which is the geometry, not the row. `pointRadius: {"expr": "datum.r"}` over a column
 * of features therefore reads `r` off the *geometry object* and gets NaN unless the geometry
 * carries one, and that NaN is written into the path string rather than swallowed. Transcribed
 * because a plausible reading — the row is the datum — would put a number where upstream puts a
 * broken path, which is the difference between a chart that is wrong and one that is visibly wrong.
 *
 * Null means the specification named none, and the sink keeps d3's own default of 4.5.
 */
internal fun pointRadiusOf(
  params: VegaValue.Obj,
  context: TransformContext,
  type: String,
): ((VegaValue) -> Double)? {
  val written = params.fields["pointRadius"] ?: return null
  val expression = ((written as? VegaValue.Obj)?.fields?.get("expr") as? VegaValue.Str)?.value
  if (expression != null) {
    val compiled = TupleExpression(expression, context, type)
    if (!compiled.isUsable) return null
    // `+pointRadius.apply(…)`: coerced, so a geometry with no such field gives NaN rather than
    // falling back to the default.
    return { geometry -> JsSemantics.toNumber(compiled.evaluate(geometry) ?: VegaValue.Null) }
  }
  val fixed = params.number("pointRadius") ?: return null
  return { _ -> fixed }
}
