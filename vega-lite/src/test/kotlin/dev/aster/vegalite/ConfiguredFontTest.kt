package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * A configured font reaches the five styles that name it, and `config.font` itself does not
 * survive.
 *
 * `initConfig` lifts it out of the configuration and merges a derived block in its place, **under**
 * everything the specification wrote:
 * ```js
 * const {color, font, fontSize, selection, ...restConfig} = specifiedConfig;
 * const mergedConfig = mergeConfig({}, duplicate(defaultConfig),
 *   font ? fontConfig(font) : {}, …, restConfig || {});
 *
 * export function fontConfig(font: string): Config {
 *   return {
 *     text: {font},
 *     style: {'guide-label': {font}, 'guide-title': {font}, 'group-title': {font}, 'group-subtitle': {font}},
 *   };
 * }
 * ```
 *
 * Vega has no top-level `config.font`, so a theme naming one reached the renderer with the font in
 * a place nothing reads and the whole chart was drawn in the default face. The order matters as
 * much as the block: the specification's own style blocks merge **over** the font, so a
 * `guide-label` that names a colour keeps this font beside it rather than replacing the block.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ConfiguredFontTest {

  private fun style(config: String): VegaValue.Obj {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2}]},"mark":"point","config":$config,
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return (compiled.fields["config"] as VegaValue.Obj)
  }

  private val roboto = VegaValue.Str("Roboto")

  private fun blocks(config: VegaValue.Obj) =
    (config.fields["style"] as VegaValue.Obj).fields.mapValues { (_, v) ->
      (v as VegaValue.Obj).fields
    }

  /** The reported shape: a theme that names only a font. */
  @Test
  fun `a configured font reaches all five styles`() {
    val config = style("""{"font":"Roboto"}""")
    assertNull(config.fields["font"], "Vega has no top-level `config.font`")
    assertEquals(
      mapOf(
        "guide-label" to mapOf("font" to roboto),
        "guide-title" to mapOf("font" to roboto),
        "group-title" to mapOf("font" to roboto),
        "group-subtitle" to mapOf("font" to roboto),
        "text" to mapOf("font" to roboto),
      ),
      blocks(config),
    )
  }

  /** A style the specification wrote merges **over** the font rather than replacing its block. */
  @Test
  fun `a stated style keeps the font beside it`() {
    val blocks = blocks(style("""{"font":"Roboto","style":{"guide-label":{"fill":"#333"}}}"""))
    assertEquals(mapOf("font" to roboto, "fill" to VegaValue.Str("#333")), blocks["guide-label"])
    assertEquals(mapOf("font" to roboto), blocks["guide-title"])
  }

  /** And a mark-type block, which is redirected into the same place. */
  @Test
  fun `a stated text config keeps the font beside it`() {
    val blocks = blocks(style("""{"font":"Roboto","text":{"fontSize":9}}"""))
    assertEquals(mapOf("font" to roboto, "fontSize" to VegaValue.Num(9.0)), blocks["text"])
  }

  /**
   * And `config.title`, whose two derived styles land on the same blocks — so this checks the font
   * and the subtitle style together, which is the only place the two rules meet.
   */
  @Test
  fun `a configured title merges over the font in both title styles`() {
    val blocks = blocks(style("""{"font":"Roboto","title":{"dx":5}}"""))
    assertEquals(mapOf("font" to roboto, "dx" to VegaValue.Num(5.0)), blocks["group-title"])
    assertEquals(mapOf("font" to roboto, "dx" to VegaValue.Num(5.0)), blocks["group-subtitle"])
  }
}
