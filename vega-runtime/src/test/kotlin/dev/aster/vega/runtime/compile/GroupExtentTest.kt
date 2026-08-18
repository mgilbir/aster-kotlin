package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.runtime.load.DenyLoader
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.SceneNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A group mark's own extent, which Vega takes from `x2`/`y2` as readily as from `width`/`height`.
 *
 * This has a test of its own because the **differential fixtures cannot see it**. They compare the
 * marks in a scene, and a group's extent is not a mark: with the extent wrong the bars were still
 * present, still at the right coordinates, and still identical to upstream's — every fixture
 * passed. What the extent decides is the group's `clip`, and a clipped group with no height hides
 * everything inside it. The chart drew its axes, its gridlines and its labels, and no data, with no
 * diagnostic.
 *
 * Found by looking at the iOS demo, which is the only place it was visible.
 */
class GroupExtentTest {

  private fun group(encode: String): GroupNode {
    val json =
      """
      {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 100, "height": 100, "padding": 0,
       "data": [{"name": "t", "values": [{"c": "a"}]}],
       "marks": [{"type": "group", "from": {"data": "t"},
         "encode": {"update": {$encode}}}]}
      """
        .trimIndent()
    val compiled = SpecCompiler(VegaHeadlessTextEngine(), DenyLoader).compileJson(json)
    val found = mutableListOf<GroupNode>()
    fun walk(node: SceneNode) {
      if (node is GroupNode) {
        if (node.size != null) found += node
        node.children.forEach { walk(it) }
      }
    }
    walk(requireNotNull(compiled.scene) { "no scene" }.root)
    // The innermost sized group is the mark's own; the ones above it are the chart and its cell.
    return found.last()
  }

  @Test
  fun `a group sized by its far edges spans between them`() {
    // The case Vega-Lite emits for a bar with a rounded end: a wrapper positioned by `y`/`y2` and
    // clipped, so the radius rounds the stack rather than each segment. Read as `height`-only, this
    // group was zero-high — and being clipped, it hid the bars it was there to round.
    val node =
      group(""""x": {"value": 10}, "x2": {"value": 40}, "y": {"value": 20}, "y2": {"value": 90}""")
    assertEquals(30.0, requireNotNull(node.size).width, 1e-9, "x2 - x")
    assertEquals(70.0, requireNotNull(node.size).height, 1e-9, "y2 - y")
  }

  @Test
  fun `far edges above the near ones give the same group, not a negative one`() {
    // `resolveSpan`'s rule, which the rect encoder already followed: lower edge first.
    val node =
      group(""""x": {"value": 40}, "x2": {"value": 10}, "y": {"value": 90}, "y2": {"value": 20}""")
    assertEquals(30.0, requireNotNull(node.size).width, 1e-9)
    assertEquals(70.0, requireNotNull(node.size).height, 1e-9)
  }

  @Test
  fun `a far edge alone is measured back by the size beside it`() {
    val node = group(""""x2": {"value": 40}, "width": {"value": 15}, "height": {"value": 20}""")
    assertEquals(15.0, requireNotNull(node.size).width, 1e-9)
  }

  @Test
  fun `a stated size still wins where both are given`() {
    // Unchanged behaviour, and worth pinning: every faceted cell in the corpus is sized this way.
    val node = group(""""x": {"value": 5}, "width": {"value": 25}, "height": {"value": 35}""")
    assertEquals(25.0, requireNotNull(node.size).width, 1e-9)
    assertEquals(35.0, requireNotNull(node.size).height, 1e-9)
  }

  @Test
  fun `a group that states no extent has none`() {
    // A pure container — an axis group, the scene root. It paints nothing and measures only its
    // children, and it must not be reported as a malformed mark either.
    val json =
      """
      {"${'$'}schema": "https://vega.github.io/schema/vega/v6.json",
       "width": 100, "height": 100, "padding": 0,
       "data": [{"name": "t", "values": [{"c": "a"}]}],
       "marks": [{"type": "group", "from": {"data": "t"},
         "encode": {"update": {"fill": {"value": "red"}}}}]}
      """
        .trimIndent()
    val compiled = SpecCompiler(VegaHeadlessTextEngine(), DenyLoader).compileJson(json)
    val groups = mutableListOf<GroupNode>()
    fun walk(node: SceneNode) {
      if (node is GroupNode) {
        groups += node
        node.children.forEach { walk(it) }
      }
    }
    walk(requireNotNull(compiled.scene).root)
    assertNull(groups.last().size, "no width, height, x2 or y2 means no extent of its own")
    assertEquals(
      emptyList<String>(),
      compiled.diagnostics.map { it.message },
      "and it is not a complaint",
    )
  }
}
