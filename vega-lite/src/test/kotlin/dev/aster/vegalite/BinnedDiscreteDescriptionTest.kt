package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A binned field on a **discrete** scale, including the part a picture cannot show.
 *
 * `SUPPORTED_FEATURES.md` filed this as `Partial` for exactly one reason: "the spoken *description*
 * still reads the bin's range where upstream reads the raw column — one difference, on the
 * accessibility text alone". That has stopped being true. The description reads `datum["n"]`, the
 * raw column, which is upstream's own answer.
 *
 * A difference in what a screen reader says is the kind no picture comparison can see, which is why
 * it went on being claimed after it was fixed — and why it is worth a test now that it is not.
 */
class BinnedDiscreteDescriptionTest {

  private val json =
    """
    {"data": {"values": [{"n": 1}, {"n": 4}, {"n": 7}, {"n": 9}]},
     "mark": "bar",
     "encoding": {
       "x": {"bin": true, "field": "n", "type": "ordinal"},
       "y": {"aggregate": "count", "type": "quantitative"}}}
    """
      .trimIndent()

  @Test
  fun `the bin writes its labels into a range column the discrete scale reads`() {
    val result = VegaLiteCompiler().compileJson(json)
    val emitted = VegaJson.write(requireNotNull(result.vega))
    assertTrue(
      "_range" in emitted,
      "an ordinal binned channel did not write a `_range` column, so it has nothing to label with",
    )
  }

  /**
   * The description reads the **raw** column, which is what upstream describes such a mark by.
   *
   * The geometry follows `_range` — the axis, the domain, the grouping — while the spoken text
   * follows the field the specification named. Those are different columns on purpose: a reader
   * hearing "n: 4" is being told the datum, where the axis beside it is showing which bucket that
   * datum fell in.
   */
  @Test
  fun `the description reads the raw column, as upstream does`() {
    val result = VegaLiteCompiler().compileJson(json)
    val emitted = VegaJson.write(requireNotNull(result.vega))
    val description = Regex("\"description\"\\s*:\\s*\\{[^}]*\\}").find(emitted)?.value
    assertTrue(
      description != null,
      "the compiled specification carries no description, so there is nothing to read out",
    )
    // The emitted signal is JSON, so the field reference arrives escaped; what matters is that the
    // description names the field the specification wrote and not the column the bin derived.
    assertTrue(
      "_range" !in description!!,
      "the description reads the bin's range: $description",
    )
    assertTrue("datum[" in description, "the description names no field at all: $description")
  }
}
