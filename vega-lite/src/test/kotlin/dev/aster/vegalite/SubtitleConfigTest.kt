package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * `config.title` survives, holding its **subtitle** properties and nothing else.
 *
 * ```js
 * // subtitle part can stay in config.title since header titles do not use subtitle
 * if (!isEmpty(subtitle)) {
 *   config.title = subtitle;
 * } else {
 *   delete config.title;
 * }
 * ```
 *
 * The rest of the block has become styles by then — the paint is `group-title`, the placement is
 * `group-subtitle`, and the six non-mark properties went onto the title directive. These seven are
 * the part that stays, because a **header** title has no subtitle and Vega's own title directive
 * reads them from the configuration.
 *
 * This consumed the whole block, so a theme setting `subtitleFont` had nowhere to say it and the
 * subtitle was drawn in the title's face. Seven specifications in the wild corpus set one.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class SubtitleConfigTest {

  private fun config(title: String): VegaValue.Obj {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2}]},"mark":"point","config":{"title":$title},
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    return compiled.fields["config"] as VegaValue.Obj
  }

  private val subtitleOnly = """{"subtitleFont":"Roboto","subtitleColor":"#44475a"}"""
  private val expected =
    VegaValue.Obj(
      linkedMapOf(
        "subtitleColor" to VegaValue.Str("#44475a"),
        "subtitleFont" to VegaValue.Str("Roboto"),
      )
    )

  /** The reported shape: a theme that styles the subtitle. */
  @Test
  fun `the subtitle properties stay in config title`() {
    assertEquals(expected, config(subtitleOnly).fields["title"])
  }

  /** With the title's own properties beside them, which become styles instead. */
  @Test
  fun `the title's own properties become styles and the subtitle's stay`() {
    val config =
      config(
        """{"font":"Roboto","color":"#44475a","subtitleFont":"Roboto","subtitleColor":"#44475a"}"""
      )
    assertEquals(expected, config.fields["title"])
    assertEquals(
      VegaValue.Obj(
        linkedMapOf("font" to VegaValue.Str("Roboto"), "fill" to VegaValue.Str("#44475a"))
      ),
      (config.fields["style"] as VegaValue.Obj).fields["group-title"],
    )
  }

  /** With nothing said about the subtitle, `config.title` goes entirely. */
  @Test
  fun `a title config with no subtitle properties is dropped`() {
    val config = config("""{"font":"Roboto","color":"#44475a"}""")
    assertNull(config.fields["title"])
  }

  /** All seven, in upstream's order. */
  @Test
  fun `all seven subtitle properties stay`() {
    val config =
      config(
        """{"subtitleColor":"c","subtitleFont":"f","subtitleFontSize":1,"subtitleFontStyle":"s",
           "subtitleFontWeight":"w","subtitleLineHeight":2,"subtitlePadding":3}"""
      )
    assertEquals(
      listOf(
        "subtitleColor",
        "subtitleFont",
        "subtitleFontSize",
        "subtitleFontStyle",
        "subtitleFontWeight",
        "subtitleLineHeight",
        "subtitlePadding",
      ),
      (config.fields["title"] as VegaValue.Obj).fields.keys.toList(),
    )
  }
}
