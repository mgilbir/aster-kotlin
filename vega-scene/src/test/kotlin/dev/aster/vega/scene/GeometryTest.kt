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

  /**
   * Upstream's `Bounds.union` is four bare comparisons and has **no** empty case:
   * ```js
   * union(b) { if (b.x1 < this.x1) this.x1 = b.x1; … }
   * ```
   *
   * The two `isEmpty` short-circuits this engine opened with are equivalent to the comparisons for
   * any box whose corners are numbers, which is why they survived — and wrong for a box whose
   * corners are not. `isEmpty` is `right < left`, false against a NaN, so a NaN-cornered box is
   * *not* empty and `isEmpty -> other` handed it back whole where every comparison rejects it.
   *
   * A mark upstream cannot place has **cleared** bounds, never NaN ones: `boundContext` builds a
   * NaN matrix from a non-numeric angle, every point it adds fails all four comparisons, and the
   * box stays as it was. This engine stored the NaN instead, and agreed about the chart's size
   * anyway — the surface unions one level up, where the comparisons did run — so only the mark's
   * own extent was wrong, which is the half the differential exists to check.
   */
  @Test
  fun `a box whose corners are not numbers adds nothing`() {
    val nan = RectD(Double.NaN, Double.NaN, Double.NaN, Double.NaN)
    assertFalse(nan.isEmpty, "a NaN box is not empty, which is what made the shortcut wrong")

    assertTrue(RectD.Empty.union(nan).isEmpty, "an unplaceable mark stays cleared")

    val real = RectD(10.0, 20.0, 30.0, 40.0)
    assertEquals(real, real.union(nan), "a NaN box never widens a real one")
    // **Asymmetric, and upstream's is too.** `if (b.x1 < this.x1)` is false against a NaN
    // *receiver* as well, so a box that has already gone NaN stays NaN. It never arises there
    // because a box never becomes NaN in the first place — points that are not numbers are
    // rejected on the way in, which is the property this test is really pinning.
    assertTrue(nan.union(real).left.isNaN(), "a NaN receiver keeps its NaN, as upstream's does")

    // The finite behaviour the shortcuts used to provide is unchanged.
    assertEquals(real, RectD.Empty.union(real))
    assertEquals(real, real.union(RectD.Empty))
    assertEquals(RectD(0.0, 0.0, 30.0, 40.0), real.union(RectD(0.0, 0.0, 1.0, 1.0)))

    // An **infinity** is a number upstream does add: `if (Inf > x2)` is true.
    val far = RectD(0.0, 0.0, Double.POSITIVE_INFINITY, 1.0)
    assertEquals(Double.POSITIVE_INFINITY, RectD.Empty.union(far).right)
  }

  /** A rotation by an angle that is not a number therefore maps a box to nothing, as upstream. */
  @Test
  fun `a rotation by no angle at all maps a box to nothing`() {
    val unit = RectD(-1.0, -1.0, 1.0, 1.0)
    assertTrue(Transform2D.rotateDegrees(Double.NaN).mapBounds(unit).isEmpty)
    assertFalse(Transform2D.rotateDegrees(45.0).mapBounds(unit).isEmpty)
  }

  /**
   * `boundRect` puts **all four** corners through `||`, which is falsiness and not a null test:
   * ```js
   * boundStroke(bounds.set(x = item.x || 0, y = item.y || 0,
   *                        (x + item.width) || 0, (y + item.height) || 0), item)
   * ```
   *
   * The order is what makes it interesting. `y` is replaced by `0` **first**, so `(0 + height)` is
   * an ordinary number and the rect keeps its full extent, anchored at the origin — it is *not*
   * flattened. It flattens only when the height is a NaN as well, which is what a rect written with
   * `y2` rather than `height` produces: `adjustSpatial` computes `o.height = o.y2 - o.y`, and that
   * is NaN when `y` is. Both come out of the one rule, and reading either off the other gets the
   * other wrong.
   *
   * Read off a live upstream view, both shapes:
   * ```
   * y NaN, height 40 -> bounds[10,0,40,40]
   * y NaN, y2 100    -> bounds[10,0,40,0]
   * ```
   *
   * Pinned here rather than in a fixture because no chart in the corpus can see it: the box lands
   * inside the plotting area, so the surface does not move and the reverted form passes every gate.
   * Correct, cited and unexercised is still worth keeping — it is a difference the moment such a
   * rect sits near an edge.
   */
  @Test
  fun `a rect that cannot be placed is anchored at the origin`() {
    assertEquals(RectD(10.0, 20.0, 40.0, 60.0), RectD.ofRect(10.0, 20.0, 30.0, 40.0))

    // Upstream: bounds[10,0,40,40] — the origin, and the height survives.
    assertEquals(RectD(10.0, 0.0, 40.0, 40.0), RectD.ofRect(10.0, Double.NaN, 30.0, 40.0))

    // Upstream: bounds[10,0,40,0] — a height that is also NaN collapses the far edge.
    val flat = RectD.ofRect(10.0, Double.NaN, 30.0, Double.NaN)
    assertEquals(RectD(10.0, 0.0, 40.0, 0.0), flat)
    assertFalse(flat.isEmpty, "upstream bounds it, so it still counts towards the chart's reach")

    // A negative extent still orders its corners, which `Bounds.set` does with one comparison.
    assertEquals(RectD(-20.0, 20.0, 10.0, 60.0), RectD.ofRect(10.0, 20.0, -30.0, 40.0))
  }

  /**
   * And the node is wired to it, which the test above does not say on its own.
   *
   * `RectNode.rect` stays [RectD.fromSize] — a renderer draws from it and upstream's renderer takes
   * `item.x` raw, so a NaN draws nothing — while `bounds` goes through [RectD.ofRect]. Testing the
   * companion function alone left the wiring free to be reverted with every gate still green.
   */
  @Test
  fun `a rect node bounds itself through the falsiness and draws from the raw values`() {
    val placed = RectNode(id = SceneNodeId(0), x = 10.0, y = 20.0, width = 30.0, height = 40.0)
    assertEquals(RectD(10.0, 20.0, 40.0, 60.0), placed.bounds)

    val unplaceable =
      RectNode(id = SceneNodeId(1), x = 10.0, y = Double.NaN, width = 30.0, height = 40.0)
    assertEquals(RectD(10.0, 0.0, 40.0, 40.0), unplaceable.bounds, "bounded at the origin")
    assertTrue(unplaceable.rect.top.isNaN(), "and still drawn from the raw value, which is NaN")
  }
}
