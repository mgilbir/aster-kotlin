package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A mark's fill and stroke are read under their **own Vega names**, not only under `color`.
 *
 * ```js
 * const defaultFill =
 *   getMarkPropOrConfig(filled === true ? 'color' : undefined, markDef, config, {vgChannel: 'fill'}) ??
 *   markDef.fill ?? config.mark.fill ?? transparentIfNeeded;
 * const defaultStroke =
 *   getMarkPropOrConfig(filled === false ? 'color' : undefined, markDef, config, {vgChannel: 'stroke'}) ??
 *   markDef.stroke ?? config.mark.stroke;
 * ```
 *
 * `color` answers only for the one the colour *is* — the fill of a filled mark, the stroke of a
 * hollow one — and each of the two is looked up under its Vega name whatever the mark is filled
 * with. So a theme that strokes every point black, `config.point.stroke`, strokes a **filled**
 * point too, and a `config.mark.fill` fills a line that is not filled at all.
 *
 * This engine read `color` alone, so such a theme was read and dropped: five specifications in the
 * wild corpus theme their marks that way. The chain each name is looked up in is `getMarkConfig`'s
 * — the mark's own property, then the style blocks it names, then the configuration for its kind,
 * then `config.mark`.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class MarkColourConfigTest {

  /** Each mark as `type|fill|stroke`, which is what a theme of colours settles. */
  private fun painted(spec: String): List<String> {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["marks"] as VegaValue.Arr)
      .values
      .map { it as VegaValue.Obj }
      .map { mark ->
        val update = (mark.fields["encode"] as VegaValue.Obj).fields["update"] as VegaValue.Obj
        fun paint(name: String) =
          ((update.fields[name] as? VegaValue.Obj)?.fields?.get("value") as? VegaValue.Str)?.value
            ?: "-"
        "${mark.string("type")}|${paint("fill")}|${paint("stroke")}"
      }
  }

  private val data = """"data":{"values":[{"a":1,"b":2}]}"""
  private val positions =
    """"encoding":{"x":{"field":"a","type":"quantitative"},
                   "y":{"field":"b","type":"quantitative"}}"""

  private fun chart(mark: String, config: String = "") =
    """{$data,$config"mark":$mark,$positions}"""

  /** The reported shape: a theme that strokes every point, whatever the point is filled with. */
  @Test
  fun `a themed stroke reaches a filled point`() {
    assertEquals(
      listOf("symbol|#4c78a8|black"),
      painted(chart("\"point\"", """"config":{"point":{"stroke":"black","filled":true}},""")),
    )
  }

  /** And a themed fill reaches a line, which is not filled at all. */
  @Test
  fun `a themed fill reaches a line`() {
    assertEquals(
      listOf("line|#7aa2f7|#4c78a8"),
      painted(chart("\"line\"", """"config":{"mark":{"fill":"#7aa2f7"}},""")),
    )
  }

  /** With nothing themed, a line is stroked in the default colour and not filled. */
  @Test
  fun `a plain line is stroked and not filled`() {
    assertEquals(listOf("line|-|#4c78a8"), painted(chart("\"line\"")))
  }

  /** A mark's own property still beats every theme, which is what `markDef` first means. */
  @Test
  fun `a mark's own fill beats the theme`() {
    assertEquals(
      listOf("line|white|#4c78a8"),
      painted(
        chart("""{"type":"line","fill":"white"}""", """"config":{"mark":{"fill":"#7aa2f7"}},""")
      ),
    )
  }

  /**
   * A **style block** is not part of this chain, and does not need to be: a style block is
   * something *Vega* applies, the mark carrying its names in `style`. So a `stroke` kept in one
   * leaves the mark stroked in the default colour, and Vega paints it red itself.
   */
  @Test
  fun `a styled stroke is left for Vega to apply`() {
    assertEquals(
      listOf("symbol|transparent|#4c78a8"),
      painted(
        chart(
          """{"type":"point","style":"s1"}""",
          """"config":{"style":{"s1":{"stroke":"red"}}},""",
        )
      ),
    )
  }

  /** A colour written in one *is* read here, `color` being a name Vega has never heard of. */
  @Test
  fun `a styled colour reaches the mark`() {
    assertEquals(
      listOf("symbol|transparent|green"),
      painted(
        chart(
          """{"type":"point","style":"s1"}""",
          """"config":{"style":{"s1":{"color":"green"}}},""",
        )
      ),
    )
  }
}
