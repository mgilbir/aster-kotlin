package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The pre-aggregation table exists where a domain **reads** it, and not merely where a sort could.
 *
 * ```js
 * public isRequired(): boolean {
 *   return !!this.refCounts[this._name];
 * }
 * ```
 *
 * Upstream builds a raw output node for every unit and its optimizer then removes the ones nothing
 * asked for, so the count is of *requests*. A scale whose domain the specification **states** never
 * reads any table at all, whatever its sort says — and this compiler asked the sort alone, so such
 * a chart kept a raw table nothing read. It costs nothing to compute, the node having no
 * transforms, but a named point in the flow **spends a dataset name**: every table the chart
 * derived afterwards came out one number high, and every mark and domain that named one named the
 * wrong table.
 *
 * ```js
 * // we have to use a sort object if sort = true to make the sort correct by bin start
 * sort: sort === true || !isObject(sort) ? {field: model.vgField(channel, {}), op: 'min'} : sort,
 * ```
 *
 * A domain of **labels** does not sort itself into numeric order — `"1.0 – 2.0"` sorts before `"9.0
 * – 10.0"` — so the bin's own start orders it. That is written on the domain entry and is not
 * `domainSort`'s answer: which table to read is that function's answer, and a bin ordered by its
 * own start still reads the table being drawn.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class RawTableTest {

  /** Every dataset with the steps it runs, and the domain the `x` scale ends up with. */
  private fun flow(spec: String, scale: String = "x"): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val data =
      (compiled.fields["data"] as VegaValue.Arr).values.joinToString(",") {
        it as VegaValue.Obj
        val steps =
          (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty().joinToString(",") { step ->
            (step as VegaValue.Obj).string("type").orEmpty()
          }
        "${it.string("name")}($steps)"
      }
    val domain =
      (compiled.fields["scales"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first { it.string("name") == scale }
        .fields["domain"]
    return "$data | ${VegaJson.write(domain!!).replace(Regex("""\n\s*"""), "")}"
  }

  private val rows = """"data":{"values":[{"c":"a","v":1}]}"""
  private val summed = """"y":{"aggregate":"sum","field":"v","type":"quantitative"}"""

  /**
   * The reported shape: a layered chart whose colour domain is stated, beside a sort that would
   * otherwise ask for the rows. The raw table it used to keep spent a `data_n` and shifted every
   * table after it.
   */
  @Test
  fun `a stated domain spends no dataset name`() {
    val layers = { domain: String ->
      val colour = """"color":{"field":"c","type":"nominal","sort":"descending"$domain}"""
      val encoding =
        """"encoding":{"x":{"field":"v","type":"quantitative"},
             "y":{"field":"w","type":"quantitative"},$colour}"""
      """{"data":{"url":"a.csv"},"layer":[
           {"mark":"circle",$encoding},
           {"mark":"text","transform":[{"filter":"datum.v > 1"}],$encoding}]}"""
    }
    assertEquals(
      """source_0(),data_0(filter),data_1(filter,filter) | ["a","b"]""",
      flow(layers(""","scale":{"domain":["a","b"]}"""), scale = "color"),
    )
    // And where the domain **is** derived, the rows are read and the extra table is real.
    assertEquals(
      "source_0(),data_0(filter),data_1(filter),data_2(filter) | " +
        """{"fields": [{"data": "source_0","field": "c"},{"data": "data_1","field": "c"}],""" +
        """"sort": {"op": "min","field": "c","order": "descending"}}""",
      flow(layers(""), scale = "color"),
    )
  }

  /** The reported shape: a domain the specification states, beside a sort that says nothing. */
  @Test
  fun `a stated domain reads no table`() {
    assertEquals(
      """source_0(),data_0(aggregate,filter) | ["a","b"]""",
      flow(
        """{$rows,"mark":"bar","encoding":{
           "x":{"field":"c","type":"nominal","sort":"descending","scale":{"domain":["a","b"]}},
           $summed}}"""
      ),
    )
  }

  /** A **derived** domain sorted that way does read the rows themselves. */
  @Test
  fun `a derived domain sorted descending reads the rows`() {
    assertEquals(
      """source_0(),data_0(aggregate,filter) | """ +
        """{"data": "source_0","field": "c","sort": {"order": "descending"}}""",
      flow(
        """{$rows,"mark":"bar","encoding":{
           "x":{"field":"c","type":"nominal","sort":"descending"},$summed}}"""
      ),
    )
  }

  /** So does one sorted by an aggregate of some **other** column. */
  @Test
  fun `a domain sorted by another column reads the rows`() {
    assertEquals(
      """source_0(),data_0(aggregate,filter) | """ +
        """{"data": "source_0","field": "c","sort": {"op": "mean","field": "v"}}""",
      flow(
        """{$rows,"mark":"bar","encoding":{
           "x":{"field":"c","type":"nominal","sort":{"op":"mean","field":"v"}},$summed}}"""
      ),
    )
  }

  /**
   * A **bin** on a discrete scale is ordered by its own start, and that is written on the entry: it
   * still reads the table being drawn.
   */
  @Test
  fun `a binned domain is ordered by its own start and reads the drawn table`() {
    assertEquals(
      """source_0(),data_0(extent,bin,formula,aggregate) | {"data": "data_0",""" +
        """"field": "bin_maxbins_10_v_range","sort": {"field": "bin_maxbins_10_v","op": "min"}}""",
      flow(
        """{"data":{"values":[{"v":1}]},"mark":"bar","encoding":{
           "x":{"field":"v","type":"ordinal","bin":true},
           "y":{"aggregate":"count","type":"quantitative"}}}"""
      ),
    )
  }
}
