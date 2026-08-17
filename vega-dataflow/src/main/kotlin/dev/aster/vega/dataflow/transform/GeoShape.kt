package dev.aster.vega.dataflow.transform

import dev.aster.vega.dataflow.geo.AlbersUsa
import dev.aster.vega.dataflow.geo.GeoJsonStream
import dev.aster.vega.dataflow.geo.GeoProjector
import dev.aster.vega.dataflow.geo.Graticule
import dev.aster.vega.dataflow.geo.PathAreaSink
import dev.aster.vega.dataflow.geo.PathBoundsSink
import dev.aster.vega.dataflow.geo.PathCentroidSink
import dev.aster.vega.dataflow.geo.PathStringSink
import dev.aster.vega.dataflow.geo.Projection
import dev.aster.vega.dataflow.geo.Projections
import dev.aster.vega.dataflow.geo.SphericalMeasure
import dev.aster.vega.expression.JsSemantics
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.field
import dev.aster.vega.model.isMissing

/**
 * A projection with every signal in it already resolved, as a transform receives it.
 *
 * The projection *machinery* is private to this module — it is a stream pipeline, not a value — so
 * what crosses the boundary is the description. The runtime resolves the signals, because that is
 * where signals live; this builds the projection, because that is where d3-geo lives.
 */
public data class ProjectionDefinition(
  val name: String,
  val type: String,
  val scale: Double? = null,
  val translate: List<Double> = emptyList(),
  val center: List<Double> = emptyList(),
  val rotate: List<Double> = emptyList(),
  val angle: Double? = null,
  val precision: Double? = null,
  val reflectX: Boolean = false,
  val reflectY: Boolean = false,
  val clipExtent: List<Double> = emptyList(),
  val clipAngle: Double? = null,
  /** The two standard parallels of a conic projection, which rebuild its raw formula. */
  val parallels: List<Double> = emptyList(),
  /** The radius a `Point` geometry draws as; `null` leaves d3's 4.5. */
  val pointRadius: Double? = null,
  /**
   * The geometry the projection is scaled and centred to cover, `fit`.
   *
   * A projection fitted to its data has no `scale` or `translate` of its own worth speaking of:
   * both are computed from where the geometry lands, which means the projection cannot be built
   * until the data it fits has been loaded. That ordering is the whole reason this is a property of
   * the definition rather than something the compiler could apply once.
   */
  val fit: VegaValue? = null,
  /** `[[x0, y0], [x1, y1]]` flattened — the rectangle [fit] is made to fill. */
  val fitExtent: List<Double> = emptyList(),
  /** `[width, height]` — the same thing anchored at the origin. */
  val fitSize: List<Double> = emptyList(),
)

/** Builds the projection a definition describes, or null for a type this engine does not have. */
internal fun ProjectionDefinition.build(): GeoProjector? {
  // A composite takes only the properties it exposes. Upstream has the same rule and states it the
  // same way — `isFunction(proj[prop])` — so a `rotate` on an `albersUsa` is ignored there too.
  Projections.compositeByName(type)?.let { composite ->
    if (composite is AlbersUsa) {
      scale?.let { composite.scale(it) }
      if (translate.size >= 2) composite.translate(translate[0], translate[1])
      precision?.let { composite.precision(it) }
    }
    return composite
  }
  val projection = Projections.byName(type) ?: return null
  // Upstream's own order, `vega-projection`'s `projectionProperties`. It matters in two places:
  // `parallels` rebuilds the conic raw projection, so it has to come before `precision` reads the
  // resampling threshold off the built one; and an explicit `clipExtent` has to be applied before
  // `fit`, which reads it, clears it and puts it back.
  clipAngle?.let { projection.clipAngle(it) }
  if (clipExtent.size >= 4) {
    projection.clipExtent(clipExtent[0], clipExtent[1], clipExtent[2], clipExtent[3])
  }
  scale?.let { projection.scale(it) }
  if (translate.size >= 2) projection.translate(translate[0], translate[1])
  if (center.size >= 2) projection.center(center[0], center[1])
  if (rotate.isNotEmpty()) projection.rotate(rotate.toDoubleArray())
  if (parallels.size >= 2) projection.parallels(parallels[0], parallels[1])
  angle?.let { projection.angle(it) }
  if (reflectX || reflectY) projection.reflect(reflectX, reflectY)
  precision?.let { projection.precision(it) }
  fit?.let { features ->
    when {
      fitExtent.size >= 4 ->
        projection.fitExtent(fitExtent[0], fitExtent[1], fitExtent[2], fitExtent[3], features)
      fitSize.size >= 2 -> projection.fitExtent(0.0, 0.0, fitSize[0], fitSize[1], features)
      // Upstream's own reading: `fit` with neither an `extent` nor a `size` does nothing at all.
      else -> Unit
    }
  }
  return projection
}

/**
 * `geoshape`: a GeoJSON feature drawn through a projection.
 *
 * Upstream writes a *generator function* onto the item and lets the renderer call it with a canvas
 * or an SVG context. There is no such indirection here — a scene node holds a parsed path — so what
 * is written is the path string itself, which is what upstream's SVG renderer would have produced.
 *
 * The default field is the item's `datum`: a `shape` mark is drawn straight from a row that *is* a
 * GeoJSON feature, which is what the TopoJSON loader produces.
 */
public object GeoShapeTransform : Transform {
  override val type: String = "geoshape"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> = apply(input, params, context, defaultField = "datum")

  /**
   * @param defaultField where the geometry is when the specification names no `field`.
   *
   * `"datum"` for `geoshape`, which runs over scene items and reaches for the row inside one.
   * **Null** for `geopath`, whose default is the row *itself* — upstream declares no default for it
   * and falls back to the identity accessor. A dataset of decoded TopoJSON features is exactly that
   * case, and reading a `datum` column that is not there leaves every state undrawn.
   */
  internal fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
    defaultField: String?,
  ): List<VegaValue> {
    val as0 = params.string("as") ?: "shape"
    val name = params.string("projection")
    if (name == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "geoshape needs a 'projection'; without one there is nothing to place the geometry on",
        operator = type,
      )
      return input
    }
    val definition = context.projection(name)
    if (definition == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "geoshape names projection '$name', which this scope does not define",
        operator = type,
      )
      return input
    }
    val projection = definition.build()
    if (projection == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
        "Projection type '${definition.type}' is not implemented, so projection " +
          "'$name' placed nothing. Implemented: ${Projections.names.sorted().joinToString(", ")}",
        operator = type,
      )
      return input
    }
    val path = params.string("field") ?: defaultField
    // The mark's own `pointRadius` wins; otherwise the projection's, which upstream sets on the
    // path generator the projection carries. Either way a projected city is a dot of that radius.
    val radius = params.number("pointRadius") ?: definition.pointRadius

    return input.map { item ->
      val sink = PathStringSink(digits = null)
      radius?.let { sink.pointRadius(it) }
      GeoJsonStream.stream(if (path == null) item else item.field(path), projection.stream(sink))
      val drawn = sink.result()
      // Null, not an empty string: upstream's path generator returns null when nothing was drawn,
      // and a mark measures a null path as a point rather than as empty bounds.
      item.withField(as0, if (drawn == null) VegaValue.Null else VegaValue.Str(drawn))
    }
  }
}

/**
 * `graticule`: the grid of meridians and parallels a map is drawn over.
 *
 * A **generator**, so it ignores its input entirely and publishes one row — a single
 * `MultiLineString` holding every line. That is upstream's shape too, and it matters downstream:
 * the whole grid is one geometry, so a `geoshape` over it produces one path and one mark.
 */
public object GraticuleTransform : Transform {
  override val type: String = "graticule"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val graticule = Graticule()
    params
      .numberList("extent")
      .takeIf { it.size >= 4 }
      ?.let {
        graticule.extentMajor(it[0], it[1], it[2], it[3])
        graticule.extentMinor(it[0], it[1], it[2], it[3])
      }
    pairs(params, "extentMajor")?.let { graticule.extentMajor(it[0], it[1], it[2], it[3]) }
    pairs(params, "extentMinor")?.let { graticule.extentMinor(it[0], it[1], it[2], it[3]) }
    params
      .numberList("step")
      .takeIf { it.size >= 2 }
      ?.let {
        graticule.stepMajor(it[0], it[1])
        graticule.stepMinor(it[0], it[1])
      }
    params
      .numberList("stepMajor")
      .takeIf { it.size >= 2 }
      ?.let {
        graticule.stepMajor(it[0], it[1])
      }
    params
      .numberList("stepMinor")
      .takeIf { it.size >= 2 }
      ?.let {
        graticule.stepMinor(it[0], it[1])
      }
    params.number("precision")?.let { graticule.precision(it) }
    return listOf(graticule.multiLineString())
  }

  /** An extent written as `[[x0, y0], [x1, y1]]`, flattened. */
  private fun pairs(params: VegaValue.Obj, key: String): DoubleArray? {
    val outer = params.fields[key] as? VegaValue.Arr ?: return null
    if (outer.values.size < 2) return null
    val first = outer.values[0] as? VegaValue.Arr ?: return null
    val second = outer.values[1] as? VegaValue.Arr ?: return null
    if (first.values.size < 2 || second.values.size < 2) return null
    return doubleArrayOf(
      first.values[0].asDouble(),
      first.values[1].asDouble(),
      second.values[0].asDouble(),
      second.values[1].asDouble(),
    )
  }
}

/**
 * The measurements a specification asks for with an expression rather than a transform.
 *
 * `geoCentroid(projection, feature)` is how a chart labels a country: it places the label where the
 * shape's *area* balances, not where its bounding box does, so a label on Norway lands on the
 * mainland rather than out at sea between it and Svalbard.
 */
public object GeoMeasure {

  /**
   * The centroid of a geometry, in projected coordinates, or null if it has no area.
   *
   * d3 measures three things at once and reports the highest-dimensional one that exists: the
   * area-weighted centroid of the polygons, else the length-weighted centroid of the lines, else
   * the mean of the bare points. That order is why a `MultiPolygon` with one degenerate part still
   * balances on the part that has area.
   */
  public fun centroid(definition: ProjectionDefinition?, geojson: VegaValue): DoubleArray? {
    // No projection is not "the identity projection": upstream's `geoMethod` calls d3's *spherical*
    // centroid, which is a different measurement on a different surface.
    if (definition == null) return SphericalMeasure.centroid(geojson)
    val sink = PathCentroidSink()
    val stream = definition.build()?.stream(sink) ?: sink
    GeoJsonStream.stream(geojson, stream)
    return sink.result()
  }

  /**
   * A point on the page read back to longitude and latitude.
   *
   * `invert('projection', p)` — how a map turns where someone clicked into where on Earth that is.
   * Null for a projection with no closed-form inverse, and for the composite ones, which are three
   * projections and have no single answer.
   */
  public fun invert(definition: ProjectionDefinition, x: Double, y: Double): DoubleArray? =
    (definition.build() as? Projection)?.invert(x, y)

  /**
   * The area a geometry covers once drawn, in square units of the page.
   *
   * What a cartogram sizes its circles by. Each ring counts by the absolute value of its own signed
   * area, so a hole adds rather than subtracts — upstream's choice, and the one a chart calibrated
   * against it expects.
   */
  public fun area(definition: ProjectionDefinition?, geojson: VegaValue): Double {
    // Steradians on the globe, not square units of a page that was never drawn.
    if (definition == null) return SphericalMeasure.area(geojson)
    val sink = PathAreaSink()
    val stream = definition.build()?.stream(sink) ?: sink
    GeoJsonStream.stream(geojson, stream)
    return sink.result()
  }

  /**
   * The box a geometry occupies once drawn, as `[x0, y0, x1, y1]`, or null when it draws nothing.
   *
   * Measured through the projection's own stream, so the answer already carries whatever clipping
   * and resampling the projection does — a feature cut at the antimeridian is bounded by the part
   * that survived, not by the part that was asked for.
   */
  public fun bounds(definition: ProjectionDefinition?, geojson: VegaValue): DoubleArray? {
    // On the globe, where longitude wraps: two islands at ±179° are two degrees apart, and the box
    // that says otherwise spans the Pacific the wrong way round.
    if (definition == null) return SphericalMeasure.bounds(geojson)
    val sink = PathBoundsSink()
    val stream = definition.build()?.stream(sink) ?: sink
    GeoJsonStream.stream(geojson, stream)
    return sink.result()
  }

  /** A projection's own scale, `geoScale('name')`. */
  /**
   * `geoShape(projection, feature)` — the feature drawn through the projection, as an SVG path.
   *
   * The same machinery the `geoshape` transform uses, asked for one feature instead of a column of
   * them. Null where nothing was drawn, which is upstream's path generator returning null rather
   * than an empty string, and a projection that could not be built draws nothing at all.
   */
  public fun shape(definition: ProjectionDefinition?, geojson: VegaValue): String? {
    val projection = definition?.build() ?: return null
    val sink = PathStringSink(digits = null)
    definition.pointRadius?.let { sink.pointRadius(it) }
    GeoJsonStream.stream(geojson, projection.stream(sink))
    return sink.result()
  }

  public fun scaleOf(definition: ProjectionDefinition): Double? =
    (definition.build() as? Projection)?.scale
}

/**
 * `geopoint`: a longitude and a latitude placed on the page.
 *
 * The whole of what a projection does to a *point*, with none of what it does to a shape: no
 * clipping, no resampling, no path. A city on a map is one of these; the country under it is a
 * `geoshape`. Writing nothing where the point falls outside the projection is upstream's own
 * behaviour, and it matters — a mark encoded from a missing `x` draws at the origin rather than
 * being left out, which is visible as a cluster of points in the top-left corner.
 */
/**
 * `geojson`: gathers rows into one GeoJSON `FeatureCollection` and publishes it as a signal.
 *
 * The data passes through untouched — this transform exists for its **value**, which is what a
 * projection's `fit` reads. It is the ordinary way to fit a projection to a table of coordinates:
 * `fields` names a longitude and a latitude column and every row becomes one point of a single
 * `MultiPoint` feature, so the fit sees the whole cloud as one geometry rather than as one feature
 * per row.
 *
 * `geojson` names a column already holding a feature, and the two combine: the features come first
 * and the `MultiPoint` built from the coordinate columns is appended after them, which is
 * upstream's order and matters because `fit` walks the collection in order.
 *
 * A row whose longitude or latitude is missing or unparseable is left out of the point list rather
 * than contributing a `NaN` — upstream tests both with `(x = +x) === x`, which rejects a `null`, an
 * empty string and anything non-numeric alike.
 */
public object GeoJsonTransform : Transform {
  override val type: String = "geojson"

  /** The collection itself, which is the only thing this transform produces. */
  override val publishesSignal: Boolean = true

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val fields = params.stringList("fields")
    val geojson = params.string("geojson")
    if (geojson == null && fields.size < 2) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "geojson needs 'geojson' naming a feature column, or 'fields' naming a longitude and a " +
          "latitude",
        operator = type,
      )
      return input
    }

    val features = mutableListOf<VegaValue>()
    // With neither parameter upstream falls back to the identity accessor, so each row *is* the
    // feature. That case cannot be reached here: the guard above requires one or the other.
    if (geojson != null) {
      for (row in input) features += row.field(geojson)
    }
    if (fields.size >= 2) {
      val points = input.mapNotNull { row ->
        val lon = row.field(fields[0])
        val lat = row.field(fields[1])
        if (lon.isMissing || lat.isMissing) return@mapNotNull null
        val x = JsSemantics.toNumber(lon)
        val y = JsSemantics.toNumber(lat)
        if (!x.isFinite() || !y.isFinite()) null
        else VegaValue.Arr(listOf(VegaValue.Num(x), VegaValue.Num(y)))
      }
      features +=
        VegaValue.Obj(
          linkedMapOf(
            "type" to VegaValue.Str("Feature"),
            "geometry" to
              VegaValue.Obj(
                linkedMapOf(
                  "type" to VegaValue.Str("MultiPoint"),
                  "coordinates" to VegaValue.Arr(points),
                )
              ),
          )
        )
    }

    val signal = params.string("signal")
    if (signal != null) {
      context.setSignal(
        signal,
        VegaValue.Obj(
          linkedMapOf(
            "type" to VegaValue.Str("FeatureCollection"),
            "features" to VegaValue.Arr(features),
          )
        ),
      )
    }
    return input
  }
}

public object GeoPointTransform : Transform {
  override val type: String = "geopoint"

  override fun apply(
    input: List<VegaValue>,
    params: VegaValue.Obj,
    context: TransformContext,
  ): List<VegaValue> {
    val fields = params.stringList("fields")
    if (fields.size < 2) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "geopoint needs 'fields' naming a longitude and a latitude",
        operator = type,
      )
      return input
    }
    val outputs = params.stringList("as").takeIf { it.size >= 2 } ?: listOf("x", "y")
    val name = params.string("projection")
    val definition = name?.let { context.projection(it) }
    if (definition == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_INVALID_PARAMETER,
        "geopoint names projection '${name ?: ""}', which this scope does not define",
        operator = type,
      )
      return input
    }
    val projection = definition.build()
    if (projection == null) {
      context.diagnostics.error(
        DiagnosticCodes.TRANSFORM_NOT_IMPLEMENTED,
        "Projection type '${definition.type}' is not implemented, so projection " +
          "'$name' placed nothing. Implemented: ${Projections.names.sorted().joinToString(", ")}",
        operator = type,
      )
      return input
    }

    // Each coordinate is coerced the way `+value` coerces it and the pair is projected whatever
    // comes out, which is upstream's `proj([lon(t), lat(t)])` and not the same as skipping the row:
    // a **null** longitude is `+null`, which is zero, so the row is placed on the prime meridian; a
    // longitude of `"west"` is `NaN`, and for a projection whose two coordinates are independent
    // that leaves `x` unusable while `y` still lands where the latitude says. Rejecting the pair up
    // front put both at the origin, which drew a point nothing in the data asked for.
    return input.map { row ->
      val lon = JsSemantics.toNumber(row.field(fields[0]))
      val lat = JsSemantics.toNumber(row.field(fields[1]))
      val placed = projection.apply(lon, lat)
      row.withFields(
        linkedMapOf(
          outputs[0] to (placed?.let { VegaValue.Num(it[0]) } ?: VegaValue.Null),
          outputs[1] to (placed?.let { VegaValue.Num(it[1]) } ?: VegaValue.Null),
        )
      )
    }
  }
}
