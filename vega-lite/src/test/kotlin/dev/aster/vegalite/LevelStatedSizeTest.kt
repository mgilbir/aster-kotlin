package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A band's range is measured against the size the **level** settled on, not the view's own.
 *
 * ```js
 * function getDiscretePositionSize(channel, size, viewConfig) {
 *   const sizeValue = size[channel === X ? 'width' : 'height'];
 *   if (sizeValue !== undefined) { return sizeValue; }
 *   return getViewConfigDiscreteSize(viewConfig, sizeChannel);
 * }
 * ```
 *
 * `model.size` is the size the model was **given**, and a layer hands its members its own — which
 * is its first member's, by the rule that settles a layer's size. Read as this view's own instead,
 * a member that states nothing fell through to the theme where its sibling had already said the
 * level is one step per category: a band chart whose second layer asks for a step of thirteen came
 * out stretched across a themed width, with its bands as wide as the plot divided by their number.
 *
 * The theme is still what answers where **nothing** states a size, which is what makes a document
 * that sizes every plot with `config.view.discreteWidth` size them.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class LevelStatedSizeTest {

  /** The chart's width, and the range its `x` scale runs over. */
  private fun band(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val scale =
      (compiled.fields["scales"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first { it.string("name") == "x" }
    val width = compiled.fields["width"]?.let { VegaJson.write(it) } ?: "-"
    return "$width ${VegaJson.write(scale.fields["range"]!!).replace(Regex("""\n\s*"""), "")}"
  }

  private val rows = """"data":{"values":[{"a":"x","b":2}]}"""
  private val enc =
    """"encoding":{"x":{"field":"a","type":"nominal"},
                                   "y":{"field":"b","type":"quantitative"}}"""
  private val themed = """"config":{"view":{"width":600}},"""

  /** The reported shape: one layer states a step and the other states nothing. */
  @Test
  fun `a sibling's step sizes the whole level`() {
    assertEquals(
      """- {"step": {"signal": "x_step"}}""",
      band(
        """{$rows,$themed"layer":[{"mark":"bar",$enc},
             {"mark":"bar",$enc,"width":{"step":13}}]}"""
      ),
    )
  }

  /** With no member stating one the theme answers, which is what sizes a themed document. */
  @Test
  fun `the theme sizes a level no member states`() {
    assertEquals(
      "600 [0,{\"signal\": \"width\"}]",
      band("""{$rows,$themed"layer":[{"mark":"bar",$enc},{"mark":"bar",$enc}]}"""),
    )
  }

  /** A sibling stating a **number** sizes it as a number, and the band runs the whole way. */
  @Test
  fun `a sibling's number sizes the whole level`() {
    assertEquals(
      "400 [0,{\"signal\": \"width\"}]",
      band("""{$rows,$themed"layer":[{"mark":"bar",$enc},{"mark":"bar",$enc,"width":400}]}"""),
    )
  }

  /** A single view takes the theme, which is the case the theme is written for. */
  @Test
  fun `a single view takes the themed width`() {
    assertEquals("600 [0,{\"signal\": \"width\"}]", band("""{$rows,$themed"mark":"bar",$enc}"""))
  }

  /** And its own step where it states one. */
  @Test
  fun `a single view's own step beats the theme`() {
    assertEquals(
      """- {"step": {"signal": "x_step"}}""",
      band("""{$rows,$themed"mark":"bar",$enc,"width":{"step":13}}"""),
    )
  }

  /** With neither a theme nor a stated size the band is one configured step per category. */
  @Test
  fun `a band with nothing stated is one step per category`() {
    assertEquals("""- {"step": {"signal": "x_step"}}""", band("""{$rows,"mark":"bar",$enc}"""))
  }
}
