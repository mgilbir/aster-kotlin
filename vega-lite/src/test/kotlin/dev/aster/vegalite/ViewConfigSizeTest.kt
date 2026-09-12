package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The view configuration's sizes, read the way upstream reads them.
 *
 * Two rules, both in `config.ts`:
 * ```js
 * export function getViewConfigContinuousSize(viewConfig, channel) {
 *   return viewConfig[channel] ?? viewConfig[channel === 'width' ? 'continuousWidth' : 'continuousHeight'];
 * }
 * export function getViewConfigDiscreteSize(viewConfig, channel) {
 *   const size = viewConfig[channel] ?? viewConfig[channel === 'width' ? 'discreteWidth' : 'discreteHeight'];
 *   return getFirstDefined(size, {step: viewConfig.step});
 * }
 * ```
 *
 * `view.width` and `view.height` are the names those properties had before the continuous and
 * discrete sizes were told apart, and both readers still ask for them **first** — "get width/height
 * for backwards compatibility". A theme written against an older Vega-Lite sizes its plots that
 * way, and this engine read only the newer names on the continuous side, so such a chart was drawn
 * at the default 300. Eight specifications in the wild corpus size themselves that way.
 *
 * The discrete side answers a **number** where the theme states one, and `{step: …}` only otherwise
 * — so a themed discrete size replaces the step arithmetic entirely: every strip in the document is
 * that deep, however many categories it holds, and there is no `«scale»_step` signal to compute it
 * from. The specification's own `width` still outranks the theme either way.
 *
 * The two properties are *not* stripped from the emitted configuration, unlike the four newer
 * names: they land in `config.style.cell`, where Vega reads them as a group's own size.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ViewConfigSizeTest {

  private class Compiled(val spec: VegaValue.Obj) {
    val width = spec.fields["width"]
    val height = spec.fields["height"]
    val signals =
      (spec.fields["signals"] as? VegaValue.Arr)?.values?.mapNotNull {
        (it as? VegaValue.Obj)?.string("name")
      }
    val cellStyle = spec.obj("config")?.obj("style")?.obj("cell")
  }

  private fun compile(view: String, encoding: String, extra: String = ""): Compiled =
    Compiled(
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":"point",
               "config":{"view":$view}$extra,"encoding":$encoding}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    )

  private val measured =
    """{"x":{"field":"a","type":"quantitative"},"y":{"field":"b","type":"quantitative"}}"""
  private val categorical =
    """{"x":{"field":"c","type":"nominal"},"y":{"field":"b","type":"quantitative"}}"""

  /** The reported shape: a theme sizing its plots by the older names. */
  @Test
  fun `the older names size a continuous plot`() {
    val compiled = compile("""{"width":400,"height":250}""", measured)
    assertEquals(VegaValue.Num(400.0), compiled.width)
    assertEquals(VegaValue.Num(250.0), compiled.height)
  }

  /** The newer ones still work, and the older ones outrank them. */
  @Test
  fun `the older names outrank the newer ones`() {
    assertEquals(VegaValue.Num(400.0), compile("""{"continuousWidth":400}""", measured).width)
    assertEquals(
      VegaValue.Num(400.0),
      compile("""{"width":400,"continuousWidth":500}""", measured).width,
    )
  }

  /** With neither, a continuous plot is three hundred as it always was. */
  @Test
  fun `a continuous plot with no theme is three hundred`() {
    assertEquals(VegaValue.Num(300.0), compile("{}", measured).width)
  }

  /** A themed **discrete** size replaces the step, and the signal that computed it goes. */
  @Test
  fun `a themed discrete size replaces the step`() {
    val byNewName = compile("""{"discreteWidth":400}""", categorical)
    assertEquals(VegaValue.Num(400.0), byNewName.width)
    assertNull(byNewName.signals, "no step to compute the width from")
    assertEquals(VegaValue.Num(400.0), compile("""{"width":400}""", categorical).width)
  }

  /** A themed **step** is the step, and the width is the signal computed from it. */
  @Test
  fun `a themed step leaves the width to be computed`() {
    val stepped = compile("""{"step":40}""", categorical)
    assertNull(stepped.width)
    assertEquals(listOf("x_step", "width"), stepped.signals)
    assertEquals(listOf("x_step", "width"), compile("{}", categorical).signals)
  }

  /** The specification's own size outranks the theme, whichever way it is written. */
  @Test
  fun `the specification's own size outranks the theme`() {
    val stepped = compile("""{"discreteWidth":400}""", categorical, ""","width":{"step":50}""")
    assertNull(stepped.width, "a stated step is a step, and the theme's number does not replace it")
    assertEquals(listOf("x_step", "width"), stepped.signals)
    assertEquals(
      VegaValue.Num(222.0),
      compile("""{"discreteWidth":400}""", categorical, ""","width":222""").width,
    )
  }

  /**
   * The two older names reach the emitted configuration, where the four newer ones do not: Vega
   * reads them off the cell's own style.
   */
  @Test
  fun `the older names reach the cell's style`() {
    val compiled = compile("""{"width":400,"stroke":"red"}""", measured)
    assertEquals(VegaValue.Num(400.0), compiled.cellStyle?.fields?.get("width"))
    assertEquals(VegaValue.Str("red"), compiled.cellStyle?.fields?.get("stroke"))
    assertNull(
      compile("""{"continuousWidth":400,"stroke":"red"}""", measured)
        .cellStyle
        ?.fields
        ?.get("continuousWidth"),
      "the newer names are Vega-Lite's own arithmetic and are stripped",
    )
  }
}
