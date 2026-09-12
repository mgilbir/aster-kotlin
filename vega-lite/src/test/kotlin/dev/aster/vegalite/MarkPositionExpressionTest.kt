package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A position stated on the **mark** is a value ref like any other, expression and all.
 *
 * ```js
 * export function signalOrValueRef(value: any) {
 *   if (isExprRef(value)) {
 *     const {expr, ...rest} = value;
 *     return {signal: expr, ...rest};
 *   }
 *   ...
 * ```
 *
 * `{"mark": {"type": "bar", "x": {"expr": "childWidth + 5"}}}` places a bar relative to a size the
 * chart computes — five units past the plot it stands beside — and the expression is a **signal**.
 * Every other mark property already went through this compiler's own `literalRef`; the two
 * positions did not, so such a mark was handed an object where Vega wants a number and drawn at
 * nothing at all.
 *
 * The other two forms are unchanged: a number is a value, and the words `width` and `height` are a
 * reference to the enclosing group's own size.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class MarkPositionExpressionTest {

  /** What the mark's own encode block says about where it sits and how wide it is. */
  private fun placed(mark: String): String {
    val spec =
      """{"data":{"values":[{"a":1,"b":2}]},"mark":$mark,
         "encoding":{"y":{"field":"b","type":"quantitative"}}}"""
    val compiled =
      VegaJson.parse(requireNotNull(VegaLiteCompiler().compileJson(spec).toJson()) { "no output" })
        as VegaValue.Obj
    fun find(marks: List<VegaValue>): VegaValue.Obj? {
      for (entry in marks) {
        entry as VegaValue.Obj
        if (entry.obj("encode") != null) return entry
        find((entry.fields["marks"] as? VegaValue.Arr)?.values.orEmpty())?.let {
          return it
        }
      }
      return null
    }
    val update =
      find((compiled.fields["marks"] as VegaValue.Arr).values)?.obj("encode")?.obj("update")
    return listOf("x", "xc", "width")
      .mapNotNull { key ->
        update?.fields?.get(key)?.let {
          "$key=${VegaJson.write(it).replace(Regex("""\n\s*"""), "")}"
        }
      }
      .joinToString(",")
  }

  /** The reported shape: a bar placed by an expression over a computed size. */
  @Test
  fun `a position written as an expression is a signal`() {
    assertEquals(
      """xc={"signal": "width + 5"},width={"value": 10}""",
      placed("""{"type":"bar","x":{"expr":"width + 5"},"width":10}"""),
    )
  }

  /** A number is a value, as it always was. */
  @Test
  fun `a position written as a number is a value`() {
    assertEquals(
      """xc={"value": 20},width={"value": 10}""",
      placed("""{"type":"bar","x":20,"width":10}"""),
    )
  }

  /** And the word `width` is the enclosing group's own size. */
  @Test
  fun `a position written as width is the group's`() {
    assertEquals(
      """xc={"field": {"group": "width"}},width={"value": 10}""",
      placed("""{"type":"bar","x":"width","width":10}"""),
    )
  }
}
