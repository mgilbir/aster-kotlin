package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A selection told what it **starts with** and nothing else is projected onto whatever that names.
 *
 * ```js
 * // If no explicit projection (either fields or encodings) is specified, set some defaults.
 * // If an initial value is set, try to infer projections.
 * if (!fields && !encodings && init) {
 *   for (const initVal of init) {
 *     if (!isObject(initVal)) { continue; }
 *     for (const key of keys(initVal)) {
 *       if (isSingleDefUnitChannel(key)) { (encodings ||= []).push(key); }
 *       else { (fields ??= []).push(key); }
 *     }
 *   }
 * }
 * ```
 *
 * A slider bound to `maxReported` remembers a `maxReported`, and a click started at `{"x": 5}`
 * remembers the column `x` is drawn from. With neither read, such a selection fell back to
 * remembering rows by **identity**: it had no field signal for the control to write into, no
 * `tuple_fields` to say what it stored, and a store that began empty however the specification had
 * started it — so a chart whose sliders were meant to filter it from the first frame filtered
 * nothing until the reader moved one. Two specifications in the wild corpus are written that way.
 *
 * A **scalar** starting value is not a projection: it is the identity of a row, and `isObject`
 * passes over it.
 *
 * An **interval** is the same rule seen from the other side: a brush started over a range of `y` is
 * dragged along `y` alone, where without this it was projected onto both positions and opened as a
 * rectangle. A key that names no channel is refused there and the two positions are used instead.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class SelectionInitProjectionTest {

  /** The chart's signals, and the store the selection opens with. */
  private fun selection(param: String): String {
    val spec =
      """{"data":{"values":[{"a":1,"b":2,"c":"x"}]},"mark":"point",
         "encoding":{"x":{"field":"a","type":"quantitative"},
                     "y":{"field":"b","type":"quantitative"}},
         "params":[$param]}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    lastCompiled = compiled
    val signals =
      (compiled.fields["signals"] as VegaValue.Arr).values.joinToString(",") {
        (it as VegaValue.Obj).string("name").orEmpty()
      }
    val store =
      (compiled.fields["data"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first { it.string("name") == "p_store" }
    return "$signals | ${VegaJson.write(store).replace(Regex("""\n\s*"""), "")}"
  }

  /** The reported shape: a slider bound to a column the selection was started at. */
  @Test
  fun `a starting value naming a column is projected onto that column`() {
    assertEquals(
      "unit,p_lim,p,p_tuple,p_tuple_fields,p_modify | " +
        """{"name": "p_store","values": [{"unit": "","fields": [{"field": "lim",""" +
        """"type": "E"}],"values": [5]}]}""",
      selection(
        """{"name":"p","value":{"lim":5},
           "bind":{"lim":{"input":"range","min":0,"max":10}},"select":{"type":"point"}}"""
      ),
    )
  }

  /** A key that names a **channel** is projected through that channel's own column. */
  @Test
  fun `a starting value naming a channel is projected through it`() {
    assertEquals(
      "unit,p,p_tuple,p_tuple_fields,p_toggle,p_modify | " +
        """{"name": "p_store","values": [{"unit": "","fields": [{"field": "a",""" +
        """"channel": "x","type": "E"}],"values": [5]}]}""",
      selection("""{"name":"p","value":{"x":5},"select":{"type":"point"}}"""),
    )
  }

  /** Every key of every row, which is one projection each. */
  @Test
  fun `a list of starting rows is projected onto all their columns`() {
    assertEquals(
      "unit,p,p_tuple,p_tuple_fields,p_toggle,p_modify | " +
        """{"name": "p_store","values": [{"unit": "","fields": [{"field": "lim",""" +
        """"type": "E"},{"field": "other","type": "E"}],"values": [5,null]},{"unit": "",""" +
        """"fields": [{"field": "lim","type": "E"},{"field": "other","type": "E"}],""" +
        """"values": [null,1]}]}""",
      selection("""{"name":"p","value":[{"lim":5},{"other":1}],"select":{"type":"point"}}"""),
    )
  }

  /** A projection the specification **states** is the projection: the value says nothing more. */
  @Test
  fun `a stated projection is not inferred over`() {
    assertEquals(
      "unit,p,p_tuple,p_tuple_fields,p_toggle,p_modify | " +
        """{"name": "p_store","values": [{"unit": "","fields": [{"field": "c",""" +
        """"type": "E"}],"values": [null]}]}""",
      selection("""{"name":"p","value":{"lim":5},"select":{"type":"point","fields":["c"]}}"""),
    )
  }

  /**
   * A **scalar** is the identity of a row and names no column, so there is nothing to infer — and
   * the row it names is what the store opens with, written as that identity.
   */
  @Test
  fun `a scalar starting value is no projection`() {
    assertEquals(
      "unit,p,p_tuple,p_toggle,p_modify | " +
        """{"name": "p_store","values": [{"unit": "","_vgsid_": 7}],""" +
        """"transform": [{"type": "collect","sort": {"field": "_vgsid_"}}]}""",
      selection("""{"name":"p","value":7,"select":{"type":"point"}}"""),
    )
  }

  /** And a selection with no starting value at all remembers rows by identity, as it always did. */
  @Test
  fun `a selection with no starting value is unchanged`() {
    assertEquals(
      "unit,p,p_tuple,p_toggle,p_modify | " +
        """{"name": "p_store","transform": [{"type": "collect","sort": """ +
        """{"field": "_vgsid_"}}]}""",
      selection("""{"name":"p","select":{"type":"point"}}"""),
    )
  }

  /**
   * An **interval** started along one channel is dragged along that channel alone: the same rule
   * reads its extent, and a brush that opens over a range of `y` has no `x` to its name. Without it
   * such a brush was projected onto both, so a chart that opened with a horizontal band selected
   * opened with a rectangle instead.
   */
  @Test
  fun `an interval's starting extent says which channel it is dragged along`() {
    assertEquals(
      "unit,p,p_y,p_b,p_scale_trigger,p_tuple,p_tuple_fields,p_translate_anchor," +
        "p_translate_delta,p_zoom_anchor,p_zoom_delta,p_modify | " +
        """{"name": "p_store","values": [{"unit": "","fields": [{"field": "b",""" +
        """"channel": "y","type": "R"}],"values": [[1,2]]}]}""",
      selection("""{"name":"p","value":{"y":[1,2]},"select":{"type":"interval"}}"""),
    )
  }

  /**
   * A key that names **no** channel is refused on an interval — upstream says so and warns, which
   * this compiler does not yet — and the two position channels are dragged instead.
   */
  @Test
  fun `an interval started at no channel is dragged along both`() {
    assertEquals(
      "unit,p,p_x,p_a,p_y,p_b,p_scale_trigger,p_tuple,p_tuple_fields,p_translate_anchor," +
        "p_translate_delta,p_zoom_anchor,p_zoom_delta,p_modify | " +
        """{"name": "p_tuple_fields","value": [{"field": "a","channel": "x","type": "R"},""" +
        """{"field": "b","channel": "y","type": "R"}]}""",
      selection("""{"name":"p","value":{"lim":[1,2]},"select":{"type":"interval"}}""")
        .substringBefore(" | ") + " | " + tupleFields(),
    )
  }

  /** The last chart compiled, read back for its `_tuple_fields` signal. */
  private var lastCompiled: VegaValue.Obj? = null

  private fun tupleFields(): String =
    VegaJson.write(
        (lastCompiled!!.fields["signals"] as VegaValue.Arr)
          .values
          .map { it as VegaValue.Obj }
          .first { it.string("name") == "p_tuple_fields" }
      )
      .replace(Regex("""\n\s*"""), "")
}
