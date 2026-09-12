package dev.aster.vegalite

import dev.aster.vega.model.VegaJson
import dev.aster.vega.model.VegaValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * A tooltip's lines come out in the order **JavaScript** iterates an object's keys.
 *
 * `tooltipData` collects its lines into a plain object keyed by the caption:
 * ```js
 * const out: Dict<string> = {};
 * for (const {channel, key, value} of tuples) {
 *   if (!toSkip.has(channel) && !out[key]) { out[key] = value; }
 * }
 * return out;
 * ```
 *
 * and both the tooltip and the chart's description read it back with `entries(data)` — which is
 * `Object.keys`, whose order is *not* insertion order. A key that is the canonical decimal form of
 * an **array index** comes first, in ascending numeric order, and everything else follows in the
 * order it was written.
 *
 * A column called `2020` is such a key. So a chart of yearly columns describes itself starting with
 * the years, however its encoding was written — two specifications in the wild corpus differ for
 * exactly that, both of them tables with a year per column.
 *
 * `01`, `-1` and `1.5` are **not** array indices: a leading zero, a sign and a fraction each make
 * the key an ordinary string. That is what makes this a rule about the *canonical* form rather than
 * about looking numeric, and it is checked below.
 *
 * Every expectation was compiled with upstream rather than reasoned about.
 */
class JavaScriptKeyOrderTest {

  private fun captions(tooltip: String): List<String> {
    val compiled =
      VegaJson.parse(
        requireNotNull(
          VegaLiteCompiler()
            .compileJson(
              """
              {"data":{"values":[{"b":1,"2":2,"10":3,"a":4,"01":5,"-1":6,"1.5":7}]},
               "mark":"point",
               "encoding":{"x":{"field":"b","type":"quantitative"},"tooltip":$tooltip}}
              """
            )
            .toJson()
        ) {
          "did not compile"
        }
      ) as VegaValue.Obj
    val mark = (compiled.fields["marks"] as VegaValue.Arr).values.first() as VegaValue.Obj
    val signal = mark.obj("encode")?.obj("update")?.obj("tooltip")?.string("signal").orEmpty()
    return Regex("\"([^\"]+)\":").findAll(signal).map { it.groupValues[1] }.toList()
  }

  private fun measured(name: String) = """{"field":"$name","type":"quantitative"}"""

  /** The reported shape: columns named after years. */
  @Test
  fun `a numeric caption comes first`() {
    assertEquals(
      listOf("2", "10", "b", "a"),
      captions("[${measured("b")},${measured("2")},${measured("10")},${measured("a")}]"),
      "the indices ascending, then the rest as written",
    )
  }

  /** Ascending **numerically**, not as text — which is where `10` before `2` would show. */
  @Test
  fun `numeric captions are ordered by value`() {
    assertEquals(listOf("2", "10"), captions("[${measured("10")},${measured("2")}]"))
  }

  /** A key that only looks numeric is an ordinary string and keeps its place. */
  @Test
  fun `a key that is not a canonical index keeps its place`() {
    assertEquals(
      listOf("2", "b", "01", "-1", "1.5"),
      captions(
        "[${measured("b")},${measured("01")},${measured("-1")},${measured("1.5")},${measured("2")}]"
      ),
    )
  }

  /** The **caption** is the key, so a numeric title sorts even where the column's name does not. */
  @Test
  fun `a numeric title sorts as an index`() {
    assertEquals(
      listOf("3", "9", "z"),
      captions(
        """
        [{"field":"b","type":"quantitative","title":"9"},
         {"field":"a","type":"quantitative","title":"z"},
         {"field":"2","type":"quantitative","title":"3"}]
        """
      ),
    )
  }
}
