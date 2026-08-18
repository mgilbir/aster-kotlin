package dev.aster.vega.dataflow.geo

import dev.aster.vega.dataflow.transform.GeoMeasure
import dev.aster.vega.dataflow.transform.ProjectionDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Reading a point on the page back to a place on the globe, for the family that could not.
 *
 * `conicEqualArea` had no inverse here, and `albers` is that projection pointed at the United
 * States while `albersUsa` is three of them — which is the family Vega-Lite reaches for by default
 * on any chart of the United States. So nothing about such a map could answer where a point *was*:
 * not a click, not a brush asking what the middle of the plot inverts to.
 *
 * A **round trip** rather than recorded coordinates, and deliberately: an inverse checked against
 * numbers typed here is checked against my arithmetic, where one checked against the forward
 * direction is checked against the projection this engine has already verified path by path against
 * d3's own output. Nothing is asserted twice and the fixed point is upstream's.
 */
class ProjectionInverseTest {

  private fun roundTrip(type: String, longitude: Double, latitude: Double) {
    val definition = ProjectionDefinition(name = "p", type = type)
    val point =
      requireNotNull(GeoMeasure.project(definition, longitude, latitude)) {
        "$type placed nothing"
      }
    val place =
      requireNotNull(GeoMeasure.invert(definition, point[0], point[1])) {
        "$type read nothing back"
      }
    assertEquals(longitude, place[0], 1e-6, "$type longitude")
    assertEquals(latitude, place[1], 1e-6, "$type latitude")
  }

  @Test
  fun `a conic equal-area projection reads a point back to the place it came from`() {
    roundTrip("conicEqualArea", 12.5, 41.9)
    roundTrip("albers", -96.0, 39.0)
  }

  /** The cone degenerates to a cylinder for parallels symmetric about the equator. */
  @Test
  fun `the cylindrical case reads back too`() {
    val definition =
      ProjectionDefinition(name = "p", type = "conicEqualArea", parallels = listOf(-20.0, 20.0))
    val point = requireNotNull(GeoMeasure.project(definition, 30.0, 10.0))
    val place = requireNotNull(GeoMeasure.invert(definition, point[0], point[1]))
    assertEquals(30.0, place[0], 1e-6)
    assertEquals(10.0, place[1], 1e-6)
  }

  /**
   * A composite answers by asking whichever piece drew the point, which is d3's own rule.
   *
   * The insets are what make it worth its own case: a place in Alaska and one in Hawaii are drawn
   * into boxes at known offsets, and reading them back through the lower forty-eight would put both
   * somewhere in the Pacific.
   */
  @Test
  fun `albersUsa reads a point back through whichever piece drew it`() {
    roundTrip("albersUsa", -96.0, 39.0)
    roundTrip("albersUsa", -150.0, 63.0)
    roundTrip("albersUsa", -157.0, 21.0)
  }

  /** A projection with no closed form still says so rather than answering something plausible. */
  @Test
  fun `a projection without an inverse answers nothing`() {
    assertNull(GeoMeasure.invert(ProjectionDefinition(name = "p", type = "airy"), 100.0, 100.0))
  }
}
