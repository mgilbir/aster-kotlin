package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.field
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `geopath` with **no projection**, over the geometry types it used not to understand.
 *
 * Upstream is `geoPath()` with a null projection, which is still d3's full GeoJSON reader: a
 * `Point` is drawn as a circle of the current `pointRadius`, a `MultiPoint` as one circle per
 * coordinate, and a `Feature` or `GeometryCollection` is unwrapped to the geometry inside it. Only
 * polygons and line strings were handled here, so a specification handing this a column of points —
 * decoded TopoJSON, a `geojson` transform's output, anything that is not a contour — got a column
 * of nulls and drew nothing.
 *
 * Every string below is upstream's own output for the same geometry.
 *
 * One case is deliberately absent: a `Sphere` geometry. d3's path-string context has no `sphere`
 * method, so an unprojected `geopath` over one **throws** — `stream.sphere is not a function` — and
 * the whole view fails. There is no scene to agree with, so this draws nothing rather than
 * reproducing the crash.
 */
class GeoPathGeometryTest {

  private class Context : TransformContext {
    override var tree: TreeSource? = null
    override val diagnostics = DiagnosticCollector()
    override val expressions: ExpressionCompiler =
      CachingExpressionCompiler(VegaExpressionCompiler())
    override val scope: ExpressionScope = scopeFor(VegaValue.Null)

    override fun setSignal(name: String, value: VegaValue) = Unit

    override fun scopeFor(datum: VegaValue): ExpressionScope =
      object : ExpressionScope {
        override val datum: VegaValue = datum

        override fun signal(name: String): VegaValue = VegaValue.Null

        override fun dataset(name: String): List<VegaValue> = emptyList()
      }
  }

  /** The path each row is written, running `geopath` over a column named `g`. */
  private fun paths(rows: String, params: String = """{"field": "g", "as": "p"}"""): List<String?> {
    val context = Context()
    val out =
      GeoPathTransform.apply(
        (VegaJson.parse(rows) as VegaValue.Arr).values,
        VegaJson.parse(params) as VegaValue.Obj,
        context,
      )
    assertEquals(emptyList<String>(), context.diagnostics.diagnostics.map { it.message })
    return out.map { (it.field("p") as? VegaValue.Str)?.value }
  }

  @Test
  fun `a point is a circle of the default radius`() {
    assertEquals(
      listOf("M1,2m0,4.5a4.5,4.5 0 1,1 0,-9a4.5,4.5 0 1,1 0,9z"),
      paths("""[{"g": {"type": "Point", "coordinates": [1, 2]}}]"""),
    )
  }

  @Test
  fun `a multipoint is one circle per coordinate`() {
    assertEquals(
      listOf(
        "M1,2m0,4.5a4.5,4.5 0 1,1 0,-9a4.5,4.5 0 1,1 0,9z" +
          "M3,4m0,4.5a4.5,4.5 0 1,1 0,-9a4.5,4.5 0 1,1 0,9z"
      ),
      paths("""[{"g": {"type": "MultiPoint", "coordinates": [[1, 2], [3, 4]]}}]"""),
    )
  }

  @Test
  fun `a feature and a geometry collection are unwrapped`() {
    assertEquals(
      listOf("M0,0L5,5", "M9,9m0,4.5a4.5,4.5 0 1,1 0,-9a4.5,4.5 0 1,1 0,9z"),
      paths(
        """[{"g": {"type": "Feature",
                   "geometry": {"type": "LineString", "coordinates": [[0, 0], [5, 5]]}}},
            {"g": {"type": "GeometryCollection",
                   "geometries": [{"type": "Point", "coordinates": [9, 9]}]}}]"""
      ),
    )
  }

  @Test
  fun `polygons keep the shape they had`() {
    // The cases that already worked, kept so the shared reader cannot quietly change them: the
    // repeated closing point is dropped and `Z` says it instead.
    assertEquals(
      listOf("M0,0L4,0L4,4Z", "M0,0L4,0L4,4ZM9,9L8,8L7,9Z", "M0,0L2,3"),
      paths(
        """[{"g": {"type": "Polygon", "coordinates": [[[0, 0], [4, 0], [4, 4], [0, 0]]]}},
            {"g": {"type": "MultiPolygon",
                   "coordinates": [[[[0, 0], [4, 0], [4, 4], [0, 0]]],
                                   [[[9, 9], [8, 8], [7, 9], [9, 9]]]]}},
            {"g": {"type": "LineString", "coordinates": [[0, 0], [2, 3]]}}]"""
      ),
    )
  }

  @Test
  fun `the row itself is the geometry when no field is named`() {
    assertEquals(
      listOf("M0,0L2,3"),
      paths(
        """[{"type": "LineString", "coordinates": [[0, 0], [2, 3]]}]""",
        """{"as": "p"}""",
      ),
    )
  }

  @Test
  fun `pointRadius sizes the circle`() {
    assertEquals(
      listOf("M1,2m0,10a10,10 0 1,1 0,-20a10,10 0 1,1 0,20z"),
      paths(
        """[{"g": {"type": "Point", "coordinates": [1, 2]}}]""",
        """{"field": "g", "as": "p", "pointRadius": 10}""",
      ),
    )
  }

  @Test
  fun `a pointRadius expression reads the geometry, not the row`() {
    // d3 calls the function with whatever was handed to the path generator, which is the geometry.
    // The first row's geometry carries an `r` and the second row's does not — the second's `r` is
    // on the row, where nothing looks — so upstream writes a NaN-riddled path for it rather than
    // falling back to the default radius.
    assertEquals(
      listOf(
        "M1,2m0,6a6,6 0 1,1 0,-12a6,6 0 1,1 0,12z",
        "M1,2m0,NaNaNaN,NaN 0 1,1 0,NaNaNaN,NaN 0 1,1 0,NaNz",
      ),
      paths(
        """[{"g": {"type": "Point", "coordinates": [1, 2], "r": 6}},
            {"r": 7, "g": {"type": "Point", "coordinates": [1, 2]}}]""",
        """{"field": "g", "as": "p", "pointRadius": {"expr": "datum.r"}}""",
      ),
    )
  }
}
