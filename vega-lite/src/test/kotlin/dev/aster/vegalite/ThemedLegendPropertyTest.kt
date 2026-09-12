package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A legend property the **theme** states is not written onto the legend at all.
 *
 * ```js
 * const value = property in legendRules ? legendRules[property](ruleParams) : (legend as any)[property];
 * if (value !== undefined) {
 *   const explicit = …;
 *   if (explicit || config.legend[property] === undefined) {
 *     legendCmpt.set(property, value, explicit);
 *   }
 * }
 * ```
 *
 * `config.legend` goes out beside the chart in Vega's own config block, and Vega applies it from
 * there to every legend at once. Writing a *derived* value onto this legend as well would settle
 * the property for it alone — and settle it with a **default**, which is how `"config": {"legend":
 * {"title": false}}` came out with every caption still drawn: the derived caption was written on
 * the legend, where it outranked the theme that had turned captions off.
 *
 * A value the specification stated on the channel is explicit and still wins, and for `title` that
 * includes one written on the *definition* rather than in its `legend` block:
 * ```js
 * case 'title':
 *   // title can be explicit if fieldDef.title is set
 *   if (property === 'title' && value === fieldDef?.title) { explicit = true; }
 * ```
 *
 * Two specifications in the wild corpus turn their captions off that way.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ThemedLegendPropertyTest {

  private fun legend(colour: String, config: String = ""): VegaValue.Obj? {
    val theme = if (config.isEmpty()) "" else ""","config":{"legend":$config}"""
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"t":"x"}]},"mark":"point"$theme,
               "encoding":{"x":{"field":"a","type":"quantitative"},"color":$colour}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["legends"] as? VegaValue.Arr)?.values?.firstOrNull() as? VegaValue.Obj
  }

  private val plain = """{"field":"t","type":"nominal"}"""

  /** The reported shape: a theme that turns every caption off. */
  @Test
  fun `a theme that turns captions off leaves the legend uncaptioned`() {
    assertNull(legend(plain, """{"title":false}""")?.fields?.get("title"))
    assertNull(legend(plain, """{"title":null}""")?.fields?.get("title"))
    assertEquals(
      VegaValue.Str("t"),
      legend(plain)?.fields?.get("title"),
      "with the theme silent, the derived caption is written as it always was",
    )
  }

  /** A caption the **definition** wrote is explicit, and beats the theme. */
  @Test
  fun `a stated caption survives a theme that turns captions off`() {
    assertEquals(
      VegaValue.Str("T"),
      legend("""{"field":"t","type":"nominal","title":"T"}""", """{"title":false}""")
        ?.fields
        ?.get("title"),
    )
    assertEquals(
      VegaValue.Str("T"),
      legend("""{"field":"t","type":"nominal","legend":{"title":"T"}}""", """{"title":false}""")
        ?.fields
        ?.get("title"),
      "and one written in the legend block, which was explicit already",
    )
  }

  /** It is not a rule about captions: any derived property the theme states goes the same way. */
  @Test
  fun `a theme that states the swatch shape leaves it off the legend`() {
    assertNull(legend(plain, """{"symbolType":"square"}""")?.fields?.get("symbolType"))
    assertEquals(
      VegaValue.Str("circle"),
      legend(plain)?.fields?.get("symbolType"),
      "and with the theme silent it is derived as before",
    )
  }

  /** A derived **format** too, which is where a legend over instants would have said too much. */
  @Test
  fun `a theme that states the label format leaves it off the legend`() {
    val temporal = """{"field":"t","type":"temporal"}"""
    assertNull(legend(temporal, """{"format":"%Y"}""")?.fields?.get("format"))
    assertEquals(VegaValue.Str("%b %d, %Y"), legend(temporal)?.fields?.get("format"))
  }

  /** A property the theme states that this compiler never derives is unaffected either way. */
  @Test
  fun `a theme that states an orientation leaves the legend alone`() {
    val legend = legend(plain, """{"orient":"left"}""")
    assertEquals(VegaValue.Str("t"), legend?.fields?.get("title"))
    assertNull(legend?.fields?.get("orient"), "it was never written here to begin with")
  }
}
