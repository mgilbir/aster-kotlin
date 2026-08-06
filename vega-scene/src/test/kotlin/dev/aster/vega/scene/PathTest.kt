package dev.aster.vega.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PathTest {

  @Test
  fun `empty path has empty bounds and no subpaths`() {
    assertTrue(PathData.Empty.isEmpty)
    assertTrue(PathData.Empty.bounds.isEmpty)
    assertEquals(emptyList<List<PointD>>(), PathData.Empty.flatten())
    assertEquals(Double.POSITIVE_INFINITY, PathData.Empty.distanceToOutline(PointD.Origin))
  }

  @Test
  fun `cubic bounds are tight rather than the control hull`() {
    // A curve whose control points reach y = 3 but which only reaches y = 2.25.
    val path = PathData.build {
      moveTo(0.0, 0.0)
      cubicTo(0.0, 3.0, 10.0, 3.0, 10.0, 0.0)
    }
    assertEquals(0.0, path.bounds.left, 1e-9)
    assertEquals(10.0, path.bounds.right, 1e-9)
    assertEquals(0.0, path.bounds.top, 1e-9)
    assertEquals(2.25, path.bounds.bottom, 1e-9)
  }

  @Test
  fun `quadratic is elevated to an exact cubic`() {
    val quadratic = PathData.build {
      moveTo(0.0, 0.0)
      quadraticTo(5.0, 10.0, 10.0, 0.0)
    }
    // The apex of this quadratic sits at y = 5.
    assertEquals(5.0, quadratic.bounds.bottom, 1e-9)
    assertTrue(quadratic.commands[1] is PathCommand.CubicTo)
  }

  @Test
  fun `circle bounds match the requested radius`() {
    val circle = PathData.build { circle(10.0, 20.0, 4.0) }
    assertEquals(6.0, circle.bounds.left, 1e-6)
    assertEquals(14.0, circle.bounds.right, 1e-6)
    assertEquals(16.0, circle.bounds.top, 1e-6)
    assertEquals(24.0, circle.bounds.bottom, 1e-6)
  }

  @Test
  fun `rect normalizes negative extents`() {
    val path = PathData.build { rect(10.0, 10.0, -5.0, -5.0) }
    assertEquals(RectD(5.0, 5.0, 10.0, 10.0), path.bounds)
  }

  @Test
  fun `even-odd containment leaves a hole in a ring`() {
    val ring = PathData.build {
      rect(0.0, 0.0, 20.0, 20.0)
      rect(5.0, 5.0, 10.0, 10.0)
    }
    assertTrue(ring.containsEvenOdd(PointD(2.0, 2.0)), "outer band should be inside")
    assertFalse(ring.containsEvenOdd(PointD(10.0, 10.0)), "inner square should be a hole")
    assertFalse(ring.containsEvenOdd(PointD(-1.0, -1.0)), "outside should miss")
  }

  @Test
  fun `containment rejects points outside the bounds without flattening`() {
    val square = PathData.build { rect(0.0, 0.0, 10.0, 10.0) }
    assertFalse(square.containsEvenOdd(PointD(1000.0, 1000.0)))
  }

  @Test
  fun `distance to outline measures from the nearest segment`() {
    val square = PathData.build { rect(0.0, 0.0, 10.0, 10.0) }
    assertEquals(0.0, square.distanceToOutline(PointD(0.0, 5.0)), 1e-9)
    assertEquals(3.0, square.distanceToOutline(PointD(-3.0, 5.0)), 1e-9)
    // A point at the centre is 5 away from every edge.
    assertEquals(5.0, square.distanceToOutline(PointD(5.0, 5.0)), 1e-9)
  }

  @Test
  fun `flatten produces one polyline per subpath`() {
    val twoSquares = PathData.build {
      rect(0.0, 0.0, 5.0, 5.0)
      rect(10.0, 10.0, 5.0, 5.0)
    }
    val subpaths = twoSquares.flatten()
    assertEquals(2, subpaths.size)
    // A closed rectangle is 4 corners plus the repeated start point.
    assertEquals(5, subpaths[0].size)
  }

  @Test
  fun `flatten refines curves until within tolerance`() {
    val curve = PathData.build {
      moveTo(0.0, 0.0)
      cubicTo(0.0, 100.0, 100.0, 100.0, 100.0, 0.0)
    }
    val coarse = curve.flatten(tolerance = 10.0).first().size
    val fine = curve.flatten(tolerance = 0.05).first().size
    assertTrue(fine > coarse, "tighter tolerance should produce more segments ($fine vs $coarse)")

    // Every flattened vertex must lie within the curve's own bounds.
    for (point in curve.flatten(tolerance = 0.05).first()) {
      assertTrue(curve.bounds.expand(1e-6).contains(point), "vertex $point escaped the bounds")
    }
  }

  @Test
  fun `transformedBy maps every coordinate including control points`() {
    val original = PathData.build {
      moveTo(1.0, 1.0)
      cubicTo(2.0, 2.0, 3.0, 3.0, 4.0, 4.0)
      close()
    }
    val moved = original.transformedBy(Transform2D.translate(10.0, 20.0))
    assertEquals(PathCommand.MoveTo(11.0, 21.0), moved.commands[0])
    assertEquals(PathCommand.CubicTo(12.0, 22.0, 13.0, 23.0, 14.0, 24.0), moved.commands[1])
    assertEquals(PathCommand.Close, moved.commands[2])
    // Identity must not copy.
    assertTrue(original === original.transformedBy(Transform2D.Identity))
  }

  @Test
  fun `distanceToSegment handles a degenerate zero-length segment`() {
    val point = PointD(3.0, 4.0)
    assertEquals(5.0, distanceToSegment(point, PointD.Origin, PointD.Origin), 1e-9)
  }

  @Test
  fun `paths with equal commands are equal`() {
    val a = PathData.build { rect(0.0, 0.0, 1.0, 1.0) }
    val b = PathData.build { rect(0.0, 0.0, 1.0, 1.0) }
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
  }
}
