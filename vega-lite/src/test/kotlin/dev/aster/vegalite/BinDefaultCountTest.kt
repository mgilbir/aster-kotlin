package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A bucketing that says neither how **many** buckets nor how **wide** gets the default count.
 *
 * ```js
 * export function normalizeBin(bin: BinParams | boolean | 'binned', channel?: ExtendedChannel) {
 *   if (isBoolean(bin)) {
 *     return {maxbins: autoMaxBins(channel)};
 *   } else if (bin === 'binned') {
 *     return {binned: true};
 *   } else if (!bin.maxbins && !bin.step) {
 *     return {...bin, maxbins: autoMaxBins(channel)};
 *   } else {
 *     return bin;
 *   }
 * }
 * ```
 *
 * Three arms, and this compiler read only two of them: `bin: true` and the empty object. A stated
 * bucketing that says something *else* — an `anchor`, a `base` — was left without a count, so
 * nothing cut the column into ten. The count is spelled into the name the bucketing writes, so the
 * column the mark read, `bin_anchor_0_5_v`, was not the one the data flow had written under
 * `bin_anchor_0_5_maxbins_10_v`: every mark, every axis and the scale's own `bins` named a column
 * that did not exist, and the chart drew nothing.
 *
 * `!bin.maxbins && !bin.step` is their **truthiness**: a bucketing asking for zero buckets is
 * asking for the default. The default itself is the channel's — six for a facet or a colour, ten
 * elsewhere — and in a `transform` there is no channel to ask, so it is always ten.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class BinDefaultCountTest {

  private fun compiled(spec: String) =
    VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
      as VegaValue.Obj

  /** Every `bin` step the flow runs, as the columns it writes and the count it was given. */
  private fun bins(spec: String): String =
    (compiled(spec).fields["data"] as VegaValue.Arr)
      .values
      .flatMap { (it as VegaValue.Obj).array("transform").orEmpty() }
      .map { it as VegaValue.Obj }
      .filter { it.string("type") == "bin" }
      .joinToString(" ") {
        val written =
          (it.fields["as"] as? VegaValue.Arr)?.values?.joinToString(",") { name ->
            (name as VegaValue.Str).value
          }
        "$written maxbins=${it.number("maxbins")} step=${it.number("step")}"
      }

  private fun encoded(bin: String, channel: String = "x") =
    """{"mark":"bar","encoding":{"$channel":{"bin":$bin,"field":"v","type":"quantitative"},
        "y":{"aggregate":"count","type":"quantitative"}}}"""

  /** `true` is ten buckets, which is what already worked. */
  @Test
  fun `a bucketing stated as true takes the default count`() {
    assertEquals(
      "bin_maxbins_10_v,bin_maxbins_10_v_end maxbins=10.0 step=null",
      bins(encoded("true")),
    )
  }

  /** So is an empty object. */
  @Test
  fun `an empty bucketing takes the default count`() {
    assertEquals(
      "bin_maxbins_10_v,bin_maxbins_10_v_end maxbins=10.0 step=null",
      bins(encoded("{}")),
    )
  }

  /** The reported shape: an `anchor` and nothing about how many or how wide. */
  @Test
  fun `a bucketing that states only an anchor takes the default count`() {
    assertEquals(
      "bin_anchor_0_5_maxbins_10_v,bin_anchor_0_5_maxbins_10_v_end maxbins=10.0 step=null",
      bins(encoded("""{"anchor":0.5}""")),
    )
  }

  /** And one that states only a `base`, the name reading what was stated before the count. */
  @Test
  fun `a bucketing that states only a base takes the default count`() {
    assertEquals(
      "bin_base_10_maxbins_10_v,bin_base_10_maxbins_10_v_end maxbins=10.0 step=null",
      bins(encoded("""{"base":10}""")),
    )
  }

  /** A stated **width** is how wide, so no count is invented for it. */
  @Test
  fun `a bucketing that states a step keeps it alone`() {
    assertEquals(
      "bin_step_2_v,bin_step_2_v_end maxbins=null step=2.0",
      bins(encoded("""{"step":2}""")),
    )
  }

  /** As is a stated width beside an anchor. */
  @Test
  fun `a bucketing that states a step and an anchor keeps them alone`() {
    assertEquals(
      "bin_step_2_anchor_0_5_v,bin_step_2_anchor_0_5_v_end maxbins=null step=2.0",
      bins(encoded("""{"step":2,"anchor":0.5}""")),
    )
  }

  /** And a stated count is the count. */
  @Test
  fun `a bucketing that states a count keeps it`() {
    assertEquals(
      "bin_maxbins_4_anchor_0_5_v,bin_maxbins_4_anchor_0_5_v_end maxbins=4.0 step=null",
      bins(encoded("""{"maxbins":4,"anchor":0.5}""")),
    )
  }

  /** A count of **zero** is no count: `!bin.maxbins` is its truthiness. */
  @Test
  fun `a bucketing that asks for no buckets takes the default count`() {
    assertEquals(
      "bin_maxbins_10_v,bin_maxbins_10_v_end maxbins=10.0 step=null",
      bins(encoded("""{"maxbins":0}""")),
    )
  }

  /** The default is the **channel's**: a colour takes six, being harder to tell apart. */
  @Test
  fun `a bucketing on a colour takes that channel's default count`() {
    assertEquals(
      "bin_anchor_0_5_maxbins_6_v,bin_anchor_0_5_maxbins_6_v_end maxbins=6.0 step=null",
      bins(encoded("""{"anchor":0.5}""", channel = "color")),
    )
  }

  /** In a `transform` there is no channel to ask, so the count is always ten. */
  @Test
  fun `a bucketing written as a transform takes ten`() {
    assertEquals(
      "b,b_end maxbins=10.0 step=null",
      bins(
        """{"transform":[{"bin":{"anchor":0.5},"field":"v","as":"b"}],"mark":"bar",
            "encoding":{"x":{"field":"b","type":"quantitative"}}}"""
      ),
    )
  }
}
