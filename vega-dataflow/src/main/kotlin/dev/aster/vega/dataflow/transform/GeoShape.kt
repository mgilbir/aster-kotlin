package dev.aster.vega.dataflow.transform

import dev.aster.vega.dataflow.geo.AlbersUsa
import dev.aster.vega.dataflow.geo.GeoJsonStream
import dev.aster.vega.dataflow.geo.GeoProjector
import dev.aster.vega.dataflow.geo.Graticule
import dev.aster.vega.dataflow.geo.PathCentroidSink
import dev.aster.vega.dataflow.geo.PathStringSink
import dev.aster.vega.dataflow.geo.Projection
import dev.aster.vega.dataflow.geo.Projections
import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.field

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
  scale?.let { projection.scale(it) }
  if (translate.size >= 2) projection.translate(translate[0], translate[1])
  if (center.size >= 2) projection.center(center[0], center[1])
  if (rotate.isNotEmpty()) projection.rotate(rotate.toDoubleArray())
  angle?.let { projection.angle(it) }
  if (reflectX || reflectY) projection.reflect(reflectX, reflectY)
  precision?.let { projection.precision(it) }
  // Last: an explicit extent replaces whatever rule the projection family applied for itself.
  if (clipExtent.size >= 4) {
    projection.clipExtent(clipExtent[0], clipExtent[1], clipExtent[2], clipExtent[3])
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
    val path = params.string("field") ?: "datum"
    val radius = params.number("pointRadius")

    return input.map { item ->
      val sink = PathStringSink()
      radius?.let { sink.pointRadius(it) }
      GeoJsonStream.stream(item.field(path), projection.stream(sink))
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
    val sink = PathCentroidSink()
    val stream = definition?.build()?.stream(sink) ?: sink
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
}
