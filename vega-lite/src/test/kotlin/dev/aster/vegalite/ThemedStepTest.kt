package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A step is **that dimension's** own, and a theme may state one dimension and not the other.
 *
 * ```js
 * export function getViewConfigDiscreteStep(viewConfig, channel) {
 *   const size = getViewConfigDiscreteSize(viewConfig, channel);
 *   return isStep(size) ? size.step : DEFAULT_STEP;
 * }
 * ```
 *
 * `view.discreteWidth` answers for `x` and `view.discreteHeight` for `y`; `view.step` is the answer
 * only where neither is set, being what `getViewConfigDiscreteSize` falls back to. This compiler
 * read `view.step` and nothing else, so a document that spaces its bars thirty units apart drew
 * them twenty — and every reader of a step reads it: the position scale's own, the arithmetic a
 * grouped bar's band is widened by, the offset scale's range, the largest a sized point may be, and
 * the width a rect takes where nothing else settles one.
 *
 * Where the themed size is a plain **number** the step is `DEFAULT_STEP` and not `view.step`:
 * `isStep` is false and the fallback has already been passed. It is upstream's own reading, and it
 * shows in the one reader that asks for a step whether or not either dimension is sized by one.
 *
 * No specification in the wild corpus themes a step. This was found by reading
 * `getViewConfigDiscreteStep`'s callers beside this compiler's.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ThemedStepTest {

  private fun compiled(spec: String): VegaValue.Obj =
    VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
      as VegaValue.Obj

  /** The chart's signals, by name and settled value. */
  private fun signals(spec: String): String =
    (compiled(spec).fields["signals"] as? VegaValue.Arr)?.values.orEmpty().joinToString(",") {
      it as VegaValue.Obj
      val settled =
        it.fields["value"]?.let { value -> VegaJson.write(value) } ?: it.string("update")
      "${it.string("name")}=$settled"
    }

  /** One scale's range. */
  private fun range(spec: String, scale: String): String =
    VegaJson.write(
        (compiled(spec).fields["scales"] as VegaValue.Arr)
          .values
          .map { it as VegaValue.Obj }
          .first { it.string("name") == scale }
          .fields["range"]!!
      )
      .replace(Regex("""\n\s*"""), "")

  /** What the first mark is given along one dimension. */
  private fun extent(spec: String, size: String): String =
    VegaJson.write(
        ((compiled(spec).fields["marks"] as VegaValue.Arr).values[0] as VegaValue.Obj)
          .obj("encode")!!
          .obj("update")!!
          .fields[size]!!
      )
      .replace(Regex("""\n\s*"""), "")

  private val rows = """"data":{"values":[{"a":1,"b":"x","c":"y"}]}"""
  private val strip =
    """"mark":"bar","encoding":{"y":{"field":"b","type":"nominal"},
                                "x":{"field":"a","type":"quantitative"}}"""
  private val grouped =
    """"mark":"bar","encoding":{"x":{"field":"b","type":"nominal"},
                                "xOffset":{"field":"c","type":"nominal"},
                                "y":{"field":"a","type":"quantitative"}}"""
  private val sized =
    """"mark":"point","encoding":{"y":{"field":"b","type":"nominal"},
                                  "size":{"field":"a","type":"quantitative"}}"""

  /**
   * A bar on a continuous scale with its `continuousBandSize` taken away: nothing is left to settle
   * its width but the step, which is the reader `defaultStep - 2` is.
   */
  private fun plainBar(view: String) =
    """{$rows,"config":{"view":{$view},"bar":{"continuousBandSize":null}},"mark":"bar",
       "encoding":{"x":{"field":"a","type":"quantitative"},
                   "y":{"field":"a","type":"quantitative"}}}"""

  /** The reported shape: the theme states the depth dimension's step, and `y` takes it. */
  @Test
  fun `a themed height step sizes the y band`() {
    assertEquals(
      "y_step=40,height=bandspace(domain('y').length, 0.1, 0.05) * y_step",
      signals("""{$rows,"config":{"view":{"discreteHeight":{"step":40}}},$strip}"""),
    )
  }

  /** `view.height` says the same thing, being the name the property had first. */
  @Test
  fun `the older name says the same`() {
    assertEquals(
      "y_step=40,height=bandspace(domain('y').length, 0.1, 0.05) * y_step",
      signals("""{$rows,"config":{"view":{"height":{"step":40}}},$strip}"""),
    )
  }

  /** The **other** dimension's step is not this one's, which is the whole of the rule. */
  @Test
  fun `a themed width step leaves the y band alone`() {
    assertEquals(
      "y_step=20,height=bandspace(domain('y').length, 0.1, 0.05) * y_step",
      signals("""{$rows,"config":{"view":{"discreteWidth":{"step":40}}},$strip}"""),
    )
  }

  /** `view.step` answers where the dimension states nothing, and gives way where it does. */
  @Test
  fun `the plain step answers only for an unthemed dimension`() {
    assertEquals(
      "y_step=15,height=bandspace(domain('y').length, 0.1, 0.05) * y_step",
      signals("""{$rows,"config":{"view":{"step":15}},$strip}"""),
    )
    assertEquals(
      "y_step=40,height=bandspace(domain('y').length, 0.1, 0.05) * y_step",
      signals("""{$rows,"config":{"view":{"step":15,"discreteHeight":{"step":40}}},$strip}"""),
    )
  }

  /** A grouped bar's band is widened from the themed step, and its lanes are that wide each. */
  @Test
  fun `a themed step widens a grouped band`() {
    val spec = """{$rows,"config":{"view":{"discreteWidth":{"step":40}}},$grouped}"""
    assertEquals(
      "x_step=40 * bandspace(domain('xOffset').length, 0, 0) / (1-0.2)," +
        "width=bandspace(domain('x').length, 0.2, 0.2) * x_step",
      signals(spec),
    )
    assertEquals("""{"step": 40}""", range(spec, "xOffset"))
  }

  /**
   * A themed **depth** is not a step, so there is nothing for the lanes to be one step each of and
   * the offset divides the band instead — the same answer a stated width gives.
   */
  @Test
  fun `a themed depth leaves the lanes to divide the band`() {
    val spec = """{$rows,"config":{"view":{"discreteWidth":100}},$grouped}"""
    assertEquals("", signals(spec))
    assertEquals("""[0,{"signal": "bandwidth('x')"}]""", range(spec, "xOffset"))
  }

  /** The largest a sized point may be is bounded by the **smaller** of the two steps. */
  @Test
  fun `the size range is bounded by the smaller step`() {
    assertEquals(
      "[4,812.25]",
      range("""{$rows,"config":{"view":{"step":30}},$sized}""", "size"),
    )
    assertEquals(
      "[4,32.489999999999995]",
      range("""{$rows,"config":{"view":{"discreteWidth":{"step":6}}},$sized}""", "size"),
    )
  }

  /**
   * And a themed **depth** answers that reader with `DEFAULT_STEP`, not with `view.step`: the
   * fallback to `{step: view.step}` has already been passed by the time `isStep` is asked.
   */
  @Test
  fun `a themed depth bounds the size range at the default step`() {
    assertEquals(
      "[4,361]",
      range("""{$rows,"config":{"view":{"discreteHeight":100,"step":30}},$sized}""", "size"),
    )
  }

  /** The width a rect takes where nothing else settles one is a step less two, that step. */
  @Test
  fun `a rect with nothing to size it takes a step less two`() {
    assertEquals("""{"value": 18}""", extent(plainBar(""), "width"))
    assertEquals("""{"value": 28}""", extent(plainBar(""""discreteWidth":{"step":30}"""), "width"))
    // The other dimension's, again, is not this one's.
    assertEquals("""{"value": 18}""", extent(plainBar(""""discreteHeight":{"step":30}"""), "width"))
    assertEquals("""{"value": 28}""", extent(plainBar(""""step":30"""), "width"))
    // And a themed depth is the default step here too.
    assertEquals("""{"value": 18}""", extent(plainBar(""""discreteWidth":100"""), "width"))
  }
}
