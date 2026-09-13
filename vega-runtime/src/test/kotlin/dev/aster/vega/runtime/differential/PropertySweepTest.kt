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
 * One chart per **property value Vega's own schema declares**, compared against upstream.
 *
 * Every other corpus here is a collection of charts somebody drew: the 200 fixtures, Vega-Lite's
 * 627 examples, 1981 specifications from GitHub, 63 Deneb templates. All four agree with upstream
 * to the last mark, which is why they have stopped finding anything — they sample what people
 * *use*. What they cannot reach is a property nobody happened to set, or a value of it nobody
 * happened to choose: `tickBand: "extent"`, `labelOverlap: "greedy"`, `align: "all"`,
 * `bandPosition: 0`.
 *
 * `vega/build/vega-schema.json` is the list of them and it is machine-readable, so
 * `oracle-js/src/property-sweep.js` walks it and writes one small bar chart per (property, value)
 * pair — the same chart every time, with one property changed, so a difference names its own cause.
 * The sweep is the declared surface rather than the used one.
 *
 * **The claim is agreement, not success**, as in every sweep here: a value upstream refuses is
 * recorded as a refusal in the manifest rather than dropped, and this compares what upstream drew.
 *
 * **A measurement, not a gate**, for the same reason `DenebCorpusTest` is one: it prints a tally
 * and the differences ranked by how many cases each affects, which is the input to deciding what to
 * fix. Skips when the sweep has not been built. Arm it with `scripts/property-sweep.sh`.
 */
class PropertySweepTest {

  @Test
  fun `report how far the schema property sweep agrees with upstream`() {
    assumeTrue(
      referenceDir.isDirectory,
      "The property sweep is not built. Run scripts/property-sweep.sh.",
    )

    val names =
      referenceDir
        .listFiles()
        .orEmpty()
        .filter { it.name.endsWith(".reference.json") }
        .map { it.name.removeSuffix(".reference.json") }
        .sorted()
    assumeTrue(
      names.isNotEmpty(),
      "The property sweep has no references. Run scripts/property-sweep.sh.",
    )

    var matched = 0
    var differed = 0
    var oursRefused = 0
    val causes = HashMap<String, MutableSet<String>>()
    val drewNothing = LinkedHashMap<String, String>()
    // The first difference is kept with the count, because a sweep of 600 cases is only useful if
    // its report says what happened without a second run: the ranked list below says which causes
    // are common, and this says what each individual case actually disagreed about.
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
          // The **surface size** is compared as a difference like any other, because half of what
          // a guide property decides is how much room the guide takes: a `labelAngle` or a
          // `titlePadding` that is read wrongly moves nothing inside the plot and makes the drawing
          // the wrong size. The tolerance is the reference's own rounding, as
          // `FixtureDifferentialTest` uses.
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
      // One vote per shape per case, so a chart with fifty marks does not outvote fifty charts.
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
    println("==== schema property sweep ====")
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
        // The **properties** rather than the case names: a cause that shows up under one property
        // is that property's, and one that shows up under nine is something they share.
        println("         ${cases.map { it.substringBeforeLast('-') }.distinct().take(6)}")
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
    val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile
    val sweepDir = File(repositoryRoot, "build/property-sweep")
    val specDir = File(sweepDir, "specs")
    val referenceDir = File(sweepDir, "reference")

    /** The generated charts carry their data inline, so nothing should ask the loader for it. */
    val loader = FileDataLoader(specDir)
  }
}
