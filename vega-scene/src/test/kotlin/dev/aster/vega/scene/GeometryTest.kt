package dev.aster.vega.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeometryTest {

  private val tolerance = 1e-12

  @Test
  fun `empty rect is inverted so union behaves as an identity`() {
    assertTrue(RectD.Empty.isEmpty)
    val rect = RectD(1.0, 2.0, 3.0, 4.0)
    assertEquals(rect, RectD.Empty.union(rect))
    assertEquals(rect, rect.union(RectD.Empty))
    assertTrue(RectD.Empty.union(RectD.Empty).isEmpty)
  }

  @Test
  fun `empty rect contains nothing and intersects nothing`() {
    assertFalse(RectD.Empty.contains(0.0, 0.0))
    assertFalse(RectD.Empty.intersects(RectD(0.0, 0.0, 10.0, 10.0)))
  }

  @Test
  fun `fromSize normalizes negative extents`() {
    val upward = RectD.fromSize(x = 10.0, y = 100.0, width = 20.0, height = -40.0)
    assertEquals(RectD(10.0, 60.0, 30.0, 100.0), upward)

    val leftward = RectD.fromSize(x = 10.0, y = 10.0, width = -5.0, height = 5.0)
    assertEquals(RectD(5.0, 10.0, 10.0, 15.0), leftward)
  }

  @Test
  fun `zero-extent rect is not empty and contains its own edge`() {
    val degenerate = RectD(5.0, 5.0, 5.0, 5.0)
    assertFalse(degenerate.isEmpty)
    assertTrue(degenerate.contains(5.0, 5.0))
    assertEquals(0.0, degenerate.width)
  }

  @Test
  fun `normalized removes negative zero components`() {
    val rect = RectD(-0.0, -0.0, 1.0, 1.0).normalized()
    assertEquals(0L, rect.left.toRawBits())
    assertEquals(0L, rect.top.toRawBits())
  }

  @Test
  fun `expand does not resurrect an empty rect`() {
    assertTrue(RectD.Empty.expand(10.0).isEmpty)
  }

  @Test
  fun `transform concat applies the argument first`() {
    val translateThenScale = Transform2D.scale(2.0).concat(Transform2D.translate(10.0, 0.0))
    // Translate first: (0,0) -> (10,0), then scale by 2 -> (20,0).
    assertEquals(PointD(20.0, 0.0), translateThenScale.apply(0.0, 0.0))

    val scaleThenTranslate = Transform2D.translate(10.0, 0.0).concat(Transform2D.scale(2.0))
    // Scale first: (1,0) -> (2,0), then translate -> (12,0).
    assertEquals(PointD(12.0, 0.0), scaleThenTranslate.apply(1.0, 0.0))
  }

  @Test
  fun `invert round-trips a point`() {
    val transform =
      Transform2D.translate(30.0, -12.0)
        .concat(Transform2D.rotateDegrees(37.0))
        .concat(Transform2D.scale(2.0, 0.5))
    val inverse = requireNotNull(transform.invert())
    val original = PointD(7.5, -3.25)
    val roundTripped = inverse.apply(transform.apply(original))
    assertEquals(original.x, roundTripped.x, 1e-9)
    assertEquals(original.y, roundTripped.y, 1e-9)
  }

  @Test
  fun `singular transform reports null instead of pretending to be identity`() {
    assertNull(Transform2D.scale(0.0, 1.0).invert())
    assertNull(Transform2D(0.0, 0.0, 0.0, 0.0, 5.0, 5.0).invert())
  }

  @Test
  fun `mapBounds widens bounds under rotation`() {
    val unit = RectD(0.0, 0.0, 1.0, 1.0)
    val rotated = Transform2D.rotateDegrees(45.0).mapBounds(unit)
    val expectedHalfDiagonal = kotlin.math.sqrt(2.0)
    assertEquals(expectedHalfDiagonal, rotated.width, 1e-9)
    assertEquals(expectedHalfDiagonal, rotated.height, 1e-9)
  }

  @Test
  fun `mapBounds is a no-op for the identity transform`() {
    val rect = RectD(1.0, 2.0, 3.0, 4.0)
    assertEquals(rect, Transform2D.Identity.mapBounds(rect))
    assertTrue(Transform2D.rotateDegrees(30.0).mapBounds(RectD.Empty).isEmpty)
  }

  @Test
  fun `rotation by 90 degrees maps the x axis onto the y axis`() {
    val point = Transform2D.rotateDegrees(90.0).apply(1.0, 0.0)
    assertEquals(0.0, point.x, tolerance)
    assertEquals(1.0, point.y, tolerance)
  }
}
