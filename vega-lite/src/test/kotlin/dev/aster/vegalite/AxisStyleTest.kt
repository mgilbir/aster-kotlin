package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * An axis reads the **style blocks** it names, and they outrank every configuration family.
 *
 * ```js
 * export function getAxisConfig(property, styleConfigIndex, style, axisConfigs = {}) {
 *   const styleConfig = getStyleConfig(property, style, styleConfigIndex);
 *   if (styleConfig !== undefined) {
 *     return {configFrom: 'style', configValue: styleConfig};
 *   }
 *   for (const configFrom of ['vlOnlyAxisConfig', 'vgAxisConfig', 'axisConfigStyle'] as const) {
 *     if (axisConfigs[configFrom]?.[property] !== undefined) {
 *       return {configFrom, configValue: axisConfigs[configFrom][property]};
 *     }
 *   }
 *   return {};
 * }
 * ```
 *
 * That is how a document keeps its axis styling in one place and points an axis at it by name, and
 * it is the only way to reach a property no configuration family can state: a `labelExpr` in a
 * style block writes the labels of every axis that names it.
 *
 * This engine asked the families and not the styles, and *forwarded* the `style` property to Vega
 * instead — which is not one of `AXIS_COMPONENT_PROPERTIES` and never reaches an axis upstream. The
 * two are not the same chart: Vega applies a style block to the axis as a whole, where Vega-Lite
 * resolves it first and lets the axis's own properties, its scale's kind and its own rules outrank
 * it. A `"grid": false` in a style block, for one, takes the gridlines off before there is an axis
 * to put them on, so the whole grid axis is never written. Two specifications in the wild corpus
 * differ for that.
 *
 * A family may name style blocks too — `config.axisX.style` — and those are the **last** word
 * rather than the first, behind everything the families themselves state.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class AxisStyleTest {

  private fun axes(spec: String): List<VegaValue.Obj> {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["axes"] as? VegaValue.Arr)?.values.orEmpty().map { it as VegaValue.Obj }
  }

  /** The axis proper — the one that is not the gridlines. */
  private fun main(spec: String) = axes(spec).first { it.fields["grid"] != VegaValue.Bool(true) }

  /** The gridlines, which are an axis of their own. */
  private fun grid(spec: String) =
    axes(spec).firstOrNull { it.fields["grid"] == VegaValue.Bool(true) }

  private val data = """"data":{"values":[{"b":1,"n":2}]}"""

  private fun chart(axis: String, config: String) =
    """{$data,"config":$config,"mark":"point",
       "encoding":{"x":{"field":"b","type":"quantitative","axis":$axis},
                   "y":{"field":"n","type":"quantitative","axis":null}}}"""

  /** The reported shape: a property kept in a style block reaches the axis that names it. */
  @Test
  fun `a style block's property is written onto the axis`() {
    val spec = chart("""{"style":"s1"}""", """{"style":{"s1":{"gridColor":"lightgreen"}}}""")
    assertEquals(VegaValue.Str("lightgreen"), grid(spec)?.fields?.get("gridColor"))
    // And the block's *name* is not forwarded: `style` is not a property an axis has.
    assertNull(main(spec).fields["style"])
    assertNull(grid(spec)?.fields?.get("style"))
  }

  /** Several blocks, a later one overriding an earlier one. */
  @Test
  fun `a later style block overrides an earlier one`() {
    assertEquals(
      VegaValue.Str("blue"),
      main(
          chart(
            """{"style":["s1","s2"]}""",
            """{"style":{"s1":{"domainColor":"orange"},"s2":{"domainColor":"blue"}}}""",
          )
        )
        .fields["domainColor"],
    )
  }

  /** A `labelExpr` is the case a family cannot express and a style block can. */
  @Test
  fun `a style block's labelExpr writes the labels`() {
    assertEquals(
      VegaJson.parse("""{"labels":{"update":{"text":{"signal":"'x:'+datum.label"}}}}"""),
      main(chart("""{"style":"s1"}""", """{"style":{"s1":{"labelExpr":"'x:'+datum.label"}}}"""))
        .fields["encode"],
    )
  }

  /** The axis's own property outranks the block it names. */
  @Test
  fun `an axis property outranks its style block`() {
    assertEquals(
      VegaValue.Str("red"),
      main(
          chart(
            """{"style":"s1","domainColor":"red"}""",
            """{"style":{"s1":{"domainColor":"orange"}}}""",
          )
        )
        .fields["domainColor"],
    )
  }

  /** And the block outranks every configuration family, `config.axis` included. */
  @Test
  fun `a style block outranks the axis configuration`() {
    assertEquals(
      VegaValue.Str("orange"),
      main(
          chart(
            """{"style":"s1"}""",
            """{"axis":{"domainColor":"black"},"style":{"s1":{"domainColor":"orange"}}}""",
          )
        )
        .fields["domainColor"],
    )
  }

  /** A family may name blocks of its own, which are read where the family said nothing. */
  @Test
  fun `a configuration family may name a style block`() {
    assertEquals(
      VegaValue.Str("orange"),
      main(chart("{}", """{"axisX":{"style":"s1"},"style":{"s1":{"domainColor":"orange"}}}"""))
        .fields["domainColor"],
    )
  }

  /**
   * And loses to what the family itself states — which is then left off the axis altogether, being
   * a block Vega reads for itself.
   */
  @Test
  fun `a family's own property outranks the block it names`() {
    assertNull(
      main(
          chart(
            "{}",
            """{"axisX":{"style":"s1","domainColor":"black"},
               "style":{"s1":{"domainColor":"orange"}}}""",
          )
        )
        .fields["domainColor"]
    )
  }

  /** A `grid` in a style block decides whether there is a grid axis at all. */
  @Test
  fun `a style block may take the gridlines off`() {
    assertNull(grid(chart("""{"style":"s1"}""", """{"style":{"s1":{"grid":false}}}""")))
  }

  /** Where the axis asks for them itself, it outranks the block as it does anywhere else. */
  @Test
  fun `an axis asking for gridlines keeps them`() {
    assertEquals(
      VegaValue.Bool(true),
      grid(chart("""{"style":"s1","grid":true}""", """{"style":{"s1":{"grid":false}}}"""))
        ?.fields
        ?.get("grid"),
    )
  }
}
