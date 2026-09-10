package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A band scale's range is a **step** only where the size it is measured against is one.
 *
 * ```js
 * if (util.contains(['point', 'band'], scaleType)) {
 *   const positionSize = getDiscretePositionSize(channel, size, config.view);
 *   if (isStep(positionSize)) {
 *     const step = getPositionStep(positionSize, model, channel);
 *     return {step};
 *   }
 * }
 * return fullWidthOrHeightRange(channel, model, scaleType);
 * ```
 *
 * and `getDiscretePositionSize` is the specification's own `width` where it states one, and the
 * **theme's** discrete size otherwise — which is a step only where the theme states no number. So a
 * document that sizes every plot with `config.view.discreteWidth`, or with `width` under its older
 * name, has said how wide a band chart is: the scale then runs the whole way across rather than one
 * step per category, and there is no `«scale»_step` signal at all.
 *
 * This is the other half of the size rule `ViewConfigSizeTest` describes, and 21 specifications in
 * the wild corpus differ on this key alone.
 *
 * A **discrete** y runs top-to-bottom like an x — `[0, height]` — where a continuous one is
 * reversed.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class DiscretePositionRangeTest {

  private fun ranges(view: String, encoding: String, extra: String = ""): Map<String, String> {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":"bar",
               "config":{"view":$view}$extra,"encoding":$encoding}
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
      .associate { scale -> (scale.string("name") ?: "?") to render(scale.fields["range"]) }
  }

  /** A range as `0..width`, `height..0` or `step(«signal»)`. */
  private fun render(range: VegaValue?): String =
    when (range) {
      is VegaValue.Arr -> range.values.joinToString("..") { render(it) }
      is VegaValue.Obj ->
        range.string("signal")
          ?: range.fields["step"]?.let { "step(${render(it)})" }
          ?: range.toString()
      is VegaValue.Num -> canonical(range.value)
      else -> range.toString()
    }

  private fun canonical(number: Double) =
    if (number == number.toLong().toDouble()) number.toLong().toString() else number.toString()

  private val categorical =
    """{"x":{"field":"c","type":"nominal"},"y":{"field":"b","type":"quantitative"}}"""

  /** The reported shape: a theme that sizes every plot, and a band chart inside it. */
  @Test
  fun `a themed size makes the band scale span the plot`() {
    assertEquals(
      mapOf("x" to "0..width", "y" to "height..0"),
      ranges("""{"width":300}""", categorical),
    )
    assertEquals(
      mapOf("x" to "0..width", "y" to "height..0"),
      ranges("""{"discreteWidth":300}""", categorical),
      "the newer name says the same thing",
    )
  }

  /** A themed **step** leaves the range a step, which is what it always was. */
  @Test
  fun `a themed step leaves the range a step`() {
    assertEquals(
      mapOf("x" to "step(x_step)", "y" to "height..0"),
      ranges("""{"step":40}""", categorical),
    )
    assertEquals(
      mapOf("x" to "step(x_step)", "y" to "height..0"),
      ranges("{}", categorical),
      "and so does no theme at all",
    )
  }

  /** The specification's own size outranks the theme, whichever way it is written. */
  @Test
  fun `the specification's own size decides it`() {
    assertEquals(
      mapOf("x" to "step(x_step)", "y" to "height..0"),
      ranges("""{"width":300}""", categorical, ""","width":{"step":50}"""),
      "a stated step is a step, however the theme sizes the plot",
    )
    assertEquals(
      mapOf("x" to "0..width", "y" to "height..0"),
      ranges("""{"width":300}""", categorical, ""","width":222"""),
    )
  }

  /** A discrete **y** runs top-to-bottom, where a continuous one is reversed. */
  @Test
  fun `a discrete y is not reversed`() {
    assertEquals(
      mapOf("x" to "0..width", "y" to "0..height"),
      ranges(
        """{"height":300}""",
        """{"x":{"field":"a","type":"quantitative"},"y":{"field":"c","type":"nominal"}}""",
      ),
    )
  }
}
