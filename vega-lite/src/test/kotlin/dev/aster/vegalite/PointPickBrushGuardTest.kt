package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * A click on a brush is not a pick, and **every** interval selection's brush is guarded against.
 *
 * ```js
 * const brushes = vals(model.component.selection ?? {})
 *   .reduce((acc, cmpt) => (cmpt.type === 'interval' ? acc.concat(cmpt.name + BRUSH) : acc), [])
 *   .map((b) => `indexof(item().mark.name, '${b}') < 0`)
 *   .join(' && ');
 * ```
 *
 * Upstream asks a selection only its **type**. This also asked what it was bound to and excluded a
 * scale-bound interval, on the reasoning that such a selection draws no brush and so has no
 * rectangle to click. The reasoning is sound and the conclusion is still wrong: `indexof` on a name
 * nothing carries is always less than zero, so the guard costs nothing, and upstream writes it. Six
 * specifications in the wild corpus pan their axes while picking points — `{"type": "interval",
 * "bind": "scales"}` beside a point selection is the ordinary way to write that — and disagreed on
 * the one signal that does the picking.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class PointPickBrushGuardTest {

  private fun pickUpdate(params: String): String {
    val spec =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"a":1,"b":2}]},"mark":"point","params":[$params],
               "encoding":{"x":{"field":"a","type":"quantitative"},
                           "y":{"field":"b","type":"quantitative"}}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val signal =
      (spec.fields["signals"] as VegaValue.Arr)
        .values
        .mapNotNull { it as? VegaValue.Obj }
        .first { (it.fields["name"] as? VegaValue.Str)?.value == "p_tuple" }
    val first = ((signal.fields["on"] as VegaValue.Arr).values.first() as VegaValue.Obj)
    return (first.fields["update"] as VegaValue.Str).value
  }

  private fun guards(update: String): List<String> =
    Regex("""indexof\(item\(\)\.mark\.name, '([^']+)'\) < 0""")
      .findAll(update)
      .map { it.groupValues[1] }
      .toList()

  private val point = """{"name":"p","select":"point"}"""

  /** The reported shape: an interval bound to the scales, which draws no brush of its own. */
  @Test
  fun `a scale-bound interval's brush is guarded against`() {
    val update = pickUpdate("""$point,{"name":"g","select":"interval","bind":"scales"}""")
    assertEquals(listOf("g_brush"), guards(update))
  }

  /** A plain interval's is too, which is the case that always worked. */
  @Test
  fun `a plain interval's brush is guarded against`() {
    assertEquals(
      listOf("g_brush"),
      guards(pickUpdate("""$point,{"name":"g","select":"interval"}""")),
    )
  }

  /** Both of them, in the order the selections were written, joined into the one test. */
  @Test
  fun `every interval contributes a guard in order`() {
    val update =
      pickUpdate(
        """$point,{"name":"g","select":"interval"},
           {"name":"h","select":"interval","bind":"scales"}"""
      )
    assertEquals(listOf("g_brush", "h_brush"), guards(update))
  }

  /** With no interval anywhere there is nothing to guard against, and nothing is written. */
  @Test
  fun `a point selection alone gets no guard`() {
    val update = pickUpdate(point)
    assertEquals(emptyList<String>(), guards(update))
    assertFalse(update.contains("mark.name"), "and the clause is left out rather than left empty")
  }

  /** The two guards that are always there, so the shape of the whole test is on the record. */
  @Test
  fun `the group and legend guards stand either way`() {
    val update = pickUpdate("""$point,{"name":"g","select":"interval","bind":"scales"}""")
    assertEquals(
      "datum && item().mark.marktype !== 'group' && " +
        "indexof(item().mark.role, 'legend') < 0 && " +
        "indexof(item().mark.name, 'g_brush') < 0",
      update.substringBefore(" ? "),
    )
  }
}
