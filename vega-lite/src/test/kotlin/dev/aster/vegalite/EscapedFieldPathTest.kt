package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A column whose name holds a dot is written with that dot **escaped**.
 *
 * ```js
 * export function assembleProjection(proj: SelectionProjection) {
 *   const {signals, hasLegend, index, ...rest} = proj;
 *   rest.field = replacePathInField(rest.field);
 *   return rest;
 * }
 * ```
 * ```js
 * transforms.push({
 *   field: replacePathInField(field),
 *   type: 'timeunit',
 * ```
 *
 * `replacePathInField` splits the access path, escapes what is inside each step and joins them with
 * an escaped dot, because Vega reads an unescaped one as a step into a nested object. A column
 * called `properties.NAME` is a **name** with a dot in it, not a path into `properties` — a GeoJSON
 * feature's flattened property is the usual way to get one — and written unescaped it tells Vega to
 * look a level in and find nothing.
 *
 * Two places wrote it bare: the field a selection remembers a value by, where the store then
 * compares against a value no row has and nothing ever matches; and the field a `timeunit`
 * transform buckets, where the bucket is cut from undefined on every row. One specification in the
 * wild corpus picks countries out of a map that way.
 *
 * `vgField` already escaped it everywhere else, which is why the encoding reads the column
 * correctly in the same chart.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class EscapedFieldPathTest {

  private fun compiled(spec: String) =
    VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
      as VegaValue.Obj

  private fun written(value: VegaValue?) =
    VegaJson.write(value ?: VegaValue.Null).replace(Regex("""\n\s*"""), "")

  /** The reported shape: a selection projected onto a column whose name holds a dot. */
  @Test
  fun `a selection remembers a dotted column by its escaped name`() {
    val chart =
      compiled(
        """{"data":{"values":[{"a.b":1,"c":2}]},"mark":"point",
           "encoding":{"x":{"field":"a.b","type":"quantitative"},
                       "y":{"field":"c","type":"quantitative"}},
           "params":[{"name":"pick","select":{"type":"point","fields":["a.b"]}}]}"""
      )
    val fields =
      (chart.fields["signals"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first {
          it.string("name") == "pick_tuple_fields"
        }
    assertEquals(
      """{"name": "pick_tuple_fields","value": [{"field": "a\\.b","type": "E"}]}""",
      written(fields),
    )
  }

  /** The same name in a **stored** initial value, which is the other half of the store. */
  @Test
  fun `an initial value records the escaped name too`() {
    val chart =
      compiled(
        """{"data":{"values":[{"a.b":1,"c":2}]},"mark":"point",
           "encoding":{"x":{"field":"a.b","type":"quantitative"},
                       "y":{"field":"c","type":"quantitative"}},
           "params":[{"name":"pick","value":[{"a.b":1}],
             "select":{"type":"point","fields":["a.b"]}}]}"""
      )
    val store =
      (chart.fields["data"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first {
          it.string("name") == "pick_store"
        }
    assertEquals(
      """{"name": "pick_store","values": [{"unit": "","fields": [{"field": "a\\.b",""" +
        """"type": "E"}],"values": [1]}]}""",
      written(store),
    )
  }

  /** And the column a `timeunit` buckets, which is read off the row the same way. */
  @Test
  fun `a time unit buckets a dotted column by its escaped name`() {
    val chart =
      compiled(
        """{"data":{"values":[{"t.s":"2020-01-01","c":2}]},"mark":"point",
           "encoding":{"x":{"field":"t.s","type":"temporal","timeUnit":"year"},
                       "y":{"field":"c","type":"quantitative"}}}"""
      )
    val step =
      (chart.fields["data"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .flatMap { (it.fields["transform"] as? VegaValue.Arr)?.values.orEmpty() }
        .map { it as VegaValue.Obj }
        .first { it.string("type") == "timeunit" }
    assertEquals(
      """{"type": "timeunit","field": "t\\.s","units": ["year"],""" +
        """"as": ["year_t.s","year_t.s_end"]}""",
      written(step),
    )
  }

  /** A plain name is untouched, which is every other chart. */
  @Test
  fun `a plain column is written as it stands`() {
    val chart =
      compiled(
        """{"data":{"values":[{"a":1,"c":2}]},"mark":"point",
           "encoding":{"x":{"field":"a","type":"quantitative"},
                       "y":{"field":"c","type":"quantitative"}},
           "params":[{"name":"pick","select":{"type":"point","fields":["a"]}}]}"""
      )
    val fields =
      (chart.fields["signals"] as VegaValue.Arr)
        .values
        .map { it as VegaValue.Obj }
        .first {
          it.string("name") == "pick_tuple_fields"
        }
    assertEquals(
      """{"name": "pick_tuple_fields","value": [{"field": "a","type": "E"}]}""",
      written(fields),
    )
  }
}
