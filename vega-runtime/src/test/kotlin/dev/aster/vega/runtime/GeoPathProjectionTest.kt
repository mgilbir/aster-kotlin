package dev.aster.vega.runtime

import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.PathNode
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.SceneNode
import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.tan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `geopath` through a **projection**, which `SUPPORTED_FEATURES.md` said was refused.
 *
 * The row read: "GeoJSON written out as an SVG path, with **no projection** … A `projection`
 * parameter is refused and says why." Both halves are wrong now. The parameter is accepted, the
 * projection is applied, and there is no diagnostic — an adopter reading that row would conclude
 * this engine cannot draw a map, which is a long way from the truth and the most expensive kind of
 * stale claim: nobody files a bug for a feature that works.
 *
 * The expected numbers are **d3-geo's own arithmetic**, worked by hand rather than copied from the
 * output, so this checks the projection rather than restating it.
 */
class GeoPathProjectionTest {

  /**
   * A diagonal across the equator, chosen so both axes move and neither is symmetric about zero.
   */
  private fun spec(projection: String) =
    """
    {"width": 200, "height": 200, "padding": 0, "autosize": "none",
     "projections": [{"name": "p", "type": "mercator", "scale": 50, "translate": [100, 100]}],
     "data": [{"name": "t",
               "values": [{"g": {"type": "LineString", "coordinates": [[-30, -20], [30, 20]]}}],
               "transform": [{"type": "geopath", "field": "g"$projection}]}],
     "marks": [{"type": "path", "from": {"data": "t"},
                "encode": {"enter": {"stroke": {"value": "#000000"}}}}]}
    """
      .trimIndent()

  private fun outlines(json: String): Pair<List<RectD>, List<String>> {
    val compiled = SpecCompiler().compileJson(json)
    val boxes = mutableListOf<RectD>()
    fun walk(node: SceneNode) {
      if (node is PathNode) boxes += node.path.bounds
      if (node is GroupNode) node.children.forEach { walk(it) }
    }
    compiled.scene?.root?.let { walk(it) }
    return boxes to compiled.diagnostics.map { it.message }
  }

  /** d3's spherical Mercator: `x = λ`, `y = ln(tan(π/4 + φ/2))`, then scaled and translated. */
  private fun mercator(lonDegrees: Double, latDegrees: Double): Pair<Double, Double> {
    val lambda = lonDegrees * PI / 180
    val phi = latDegrees * PI / 180
    val x = 100 + 50 * lambda
    val y = 100 - 50 * ln(tan(PI / 4 + phi / 2))
    return x to y
  }

  @Test
  fun `a projection is applied rather than refused`() {
    val (boxes, diagnostics) = outlines(spec(""","projection": "p""""))
    assertEquals(emptyList<String>(), diagnostics, "a named projection was reported as a problem")
    assertEquals(1, boxes.size, "the geopath produced no outline")

    val (left, bottom) = mercator(-30.0, -20.0)
    val (right, top) = mercator(30.0, 20.0)
    val box = boxes.single()
    assertEquals(left, box.left, 1e-6)
    assertEquals(right, box.right, 1e-6)
    assertEquals(top, box.top, 1e-6)
    assertEquals(bottom, box.bottom, 1e-6)
  }

  /**
   * With **no** projection the coordinates pass through unchanged, which is upstream's own
   * fallback.
   *
   * The other half of the row, and the half that is still true: d3's path generator with a null
   * projection writes the numbers it was given. It is what makes `geopath` right for a contour over
   * a raster grid, whose coordinates are already in chart units — `contour-plot` and
   * `volcano-contours` both rely on it.
   */
  @Test
  fun `without a projection the coordinates are written as given`() {
    val (boxes, diagnostics) = outlines(spec(""))
    assertEquals(emptyList<String>(), diagnostics)
    val box = boxes.single()
    assertEquals(-30.0, box.left, 1e-9)
    assertEquals(30.0, box.right, 1e-9)
    assertEquals(-20.0, box.top, 1e-9)
    assertEquals(20.0, box.bottom, 1e-9)
  }

  /**
   * The two differ, which is the guard that keeps both tests above from passing vacuously.
   *
   * If the projection were quietly ignored — the behaviour the row described — the first test would
   * fail on its numbers, but a future change that dropped projections *and* relaxed those numbers
   * would still have to get past this.
   */
  @Test
  fun `projecting moves the outline`() {
    val projected = outlines(spec(""","projection": "p"""")).first.single()
    val plain = outlines(spec("")).first.single()
    assertTrue(
      projected != plain,
      "the projected outline is identical to the unprojected one, so nothing was applied",
    )
  }

  /**
   * A projection named but never declared is reported, rather than silently drawing unprojected.
   */
  @Test
  fun `a projection that does not exist is reported`() {
    val (_, diagnostics) = outlines(spec(""","projection": "nowhere""""))
    assertTrue(
      diagnostics.any { "nowhere" in it },
      "an undeclared projection drew without complaint: $diagnostics",
    )
  }
}
