package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A stated `"title": null` on any layer takes the caption off the whole axis.
 *
 * ```js
 * export function mergeTitleComponent(v1: Explicit<AxisTitleComponent>, v2: Explicit<AxisTitleComponent>) {
 *   const v1Val = v1.value;
 *   const v2Val = v2.value;
 *   if (v1Val == null || v2Val === null) {
 *     return {explicit: v1.explicit, value: null};
 *   }
 *   ...
 * ```
 *
 * Two layers over one axis each contribute a title, and upstream joins them with a comma rather
 * than picking one — a shared axis says what it is showing. A **null** is not a contribution to
 * join: it is the statement that this axis has no caption, and `mergeTitleComponent` answers `null`
 * for either side being it, whatever the other says. That is how a layer added to a titled chart
 * leaves the titling to the chart.
 *
 * This engine took the null as *its own view's* contribution and kept the sibling's, so such an
 * axis came out captioned with the other layer's field. One specification in the wild corpus
 * differs for that.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class AxisTitleMergeTest {

  private fun title(spec: String, scale: String = "y"): VegaValue? {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val axes =
      (compiled.fields["axes"] as? VegaValue.Arr)?.values.orEmpty().map { it as VegaValue.Obj }
    return axes
      .first { it.string("scale") == scale && it.fields["grid"] != VegaValue.Bool(true) }
      .fields["title"]
  }

  private val data = """"data":{"values":[{"a":1,"b":2}]}"""

  /** Two layers over one `y`, each titling it however the arguments say. */
  private fun layered(first: String, second: String) =
    """{$data,"layer":[
       {"mark":"line","encoding":{"y":{"field":"a","type":"quantitative"$first}}},
       {"mark":"point","encoding":{"y":{"field":"b","type":"quantitative"$second}}}]}"""

  /** The reported shape: the second layer says the axis has no caption. */
  @Test
  fun `a null title on the second layer untitles the axis`() {
    assertNull(title(layered("", ""","axis":{"title":null}""")))
  }

  /** And the first layer saying it is the same statement. */
  @Test
  fun `a null title on the first layer untitles the axis`() {
    assertNull(title(layered(""","axis":{"title":null}""", "")))
  }

  /** It outranks another layer's *stated* title too, which two stated titles would not. */
  @Test
  fun `a null title outranks a stated one`() {
    assertNull(title(layered(""","axis":{"title":null}""", ""","axis":{"title":"Y"}""")))
  }

  /** Whichever side of the merge it arrives on. */
  @Test
  fun `a null title after a stated one still untitles the axis`() {
    assertNull(title(layered(""","axis":{"title":"X"}""", ""","axis":{"title":null}""")))
  }

  /** Three layers, the middle one saying it: the axis has no caption. */
  @Test
  fun `a null title among three layers untitles the axis`() {
    assertNull(
      title(
        """{$data,"layer":[
           {"mark":"line","encoding":{"y":{"field":"a","type":"quantitative"}}},
           {"mark":"point","encoding":{"y":{"field":"b","type":"quantitative",
                                            "axis":{"title":null}}}},
           {"mark":"rule","encoding":{"y":{"field":"a","type":"quantitative",
                                           "axis":{"title":"Z"}}}}]}"""
      )
    )
  }

  /** Two derived titles are joined, which is what a shared axis is for. */
  @Test
  fun `two derived titles are joined`() {
    assertEquals(VegaValue.Str("a, b"), title(layered("", "")))
  }

  /** Two stated titles are joined the same way. */
  @Test
  fun `two stated titles are joined`() {
    assertEquals(
      VegaValue.Str("X, Y"),
      title(layered(""","axis":{"title":"X"}""", ""","axis":{"title":"Y"}""")),
    )
  }

  /** An empty title is a caption that renders to nothing, so the other layer's stands. */
  @Test
  fun `an empty title leaves the other layer's standing`() {
    assertEquals(
      VegaValue.Str("Y"),
      title(layered(""","axis":{"title":""}""", ""","axis":{"title":"Y"}""")),
    )
  }
}
