package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A domain's `sort` is settled where the domains are **merged**, not where each was built.
 *
 * `mergeDomains` takes the sorts every view contributed, drops what a union cannot express, and
 * writes one sort for the whole scale:
 * ```js
 * const unionDomainSorts = unique(sorts.map(s => {
 *   if (isBoolean(s) || !('op' in s) || hasOwnProperty(UNIONDOMAIN_SORT_OP_INDEX, s.op)) return s;
 *   log.warn(log.message.domainSortDropped(s));
 *   return true;
 * }), hash);
 * ```
 *
 * This engine settled some of it and not the rest: the `count`/`ascending` cleanups were there, and
 * the two rules that give a request *up* were not. So a scale shared by two layers where one of
 * them sorted its categories by a `sum` came out asking Vega for a sum across two datasets, which
 * is a total there is nothing to total; and two views that disagreed came out with a sort inside
 * each part of the union, which sorts the pieces rather than the whole.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class MergedDomainSortTest {

  private fun domain(spec: String, scale: String = "x"): VegaValue {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val scales = (compiled.fields["scales"] as? VegaValue.Arr)?.values.orEmpty()
    val found = scales.first { (it as VegaValue.Obj).string("name") == scale } as VegaValue.Obj
    return requireNotNull(found.fields["domain"]) { "no domain" }
  }

  /** The expectation, written as the JSON upstream compiled. */
  private fun json(text: String) = VegaJson.parse(text)

  private fun chart(sort: String) =
    """{"data":{"values":[{"c":"x","b":1}]},"mark":"bar",
       "encoding":{"x":{"field":"c","type":"nominal","sort":$sort},
                   "y":{"field":"b","type":"quantitative"}}}"""

  /** Two layers over the same table, each sorting the shared categorical scale its own way. */
  private fun layered(first: String, second: String?, data: String = "") =
    """{"data":{"values":[{"c":"x","b":1}]},"layer":[
         {${data}"mark":"bar","encoding":{"x":{"field":"c","type":"nominal","sort":$first},
                                          "y":{"field":"b","type":"quantitative"}}},
         {"mark":"point","encoding":{"x":{"field":"c","type":"nominal"${sortOf(second)}},
                                     "y":{"field":"b","type":"quantitative"}}}]}"""

  /** A view that states no sort at all, which is not the same request as `"sort": null`. */
  private fun sortOf(sort: String?) = if (sort == null) "" else ",\"sort\":$sort"

  // -- One domain -------------------------------------------------------------------------------

  /** Sorting a column by an aggregate of itself is the natural order. */
  @Test
  fun `a sort by the domain's own field is the natural order`() {
    assertEquals(
      json("""{"data":"source_0","field":"c","sort":true}"""),
      domain(chart("""{"op":"sum","field":"c"}""")),
    )
  }

  /** With a direction to it, the direction is all that is left. */
  @Test
  fun `a descending sort by the domain's own field keeps only its direction`() {
    assertEquals(
      json("""{"data":"source_0","field":"c","sort":{"order":"descending"}}"""),
      domain(chart("""{"op":"sum","field":"c","order":"descending"}""")),
    )
  }

  /** A `count` counts rows, so the column it was written beside says nothing. */
  @Test
  fun `a count sort drops the field it was written beside`() {
    assertEquals(
      json("""{"data":"source_0","field":"c","sort":{"op":"count"}}"""),
      domain(chart("""{"op":"count","field":"b"}""")),
    )
  }

  /** `ascending` is the order a sort has anyway. */
  @Test
  fun `an explicitly ascending sort drops the order`() {
    assertEquals(
      json("""{"data":"source_0","field":"c","sort":{"op":"sum","field":"b"}}"""),
      domain(chart("""{"op":"sum","field":"b","order":"ascending"}""")),
    )
  }

  /** A sort by another column stands as it was written. */
  @Test
  fun `a sort by another field is left alone`() {
    assertEquals(
      json("""{"data":"source_0","field":"c","sort":{"op":"sum","field":"b"}}"""),
      domain(chart("""{"op":"sum","field":"b"}""")),
    )
  }

  // -- One domain, several sorts ---------------------------------------------------------------

  /** A single non-default aggregate among them is a choice somebody made, and it wins. */
  @Test
  fun `one stated aggregate outranks the default one`() {
    assertEquals(
      json("""{"data":"source_0","field":"c","sort":{"op":"sum","field":"b"}}"""),
      domain(layered("""{"op":"sum","field":"b"}""", """{"op":"min","field":"b"}""")),
    )
  }

  /** Two of them are a disagreement, and the natural order privileges neither. */
  @Test
  fun `two stated aggregates leave the domain sorted naturally`() {
    assertEquals(
      json("""{"data":"source_0","field":"c","sort":true}"""),
      domain(layered("""{"op":"sum","field":"b"}""", """{"op":"mean","field":"b"}""")),
    )
  }

  // -- A union of domains -----------------------------------------------------------------------

  /**
   * The reported shape: a union sorted by a `sum`, which is a total across datasets that Vega has
   * nothing to compute from. The request is given up rather than passed on.
   */
  @Test
  fun `a union gives up an aggregate it cannot compute`() {
    assertEquals(
      json(
        """{"fields":[{"data":"source_1","field":"c"},{"data":"source_0","field":"c"}],"sort":true}"""
      ),
      // Both parts ask for the same `sum`, so they agree: what the union cannot do is compute it.
      domain(
        layered(
          """{"op":"sum","field":"b"}""",
          """{"op":"sum","field":"b"}""",
          data = """"data":{"values":[{"c":"y","b":2}]},""",
        )
      ),
    )
  }

  /** The three that a union *can* compute are passed on. */
  @Test
  fun `a union keeps an aggregate every dataset can answer`() {
    assertEquals(
      json(
        """{"fields":[{"data":"source_1","field":"c"},{"data":"source_0","field":"c"}],
           "sort":{"op":"min","field":"b"}}"""
      ),
      domain(
        layered(
          """{"op":"min","field":"b"}""",
          """{"op":"min","field":"b"}""",
          data = """"data":{"values":[{"c":"y","b":2}]},""",
        )
      ),
    )
  }

  /**
   * Sorts that disagree are settled the same way: naturally, and once, for the whole union. A view
   * that asked for nothing asked for the natural order, which is a request like any other.
   */
  @Test
  fun `a union whose parts disagree sorts the whole naturally`() {
    assertEquals(
      json(
        """{"fields":[{"data":"source_0","field":"c"},{"data":"data_2","field":"c"}],"sort":true}"""
      ),
      domain(layered("""{"op":"min","field":"b"}""", null)),
    )
  }

  /** Where every part asked for *no* sort, the union asks for none either. */
  @Test
  fun `a union no part of which sorts is written without a sort`() {
    assertEquals(
      json("""{"fields":[{"data":"data_1","field":"c"},{"data":"data_0","field":"c"}]}"""),
      domain(layered("null", "null", data = """"data":{"values":[{"c":"y","b":2}]},""")),
    )
  }

  /** And a plain `"descending"`, which both parts expand the same way, stays. */
  @Test
  fun `a union both of whose parts run backwards keeps the comparator`() {
    assertEquals(
      json(
        """{"fields":[{"data":"source_1","field":"c"},{"data":"source_0","field":"c"}],
           "sort":{"op":"min","field":"c","order":"descending"}}"""
      ),
      domain(
        layered(
          "\"descending\"",
          "\"descending\"",
          data = """"data":{"values":[{"c":"y","b":2}]},""",
        )
      ),
    )
  }
}
