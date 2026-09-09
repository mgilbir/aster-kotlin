package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A facet **channel** carries its own `spacing`, and it becomes the layout's padding.
 *
 * `getFacetMappingAndLayout` lifts four properties off a facet definition:
 * ```js
 * const {align, center, spacing, columns, ...facetMapping} = facet;
 * ```
 *
 * and `assembleLayout` then extracts `spacing` from the layout and writes it as `padding`:
 * ```js
 * const {spacing, ...layout} = this.layout;
 * return {padding: spacing, ...this.assembleDefaultLayout(), ...layout, …};
 * ```
 *
 * So the gap between a trellis's cells may be written beside the facet — `{"facet": …, "spacing":
 * 5}` — or on the channel that makes the facet — `{"facet": {"field": …, "spacing": 5}}`. This read
 * only the first place and fell back to the configured twenty for the second, exactly as it once
 * read `columns` in only one place.
 *
 * A crossed facet states it **per channel**, `layout[prop][channel] = def[prop]`, so a trellis
 * whose rows name a gap and whose columns do not is a pair with one side filled in from the theme.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class FacetSpacingTest {

  private fun layout(spec: String): VegaValue.Obj =
    ((VegaJson.parse(
        requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "did not compile" }
      ) as VegaValue.Obj)
      .fields["layout"]
      as VegaValue.Obj)

  private val position =
    """"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"quantitative"}"""

  private fun faceted(facet: String, config: String = "") =
    layout(
      """
      {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":"point",$config
       "encoding":{$position,"facet":$facet}}
      """
    )

  private fun padding(layout: VegaValue.Obj) = layout.fields["padding"]

  /** The reported shape: the gap is stated on the channel. */
  @Test
  fun `a spacing stated on the facet channel is the layout's padding`() {
    assertEquals(
      VegaValue.Num(5.0),
      padding(faceted("""{"field":"c","type":"nominal","columns":2,"spacing":5}""")),
    )
  }

  /** With nothing stated the configured gap stands, which is what this always did. */
  @Test
  fun `a facet with no spacing takes the configured gap`() {
    assertEquals(VegaValue.Num(20.0), padding(faceted("""{"field":"c","type":"nominal"}""")))
  }

  /** And a theme may set it, which the channel then outranks. */
  @Test
  fun `the theme's gap is used and the channel outranks it`() {
    assertEquals(
      VegaValue.Num(7.0),
      padding(
        faceted("""{"field":"c","type":"nominal"}""", """"config":{"facet":{"spacing":7}},""")
      ),
    )
    assertEquals(
      VegaValue.Num(5.0),
      padding(
        faceted(
          """{"field":"c","type":"nominal","spacing":5}""",
          """"config":{"facet":{"spacing":7}},""",
        )
      ),
    )
  }

  /** The operator form, where the gap sits beside the facet rather than on it. */
  @Test
  fun `a spacing stated beside the facet is still the padding`() {
    assertEquals(
      VegaValue.Num(5.0),
      padding(
        layout(
          """
          {"data":{"values":[{"a":1,"b":2,"c":"x"}]},
           "facet":{"field":"c","type":"nominal"},"columns":2,"spacing":5,
           "spec":{"mark":"point","encoding":{$position}}}
          """
        )
      ),
    )
  }

  /**
   * A crossed facet states it per channel, and the side left out is filled in rather than dropped:
   * a trellis of rows five apart still wants the configured gap between its columns.
   */
  @Test
  fun `a row channel's spacing is one side of a pair`() {
    val padding =
      padding(
        layout(
          """
          {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":"point",
           "encoding":{$position,"row":{"field":"c","type":"nominal","spacing":5}}}
          """
        )
      )
        as VegaValue.Obj
    assertEquals(VegaValue.Num(5.0), padding.fields["row"])
    assertEquals(VegaValue.Num(20.0), padding.fields["column"], "the side left out is filled in")
  }
}
