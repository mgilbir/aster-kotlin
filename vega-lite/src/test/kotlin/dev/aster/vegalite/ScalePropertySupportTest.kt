package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A scale keeps only the properties its **type** has.
 *
 * `parseScaleProperty` asks `scaleTypeSupportProperty` of every property it is given — derived or
 * stated — and drops the ones that do not apply, with a warning of the form `x-scale's "zero" is
 * dropped as it does not work with time scale`. A `base` belongs to a logarithm, an `exponent` to a
 * power, a `constant` to a symlog; the ends of a domain and a `clamp` need a continuous domain to
 * be the ends of.
 *
 * The gate existed here but two things were wrong with it. Half of upstream's cases were missing,
 * so `base`, `exponent`, `constant`, `clamp` and the domain ends were never questioned. And the
 * pass that copies *stated* scale properties wrote them straight into the component, going round
 * the gate entirely — so a `{"zero": false}` written on a temporal scale reached Vega, which has no
 * zero on a time scale.
 *
 * The wild corpus warns about this from upstream's own side: fourteen of its specifications state a
 * `base` on a scale that is not a logarithm.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ScalePropertySupportTest {

  private fun xScale(type: String, scale: String): VegaValue.Obj {
    val field = if (type == "temporal") "t" else "a"
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"t":"2020-01-01"}]},"mark":"point",
               "encoding":{"x":{"field":"$field","type":"$type","scale":$scale},
                           "y":{"field":"b","type":"quantitative"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["scales"] as VegaValue.Arr)
      .values
      .mapNotNull { it as? VegaValue.Obj }
      .first { (it.fields["name"] as? VegaValue.Str)?.value == "x" }
  }

  /** The reported shape: a `zero` stated on a time scale, which has no zero. */
  @Test
  fun `a zero stated on a time scale is dropped`() {
    val scale = xScale("temporal", """{"zero":false}""")
    assertEquals(VegaValue.Str("time"), scale.fields["type"])
    assertNull(scale.fields["zero"])
  }

  /** `base` is a logarithm's, and fourteen of the corpus's specifications put one elsewhere. */
  @Test
  fun `a base is kept only on a log scale`() {
    assertNull(xScale("quantitative", """{"base":2}""").fields["base"])
    assertEquals(
      VegaValue.Num(2.0),
      xScale("quantitative", """{"type":"log","base":2}""").fields["base"],
    )
  }

  /** `exponent` is a power's. */
  @Test
  fun `an exponent is kept only on a pow scale`() {
    assertNull(xScale("quantitative", """{"exponent":2}""").fields["exponent"])
    assertEquals(
      VegaValue.Num(2.0),
      xScale("quantitative", """{"type":"pow","exponent":2}""").fields["exponent"],
    )
  }

  /** `constant` is a symlog's. */
  @Test
  fun `a constant is kept only on a symlog scale`() {
    assertNull(xScale("quantitative", """{"constant":2}""").fields["constant"])
    assertEquals(
      VegaValue.Num(2.0),
      xScale("quantitative", """{"type":"symlog","constant":2}""").fields["constant"],
    )
  }

  /** A `clamp` and the ends of a domain need a continuous domain to apply to. */
  @Test
  fun `a clamp and a domain end need a continuous domain`() {
    assertNull(xScale("nominal", """{"clamp":true}""").fields["clamp"])
    assertNull(xScale("nominal", """{"domainMin":0}""").fields["domainMin"])
    assertEquals(
      VegaValue.Bool(true),
      xScale("quantitative", """{"clamp":true}""").fields["clamp"],
    )
    assertEquals(
      VegaValue.Num(0.0),
      xScale("quantitative", """{"domainMin":0}""").fields["domainMin"],
    )
  }

  /** And a band property on a scale that is not one — the gate reaching the way it always did. */
  @Test
  fun `an inner padding is kept off a point scale`() {
    assertNull(xScale("nominal", """{"paddingInner":0.2}""").fields["paddingInner"])
  }
}
