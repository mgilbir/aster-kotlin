package dev.aster.vega.runtime.differential

import dev.aster.vega.fixtures.VegaHeadlessTextEngine
import dev.aster.vega.loader.FileDataLoader
import dev.aster.vega.model.locale.VegaLocale
import dev.aster.vega.runtime.compile.SpecCompiler
import dev.aster.vega.runtime.scale.BandScale
import dev.aster.vega.runtime.scale.LinearScale
import dev.aster.vega.runtime.scale.PointScale
import dev.aster.vega.runtime.scale.TimeScale
import dev.aster.vega.runtime.scale.TransformedScale
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * How many of the corpus's scales `compareScales` actually looks at — counted, not assumed.
 *
 * `FixtureDifferentialTest` runs a test called "scale domains, ranges, bandwidth and ticks match
 * upstream", and for a third of the scales in the corpus it used to check **nothing**: the
 * comparison's `when (scale)` named `linear`, `band` and `point` and fell to `else -> Unit`. 152 of
 * 466 recorded scales went past it — 64 ordinal, 41 sequential-colour, 16 time, and the log, pow,
 * symlog, quantile, quantize, threshold, bin-ordinal and identity families behind them.
 *
 * That is the shape of failure this repository keeps finding: a gate naming a target it does not
 * execute. The continuous families are compared now, and this exists so the rest cannot go quiet
 * again — a scale kind that is not compared has to be **named here**, and the number has to be
 * argued down rather than drifting up.
 *
 * Enabling the continuous ones found two genuine differences immediately, both fixed rather than
 * tolerated: a time domain that kept fractional milliseconds where upstream's `Date` truncates, and
 * a twelve-hour local axis that added twelve absolute hours across a daylight-saving change where
 * upstream steps eleven real ones.
 */
class ScaleComparisonCoverageTest {

  private val root = File(System.getProperty("user.dir")).parentFile

  private val loader = FileDataLoader(File(root, "test-fixtures"))

  /**
   * The scale kinds `compareScales` still walks past, with why.
   *
   * Each needs facts the reference does not record in a comparable form, which is a different
   * problem from the one just fixed — the continuous families were skipped while their domain,
   * range and ticks sat in the reference all along.
   */
  private val NOT_COMPARED =
    mapOf(
      "OrdinalScale" to "a discrete domain mapped to arbitrary values; the range is colours",
      "SequentialColorScale" to "the range is an interpolator, not a pair of numbers",
      "BinOrdinalScale" to "bucket boundaries, recorded as a domain of a different shape",
      "QuantileScale" to "bucket boundaries derived from the data rather than declared",
      "QuantizeScale" to "bucket boundaries derived from the domain",
      "ThresholdScale" to "explicit cuts, with one more range value than domain",
      "IdentityScale" to "maps nothing, so there is nothing to compare",
    )

  private class Counted(val compared: Int, val skipped: Map<String, Int>)

  private fun count(): Counted {
    var compared = 0
    val skipped = sortedMapOf<String, Int>()
    val specs = File(root, "test-fixtures/specs").listFiles { f -> f.name.endsWith(".vg.json") }
    for (file in specs.orEmpty().sortedBy { it.name }) {
      val name = file.name.removeSuffix(".vg.json")
      val reference = File(root, "test-fixtures/reference/$name.reference.json")
      if (!reference.isFile) continue
      val recorded = Differential.readReference(reference).scales
      val compiled =
        SpecCompiler(VegaHeadlessTextEngine(), loader, locale = VegaLocale.EnglishUS)
          .compileJson(file.readText())
      for ((scaleName, _) in recorded) {
        val scale = compiled.scales[scaleName] ?: continue
        val handled =
          scale is LinearScale ||
            scale is TimeScale ||
            scale is TransformedScale ||
            scale is BandScale ||
            scale is PointScale
        if (handled) compared++
        else {
          val kind = scale::class.simpleName ?: "?"
          skipped[kind] = (skipped[kind] ?: 0) + 1
        }
      }
    }
    return Counted(compared, skipped)
  }

  /**
   * Every kind that is skipped is one this class names, with a reason.
   *
   * The assertion that keeps the gap honest: a scale family added later, or one that quietly stops
   * matching its branch, shows up here as an unexplained kind rather than as silence.
   */
  @Test
  fun `every uncompared scale kind is named and explained`() {
    val counted = count()
    assumeTrue(counted.compared > 100, "the corpus is not built; only ${counted.compared} compared")
    assertEquals(
      emptySet<String>(),
      counted.skipped.keys - NOT_COMPARED.keys,
      "a scale kind is being walked past that this class does not name. Either compare it or say " +
        "here why it cannot be compared",
    )
  }

  /**
   * And the continuous families really are compared, which is what the fix was.
   *
   * Without this the test above would pass just as well if `compareScales` went back to naming only
   * `linear`: the skipped kinds would grow, and every one of them is already excused by name.
   */
  @Test
  fun `the continuous families are compared`() {
    val counted = count()
    assumeTrue(counted.compared > 100, "the corpus is not built")
    for (kind in listOf("TimeScale", "LogScale", "PowScale", "SymlogScale")) {
      assertTrue(
        kind !in counted.skipped,
        "$kind is being walked past again; the continuous families were the point of this change",
      )
    }
    // The count that was 314 before the fix and is 343 after it. A floor rather than an equality,
    // because a new fixture may add scales of either sort; it may not take the number *down*.
    assertTrue(
      counted.compared >= 340,
      "only ${counted.compared} scales are compared, down from 343; a family has stopped matching",
    )
  }

  /** What is left, printed, so the number is visible in a log rather than only in a diff. */
  @Test
  fun `the remaining gap is reported`() {
    val counted = count()
    assumeTrue(counted.compared > 100, "the corpus is not built")
    val total = counted.compared + counted.skipped.values.sum()
    println(
      "scale comparison: ${counted.compared} of $total compared; " +
        counted.skipped.entries.joinToString(", ") { "${it.key}=${it.value}" }
    )
    assertTrue(counted.skipped.values.sum() > 0, "nothing is skipped, so this class is obsolete")
  }
}
