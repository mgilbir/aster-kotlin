package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * What a selection **opens with** is a row already in its store, written the way that store is.
 *
 * ```js
 * init.values = selCmpt.project.hasSelectionId
 *   ? selCmpt.init.map((v) => ({unit: unitName(...), [SELECTION_ID]: assembleInit(v, false)[0]}))
 *   : selCmpt.init.map((v) => ({unit: ..., fields, values: assembleInit(v, false)}));
 * ```
 * ```js
 * export function assembleInit(init, isExpr = true, wrap = identity) {
 *   if (isArray(init)) { const assembled = init.map((v) => assembleInit(v, isExpr, wrap));
 *     return isExpr ? `[${assembled.join(', ')}]` : assembled; }
 *   if (isDateTime(init)) { return wrap(isExpr ? dateTimeToExpr(init) : dateTimeToTimestamp(init)); }
 *   return isExpr ? wrap(stringify(init)) : init;
 * }
 * ```
 *
 * Two arms of that were missing here.
 *
 * A selection that remembers rows **by identity** and was told which rows to start with says so the
 * same way its store will — one row per identity, with no projection to name. Left out, such a
 * chart opened with nothing picked however the specification had started it.
 *
 * And `assembleInit` maps a **list** element by element and hands anything else back as it stands,
 * so a channel a brush's extent says nothing about is `null` rather than an empty extent. An empty
 * one is a brush of no width, which filters every row out; a null is the absence Vega reads as "not
 * brushed along this channel". A brush started along one position and projected onto both opened
 * showing nothing at all.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class SelectionStoreInitTest {

  /** The store the selection opens with. */
  private fun store(param: String): String {
    val spec =
      """{"data":{"values":[{"a":1,"b":2}]},"mark":"point",
         "encoding":{"x":{"field":"a","type":"quantitative"},
                     "y":{"field":"b","type":"quantitative"}},
         "params":[$param]}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    val store =
      (compiled.fields["data"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first { it.string("name") == "p_store" }
    return VegaJson.write(store).replace(Regex("""\n\s*"""), "")
  }

  /** The reported shape: a brush started along one position and dragged along two. */
  @Test
  fun `a channel the extent says nothing about is not brushed`() {
    assertEquals(
      """{"name": "p_store","values": [{"unit": "","fields": [{"field": "a","channel": "x",""" +
        """"type": "R"},{"field": "b","channel": "y","type": "R"}],""" +
        """"values": [[1,2],null]}]}""",
      store(
        """{"name":"p","value":{"x":[1,2]},"select":{"type":"interval","encodings":["x","y"]}}"""
      ),
    )
  }

  /** A channel given something that is not a list keeps it as it stands. */
  @Test
  fun `a channel given a bare value keeps it`() {
    assertEquals(
      """{"name": "p_store","values": [{"unit": "","fields": [{"field": "a","channel": "x",""" +
        """"type": "R"}],"values": [5]}]}""",
      store("""{"name":"p","value":{"x":5},"select":{"type":"interval","encodings":["x"]}}"""),
    )
  }

  /** A selection that remembers rows by **identity** opens with the row it was told to. */
  @Test
  fun `an identity selection opens with the row it was given`() {
    assertEquals(
      """{"name": "p_store","values": [{"unit": "","_vgsid_": 7}],""" +
        """"transform": [{"type": "collect","sort": {"field": "_vgsid_"}}]}""",
      store("""{"name":"p","value":7,"select":{"type":"point"}}"""),
    )
  }

  /** One row per identity, which is what a list of them is. */
  @Test
  fun `an identity selection opens with every row it was given`() {
    assertEquals(
      """{"name": "p_store","values": [{"unit": "","_vgsid_": 7},{"unit": "","_vgsid_": 9}],""" +
        """"transform": [{"type": "collect","sort": {"field": "_vgsid_"}}]}""",
      store("""{"name":"p","value":[7,9],"select":{"type":"point"}}"""),
    )
  }

  /** And one told nothing opens empty, which is every other selection. */
  @Test
  fun `an identity selection with no starting row opens empty`() {
    assertEquals(
      """{"name": "p_store","transform": [{"type": "collect","sort": {"field": "_vgsid_"}}]}""",
      store("""{"name":"p","select":{"type":"point"}}"""),
    )
  }
}
