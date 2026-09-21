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

  /**
   * Cases where upstream is **wrong** and this compiler deliberately does not follow it.
   *
   * A sweep that only counts differences invites the next reader to close them all, and one of
   * these must not be closed: matching it would mean emitting a specification that draws nothing.
   * They are counted apart from the rest so the number left to fix is the number actually left to
   * fix.
   *
   * Keyed by the prefix of a case name, with the reason spelled out. Anything listed here is still
   * compared — it is reported under its own heading rather than skipped, because the day upstream
   * fixes one of these the case should start failing loudly rather than sitting in a skip list.
   */
  private val accepted =
    mapOf(
      "encoding-theta-timeUnit-" to
        "upstream reads `…_offsetted_rect_start` for a bucketed instant on a polar channel, and " +
          "writes no formula producing it: `useRectOffsetField = fieldDef.timeUnit && " +
          "bandPosition !== 0.5` is true when `bandPosition` is *undefined*, which it is for an " +
          "arc — no `timeUnitBandPosition` is configured for one — while the formulas that would " +
          "write those columns are guarded by `rectBandPosition !== undefined && !== 0.5`. The " +
          "reference names a column no transform in it produces, so the angle resolves to nothing " +
          "and the arc is not drawn. This compiler reads the bucket's own column.",
      "encoding-radius-timeUnit-" to
        "the same upstream defect as the theta case above, on the other polar channel: " +
          "`innerRadius` and `outerRadius` name `…_offsetted_rect_start` and `…_offsetted_rect_end` " +
          "where the only columns written are the bucket's own and its `_end`.",
      "encoding-theta-bandPosition-8" to BAND_POSITION_OUT_OF_RANGE,
      "encoding-theta-bandPosition--4" to BAND_POSITION_OUT_OF_RANGE,
    )

  private fun acceptedReason(name: String): String? =
    accepted.entries.firstOrNull { name.startsWith(it.key) }?.value

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
    val acceptedCases = LinkedHashMap<String, String>()
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
      val reason = acceptedReason(name)
      if (reason != null) {
        acceptedCases[name] = reason
        perCase[name] = Triple("accepted", differences.size, shapeless(differences.first()))
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

    // The accepted ones count as compared, because they were — and not as matched, because they did
    // not. The rate is what agrees; the line below it says how much of the remainder is deliberate.
    val compared = matched + differed + oursRefused + acceptedCases.size
    val rate = if (compared == 0) 0.0 else matched * 100.0 / compared
    println("==== Vega-Lite property sweep ====")
    println("compared          $compared")
    println(String.format(Locale.ROOT, "matched           %d (%.1f%%)", matched, rate))
    println("differed          $differed")
    println("accepted          ${acceptedCases.size} (upstream is wrong; see below)")
    println("we produced none  $oursRefused")
    acceptedCases
      .takeIf { it.isNotEmpty() }
      ?.let { cases ->
        println()
        println("accepted divergences, deliberately not matched:")
        cases.values.distinct().forEach { why ->
          val affected = cases.entries.filter { it.value == why }.map { it.key }
          println("  ${affected.size} case(s): $why")
          println("         ${affected.take(3)}${if (affected.size > 3) " …" else ""}")
        }
      }
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
    /**
     * `interpolatedSignalRef` reads the row through `datum[…]` only for a band position strictly
     * *between* the edges:
     * ```js
     * const expr = !isSignalRef(bandPosition) && 0 < bandPosition && bandPosition < 1 ? 'datum' : undefined;
     * ```
     *
     * which is right at exactly 0 and exactly 1, where the name is written as a **field** reference
     * and a plain column name is what a field reference wants. Outside `[0, 1]` it is neither: the
     * interpolation branch is taken, so the name lands inside a **signal**, and a plain column name
     * there is read as a signal of that name. `vega.parse` rejects the result outright —
     * *Unrecognized signal name: "v_start"* — so the chart does not load at all. This compiler
     * writes `datum["v_start"]`, which is what the signal branch means everywhere it is reachable.
     */
    const val BAND_POSITION_OUT_OF_RANGE =
      "upstream writes a band position outside [0, 1] into a *signal* using bare column names — " +
        "`scale(\"theta\", 5 * v_start + -4 * v_end)` — because its `datum` guard is " +
        "`0 < bandPosition && bandPosition < 1`, which is right at the two edges, where the name " +
        "is a field reference, and wrong outside them, where it is a signal. `vega.parse` refuses " +
        "the result: Unrecognized signal name: \"v_start\". Matching it would emit a chart that " +
        "does not load."

    private val repositoryRoot: File = File(System.getProperty("user.dir")).parentFile
    private val sweepDir = File(repositoryRoot, "build/vega-lite-property-sweep")
    private val specDir = File(sweepDir, "specs")
    private val referenceDir = File(sweepDir, "reference")
  }
}
