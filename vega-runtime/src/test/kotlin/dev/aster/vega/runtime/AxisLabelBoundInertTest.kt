package dev.aster.vega.runtime

import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.TextNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Axis `labelBound` is read and does nothing, because upstream's does nothing.
 *
 * `SUPPORTED_FEATURES.md` files this as `Consumed, deliberately inert`, which is an unusual thing
 * for a row to say and needs holding up. The reasoning is upstream's own code: it applies the test
 * as `boundRectangle.encloses(item.bounds)` inside `Overlap`, and `Overlap` runs **before** the
 * label bounds exist. Every item still holds a cleared `Bounds`, which any rectangle trivially
 * encloses, so nothing is ever culled — established by experiment, with a label overflowing by 68
 * surviving `labelBound: false`, `true` and `40` alike.
 *
 * Implementing the *documented* behaviour would therefore make this engine differ from upstream on
 * every chart that sets the property, which is the opposite of the point. So it is consumed — a
 * specification setting it draws no `PARSE_UNKNOWN_PROPERTY` — and it changes nothing.
 *
 * The day upstream fixes its ordering, this goes red and the row becomes an ordinary gap.
 */
class AxisLabelBoundInertTest {

  private fun labels(labelBound: String): List<String> {
    val compiled =
      SpecCompiler()
        .compileJson(
          """
          {"width": 120, "height": 80, "padding": 0, "autosize": "none",
           "data": [{"name": "t", "values": [{"c": "a stubbornly long category name"},
                                             {"c": "another very long category name"}]}],
           "scales": [{"name": "x", "type": "band",
                       "domain": {"data": "t", "field": "c"}, "range": "width"}],
           "axes": [{"scale": "x", "orient": "bottom"$labelBound}],
           "marks": []}
          """
            .trimIndent()
        )
    val out = mutableListOf<String>()
    fun walk(node: SceneNode, role: String?) {
      val here = node.metadata.role ?: role
      if (here == "axis-label" && node is TextNode) out += node.text
      if (node is GroupNode) node.children.forEach { walk(it, here) }
    }
    compiled.scene?.root?.let { walk(it, null) }
    return out
  }

  /**
   * Every spelling draws the same labels, including one that overflows the axis by a long way.
   *
   * Two long names on a 120-unit axis: each label is far wider than its band, so a `labelBound`
   * that culled anything would cull here. Upstream culls nothing, and neither does this.
   */
  @Test
  fun `labelBound culls nothing, whatever it is set to`() {
    val unset = labels("")
    assertTrue(unset.isNotEmpty(), "the axis drew no labels at all, so this proves nothing")
    for (setting in
      listOf(
        """, "labelBound": false""",
        """, "labelBound": true""",
        """, "labelBound": 40""",
        """, "labelBound": 0""",
      )) {
      assertEquals(
        unset,
        labels(setting),
        "`labelBound`$setting changed which labels were drawn; upstream's culls nothing, so this " +
          "engine now differs from it on every chart that sets the property",
      )
    }
  }

  /** And it is **consumed**: setting it is not reported as a property nobody read. */
  @Test
  fun `setting labelBound draws no unknown-property diagnostic`() {
    val compiled =
      SpecCompiler()
        .compileJson(
          """
          {"width": 120, "height": 80, "padding": 0, "autosize": "none",
           "data": [{"name": "t", "values": [{"c": "a"}]}],
           "scales": [{"name": "x", "type": "band",
                       "domain": {"data": "t", "field": "c"}, "range": "width"}],
           "axes": [{"scale": "x", "orient": "bottom", "labelBound": 40}],
           "marks": []}
          """
            .trimIndent()
        )
    assertTrue(
      compiled.diagnostics.none { "labelBound" in it.message },
      "labelBound was reported as unread: ${compiled.diagnostics.map { it.message }}",
    )
  }
}
