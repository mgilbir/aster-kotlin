@file:OptIn(dev.aster.vega.model.InternalAsterVegaApi::class)

package dev.aster.vega.dataflow.transform

import dev.aster.vega.expression.CachingExpressionCompiler
import dev.aster.vega.expression.ExpressionCompiler
import dev.aster.vega.expression.ExpressionScope
import dev.aster.vega.expression.VegaExpressionCompiler
import dev.aster.vega.model.DiagnosticCollector
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asString
import dev.aster.vega.model.field
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * `voronoi` where the points **coincide**, which is not the curiosity it sounds like.
 *
 * ```js
 * // degenerate case: 1 or 2 (distinct) points
 * this.triangles = new Int32Array(3).fill(-1);
 * ```
 *
 * One or two distinct points have no triangle, and the triangulation `Delaunay` builds for that
 * case carries `-1` where a vertex index would be. Upstream then reads past the front of its
 * coordinate array, gets `undefined`, and every circumcentre comes out `NaN` — which nothing reads,
 * because such a cell is taken from the clip box instead. Reading a negative index is not free in
 * Kotlin: it threw, the whole compile was caught as a defect, and the chart was not drawn at all.
 *
 * A scatter plot reaches this whenever its points collapse — a plotting area fitted to nothing, a
 * scale whose domain excludes all the data, a table of one row. One of the sixty-three Deneb
 * templates does exactly that, its `domainMax` of 10 sitting under data that runs to 90: upstream
 * draws twelve dots at the origin and twelve empty cells, and this engine drew nothing whatever.
 *
 * Every expectation is `d3-delaunay` and `vega-voronoi`'s own `toPathString` on the same points.
 */
class VoronoiDegenerateTest {

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

  /** Each row's cell, as the path string the transform writes, or null where it writes none. */
  private fun cells(points: String, size: String = "[100, 50]"): List<String?> {
    val rows = (VegaJson.parse(points) as VegaValue.Arr).values
    val params = VegaJson.parse("""{"x": "x", "y": "y", "size": $size}""") as VegaValue.Obj
    val context = Context()
    val out = VoronoiTransform.apply(rows, params, context)
    assertEquals(emptyList<String>(), context.diagnostics.diagnostics.map { it.message })
    return out.map { (it.field("path") as? VegaValue.Str)?.value }
  }

  /** Four rows on one spot: the first cell is the whole box and the rest have none. */
  @Test
  fun `points on one spot leave one cell covering the box`() {
    assertEquals(
      listOf("M100,0L100,50L0,50L0,0Z", null, null, null),
      cells("""[{"x":5,"y":5},{"x":5,"y":5},{"x":5,"y":5},{"x":5,"y":5}]"""),
    )
  }

  /**
   * The shape the Deneb template reaches: the points coincide **and** the box has collapsed with
   * them, so even the one cell is a point and upstream writes no path for it either.
   */
  @Test
  fun `a collapsed box leaves no cell at all`() {
    assertEquals(
      listOf(null, null, null, null),
      cells("""[{"x":0,"y":0},{"x":0,"y":0},{"x":0,"y":0},{"x":0,"y":0}]""", size = "[0, 0]"),
    )
  }

  /** Two distinct points, each doubled: the box is halved and the duplicates have no cell. */
  @Test
  fun `two distinct points split the box between them`() {
    assertEquals(
      listOf(
        "M0,50L0,0L59.375,0L50,25L40.625,50Z",
        null,
        "M100,0L100,50L40.625,50L50,25L59.375,0Z",
        null,
      ),
      cells("""[{"x":10,"y":10},{"x":10,"y":10},{"x":90,"y":40},{"x":90,"y":40}]"""),
    )
  }

  /**
   * And a single duplicate among points that do triangulate, which is the common case: the
   * duplicate is the one without a cell and the others are unaffected by it.
   */
  @Test
  fun `a duplicate among ordinary points costs only its own cell`() {
    assertEquals(
      listOf(
        "M54.0625,0L10.3125,50L0,50L0,0Z",
        null,
        "M100,0L100,50L70.9375,50L64.6875,0Z",
        "M10.3125,50L54.0625,0L64.6875,0L70.9375,50Z",
      ),
      cells("""[{"x":10,"y":10},{"x":10,"y":10},{"x":90,"y":40},{"x":50,"y":45}]"""),
    )
  }

  /** The rows keep everything else they carried; only `path` is added. */
  @Test
  fun `the rows are otherwise untouched`() {
    val rows =
      (VegaJson.parse("""[{"x":0,"y":0,"c":"a"},{"x":0,"y":0,"c":"b"}]""") as VegaValue.Arr).values
    val params = VegaJson.parse("""{"x": "x", "y": "y", "size": [0, 0]}""") as VegaValue.Obj
    val out = VoronoiTransform.apply(rows, params, Context())
    assertEquals(listOf("a", "b"), out.map { it.field("c").asString() })
  }
}
