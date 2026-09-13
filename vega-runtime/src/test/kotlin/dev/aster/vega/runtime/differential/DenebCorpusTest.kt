@file:OptIn(dev.aster.vega.model.InternalAsterVegaApi::class)

package dev.aster.vega.runtime.differential

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.loader.FileDataLoader
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.runtime.compile.SpecCompiler
import java.io.File
import java.util.Locale
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Vega specifications **other people wrote**, compared against upstream Vega.
 *
 * `FixtureDifferentialTest` sweeps the 198 fixtures under `test-fixtures/specs`. Those are this
 * repository's own: written to pin down a reading of Vega's semantics, one behaviour at a time, by
 * the person implementing it. A specification someone wrote to draw a real chart is a different
 * distribution — features combined in ways no fixture combines them, layouts that depend on three
 * transforms agreeing, and the occasional thing that only works by accident. This sweeps the Deneb
 * templates: Financial Times Visual Vocabulary chart types plus force layouts, isocontours,
 * treemaps, voronoi, projections and a sankey, pinned to a commit.
 *
 * **The claim is agreement, not success.** The question is never whether a template renders but
 * whether upstream and this engine make the same scene of it.
 *
 * ### A measurement, and why it is not yet a gate
 *
 * This **reports** and does not fail, which is the shape the gallery and wild sweeps had before
 * they earned promotion. Wiring somebody else's charts straight into `check.sh` would paint every
 * branch red for reasons unconnected to it, and a red gate nobody can act on is one people learn to
 * ignore. So it prints a tally and the differences ranked by how many templates each affects, which
 * is the input to deciding what to fix. When the number is high enough to hold it becomes an
 * assertion.
 *
 * ### What the corpus is not
 *
 * The rows are **synthesised** — Power BI supplies the table at run time, so `scripts/deneb.sh`
 * builds one from the column declaration each template carries. Both engines get the same twelve
 * rows, so the comparison is sound, but a template whose layout collapses on nonsense data
 * exercises less of the renderer than it would on real data.
 *
 * And seven templates are **absent from the comparison**, recorded in `refused.tsv` beside the
 * references: one needs a second Deneb function this corpus does not fake, and six use
 * `vega-label`, which places labels by rasterising the marks and so needs a real canvas the oracle
 * deliberately does not have. `label` is the one transform here that no fixture uses, and it is the
 * one that cannot be referenced — so this corpus adds combinations rather than transform types.
 *
 * Skips when the corpus is absent rather than failing, which is the opposite of
 * `FixtureDifferentialTest`'s choice and for the opposite reason: a gate must not pass over
 * nothing, but a measurement nobody asked for must not fail a run. Arm it with `scripts/deneb.sh`.
 */
class DenebCorpusTest {

  @Test
  fun `report how far the Deneb corpus agrees with upstream`() {
    assumeTrue(
      referenceDir.isDirectory,
      "The Deneb corpus is not built. Run scripts/deneb.sh to fetch and render it.",
    )

    val names =
      referenceDir
        .listFiles()
        .orEmpty()
        .filter { it.name.endsWith(".reference.json") }
        .map { it.name.removeSuffix(".reference.json") }
        .sorted()
    assumeTrue(names.isNotEmpty(), "The Deneb corpus has no references. Run scripts/deneb.sh.")

    var matched = 0
    var differed = 0
    var oursRefused = 0
    // Keyed by the difference's shape rather than its full text, so "the same fault in forty
    // charts"
    // reads as one line with a count instead of forty lines.
    val causes = HashMap<String, Int>()
    val examplePerCause = HashMap<String, String>()
    // Listed in full rather than ranked: a template this engine will not draw at all is worth more
    // than the thirty-first geometry difference, and there are never many of them.
    val drewNothing = LinkedHashMap<String, String>()
    val perSpec = LinkedHashMap<String, Pair<String, Int>>()

    for (name in names) {
      val spec = File(specDir, "$name.vg.json")
      if (!spec.isFile) continue
      val reference = Differential.readReference(File(referenceDir, "$name.reference.json"))
      val differences =
        try {
          val compiled =
            SpecCompiler(VegaHeadlessTextEngine(), loader, locale = VegaLocale.EnglishUS)
              .compileJson(spec.readText())
          val scene = compiled.scene
          if (scene == null) {
            // A specification this engine declines to draw at all, which is a difference like any
            // other and a louder one: upstream produced a scene. Reported by the diagnostic that
            // explains it rather than as a bare refusal, because that is what says where to look.
            // `>= ERROR`, not `== ERROR`. A compile that *threw* reports FATAL, and asking for the
            // one severity read that as a silent refusal: the voronoi crash was listed here as "no
            // scene and no diagnostic" while the diagnostic naming the exception sat beside it. A
            // report that hides the loudest failure is worse than none.
            val why =
              compiled.diagnostics
                .firstOrNull { it.severity >= DiagnosticSeverity.ERROR }
                ?.let { "${it.code}: ${shape(it.message)}" } ?: "no scene and no diagnostic"
            drewNothing[name] = why
            oursRefused++
            perSpec[name] = "refused" to 0
            continue
          }
          Differential.compareMarks(reference.marks, Differential.flattenScene(scene)) +
            Differential.compareScales(reference.scales, compiled.scales)
        } catch (error: Throwable) {
          // A throw is a difference like any other, and a louder one: upstream produced a scene.
          val key = "threw: ${error::class.simpleName}: ${shape(error.message ?: "")}"
          causes.merge(key, 1, Int::plus)
          examplePerCause.putIfAbsent(key, name)
          oursRefused++
          perSpec[name] = "refused" to 0
          continue
        }
      if (differences.isEmpty()) {
        matched++
        perSpec[name] = "matched" to 0
        continue
      }
      differed++
      perSpec[name] = "differed" to differences.size
      // One vote per shape per template, so a chart with fifty marks does not outvote fifty charts.
      // What is being ranked is "how many templates does this affect".
      differences
        .map { shape("${it.where}: ${it.expected} vs ${it.actual}") }
        .distinct()
        .forEach { key ->
          causes.merge(key, 1, Int::plus)
          examplePerCause.putIfAbsent(key, name)
        }
    }

    File(referenceDir, "report.tsv")
      .writeText(
        buildString {
          append("name\tstatus\tdifferences\n")
          perSpec.forEach { (name, row) ->
            append(name).append('\t').append(row.first).append('\t').append(row.second).append('\n')
          }
        }
      )

    val refused = File(referenceDir, "refused.tsv").takeIf { it.isFile }?.readLines()?.drop(1)
    val compared = matched + differed + oursRefused
    val rate = if (compared == 0) 0.0 else matched * 100.0 / compared
    println("==== deneb corpus ====")
    println("upstream rendered ${names.size}, refused ${refused?.size ?: 0}")
    println("compared          $compared")
    // `Locale.ROOT`, so the rate reads the same on a machine whose decimal separator is a comma.
    println(String.format(Locale.ROOT, "matched           %d (%.1f%%)", matched, rate))
    println("differed          $differed")
    println("we produced none  $oursRefused")
    refused
      ?.takeIf { it.isNotEmpty() }
      ?.let {
        println()
        println("not comparable, and why:")
        it.forEach { row -> println("  ${row.replace('\t', ' ')}") }
      }
    drewNothing
      .takeIf { it.isNotEmpty() }
      ?.let {
        println()
        println("this engine drew nothing, and why:")
        it.forEach { (name, why) -> println("  $name: $why") }
      }
    println()
    println("causes, by how many templates each affects:")
    causes.entries
      .sortedByDescending { it.value }
      .take(30)
      .forEach { (key, n) ->
        println(String.format(Locale.ROOT, "  %5d  %s", n, key))
        println("         e.g. ${examplePerCause[key]}")
      }
    println()
    println("per template: ${File(referenceDir, "report.tsv")}")
    println("==== end ====")
  }

  /**
   * A difference's **shape**: its text with the numbers and the names taken out, so two charts
   * disagreeing the same way about different values rank as one cause rather than two.
   */
  private fun shape(text: String): String =
    text
      .replace(Regex("-?\\d+\\.?\\d*(e-?\\d+)?"), "N")
      .replace(Regex("\"[^\"]*\""), "\"…\"")
      .take(140)

  private companion object {
    val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile
    val specDir = File(repositoryRoot, "build/deneb-specs")
    val referenceDir = File(repositoryRoot, "build/deneb-reference")

    /**
     * A **file** loader rooted at the prepared specifications, as every other differential test
     * uses: these must run from a checked-out tree with no network. The prepared templates carry
     * their data inline, so nothing should ask it for anything.
     */
    val loader = FileDataLoader(specDir)
  }
}
