package dev.aster.vega.scene

import dev.aster.vega.fixtures.SampleScenes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HitTestTest {

  private val ids = SceneNodeIdAllocator()
  private val interactive = NodeMetadata(interactive = true)

  private fun sceneOf(vararg children: SceneNode): Scene =
    Scene(
      width = 100.0,
      height = 100.0,
      background = null,
      root = GroupNode(id = ids.allocate(), children = children.toList()),
    )

  /** JUnit 5's `assertNotNull` returns `Unit`, so this narrows the type as well as asserting. */
  private fun <T : Any> assertPresent(value: T?, message: String? = null): T {
    assertNotNull(value, message)
    return value!!
  }

  @Test
  fun `hits a rect and reports its local coordinates`() {
    val rect =
      RectNode(
        id = ids.allocate(),
        x = 10.0,
        y = 10.0,
        width = 20.0,
        height = 20.0,
        metadata = interactive,
      )
    val index = SceneHitIndex(sceneOf(rect))

    val hit = assertPresent(index.hitTest(PointD(15.0, 15.0)))
    assertEquals(rect.id, hit.node.id)
    assertEquals(PointD(15.0, 15.0), hit.localPoint)
    assertNull(index.hitTest(PointD(5.0, 5.0)))
  }

  @Test
  fun `topmost node in paint order wins`() {
    val below =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 50.0,
        height = 50.0,
        metadata = interactive,
      )
    val above =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 50.0,
        height = 50.0,
        metadata = interactive,
      )
    val index = SceneHitIndex(sceneOf(below, above))
    assertEquals(above.id, index.hitTest(PointD(25.0, 25.0))!!.node.id)
  }

  @Test
  fun `non-interactive nodes are skipped unless requested`() {
    val rect = RectNode(id = ids.allocate(), x = 0.0, y = 0.0, width = 50.0, height = 50.0)
    assertNull(SceneHitIndex(sceneOf(rect)).hitTest(PointD(10.0, 10.0)))
    assertNotNull(
      SceneHitIndex(sceneOf(rect), HitTestOptions(requireInteractive = false))
        .hitTest(PointD(10.0, 10.0))
    )
  }

  @Test
  fun `invisible and fully transparent nodes are never hit`() {
    val invisible =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 50.0,
        height = 50.0,
        visible = false,
        metadata = interactive,
      )
    val transparent = invisible.copy(id = ids.allocate(), visible = true, opacity = 0.0)
    assertNull(SceneHitIndex(sceneOf(invisible, transparent)).hitTest(PointD(10.0, 10.0)))
  }

  @Test
  fun `group transforms are inverted before precise testing`() {
    val child =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 10.0,
        height = 10.0,
        metadata = interactive,
      )
    val group =
      GroupNode(
        id = ids.allocate(),
        children = listOf(child),
        transform = Transform2D.translate(50.0, 50.0),
      )
    val index = SceneHitIndex(sceneOf(group))

    assertNull(index.hitTest(PointD(5.0, 5.0)))
    val hit = assertPresent(index.hitTest(PointD(55.0, 55.0)))
    assertEquals(child.id, hit.node.id)
    assertEquals(PointD(5.0, 5.0), hit.localPoint)
    assertEquals(listOf(group.id), hit.ancestors.drop(1).map { it.id })
  }

  @Test
  fun `a child wins over its interactive parent group`() {
    val child =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 10.0,
        height = 10.0,
        metadata = interactive,
      )
    val group = GroupNode(id = ids.allocate(), children = listOf(child), metadata = interactive)
    val hit = assertPresent(SceneHitIndex(sceneOf(group)).hitTest(PointD(5.0, 5.0)))
    assertEquals(child.id, hit.node.id)
  }

  @Test
  fun `a thin rule is tappable within the stroke tolerance`() {
    val rule =
      RuleNode(
        id = ids.allocate(),
        x1 = 0.0,
        y1 = 50.0,
        x2 = 100.0,
        y2 = 50.0,
        stroke = Stroke(paint = ScenePaint.Black, width = 1.0),
        metadata = interactive,
      )
    val scene = sceneOf(rule)

    val mouse = SceneHitIndex(scene, HitTestOptions.Mouse)
    val touch = SceneHitIndex(scene, HitTestOptions.Touch)

    assertNotNull(mouse.hitTest(PointD(50.0, 50.0)))
    assertNull(mouse.hitTest(PointD(50.0, 56.0)))
    assertNotNull(touch.hitTest(PointD(50.0, 56.0)), "touch tolerance should widen the target")
  }

  @Test
  fun `tolerance does not change visual bounds`() {
    val rule =
      RuleNode(
        id = ids.allocate(),
        x1 = 0.0,
        y1 = 50.0,
        x2 = 100.0,
        y2 = 50.0,
        stroke = Stroke(paint = ScenePaint.Black, width = 1.0),
        metadata = interactive,
      )
    // Bounds are the geometry expanded by half the stroke width, deliberately conservative: they
    // do not include the 8px touch tolerance that HitTestOptions.Touch adds.
    assertEquals(RectD(-0.5, 49.5, 100.5, 50.5), rule.bounds)
  }

  @Test
  fun `symbol hit testing uses the outline not the bounding box`() {
    val diamond =
      SymbolNode(
        id = ids.allocate(),
        x = 50.0,
        y = 50.0,
        size = 400.0,
        shape = SymbolShape.DIAMOND,
        fill = Fill.of(SceneColor.Black),
        metadata = interactive,
      )
    val index = SceneHitIndex(sceneOf(diamond), HitTestOptions(strokeTolerance = 0.0))
    assertNotNull(index.hitTest(PointD(50.0, 50.0)), "centre should hit")

    // A corner of the bounding box lies outside the diamond.
    val corner = PointD(diamond.bounds.left + 0.5, diamond.bounds.top + 0.5)
    assertNull(index.hitTest(corner), "bounding-box corner should miss the diamond")
  }

  @Test
  fun `unfilled path is hit near its outline only`() {
    val line =
      PathNode(
        id = ids.allocate(),
        path =
          PathData.build {
            moveTo(0.0, 0.0)
            lineTo(100.0, 100.0)
          },
        stroke = Stroke(paint = ScenePaint.Black, width = 2.0),
        metadata = interactive,
      )
    val index = SceneHitIndex(sceneOf(line), HitTestOptions(strokeTolerance = 3.0))
    assertNotNull(index.hitTest(PointD(50.0, 51.0)))
    assertNull(index.hitTest(PointD(10.0, 90.0)))
  }

  @Test
  fun `nodesIntersecting finds every node in an interval selection`() {
    val a =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 10.0,
        height = 10.0,
        metadata = interactive,
      )
    val b =
      RectNode(
        id = ids.allocate(),
        x = 40.0,
        y = 40.0,
        width = 10.0,
        height = 10.0,
        metadata = interactive,
      )
    val c =
      RectNode(
        id = ids.allocate(),
        x = 90.0,
        y = 90.0,
        width = 10.0,
        height = 10.0,
        metadata = interactive,
      )
    val index = SceneHitIndex(sceneOf(a, b, c))

    val selected = index.nodesIntersecting(RectD(0.0, 0.0, 60.0, 60.0)).map { it.id }
    assertEquals(listOf(a.id, b.id), selected)
  }

  @Test
  fun `spatial index and linear scan agree`() {
    val scene = SampleScenes.symbolStressTest(count = 2000, width = 400.0, height = 400.0)
    val linear = SceneHitIndex(scene, HitTestOptions(spatialIndexThreshold = Int.MAX_VALUE))
    val indexed = SceneHitIndex(scene, HitTestOptions(spatialIndexThreshold = 512))

    assertFalse(linear.usesSpatialIndex)
    assertTrue(indexed.usesSpatialIndex)
    assertEquals(linear.indexedNodeCount, indexed.indexedNodeCount)

    var hits = 0
    for (i in 0 until 200) {
      // Deterministic probe positions across the whole viewport.
      val point = PointD((i * 37 % 400).toDouble(), (i * 53 % 400).toDouble())
      val expected = linear.hitTest(point)?.node?.id
      val actual = indexed.hitTest(point)?.node?.id
      assertEquals(expected, actual, "disagreement at $point")
      if (expected != null) hits++
    }
    assertTrue(hits > 0, "probes never hit anything; the test would be vacuous")
  }

  @Test
  fun `sample bar chart is hit at a bar and misses the margin`() {
    val scene = SampleScenes.barChart()
    val index = SceneHitIndex(scene, HitTestOptions.Touch)

    val bar =
      scene
        .flatten()
        .map { it.node }
        .filterIsInstance<RectNode>()
        .first {
          it.metadata.markName == "bars"
        }
    val hit = assertPresent(index.hitTest(PointD(bar.rect.centerX, bar.rect.centerY)))
    assertEquals("bars", hit.node.metadata.markName)

    assertNull(index.hitTest(PointD(1.0, scene.height - 1.0)))
  }

  // ---- groups, which are picked only where they paint ------------------------

  private fun rect(x: Double, y: Double, size: Double = 10.0) =
    RectNode(
      id = ids.allocate(),
      x = x,
      y = y,
      width = size,
      height = size,
      metadata = interactive,
    )

  /**
   * The defect this pair of tests exists for.
   *
   * A group used to be picked anywhere inside its **bounds**, so a compiled chart — which wraps its
   * marks in a group whose bounds are the whole plotting area — reported a selected mark for a tap
   * on blank space. Upstream picks a group only where it *paints*: `group.fill ||
   * (!strokeForeground && group.stroke)` for its background, and its foreground stroke on its own.
   * A group that paints nothing is never the hit.
   */
  @Test
  fun `a group that paints nothing does not swallow a tap`() {
    val child = rect(0.0, 0.0)
    val frame =
      GroupNode(
        id = ids.allocate(),
        children = listOf(child),
        size = SizeD(100.0, 100.0),
        metadata = interactive,
      )
    val index = SceneHitIndex(sceneOf(frame))

    assertEquals(child.id, assertPresent(index.hitTest(PointD(5.0, 5.0))).node.id)
    assertNull(index.hitTest(PointD(80.0, 80.0)), "blank space inside a bare group is not a hit")
  }

  @Test
  fun `a group that paints a background is hit on it`() {
    val child = rect(0.0, 0.0)
    val panel =
      GroupNode(
        id = ids.allocate(),
        children = listOf(child),
        size = SizeD(100.0, 100.0),
        fill = Fill(ScenePaint.Black),
        metadata = interactive,
      )
    val index = SceneHitIndex(sceneOf(panel))

    assertEquals(panel.id, assertPresent(index.hitTest(PointD(80.0, 80.0))).node.id)
    // And its child still wins where the two overlap, which is paint order doing its job.
    assertEquals(child.id, assertPresent(index.hitTest(PointD(5.0, 5.0))).node.id)
  }

  /**
   * A group is picked on its **paint rect**, not on the union of what its children reach.
   *
   * A panel of 40 units with a mark drawn 60 units out is 60 units of bounds and 40 of paint.
   * Bounds are what a *layout* asks about; a pick asks what was drawn.
   */
  @Test
  fun `a group is hit within its declared extent rather than its bounds`() {
    val overflowing = rect(70.0, 70.0)
    val panel =
      GroupNode(
        id = ids.allocate(),
        children = listOf(overflowing),
        size = SizeD(40.0, 40.0),
        fill = Fill(ScenePaint.Black),
        metadata = interactive,
      )
    val index = SceneHitIndex(sceneOf(panel))

    assertEquals(panel.id, assertPresent(index.hitTest(PointD(20.0, 20.0))).node.id)
    assertEquals(overflowing.id, assertPresent(index.hitTest(PointD(75.0, 75.0))).node.id)
    assertNull(index.hitTest(PointD(55.0, 55.0)), "inside the bounds, outside everything drawn")
  }

  /** A stroke drawn over the children is grabbed by that outline, within the tap tolerance. */
  @Test
  fun `a group stroked in the foreground is hit on its outline`() {
    val bordered =
      GroupNode(
        id = ids.allocate(),
        children = emptyList(),
        size = SizeD(50.0, 50.0),
        stroke = Stroke(paint = ScenePaint.Black, width = 2.0),
        strokeForeground = true,
        metadata = interactive,
      )
    val index = SceneHitIndex(sceneOf(bordered), HitTestOptions(strokeTolerance = 2.0))

    assertEquals(bordered.id, assertPresent(index.hitTest(PointD(50.0, 25.0))).node.id)
    // Not its middle: a foreground stroke paints an outline, and there is no fill behind it.
    assertNull(index.hitTest(PointD(25.0, 25.0)), "a border is not a panel")
  }

  /**
   * A mark clipped away cannot be touched, which is the other half of applying `mark.clip`.
   *
   * Upstream returns from `pick` before testing a clipped group's contents at all. Drawing already
   * hid this mark; a hit test that still found it would report a tap on something invisible — and a
   * pan over a detail plot is exactly where that happens, because the rows outside the brushed
   * domain are still in the scene.
   */
  @Test
  fun `a clipped-away mark is not hit`() {
    val inside = rect(5.0, 5.0)
    val outside = rect(70.0, 70.0)
    val clipped =
      GroupNode(
        id = ids.allocate(),
        children = listOf(inside, outside),
        clip = RectD(0.0, 0.0, 40.0, 40.0),
        metadata = interactive,
      )
    val index = SceneHitIndex(sceneOf(clipped))

    assertEquals(inside.id, assertPresent(index.hitTest(PointD(8.0, 8.0))).node.id)
    assertNull(index.hitTest(PointD(75.0, 75.0)), "outside the clip is outside the chart")
  }
}
