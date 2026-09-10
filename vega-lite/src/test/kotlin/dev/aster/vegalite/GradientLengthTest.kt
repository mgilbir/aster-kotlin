package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A colour ramp follows the plot only where it lies **along** the measure it would follow.
 *
 * ```js
 * if (direction === 'horizontal') {
 *   if (orient === 'top' || orient === 'bottom') {
 *     return gradientLengthSignal(model, 'width', gradientHorizontalMinLength, gradientHorizontalMaxLength);
 *   } else {
 *     return gradientHorizontalMinLength;
 *   }
 * } else {
 *   return gradientLengthSignal(model, 'height', gradientVerticalMinLength, gradientVerticalMaxLength);
 * }
 * ```
 *
 * A horizontal ramp *beside* the plot has no width to follow, and one placed by hand with `orient:
 * "none"` has no side at all — either is simply the shortest a horizontal ramp may be. A vertical
 * ramp follows the height wherever it sits, there being a height either way.
 *
 * This clamped in every case, so a ramp laid out by hand grew and shrank with a plot it is not
 * beside.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class GradientLengthTest {

  private fun gradientLength(legend: String): VegaValue? {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":3}]},"mark":"point",
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"},
                           "color":{"field":"c","type":"quantitative","legend":$legend}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return ((compiled.fields["legends"] as VegaValue.Arr).values.first() as VegaValue.Obj)
      .fields["gradientLength"]
  }

  private val alongTheWidth =
    VegaValue.Obj(linkedMapOf("signal" to VegaValue.Str("clamp(width, 100, 200)")))
  private val alongTheHeight =
    VegaValue.Obj(linkedMapOf("signal" to VegaValue.Str("clamp(height, 64, 200)")))

  /** Along the top or the bottom, a horizontal ramp is as long as the plot is wide. */
  @Test
  fun `a horizontal ramp above or below the plot follows its width`() {
    assertEquals(alongTheWidth, gradientLength("""{"direction":"horizontal","orient":"top"}"""))
    assertEquals(alongTheWidth, gradientLength("""{"direction":"horizontal","orient":"bottom"}"""))
  }

  /** The reported shape: a horizontal ramp placed by hand has no width to follow. */
  @Test
  fun `a horizontal ramp placed by hand is the shortest it may be`() {
    assertEquals(
      VegaValue.Num(100.0),
      gradientLength("""{"direction":"horizontal","orient":"none","legendX":0,"legendY":0}"""),
    )
  }

  /** Nor does one beside the plot, or in a corner of it. */
  @Test
  fun `a horizontal ramp beside the plot is the shortest it may be`() {
    assertEquals(
      VegaValue.Num(100.0),
      gradientLength("""{"direction":"horizontal","orient":"right"}"""),
    )
    assertEquals(
      VegaValue.Num(100.0),
      gradientLength("""{"direction":"horizontal","orient":"top-left"}"""),
    )
  }

  /** A vertical ramp follows the height wherever it sits — there is always a height. */
  @Test
  fun `a vertical ramp follows the height wherever it sits`() {
    assertEquals(alongTheHeight, gradientLength("""{"direction":"vertical","orient":"right"}"""))
    assertEquals(alongTheHeight, gradientLength("""{"direction":"vertical","orient":"top"}"""))
  }

  /** With nothing stated a ramp is vertical, and follows the height. */
  @Test
  fun `a ramp with nothing stated follows the height`() {
    assertEquals(alongTheHeight, gradientLength("""{}"""))
  }

  /** An `orient` alone turns the ramp horizontal, and then the top does follow the width. */
  @Test
  fun `an orient alone can turn the ramp and make it follow the width`() {
    assertEquals(alongTheWidth, gradientLength("""{"orient":"top"}"""))
  }
}
