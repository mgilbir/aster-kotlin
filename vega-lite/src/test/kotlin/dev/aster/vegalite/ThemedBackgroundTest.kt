package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A theme's background is taken only where it is **truthy**, as its padding is.
 *
 * ```js
 * const outputConfig: Config<SignalRef> = omit(mergedConfig, configPropsWithExpr);
 *
 * for (const prop of ['background', 'lineBreak', 'padding'] as const) {
 *   if (mergedConfig[prop]) {
 *     (outputConfig as any)[prop] = signalRefOrValue(mergedConfig[prop]);
 *   }
 * }
 * ```
 *
 * `initConfig` takes all three of these off the configuration and puts back only the ones that are
 * truthy. A theme saying `{"background": null}` — a document whose charts are drawn on whatever is
 * behind them — is a theme with **no** background at all, and this compiler wrote the null out as
 * the chart's own. So did an empty string.
 *
 * A background the **chart** states is written as it stands, null included: that one is not the
 * theme's to drop.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ThemedBackgroundTest {

  /** What the chart says its background is, and whether it says anything at all. */
  private fun background(spec: String): String {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val stated = compiled.fields["background"]
    return if (stated == null) "absent" else VegaJson.write(stated).trim()
  }

  private val chart =
    """"data":{"values":[{"a":1,"b":2}]},"mark":"point",
       "encoding":{"x":{"field":"a","type":"quantitative"},
                   "y":{"field":"b","type":"quantitative"}}"""

  /** The reported shape: a theme that says its charts have no background. */
  @Test
  fun `a themed null background is no background`() {
    assertEquals("absent", background("""{$chart,"config":{"background":null}}"""))
  }

  /** An empty string is as untrue as a null, and goes the same way. */
  @Test
  fun `a themed empty background is no background`() {
    assertEquals("absent", background("""{$chart,"config":{"background":""}}"""))
  }

  /** A themed colour is the chart's background, as it always was. */
  @Test
  fun `a themed colour is the background`() {
    assertEquals("\"#eee\"", background("""{$chart,"config":{"background":"#eee"}}"""))
  }

  /** With nothing themed the default stands, which is white. */
  @Test
  fun `an unthemed chart is white`() {
    assertEquals("\"white\"", background("{$chart}"))
  }

  /** And a **chart** that states null states it: that one is not the theme's to drop. */
  @Test
  fun `a chart's own null background is written`() {
    assertEquals("null", background("""{$chart,"background":null}"""))
  }
}
