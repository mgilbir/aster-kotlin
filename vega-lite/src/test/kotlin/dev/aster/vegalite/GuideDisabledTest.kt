package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A guide is switched off by any **falsy** value, not only by `null`.
 *
 * `parseLegendForChannel` and `parseAxis` settle it identically:
 * ```js
 * const disable = legend !== undefined ? !legend : legendConfig.disable;
 * ```
 *
 * So the question is JavaScript truthiness. `"legend": false` is what specifications in the wild
 * actually write — the documented spelling is `null`, and the schema does not admit `false` — and
 * upstream honours it because `!false` is true. This engine compared against `null` alone and left
 * the key with a legend beside it.
 *
 * The scale asks a narrower question, `specifiedScale !== null && specifiedScale !== false`, and is
 * checked here beside the other two so the difference is on the record rather than assumed away.
 *
 * The second arm of the same line is the theme's: where a channel says nothing about its legend,
 * `config.legend.disable` decides. The axis beside it has honoured `config.axis.disable` all along.
 * Together the two arms account for 25 of the wild corpus's disagreements over legends.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class GuideDisabledTest {

  private fun compiled(colour: String, position: String = "", config: String = ""): VegaValue.Obj =
    VegaJson.parse(
      requireNotNull(
        VegaLiteCompiler()
          .compileJson(
            """
            {"data":{"values":[{"a":"A","b":28,"c":"x"}]},
             "mark":"point",$config
             "encoding":{
               "x":{"field":"a","type":"nominal"$position},
               "y":{"field":"b","type":"quantitative"},
               "color":{"field":"c","type":"nominal"$colour}}}
            """
          )
          .toJson()
      ) {
        "did not compile"
      }
    ) as VegaValue.Obj

  private fun legendTitles(spec: VegaValue.Obj): List<String>? =
    (spec.fields["legends"] as? VegaValue.Arr)?.values?.map {
      ((it as? VegaValue.Obj)?.fields?.get("title") as? VegaValue.Str)?.value ?: "(untitled)"
    }

  private fun count(spec: VegaValue.Obj, key: String): Int =
    (spec.fields[key] as? VegaValue.Arr)?.values?.size ?: 0

  /** The reported shape, and the one the schema does not even admit. */
  @Test
  fun `a legend switched off with false is not drawn`() {
    assertNull(legendTitles(compiled(""","legend":false""")), "`!false` is true, so it is disabled")
  }

  /** The documented spelling, which worked already and is kept honest. */
  @Test
  fun `a legend switched off with null is not drawn`() {
    assertNull(legendTitles(compiled(""","legend":null""")))
  }

  /**
   * An **empty object** is truthy, which is what makes `"legend": {}` a legend with no properties
   * of its own rather than no legend.
   */
  @Test
  fun `an empty legend block still draws a legend`() {
    assertEquals(listOf("c"), legendTitles(compiled(""","legend":{}""")))
  }

  /** The theme's arm: nothing said on the channel, so `config.legend.disable` decides. */
  @Test
  fun `a legend disabled by the theme is not drawn`() {
    assertNull(legendTitles(compiled("", config = """"config":{"legend":{"disable":true}},""")))
  }

  /** And a channel that states its legend has spoken, whatever the theme says. */
  @Test
  fun `a stated legend outranks a theme that disables every legend`() {
    assertEquals(
      listOf("kept"),
      legendTitles(
        compiled(
          ""","legend":{"title":"kept"}""",
          config = """"config":{"legend":{"disable":true}},""",
        )
      ),
    )
  }

  /** The axis takes the same rule, being the same line of upstream. */
  @Test
  fun `an axis switched off with false is not drawn`() {
    val withAxis = compiled("")
    val withoutAxis = compiled("", position = ""","axis":false""")
    assertEquals(
      count(withAxis, "axes") - 1,
      count(withoutAxis, "axes"),
      "one axis fewer, and the legend untouched",
    )
    assertEquals(listOf("c"), legendTitles(withoutAxis))
  }

  /**
   * A scale switched off with `false` goes too — upstream names `null` and `false` rather than
   * asking about truthiness, so this is the same answer reached a different way.
   */
  @Test
  fun `a scale switched off with false is not built`() {
    val spec = compiled("", position = ""","scale":false""")
    val names =
      (spec.fields["scales"] as? VegaValue.Arr)?.values?.mapNotNull {
        ((it as? VegaValue.Obj)?.fields?.get("name") as? VegaValue.Str)?.value
      }
    assertEquals(false, names?.contains("x"), "there is no `x` scale to place anything on")
  }
}
