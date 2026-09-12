package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A row a selection **opens with** says which cell of the grid it was picked in.
 *
 * ```js
 * export function unitName(model: Model, {escape} = {escape: true}) {
 *   let name = escape ? stringValue(model.name) : model.name;
 *   const facetModel = getFacetModel(model);
 *   if (facetModel) {
 *     const {facet} = facetModel;
 *     for (const channel of FACET_CHANNELS) {
 *       if (facet[channel]) {
 *         name += ` + '__facet_${channel}_' + (facet[${stringValue(facetModel.vgField(channel))}])`;
 *       }
 *     }
 *   }
 *   return name;
 * }
 * ```
 *
 * Inside a grid the unit is not a name but the cell's name and the values that cell holds, since
 * every cell is the same model drawn once per value. This engine wrote the declaring view's plain
 * name into the store, so a trellis opening with one of its cells brushed had that row belong to no
 * cell at all — and every test of the selection compared it against a unit that never matched. Four
 * specifications in the wild corpus open that way.
 *
 * The store's copy is the one place the name is **not** quoted: a row already in the store is data,
 * where every other use of the name is spelled into an expression a signal computes.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class StoreUnitNameTest {

  private fun store(spec: String): VegaValue? {
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    return (compiled.fields["data"] as VegaValue.Arr)
      .values
      .map { it as VegaValue.Obj }
      .first { it.string("name")?.endsWith("_store") == true }
      .fields["values"]
  }

  private val data = """"data":{"values":[{"a":1,"c":"x","r":"p","name":"n"}]}"""
  private val picked =
    """{"name":"sel","select":{"type":"point","fields":["c"]},"value":[{"c":"x"}]}"""
  private val plot = """"mark":"point","encoding":{"x":{"field":"a","type":"quantitative"}}"""

  private fun json(text: String) = VegaJson.parse(text)

  /** The reported shape: a wrapped trellis, whose one channel is called `facet`. */
  @Test
  fun `a row picked in a wrapped grid names the cell it was picked in`() {
    assertEquals(
      json(
        """[{"unit":"child + '__facet_facet_' + (facet[\"name\"])",
            "fields":[{"type":"E","field":"c"}],"values":["x"]}]"""
      ),
      store(
        """{$data,"facet":{"field":"name","type":"nominal"},"columns":2,
           "spec":{"params":[$picked],$plot}}"""
      ),
    )
  }

  /** A crossed grid names both of its channels, row before column. */
  @Test
  fun `a row picked in a crossed grid names both channels`() {
    assertEquals(
      json(
        """[{"unit":"child + '__facet_row_' + (facet[\"r\"]) + '__facet_column_' + (facet[\"c\"])",
            "fields":[{"type":"E","field":"c"}],"values":["x"]}]"""
      ),
      store(
        """{$data,"facet":{"row":{"field":"r","type":"nominal"},
                           "column":{"field":"c","type":"nominal"}},
           "spec":{"params":[$picked],$plot}}"""
      ),
    )
  }

  /** With no grid there is one view, and its name is the empty one it has. */
  @Test
  fun `a row picked in a plain chart names no cell`() {
    assertEquals(
      json("""[{"unit":"","fields":[{"type":"E","field":"c"}],"values":["x"]}]"""),
      store("""{$data,"params":[$picked],$plot}"""),
    )
  }

  /** A brush's opening extent is a row of the store too, and says the same thing. */
  @Test
  fun `a brush opening in a wrapped grid names its cell`() {
    assertEquals(
      json(
        """[{"unit":"child + '__facet_facet_' + (facet[\"name\"])",
            "fields":[{"field":"a","channel":"x","type":"R"}],"values":[[0,5]]}]"""
      ),
      store(
        """{$data,"facet":{"field":"name","type":"nominal"},"columns":2,
           "spec":{"params":[{"name":"br","select":{"type":"interval","encodings":["x"]},
                              "value":{"x":[0,5]}}],$plot}}"""
      ),
    )
  }
}
