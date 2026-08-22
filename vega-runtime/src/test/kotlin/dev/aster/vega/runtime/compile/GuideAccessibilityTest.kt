package dev.aster.vega.runtime.compile

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.scene.AccessibilityTree
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.flatten
import dev.aster.vegalite.VegaLiteInput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `aria` and `description` on a guide.
 *
 * Neither is visible in a differential comparison — upstream's scenegraph carries them as
 * properties of the guide group and nothing about the drawing changes — so this is where they are
 * pinned. They are worth pinning: a chart whose axis is self-explanatory in context wants to say so
 * rather than have its whole scale read out, and `aria: false` is the only way to take a decorative
 * guide out of the accessibility tree.
 */
class GuideAccessibilityTest {

  private fun spec(axis: String, legend: String) =
    """
    {
      "width": 120, "height": 80, "padding": 5,
      "data": [{"name": "t", "values": [{"c": "a", "v": 1}]}],
      "scales": [
        {"name": "x", "type": "band", "domain": {"data": "t", "field": "c"}, "range": "width"},
        {"name": "colour", "type": "ordinal", "domain": {"data": "t", "field": "c"},
         "range": "category"}
      ],
      "axes": [{"orient": "bottom", "scale": "x"$axis}],
      "legends": [{"fill": "colour"$legend}]
    }
    """
      .trimIndent()

  private fun guide(json: String, role: String): SceneNode {
    val compiled = SpecCompiler(VegaHeadlessTextEngine()).compileJson(json)
    return requireNotNull(compiled.scene)
      .flatten()
      .map { it.node }
      .first {
        it.metadata.role == role
      }
  }

  @Test
  fun `a guide describes itself from its scale by default`() {
    val axis = guide(spec("", ""), "axis")
    assertNotNull(axis.metadata.accessibility, "the axis has no caption")
    assertNotNull(guide(spec("", ""), "legend").metadata.accessibility)
  }

  @Test
  fun `an explicit description replaces the generated caption`() {
    val axis = guide(spec(""", "description": "months of the year"""", ""), "axis")
    assertEquals("months of the year", axis.metadata.accessibility?.label)

    val legend = guide(spec("", """, "description": "one colour per crop""""), "legend")
    assertEquals("one colour per crop", legend.metadata.accessibility?.label)
  }

  /**
   * A title is a guide too, and `aria: false` is the only way to keep a decorative heading — a
   * watermark, a chart drawn twice with one copy labelled — out of what a screen reader reads.
   */
  @Test
  fun `a title takes aria, name and interactive`() {
    val json =
      """
      {
        "width": 100, "height": 60, "padding": 5,
        "title": {"text": "Heading", "subtitle": "and a subtitle", "name": "banner",
                  "aria": false, "interactive": false},
        "data": [{"name": "t", "values": [{"v": 1}]}]
      }
      """
        .trimIndent()
    for (role in listOf("title-text", "title-subtitle")) {
      val node = guide(json, role)
      assertNull(node.metadata.accessibility, "$role is still in the accessibility tree")
      assertEquals("banner", node.metadata.markName)
      assertEquals(false, node.metadata.interactive)
    }

    val plain =
      guide(
        json.replace(""""aria": false, "interactive": false""", """"aria": true"""),
        "title-text",
      )
    assertNotNull(plain.metadata.accessibility)
    assertEquals("Title text 'Heading'", plain.metadata.accessibility?.label)
  }

  @Test
  fun `aria false takes the guide out of the accessibility tree`() {
    assertNull(guide(spec(""", "aria": false""", ""), "axis").metadata.accessibility)
    assertNull(guide(spec("", """, "aria": false"""), "legend").metadata.accessibility)
  }

  /** A description that is only whitespace is not a description; the generated caption stands. */
  @Test
  fun `a blank description leaves the generated caption alone`() {
    val blank = guide(spec(""", "description": "   """", ""), "axis")
    assertEquals(
      guide(spec("", ""), "axis").metadata.accessibility?.label,
      blank.metadata.accessibility?.label,
    )
  }

  /**
   * The summary threshold counts **data marks**, measured on a real compiled chart.
   *
   * The numbers are the point. 118 points, two axes and a legend is 121 focusable elements, so a
   * rule that counted everything collapsed the whole tree at 118 marks — the data was not dense and
   * a reader lost it anyway, together with the axes and the legend, which are three elements and
   * are exactly what is worth reading when the marks cannot be walked.
   *
   * Compiled through Vega-Lite because the legend is what makes the arithmetic tip, and this is the
   * shape a host actually has: a scatter plot coloured by a category.
   */
  @Test
  fun `a chart just under the cap keeps its marks, and its guides survive a chart over it`() {
    fun scene(points: Int) =
      requireNotNull(
        SpecCompiler(VegaHeadlessTextEngine())
          .compileJson(
            requireNotNull(
              VegaLiteInput.toVega(
                  """
                {"width": 300, "height": 150,
                 "data": {"values": [${(1..points).joinToString(", ") {
                  """{"c": $it, "v": ${it % 7}, "g": "cat ${it % 12}"}"""
                }}]},
                 "mark": "point",
                 "encoding": {"x": {"field": "c", "type": "quantitative"},
                              "y": {"field": "v", "type": "quantitative"},
                              "color": {"field": "g", "type": "nominal"}}}
                """
                    .trimIndent()
                )
                .vegaJson
            )
          )
          .scene
      )

    val underTheCap = scene(AccessibilityTree.MAX_EXPOSED_MARKS - 2)
    val focusable =
      underTheCap.flatten().count {
        val descriptor = it.node.metadata.accessibility
        it.node.visible && descriptor != null && descriptor.focusable
      }
    val marks = underTheCap.flatten().count { it.node.metadata.role == "mark" }
    // Two axes and a legend past the mark count: the arithmetic the old rule got wrong.
    assertEquals(AccessibilityTree.MAX_EXPOSED_MARKS + 1, focusable)
    assertEquals(AccessibilityTree.MAX_EXPOSED_MARKS - 2, marks)

    val elements = AccessibilityTree.elements(underTheCap)
    assertFalse(elements.any { it.isSummary }, "the data is not dense: ${elements.size} elements")
    assertEquals(focusable, elements.size)

    // One mark over the cap, and the guides are still there beside the summary.
    val over = AccessibilityTree.elements(scene(AccessibilityTree.MAX_EXPOSED_MARKS + 1))
    assertTrue(over.first().isSummary)
    assertEquals(3, over.size - 1, "two axes and a legend: ${over.drop(1).map { it.label }}")
    assertFalse(over.drop(1).any { it.isSummary })
  }
}
