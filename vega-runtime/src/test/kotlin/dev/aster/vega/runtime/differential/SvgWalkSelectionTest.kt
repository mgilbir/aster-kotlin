package dev.aster.vega.runtime.differential

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.loader.FileDataLoader
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.RuleNode
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.paintOrder
import dev.aster.vega.scene.paintsNothing
import dev.aster.vega.svg.SvgRenderer
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The SVG walk paints the items the scene says it should, over the whole fixture corpus.
 *
 * **Why this exists.** Four implementations cross a scene deciding what to draw, and until now only
 * two of them were compared with each other — `test-fixtures/scene-walk` holds the Compose walk and
 * the Swift one call for call. The Android canvas and this export were checked by nothing but their
 * own tests, which is the arrangement that let the zero-opacity guard go missing from one walk and
 * a dense axis come out unreadable on one host while every suite stayed green.
 *
 * Their *selection* is now shared code — `paintsNothing` and `paintOrder` — so it can be asserted
 * against rather than compared between. This counts what the scene says must be painted and what
 * the document actually carries, on 198 fixtures.
 *
 * **Rules and labels, and not the rest.** A `RuleNode` becomes exactly one `<line>` and a
 * `TextNode` exactly one `<text>`, so a count is an exact statement. The others are ambiguous by
 * construction and a count of them would be a weaker claim dressed as a strong one: a rect with
 * rounded corners is emitted as a `<path>` rather than a `<rect>`, symbols and paths are both
 * `<path>`, a group's panel is another `<rect>` or `<path>`, and the export has a documented policy
 * for an image it could not resolve. Labels are also the interesting half — the defect that started
 * all of this was 43 text runs against 19.
 *
 * The **one** divergence this found, and the reason the label count is not simply "every text node
 * that paints": this export skips a label whose position is not finite, and the three canvas walks
 * do not. An axis's `tickExtra` label is exactly that — it scales a value its datum does not carry
 * — and upstream emits no element for it either. The canvas renderers hand the platform a draw call
 * at `NaN`, which paints nothing, so the picture agrees and the call is wasted. Recorded here
 * rather than folded into `paintsNothing`, because moving it would change what the two compared
 * walks emit and that is a change to review on its own evidence.
 */
class SvgWalkSelectionTest {

  private val repositoryRoot = File(System.getProperty("user.dir")).parentFile

  /**
   * The nodes a conforming walk reaches, in the order it reaches them.
   *
   * A subtree behind a node that paints nothing is **not** reached, which is why this is a walk and
   * not a filter over `flatten`: an invisible group takes its children with it, and a transparent
   * one does not.
   */
  private fun painted(node: SceneNode, into: MutableList<SceneNode>) {
    if (paintsNothing(node)) return
    into += node
    if (node is GroupNode) for (child in paintOrder(node.children)) painted(child, into)
  }

  /**
   * How many `<name …>` elements the document opens.
   *
   * `\b` rather than a plain `contains`, because `<line` is a prefix of `<linearGradient` and
   * counting the substring reported one extra rule for every gradient in the chart — twelve
   * fixtures' worth of failures that were this test's arithmetic and not the renderer's.
   */
  private fun elements(svg: String, name: String): Int = Regex("<$name\\b").findAll(svg).count()

  @Test
  fun `every rule and every label the scene paints reaches the document`() {
    val specs =
      requireNotNull(File(repositoryRoot, "test-fixtures/specs").listFiles())
        .filter { it.name.endsWith(".vg.json") }
        .sortedBy { it.name }
    assertTrue(specs.size > 150, "the fixture corpus went missing: ${specs.size} specifications")

    val disagreed = mutableListOf<String>()
    var rules = 0
    var labels = 0
    for (spec in specs) {
      val compiled =
        SpecCompiler(
            VegaHeadlessTextEngine(),
            FileDataLoader(File(repositoryRoot, "test-fixtures")),
          )
          .compileJson(spec.readText())
      val scene = compiled.scene ?: continue
      val nodes = mutableListOf<SceneNode>().also { painted(scene.root, it) }
      val svg = SvgRenderer().render(scene).svg

      val expectedRules = nodes.count { it is RuleNode }
      // See the note above: this export emits no element for a label whose position is not finite,
      // where the three canvas walks hand the platform a draw call at `NaN` that paints nothing.
      val expectedLabels = nodes.count { it is TextNode && it.x.isFinite() && it.y.isFinite() }
      rules += expectedRules
      labels += expectedLabels

      val actualRules = elements(svg, "line")
      val actualLabels = elements(svg, "text")
      if (actualRules != expectedRules) {
        disagreed += "${spec.name}: $expectedRules rule(s) painted, $actualRules <line> emitted"
      }
      if (actualLabels != expectedLabels) {
        disagreed += "${spec.name}: $expectedLabels label(s) painted, $actualLabels <text> emitted"
      }
    }

    assertEquals(emptyList<String>(), disagreed, "the SVG walk drew a different set than the scene")
    // Not a vacuous pass. A comparison of two numbers agrees perfectly when both are zero, so the
    // floors are what stop a corpus that quietly stopped compiling — or a walk that quietly stopped
    // walking — from reading as agreement. The corpus draws 2595 rules and 3792 labels today.
    assertTrue(rules > 2000, "only $rules rule(s) across the corpus; the walk found nothing")
    assertTrue(labels > 3000, "only $labels label(s) across the corpus; the walk found nothing")
  }
}
