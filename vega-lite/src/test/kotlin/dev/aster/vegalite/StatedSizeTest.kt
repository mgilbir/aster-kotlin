package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Whatever a specification states as a size **is** the size, a step object aside.
 *
 * ```js
 * component.layoutSize.set(sizeType, isStep(specifiedSize) ? 'step' : specifiedSize, true);
 * ```
 *
 * `parseUnitLayoutSize` puts the specified size into the component without asking what kind of
 * value it is: a chart written `"width": "1024"` is 1024 wide, not the view's default. This engine
 * read a number and nothing else, so such a chart came out three hundred units wide — and the same
 * on a discrete scale, where a stated size is what stops the step arithmetic.
 *
 * ```js
 * topLevelProperties[signal.name] = +signal.value;
 * ```
 *
 * The string stops being one at the **hoist**: what moves to the top of the chart is coerced, so
 * the chart's own width is a number while the signal a plot of a concatenation keeps carries the
 * string as it was written. Two specifications in the wild corpus state a size as a string, one of
 * each kind.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class StatedSizeTest {

  /** The chart's own size and every signal that carries one. */
  private fun sizes(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun written(value: VegaValue?) =
      VegaJson.write(value ?: VegaValue.Null).replace(Regex("""\n\s*"""), "")
    val signals =
      (compiled.fields["signals"] as? VegaValue.Arr)?.values.orEmpty().map {
        it as VegaValue.Obj
        "${it.string("name")}=${written(it.fields["value"])}"
      }
    return "width=${written(compiled.fields["width"])} " +
      "height=${written(compiled.fields["height"])} signals=$signals"
  }

  private val data = """"data":{"values":[{"a":1,"b":2,"c":"x"}]}"""
  private val measured =
    """"encoding":{"x":{"field":"a","type":"quantitative"},
       "y":{"field":"b","type":"quantitative"}}"""
  private val categorical =
    """"encoding":{"x":{"field":"c","type":"nominal"},
       "y":{"field":"b","type":"quantitative"}}"""

  /** The reported shape: a size stated as a string. */
  @Test
  fun `a size stated as a string is the size`() {
    assertEquals(
      "width=1024 height=400 signals=[]",
      sizes("""{$data,"mark":"point",$measured,"width":"1024","height":"400"}"""),
    )
  }

  /** And it stops the step arithmetic, as a stated number does. */
  @Test
  fun `a stated string beats the step of a discrete scale`() {
    assertEquals(
      "width=250 height=300 signals=[]",
      sizes("""{$data,"mark":"bar",$categorical,"width":"250"}"""),
    )
  }

  /** A plot of a concatenation keeps the string as written: only the hoist coerces. */
  @Test
  fun `a plot's own size keeps the string`() {
    assertEquals(
      """width=null height=null signals=[childHeight=300, concat_0_width="250", """ +
        "concat_1_width=400]",
      sizes(
        """{$data,"vconcat":[{"mark":"point",$measured,"width":"250"},
           {"mark":"point",$measured,"width":400}]}"""
      ),
    )
  }

  /** Two plots agreeing on one string agree, and what is hoisted is the number. */
  @Test
  fun `plots that agree on a string are hoisted as a number`() {
    assertEquals(
      "width=250 height=null signals=[childHeight=300]",
      sizes(
        """{$data,"vconcat":[{"mark":"point",$measured,"width":"250"},
           {"mark":"point",$measured,"width":"250"}]}"""
      ),
    )
  }

  /** A `{"step": …}` is the one stated size that is not a size: it is a step. */
  @Test
  fun `a step is still a step`() {
    assertEquals(
      "width=null height=300 signals=[x_step=33, width=null]",
      sizes("""{$data,"mark":"bar",$categorical,"width":{"step":33}}"""),
    )
  }

  /** A stated number is unchanged, which is the whole of what must not move. */
  @Test
  fun `a stated number is unchanged`() {
    assertEquals(
      "width=640 height=300 signals=[]",
      sizes("""{$data,"mark":"point",$measured,"width":640}"""),
    )
  }
}
