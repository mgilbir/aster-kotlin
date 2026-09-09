package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `nice` rounds a domain outwards, unless somebody already chose the bounds.
 *
 * ```js
 * if (
 *   getFieldDef(fieldOrDatumDef)?.bin ||
 *   isArray(specifiedDomain) ||
 *   domainMax != null ||
 *   domainMin != null ||
 *   util.contains([ScaleType.TIME, ScaleType.UTC], scaleType)
 * ) {
 *   return undefined;
 * }
 * return isXorY(channel) ? true : undefined;
 * ```
 *
 * Two of those five were wrong here, and the comment beside the code had upstream's rule written
 * out correctly while the code did something else.
 *
 * A domain suppresses it only where it is an **array** — a pair of bounds already chosen. A domain
 * naming a dataset, `{"data": …, "field": …}`, is not bounds at all: the scale still rounds
 * whatever the data turns out to give it, and this suppressed it. And the two **ends** suppress it
 * on their own, either one being a bound somebody picked; this never looked at them, so a scale
 * pinned to `domainMin: -1, domainMax: 7` was niced past both.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ScaleNiceTest {

  private fun niceOf(scaleName: String, encoding: String): VegaValue? {
    val spec =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """{"data":{"values":[{"a":1,"b":2}]},"mark":"point","encoding":$encoding}"""
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val scales = (spec.fields["scales"] as? VegaValue.Arr)?.values.orEmpty()
    val scale =
      scales
        .mapNotNull { it as? VegaValue.Obj }
        .first {
          (it.fields["name"] as? VegaValue.Str)?.value == scaleName
        }
    return scale.fields["nice"]
  }

  private fun yNice(scale: String?): VegaValue? =
    niceOf(
      "y",
      """{"x":{"field":"a","type":"quantitative"},
          "y":{"field":"b","type":"quantitative"${scale?.let { ""","scale":$it""" } ?: ""}}}""",
    )

  private val yes = VegaValue.Bool(true)

  /** With nothing stated, a position scale is niced. */
  @Test
  fun `a position scale with nothing stated is niced`() {
    assertEquals(yes, yNice(null))
  }

  /** An array domain is a pair of bounds somebody chose, and is left alone. */
  @Test
  fun `an array domain suppresses it`() {
    assertNull(yNice("""{"domain":[0,10]}"""))
  }

  /**
   * A domain naming a **dataset** is not bounds — the numbers are not known until the data is read
   * — so the scale still rounds them. This is the case the old rule got backwards.
   */
  @Test
  fun `a domain naming a dataset does not suppress it`() {
    assertEquals(
      yes,
      yNice("""{"domain":{"data":"src","field":"b"}}"""),
      "the bounds are whatever the data gives, so they are still rounded",
    )
  }

  /** Either **end** on its own is a bound somebody picked. */
  @Test
  fun `a stated end suppresses it`() {
    assertNull(yNice("""{"domainMin":-1}"""), "a stated floor is a floor, and is not moved")
    assertNull(yNice("""{"domainMax":7}"""))
    assertNull(yNice("""{"domainMin":-1,"domainMax":7}"""))
  }

  /** `domainMid` is not an end and says nothing about the bounds. */
  @Test
  fun `a stated middle does not suppress it`() {
    assertEquals(yes, yNice("""{"domainMid":3}"""))
  }

  /** A **log** scale is niced like any other continuous one; only time and UTC are exempt. */
  @Test
  fun `a log scale is niced`() {
    assertEquals(yes, yNice("""{"type":"log"}"""))
  }

  /** A time scale is not: d3's ticks already land on calendar boundaries. */
  @Test
  fun `a temporal scale is not niced`() {
    assertNull(
      niceOf(
        "y",
        """{"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"temporal"}}""",
      )
    )
  }

  /** Nor is a binned one, the bin transform having already picked its edges. */
  @Test
  fun `a binned scale is not niced`() {
    val encoding =
      """{"x":{"field":"a","type":"quantitative","bin":true},
          "y":{"field":"b","type":"quantitative"}}"""
    assertNull(niceOf("x", encoding))
    assertEquals(yes, niceOf("y", encoding), "and the other channel is untouched by it")
  }
}
