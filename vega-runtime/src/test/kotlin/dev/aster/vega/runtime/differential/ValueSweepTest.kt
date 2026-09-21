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
 * One chart per **(chart, column of values)** pair, compared against upstream.
 *
 * Every other corpus here varies the **specification** and holds the data still — and the data in
 * all of them is tidy, numbers where numbers go and words where words go. `PropertySweepTest` is
 * one chart with a property changed; the Vega-Lite sweep is one encoding changed; the fixtures, the
 * gallery and the wild corpus are charts somebody drew, with the data they drew them from.
 *
 * Real data is not tidy, and that is where the differences have been. Six defects found in one
 * afternoon were every one of them a value nobody expected to be there — a word where a date was
 * meant, an empty cell in a number column, a null that a stack keyed with the NaNs, a number beside
 * the same number written as text. Not one was reachable by changing a property, because none of
 * them is about a property.
 *
 * So this sweep holds the specification still and varies the **column**: twenty-five columns, each
 * chosen because JavaScript treats it differently from the obvious reading, across twenty charts
 * chosen to cover the journeys a value takes — a scale of each family, a guide of each kind, and a
 * transform that accumulates or orders or formats one.
 *
 * **The claim is agreement, not success**, as in every sweep here: a specification upstream refuses
 * is recorded as a refusal in the manifest rather than dropped.
 *
 * **A measurement, not a gate**, for the same reason `PropertySweepTest` is one: it prints a tally
 * and the differences ranked by how many cases each affects, which is the input to deciding what to
 * fix. Skips when the sweep has not been built. Arm it with `scripts/value-sweep.sh`.
 */
class ValueSweepTest {

  @Test
  fun `report how far the value sweep agrees with upstream`() {
    assumeTrue(
      referenceDir.isDirectory,
      "The value sweep is not built. Run scripts/value-sweep.sh.",
    )

    val names =
      referenceDir
        .listFiles()
        .orEmpty()
        .filter { it.name.endsWith(".reference.json") }
        .map { it.name.removeSuffix(".reference.json") }
        .sorted()
    assumeTrue(names.isNotEmpty(), "The value sweep has no references. Run scripts/value-sweep.sh.")

    var matched = 0
    var differed = 0
    var oursRefused = 0
    val causes = HashMap<String, MutableSet<String>>()
    val drewNothing = LinkedHashMap<String, String>()
    val perCase = LinkedHashMap<String, Triple<String, Int, String>>()

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
            val why =
              compiled.diagnostics
                .firstOrNull { it.severity >= DiagnosticSeverity.ERROR }
                ?.let { "${it.code}: ${shape(it.message)}" } ?: "no scene and no diagnostic"
            drewNothing[name] = why
            oursRefused++
            perCase[name] = Triple("refused", 0, why)
            continue
          }
          sizeDifference(reference, scene) +
            Differential.compareMarks(reference.marks, Differential.flattenScene(scene)) +
            Differential.compareScales(reference.scales, compiled.scales)
        } catch (error: Throwable) {
          val key = "threw: ${error::class.simpleName}: ${shape(error.message ?: "")}"
          causes.getOrPut(key) { linkedSetOf() } += name
          oursRefused++
          perCase[name] = Triple("refused", 0, key)
          continue
        }
      if (differences.isEmpty()) {
        matched++
        perCase[name] = Triple("matched", 0, "")
        continue
      }
      differed++
      perCase[name] = Triple("differed", differences.size, differences.first().toString())
      // **Every difference for the cases asked about, unmasked.** The tally above ranks *shapes*
      // with the numbers taken out, which is the right thing for deciding what to fix and the wrong
      // thing for fixing it: by then the question is what this one chart actually drew, and the
      // report keeps only the first line of it. `-DvalueSweepCase=<substring>` prints the rest.
      if (focus.any { name.contains(it) }) {
        println("---- $name ----")
        differences.forEach { println("  ${it.where}: expected ${it.expected}, got ${it.actual}") }
      }
      differences
        .map { shape("${it.where}: ${it.expected} vs ${it.actual}") }
        .distinct()
        .forEach { key -> causes.getOrPut(key) { linkedSetOf() } += name }
    }

    File(referenceDir.parentFile, "report.tsv")
      .writeText(
        buildString {
          append("case\tstatus\tdifferences\tfirst\n")
          perCase.forEach { (name, row) ->
            append(name)
              .append('\t')
              .append(row.first)
              .append('\t')
              .append(row.second)
              .append('\t')
              .append(row.third.replace('\t', ' '))
              .append('\n')
          }
        }
      )

    val compared = matched + differed + oursRefused
    val rate = if (compared == 0) 0.0 else matched * 100.0 / compared
    println("==== value sweep ====")
    println("compared          $compared")
    println(String.format(Locale.ROOT, "matched           %d (%.1f%%)", matched, rate))
    println("differed          $differed")
    println("we produced none  $oursRefused")
    drewNothing
      .takeIf { it.isNotEmpty() }
      ?.let {
        println()
        println("this engine drew nothing, and why:")
        it.forEach { (name, why) -> println("  $name: $why") }
      }
    println()
    println("causes, by how many cases each affects:")
    causes.entries
      .sortedByDescending { it.value.size }
      .take(40)
      .forEach { (key, cases) ->
        println(String.format(Locale.ROOT, "  %5d  %s", cases.size, key))
        // **Both halves of the name**, because a cause that shows up under one column across many
        // charts is that column's, and one that shows up under many columns in one chart is that
        // chart's — and which of the two it is decides where to look.
        println("         columns ${cases.map { it.substringAfterLast("--") }.distinct().take(5)}")
        println("         charts  ${cases.map { it.substringBefore("--") }.distinct().take(5)}")
      }
    println()
    println("per case: ${File(referenceDir.parentFile, "report.tsv")}")
    println("==== end ====")
  }

  /** The surface size, as a [Differential.Difference] so it ranks beside every other cause. */
  private fun sizeDifference(
    reference: Differential.Reference,
    scene: dev.aster.vega.scene.Scene,
  ): List<Differential.Difference> = buildList {
    if (kotlin.math.abs(reference.width - scene.width) > Differential.GEOMETRY_TOLERANCE) {
      add(Differential.Difference("surface", "width ${reference.width}", "${scene.width}"))
    }
    if (kotlin.math.abs(reference.height - scene.height) > Differential.GEOMETRY_TOLERANCE) {
      add(Differential.Difference("surface", "height ${reference.height}", "${scene.height}"))
    }
  }

  /** A difference's shape: its text with the numbers and the names taken out. */
  private fun shape(text: String): String =
    text
      .replace(Regex("-?\\d+\\.?\\d*(e-?\\d+)?"), "N")
      .replace(Regex("\"[^\"]*\""), "\"…\"")
      .take(140)

  private companion object {
    /**
     * `-DvalueSweepCase=<substring>[,<substring>…]`: which cases print every difference they found.
     *
     * A list rather than one, because a sweep is slow enough that looking at two clusters means
     * looking at them in the same run.
     */
    val focus: List<String> =
      System.getProperty("valueSweepCase")
        .orEmpty()
        .split(',')
        .map { it.trim() }
        .filter {
          it.isNotEmpty()
        }

    val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile
    val sweepDir = File(repositoryRoot, "build/value-sweep")
    val specDir = File(sweepDir, "specs")
    val referenceDir = File(sweepDir, "reference")

    /** The generated charts carry their data inline, so nothing should ask the loader for it. */
    val loader = FileDataLoader(specDir)
  }
}
