package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A `view` block names the style its plotting area is drawn with.
 *
 * ```js
 * public assembleGroupStyle(): string | string[] {
 *   const {style} = this.view || {};
 *   if (style !== undefined) {
 *     return style;
 *   }
 *   if (this.encoding.x || this.encoding.y) {
 *     return 'cell';
 *   } else {
 *     return 'view';
 *   }
 * }
 * ```
 *
 * `cell` is a default like any other, and a chart that writes `{"view": {"style": "myStyle"}}` is
 * asking for its own style block instead — which is how a document paints the paper behind one
 * chart of a row differently from its neighbours. This engine derived `cell` or `view` from the
 * encoding and never asked, so such a chart was drawn with the default and its style block applied
 * to nothing. One specification in the wild corpus is a row of three plots, the last of which names
 * two styles of its own.
 *
 * A layer unions its members' answers — `styles.length > 1 ? styles : styles.length === 1 ?
 * styles[0] : undefined` — so a member naming a style and a member naming none come out as that
 * style beside `cell`. A **layer's own** `view.style` is not read at all: `LayerModel` asks its
 * children and nothing else, which is upstream's shape and is reproduced here rather than repaired.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class ViewStyleTest {

  private fun compiled(spec: String) =
    VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
      as VegaValue.Obj

  private fun style(spec: String): VegaValue? = compiled(spec).fields["style"]

  /** The style of the group that holds one cell of a trellis. */
  private fun cellStyle(spec: String): VegaValue? {
    val marks = (compiled(spec).fields["marks"] as? VegaValue.Arr)?.values.orEmpty()
    return (marks.first { (it as VegaValue.Obj).string("name") == "cell" } as VegaValue.Obj)
      .fields["style"]
  }

  private val data = """"data":{"values":[{"c":"x","b":1,"d":"q"}]}"""
  private val positions =
    """"encoding":{"x":{"field":"c","type":"nominal"},"y":{"field":"b","type":"quantitative"}}"""

  /** The reported shape: a plot that names its own style. */
  @Test
  fun `a view block naming a style replaces the default`() {
    assertEquals(
      VegaValue.Str("myStyle"),
      style("""{$data,"mark":"bar","view":{"style":"myStyle"},$positions}"""),
    )
  }

  /** Several of them, later ones overriding earlier ones, which is Vega's own rule. */
  @Test
  fun `a view block may name several styles`() {
    assertEquals(
      VegaJson.parse("""["myStyle","mySecondStyle"]"""),
      style("""{$data,"mark":"bar","view":{"style":["myStyle","mySecondStyle"]},$positions}"""),
    )
  }

  /** The rest of the block still paints the plotting area; only the style is taken out of it. */
  @Test
  fun `a style beside a fill leaves the fill on the plotting area`() {
    val spec = """{$data,"mark":"bar","view":{"style":"myStyle","fill":"red"},$positions}"""
    assertEquals(VegaValue.Str("myStyle"), style(spec))
    assertEquals(
      VegaJson.parse("""{"update":{"fill":{"value":"red"}}}"""),
      compiled(spec).fields["encode"],
    )
  }

  /** With nothing named, a Cartesian plotting area is a `cell`. */
  @Test
  fun `a plot that names no style is a cell`() {
    assertEquals(VegaValue.Str("cell"), style("""{$data,"mark":"bar",$positions}"""))
  }

  /** And a chart with no Cartesian position has no plotting area to border. */
  @Test
  fun `a chart with no position is a view`() {
    assertEquals(
      VegaValue.Str("view"),
      style("""{$data,"mark":"arc","encoding":{"theta":{"field":"b","type":"quantitative"}}}"""),
    )
  }

  /** A layer unions what its members asked for. */
  @Test
  fun `a layer unions its members styles`() {
    assertEquals(
      VegaJson.parse("""["childStyle","cell"]"""),
      style(
        """{$data,"layer":[{"mark":"bar","view":{"style":"childStyle"},$positions},
           {"mark":"point",$positions}]}"""
      ),
    )
  }

  /**
   * A layer's own block is not asked, `LayerModel.assembleGroupStyle` reading its children and
   * nothing else. Reproduced rather than repaired: it is upstream's answer.
   */
  @Test
  fun `a layer's own view style is not read`() {
    assertEquals(
      VegaValue.Str("cell"),
      style("""{$data,"view":{"style":"layerStyle"},"layer":[{"mark":"bar",$positions}]}"""),
    )
  }

  /** A cell of a trellis is styled by the same rule, several styles included. */
  @Test
  fun `a cell may name several styles`() {
    assertEquals(
      VegaJson.parse("""["myStyle","mySecondStyle"]"""),
      cellStyle(
        """{$data,"facet":{"field":"d","type":"nominal"},
           "spec":{"mark":"bar","view":{"style":["myStyle","mySecondStyle"]},$positions}}"""
      ),
    )
  }
}
