package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A scale and an axis are read by asking them for the properties scales and axes **have**.
 *
 * ```js
 * for (const prop of NON_TYPE_DOMAIN_RANGE_VEGA_SCALE_PROPERTIES) {
 *   parseScaleProperty(model, prop);
 * }
 * ```
 * ```js
 * for (const property of AXIS_COMPONENT_PROPERTIES) {
 *   const value = property in axisRules ? axisRules[property](ruleParams) : isAxisProperty(property) ? axis[property] : undefined;
 * ```
 *
 * so a block holding anything else is never looked at. This engine copied the block's own keys and
 * forwarded them, which is the third and fourth time that shape has been found here — the mark's
 * and the legend's property lists were the first two — and it fails the same way: a denylist cannot
 * be finished, because what it has to exclude is every word nobody has written yet.
 *
 * What the wild corpus actually carries is words from **older versions**: a `rangeStep` inside a
 * scale, which Vega-Lite had in version 2, and an `axisWidth` inside an axis, which it had in
 * version 1. A third specification writes `{"scale": {"legend": false}}` — a legend property
 * misplaced inside the scale — and Vega reported `PARSE_UNKNOWN_PROPERTY` for all three.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class GuidePropertyAllowlistTest {

  private class Compiled(val spec: VegaValue.Obj) {
    fun scale(name: String) =
      (spec.fields["scales"] as? VegaValue.Arr)
        ?.values
        ?.mapNotNull { it as? VegaValue.Obj }
        ?.firstOrNull { it.string("name") == name }

    /**
     * The **main** axis of a channel: the one that carries the labels rather than the gridlines.
     */
    fun axis(scale: String) =
      (spec.fields["axes"] as? VegaValue.Arr)
        ?.values
        ?.mapNotNull { it as? VegaValue.Obj }
        ?.firstOrNull { it.string("scale") == scale && !it.fields.containsKey("gridScale") }

    fun gridAxis(scale: String) =
      (spec.fields["axes"] as? VegaValue.Arr)
        ?.values
        ?.mapNotNull { it as? VegaValue.Obj }
        ?.firstOrNull { it.string("scale") == scale && it.fields.containsKey("gridScale") }
  }

  private fun compile(x: String): Compiled =
    Compiled(
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":"point",
               "encoding":{"x":$x,"y":{"field":"b","type":"quantitative"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    )

  /** The reported shape: a legend property written inside the scale. */
  @Test
  fun `a legend property inside a scale does not reach Vega`() {
    val scale = compile("""{"field":"c","type":"ordinal","scale":{"legend":false}}""").scale("x")
    assertNull(scale?.fields?.get("legend"), "Vega has no such scale property")
  }

  /** And a scale property from an older Vega-Lite. */
  @Test
  fun `a scale property from an older version does not reach Vega`() {
    val scale =
      compile(
          """{"field":"c","type":"ordinal","scale":{"paddingInner":0,"paddingOuter":1,"rangeStep":1}}"""
        )
        .scale("x")
    assertNull(scale?.fields?.get("rangeStep"), "`rangeStep` was Vega-Lite 2's")
    assertEquals(
      VegaValue.Num(1.0),
      scale?.fields?.get("paddingOuter"),
      "the properties beside it are unaffected",
    )
  }

  /** A scale property this compiler knows is written as before. */
  @Test
  fun `a known scale property reaches Vega`() {
    assertEquals(
      VegaValue.Num(0.3),
      compile("""{"field":"c","type":"ordinal","scale":{"padding":0.3}}""")
        .scale("x")
        ?.fields
        ?.get("padding"),
    )
  }

  /** An axis property from an older Vega-Lite goes the same way. */
  @Test
  fun `an axis property from an older version does not reach Vega`() {
    val axis =
      compile(
          """{"field":"a","type":"quantitative","axis":{"axisWidth":0,"format":"%Y","labelAngle":0}}"""
        )
        .axis("x")
    assertNull(axis?.fields?.get("axisWidth"), "`axisWidth` was Vega-Lite 1's")
    assertEquals(VegaValue.Str("%Y"), axis?.fields?.get("format"))
    assertEquals(VegaValue.Num(0.0), axis?.fields?.get("labelAngle"))
  }

  /** A word from some later version, which needs listing nowhere. */
  @Test
  fun `an axis property this version has never heard of does not reach Vega`() {
    assertNull(
      compile("""{"field":"a","type":"quantitative","axis":{"notAThing":5}}""")
        .axis("x")
        ?.fields
        ?.get("notAThing")
    )
  }

  /**
   * A **grid** property is an axis property all the same, and lands on the axis that draws the
   * gridlines rather than on the one that draws the labels.
   */
  @Test
  fun `a grid property reaches the gridline axis`() {
    val compiled =
      compile("""{"field":"a","type":"quantitative","axis":{"grid":true,"gridCap":"round"}}""")
    assertEquals(VegaValue.Str("round"), compiled.gridAxis("x")?.fields?.get("gridCap"))
    assertNull(compiled.axis("x")?.fields?.get("gridCap"))
  }

  /** A turned label is still normalised on the way through. */
  @Test
  fun `a negative label angle is still turned into its positive twin`() {
    assertEquals(
      VegaValue.Num(315.0),
      compile("""{"field":"a","type":"quantitative","axis":{"labelAngle":-45}}""")
        .axis("x")
        ?.fields
        ?.get("labelAngle"),
    )
  }
}
