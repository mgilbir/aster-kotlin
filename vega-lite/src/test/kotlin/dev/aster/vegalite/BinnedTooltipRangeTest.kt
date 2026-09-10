package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A bucketed column reads as its **span** wherever it is read.
 *
 * `formatSignalRef` works the far edge out itself rather than being handed one:
 * ```js
 * if (isFieldDef(fieldOrDatumDef) && isBinning(fieldOrDatumDef.bin)) {
 *   const endField = vgField(fieldOrDatumDef, {expr, binSuffix: 'end'});
 *   return {signal: binFormatExpression(field, endField, format, formatType, config)};
 * }
 * ```
 *
 * so no caller has to say so. A `tooltip` written as a **list** goes through a different path here
 * from the channels' own, and that path passed nothing — so a bucket in a tooltip printed its lower
 * edge as a bare number where the axis beside it read `0 – 10`. Three specifications in the wild
 * corpus list a binned field in a tooltip.
 *
 * A **pre-binned** column still needs its caller: the far edge is the secondary channel's own
 * field, which the definition alone cannot name.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class BinnedTooltipRangeTest {

  private fun tooltip(spec: String): String? {
    val compiled =
      VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "did not compile" }
      ) as VegaValue.Obj
    val mark = (compiled.fields["marks"] as VegaValue.Arr).values.first() as VegaValue.Obj
    val update = (mark.fields["encode"] as VegaValue.Obj).fields["update"] as VegaValue.Obj
    return ((update.fields["tooltip"] as? VegaValue.Obj)?.fields?.get("signal") as? VegaValue.Str)
      ?.value
  }

  private val span =
    """!isValid(datum["bin_maxbins_10_v"]) || !isFinite(+datum["bin_maxbins_10_v"]) ? "null" : """ +
      """format(datum["bin_maxbins_10_v"], "") + " – " + format(datum["bin_maxbins_10_v_end"], "")"""

  /** The reported shape: a binned field listed in a tooltip beside a count. */
  @Test
  fun `a binned field in a tooltip list reads as a span`() {
    assertEquals(
      """{"x": $span, "y": format(datum["__count"], "")}""",
      tooltip(
        """
        {"data":{"values":[{"v":1}]},"mark":"bar",
         "encoding":{"x":{"bin":true,"field":"v","type":"quantitative","title":"x"},
                     "y":{"aggregate":"count","type":"quantitative","title":"y"},
                     "tooltip":[{"bin":true,"field":"v","type":"quantitative","title":"x"},
                                {"aggregate":"count","type":"quantitative","title":"y"}]}}
        """
      ),
    )
  }

  /** A tooltip of one binned field, which is a single definition rather than a list. */
  @Test
  fun `a lone binned tooltip reads as a span`() {
    assertEquals(
      span,
      tooltip(
        """
        {"data":{"values":[{"v":1}]},"mark":"bar",
         "encoding":{"x":{"bin":true,"field":"v","type":"quantitative"},
                     "y":{"aggregate":"count","type":"quantitative"},
                     "tooltip":{"bin":true,"field":"v","type":"quantitative"}}}
        """
      ),
    )
  }

  /** And the derived tooltip, which read the span already — the path that always worked. */
  @Test
  fun `a derived tooltip still reads a bucket as a span`() {
    assertEquals(
      """{"v (binned)": $span, "Count of Records": format(datum["__count"], "")}""",
      tooltip(
        """
        {"data":{"values":[{"v":1}]},"mark":"bar","config":{"mark":{"tooltip":true}},
         "encoding":{"x":{"bin":true,"field":"v","type":"quantitative"},
                     "y":{"aggregate":"count","type":"quantitative"}}}
        """
      ),
    )
  }
}
