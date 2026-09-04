package dev.aster.vega.runtime.differential

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.model.asDouble
import dev.aster.vega.model.asString
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.scene.GroupNode
import dev.aster.vega.scene.RectD
import dev.aster.vega.scene.SceneNode
import dev.aster.vega.scene.SizeD
import dev.aster.vega.scene.TextEngine
import dev.aster.vega.scene.TextLayout
import dev.aster.vega.scene.TextMetrics
import dev.aster.vega.scene.TextNode
import dev.aster.vega.scene.TextRun
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The `label` transform, compared against upstream — which the row said was impossible.
 *
 * `SUPPORTED_FEATURES.md` has said since it was written that the occupancy bitmap is "not verified
 * against upstream", because `vega-label` rasterises the avoided marks through a canvas, `vega-
 * canvas` answers null under Node, and upstream's own transform throws there: "so there is no
 * reference to compare against".
 *
 * There is. `oracle-js/src/record-label.mjs` installs `canvas` for the run — the same trick
 * `record-wordcloud.mjs` uses, and for the same reason it must stay out of `package.json` — and
 * upstream places labels perfectly well. The vectors are committed rather than rebuilt by the gate,
 * because the gate has no canvas either.
 *
 * **What is compared, and why not pixels.** Installing canvas switches upstream's text measurement
 * from its no-canvas fallback to real font metrics, and the fixture corpus is recorded against the
 * fallback — so upstream's exact `x` here is measured with fonts this engine does not have, and
 * comparing them would compare font metrics rather than the transform. What the occupancy bitmap
 * actually decides is **which anchor each label takes** and **which labels are dropped**, and those
 * are what this holds. A label's own extent then follows from its anchor and its text size, which
 * the ordinary differential corpus already covers everywhere else.
 *
 * That is the honest boundary of the check, and it is exactly the claim the row makes: the two
 * occupancies "agree except on pixels a shape barely grazes, and one pixel is enough to move a
 * label to a different anchor or to drop one a crowded chart would otherwise fit". This measures
 * how often that happens instead of asserting it cannot be measured.
 */
class LabelAgainstUpstreamTest {

  private val root = File(System.getProperty("user.dir")).parentFile

  private val vectors = File(root, "test-fixtures/upstream-vectors-label.json")

  private fun scenarios(): List<VegaValue.Obj> {
    assumeTrue(
      vectors.exists(),
      "no recorded label vectors; regenerate with oracle-js/src/record-label.mjs " +
        "after `npm install --no-save canvas`",
    )
    val root = VegaJson.parse(vectors.readText()) as VegaValue.Obj
    return (root.fields["scenarios"] as VegaValue.Arr).values.map { it as VegaValue.Obj }
  }

  /**
   * A text engine that answers with **upstream's** measurement for the strings it recorded.
   *
   * The load-bearing part of the comparison. A label's width decides whether it fits, which decides
   * its anchor and whether it is dropped at all — so measuring with this engine's own fonts while
   * comparing against placements made with canvas fonts would compare typefaces and call the answer
   * "occupancy". Everything it has no recording for falls through to the ordinary headless engine,
   * which is every string that is not a label.
   */
  private class RecordedWidths(private val widths: Map<Pair<String, Double>, Double>) : TextEngine {
    private val fallback = VegaHeadlessTextEngine()

    override fun measure(text: TextRun, constraint: SizeD?): TextMetrics =
      layout(text, constraint).metrics

    override fun layout(text: TextRun, constraint: SizeD?): TextLayout {
      val laid = fallback.layout(text, constraint)
      val recorded = widths[text.text to text.style.fontSize] ?: return laid
      // Only the width is replaced: the height is the font size and both engines agree on it, and
      // the line breaking is upstream's own fallback, which this engine already reproduces.
      val scale = if (laid.metrics.width > 0.0) recorded / laid.metrics.width else 1.0
      return laid.copy(
        metrics = laid.metrics.copy(width = recorded),
        bounds =
          RectD(
            laid.bounds.left * scale,
            laid.bounds.top,
            laid.bounds.right * scale,
            laid.bounds.bottom,
          ),
      )
    }
  }

  /** This engine's labels, in the order the transform emitted them. */
  private fun ours(spec: String, widths: Map<Pair<String, Double>, Double>): List<Placed> {
    val compiled = SpecCompiler(RecordedWidths(widths)).compileJson(spec)
    val out = mutableListOf<TextNode>()
    fun walk(node: SceneNode) {
      if (node is TextNode) out += node
      if (node is GroupNode) node.children.forEach { walk(it) }
    }
    compiled.scene?.root?.let { walk(it) }
    return out.map {
      Placed(
        text = it.layout.run.text,
        // Upstream's own words for the same anchors: `center`, and `middle` for the vertical one.
        align =
          when (it.layout.run.align.name.lowercase()) {
            "centre" -> "center"
            else -> it.layout.run.align.name.lowercase()
          },
        baseline = it.layout.run.baseline.name.lowercase(),
        shown = it.visible && it.opacity > 0.0 && (it.fill?.opacity ?: 1.0) > 0.0,
      )
    }
  }

  private data class Placed(
    val text: String,
    val align: String?,
    val baseline: String?,
    val shown: Boolean,
  )

  /** What upstream measured each label as, so this engine can be asked the same question. */
  private fun widthsOf(scenario: VegaValue.Obj): Map<Pair<String, Double>, Double> =
    (scenario.fields["labels"] as VegaValue.Arr)
      .values
      .mapNotNull { entry ->
        val label = entry as VegaValue.Obj
        val text = label.fields["text"]?.asString() ?: return@mapNotNull null
        val size = label.fields["fontSize"]?.asDouble() ?: return@mapNotNull null
        val width = label.fields["width"]?.asDouble() ?: return@mapNotNull null
        (text to size) to width
      }
      .toMap()

  private fun theirs(scenario: VegaValue.Obj): List<Placed> =
    (scenario.fields["labels"] as VegaValue.Arr).values.map { entry ->
      val label = entry as VegaValue.Obj
      val shown = (label.fields["opacity"]?.asDouble() ?: 0.0) > 0.0
      Placed(
        text = label.fields["text"]?.asString() ?: "",
        // Upstream leaves both undefined on a label it dropped, so they are only compared where
        // one was placed. A dropped label's anchor is not a decision anybody made.
        align = if (shown) label.fields["align"]?.asString() else null,
        baseline = if (shown) label.fields["baseline"]?.asString() else null,
        shown = shown,
      )
    }

  /**
   * Every label upstream dropped, this engine drops, and every one it kept, this keeps.
   *
   * The half that matters most: a dropped label is a word a reader does not see, and getting the
   * *count* right while dropping a different one would still be wrong.
   */
  @Test
  fun `the same labels are shown and dropped`() {
    val wrong = mutableListOf<String>()
    var compared = 0
    for (scenario in scenarios()) {
      val name = scenario.fields["name"]?.asString()!!
      val spec = VegaJson.write(scenario.fields["spec"]!!)
      val mine = ours(spec, widthsOf(scenario))
      val upstream = theirs(scenario)
      assertEquals(
        upstream.size,
        mine.size,
        "$name emitted ${mine.size} labels where upstream emitted ${upstream.size}",
      )
      upstream.forEachIndexed { index, expected ->
        compared++
        val got = mine[index]
        if (got.shown != expected.shown) {
          wrong +=
            "$name '${expected.text}' ${if (expected.shown) "shown" else "dropped"} upstream, " +
              "${if (got.shown) "shown" else "dropped"} here"
        }
      }
    }
    assertTrue(compared > 40, "only $compared labels compared; the vectors look truncated")
    assertTrue(
      wrong.isEmpty(),
      "${wrong.size} of $compared labels disagree about being drawn at all: $wrong",
    )
  }

  /**
   * The one label whose anchor differs, pinned exactly rather than tolerated as a count.
   *
   * `no-base-mark` puts eight labels in a quarter of the surface with the dots *not* avoided, so
   * only label-on-label collisions count and every placement is decided by the occupancy alone.
   * With the anchor order `top, bottom, right, left`, upstream refuses `'seven'` its second choice
   * and takes the third; this engine finds the second free.
   *
   * That is the difference the row describes, and it is now a number: **one placed label in
   * forty-six**, on the one scenario built to make the bitmap decide everything. Upstream reads the
   * alpha of a rasterised mark, this measures whether a shape overlaps a pixel's square, and they
   * part company where a shape barely grazes one.
   *
   * Pinned as a set rather than a count, the way `known-divergences.json` pins the transform
   * replays: a *new* disagreement fails, and so does fixing this one without deleting its entry.
   */
  private val ANCHOR_DIVERGENCES =
    setOf("no-base-mark 'seven' upstream right/middle, here center/bottom")

  @Test
  fun `each placed label takes the same anchor, but for the one pinned divergence`() {
    val wrong = mutableSetOf<String>()
    var compared = 0
    for (scenario in scenarios()) {
      val name = scenario.fields["name"]?.asString()!!
      val spec = VegaJson.write(scenario.fields["spec"]!!)
      val mine = ours(spec, widthsOf(scenario))
      val upstream = theirs(scenario)
      upstream.forEachIndexed { index, expected ->
        if (!expected.shown) return@forEachIndexed
        val got = mine.getOrNull(index) ?: return@forEachIndexed
        if (!got.shown) return@forEachIndexed
        compared++
        if (got.align != expected.align || got.baseline != expected.baseline) {
          wrong +=
            "$name '${expected.text}' upstream ${expected.align}/${expected.baseline}, " +
              "here ${got.align}/${got.baseline}"
        }
      }
    }
    assertTrue(compared > 30, "only $compared placed labels compared")
    assertEquals(
      ANCHOR_DIVERGENCES,
      wrong,
      "the anchors this engine chooses have moved away from the pinned divergence. A new entry is " +
        "a regression in the occupancy; a missing one means the geometric bitmap now agrees with " +
        "upstream's raster there, and the entry should be deleted along with the row's claim",
    )
  }

  /**
   * The vectors exercise more than one outcome, so agreement means something.
   *
   * A guard on the two above: if every scenario placed every label at the same anchor, both would
   * pass for an engine that always answered "top" and never dropped anything.
   */
  @Test
  fun `the recorded scenarios disagree with each other`() {
    val anchors = mutableSetOf<String>()
    var dropped = 0
    var shown = 0
    for (scenario in scenarios()) {
      for (label in theirs(scenario)) {
        if (label.shown) {
          shown++
          anchors += "${label.align}/${label.baseline}"
        } else {
          dropped++
        }
      }
    }
    assertTrue(anchors.size >= 3, "the vectors only ever use $anchors; they decide nothing")
    assertTrue(dropped > 0, "no label is dropped anywhere, so the crowded case is not exercised")
    assertTrue(shown > 0, "no label is placed anywhere")
  }
}
