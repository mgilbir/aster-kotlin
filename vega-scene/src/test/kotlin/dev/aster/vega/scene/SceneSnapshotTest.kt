package dev.aster.vega.scene

import dev.aster.vega.fixtures.GoldenFiles
import dev.aster.vega.fixtures.SampleScenes
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SceneSnapshotTest {

  @Test
  fun `snapshot is byte-identical across repeated builds`() {
    val first = SampleScenes.barChart().toCanonicalJson()
    val second = SampleScenes.barChart().toCanonicalJson()
    assertEquals(first, second)
  }

  @Test
  fun `snapshot contains no memory addresses timestamps or platform line endings`() {
    val snapshot = SampleScenes.scatterPlot().toCanonicalJson()
    assertFalse(snapshot.contains("@"), "an identity hash code leaked into the snapshot")
    assertFalse(snapshot.contains("\r"), "platform line endings leaked into the snapshot")
    assertFalse(Regex("""\d{13}""").containsMatchIn(snapshot), "a timestamp-like value appeared")
  }

  @Test
  fun `negative zero is normalized in the snapshot`() {
    val ids = SceneNodeIdAllocator()
    val scene =
      Scene(
        width = 10.0,
        height = 10.0,
        background = null,
        root =
          GroupNode(
            id = ids.allocate(),
            children =
              listOf(RectNode(id = ids.allocate(), x = -0.0, y = -0.0, width = 5.0, height = 5.0)),
          ),
      )
    val snapshot = scene.toCanonicalJson()
    assertFalse(snapshot.contains("-0"), "negative zero survived into the snapshot:\n$snapshot")
  }

  @Test
  fun `precision is configurable and stable`() {
    val ids = SceneNodeIdAllocator()
    val scene =
      Scene(
        width = 1.0,
        height = 1.0,
        background = null,
        root =
          GroupNode(
            id = ids.allocate(),
            children =
              listOf(
                RectNode(
                  id = ids.allocate(),
                  x = 1.0 / 3.0,
                  y = 0.0,
                  width = 1.0,
                  height = 1.0,
                )
              ),
          ),
      )
    assertTrue(scene.toCanonicalJson(precision = 2).contains("\"x\": 0.33"))
    assertTrue(scene.toCanonicalJson(precision = 4).contains("\"x\": 0.3333"))
  }

  @Test
  fun `node ids are excluded by default and stable when included`() {
    val scene = SampleScenes.lineChart()
    assertFalse(scene.toCanonicalJson().contains("\"id\""))

    val withIds = scene.toCanonicalJson(includeNodeIds = true)
    assertTrue(withIds.contains("\"id\""))
    assertEquals(withIds, SampleScenes.lineChart().toCanonicalJson(includeNodeIds = true))
  }

  @Test
  fun `metadata including tooltips and accessibility is serialized`() {
    val ids = SceneNodeIdAllocator()
    val scene =
      Scene(
        width = 10.0,
        height = 10.0,
        background = null,
        root =
          GroupNode(
            id = ids.allocate(),
            children =
              listOf(
                RectNode(
                  id = ids.allocate(),
                  x = 0.0,
                  y = 0.0,
                  width = 1.0,
                  height = 1.0,
                  metadata =
                    NodeMetadata(
                      markName = "bars",
                      datumIndex = 3,
                      interactive = true,
                      tooltip = VegaValue.Str("a \"quoted\" value"),
                      accessibility =
                        AccessibilityDescriptor(label = "Bar 3", value = "42", focusable = true),
                    ),
                )
              ),
          ),
      )
    val snapshot = scene.toCanonicalJson()
    assertTrue(snapshot.contains("\"markName\": \"bars\""))
    assertTrue(snapshot.contains("\"datumIndex\": 3"))
    assertTrue(snapshot.contains("\"interactive\": true"))
    assertTrue(snapshot.contains("""a \"quoted\" value"""))
    assertTrue(snapshot.contains("\"label\": \"Bar 3\""))
  }

  @Test
  fun `nodeCount matches a manual walk`() {
    val scene = SampleScenes.stackedBarChart()
    assertEquals(scene.flatten().size, scene.nodeCount)
  }

  @Test
  fun `flatten returns nodes in paint order with accumulated transforms`() {
    val ids = SceneNodeIdAllocator()
    val leaf = RectNode(id = ids.allocate(), x = 0.0, y = 0.0, width = 1.0, height = 1.0)
    val inner =
      GroupNode(
        id = ids.allocate(),
        children = listOf(leaf),
        transform = Transform2D.translate(5.0, 0.0),
      )
    val root =
      GroupNode(
        id = ids.allocate(),
        children = listOf(inner),
        transform = Transform2D.translate(10.0, 0.0),
      )
    val scene = Scene(width = 100.0, height = 100.0, background = null, root = root)

    val flattened = scene.flatten()
    assertEquals(listOf(root.id, inner.id, leaf.id), flattened.map { it.node.id })
    assertEquals(RectD(15.0, 0.0, 16.0, 1.0), flattened.last().worldBounds)
  }

  @Test
  fun `findNode locates a nested node`() {
    val scene = SampleScenes.barChart()
    val target = scene.flatten()[3].node
    assertEquals(target.id, scene.findNode(target.id)?.id)
    assertEquals(null, scene.findNode(SceneNodeId(Long.MAX_VALUE)))
  }

  @Test
  fun `bar chart snapshot matches its golden`() {
    GoldenFiles.assertMatches("scene/bar-chart.json", SampleScenes.barChart().toCanonicalJson())
  }

  @Test
  fun `line chart snapshot matches its golden`() {
    GoldenFiles.assertMatches("scene/line-chart.json", SampleScenes.lineChart().toCanonicalJson())
  }

  @Test
  fun `area chart snapshot matches its golden`() {
    GoldenFiles.assertMatches("scene/area-chart.json", SampleScenes.areaChart().toCanonicalJson())
  }

  @Test
  fun `scatter plot snapshot matches its golden`() {
    GoldenFiles.assertMatches(
      "scene/scatter-plot.json",
      SampleScenes.scatterPlot(pointCount = 12).toCanonicalJson(),
    )
  }

  @Test
  fun `stacked bar chart snapshot matches its golden`() {
    GoldenFiles.assertMatches(
      "scene/stacked-bar-chart.json",
      SampleScenes.stackedBarChart().toCanonicalJson(),
    )
  }
}
