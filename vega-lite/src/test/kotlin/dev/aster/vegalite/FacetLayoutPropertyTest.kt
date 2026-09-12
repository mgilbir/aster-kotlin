package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A layout property the specification **states** outranks the default computed beside it.
 *
 * `assembleLayout` is `{padding: spacing, ...this.assembleDefaultLayout(), ...layout}`, where
 * `layout` is `extractCompositionLayout(spec, 'facet', config)` — the chart's own `align`,
 * `bounds`, `center`, `columns` and `spacing`. So a trellis writing `"bounds": "flush"` gets it,
 * however firmly the default says `full`.
 *
 * A **crossed** grid lifts two of them per channel:
 * ```js
 * for (const prop of ['align', 'center', 'spacing'] as const) {
 *   if (def[prop] !== undefined) {
 *     layout[prop] ??= {};
 *     layout[prop][channel] = def[prop];
 *   }
 * }
 * ```
 *
 * so an alignment written on the `row` channel becomes `{"align": {"row": …}}` — an object, which
 * *replaces* whatever the chart itself said rather than filling in the other side. Row before
 * column, the loop's own order. And because those lifted properties are spread **after** the
 * chart's own, a `center` on the facet channel outranks a `center` beside the chart; this engine
 * had that precedence the other way round for a wrapped facet and did not read the crossed one at
 * all.
 *
 * Two specifications in the wild corpus state `"bounds": "flush"` and were drawn with `full`.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class FacetLayoutPropertyTest {

  private fun layout(encoding: String, extra: String = ""): VegaValue.Obj {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":"x","d":"y"}]},"mark":"point"$extra,
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"},$encoding}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return compiled.obj("layout") ?: VegaValue.EmptyObject
  }

  private val column = """"column":{"field":"c","type":"nominal"}"""
  private val row = """"row":{"field":"d","type":"nominal"}"""
  private val wrapped = """"facet":{"field":"c","type":"nominal","columns":2}"""

  /** The reported shape: a trellis that asks for flush bounds. */
  @Test
  fun `a stated bounds reaches a crossed grid`() {
    val layout = layout(column, ""","bounds":"flush"""")
    assertEquals(VegaValue.Str("flush"), layout.fields["bounds"])
    assertEquals(
      listOf("padding", "offset", "columns", "bounds", "align"),
      layout.fields.keys.toList(),
      "in the place the default already held",
    )
  }

  /** And a wrapped one, which reads it from the same place. */
  @Test
  fun `a stated bounds reaches a wrapped grid`() {
    assertEquals(VegaValue.Str("flush"), layout(wrapped, ""","bounds":"flush"""").fields["bounds"])
  }

  /** A grid faceted both ways is the same chart property. */
  @Test
  fun `a stated bounds reaches a grid faceted both ways`() {
    assertEquals(
      VegaValue.Str("flush"),
      layout("$column,$row", ""","bounds":"flush"""").fields["bounds"],
    )
  }

  /** `align` and `center` come from the same block. */
  @Test
  fun `a stated align and center reach the layout`() {
    assertEquals(VegaValue.Str("none"), layout(column, ""","align":"none"""").fields["align"])
    val centred = layout(column, ""","center":true""")
    assertEquals(VegaValue.Bool(true), centred.fields["center"])
    assertEquals(
      listOf("padding", "offset", "columns", "bounds", "align", "center"),
      centred.fields.keys.toList(),
      "`center` has no default, so it lands after the ones that do",
    )
  }

  /**
   * A crossed grid's `align` written **on a channel** is lifted per channel, and the object
   * replaces the chart's own scalar rather than filling in the other side.
   */
  @Test
  fun `an align on a facet channel is lifted per channel`() {
    assertEquals(
      VegaValue.Obj(linkedMapOf("column" to VegaValue.Str("each"))),
      layout(""""column":{"field":"c","type":"nominal","align":"each"}""", ""","align":"none"""")
        .fields["align"],
    )
    assertEquals(
      VegaValue.Obj(linkedMapOf("row" to VegaValue.Str("each"))),
      layout("""$column,"row":{"field":"d","type":"nominal","align":"each"}""").fields["align"],
    )
    val both =
      layout(
          """"column":{"field":"c","type":"nominal","align":"none"},
             "row":{"field":"d","type":"nominal","align":"each"}"""
        )
        .fields["align"]
        as VegaValue.Obj
    assertEquals(
      VegaValue.Obj(linkedMapOf("row" to VegaValue.Str("each"), "column" to VegaValue.Str("none"))),
      both,
    )
    assertEquals(
      listOf("row", "column"),
      both.fields.keys.toList(),
      "row before column, the loop's own order — a map compares equal either way round",
    )
  }

  /** `center` is lifted the same way, and the default it replaces is that there is none. */
  @Test
  fun `a center on a facet channel is lifted per channel`() {
    assertEquals(
      VegaValue.Obj(linkedMapOf("column" to VegaValue.Bool(false))),
      layout(""""column":{"field":"c","type":"nominal","center":false}""", ""","center":true""")
        .fields["center"],
    )
  }

  /** A **wrapped** facet's channel carries the property whole, and outranks the chart's. */
  @Test
  fun `a wrapped facet's channel outranks the chart`() {
    assertEquals(
      VegaValue.Str("each"),
      layout(
          """"facet":{"field":"c","type":"nominal","columns":2,"align":"each"}""",
          ""","align":"none"""",
        )
        .fields["align"],
    )
    assertEquals(
      VegaValue.Str("none"),
      layout(wrapped, ""","align":"none"""").fields["align"],
      "with the channel silent, the chart's own stands",
    )
  }

  /** With nothing stated, the defaults are what they always were. */
  @Test
  fun `a trellis with nothing stated keeps its defaults`() {
    val layout = layout(column)
    assertEquals(VegaValue.Str("full"), layout.fields["bounds"])
    assertEquals(VegaValue.Str("all"), layout.fields["align"])
    assertEquals(null, layout.fields["center"])
  }
}
