package dev.aster.vega.scene

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The semantic tree a screen reader explores.
 *
 * These rules used to live in `VegaAccessibilityHelper`, which is an Android class — so they were
 * untestable off a device and, more to the point, invisible to every other host. A reader's
 * experience of a chart is not a platform detail, so the policy is here and so are its tests.
 */
class AccessibilityTreeTest {

  private val ids = SceneNodeIdAllocator()

  private fun mark(
    label: String,
    value: String? = null,
    focusable: Boolean = true,
    order: Int = 0,
  ) =
    RectNode(
      id = ids.allocate(),
      x = 0.0,
      y = 0.0,
      width = 10.0,
      height = 10.0,
      metadata =
        NodeMetadata(
          accessibility =
            AccessibilityDescriptor(
              label = label,
              value = value,
              focusable = focusable,
              traversalIndex = order,
            )
        ),
    )

  private fun sceneOf(vararg nodes: SceneNode) =
    Scene(
      width = 100.0,
      height = 100.0,
      background = null,
      root = GroupNode(id = ids.allocate(), children = nodes.toList()),
    )

  @Test
  fun `only focusable marks are announced`() {
    val elements =
      AccessibilityTree.elements(
        sceneOf(mark("visible one"), mark("not focusable", focusable = false))
      )
    assertEquals(listOf("visible one"), elements.map { it.label })
  }

  @Test
  fun `a label and a value are read together`() {
    // "Sales" is a column; "Sales: 42" is the datum a reader wanted.
    val elements = AccessibilityTree.elements(sceneOf(mark("Sales", value = "42")))
    assertEquals("Sales: 42", elements.single().label)
  }

  @Test
  fun `marks are ordered by traversal index rather than paint order`() {
    val elements =
      AccessibilityTree.elements(
        sceneOf(mark("third", order = 3), mark("first", order = 1), mark("second", order = 2))
      )
    assertEquals(listOf("first", "second", "third"), elements.map { it.label })
  }

  @Test
  fun `a dense chart becomes one summary rather than an unusable list`() {
    val many = (1..AccessibilityTree.MAX_EXPOSED_MARKS + 1).map { mark("point $it") }
    val elements = AccessibilityTree.elements(sceneOf(*many.toTypedArray()))

    // The point of the cap: a reader swiping through four thousand points is stuck, so the chart
    // says
    // what it is instead of enumerating itself.
    assertEquals(1, elements.size)
    assertTrue(elements.single().isSummary)
    assertTrue(elements.single().label.contains("${AccessibilityTree.MAX_EXPOSED_MARKS + 1} marks"))
    assertEquals(null, elements.single().nodeId)
  }

  @Test
  fun `exactly at the cap every mark is still announced`() {
    val many = (1..AccessibilityTree.MAX_EXPOSED_MARKS).map { mark("point $it") }
    val elements = AccessibilityTree.elements(sceneOf(*many.toTypedArray()))
    assertEquals(AccessibilityTree.MAX_EXPOSED_MARKS, elements.size)
    assertFalse(elements.any { it.isSummary })
  }

  @Test
  fun `a selected mark is reported as selected`() {
    val selected = mark("chosen")
    val other = mark("plain")
    val elements = AccessibilityTree.elements(sceneOf(selected, other), setOf(selected.id))

    assertEquals(true, elements.single { it.label == "chosen" }.selected)
    assertEquals(false, elements.single { it.label == "plain" }.selected)
  }

  @Test
  fun `bounds are in scene coordinates, through the groups above a mark`() {
    // A mark inside a translated group has to be announced where it *is*, or a reader's touch
    // exploration lands on the wrong thing.
    val inner = mark("nested")
    val scene =
      Scene(
        width = 100.0,
        height = 100.0,
        background = null,
        root =
          GroupNode(
            id = ids.allocate(),
            children =
              listOf(
                GroupNode(
                  id = ids.allocate(),
                  transform = Transform2D.translate(20.0, 30.0),
                  children = listOf(inner),
                )
              ),
          ),
      )
    val element = AccessibilityTree.elements(scene).single()
    assertEquals(20.0, element.bounds.left)
    assertEquals(30.0, element.bounds.top)
  }

  @Test
  fun `a hidden mark is not announced`() {
    val elements =
      AccessibilityTree.elements(sceneOf(mark("shown"), mark("gone").copy(visible = false)))
    assertEquals(listOf("shown"), elements.map { it.label })
  }
}
