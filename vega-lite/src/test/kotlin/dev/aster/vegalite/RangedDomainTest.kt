package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A ranged position's two ends are unioned **above** the per-channel domain, not instead of it.
 *
 * ```js
 * if (channel === 'x' && getFieldOrDatumDef(encoding.x2)) {
 *   if (getFieldOrDatumDef(encoding.x)) {
 *     return mergeValuesWithExplicit(
 *       parseSingleChannelDomain(scaleType, domain, model, 'x'),
 *       parseSingleChannelDomain(scaleType, domain, model, 'x2'), ...);
 *   }
 * }
 * ```
 *
 * `parseDomainForChannel` merges whatever the channel's own domain came out as with whatever its
 * second channel's did. This compiler read it as one of the per-channel shapes, so it stood behind
 * the earlier ones: a **bucketed** position with a second column of its own contributed the bin's
 * extent alone and the scale stopped at the last bucket's start, and a position given as a `datum`
 * with a column beyond it contributed the constant alone.
 *
 * ```js
 * if (timeUnit && !fieldDef2) {
 *   return getMarkConfig('timeUnitBandPosition', mark, config);
 * }
 * ```
 *
 * And once the union is read where upstream reads it, the band it unions with has to be the one
 * upstream computes: a position given a second one of its own spans what the two of them name, not
 * the bucket the first sits in, so `getBandPosition` answers nothing for it and there is no band to
 * reach the end of. A `bandPosition` the specification states is still a band — that clause is
 * asked first.
 *
 * One specification in the wild corpus draws bars between a bucket's edges and a column of its own.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class RangedDomainTest {

  private fun domain(encoding: String, mark: String = "bar", scale: String = "x"): String {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """{"data":{"values":[{"s":0,"e":2,"c":3,"t":"2020-01-01","u":"2021-01-01"}]},
                 "mark":"$mark","encoding":{$encoding}}"""
            )
            .toJson()
        ) {
          "no output"
        }
      ) as VegaValue.Obj
    return VegaJson.write(
        (compiled.fields["scales"] as VegaValue.Arr)
          .values
          .map { it as VegaValue.Obj }
          .first { it.string("name") == scale }
          .fields["domain"]!!
      )
      .replace(Regex("""\n\s*"""), "")
  }

  private val count = """"y":{"field":"c","type":"quantitative"}"""

  /** The reported shape: the bin's extent, and beyond it the column the bars reach to. */
  @Test
  fun `a bucketed position unions the column beyond it`() {
    assertEquals(
      """{"fields": [{"signal": "[bin_step_2_s_bins.start, bin_step_2_s_bins.stop]"},""" +
        """{"data": "data_0","field": "e"}]}""",
      domain(
        """"x":{"field":"s","type":"quantitative","bin":{"step":2}},"x2":{"field":"e"},$count"""
      ),
    )
  }

  /** Alone, the bin's extent is the whole domain — which is what used to answer for both. */
  @Test
  fun `a bucketed position alone is its own extent`() {
    assertEquals(
      """{"signal": "[bin_step_2_s_bins.start, bin_step_2_s_bins.stop]"}""",
      domain(""""x":{"field":"s","type":"quantitative","bin":{"step":2}},$count"""),
    )
  }

  /** A far end given as a **datum** is unioned as the constant it is. */
  @Test
  fun `a bucketed position unions a stated far end`() {
    assertEquals(
      """{"fields": [{"signal": "[bin_step_2_s_bins.start, bin_step_2_s_bins.stop]"},[9]]}""",
      domain(
        """"x":{"field":"s","type":"quantitative","bin":{"step":2}},"x2":{"datum":9},$count"""
      ),
    )
  }

  /** A bucket on a **discrete** scale unions its label column, and the union carries the sort. */
  @Test
  fun `a bucketed discrete position unions its far column`() {
    assertEquals(
      """{"data": "data_0","fields": ["bin_step_2_s_range","e"],""" +
        """"sort": {"field": "bin_step_2_s","op": "min"}}""",
      domain(""""x":{"field":"s","type":"ordinal","bin":{"step":2}},"x2":{"field":"e"},$count"""),
    )
  }

  /** A plain ranged position is the pair it always was, which is what must not change. */
  @Test
  fun `a plain ranged position is both its columns`() {
    assertEquals(
      """{"data": "data_0","fields": ["s","e"]}""",
      domain(""""x":{"field":"s","type":"quantitative"},"x2":{"field":"e"},$count"""),
    )
  }

  /** A rect between two instants covers **those** two, not the first one's bucket. */
  @Test
  fun `a ranged instant is the pair of instants`() {
    assertEquals(
      """{"data": "data_0","fields": ["year_t","year_u"]}""",
      domain(
        """"x":{"field":"t","timeUnit":"year"},"x2":{"field":"u","timeUnit":"year"}""",
        mark = "rect",
      ),
    )
  }

  /** With no second position it does cover the bucket, which is the arm that must not change. */
  @Test
  fun `an instant on its own reaches its bucket's end`() {
    assertEquals(
      """{"data": "data_0","fields": ["year_t","year_t_end"]}""",
      domain(""""x":{"field":"t","timeUnit":"year"},$count""", mark = "rect"),
    )
  }

  /**
   * A `bandPosition` the specification states is a band whatever else is encoded: it is asked
   * first.
   */
  @Test
  fun `a stated band position keeps its bucket`() {
    assertEquals(
      """{"data": "data_0","fields": ["year_t","year_t_end","year_u"]}""",
      domain(
        """"x":{"field":"t","timeUnit":"year","bandPosition":0.5},
           "x2":{"field":"u","timeUnit":"year"}""",
        mark = "rect",
      ),
    )
  }
}
