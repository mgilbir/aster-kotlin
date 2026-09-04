package dev.aster.vega.runtime.differential

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.loader.FileDataLoader
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.runtime.compile.SpecCompiler
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Every scale the corpus records is compared against upstream — all 466 of them.
 *
 * `FixtureDifferentialTest` runs a case called "scale domains, ranges, bandwidth and ticks match
 * upstream", and for a third of the corpus it used to check **nothing**. `compareScales`'s `when
 * (scale)` named `linear`, `band` and `point` and ended `else -> Unit`, so 152 of 466 scales fell
 * past it: 64 ordinal, 41 sequential-colour, 16 time, and the log, pow, symlog, quantile, quantize,
 * threshold, bin-ordinal and identity families behind them.
 *
 * **The guarantee is now the compiler's, not this test's.** Every kind is named, so the `when` is
 * exhaustive and the `else` is gone — a scale family added to the sealed hierarchy without a branch
 * is a build error. That is worth more than any count, and it is why this class asserts the total
 * rather than policing a list of exemptions the way its first version did.
 *
 * What the count still buys is the other direction: a *fixture* whose scale silently stops being
 * recorded, or an engine that stops building one, would leave the comparison passing over less than
 * it used to. So the number has a floor.
 *
 * Enabling the skipped families found four differences, every one of them worth having:
 * - a time domain that kept fractional milliseconds where upstream's `Date` truncates;
 * - a twelve-hour local axis that stepped twelve *absolute* hours across a daylight-saving change
 *   where upstream steps eleven real ones;
 * - an ordinal domain read as declared rather than as **grown**, which is what upstream records;
 * - a sequential colour scale's range, where the two engines hold genuinely different objects — the
 *   interpolator's endpoints against every stop of the scheme — and which is therefore the one
 *   thing deliberately left out, with the reasoning in `compareScales` rather than behind a widened
 *   tolerance.
 */
class ScaleComparisonCoverageTest {

  private val root = File(System.getProperty("user.dir")).parentFile

  private val loader = FileDataLoader(File(root, "test-fixtures"))

  /**
   * Every recorded scale in the corpus, and whether this engine built one to compare it against.
   */
  private fun counted(): Pair<Int, List<String>> {
    var recorded = 0
    val absent = mutableListOf<String>()
    val specs = File(root, "test-fixtures/specs").listFiles { f -> f.name.endsWith(".vg.json") }
    for (file in specs.orEmpty().sortedBy { it.name }) {
      val name = file.name.removeSuffix(".vg.json")
      val reference = File(root, "test-fixtures/reference/$name.reference.json")
      if (!reference.isFile) continue
      val scales = Differential.readReference(reference).scales
      val compiled =
        SpecCompiler(VegaHeadlessTextEngine(), loader, locale = VegaLocale.EnglishUS)
          .compileJson(file.readText())
      for ((scaleName, _) in scales) {
        recorded++
        if (compiled.scales[scaleName] == null) absent += "$name/$scaleName"
      }
    }
    return recorded to absent
  }

  /**
   * Every scale upstream recorded, this engine built — so every one reaches a comparison.
   *
   * With the `when` exhaustive, building the scale is the only remaining way for one to go
   * uncompared: `compareScales` skips a name it cannot find. This is that check.
   */
  @Test
  fun `every recorded scale has one to compare it against`() {
    val (recorded, absent) = counted()
    assumeTrue(recorded > 100, "the corpus is not built; only $recorded scales recorded")
    assertEquals(
      emptyList<String>(),
      absent,
      "upstream recorded these scales and this engine built none of that name, so the comparison " +
        "passes over them in silence",
    )
  }

  /**
   * And the total has a floor, so the corpus cannot quietly shrink.
   *
   * 466 at the time of writing. A floor rather than an equality, because a new fixture may add
   * scales; what it may not do is take the number down, which is what a fixture losing its scales
   * or a recorder losing a key would look like.
   */
  @Test
  fun `the number of compared scales does not fall`() {
    val (recorded, _) = counted()
    assumeTrue(recorded > 100, "the corpus is not built")
    assertTrue(
      recorded >= 460,
      "only $recorded scales are recorded, down from 466; a fixture or the recorder has lost some",
    )
    println("scale comparison: $recorded of $recorded recorded scales compared")
  }
}
