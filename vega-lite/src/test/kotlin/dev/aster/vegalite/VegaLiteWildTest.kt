package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Vega-Lite specifications **other people wrote**, compared against upstream's compiler.
 *
 * `VegaLiteGalleryTest` sweeps the 627 examples Vega-Lite ships. Those are upstream's own: written
 * to demonstrate features, by the people who built them, in the version that shipped them. A
 * specification someone wrote to draw a real chart is a different distribution — older schema
 * versions, defaults left unstated, features combined in ways no example demonstrates, and the
 * occasional thing that was never valid. This sweeps 1981 of those, collected from public GitHub
 * repositories by `hyungkwonko/chart-llm` and pinned to a commit — its `docs/data/chart`, and not
 * the `benchmark/` directories beside it, which hold Vega-Lite's own examples under their own names
 * and are already swept by `VegaLiteGalleryTest` at the pinned version.
 *
 * **The claim is agreement, not success.** The question is never whether a specification compiles
 * but whether upstream's compiler and this one make the same thing of it — including agreeing that
 * it cannot be compiled. A specification upstream refuses is not this engine's problem to solve.
 *
 * ### A measurement, and why it is not yet a gate
 *
 * This **reports** and does not fail. That is the shape the gallery sweep had for a long time, and
 * it earned the promotion: it went from 124 of 627 matching to all 627, cause by ranked cause, and
 * only then became something a branch has to keep green. Wiring somebody else's charts straight
 * into `check.sh` would paint every branch red for reasons unconnected to it, and a red gate nobody
 * can act on is a gate people learn to ignore.
 *
 * So this prints a tally and the differences ranked by how often they occur, which is the input to
 * deciding what to fix. When the number is high enough to hold, it becomes an assertion and joins
 * `check.sh` — and the assertion should be a floor on the match rate rather than "all of them",
 * because a corpus of wild specifications will always contain some that upstream itself refuses.
 *
 * Skips when the corpus is absent rather than failing, which is the opposite of
 * `VegaLiteGalleryTest`'s choice and for the opposite reason: a gate must not pass over nothing,
 * but a measurement nobody asked for must not fail a run. Arm it with `scripts/vega-lite-wild.sh`.
 */
class VegaLiteWildTest {

  @Test
  fun `report how far the wild corpus agrees with upstream`() {
    assumeTrue(
      manifestFile.isFile,
      "The wild corpus is not built. Run scripts/vega-lite-wild.sh to fetch and compile it.",
    )

    val manifest = VegaJson.parse(manifestFile.readText()) as VegaValue.Obj
    fun num(key: String) = (manifest.fields[key] as? VegaValue.Num)?.value?.toInt() ?: -1
    val upstreamRefused = (manifest.fields["failedUpstream"] as? VegaValue.Arr)?.values?.size ?: 0

    val names =
      referenceDir
        .listFiles()
        .orEmpty()
        .filter { it.name.endsWith(".vega.json") }
        .map { it.name.removeSuffix(".vega.json") }
        .sorted()

    var matched = 0
    var differed = 0
    var oursRefused = 0
    // Keyed by the difference's shape rather than its full text, so "the same fault in 400 charts"
    // reads as one line with a count instead of four hundred lines.
    val causes = HashMap<String, Int>()
    val examplePerCause = HashMap<String, String>()
    val perSpec = LinkedHashMap<String, Pair<String, Int>>()

    for (name in names) {
      val source = specFile(name)
      if (!source.isFile) continue
      val ours =
        try {
          VegaLiteCompiler().compileJson(source.readText()).vega
        } catch (error: Throwable) {
          // A throw is a difference like any other, and a louder one: upstream produced something.
          null.also {
            val key = "threw: ${error::class.simpleName}: ${shape(error.message ?: "")}"
            causes.merge(key, 1, Int::plus)
            examplePerCause.putIfAbsent(key, name)
          }
        }
      if (ours == null) {
        oursRefused++
        perSpec[name] = "refused" to 0
        continue
      }
      val differences = SpecDiff.compare(VegaJson.parse(referenceFile(name).readText()), ours)
      if (differences.isEmpty()) {
        matched++
        perSpec[name] = "matched" to 0
        continue
      }
      differed++
      perSpec[name] = "differed" to differences.size
      // One vote per shape per specification, so a chart with fifty axes does not outvote fifty
      // charts. What is being ranked is "how many charts does this affect".
      differences
        .map { shape(it) }
        .distinct()
        .forEach { key ->
          causes.merge(key, 1, Int::plus)
          examplePerCause.putIfAbsent(key, name)
        }
    }

    // **Per specification, on disk.** The tally says how far off the corpus is; this says which
    // charts, which is what lets a fix be measured against the charts it was supposed to move
    // rather than against the total. Written rather than printed because 1981 lines is not a thing
    // to read in a log.
    File(referenceDir, "report.tsv")
      .writeText(
        buildString {
          append("name\tstatus\tcauses\n")
          perSpec.forEach { (name, row) ->
            append(name).append('\t').append(row.first).append('\t').append(row.second).append('\n')
          }
        }
      )

    val compared = matched + differed + oursRefused
    val rate = if (compared == 0) 0.0 else matched * 100.0 / compared
    println("==== wild corpus ====")
    println("corpus            ${num("examples")} specification(s), pinned")
    println("upstream compiled ${num("compiled")}, refused $upstreamRefused")
    println("compared          $compared")
    println(String.format("matched           %d (%.1f%%)", matched, rate))
    println("differed          $differed")
    println("we produced none  $oursRefused")
    println()
    println("causes, by how many specifications each affects:")
    causes.entries
      .sortedByDescending { it.value }
      .take(30)
      .forEach { (key, n) ->
        println(String.format("  %5d  %s", n, key))
        println("         e.g. ${examplePerCause[key]}")
      }
    if (causes.size > 30) println("  ... ${causes.size - 30} more distinct cause(s)")
    println("==== end ====")
  }

  /**
   * A difference's **shape**: the same fault in a thousand charts should rank as one cause.
   *
   * Numbers, quoted strings and array indices are what vary between two charts with the same
   * underlying difference, so they are replaced. Without this the ranking is a list of a thousand
   * unique strings and says nothing about what to fix first.
   */
  private fun shape(text: String): String =
    text
      .replace(Regex("\"[^\"]*\""), "\"X\"")
      .replace(Regex("\\[[0-9]+]"), "[i]")
      .replace(Regex("-?[0-9]+\\.?[0-9]*(e-?[0-9]+)?"), "N")
      .take(160)

  private companion object {
    private val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile
    private val specsDir = File(repositoryRoot, "build/vega-lite-wild-corpus/docs/data/chart")
    private val referenceDir = File(repositoryRoot, "build/vega-lite-wild")
    private val manifestFile = File(referenceDir, "manifest.json")

    private fun specFile(name: String) = File(specsDir, "$name.vl.json")

    private fun referenceFile(name: String) = File(referenceDir, "$name.vega.json")
  }
}
