package dev.aster.vega.runtime.compile

import dev.aster.vega.model.DiagnosticCodes
import dev.aster.vega.model.DiagnosticSeverity
import dev.aster.vega.runtime.scale.BandScale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `sort` on a data-driven discrete domain.
 *
 * Every expected domain here was read out of upstream — `view.scale('s').domain()` on the same
 * seven rows, via `oracle-js`. That mattered more than usual: the shape of this feature is not
 * guessable from the specification. Upstream builds a discrete domain by *grouping* the dataset on
 * the domain field, so `sort` orders groups rather than rows, an aggregate sort can name a field
 * the domain never mentions, and three of the `sort` object's forms behave in ways nothing but the
 * source explains.
 */
class DomainSortTest {

  /**
   * Sums per team: alpha 11, charlie 9, bravo 7, delta 6. Counts: delta, alpha and charlie 2 each,
   * bravo 1 — deliberately tied, so a sort that is not stable shows up.
   */
  private val rows =
    """
    [
      {"k": "delta", "g": "x", "v": 3},
      {"k": "alpha", "g": "y", "v": 10},
      {"k": "charlie", "g": "x", "v": 7},
      {"k": "alpha", "g": "x", "v": 1},
      {"k": "bravo", "g": "y", "v": 7},
      {"k": "charlie", "g": "y", "v": 2},
      {"k": "delta", "g": "y", "v": 3}
    ]
    """
      .trimIndent()

  private fun compile(domain: String, values: String = rows) =
    SpecCompiler()
      .compileJson(
        """
        {
          "width": 100, "height": 100, "padding": 0,
          "data": [{"name": "t", "values": $values}],
          "scales": [
            {"name": "s", "type": "band", "domain": $domain, "range": "width"}
          ]
        }
        """
          .trimIndent()
      )

  private fun domainOf(domain: String, values: String = rows): List<String> =
    (compile(domain, values).scales["s"] as BandScale).domain

  private fun codes(domain: String, values: String = rows) =
    compile(domain, values).diagnostics.map { it.code }

  // ---- ordering -------------------------------------------------------------

  @Test
  fun `no sort keeps the order the groups were first seen in`() {
    assertEquals(
      listOf("delta", "alpha", "charlie", "bravo"),
      domainOf("""{"data": "t", "field": "k"}"""),
    )
  }

  @Test
  fun `sort true orders by the domain value itself`() {
    assertEquals(
      listOf("alpha", "bravo", "charlie", "delta"),
      domainOf("""{"data": "t", "field": "k", "sort": true}"""),
    )
  }

  @Test
  fun `sort false leaves the domain alone`() {
    assertEquals(
      listOf("delta", "alpha", "charlie", "bravo"),
      domainOf("""{"data": "t", "field": "k", "sort": false}"""),
    )
  }

  /**
   * An empty object is `sort: true`. Upstream's `parseSort` fills in `field: "key"` when neither
   * `op` nor `field` is given, so `{"order": "descending"}` is how a specification asks for a
   * reverse-alphabetical domain — there is no other spelling of it.
   */
  @Test
  fun `an object with neither op nor field sorts by the domain value`() {
    assertEquals(
      listOf("alpha", "bravo", "charlie", "delta"),
      domainOf("""{"data": "t", "field": "k", "sort": {}}"""),
    )
    assertEquals(
      listOf("delta", "charlie", "bravo", "alpha"),
      domainOf("""{"data": "t", "field": "k", "sort": {"order": "descending"}}"""),
    )
  }

  /**
   * The domain is strings, but the values behind it are numbers and upstream orders them as
   * numbers. Sorting the rendered form instead puts 100 before 20 before 3, which is the shape this
   * engine had: a legend counting up in tiers came out 100, 20, 3, 9.
   */
  @Test
  fun `a numeric domain sorts numerically, not lexicographically`() {
    val numbers = """[{"k": 100}, {"k": 9}, {"k": 20}, {"k": 3}]"""
    assertEquals(
      listOf("3", "9", "20", "100"),
      domainOf("""{"data": "t", "field": "k", "sort": true}""", numbers),
    )
    assertEquals(
      listOf("100", "20", "9", "3"),
      domainOf("""{"data": "t", "field": "k", "sort": {"order": "descending"}}""", numbers),
    )
  }

  @Test
  fun `an aggregate sort orders by a field the domain never mentions`() {
    assertEquals(
      listOf("alpha", "charlie", "bravo", "delta"),
      domainOf(
        """{"data": "t", "field": "k", "sort": {"op": "sum", "field": "v", "order": "descending"}}"""
      ),
    )
    assertEquals(
      listOf("delta", "bravo", "charlie", "alpha"),
      domainOf("""{"data": "t", "field": "k", "sort": {"op": "sum", "field": "v"}}"""),
    )
  }

  @Test
  fun `every aggregate operation upstream was probed with agrees`() {
    // max: alpha 10, charlie 7, bravo 7, delta 3 — charlie and bravo tie and hold group order.
    assertEquals(
      listOf("alpha", "charlie", "bravo", "delta"),
      domainOf(
        """{"data": "t", "field": "k", "sort": {"op": "max", "field": "v", "order": "descending"}}"""
      ),
    )
    // mean: bravo 7, alpha 5.5, charlie 4.5, delta 3.
    assertEquals(
      listOf("bravo", "alpha", "charlie", "delta"),
      domainOf(
        """{"data": "t", "field": "k", "sort": {"op": "mean", "field": "v", "order": "descending"}}"""
      ),
    )
    assertEquals(
      listOf("bravo", "alpha", "charlie", "delta"),
      domainOf(
        """{"data": "t", "field": "k", "sort": {"op": "median", "field": "v", "order": "descending"}}"""
      ),
    )
    // min ascending: alpha 1, charlie 2, delta 3, bravo 7.
    assertEquals(
      listOf("alpha", "charlie", "delta", "bravo"),
      domainOf("""{"data": "t", "field": "k", "sort": {"op": "min", "field": "v"}}"""),
    )
  }

  /** `count` is the one operation that needs no field, and the tied groups stay in group order. */
  @Test
  fun `a count sort needs no field and ties stably`() {
    assertEquals(
      listOf("delta", "alpha", "charlie", "bravo"),
      domainOf("""{"data": "t", "field": "k", "sort": {"op": "count", "order": "descending"}}"""),
    )
    assertEquals(
      listOf("bravo", "delta", "alpha", "charlie"),
      domainOf("""{"data": "t", "field": "k", "sort": {"op": "count"}}"""),
    )
  }

  // ---- several fields -------------------------------------------------------

  /**
   * Upstream groups each field separately and concatenates, so the domain runs field by field. Row
   * by row — the obvious reading, and what this engine did — interleaves them instead: `delta, x,
   * alpha, y, charlie, bravo`, where only the first entry lands in the right place.
   */
  @Test
  fun `a domain over several fields runs field by field`() {
    assertEquals(
      listOf("delta", "alpha", "charlie", "bravo", "x", "y"),
      domainOf("""{"data": "t", "fields": ["k", "g"]}"""),
    )
  }

  @Test
  fun `several fields sort by value across the whole union`() {
    assertEquals(
      listOf("alpha", "bravo", "charlie", "delta", "x", "y"),
      domainOf("""{"data": "t", "fields": ["k", "g"], "sort": true}"""),
    )
    assertEquals(
      listOf("y", "x", "delta", "charlie", "bravo", "alpha"),
      domainOf("""{"data": "t", "fields": ["k", "g"], "sort": {"order": "descending"}}"""),
    )
  }

  /**
   * Counts are folded across fields by *summing* them: x appears 3 times and y 4, against 2 each
   * for delta, alpha and charlie.
   */
  @Test
  fun `several fields sort by a count of counts`() {
    assertEquals(
      listOf("y", "x", "delta", "alpha", "charlie", "bravo"),
      domainOf(
        """{"data": "t", "fields": ["k", "g"], "sort": {"op": "count", "order": "descending"}}"""
      ),
    )
  }

  @Test
  fun `several fields sort by a min of mins and a max of maxes`() {
    assertEquals(
      listOf("bravo", "delta", "charlie", "y", "alpha", "x"),
      domainOf(
        """{"data": "t", "fields": ["k", "g"], "sort": {"op": "min", "field": "v", "order": "descending"}}"""
      ),
    )
    assertEquals(
      listOf("delta", "charlie", "bravo", "x", "alpha", "y"),
      domainOf("""{"data": "t", "fields": ["k", "g"], "sort": {"op": "max", "field": "v"}}"""),
    )
  }

  // ---- what gets reported ---------------------------------------------------

  /**
   * Upstream names an aggregate's output `{op}_{field}`, so a `field` with no `op` sorts on a
   * column no aggregate produced: every key is undefined, the sort is stable, and nothing moves.
   * Reproduced — a chart written against upstream gets the order upstream gives it — and reported,
   * because that order is almost certainly not the one the author was asking for.
   */
  @Test
  fun `a sort field with no op changes nothing, and says so`() {
    val spec = """{"data": "t", "field": "k", "sort": {"field": "v", "order": "descending"}}"""
    assertEquals(listOf("delta", "alpha", "charlie", "bravo"), domainOf(spec))
    assertTrue(DiagnosticCodes.PARSE_UNKNOWN_PROPERTY in codes(spec), codes(spec).toString())
  }

  /** Upstream throws here. Reporting and leaving the domain unsorted is the same information. */
  @Test
  fun `an aggregate other than count with no field is reported`() {
    val spec = """{"data": "t", "field": "k", "sort": {"op": "sum"}}"""
    assertEquals(listOf("delta", "alpha", "charlie", "bravo"), domainOf(spec))
    assertTrue(DiagnosticCodes.SCALE_INVALID_DOMAIN in codes(spec), codes(spec).toString())
  }

  /**
   * `sum` and `mean` do not survive being applied twice, so upstream refuses them on a multi-field
   * domain rather than producing an average of averages.
   */
  @Test
  fun `several fields cannot be sorted by an operation that will not fold`() {
    val spec = """{"data": "t", "fields": ["k", "g"], "sort": {"op": "mean", "field": "v"}}"""
    assertEquals(listOf("delta", "alpha", "charlie", "bravo", "x", "y"), domainOf(spec))
    val reported = compile(spec).diagnostics.filter { it.severity >= DiagnosticSeverity.ERROR }
    assertTrue(
      reported.any { it.code == DiagnosticCodes.SCALE_INVALID_DOMAIN },
      reported.toString(),
    )
    assertTrue(reported.any { "mean" in it.message }, reported.toString())
  }

  /** An array has nowhere to hang a `sort`, and upstream reads it straight through. */
  @Test
  fun `an explicit discrete domain keeps the order it was written in`() {
    assertEquals(
      listOf("delta", "alpha", "charlie"),
      domainOf("""["delta", "alpha", "charlie"]"""),
    )
  }
}
