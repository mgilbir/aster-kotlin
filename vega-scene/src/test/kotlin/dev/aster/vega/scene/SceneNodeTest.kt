package dev.aster.vega.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class SceneNodeTest {

  private val ids = SceneNodeIdAllocator()

  @Test
  fun `node ids come from build order not identity`() {
    val allocator = SceneNodeIdAllocator()
    assertEquals(SceneNodeId(1L), allocator.allocate())
    assertEquals(SceneNodeId(2L), allocator.allocate())
    // A second allocator restarts, so two identical builds produce identical ids.
    assertEquals(SceneNodeId(1L), SceneNodeIdAllocator().allocate())
  }

  @Test
  fun `rect bounds include the stroke extent`() {
    val unstroked = RectNode(id = ids.allocate(), x = 10.0, y = 10.0, width = 20.0, height = 5.0)
    assertEquals(RectD(10.0, 10.0, 30.0, 15.0), unstroked.bounds)

    val stroked = unstroked.copy(stroke = Stroke(paint = ScenePaint.Black, width = 4.0))
    assertEquals(RectD(8.0, 8.0, 32.0, 17.0), stroked.bounds)
  }

  @Test
  fun `rect with negative height is normalized`() {
    val upward = RectNode(id = ids.allocate(), x = 0.0, y = 100.0, width = 10.0, height = -30.0)
    assertEquals(RectD(0.0, 70.0, 10.0, 100.0), upward.bounds)
  }

  @Test
  fun `corner radius is clamped to half the smallest side`() {
    val node =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 10.0,
        height = 4.0,
        cornerRadius = 100.0,
      )
    assertEquals(2.0, node.effectiveCornerRadius)

    val negative = node.copy(cornerRadius = -5.0)
    assertEquals(0.0, negative.effectiveCornerRadius)
  }

  @Test
  fun `rule bounds cover a zero-length rule`() {
    val point =
      RuleNode(
        id = ids.allocate(),
        x1 = 5.0,
        y1 = 5.0,
        x2 = 5.0,
        y2 = 5.0,
        stroke = Stroke(paint = ScenePaint.Black, width = 2.0),
      )
    assertEquals(RectD(4.0, 4.0, 6.0, 6.0), point.bounds)
  }

  @Test
  fun `miter join widens path bounds more than a round join`() {
    val zigzag = PathData.build {
      moveTo(0.0, 0.0)
      lineTo(10.0, 10.0)
      lineTo(20.0, 0.0)
    }
    val miter =
      PathNode(
        id = ids.allocate(),
        path = zigzag,
        stroke = Stroke(paint = ScenePaint.Black, width = 4.0, join = StrokeJoin.MITER),
      )
    val round =
      PathNode(
        id = ids.allocate(),
        path = zigzag,
        stroke = Stroke(paint = ScenePaint.Black, width = 4.0, join = StrokeJoin.ROUND),
      )
    assertTrue(miter.bounds.width > round.bounds.width)
  }

  @ParameterizedTest
  @EnumSource(SymbolShape::class)
  fun `every symbol shape produces finite bounds around its position`(shape: SymbolShape) {
    val node = SymbolNode(id = ids.allocate(), x = 50.0, y = 60.0, size = 100.0, shape = shape)
    val bounds = node.bounds
    assertFalse(bounds.isEmpty, "$shape produced empty bounds")
    assertTrue(bounds.left.isFinite() && bounds.right.isFinite())
    assertTrue(bounds.contains(50.0, 60.0), "$shape does not enclose its own position")
  }

  @ParameterizedTest
  @EnumSource(
    value = SymbolShape::class,
    names = ["CIRCLE", "SQUARE", "CROSS", "DIAMOND", "STROKE"],
  )
  fun `point-symmetric shapes are centred on their position`(shape: SymbolShape) {
    val node = SymbolNode(id = ids.allocate(), x = 50.0, y = 60.0, size = 100.0, shape = shape)
    assertEquals(50.0, node.bounds.centerX, 1e-6, "$shape is not horizontally centred")
    if (shape != SymbolShape.STROKE) {
      assertEquals(60.0, node.bounds.centerY, 1e-6, "$shape is not vertically centred")
    }
  }

  @Test
  fun `triangles are balanced on their centroid as in d3-shape`() {
    // d3-shape places a triangle's apex at 2y and its base at -y, so the bounding box is
    // deliberately not centred on the anchor: the centroid is.
    val node =
      SymbolNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        size = 300.0,
        shape = SymbolShape.TRIANGLE_UP,
      )
    val bounds = node.bounds
    assertEquals(0.0, bounds.centerX, 1e-6)
    assertEquals(2.0, -bounds.top / bounds.bottom, 1e-6, "apex should be twice as far as the base")
  }

  @Test
  fun `symbol size is an area so radius scales with its square root`() {
    val small = SymbolNode(id = ids.allocate(), x = 0.0, y = 0.0, size = 100.0)
    val large = SymbolNode(id = ids.allocate(), x = 0.0, y = 0.0, size = 400.0)
    assertEquals(2.0, large.radius / small.radius, 1e-9)
  }

  @Test
  fun `zero-size symbol produces an empty outline instead of a degenerate shape`() {
    val node = SymbolNode(id = ids.allocate(), x = 5.0, y = 5.0, size = 0.0)
    assertTrue(node.outline.isEmpty)
    assertTrue(node.bounds.isEmpty)
  }

  @Test
  fun `square symbol area equals its size`() {
    val node =
      SymbolNode(id = ids.allocate(), x = 0.0, y = 0.0, size = 64.0, shape = SymbolShape.SQUARE)
    assertEquals(8.0, node.bounds.width, 1e-9)
    assertEquals(8.0, node.bounds.height, 1e-9)
  }

  @Test
  fun `rotating a symbol rotates its outline about its own position`() {
    val upright =
      SymbolNode(
        id = ids.allocate(),
        x = 100.0,
        y = 100.0,
        size = 200.0,
        shape = SymbolShape.TRIANGLE_UP,
      )
    val rotated = upright.copy(angleDegrees = 180.0)
    // A 180 degree rotation about the symbol's own position reflects its bounds through that point.
    assertEquals(upright.bounds.centerX, rotated.bounds.centerX, 1e-6)
    assertEquals(2 * 100.0 - upright.bounds.bottom, rotated.bounds.top, 1e-6)
    assertEquals(2 * 100.0 - upright.bounds.top, rotated.bounds.bottom, 1e-6)
    assertTrue(upright.bounds.top < rotated.bounds.top)
  }

  @Test
  fun `group bounds are the union of visible children`() {
    val a = RectNode(id = ids.allocate(), x = 0.0, y = 0.0, width = 10.0, height = 10.0)
    val b = RectNode(id = ids.allocate(), x = 90.0, y = 90.0, width = 10.0, height = 10.0)
    val hidden =
      RectNode(
        id = ids.allocate(),
        x = 500.0,
        y = 500.0,
        width = 10.0,
        height = 10.0,
        visible = false,
      )
    val group = GroupNode(id = ids.allocate(), children = listOf(a, b, hidden))
    assertEquals(RectD(0.0, 0.0, 100.0, 100.0), group.bounds)
  }

  @Test
  fun `group transform moves children in the parent space`() {
    val child = RectNode(id = ids.allocate(), x = 0.0, y = 0.0, width = 10.0, height = 10.0)
    val group =
      GroupNode(
        id = ids.allocate(),
        children = listOf(child),
        transform = Transform2D.translate(100.0, 50.0),
      )
    assertEquals(RectD(0.0, 0.0, 10.0, 10.0), group.bounds)
    assertEquals(RectD(100.0, 50.0, 110.0, 60.0), group.transformedBounds)
  }

  @Test
  fun `group clip constrains its bounds`() {
    val wide = RectNode(id = ids.allocate(), x = 0.0, y = 0.0, width = 1000.0, height = 1000.0)
    val group =
      GroupNode(id = ids.allocate(), children = listOf(wide), clip = RectD(0.0, 0.0, 100.0, 100.0))
    assertEquals(RectD(0.0, 0.0, 100.0, 100.0), group.bounds)
  }

  @Test
  fun `empty group has empty bounds`() {
    assertTrue(GroupNode(id = ids.allocate()).bounds.isEmpty)
  }

  @Test
  fun `type names are stable`() {
    assertEquals("group", typeName(GroupNode(id = ids.allocate())))
    assertEquals(
      "rect",
      typeName(RectNode(id = ids.allocate(), x = 0.0, y = 0.0, width = 1.0, height = 1.0)),
    )
    assertEquals("path", typeName(PathNode(id = ids.allocate(), path = PathData.Empty)))
  }
}
