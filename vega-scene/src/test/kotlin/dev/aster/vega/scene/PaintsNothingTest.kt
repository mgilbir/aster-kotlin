package dev.aster.vega.scene

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one predicate four renderers ask before they draw an item.
 *
 * Each clause here was a defect in at least one walk while there were four copies of it, so each is
 * pinned separately rather than through a single "an ordinary mark is painted" case. See
 * [paintsNothing] for what the copies cost.
 */
class PaintsNothingTest {

  private val ids = SceneNodeIdAllocator()

  private fun rect(opacity: Double = 1.0, visible: Boolean = true) =
    RectNode(
      id = ids.allocate(),
      x = 0.0,
      y = 0.0,
      width = 10.0,
      height = 10.0,
      fill = Fill(ScenePaint.Black),
      opacity = opacity,
      visible = visible,
    )

  private fun text(absent: Boolean, content: String = "12") =
    TextNode(
      id = ids.allocate(),
      x = 0.0,
      y = 0.0,
      layout = MetricTextEngine().layout(TextRun(content, TextStyle(fontSize = 10.0))),
      absent = absent,
      fill = Fill(ScenePaint.Black),
    )

  private fun path(absent: Boolean, commands: List<PathCommand> = emptyList()) =
    PathNode(
      id = ids.allocate(),
      path = PathData(commands),
      absent = absent,
      fill = Fill(ScenePaint.Black),
    )

  @Test
  fun `an ordinary mark is painted`() {
    assertFalse(paintsNothing(rect()))
  }

  @Test
  fun `an invisible node is not`() {
    assertFalse(rect(visible = false).visible)
    assertTrue(paintsNothing(rect(visible = false)))
  }

  /**
   * The clause the Swift walk was missing, which drew a hidden axis label in black.
   *
   * An axis hides an overlapping label by setting its opacity to zero rather than by removing it —
   * so that the mark count does not change with the chart's width — which is why this is not
   * redundant with `visible`.
   */
  @Test
  fun `a node at zero opacity is not`() {
    assertTrue(paintsNothing(rect(opacity = 0.0)))
    assertFalse(paintsNothing(rect(opacity = 0.01)), "a nearly-transparent mark is still painted")
  }

  /**
   * A **group** at zero opacity still draws its children, which is the whole reason this cannot be
   * `opacity <= 0` alone.
   *
   * A group's opacity paints its own panel and is not inherited: upstream's canvas group never
   * touches `globalAlpha`, and its SVG renderer puts the opacity on the group's background path and
   * leaves the child element bare.
   */
  @Test
  fun `a transparent group is still walked`() {
    val group = GroupNode(id = ids.allocate(), opacity = 0.0, children = listOf(rect()))
    assertFalse(paintsNothing(group), "a transparent group is not an invisible subtree")
    assertTrue(
      paintsNothing(GroupNode(id = ids.allocate(), visible = false)),
      "an invisible group is still invisible",
    )
  }

  /**
   * The clause the Android canvas and the SVG export were missing.
   *
   * `absent` is not an empty string: the item carries no `text` property at all, as a banded
   * legend's lowest bucket does, and upstream emits no element for it. An item carrying an empty
   * string is a different item that upstream still emits, so the two cannot be collapsed.
   */
  @Test
  fun `an absent text is not painted, and an empty one is`() {
    assertTrue(paintsNothing(text(absent = true)))
    assertFalse(
      paintsNothing(text(absent = false, content = "")),
      "an empty label is still an item",
    )
    assertFalse(paintsNothing(text(absent = false)))
  }

  /** The same for an outline: `geopath` over a geometry with no coordinates has none at all. */
  @Test
  fun `an absent path is not painted, and one that draws nothing is`() {
    assertTrue(paintsNothing(path(absent = true)))
    assertFalse(
      paintsNothing(path(absent = false)),
      "a path whose commands happen to be empty is still an item upstream emits",
    )
  }
}
