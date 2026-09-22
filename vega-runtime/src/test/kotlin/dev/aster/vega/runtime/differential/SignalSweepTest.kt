@file:OptIn(dev.aster.vega.model.InternalAsterVegaApi::class)

package dev.aster.vega.runtime.differential

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.loader.FileDataLoader
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import dev.aster.vega.runtime.VegaChartController
import java.io.File
import java.util.Locale
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * One chart per **(thing a signal reaches, value written into it)**, compared after the write.
 *
 * Every other corpus here compares a chart **as first drawn**. The fixtures, the gallery, the 1981
 * wild specifications, both schema sweeps and the value sweep all build a chart, draw it once and
 * compare what came out. Not one of them ever changes something and looks again.
 *
 * That leaves half the engine unchecked. `VegaChartController.setSignal` pins a value, cascades it
 * through every signal sourced on it and compiles the specification again — which is what a slider,
 * a dropdown or a fired handler does. `SignalInputTest` asserts what that *should* produce, by
 * hand. Nothing had ever asked upstream what it *does* produce.
 *
 * The values are deliberately not all sensible, for the reason the value sweep exists: a binding is
 * a door a host writes through, and what arrives is whatever its control produced — a null, a word
 * where a number goes, an empty list, a negative where only positives were imagined.
 *
 * **The second run is the one compared.** Upstream renders, sets the signal and renders again; this
 * drives `setSpec` then `setSignal` and reads the scene that recompile produced. Comparing the
 * first would be comparing what every other corpus already covers.
 *
 * **A measurement, not a gate**, as the other sweeps are: it prints a tally and the differences
 * ranked by how many cases each affects. Skips when the sweep has not been built. Arm it with
 * `scripts/signal-sweep.sh`.
 */
class SignalSweepTest {

  @Test
  fun `report how far the signal sweep agrees with upstream`() {
    assumeTrue(
      referenceDir.isDirectory,
      "The signal sweep is not built. Run scripts/signal-sweep.sh.",
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
      "The signal sweep has no references. Run scripts/signal-sweep.sh.",
    )

    var matched = 0
    var differed = 0
    var oursRefused = 0
    val causes = HashMap<String, MutableSet<String>>()
    val perCase = LinkedHashMap<String, Triple<String, Int, String>>()

    for (name in names) {
      val spec = File(specDir, "$name.vg.json")
      if (!spec.isFile) continue
      val referenceFile = File(referenceDir, "$name.reference.json")
      val reference = Differential.readReference(referenceFile)
      val root = VegaJson.parse(referenceFile.readText()) as VegaValue.Obj
      val signal = (root.fields["signal"] as VegaValue.Str).value
      val value = root.fields["value"] ?: VegaValue.Null

      val differences =
        try {
          val controller =
            VegaChartController(textEngine = VegaHeadlessTextEngine(), loader = loader)
          controller.setSpec(spec.readText())
          // The write this sweep exists for. Everything above is the chart as every other corpus
          // already sees it.
          controller.setSignal(signal, value)
          val compiled = controller.lastCompiled
          val scene = compiled?.scene
          if (compiled == null || scene == null) {
            oursRefused++
            perCase[name] = Triple("refused", 0, "no scene after setting '$signal'")
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
    println("==== signal sweep ====")
    println("compared          $compared")
    println(String.format(Locale.ROOT, "matched           %d (%.1f%%)", matched, rate))
    println("differed          $differed")
    println("we produced none  $oursRefused")
    println()
    println("causes, by how many cases each affects:")
    causes.entries
      .sortedByDescending { it.value.size }
      .take(40)
      .forEach { (key, cases) ->
        println(String.format(Locale.ROOT, "  %5d  %s", cases.size, key))
        println("         charts ${cases.map { it.substringBefore("--") }.distinct().take(5)}")
        println("         values ${cases.map { it.substringAfterLast("--") }.distinct().take(6)}")
      }
    println()
    println("per case: ${File(referenceDir.parentFile, "report.tsv")}")
    println("==== end ====")
  }

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
    /** `-PsignalSweepCase=<substring>[,…]`: print every difference for the cases that match. */
    val focus: List<String> =
      System.getProperty("signalSweepCase")
        .orEmpty()
        .split(',')
        .map { it.trim() }
        .filter {
          it.isNotEmpty()
        }

    val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile
    val sweepDir: File = File(repositoryRoot, "build/signal-sweep")
    val specDir: File = File(sweepDir, "specs")
    val referenceDir: File = File(sweepDir, "reference")
    val loader: FileDataLoader = FileDataLoader(File(repositoryRoot, "test-fixtures"))
  }
}
