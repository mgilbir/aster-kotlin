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
        fill = Fill(ScenePaint.Black),
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
        fill = Fill(ScenePaint.Black),
        metadata = interactive,
      )
    val above =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 50.0,
        height = 50.0,
        fill = Fill(ScenePaint.Black),
        metadata = interactive,
      )
    val index = SceneHitIndex(sceneOf(below, above))
    assertEquals(above.id, index.hitTest(PointD(25.0, 25.0))!!.node.id)
  }

  @Test
  fun `non-interactive nodes are skipped unless requested`() {
    val rect =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 50.0,
        height = 50.0,
        fill = Fill(ScenePaint.Black),
      )
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
        fill = Fill(ScenePaint.Black),
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
        fill = Fill(ScenePaint.Black),
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
        fill = Fill(ScenePaint.Black),
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
        fill = Fill(ScenePaint.Black),
        metadata = interactive,
      )
    val b =
      RectNode(
        id = ids.allocate(),
        x = 40.0,
        y = 40.0,
        width = 10.0,
        height = 10.0,
        fill = Fill(ScenePaint.Black),
        metadata = interactive,
      )
    val c =
      RectNode(
        id = ids.allocate(),
        x = 90.0,
        y = 90.0,
        width = 10.0,
        height = 10.0,
        fill = Fill(ScenePaint.Black),
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
      fill = Fill(ScenePaint.Black),
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

  // ---- a rect and a label are picked where they are drawn ---------------------

  /**
   * The cut corner of a rounded bar is not part of it.
   *
   * Upstream picks a rect through `pickPath(rectangle)` — `isPointInPath` over the very path it
   * draws, corner radii included. This tested the bounding box, so the corner a bar does not fill
   * answered a tap; on a stack of thin rounded bars that is a tap credited to the wrong row.
   */
  @Test
  fun `a rounded rect is not hit in the corner it does not paint`() {
    val bar =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 40.0,
        height = 40.0,
        cornerRadius = 12.0,
        fill = Fill(ScenePaint.Black),
        metadata = interactive,
      )
    val index = SceneHitIndex(sceneOf(bar))

    assertEquals(bar.id, assertPresent(index.hitTest(PointD(20.0, 20.0))).node.id)
    assertNull(index.hitTest(PointD(1.0, 1.0)), "the top-left corner is cut away by a radius of 12")
  }

  /**
   * An **unfilled** rect is picked on its edge, not across its middle.
   *
   * `hitPath` is `(fill && isPointInPath) || (stroke && isPointInStroke)`, so a frame — a brush
   * outline, a cell border, a `strokeWidth`-only box — catches a tap on the border and lets one
   * through the hole. The stroke keeps the tap tolerance every other stroke-only mark here gets.
   */
  @Test
  fun `an unfilled rect is hit on its edge and not through its middle`() {
    val frame =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 60.0,
        height = 60.0,
        stroke = Stroke(paint = ScenePaint.Black, width = 2.0),
        metadata = interactive,
      )
    val index = SceneHitIndex(sceneOf(frame), HitTestOptions(strokeTolerance = 2.0))

    assertEquals(frame.id, assertPresent(index.hitTest(PointD(0.0, 30.0))).node.id)
    assertNull(index.hitTest(PointD(30.0, 30.0)), "a frame has a hole in it")
  }

  /**
   * A rect with no paint at all is not a hit target, which is what upstream's `hitPath` says.
   *
   * Worth pinning because it is the one case where this got *stricter*: a rect used to be picked
   * inside its bounds whatever it painted.
   */
  @Test
  fun `a rect that paints nothing is not hit`() {
    val ghost =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 50.0,
        height = 50.0,
        metadata = interactive,
      )
    assertNull(SceneHitIndex(sceneOf(ghost)).hitTest(PointD(25.0, 25.0)))
  }

  /**
   * A fill of zero opacity is still a hit target.
   *
   * Upstream asks whether the item *has* a fill, and `isPointInPath` does not look at alpha — so
   * `"fill": "transparent"` over a region is the idiom for an invisible tap target, and
   * specifications in the wild use it. Being faithful here is the difference between that trick
   * working and a specification silently losing its interaction.
   */
  @Test
  fun `an invisible fill is still picked, because upstream picks it`() {
    val target =
      RectNode(
        id = ids.allocate(),
        x = 0.0,
        y = 0.0,
        width = 50.0,
        height = 50.0,
        fill = Fill(ScenePaint.Black, opacity = 0.0),
        metadata = interactive,
      )
    assertEquals(
      target.id,
      assertPresent(SceneHitIndex(sceneOf(target)).hitTest(PointD(25.0, 25.0))).node.id,
    )
  }

  /**
   * A rotated label is picked where its ink is, not across the box that holds it.
   *
   * `marks/text.js` turns the *point* back about the label's anchor and tests the **unrotated**
   * box. A label's bounds are the axis-aligned reach of the turned one, which for 45° is nearly
   * twice the area — so on an axis of rotated tick labels the corners of one box overlap its
   * neighbours, and a tap in that overlap used to pick whichever came later.
   */
  @Test
  fun `a rotated label is hit on its ink rather than on its bounding box`() {
    val layout = MetricTextEngine().layout(TextRun("Wednesday", TextStyle(fontSize = 12.0)))
    val label =
      TextNode(
        id = ids.allocate(),
        x = 50.0,
        y = 50.0,
        layout = layout,
        angleDegrees = 45.0,
        fill = Fill(ScenePaint.Black),
        metadata = interactive,
      )
    val index = SceneHitIndex(sceneOf(label), HitTestOptions(boundsTolerance = 0.0))
    val box = label.bounds

    // Along the label's own direction, a step from the anchor stays on the ink.
    val step = 20.0 / kotlin.math.sqrt(2.0)
    assertEquals(
      label.id,
      assertPresent(index.hitTest(PointD(50.0 + step, 50.0 + step))).node.id,
      "45° down and to the right of the anchor is where this label runs",
    )
    // The far corner of the axis-aligned box is not: it is the reach of a turned rectangle, and
    // nothing is drawn there.
    assertNull(
      index.hitTest(PointD(box.right - 0.5, box.top + 0.5)),
      "the corner of the bounding box holds no ink: $box",
    )
  }

  // ---- what the audit found, each probed or reasoned from the renderers ------

  /**
   * M15 — a fully transparent **group** still contributes its children.
   *
   * Every canvas renderer here draws them, and each of them says so: a group's opacity applies to
   * its own panel and is not inherited. Pruning the whole subtree from the index made marks that
   * are visible on screen impossible to tap.
   */
  @Test
  fun `a transparent group's children are still tappable`() {
    val child =
      RectNode(
        id = ids.allocate(),
        x = 10.0,
        y = 10.0,
        width = 20.0,
        height = 20.0,
        fill = Fill(ScenePaint.Black),
        metadata = interactive,
      )
    val invisible =
      GroupNode(
        id = ids.allocate(),
        opacity = 0.0,
        size = SizeD(100.0, 100.0),
        children = listOf(child),
      )
    val hit = assertPresent(SceneHitIndex(sceneOf(invisible)).hitTest(PointD(15.0, 15.0)))
    assertEquals(child.id, hit.node.id)
  }

  /**
   * M16 — the broad phase must not be tighter than the narrow one.
   *
   * `Mouse` has a `boundsTolerance` of 0 and a `strokeTolerance` of 2, and the broad phase was
   * gated on the first alone — so the second was reachable only where a node's bounds happened to
   * be fatter than its geometry. On an axis-aligned rule, whose bounds *are* its stroke width, it
   * was zero: a tap two pixels off a gridline missed, which is the case the tolerance exists for.
   */
  @Test
  fun `a mouse tap within the stroke tolerance of a rule hits it`() {
    val rule =
      RuleNode(
        id = ids.allocate(),
        x1 = 10.0,
        y1 = 50.0,
        x2 = 90.0,
        y2 = 50.0,
        stroke = Stroke(ScenePaint.Black, width = 1.0),
        metadata = interactive,
      )
    val index = SceneHitIndex(sceneOf(rule), HitTestOptions.Mouse)
    assertNotNull(index.hitTest(PointD(50.0, 51.5)), "1.5 px away, inside a 2 px tolerance")
    assertNull(index.hitTest(PointD(50.0, 60.0)), "10 px away is a miss")
  }

  /**
   * M17 — `zindex` decides paint order, so it decides which mark a tap lands on.
   *
   * Only the SVG renderer was applying the reordering, so a raised mark was on top in the export
   * and underneath everywhere else — and the tap went to whichever was drawn second in declaration
   * order.
   */
  @Test
  fun `a raised mark wins the tap it wins in the picture`() {
    fun bar(z: Int, name: String) =
      RectNode(
        id = ids.allocate(),
        x = 10.0,
        y = 10.0,
        width = 20.0,
        height = 20.0,
        fill = Fill(ScenePaint.Black),
        metadata =
          NodeMetadata(
            interactive = true,
            zindex = z,
            role = "mark",
            markName = name,
            markKind = "rect",
          ),
      )
    // Same mark, same place, and the first one is raised.
    val raised = bar(1, "bars")
    val plain = bar(0, "bars")
    val hit = assertPresent(SceneHitIndex(sceneOf(raised, plain)).hitTest(PointD(15.0, 15.0)))
    assertEquals(raised.id, hit.node.id, "the raised item is painted last and is tapped first")
  }

  /**
   * M18 — a fill is picked with the rule it was **painted** with, which is nonzero winding.
   *
   * The even-odd rule calls the middle of a self-intersecting outline outside, so a tap on the
   * visibly solid centre of a star missed.
   */
  @Test
  fun `the solid centre of a self-intersecting path is tappable`() {
    // A five-pointed star, written as one closed outline that crosses itself. Its centre is filled
    // by every renderer and is *outside* by the even-odd rule.
    val star =
      PathNode(
        id = ids.allocate(),
        path =
          PathData.build {
            moveTo(50.0, 20.0)
            lineTo(68.0, 74.0)
            lineTo(22.0, 40.0)
            lineTo(78.0, 40.0)
            lineTo(32.0, 74.0)
            close()
          },
        fill = Fill(ScenePaint.Black),
        metadata = interactive,
      )
    val path = star.path
    assertTrue(path.containsNonZero(PointD(50.0, 45.0)), "the centre is inside by winding")
    assertFalse(path.containsEvenOdd(PointD(50.0, 45.0)), "and outside by parity, which is the bug")
    assertNotNull(
      SceneHitIndex(sceneOf(star)).hitTest(PointD(50.0, 45.0)),
      "the centre of a star is filled and must be tappable",
    )
  }

  /**
   * M21 — a transparent fill is still a fill.
   *
   * `isPointInPath` never looks at alpha, so `"fill": "transparent"` is the idiom for an invisible
   * tap target and specifications in the wild use it. `hitsRect` already said so and cited
   * upstream; the `path` branch beside it was testing `isVisible` and losing the interior.
   */
  @Test
  fun `a path with a transparent fill keeps its interior`() {
    val square =
      PathNode(
        id = ids.allocate(),
        path =
          PathData.build {
            moveTo(10.0, 10.0)
            lineTo(40.0, 10.0)
            lineTo(40.0, 40.0)
            lineTo(10.0, 40.0)
            close()
          },
        fill = Fill(ScenePaint.Black, opacity = 0.0),
        metadata = interactive,
      )
    assertNotNull(SceneHitIndex(sceneOf(square)).hitTest(PointD(25.0, 25.0)))
  }
}
