package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Which way a legend runs is decided by the side it sits on, and the **theme** names that side too.
 *
 * ```js
 * return (
 *   legend.direction ??
 *   legendConfig[legendType ? 'gradientDirection' : 'symbolDirection'] ??
 *   defaultDirection(orient, legendType)
 * );
 * ```
 *
 * with `orient` being `legend.orient || config.legend.orient || 'right'`. So a theme saying
 * `config.legend.orient: "top"` turns every key in the document horizontal. This engine read the
 * channel's own orient alone, so such a chart came out with its keys stacked vertically along the
 * top edge — four specifications in the wild corpus.
 *
 * Two quirks are reproduced rather than repaired. `legendType` is `'symbol'` or `'gradient'` and
 * **both are truthy**, so the ternary always reads `gradientDirection`: `symbolDirection` is never
 * consulted at all, and a theme that turns its swatch columns sideways has to say
 * `gradientDirection` to do it. And `config.legend.direction` is *not* one of the places asked — it
 * settles what Vega draws from its own config block, and takes no part in the direction measured
 * here, which is why a themed `direction` leaves the ramp's length vertical.
 *
 * `defaultDirection` answers nothing for left, right and `none`, vertical being Vega's own default,
 * and lays an *inner* legend out compactly "like Tableau" — but only a ramp, a column of swatches
 * being compact already.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class LegendDirectionTest {

  /** The legend's direction and, for a ramp, the length that follows from it. */
  private fun legend(colour: String, config: String = ""): List<String> {
    val theme = if (config.isEmpty()) "" else ""","config":{"legend":$config}"""
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"t":"x","n":5}]},"mark":"point"$theme,
               "encoding":{"x":{"field":"a","type":"quantitative"},"color":$colour}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val legend =
      (compiled.fields["legends"] as? VegaValue.Arr)?.values?.firstOrNull() as? VegaValue.Obj
    return listOf("direction", "gradientLength").mapNotNull { key ->
      legend?.fields?.get(key)?.let { value ->
        val text =
          when (value) {
            is VegaValue.Str -> value.value
            is VegaValue.Num -> value.value.toString()
            is VegaValue.Obj -> value.string("signal").orEmpty()
            else -> value.toString()
          }
        "$key=$text"
      }
    }
  }

  private val swatches = """{"field":"t","type":"nominal"}"""
  private val ramp = """{"field":"n","type":"quantitative"}"""

  /** The reported shape: a theme that puts every key along the top. */
  @Test
  fun `a themed orient decides the direction`() {
    assertEquals(listOf("direction=horizontal"), legend(swatches, """{"orient":"top"}"""))
    assertEquals(listOf("direction=horizontal"), legend(swatches, """{"orient":"bottom"}"""))
  }

  /** The channel's own orient, which worked before — and is still written out beside it. */
  @Test
  fun `the channel's own orient decides it too`() {
    assertEquals(
      listOf("direction=horizontal"),
      legend("""{"field":"t","type":"nominal","legend":{"orient":"top"}}"""),
    )
  }

  /** Left, right and `none` keep Vega's vertical, which is written as nothing at all. */
  @Test
  fun `a legend down the side says nothing about its direction`() {
    assertEquals(emptyList<String>(), legend(swatches, """{"orient":"left"}"""))
    assertEquals(emptyList<String>(), legend(swatches))
  }

  /** An **inner** legend is compact only as a ramp. */
  @Test
  fun `a legend at a corner is horizontal only as a ramp`() {
    assertEquals(emptyList<String>(), legend(swatches, """{"orient":"top-left"}"""))
    assertEquals(
      listOf("direction=horizontal", "gradientLength=100.0"),
      legend(ramp, """{"orient":"top-left"}"""),
    )
  }

  /** A ramp's **length** follows the direction, and only along the measure it lies against. */
  @Test
  fun `a ramp's length follows the direction`() {
    assertEquals(
      listOf("gradientLength=clamp(height, 64, 200)"),
      legend(ramp),
      "down the right-hand side, so as tall as the plot",
    )
    assertEquals(
      listOf("direction=horizontal", "gradientLength=clamp(width, 100, 200)"),
      legend(ramp, """{"orient":"top"}"""),
      "along the top, so as wide as the plot",
    )
  }

  /**
   * `gradientDirection` is read for **both** kinds of legend, the ternary testing a value that is
   * always truthy, and it outranks the side.
   */
  @Test
  fun `a themed gradient direction turns a column of swatches too`() {
    assertEquals(
      listOf("direction=horizontal"),
      legend(swatches, """{"gradientDirection":"horizontal"}"""),
    )
    assertEquals(
      listOf("direction=vertical"),
      legend(swatches, """{"gradientDirection":"vertical","orient":"top"}"""),
      "it is asked before the side is",
    )
  }

  /** And `symbolDirection` is asked for nothing at all, by either kind. */
  @Test
  fun `a themed symbol direction is never read`() {
    assertEquals(emptyList<String>(), legend(swatches, """{"symbolDirection":"horizontal"}"""))
    assertEquals(
      listOf("gradientLength=clamp(height, 64, 200)"),
      legend(ramp, """{"symbolDirection":"horizontal"}"""),
    )
  }

  /**
   * A `direction` the **theme** states takes no part in this: it settles what Vega draws, so the
   * ramp beside it is still measured as a vertical one and the property is left off the legend.
   */
  @Test
  fun `a themed direction is left to Vega`() {
    assertEquals(
      listOf("gradientLength=clamp(height, 64, 200)"),
      legend(ramp, """{"direction":"horizontal"}"""),
    )
    assertEquals(
      listOf("direction=horizontal", "gradientLength=100.0"),
      legend("""{"field":"n","type":"quantitative","legend":{"direction":"horizontal"}}"""),
      "where the channel states it, the ramp is measured as the horizontal one it now is",
    )
  }
}
