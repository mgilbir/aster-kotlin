package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A legend's label expression is applied to the **merged** legend, and applying it does not cost
 * the swatch.
 *
 * `assembleLegend` destructures `labelExpr` off the component and applies it at assembly:
 * ```js
 * const {disable, labelExpr, selections, ...legend} = legendCmpt.combine();
 * …
 * if (labelExpr !== undefined) {
 *   let expr = labelExpr;
 *   if (legend.encode?.labels?.update && isSignalRef(legend.encode.labels.update.text)) {
 *     expr = util.replaceAll(labelExpr, 'datum.label', legend.encode.labels.update.text.signal);
 *   }
 *   …
 * }
 * ```
 *
 * The **place** matters. A line with a point overlay is two layers, and only the point's legend has
 * a swatch encode; applied per layer, the line's `{labels: …}` encode reached the merge first and
 * the point's `{symbols: …}` was dropped behind it, so the swatch lost the overlay's white fill.
 *
 * Moving it here uncovered a second fault in the same function. Upstream removes a scale channel
 * from the swatch with `delete out[property]` — **in place** — leaving the rest of the encode
 * alone. This rebuilt the whole `encode` from the swatch, so a legend whose labels carry an
 * expression lost them the moment a scale channel was dropped from its swatch. The two had to be
 * fixed together: either alone leaves the encode with one part instead of two.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class LegendLabelExprMergeTest {

  private fun encode(legend: String): VegaValue.Obj? {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":"x"}]},
               "mark":{"type":"line","point":{"filled":false,"fill":"white"}},
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"},
                           "color":{"field":"c","type":"nominal"$legend}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val first = (compiled.fields["legends"] as VegaValue.Arr).values.first() as VegaValue.Obj
    return first.fields["encode"] as? VegaValue.Obj
  }

  private val overlaySwatch =
    VegaValue.Obj(
      linkedMapOf(
        "update" to
          VegaValue.Obj(
            linkedMapOf(
              "fill" to VegaValue.Obj(linkedMapOf("value" to VegaValue.Str("white"))),
              "opacity" to VegaValue.Obj(linkedMapOf("value" to VegaValue.Num(1.0))),
            )
          )
      )
    )

  /** With no expression, the overlay's swatch is the whole encode — the case that always worked. */
  @Test
  fun `a point overlay's swatch reaches the legend`() {
    assertEquals(mapOf("symbols" to overlaySwatch), encode("")?.fields)
  }

  /** The reported shape: an expression on the labels, and the swatch must survive it. */
  @Test
  fun `an expression on the labels keeps the overlay's swatch`() {
    val fields = encode(""","legend":{"labelExpr":"labels[datum.value]"}""")?.fields
    assertEquals(
      VegaValue.Obj(
        linkedMapOf(
          "update" to
            VegaValue.Obj(
              linkedMapOf(
                "text" to
                  VegaValue.Obj(linkedMapOf("signal" to VegaValue.Str("labels[datum.value]")))
              )
            )
        )
      ),
      fields?.get("labels"),
      "the expression is applied",
    )
    assertEquals(overlaySwatch, fields?.get("symbols"), "and the swatch is still there")
  }
}
