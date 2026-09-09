package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A `sort` upstream does not recognise orders nothing.
 *
 * `domainSort` is a chain of arms — an array, a sort field, a sort by encoding, `'descending'`,
 * then `contains(['ascending', undefined], sort)` — and anything that fails all of them falls out
 * of the bottom as `undefined`. The scale then sorts its domain however the data arrived.
 *
 * This engine had a default at the end of each arm instead. An unknown string became the ascending
 * order and an object with nothing in it became `{"op": "min"}` over no field, so three shapes that
 * turn up in hand-written specifications — `"-"`, `""` and `{}` — sorted charts that upstream
 * leaves alone. Seven of the wild corpus's specifications wrote one of them.
 *
 * The near misses matter as much as the malformed ones, and are checked beside them: `"-y"` and
 * `"y"` name a channel to sort by, `{"op": "count"}` and `{"field": …}` are sort fields, and
 * `{"encoding": …}` is a sort by encoding. Every expectation was compiled with upstream.
 */
class DomainSortTest {

  private fun sortOf(sort: String?): VegaValue? {
    val stated = sort?.let { ""","sort":$it""" } ?: ""
    val spec =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":"A","b":2,"c":"x"}]},"mark":"bar",
               "encoding":{"x":{"field":"a","type":"nominal"$stated},
                           "y":{"field":"b","type":"quantitative"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val scale =
      (spec.fields["scales"] as VegaValue.Arr)
        .values
        .mapNotNull { it as? VegaValue.Obj }
        .first { (it.fields["name"] as? VegaValue.Str)?.value == "x" }
    return (scale.fields["domain"] as? VegaValue.Obj)?.fields?.get("sort")
  }

  /** The three shapes from the corpus, none of which upstream recognises. */
  @Test
  fun `a malformed sort orders nothing`() {
    assertNull(sortOf(""""""""), "an empty string is not an order")
    assertNull(sortOf(""""-""""), "a lone minus names no channel to sort by")
    assertNull(sortOf("""{}"""), "an object with no field, count or encoding is not a sort")
  }

  /** Nor is a word that means nothing, by the same last arm of the chain. */
  @Test
  fun `an unknown word orders nothing`() {
    assertNull(sortOf(""""banana""""))
  }

  /** An object that says only which way round, without saying by what, is not a sort either. */
  @Test
  fun `an order with nothing to order by orders nothing`() {
    assertNull(sortOf("""{"order":"descending"}"""))
  }

  /** `sort: null` is the documented way to leave a domain unsorted, and always worked. */
  @Test
  fun `an explicit null orders nothing`() {
    assertNull(sortOf("""null"""))
  }

  /** Nothing stated, and `ascending`, are the plain `true` the domain carries. */
  @Test
  fun `the default and ascending are both true`() {
    assertEquals(VegaValue.Bool(true), sortOf(null))
    assertEquals(VegaValue.Bool(true), sortOf(""""ascending""""))
  }

  /** A channel named with a minus is a sort by that channel, descending. */
  @Test
  fun `a channel named with a minus is still a sort`() {
    val sort = sortOf(""""-y"""") as VegaValue.Obj
    assertEquals(VegaValue.Str("b"), sort.fields["field"])
    assertEquals(VegaValue.Str("descending"), sort.fields["order"])
  }

  /** And without one, ascending. */
  @Test
  fun `a channel named plainly is still a sort`() {
    val sort = sortOf(""""y"""") as VegaValue.Obj
    assertEquals(VegaValue.Str("b"), sort.fields["field"])
    assertNull(sort.fields["order"])
  }

  /** `{"op": "count"}` is a sort field even with no `field`, which is what `isSortField` says. */
  @Test
  fun `a count with no field is still a sort`() {
    val sort = sortOf("""{"op":"count"}""") as VegaValue.Obj
    assertEquals(VegaValue.Str("count"), sort.fields["op"])
  }

  /** An array is turned into an index column, and is unaffected by any of this. */
  @Test
  fun `an array is still a sort`() {
    val sort = sortOf("""["B","A"]""") as VegaValue.Obj
    assertEquals(VegaValue.Str("min"), sort.fields["op"])
    assertEquals(VegaValue.Str("x_a_sort_index"), sort.fields["field"])
  }
}
