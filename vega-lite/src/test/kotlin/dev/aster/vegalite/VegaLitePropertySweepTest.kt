package dev.aster.vegalite

import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.locale.VegaLocale
import java.io.File
import java.util.Locale
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * One specification per **property value Vega-Lite's own schema declares**, compared with upstream.
 *
 * The sister of `PropertySweepTest`, one layer up. That one sweeps what Vega declares and compares
 * the *scene*; this sweeps what **Vega-Lite** declares and compares the **Vega it compiles into**,
 * because that is where a Vega-Lite defect lives. Vega-Lite's whole value is the defaults it
 * supplies — a scale type, a stack transform, a tick count, a label angle, a band size — and every
 * one of them is a property of the specification it emits. Comparing the emitted Vega names the
 * rule that drifted; comparing the picture would say "some marks moved".
 *
 * The surface is much larger than Vega's: 458 definitions, a `MarkDef` of 88 properties, an
 * `Encoding` of 38 channels, a `Config` of 72. The 283 fixtures cover what people draw; this covers
 * what the schema says can be written.
 *
 * **A measurement, not a gate**, for the reason `PropertySweepTest` is one: it prints a tally and
 * the differences ranked by how many cases each affects, which is the input to deciding what to
 * fix. Skips when the sweep has not been built. Arm it with `scripts/vega-lite-property-sweep.sh`.
 */
class VegaLitePropertySweepTest {

  @Test
  fun `report how far the Vega-Lite property sweep agrees with upstream`() {
    assumeTrue(
      referenceDir.isDirectory,
      "The Vega-Lite property sweep is not built. Run scripts/vega-lite-property-sweep.sh.",
    )

    val names =
      referenceDir
        .listFiles()
        .orEmpty()
        .filter { it.name.endsWith(".vega.json") }
        .map { it.name.removeSuffix(".vega.json") }
        .sorted()
    assumeTrue(
      names.isNotEmpty(),
      "The Vega-Lite property sweep has no references. Run scripts/vega-lite-property-sweep.sh.",
    )

    var matched = 0
    var differed = 0
    var oursRefused = 0
    val causes = HashMap<String, MutableSet<String>>()
    val refusedWhy = LinkedHashMap<String, String>()
    val perCase = LinkedHashMap<String, Triple<String, Int, String>>()

    for (name in names) {
      val spec = File(specDir, "$name.vl.json")
      if (!spec.isFile) continue
      val differences =
        try {
          val compilation =
            VegaLiteCompiler(locale = VegaLocale.EnglishUS).compileJson(spec.readText())
          val emitted = compilation.vega
          if (emitted == null) {
            val why =
              compilation.diagnostics
                .firstOrNull { it.severity >= DiagnosticSeverity.ERROR }
                ?.let { "${it.code}: ${shape(it.message)}" } ?: "no specification and no diagnostic"
            refusedWhy[name] = why
            oursRefused++
            perCase[name] = Triple("refused", 0, why)
            continue
          }
          SpecDiff.compare(
            VegaJson.parse(File(referenceDir, "$name.vega.json").readText()),
            emitted,
          )
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
      perCase[name] = Triple("differed", differences.size, shapeless(differences.first()))
      // One vote per shape per case, so a specification with fifty differences does not outvote
      // fifty specifications.
      differences
        .map { shape(shapeless(it)) }
        .distinct()
        .forEach { key ->
          causes.getOrPut(key) { linkedSetOf() } += name
        }
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
    println("==== Vega-Lite property sweep ====")
    println("compared          $compared")
    println(String.format(Locale.ROOT, "matched           %d (%.1f%%)", matched, rate))
    println("differed          $differed")
    println("we produced none  $oursRefused")
    refusedWhy
      .takeIf { it.isNotEmpty() }
      ?.let {
        println()
        println("this compiler produced nothing, and why:")
        it.entries.take(20).forEach { (name, why) -> println("  $name: $why") }
        if (it.size > 20) println("  ... ${it.size - 20} more")
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

  /**
   * A difference with its numbers and names replaced, so that fifty cases of one cause group.
   *
   * The same shaping `PropertySweepTest` uses: every run of digits becomes `N`, so
   * `marks[0].encode.update.x` and `marks[3].encode.update.x` are one cause rather than two.
   */
  private fun shape(text: String): String = text.replace(Regex("-?\\d+(\\.\\d+)?"), "N")

  /** A difference as text, whatever `SpecDiff` chooses to call its entries. */
  private fun shapeless(difference: Any): String = "$difference"

  private companion object {
    private val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile
    private val sweepDir = File(repositoryRoot, "build/vega-lite-property-sweep")
    private val specDir = File(sweepDir, "specs")
    private val referenceDir = File(sweepDir, "reference")
  }
}
